(ns swarmkhazad.investigation-test
  "The investigation lane: repro.md as a second bar source, a @cloud bar that
   answers on a Linear ticket instead of a PR, the skill and subagent generated
   into the task folder and into every worktree, the two-role lineup, and the
   MCP gateway.

   Each test here names the guard it watches, because test/mutants.edn points at
   these deftests by name and a rename that loses a mutant loses it silently."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (str (fs/cwd)))
(def scripts (str (fs/path repo-root "scripts")))
(def cli (str (fs/path scripts "swarmkhazad.bb")))

(defn run [{:keys [dir env ok? in]} & args]
  (let [opts (cond-> {:continue true :dir (str (or dir repo-root)) :extra-env (or env {})}
               in (assoc :in in))
        result (apply process/sh (concat [opts] args))]
    (when (and (not (false? ok?)) (not (zero? (:exit result))))
      (throw (ex-info (str "command failed: " (str/join " " args) "\n" (:out result) (:err result)) result)))
    result))

(defn git [dir & args]
  (str/trim (:out (apply run {:dir (str dir)} "git" args))))

(defn write! [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn make-source-repo!
  "A fixture checkout whose own test command PASSES.

   The bb.edn is load-bearing, not scenery. Without a detectable test command
   the `repo-tests` bar comes back `exit: none`, and `run_evidence.bb`'s
   `(every? #(= 0 (:exit %)) results)` is then false for every run of this
   fixture whatever the cloud bar did — so `(is (= 1 (:exit r)))` would report a
   behaviour nothing measured. With this, `repo-tests` is 0 and the pending bar
   is the only thing that can make the run non-green."
  [dir]
  (fs/create-dirs dir)
  (git dir "init" "-q" "-b" "main")
  (git dir "config" "user.email" "t@example.com")
  (git dir "config" "user.name" "T")
  (write! (fs/path dir "README.md") "one\n")
  (write! (fs/path dir "bb.edn") "{:tasks {test {:task (println \"fixture tests ran\")}}}\n")
  (git dir "add" ".")
  (git dir "commit" "-q" "-m" "one")
  (git dir "remote" "add" "origin" "https://github.com/MithraAI/istari.git")
  (git dir "update-ref" "refs/remotes/origin/main" (git dir "rev-parse" "HEAD")))

(defn headers [file]
  (into {} (for [line (take-while #(not= "--- output ---" %) (str/split-lines (slurp (str file))))
                 :let [[k v] (str/split line #": " 2)]
                 :when (and k v)]
             [k v])))

(defn output [file]
  (second (str/split (slurp (str file)) #"--- output ---\n" 2)))

(defn dispatched-brief
  "The prompt the stub `claude` was called with. The stub writes its argv
   NUL-separated, so the brief comes back whole rather than word-split."
  [dir]
  (nth (str/split (slurp (str (fs/path dir "claude-argv"))) #"\u0000") 3))

(defn stub-bin!
  "A PATH holding fake `claude` and `gh`, so a dispatch is observable without a
   real cloud session. `gh` exits non-zero, i.e. this branch has no PR — which
   is the whole point on this lane: the ticket is the return channel."
  [dir]
  (let [bin (fs/path dir "stub-bin")]
    (fs/create-dirs bin)
    (write! (fs/path bin "claude")
            (str "#!/usr/bin/env bash\n"
                 "printf '%s\\0' \"$@\" > " (str (fs/path dir "claude-argv")) "\n"
                 "echo 'Session ID: session_01TEST'\n"))
    (write! (fs/path bin "gh") "#!/usr/bin/env bash\nexit 1\n")
    (doseq [f ["claude" "gh"]]
      (fs/set-posix-file-permissions (fs/path bin f) "rwxr-xr-x"))
    (str bin)))

(defn with-task
  "An investigation task on one checkout, prepared. `f` gets the task dir, the
   source checkout, and a `measure` that runs run_evidence.bb as the run role."
  [f]
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-investigation."})
        home (str (fs/path sandbox "home"))
        src (str (fs/path sandbox "src" "istari"))
        id "t-inv"
        env {"SWARMKHAZAD_HOME" home "SWARMKHAZAD_TASK_ID" id}]
    (try
      (make-source-repo! src)
      (run {:env env} cli "new" id "--repo" src "--investigate")
      (let [dir (fs/path home "tasks" id)]
        (run {:env env} cli "prepare" id)
        (f {:dir dir :src src :env env :home home
            :measure (fn [& [extra-env]]
                       (run {:dir (str (fs/path dir "worktrees" "istari"))
                             ;; SWARMKHAZAD_CLOUD_ENV blank by default:
                             ;; :extra-env ADDS to the inherited environment, so
                             ;; an operator who exports it would otherwise have
                             ;; it leak into the case that says there is none.
                             :env (merge env
                                         {"SWARMKHAZAD_CLOUD_ENV" ""
                                          "SWARMKHAZAD_SESSION" "run"
                                          "SWARMKHAZAD_TASK_DIR" (str dir)}
                                         extra-env)
                             :ok? false}
                            "bb" (str (fs/path scripts "run_evidence.bb"))))}))
      (finally
        (fs/delete-tree sandbox)))))

;; ------------------------------------------------- repro.md as a bar source

(def repro-md
  (str "# repro\n\n"
       "## Quantitative\n"
       "- reproduces the stuck row — bar: exits non-zero while the row stays pending"
       " — measure: @cloud `bb -e '(System/exit 7)'`"
       " — ticket: MITH-1234\n"))

(deftest repro-md-is-a-second-bar-source-and-the-ticket-replaces-the-pull-request
  (with-task
    (fn [{:keys [dir measure]}]
      (let [bin (stub-bin! dir)]
        (write! (fs/path dir "repro.md") repro-md)
        (let [r (measure {"SWARMKHAZAD_CLOUD_ENV" "ccpool_TEST"
                          "PATH" (str bin ":" (System/getenv "PATH"))})
              f (fs/path dir "evidence" "reproduces-the-stuck-row.txt")]
          (testing "the bar in repro.md ran at all — metrics.md never mentioned it"
            (is (fs/regular-file? f)
                "metrics.md is locked r--r--r-- at open, so a repro bar the role wrote comes from repro.md or from nowhere"))
          (testing "and it dispatched, even though the branch has no PR"
            (is (= "pending" (get (headers f) "exit")))
            (is (fs/exists? (fs/path dir "claude-argv"))
                "a PR precondition here would refuse every investigation, whose deliverable is an RCA and never a PR")
            (is (= "0" (get (headers (fs/path dir "evidence" "repo-tests.txt")) "exit"))
                "the fixture's own suite passed, so the next assertion can only be about the cloud bar")
            (is (= 1 (:exit r)) "pending is not met, so the run is not green"))
          (testing "the brief tells the runner to answer on the ticket, and does not invent a PR"
            (let [brief (dispatched-brief dir)]
              (is (str/includes? brief "Ticket: MITH-1234"))
              (is (str/includes? brief "post ONE comment on Linear ticket MITH-1234"))
              (is (not (str/includes? brief "PR:")))
              (is (not (str/includes? brief "#")) "no PR number is fabricated anywhere in the brief")
              (is (str/includes? brief "bb -e '(System/exit 7)'"))
              (is (str/includes? brief "passes:  exits non-zero while the row stays pending")
                  "and the pass criterion travels with it — without it the runner is told to run something and say something")
              (is (str/includes? brief "State the OUTCOME")
                  "the label is set from this comment by a session that was not in the room")
              (is (not (str/includes? brief "@cloud")) "the marker is stripped before the runner sees it")))
          (testing "the evidence file names the ticket as the return channel"
            (is (str/includes? (output f) "ticket:  MITH-1234"))
            (is (str/includes? (output f) "PENDING, not met"))
            (is (str/includes? (output f) "the loop session polls that comment"))))))))

(deftest a-repro-bar-may-name-the-origin-and-branch-the-worktree-is-not-on
  (with-task
    (fn [{:keys [dir measure]}]
      (let [bin (stub-bin! dir)]
        ;; SSH form on purpose: the bar's own origin goes through the same
        ;; normalisation as the worktree's, or the owner parses as nil and a
        ;; correct line is refused with a message about the owner.
        (write! (fs/path dir "repro.md")
                (str "## Quantitative\n"
                     "- reproduces on release — bar: exits 7 — measure: @cloud `false`"
                     " — ticket: MITH-9 — origin: git@github.com:MithraAI/lothlorien.git"
                     " — branch: release-2\n"))
        (measure {"SWARMKHAZAD_CLOUD_ENV" "ccpool_TEST"
                  "PATH" (str bin ":" (System/getenv "PATH"))})
        (let [brief (dispatched-brief dir)
              f (fs/path dir "evidence" "reproduces-on-release.txt")]
          (testing "the bar's own origin and branch win over the worktree's, normalised the same way"
            (is (str/includes? brief "Repo:   https://github.com/MithraAI/lothlorien\n"))
            (is (not (str/includes? brief "git@")) "the SSH form is rewritten, not passed through")
            (is (not (str/includes? brief ".git")) "and the suffix is stripped")
            (is (str/includes? brief "Branch: release-2"))
            (is (not (str/includes? brief "istari"))
                "the worktree is istari on sk/t-inv, and a bug reproduced there proves nothing about the branch that has it")
            (is (not (str/includes? brief "sk/t-inv"))))
          (is (str/includes? (output f) "release-2")))))))

(deftest a-bar-with-neither-a-ticket-nor-a-pull-request-is-blocked-and-not-sent
  (with-task
    (fn [{:keys [dir measure]}]
      (let [bin (stub-bin! dir)]
        (write! (fs/path dir "repro.md")
                "## Quantitative\n- nowhere to answer — bar: x — measure: @cloud `false`\n")
        (measure {"SWARMKHAZAD_CLOUD_ENV" "ccpool_TEST"
                  "PATH" (str bin ":" (System/getenv "PATH"))})
        (let [f (fs/path dir "evidence" "nowhere-to-answer.txt")]
          (is (= "blocked" (get (headers f) "exit")))
          (is (str/includes? (output f) "no return channel"))
          (is (str/includes? (output f) "ticket:")
              "and it names the other channel, so an investigation is not left guessing")
          (is (not (fs/exists? (fs/path dir "claude-argv")))
              "a runner with nowhere to report is a run whose findings are lost, so it is refused here where the message is read"))))))

(defn blocked-because
  "Write a one-bar repro.md, measure it with a working cloud environment, and
   return the evidence file's exit plus its output. Nothing should reach the
   stub `claude` in any of the cases below."
  [dir measure bar-line]
  (let [bin (stub-bin! dir)]
    (write! (fs/path dir "repro.md") (str "## Quantitative\n" bar-line "\n"))
    (measure {"SWARMKHAZAD_CLOUD_ENV" "ccpool_TEST"
              "PATH" (str bin ":" (System/getenv "PATH"))})
    {:file (fs/path dir "evidence" "probe.txt")
     :dispatched? (fs/exists? (fs/path dir "claude-argv"))}))

(deftest an-origin-the-runner-cannot-reach-is-refused-by-the-whole-url-not-by-a-path-segment
  ;; The guard used to read "the 4th `/`-separated component is MithraAI", which
  ;; never looked at the HOST — so https://attacker.example/MithraAI/anything
  ;; satisfied it. `origin:` is authored by the investigate role into repro.md,
  ;; an unlocked file whose instructions come from a ticket's own text, and the
  ;; dispatch tells a credentialed cloud session to clone that URL and run it.
  (testing "a foreign host wearing MithraAI as a path segment"
    (with-task
      (fn [{:keys [dir measure]}]
        (let [{:keys [file dispatched?]}
              (blocked-because dir measure
                               "- probe — bar: x — measure: @cloud `false` — ticket: MITH-1 — origin: https://attacker.example/MithraAI/anything")]
          (is (= "blocked" (get (headers file) "exit")))
          (is (str/includes? (output file) "github.com/MithraAI"))
          (is (str/includes? (output file) "attacker.example")
              "and it names the origin it refused, so the reader is not guessing")
          (is (not dispatched?) "nothing was sent")))))
  (testing "a different owner on the right host"
    (with-task
      (fn [{:keys [dir measure]}]
        (let [{:keys [file dispatched?]}
              (blocked-because dir measure
                               "- probe — bar: x — measure: @cloud `false` — ticket: MITH-1 — origin: https://github.com/notmithra/x")]
          (is (= "blocked" (get (headers file) "exit")))
          (is (str/includes? (output file) "github.com/MithraAI"))
          (is (not dispatched?))))))
  (testing "and a prefix that merely ENDS in a reachable repo"
    (with-task
      (fn [{:keys [dir measure]}]
        (let [{:keys [file dispatched?]}
              (blocked-because dir measure
                               "- probe — bar: x — measure: @cloud `false` — ticket: MITH-1 — origin: https://evil.test/?u=https://github.com/MithraAI/istari")]
          (is (= "blocked" (get (headers file) "exit"))
              "the match is anchored, so nothing before the shape counts")
          (is (not dispatched?)))))))

(deftest a-branch-or-ticket-that-is-not-a-name-is-refused-before-it-reaches-the-runners-shell
  (testing "a branch name carrying shell punctuation"
    (with-task
      (fn [{:keys [dir measure]}]
        (let [{:keys [file dispatched?]}
              (blocked-because dir measure
                               "- probe — bar: x — measure: @cloud `false` — ticket: MITH-1 — branch: main; rm -rf /")]
          (is (= "blocked" (get (headers file) "exit")))
          (is (str/includes? (output file) "not a branch name"))
          (is (not dispatched?))))))
  (testing "a ticket that is not a Linear key"
    (with-task
      (fn [{:keys [dir measure]}]
        (let [{:keys [file dispatched?]}
              (blocked-because dir measure
                               "- probe — bar: x — measure: @cloud `false` — ticket: not a key at all")]
          (is (= "blocked" (get (headers file) "exit")))
          (is (str/includes? (output file) "not a Linear issue key"))
          (is (not dispatched?)
              "the ticket IS the return channel, so a malformed one loses the findings"))))))

(deftest a-repro-bar-sharing-a-name-with-a-metrics-bar-gets-its-own-evidence-file
  ;; The two sources are uniqued together. Uniqued separately, both would slug
  ;; to `flaky.txt` and the second writer would silently become the task's
  ;; answer for the first bar.
  (with-task
    (fn [{:keys [dir measure]}]
      (fs/set-posix-file-permissions (fs/path dir "metrics.md") "rw-r--r--")
      (write! (fs/path dir "metrics.md")
              "# t-inv — bars\n\n## Quantitative\n- flaky — bar: green — measure: `echo FROM-METRICS`\n")
      (write! (fs/path dir "repro.md")
              "## Quantitative\n- flaky — bar: reproduces — measure: `echo FROM-REPRO`\n")
      (measure)
      (let [ev (fs/path dir "evidence")]
        (is (= #{"repo-tests.txt" "flaky.txt" "flaky-2.txt"}
               (set (map (comp str fs/file-name) (fs/list-dir ev)))))
        (is (str/includes? (output (fs/path ev "flaky.txt")) "FROM-METRICS"))
        (is (str/includes? (output (fs/path ev "flaky-2.txt")) "FROM-REPRO"))))))

(defn in-process
  "Load a script and print an expression, with the task's environment. The
   portal and the summarizer are libraries here, not servers: their `-main`
   guards compare `*file*` against `babashka.file`, which `-e` does not set to
   either script."
  [env script expr]
  (let [r (run {:env env :ok? false} "bb" "-e"
               (str "(load-file \"" (fs/path scripts script) "\") " expr))]
    (str (:out r) (:err r))))

(deftest the-second-bar-source-reaches-the-page-and-the-merge-verdict-not-only-the-runner
  ;; A bar that is measured, writes evidence, and appears nowhere a human or the
  ;; verdict can see it is the worst outcome available: the acceptance criterion
  ;; is recorded and invisible, which is the exact failure `parse-bar` already
  ;; carries a comment about.
  (with-task
    (fn [{:keys [dir env]}]
      (write! (fs/path dir "repro.md") repro-md)
      (testing "the portal's bar table lists the repro bar beside the metrics ones"
        (let [out (in-process env "portal.bb"
                              "(prn (mapv (juxt :id :ticket) (portal/bars-with-evidence (task-lib/task-ctx \"t-inv\"))))")]
          (is (str/includes? out "reproduces-the-stuck-row")
              "passing metrics.md alone rendered a page that omitted a bar the runner had already measured")
          (is (str/includes? out "MITH-1234")
              "and the row carries its ticket, so the reader knows where the answer is coming back")))
      (testing "and the merge verdict is shown the bar whose evidence it is about to read"
        (let [out (in-process env "summary.bb"
                              "(print (summary/gather (task-lib/task-ctx \"t-inv\")))")]
          (is (str/includes? out "## repro.md"))
          (is (str/includes? out "reproduces the stuck row")
              "without this the verdict reads an evidence file for a bar whose threshold it was never given"))))))

(deftest a-cloud-only-field-on-a-local-bar-is-reported-rather-than-silently-ignored
  ;; `ticket:`/`origin:`/`branch:` are read by the @cloud tier and by nothing
  ;; else, so on a local bar they are a line the author believed and nothing
  ;; honoured — the same failure `parse-bar`'s docstring promises will not
  ;; happen, just with correct spelling.
  (with-task
    (fn [{:keys [dir measure]}]
      (write! (fs/path dir "repro.md")
              "## Quantitative\n- local — bar: green — measure: `echo ran` — ticket: MITH-1 — branch: main\n")
      (measure)
      (let [f (fs/path dir "evidence" "local.txt")]
        (is (= "0" (get (headers f) "exit")) "the bar still ran, and its result is still the answer")
        (is (str/includes? (output f) "ran"))
        (is (str/includes? (output f) "[notice] this bar names ticket, branch"))
        (is (str/includes? (output f) "only the @cloud tier reads"))))))

;; ------------------------------------------------- the generated agent home

(def skill-rel ".claude/skills/investigate/SKILL.md")
(def agent-rel ".claude/agents/investigation-hypothesis-tester.md")

(deftest a-re-prepare-neither-duplicates-the-excludes-nor-clobbers-a-claude-the-repo-owns
  ;; `open` is also the resume-after-reboot path, so this runs many times per
  ;; task — and it runs inside somebody else's repo, where `.claude/` is
  ;; frequently tracked. Destroying a checked-in `.claude/settings.json` to
  ;; install a generated skill would be the worst thing in this change.
  (with-task
    (fn [{:keys [dir src env]}]
      (let [wt (fs/path dir "worktrees" "istari")
            excl (fs/path src ".git" "info" "exclude")
            owned (fs/path wt ".claude" "settings.json")]
        (write! owned "{\"tracked\":\"by the repo\"}\n")
        (let [before (slurp (str excl))]
          (run {:env env} cli "prepare" "t-inv")
          (is (= "{\"tracked\":\"by the repo\"}\n" (slurp (str owned)))
              "the repo's own file survives — the copy merges into .claude, it does not replace it")
          (is (= before (slurp (str excl)))
              "and the exclude file is appended to once per pattern, not once per prepare")
          (doseq [p [skill-rel agent-rel]]
            (is (fs/regular-file? (fs/path wt p)) "while the generated copies are still there")))))))

(deftest a-file-the-target-repo-tracks-is-never-overwritten
  ;; `.claude/` is not ours. RAN: lothlorien tracks 80+ files under it,
  ;; `.claude/settings.json`, `.claude/hooks/*` and `.claude/skills/*/SKILL.md`
  ;; among them; minas-tirith tracks `.claude/agents/*.md`. Nothing collides
  ;; today, but a rename on either side would have the copy overwrite a
  ;; checked-in file — and `info/exclude` masks only UNTRACKED paths, so the
  ;; damage would surface as a modification in somebody's PR.
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-tracked."})
        home (str (fs/path sandbox "home"))
        src (str (fs/path sandbox "src" "istari"))
        env {"SWARMKHAZAD_HOME" home "SWARMKHAZAD_TASK_ID" "t-trk"}]
    (try
      (make-source-repo! src)
      (write! (fs/path src skill-rel) "THE REPO'S OWN SKILL\n")
      (git src "add" ".")
      (git src "-c" "user.email=t@e" "-c" "user.name=T" "commit" "-q" "-m" "own skill")
      (git src "update-ref" "refs/remotes/origin/main" (git src "rev-parse" "HEAD"))
      (run {:env env} cli "new" "t-trk" "--repo" src "--investigate")
      (let [r (run {:env env} cli "prepare" "t-trk")
            wt (fs/path home "tasks" "t-trk" "worktrees" "istari")]
        (is (= "THE REPO'S OWN SKILL\n" (slurp (str (fs/path wt skill-rel))))
            "the repo's own file is left exactly as it was")
        (is (str/includes? (str (:out r) (:err r)) "left alone")
            "and prepare says so, rather than differing from the operator's expectation in silence")
        (is (fs/regular-file? (fs/path wt agent-rel))
            "the file that does NOT collide is still installed")
        (is (= "" (git wt "status" "--porcelain"))
            "the checkout reads clean — a tracked file overwritten would show as a modification")
        (let [excl (slurp (str (fs/path src ".git" "info" "exclude")))]
          (is (not (str/includes? excl skill-rel))
              "and a tracked path is never excluded — that would hide the repo's own file from its owner")
          (is (str/includes? excl agent-rel))))
      (finally (fs/delete-tree sandbox)))))

(deftest the-skill-and-the-subagent-are-generated-into-the-task-folder-and-into-every-worktree
  (with-task
    (fn [{:keys [dir src]}]
      (testing "the task folder holds the canonical copy, beside prompts/ and hooks/"
        (doseq [p [skill-rel agent-rel]]
          (is (fs/regular-file? (fs/path dir p)) (str "task folder: " p))))
      (testing "and every worktree holds the copy a role can actually LOAD"
        ;; RAN: a spec at <task>/.claude/agents/x.md with cwd
        ;; <task>/worktrees/foo is NOT in the session's subagent list; the
        ;; identical file at <task>/worktrees/foo/.claude/agents/x.md is. A
        ;; parent directory's .claude is not a load path, so the task-folder
        ;; copy alone would generate two files nothing reads.
        (doseq [p [skill-rel agent-rel]]
          (is (fs/regular-file? (fs/path dir "worktrees" "istari" p))
              (str "worktree: " p))))
      (testing "the skill's egress is Linear, not a GitHub issue"
        (let [text (slurp (str (fs/path dir "worktrees" "istari" skill-rel)))]
          (is (str/includes? text "mcp__linear-server__create_comment"))
          (is (str/includes? text "Evidence: found"))
          (is (str/includes? text "repro.md"))
          (is (not (str/includes? text "swarm:spec-ready"))
              "khazad's label is not this workspace's")))
      (testing "the subagent still has no write tool of any kind"
        (let [front (slurp (str (fs/path dir "worktrees" "istari" agent-rel)))
              tools (second (re-find #"(?m)^tools:\s*(.*)$" front))]
          (is (some? tools))
          (doseq [w ["Write" "Edit" "MultiEdit" "NotebookEdit"]]
            (is (not (str/includes? tools w))
                (str w " would let the tester write the record it is supposed to report")))
          (is (str/includes? tools "mcp__logfire__")
              "and it holds telemetry the parent is denied — that asymmetry is the whole point")))
      (testing "git in the target checkout cannot see either file"
        ;; Otherwise a role running `git add -A` commits swarm scaffolding into
        ;; istari. Exact file paths, not `.claude/`, because info/exclude is
        ;; shared with the operator's own checkout of the same repo.
        (is (= "" (git (fs/path dir "worktrees" "istari") "status" "--porcelain"))
            "the worktree reads clean with the agent home written into it")
        (let [excl (slurp (str (fs/path src ".git" "info" "exclude")))]
          (doseq [p [skill-rel agent-rel]] (is (str/includes? excl p)))
          (is (not (some #{".claude/"} (map str/trim (str/split-lines excl))))
              "a directory exclude would hide a .claude file the operator wrote by hand"))))))

;; ------------------------------------------------------------ the lineup

(deftest the-investigation-lineup-denies-the-parent-its-telemetry-and-quotes-nothing
  (with-task
    (fn [{:keys [dir env]}]
      (let [roles (slurp (str (fs/path dir "roles")))
            tsv (->> (slurp (str (fs/path dir "state" "sessions.tsv")))
                     str/split-lines
                     (remove str/blank?)
                     (mapv #(str/split % #"\t" -1)))]
        (testing "two roles, investigate then run, and prepare accepted both"
          (is (= ["investigate" "run"] (mapv first tsv)))
          (is (= 2 (count tsv))))
        (testing "the model is on the argv, because model= names a vendor"
          ;; `model=opus` fails at prepare with `unknown model vendor opus`.
          (is (str/includes? roles "--model opus"))
          (is (str/includes? roles "--model haiku"))
          (is (not (re-find #"model=(opus|haiku|sonnet)" roles)))
          (is (= "anthropic" (nth (first tsv) 6)) "the vendor is the operator's own login"))
        (testing "the telemetry MCPs are denied to the investigate role and to no other"
          (let [extra (nth (first tsv) 7)]
            (is (str/includes? extra "--disallowedTools"))
            (doseq [server ["mcp__logfire__*" "mcp__datadog-mcp__*" "mcp__argocd__*"]]
              (is (str/includes? extra server)))
            (is (not (str/includes? extra "\""))
                "the line is whitespace-split twice and each token becomes one argv slot, so a quoted list reaches the CLI with its quotes and denies nothing"))
          (is (not (str/includes? (str (nth (second tsv) 7)) "disallowedTools"))
              "run has no telemetry to deny and dispatching is its whole job"))
        (testing "a task opened this way still refuses to be prepared with no checkout"
          ;; RAN, and it is why the ticket's repo: label has to name a real one.
          (spit (str (fs/path dir "repos")) "# nothing\n")
          (let [r (run {:env env :ok? false} cli "prepare" "t-inv")]
            (is (= 1 (:exit r)))
            (is (str/includes? (str (:out r) (:err r)) "repos declaration is empty"))))))))

;; --------------------------------------------------------- the MCP gateway

(defn rpc
  "Feed the gateway a batch of frames; return the parsed replies in order.
   A String frame is sent verbatim, so a malformed line can be tested."
  [& frames]
  (let [r (run {:in (str/join "\n" (map #(if (string? %) % (json/generate-string %)) frames)) :ok? false}
               "bb" (str (fs/path scripts "mcp_gateway.bb")))]
    (mapv #(json/parse-string % true) (remove str/blank? (str/split-lines (:out r))))))

(deftest the-gateway-exposes-one-tool-validates-the-key-and-never-answers-a-notification
  (let [replies (rpc {:jsonrpc "2.0" :id 1 :method "initialize" :params {}}
                     {:jsonrpc "2.0" :method "notifications/initialized"}
                     {:jsonrpc "2.0" :id 2 :method "tools/list"}
                     {:jsonrpc "2.0" :id 3 :method "tools/call"
                      :params {:name "add_task" :arguments {:issue_key "; rm -rf /"}}}
                     {:jsonrpc "2.0" :id 4 :method "tools/call"
                      :params {:name "close" :arguments {}}}
                     {:jsonrpc "2.0" :id 5 :method "tools/call"
                      :params {:name "add_task" :arguments {:issue_key "MITH-1" :repo ["--investigate"]}}}
                     {:jsonrpc "2.0" :id 6 :method "tools/call"
                      :params {:name "add_task" :arguments {:issue_key "MITH-1" :repo ["relative/path"]}}}
                     {:jsonrpc "2.0" :id 7 :method "tools/call"
                      :params {:name "add_task"
                               :arguments {:issue_key "MITH-1" :repo ["/a" "/b" "/c" "/d" "/e"]}}}
                     {:jsonrpc "2.0" :id 8 :method "tools/call" :params {:name "add_task"}}
                     "not json at all")]
    (testing "a notification carries no id and gets no reply"
      ;; Answering one desynchronises the stream: the client is not waiting for
      ;; that frame, so it lands on the next request's read.
      (is (= [1 2 3 4 5 6 7 8 nil] (mapv :id replies))))
    (testing "a repo that is not an absolute path never becomes an argv element"
      ;; A leading `-` would be read as a flag rather than as `--repo`'s value,
      ;; and a relative path resolves against whatever directory this server was
      ;; started in.
      (doseq [i [4 5]]
        (let [r (get-in (nth replies i) [:result])]
          (is (true? (:isError r)))
          (is (str/includes? (get-in r [:content 0 :text]) "absolute path")))))
    (testing "and a list a model got wrong is capped rather than run"
      ;; `open` builds a worktree and a codegraph index per repo.
      (is (str/includes? (get-in (nth replies 6) [:result :content 0 :text]) "at most 4 repos")))
    (testing "no arguments at all is a tool error, not a crash"
      (is (true? (get-in (nth replies 7) [:result :isError]))))
    (testing "a line that is not JSON is a parse error and the stream carries on"
      (is (= -32700 (get-in (last replies) [:error :code]))))
    (testing "one tool, and it takes an issue key"
      (let [tools (get-in (second replies) [:result :tools])]
        (is (= 1 (count tools)) "every tool added here is another thing an unattended session can do at 3am")
        (is (= "add_task" (:name (first tools))))
        (is (= ["issue_key"] (get-in (first tools) [:inputSchema :required])))))
    (testing "a key that is not a key is refused before it becomes an argv element or a task id"
      (let [r (get-in (nth replies 2) [:result])]
        (is (true? (:isError r)))
        (is (str/includes? (get-in r [:content 0 :text]) "not a Linear issue key"))))
    (testing "an unknown tool is a tool error the model can read, not a protocol error"
      (is (true? (get-in (nth replies 3) [:result :isError])))
      (is (nil? (:error (nth replies 3)))))))

;; ------------------------------------------------------- the CLI's own parser

(deftest a-boolean-flag-does-not-swallow-the-token-after-it
  ;; `--investigate` takes no value. A positional scan that drops the token
  ;; after EVERY flag read `/p` as the task id and scaffolded a task called
  ;; `/p`, from a command that was correct.
  (let [positional (fn [args]
                     (str/trim (:out (run {:ok? false} "bb" "-e"
                                          (str "(load-file \"" scripts "/swarmkhazad.bb\") "
                                               "(prn (swarmkhazad/positional-args " (pr-str args) "))")))))]
    (is (= "[]" (positional ["--linear" "MITH-1" "--investigate" "--repo" "/p"])))
    (is (= "[\"mine\"]" (positional ["mine" "--linear" "MITH-1" "--investigate"])))
    (is (= "[\"mine\"]" (positional ["--investigate" "mine"]))
        "the token after a boolean flag is a positional, not its value")
    (is (= "[]" (positional ["--repo" "/p"])) "a value-taking flag still consumes its value")))
