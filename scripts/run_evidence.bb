#!/usr/bin/env bb

;; run_evidence.bb — the run role's one job: measurements, not opinions.
;;
;; From the role's worktree it executes the repo's own test command and every
;; `measure:` command in the task's bar sources — metrics.md's Quantitative
;; section, and `repro.md` when one exists — and writes one file per bar under
;; <task>/evidence/:
;;
;;   bar: <name>            command: <cmd>      cwd: <worktree>
;;   threshold: <bar text>  started_at: <ts>    duration_ms: <n>
;;   exit: <code>
;;   --- output ---
;;   <stdout and stderr, in order, clipped>
;;
;; The goal judge reads that directory at every Stop, so a bar is met when its
;; file says so, not when a role says so.
;;
;; Two tiers, split by what the machine can honestly answer. Local is anything a
;; script settles here — a unit suite, an API call, a component check. A bar
;; whose measure starts with `@cloud` is the other kind: spin the service up and
;; show the behaviour, which does not fit on a laptop shared with every other
;; task. Those dispatch to the operator's self-hosted environment.
;;
;; `repro.md` is the second bar source and it exists because metrics.md is
;; LOCKED r--r--r-- from the moment the swarm opens: the investigation lane's
;; investigator has to name the command that reproduces the bug it just
;; diagnosed, and it cannot edit the contract to do so. Same grammar, one extra
;; file, and a bar there may name its own `ticket:`, `origin:` and `branch:`.
;;
;; The dispatch is one-way ON PURPOSE. There is no read-back from the CLI (RAN:
;; `claude logs <cloud session>` answers "No job matching", and `claude agents
;; --json --all` lists local sessions only, with no cloud filter). `--teleport`
;; is not the missing read — it MIGRATES the session onto this machine and
;; resumes it here, which is the one thing a cloud tier exists to prevent. So
;; the runner reports by commenting on the PR, pr_watch polls it, and its
;; `fixer` wakes the front of the pipeline. That loop is already built.

(ns run-evidence
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "project_lib.bb")))

(def timeout-ms (parse-long (or (not-empty (System/getenv "SWARMKHAZAD_EVIDENCE_TIMEOUT_MS")) "1200000")))
(def max-output 65536)
(def repo-tests-bar "repo-tests")

;; ---------------------------------------------------------------- metrics.md

(defn quantitative-lines
  "The bullet lines under `## Quantitative`, up to the next `## ` heading."
  [metrics-md]
  (->> (str/split-lines metrics-md)
       (drop-while #(not (re-matches #"(?i)##\s+quantitative\s*" (str/trim %))))
       rest
       (take-while #(not (str/starts-with? (str/trim %) "## ")))
       (map str/trim)
       (filter #(str/starts-with? % "- "))))

(defn slug [name]
  (-> (str/lower-case name)
      (str/replace #"[^a-z0-9._-]+" "-")
      (str/replace #"^-+|-+$" "")
      (as-> s (if (str/blank? s) "bar" s))))

(defn parse-bar
  "`- <name> [@<repo>…] — bar: <threshold> — measure: `<command>` …`
   → {:name :repos :threshold :measure :command}.

   The `@repo` tags read like a goal line's and mean the same thing: this bar
   is measured in that repo's worktree. A bar with none is cluster-scoped —
   `kubectl get endpoints` is nobody's repo — and is measured wherever the
   session that runs it happens to stand.

   `:command` is the backticked part and is nil when the measure is prose. Such
   a bar is still a bar — someone has to run it and write the evidence — so it
   is returned rather than dropped. Dropping it made two of a brief's seven bars
   vanish from the page while sitting in metrics.md, which is the worst place
   for an acceptance criterion to be: recorded, and invisible.

   `ticket:`, `origin:` and `branch:` are the same `— key: value` shape and are
   read by the @cloud tier only. They exist for the investigation lane, whose
   reproduction has to run against a named repo and branch and report onto a
   Linear ticket instead of a PR. Three named fields rather than a bag: the
   grammar stays something a person can read off one line, and a typo in a
   fourth key is a typo, not a silently-ignored instruction."
  [line]
  (let [body (subs line 2)
        [head & rest] (str/split body #"\s+—\s+")
        tokens (remove str/blank? (str/split (str head) #"\s+"))
        tag? #(str/starts-with? % "@")
        fields (into {} (for [part rest
                              :let [[k v] (str/split part #":\s*" 2)]
                              :when v]
                          [(str/lower-case (str/trim k)) (str/trim v)]))
        measure (get fields "measure")
        name (str/join " " (remove tag? tokens))]
    (when (seq (str/trim name))
      {:name name
       :repos (mapv #(subs % 1) (filter tag? tokens))
       :threshold (get fields "bar")
       :measure measure
       :ticket (get fields "ticket")
       :origin (get fields "origin")
       :branch (get fields "branch")
       :command (some->> measure (re-find #"`([^`]+)`") second)})))

(defn substitute [command ctx]
  (-> command
      (str/replace "<task-id>" (:task-id ctx))
      (str/replace "<id>" (:task-id ctx))
      (str/replace "<task-dir>" (str (:task-dir ctx)))))

(defn bars
  "Every Quantitative bar across the given sources, names made unique. A bar
   whose measure is prose comes back with a nil :command; callers that run
   things check for it.

   Variadic because a task has more than one bar source (see the header). The
   uniquing has to span all of them, or a repro bar sharing a name with a
   metrics bar would overwrite its evidence file."
  [ctx & metrics-mds]
  (let [parsed (mapcat #(keep parse-bar (quantitative-lines (str %))) metrics-mds)]
    (loop [todo parsed seen #{} out []]
      (if-let [b (first todo)]
        (let [base (slug (:name b))
              id (first (remove seen (cons base (map #(str base "-" %) (iterate inc 2)))))]
          (recur (rest todo) (conj seen id)
                 (conj out (assoc b :id id
                                  :command (some-> (:command b) (substitute ctx))))))
        out))))

;; ---------------------------------------------------------------- repo tests

(defn detect-test-command
  "The repo's own test command, from what the worktree carries."
  [worktree]
  (let [has? #(fs/exists? (fs/path worktree %))]
    (cond
      (and (has? "package.json") (has? "pnpm-lock.yaml")) "pnpm test"
      (and (has? "package.json") (has? "yarn.lock")) "yarn test"
      ;; `bun test` is Bun's OWN runner, not the repo's `test` script — a Vitest
      ;; suite run that way fails on every file (measured on lothlorien: 0 pass,
      ;; 33 fail on `vi.unstubAllGlobals`, all 33 green under `bun run test`).
      ;; So bun runs the script, like every other manager.
      (and (has? "package.json") (or (has? "bun.lockb") (has? "bun.lock"))) "bun run test"
      (has? "package.json") "npm test"
      (has? "bb.edn") "bb test"
      (or (has? "pyproject.toml") (has? "pytest.ini") (has? "setup.py")) "pytest"
      (has? "go.mod") "go test ./..."
      (has? "Cargo.toml") "cargo test"
      (and (has? "Makefile") (re-find #"(?m)^test:" (slurp (str (fs/path worktree "Makefile"))))) "make test"
      :else nil)))

;; ---------------------------------------------------------------- running

(defn clip [s]
  (if (> (count s) max-output)
    (str (subs s 0 max-output) "\n…[output clipped at " max-output " bytes]\n")
    s))

(defn run-command
  "bash -c in the worktree; stdout and stderr interleaved; a timeout is exit 124."
  [command cwd]
  (let [started (System/currentTimeMillis)
        p (process/process ["bash" "-c" command] {:dir (str cwd) :out :string :err :out})
        done (deref p timeout-ms nil)
        _ (when-not done (process/destroy-tree p))
        result (if done @p {:exit 124 :out (str (try (slurp (:out p)) (catch Exception _ "")) "\n…[timed out after " timeout-ms " ms]\n")})]
    {:exit (:exit result)
     :output (clip (str (:out result)))
     :duration-ms (- (System/currentTimeMillis) started)
     :started-at (str (java.time.Instant/ofEpochMilli started))}))

(def cloud-marker "@cloud")

(defn cloud-bar?
  "A bar whose measure opens with `@cloud`. The marker sits in the measure
   rather than in a new metrics.md section or a new field, because that is the
   position that already answers \"how is this checked\" — and `parse-bar`
   already returns such a bar with a nil :command, so nothing about the grammar
   changes."
  [bar]
  (str/starts-with? (str/triml (str (:measure bar))) cloud-marker))

(defn cloud-brief
  "What the runner is told. Repo, branch and the return channel are passed
   explicitly: the runner clones from origin into its own workspace and cuts its
   own branch, so nothing about this worktree reaches it implicitly.

   The return channel is a PR comment or a Linear comment, and which one is the
   only difference between the two tiers. The PR was never a precondition for
   EXECUTION — it is where the findings land — which is exactly why a ticket can
   stand in for it. On the investigation lane the ticket is already the shared
   surface: the investigator commented its RCA there and the loop session reads
   the runner's reply to decide the `Repro` label."
  [{:keys [origin branch pr ticket]} bar]
  (let [where (if ticket (str "Linear ticket " ticket) (str "PR #" pr))]
    (str "You are the run role of a swarmkhazad task, on the self-hosted environment.\n\n"
         "Repo:   " origin "\n"
         "Branch: " branch "\n"
         (if ticket (str "Ticket: " ticket "\n") (str "PR:     #" pr "\n")) "\n"
         "Check out that branch and verify this ONE bar at the service level — start the\n"
         "thing and drive it, do not settle for a green unit suite:\n\n"
         "  bar:     " (:name bar) "\n"
         (when (:threshold bar) (str "  passes:  " (:threshold bar) "\n"))
         "  measure: " (str/triml (subs (str/triml (str (:measure bar))) (count cloud-marker))) "\n\n"
         "Then post ONE comment on " where " with what you observed: what you ran, what\n"
         "happened, and the screenshots. Say plainly whether the bar is met. That comment\n"
         "is the only way your findings reach the task — nothing here can read your\n"
         "session — so a run that verifies the bar and posts nothing has failed.\n\n"
         (when ticket
           (str "State the OUTCOME, and only the outcome: did the command reproduce the\n"
                "behaviour the bar describes, yes or no, and paste the decisive output. The\n"
                "label on that ticket is set from your comment by a session that was not in\n"
                "the room — so \"it reproduces\" with nothing quoted is not an answer.\n\n"))
         "Do not merge. Do not push to main. Do not modify the branch.")))

(defn git-out [worktree & args]
  (let [r (apply process/sh {:dir (str worktree)} "git" args)]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn normalize-origin
  "A remote URL as the runner needs it: https form, no `.git`.

   Applied to the BAR's origin as well as the worktree's, and that is the point
   of pulling it out. Normalising only the derived one meant a legitimate
   `git@github.com:MithraAI/istari.git` written into repro.md never became
   https, so the owner parsed as nil and the bar was refused with a message
   about the owner — pointing the reader at the wrong half of a line that was
   correct."
  [url]
  (some-> url
          (str/replace #"^git@github\.com:" "https://github.com/")
          (str/replace #"\.git$" "")))

(def reachable-origin-re
  "The ONE repository shape the self-hosted environment is authenticated for.

   Matched against the whole normalized origin, not against a path segment. The
   segment test that stood here — `(drop 3)` of a `/`-split, i.e. \"the 4th
   component is MithraAI\" — never looked at the HOST, so
   `https://attacker.example/MithraAI/anything` satisfied it. That is not a
   hypothetical: `origin:` is authored by the investigate role into repro.md, an
   unlocked file whose instructions ultimately come from a Linear ticket's text,
   and the dispatch tells a credentialed cloud session to clone that URL and
   start the thing. Anchored, so nothing before or after the shape counts."
  #"https://github\.com/MithraAI/[A-Za-z0-9._-]+")

(defn cloud-target
  "Everything the runner needs, or {:error <why>}. Each miss is its own line
   because each has a different fix.

   The bar may name its own `origin:`, `branch:` and `ticket:`, and those win
   over what the worktree says. A reproduction is frequently not on the branch
   the worktree is sitting on — the investigation lane's whole job is to
   reproduce a bug on `main`, from a task branch that carries no code at all —
   and deriving the target from the checkout would send the runner to verify the
   one branch that is certainly innocent.

   `ticket:` also removes the PR precondition. The refusal was never about
   execution: the runner clones and runs perfectly well without a PR, and the
   PR was the RETURN CHANNEL. A ticket is a return channel, so it satisfies the
   same requirement — and demanding a PR for a task whose deliverable is an RCA
   would refuse the lane outright."
  [ctx worktree bar]
  (let [env-id (project-lib/cloud-env-for ctx)
        ticket (not-empty (str/trim (str (:ticket bar))))
        origin (normalize-origin
                (or (not-empty (str/trim (str (:origin bar))))
                    (git-out worktree "remote" "get-url" "origin")))
        bar-branch (not-empty (str/trim (str (:branch bar))))
        branch (or bar-branch (git-out worktree "rev-parse" "--abbrev-ref" "HEAD"))
        ;; Only asked when nothing else can carry the findings. `gh pr view` is
        ;; a network call, and a ticket-bound bar has no use for its answer.
        pr (when-not ticket
             (let [r (process/sh {:dir (str worktree)} "gh" "pr" "view" "--json" "number" "-q" ".number")]
               (when (zero? (:exit r)) (not-empty (str/trim (:out r))))))]
    (cond
      (nil? env-id)
      {:error (str "no self-hosted environment for this task. Set it on the project — the\n"
                   "field is on the project form, beside the checkouts — or pass\n"
                   "SWARMKHAZAD_CLOUD_ENV to override it once. Without one there is nowhere\n"
                   "to dispatch to.")}

      (nil? origin)
      {:error "this worktree has no `origin` remote, so there is no repo to name to the runner."}

      ;; The environment is authenticated to ONE GitHub account. A dispatch
      ;; naming anything else clones nothing and fails in the cloud, where no
      ;; one is watching — so it is refused here, where the message is read.
      (not (re-matches reachable-origin-re (str origin)))
      {:error (str "the runner can only reach github.com/MithraAI repos; this bar's origin is\n"
                   "  " origin "\n"
                   "That is not a repository the environment is authenticated for, so the clone\n"
                   "would fail in the cloud with nobody reading the error. Fix the `origin:` on\n"
                   "the bar, or measure this bar locally instead.")}

      ;; A branch name reaches `git checkout` inside the runner's own shell, and
      ;; on this lane it is written by a role rather than read off a checkout.
      ;; Shape-checked so the free-text surface in that session is the brief's
      ;; prose only, never the target it acts on.
      (and bar-branch (not (re-matches #"[A-Za-z0-9._/-]{1,200}" bar-branch)))
      {:error (str "the bar's `branch:` is not a branch name:\n  " bar-branch "\n"
                   "Letters, digits, dot, dash, slash and underscore, up to 200 characters.")}

      (and ticket (not (re-matches #"[A-Za-z][A-Za-z0-9]*-[0-9]+" ticket)))
      {:error (str "the bar's `ticket:` is not a Linear issue key:\n  " ticket "\n"
                   "It becomes the surface the runner comments on, so it has to name one.")}

      (and (nil? ticket) (nil? pr))
      {:error (str "no return channel for branch `" branch "`: the bar names no `ticket:` and the\n"
                   "branch has no open PR. The runner reports by commenting, so without one its\n"
                   "findings have nowhere to land. Open the PR first — the run role is meant to be\n"
                   "the step between review and merge — or, on an investigation, put\n"
                   "`— ticket: <KEY>` on the bar so the runner answers on the Linear ticket.")}

      :else {:env-id env-id :origin origin :branch branch :pr pr :ticket ticket})))

(defn dispatch-cloud!
  "Create the cloud session and record it. Exit is `pending`, never 0: the bar
   is not met by having been dispatched, and the goal judge reads this file."
  [ctx worktree bar]
  (let [started (System/currentTimeMillis)
        target (cloud-target ctx worktree bar)]
    (if-let [why (:error target)]
      {:exit "blocked" :duration-ms 0 :started-at (str (java.time.Instant/now))
       :output (str "this bar is @cloud, and it could not be dispatched:\n\n" why "\n")}
      (let [r (process/sh {:dir (str worktree)}
                          "claude" "--environment" (:env-id target)
                          "-p" (cloud-brief target bar))
            out (str (:out r) (:err r))
            session (second (re-find #"(session_[A-Za-z0-9]+)" out))]
        {:exit (if (and (zero? (:exit r)) session) "pending" (:exit r))
         :duration-ms (- (System/currentTimeMillis) started)
         :started-at (str (java.time.Instant/ofEpochMilli started))
         :output (str "dispatched to the self-hosted environment.\n"
                      "  repo:    " (:origin target) "\n"
                      "  branch:  " (:branch target) "\n"
                      (if (:ticket target)
                        (str "  ticket:  " (:ticket target) "\n")
                        (str "  PR:      #" (:pr target) "\n"))
                      (when session (str "  session: " session "\n"
                                         "  view:    https://claude.ai/code/" session "\n"))
                      "\nPENDING, not met. The runner answers by commenting on "
                      (if (:ticket target)
                        (str "Linear ticket " (:ticket target)
                             ";\nthe loop session polls that comment and sets the `Repro` label from it.")
                        (str "PR #" (:pr target)
                             ";\npr_watch polls it and wakes the front of the pipeline."))
                      " Nothing here can\n"
                      "read that session directly, and --teleport would drag it onto this machine.\n"
                      "\n--- dispatch output ---\n" (clip out))}))))

(defn write-evidence! [ctx {:keys [id name command threshold]} cwd result]
  (let [file (fs/path (:evidence-dir ctx) (str id ".txt"))]
    (fs/create-dirs (:evidence-dir ctx))
    (spit (str file)
          (str "bar: " name "\n"
               "command: " (or command "(none)") "\n"
               "cwd: " cwd "\n"
               (when threshold (str "threshold: " threshold "\n"))
               "started_at: " (:started-at result) "\n"
               "duration_ms: " (:duration-ms result) "\n"
               "exit: " (:exit result) "\n"
               "--- output ---\n"
               (:output result)
               (when-not (str/ends-with? (:output result) "\n") "\n")))
    file))

(defn mine?
  "A bar is this session's when it tags no repo or tags this one. Untagged is
   the same default a goal line has: a line that never said belongs to
   everyone."
  [repo bar]
  (or (empty? (:repos bar)) (some #{repo} (:repos bar))))

(defn measure-all!
  "Run everything this session owns; return [{:id :exit :file}].

   Both bar sources, same grammar (see the header). A task with no repro.md —
   every task but the investigation lane — behaves exactly as before."
  [ctx worktree metrics-md repro-md repo]
  (let [test-command (detect-test-command worktree)
        ;; One `repo tests` per repo, named after it. detect-test-command reads
        ;; ONE worktree, so a single shared bar meant every session overwrote
        ;; one file and the last writer's repo silently became the task's
        ;; answer — `bun run test` in gobel reported as `repo tests` for all
        ;; three. The id says which repo, so N repos leave N files.
        repo-tests {:id (if repo (str repo-tests-bar "-" repo) repo-tests-bar)
                    :name (str "repo tests" (when repo (str " — " repo)))
                    :command test-command
                    :threshold "the repo's own test command exits 0"}
        rows (cons repo-tests (filter #(mine? repo %) (bars ctx metrics-md repro-md)))]
    (vec (for [bar rows]
           (let [result (cond
                          (cloud-bar? bar) (dispatch-cloud! ctx worktree bar)
                          (:command bar) (run-command (:command bar) worktree)
                          :else
                          {:exit "none"
                           :output (if (str/starts-with? (:id bar) repo-tests-bar)
                                     "no test command detected in the worktree (no package.json, bb.edn, pyproject.toml, go.mod, Cargo.toml or Makefile test target)\n"
                                     (str "this bar has no command to run — its measure is prose, so the run role\n"
                                          "has to satisfy it and overwrite this file with what it observed:\n\n"
                                          "  " (or (:measure bar) (:name bar)) "\n"))
                           :duration-ms 0 :started-at (str (java.time.Instant/now))})
                 ;; `ticket:`, `origin:` and `branch:` are read by the @cloud
                 ;; tier and by nothing else, so on a local bar they are a line
                 ;; the author believed and nothing honoured. Said in the
                 ;; evidence file rather than refused: the bar still ran, and
                 ;; its result is still the answer.
                 ignored (when-not (cloud-bar? bar)
                           (seq (filter bar [:ticket :origin :branch])))
                 result (if ignored
                          (update result :output str
                                  "\n[notice] this bar names " (str/join ", " (map name ignored))
                                  ", which only the @cloud tier reads.\n"
                                  "Its measure does not start with @cloud, so "
                                  (if (= 1 (count ignored)) "that field was" "those fields were")
                                  " ignored.\n")
                          result)
                 file (write-evidence! ctx bar worktree result)]
             {:id (:id bar) :exit (:exit result) :duration-ms (:duration-ms result) :file (str file)})))))

(defn -main [& args]
  ;; The placeholder this replaces. It refused to run at all when set, to stop a
  ;; task believing in a runner that did not exist; the runner exists now and a
  ;; bar opts in per line, so the variable has nothing left to mean.
  (when (seq (System/getenv "SWARMKHAZAD_RUN_REMOTE"))
    (binding [*out* *err*]
      (println "SWARMKHAZAD_RUN_REMOTE no longer does anything — a bar opts into the runner"
               "by starting its measure with @cloud, and the project names the environment"
               "(SWARMKHAZAD_CLOUD_ENV still overrides it). Unset it.")))
  (let [ctx (task-lib/ctx-from-env)
        session (or (first args) (System/getenv "SWARMKHAZAD_SESSION"))
        _ (when (str/blank? session) (task-lib/fail! "SWARMKHAZAD_SESSION is not set"))
        row (task-lib/session-row ctx session)
        worktree (or (:worktree-path row) (str (fs/cwd)))
        metrics-md (if (fs/regular-file? (:metrics-file ctx)) (slurp (str (:metrics-file ctx))) "")
        repro-md (if (fs/regular-file? (:repro-file ctx)) (slurp (str (:repro-file ctx))) "")
        ;; Only past one repo: a one-repo task's `repo-tests.txt` is the name
        ;; every reader and every old task already uses.
        repo (when (> (count (distinct (keep :repo (task-lib/read-sessions-tsv ctx)))) 1)
               (:repo row))
        results (measure-all! ctx worktree metrics-md repro-md repo)]
    (doseq [{:keys [id exit duration-ms file]} results]
      (println (format "%-24s exit=%-5s %6d ms  %s" id (str exit) duration-ms file)))
    (println (str "evidence: " (count results) " files in " (:evidence-dir ctx)))
    (System/exit (if (every? #(= 0 (:exit %)) results) 0 1))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
