(ns swarmkhazad.task-test
  "task_lib.bb + `swarmkhazad prepare`: the task-folder layout, the roles and
   repos declarations, and the worktrees added from the source checkouts."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (str (fs/cwd)))
(def cli (str (fs/path repo-root "scripts" "swarmkhazad.bb")))

(defn run
  "Run a command; throw with output unless ok? is false."
  [{:keys [dir env ok?]} & args]
  (let [result (apply process/sh (concat [{:continue true
                                           :dir (str (or dir repo-root))
                                           :extra-env (or env {})}]
                                         args))]
    (when (and (not (false? ok?)) (not (zero? (:exit result))))
      (throw (ex-info (str "command failed: " (str/join " " args) "\n" (:out result) (:err result))
                      result)))
    result))

(defn git [dir & args]
  (str/trim (:out (apply run {:dir dir} "git" args))))

(defn write! [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn make-source-repo!
  "A local checkout that looks like ~/repos/x: a <branch> branch, an `origin`
   remote pointing at a URL, and origin/<branch> one commit BEHIND local
   <branch> — so the test can tell which the clone pinned to."
  ([dir] (make-source-repo! dir "main"))
  ([dir branch]
   (fs/create-dirs dir)
   (git dir "init" "-q" "-b" branch)
   (git dir "config" "user.email" "t@example.com")
   (git dir "config" "user.name" "T")
   (write! (fs/path dir "README.md") "one\n")
   (git dir "add" ".")
   (git dir "commit" "-q" "-m" "one")
   (let [upstream-sha (git dir "rev-parse" "HEAD")]
     (git dir "remote" "add" "origin" "https://example.invalid/acme/fixture.git")
     (git dir "update-ref" (str "refs/remotes/origin/" branch) upstream-sha)
     (git dir "symbolic-ref" "refs/remotes/origin/HEAD" (str "refs/remotes/origin/" branch))
     (write! (fs/path dir "README.md") "two (local only)\n")
     (git dir "commit" "-q" "-am" "two local")
     (git dir "branch" "-q" "scratch")
     (git dir "tag" "v0")
     {:upstream-sha upstream-sha :local-sha (git dir "rev-parse" "HEAD")})))

(defn snapshot
  "Every path under dir with its mtime — the 'nothing written' witness."
  [dir]
  (->> (fs/glob dir "**")
       (map (fn [p] [(str p) (str (fs/last-modified-time p))]))
       (into (sorted-map))))

(defn with-home
  "Run f with a throwaway SWARMKHAZAD_HOME and a source repo; returns f's value."
  [f]
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-test."})
        home (fs/path sandbox "home")
        src (fs/path sandbox "src" "fixture")]
    (try
      (let [shas (make-source-repo! src)]
        (f {:sandbox (str sandbox) :home (str home) :src (str src) :shas shas
            :env {"SWARMKHAZAD_HOME" (str home)}}))
      (finally
        (fs/delete-tree sandbox)))))

(defn scaffold-task!
  "Scaffold a task and overwrite its two declarations. repos-text defaults to
   the fixture source, so a test that only cares about roles says nothing."
  ([h task-id roles-text] (scaffold-task! h task-id roles-text (str (:src h) "\n")))
  ([{:keys [env src]} task-id roles-text repos-text]
   (run {:env env} cli "new" task-id "--repo" src)
   (let [dir (fs/path (get env "SWARMKHAZAD_HOME") "tasks" task-id)]
     (spit (str (fs/path dir "roles")) roles-text)
     (spit (str (fs/path dir "repos")) repos-text)
     dir)))

(defn prepare-fails
  "Scaffold a task with these declarations, run prepare, return its stderr
   (asserting non-zero exit)."
  ([h label task-id roles-text] (prepare-fails h label task-id roles-text (str (:src h) "\n")))
  ([h label task-id roles-text repos-text]
   (scaffold-task! h task-id roles-text repos-text)
   (let [result (run {:env (:env h) :ok? false} cli "prepare" task-id)]
     (is (not= 0 (:exit result)) label)
     (:err result))))

(defn tsv-rows [dir]
  (->> (slurp (str (fs/path dir "state" "sessions.tsv"))) str/split-lines (mapv #(str/split % #"\t" -1))))

(deftest new-scaffolds-the-three-truth-files
  (with-home
    (fn [{:keys [env] :as h}]
      (let [dir (scaffold-task! h "t-new" "")]
        (is (fs/regular-file? (fs/path dir "goal.md")))
        (is (fs/regular-file? (fs/path dir "metrics.md")))
        (is (str/includes? (slurp (str (fs/path dir "goal.md"))) "## Not-goal"))
        (is (str/includes? (slurp (str (fs/path dir "metrics.md"))) "measure:"))
        (let [again (run {:env env :ok? false} cli "new" "t-new")]
          (is (not= 0 (:exit again)) "a second `new` on the same id refuses"))))))

(deftest prepare-adds-worktrees-from-the-sources-and-writes-sessions-tsv
  (with-home
    (fn [{:keys [env src sandbox shas] :as h}]
      (let [second-src (str (fs/path sandbox "src" "other"))
            _ (make-source-repo! second-src)
            dir (scaffold-task! h "t-prep"
                                (str "implement claude task model=kimi --model sonnet\n"
                                     "review claude model=deepseek batch\n")
                                (str src "\n" second-src "\n"))
            _ (spit (str (fs/path dir "goal.md"))
                    (str "## Goal\n"
                         "- [ ] implement @fixture — the exporter\n"
                         "- [ ] review — read it\n"))
            before (snapshot src)
            out (:out (run {:env env} cli "prepare" "t-prep"))
            wt (fs/path dir "worktrees" "fixture")]
        (testing "the worktree starts from the source's origin/main, not its local HEAD"
          (is (= (:upstream-sha shas) (git wt "rev-parse" "HEAD")))
          (is (= "one\n" (slurp (str (fs/path wt "README.md"))))
              "the source's local-only second commit did not come along"))
        (testing "one worktree per repo, all on the one task branch"
          (is (= "sk/t-prep" (git wt "branch" "--show-current")))
          (is (= "sk/t-prep" (git (fs/path dir "worktrees" "other") "branch" "--show-current")))
          (is (= #{"fixture" "other"} (set (map fs/file-name (fs/list-dir (fs/path dir "worktrees")))))
              "roles do not get their own worktrees; repos do"))
        (testing "the source is left clean, on its own branch, plus exactly one new ref"
          (is (= "" (git src "status" "--porcelain")))
          (is (= "main" (git src "branch" "--show-current")))
          (is (= ["main" "scratch" "sk/t-prep"]
                 (sort (str/split-lines (git src "for-each-ref" "--format=%(refname:short)" "refs/heads/")))))
          (is (fs/directory? (fs/path src ".git" "worktrees"))
              "a worktree IS registered in the source — that is the cost of not cloning, and `reap` is what clears it")
          (is (= (dissoc before (str (fs/path src ".git")))
                 (dissoc (snapshot src) (str (fs/path src ".git"))))
              "nothing outside .git changed"))
        (testing "sessions.tsv is the (role, repo) table, denormalized for the shim"
          (let [rows (tsv-rows dir)]
            (is (= ["implement_fixture" "review_fixture" "review_other"] (mapv first rows)))
            (is (= ["implement_fixture" "implement" "fixture" (str (fs/path dir "worktrees" "fixture"))
                    "claude" "task" "kimi" "--model sonnet"]
                   (first rows)))
            (is (= ["review_fixture" "review" "fixture" (str (fs/path dir "worktrees" "fixture"))
                    "claude" "batch" "deepseek" ""]
                   (second rows))
                "`model=deepseek batch` parses the same as `batch model=deepseek`")
            (is (= "kimi" (nth (first rows) 6)) "the shim reads the vendor from column 7")))
        (testing "an @repo tag narrows a role to that repo; an untagged role works in all of them"
          (is (= ["implement_fixture"] (->> (tsv-rows dir) (filter #(= "implement" (second %))) (mapv first))))
          (is (= ["review_fixture" "review_other"] (->> (tsv-rows dir) (filter #(= "review" (second %))) (mapv first)))))
        (testing "mail dirs exist per session, not per role"
          (is (= #{"_system" "implement_fixture" "review_fixture" "review_other"}
                 (set (map fs/file-name (fs/list-dir (fs/path dir "mail"))))))
          (doseq [session ["implement_fixture" "review_fixture" "review_other"]
                  sub ["outbox/tmp" "sent" "failed" "inbox/new" "inbox/in_process" "inbox/completed"]]
            (is (fs/directory? (fs/path dir "mail" session sub)) (str session "/" sub))))
        (testing "the five bullet files exist and are empty; nothing clones into the task folder"
          (doseq [f ["decision.md" "gotcha.md" "finding.md" "escalation.md" "release.md"]]
            (is (= "" (slurp (str (fs/path dir f))))))
          (is (= #{"plugin"
                   "decision.md" "escalation.md" "evidence" "finding.md" "goal.md" "gotcha.md"
                   "mail" "metrics.md" "prompts" "release.md" "repos" "roles" "state" "tmp" "worktrees"}
                 (set (map fs/file-name (fs/list-dir dir))))
              "one entry, not three — a plugin root is read for hooks/, commands/ and .mcp.json, so it gets its own directory rather than sharing the task folder's namespace"))
        (testing "prepare is idempotent, and a re-prepare keeps a commit the role made"
          (spit (str (fs/path wt "probe.txt")) "x\n")
          (run {:dir (str wt)} "git" "add" "probe.txt")
          (run {:dir (str wt)} "git" "-c" "user.email=t@e" "-c" "user.name=T" "commit" "-q" "-m" "probe")
          (let [sha (git wt "rev-parse" "HEAD")]
            (run {:env env} cli "prepare" "t-prep")
            (is (= sha (git wt "rev-parse" "HEAD")) "re-prepare must not reset the task branch")
            (testing "and rebuilding a worktree someone deleted reattaches to the branch, losing nothing"
              ;; The one case where re-prepare could throw work away: the
              ;; directory is gone, so the guard that skips existing worktrees
              ;; does not fire and the add runs for real.
              (fs/delete-tree wt)
              (let [r (run {:env env :ok? false} cli "prepare" "t-prep")]
                (is (zero? (:exit r)) (str "a deleted worktree must be rebuilt, not refused: " (:err r)))
                (is (= sha (git wt "rev-parse" "HEAD"))
                    "reattached to sk/t-prep at the role's commit, not reset to the start ref")
                (is (fs/exists? (fs/path wt "probe.txt")))))))
        (is (str/includes? out "session: implement_fixture claude task model=kimi"))
        (is (str/includes? out "repo: fixture "))))))

(defn logged-calls
  "The set of lines the stub recorded. Read straight after prepare with no
   waiting, deliberately: `await-indexes!` means a returned prepare has already
   joined every build, and a read that needed a poll here would be reporting
   that it had not."
  [path]
  (->> (slurp (str path)) str/split-lines (remove str/blank?) set))

(deftest prepare-indexes-each-worktree-for-codegraph
  ;; The swarm loaded the codegraph MCP server for every role and no role could
  ;; ever use it: a task worktree lives outside its repo, codegraph resolves a
  ;; project by walking UP for a `.codegraph/`, so the walk found nothing and
  ;; the tool answered `isn't indexed ... don't call codegraph for it again this
  ;; session` — retiring itself for the rest of that role's run (RAN).
  ;;
  ;; `codegraph` is stubbed rather than required. The real binary is an npm
  ;; global that a fresh runner does not have, and stubbing also pins the one
  ;; thing worth asserting: WHICH subcommand prepare picks. `init` on an
  ;; already-indexed directory exits 0 without rebuilding, so a resume that ran
  ;; `init` would keep serving the index as it stood at the first open, blind to
  ;; every commit the roles had made since.
  (with-home
    (fn [{:keys [env src sandbox] :as h}]
      (let [second-src (str (fs/path sandbox "src" "other"))
            _ (make-source-repo! second-src)
            stubdir (str (fs/path sandbox "stubbin"))
            calls (str (fs/path sandbox "codegraph-calls.log"))
            _ (fs/create-dirs stubdir)
            _ (spit (str (fs/path stubdir "codegraph"))
                    (str "#!/usr/bin/env bash\n"
                         ;; init leaves the marker the second prepare branches on.
                         ;; Before the log line, never after: the log is what the
                         ;; test waits on, so anything written after it is a race
                         ;; the assertions below would lose.
                         "[ \"$1\" = init ] && mkdir -p \"$2/.codegraph\" && echo db > \"$2/.codegraph/codegraph.db\"\n"
                         "echo \"$1 $2\" >> " calls "\n"
                         "exit 0\n"))
            _ (fs/set-posix-file-permissions (fs/path stubdir "codegraph") "rwxr-xr-x")
            ;; Prepended to the REAL PATH, never a hardcoded prefix: a literal
            ;; "/opt/homebrew/bin" here passed on this machine and failed on CI.
            env (assoc env "PATH" (str stubdir ":" (System/getenv "PATH")))
            dir (scaffold-task! h "t-cg" "implement claude\n" (str src "\n" second-src "\n"))
            wt (fs/path dir "worktrees" "fixture")]
        (run {:env env} cli "prepare" "t-cg")
        (testing "every worktree is indexed, and with init because none had an index"
          (is (= #{(str "init " wt)
                   (str "init " (fs/path dir "worktrees" "other"))}
                 (logged-calls calls))))
        (testing "the index does not make the worktree look dirty"
          ;; `.codegraph/` carries its own .gitignore, which hides the database
          ;; but not the directory: without the exclude, `status --porcelain`
          ;; reports `?? .codegraph/` and three readers take that for work in
          ;; progress — the summary's `uncommitted:` block, the judge's
          ;; git-status section, and the runtime stamp's `dirty` field.
          (is (fs/directory? (fs/path wt ".codegraph")))
          (is (= "" (git wt "status" "--porcelain"))))
        (testing "the exclude is written to the shared common dir, once, and the source stays clean"
          ;; info/exclude lives in the COMMON git dir, so one append covers every
          ;; worktree this checkout will ever have.
          (let [lines (str/split-lines (slurp (str (fs/path src ".git" "info" "exclude"))))]
            (is (= 1 (count (filter #(= ".codegraph/" (str/trim %)) lines)))))
          (is (= "" (git src "status" "--porcelain"))))
        (testing "a re-prepare syncs the existing index instead of leaving it as it was"
          (spit calls "")
          (run {:env env} cli "prepare" "t-cg")
          (is (= #{(str "sync " wt)
                   (str "sync " (fs/path dir "worktrees" "other"))}
                 (logged-calls calls)))
          (let [lines (str/split-lines (slurp (str (fs/path src ".git" "info" "exclude"))))]
            (is (= 1 (count (filter #(= ".codegraph/" (str/trim %)) lines)))
                "the exclude is appended once, not once per prepare")))))))

(deftest prepare-without-codegraph-installed-is-unchanged
  ;; The index is an enhancement, never a dependency: a machine without the
  ;; binary must open a task exactly as it did before this existed.
  (with-home
    (fn [{:keys [env src] :as h}]
      ;; Inside the sandbox, so `with-home`'s own teardown takes it. A separate
      ;; create-temp-dir would leak one directory per run with nothing to delete
      ;; it — TMPDIR held 1098 of exactly that shape when this was written.
      (let [onlybin (fs/create-dirs (fs/path (:sandbox h) "onlybin"))
            ;; The tools prepare genuinely needs, SYMLINKED into a bin of their
            ;; own rather than putting their real directories on PATH — bb and
            ;; codegraph are both npm globals in the same directory here, so
            ;; adding that directory would smuggle back the very binary this
            ;; case removes. Asserted absent below, because a PATH that also
            ;; lost `bb` fails with 127 and reads exactly like a working case.
            _ (doseq [tool ["bb" "git"]]
                (fs/create-sym-link (fs/path onlybin tool) (fs/which tool)))
            env (assoc env "PATH" (str onlybin))
            dir (scaffold-task! h "t-nocg" "implement claude\n")]
        (is (not (zero? (:exit (run {:env env :ok? false} "sh" "-c" "command -v codegraph"))))
            "this PATH must not reach a codegraph, or the case proves nothing")
        (let [r (run {:env env :ok? false} cli "prepare" "t-nocg")]
          (is (zero? (:exit r)) (str "prepare must not need codegraph: " (:err r)))
          (is (not (fs/exists? (fs/path dir "worktrees" "fixture" ".codegraph"))))
          (is (= "sk/t-nocg" (git (fs/path dir "worktrees" "fixture") "branch" "--show-current"))))))))

(deftest prepare-handles-unusual-sources
  (with-home
    (fn [{:keys [env src sandbox shas] :as h}]
      (testing "a source with no origin remote starts from its own branch tip"
        (let [lonely (str (fs/path sandbox "src" "lonely"))]
          (make-source-repo! lonely)
          ;; `remote remove` takes refs/remotes/origin/* with it, so after this
          ;; there is no upstream ref at all and the start ref falls back.
          (git lonely "remote" "remove" "origin")
          (let [dir (scaffold-task! h "t-lonely" "a claude\n" (str lonely "\n"))]
            (run {:env env} cli "prepare" "t-lonely")
            (is (= (git lonely "rev-parse" "main")
                   (git (fs/path dir "worktrees" "lonely") "rev-parse" "HEAD"))))))
      (testing "a source whose upstream default branch is master starts from master, not a renamed main"
        (let [old (str (fs/path sandbox "src" "oldstyle"))
              old-shas (make-source-repo! old "master")
              dir (scaffold-task! h "t-master" "a claude\n" (str old "\n"))]
          (run {:env env} cli "prepare" "t-master")
          (is (= (:upstream-sha old-shas) (git (fs/path dir "worktrees" "oldstyle") "rev-parse" "HEAD")))
          (is (= "sk/t-master" (git (fs/path dir "worktrees" "oldstyle") "branch" "--show-current")))))
      (testing "a source that is itself a linked worktree is a valid checkout"
        (let [linked (str (fs/path sandbox "src" "fixture-linked"))]
          (git src "worktree" "add" "-q" linked "scratch")
          (let [dir (scaffold-task! h "t-linked" "a claude\n" (str linked "\n"))]
            (run {:env env} cli "prepare" "t-linked")
            (is (= (:upstream-sha shas) (git (fs/path dir "worktrees" "fixture-linked") "rev-parse" "HEAD"))
                "still started from origin/main, not from the scratch branch that worktree holds"))))
      (testing "branch= starts the task branch from that branch instead of the default"
        (let [other (str (fs/path sandbox "src" "branched"))]
          (make-source-repo! other)
          (run {:dir other} "git" "checkout" "-q" "-b" "feature")
          (write! (fs/path other "feature.txt") "f\n")
          (run {:dir other} "git" "add" "feature.txt")
          (run {:dir other} "git" "-c" "user.email=t@e" "-c" "user.name=T" "commit" "-q" "-m" "on feature")
          (run {:dir other} "git" "checkout" "-q" "main")
          (let [dir (scaffold-task! h "t-branch" "a claude\n" (str other " branch=feature\n"))]
            (is (zero? (:exit (run {:env env :ok? false} cli "prepare" "t-branch"))))
            (is (fs/exists? (fs/path dir "worktrees" "branched" "feature.txt"))
                "started from the named branch, not the default"))))
      (testing "a shallow source needs no special handling now that nothing is cloned"
        (let [shallow (str (fs/path sandbox "src" "shallow"))]
          (run {} "git" "clone" "-q" "--depth" "1" (str "file://" src) shallow)
          (let [dir (scaffold-task! h "t-shallow" "a claude\n" (str shallow "\n"))
                r (run {:env env :ok? false} cli "prepare" "t-shallow")]
            (is (zero? (:exit r)) (str "a shallow source must still prepare: " (:err r)))
            (is (fs/directory? (fs/path dir "worktrees" "shallow")))))))))

(deftest prepare-rejects-bad-declarations
  (with-home
    (fn [{:keys [env src sandbox] :as h}]
      (testing "the roles declaration"
        (doseq [[label roles-text needle]
                [["duplicate role" "a claude\na claude\n" "duplicate roles"]
                 ["underscore" "my_role claude\n" "must match"]
                 ["dot in role" "a.b claude\n" "must match"]
                 ["slash in role (absolute path escape)" "/tmp/pwn claude\n" "must match"]
                 ["dot-dot role (relative escape)" "../../x claude\n" "must match"]
                 ["slash inside an otherwise valid role" "a/../../x claude\n" "must match"]
                 ["leading dash role" "-rf claude\n" "must match"]
                 ["unknown harness" "a gemini\n" "unknown harness"]
                 ["unknown vendor" "a claude model=llama\n" "unknown model vendor"]
                 ["too few fields" "a\n" "need <role> <harness>"]
                 ["a repo on a role line" (str "a claude " src " task\n") "no longer names a repo"]
                 ["empty" "# only a comment\n" "empty"]]]
          (let [id (str "t-bad-" (str/replace label #"[^a-z]" ""))
                err (prepare-fails h label id roles-text)]
            (is (str/includes? err needle) (str label ": " err)))))
      (testing "a dot in a role name is refused because tmux cannot target it"
        ;; RAN: `new-session -s sk-a.b` succeeds and every later `-t sk-a.b`
        ;; fails `can't find pane: b`, since tmux reads a dot as session.pane.
        (is (str/includes? (prepare-fails h "dotted role" "t-dotrole" "im.plement claude\n") "must match")))
      (testing "the repos declaration"
        (doseq [[label repos-text needle]
                [["not a checkout" "/nope/not-a-repo\n" "not a git checkout"]
                 ["unknown token" (str src " depth=1\n") "unknown token"]
                 ["empty" "# only a comment\n" "empty"]]]
          (let [id (str "t-repo-" (str/replace label #"[^a-z]" ""))
                err (prepare-fails h label id "a claude\n" repos-text)]
            (is (str/includes? err needle) (str label ": " err)))))
      (testing "two checkouts with the same basename would share one worktree name — refused"
        (let [twin (str (fs/path sandbox "other" "fixture"))]
          (make-source-repo! twin)
          (is (str/includes? (prepare-fails h "basename collision" "t-twin" "a claude\n"
                                            (str src "\n" twin "\n"))
                             "share the basename"))))
      (testing "a branch the checkout lacks is refused rather than silently starting from HEAD"
        (is (str/includes? (prepare-fails h "ghost branch" "t-ghost" "a claude\n" (str src " branch=ghost\n"))
                           "has no branch ghost")))
      (testing "a goal line tagging a repo the task does not have is a typo, not a wildcard"
        (let [dir (scaffold-task! h "t-badtag" "a claude\n")]
          (spit (str (fs/path dir "goal.md")) "## Goal\n- [ ] a @fixtur — typo\n")
          (let [r (run {:env env :ok? false} cli "prepare" "t-badtag")]
            (is (not= 0 (:exit r)))
            (is (str/includes? (:err r) "not one of this task's repos")))))
      (testing "an upstream ref naming a commit the checkout does not hold falls back to what it has"
        ;; The real shape, from lothlorien: origin/main points past a shallow
        ;; checkout's own boundary. rev-parse answers with the sha anyway, and
        ;; a worktree started there dies with "nonexistent object".
        (let [ahead (str (fs/path sandbox "src" "ahead"))]
          (make-source-repo! ahead)
          (let [c1 (git ahead "rev-parse" "HEAD")
                dir (scaffold-task! h "t-ahead" "a claude\n" (str ahead "\n"))]
            (write! (fs/path ahead ".git" "refs" "remotes" "origin" "main")
                    "c31eff2d2d5a5cee31e529a6df5df548cc3813dd\n")
            (let [r (run {:env env :ok? false} cli "prepare" "t-ahead")]
              (is (zero? (:exit r)) (str "must not die with \"nonexistent object\": " (:err r)))
              (is (= c1 (git (fs/path dir "worktrees" "ahead") "rev-parse" "HEAD"))
                  "started from what the source holds, not from the commit its ref named")))))
      (testing "nothing escaped the sandbox on any rejected declaration"
        (is (not (fs/exists? "/tmp/pwn")))
        (is (not (fs/exists? (fs/path (get env "SWARMKHAZAD_HOME") "x"))))))))

(deftest reap-clears-what-a-task-leaves-in-your-own-checkouts
  (with-home
    (fn [{:keys [env src sandbox] :as h}]
      (let [reap (fn [& args]
                   (apply run {:env (assoc env "SWARMKHAZAD_REPO_ROOTS" (str (fs/path sandbox "src")))
                               :ok? false}
                          cli "reap" args))
            branches #(->> (git src "for-each-ref" "--format=%(refname:short)" "refs/heads/")
                           str/split-lines (remove str/blank?) sort vec)]
        (doseq [id ["t-live" "t-empty" "t-work"]]
          (scaffold-task! h id "a claude\n")
          (run {:env env} cli "prepare" id))
        (git src "branch" "keepme")
        (testing "one worktree registration per task, and one branch each"
          (is (= ["keepme" "main" "scratch" "sk/t-empty" "sk/t-live" "sk/t-work"] (branches)))
          (is (= 3 (count (fs/list-dir (fs/path src ".git" "worktrees"))))))
        (let [wt (fs/path (get env "SWARMKHAZAD_HOME") "tasks" "t-work" "worktrees" "fixture")]
          (write! (fs/path wt "work.txt") "a day of work\n")
          (run {:dir (str wt)} "git" "add" "work.txt")
          (run {:dir (str wt)} "git" "-c" "user.email=t@e" "-c" "user.name=T" "commit" "-q" "-m" "work"))
        (fs/delete-tree (fs/path (get env "SWARMKHAZAD_HOME") "tasks" "t-empty"))
        (fs/delete-tree (fs/path (get env "SWARMKHAZAD_HOME") "tasks" "t-work"))
        (testing "an orphan holding nothing is deleted; one holding work is not"
          (let [out (:out (reap))]
            (is (str/includes? out "deleted") out)
            (is (str/includes? out "sk/t-empty"))
            (is (str/includes? out "it holds 1 commit(s); --force to delete anyway"))
            (is (str/includes? out "sk/t-live") "and a live task's branch says why it was kept")
            (is (str/includes? out "its task folder is still there"))
            (is (str/includes? out "1 deleted, 2 kept")
                "the summary is the whole answer for anyone who does not read the rows"))
          (is (= ["keepme" "main" "scratch" "sk/t-live" "sk/t-work"] (branches))))
        (testing "the stale registrations are pruned, the live one is not"
          (is (= 1 (count (fs/list-dir (fs/path src ".git" "worktrees"))))))
        (testing "a branch the checkout has checked out is left alone, not failed on"
          ;; The stale registration was pruned on the run above, which is the
          ;; only reason the source can check this branch out at all.
          (run {:dir src} "git" "checkout" "-q" "sk/t-work")
          (let [r (reap "--force")]
            (is (zero? (:exit r)) (:err r))
            (is (str/includes? (:out r) "a worktree still has it: "))
            (is (some #{"sk/t-work"} (branches))
                "git refuses the delete anyway; saying so beats an error nobody can act on"))
          (run {:dir src} "git" "checkout" "-q" "main"))
        (testing "and so is one held by a worktree that is not this checkout"
          ;; The case the old check could not see: it compared the SOURCE's own
          ;; HEAD, so a branch checked out in any other linked worktree read as
          ;; deletable. `git branch -D` refused, task-lib/git threw on the
          ;; non-zero exit, and the sweep ended there — with the checkouts it
          ;; had already reaped reaped, and the rest never looked at.
          (let [elsewhere (str (fs/path sandbox "elsewhere"))]
            (run {:dir src} "git" "worktree" "add" "-q" elsewhere "sk/t-work")
            (let [r (reap "--force")]
              (is (zero? (:exit r)) (str "one held branch must not end the sweep: " (:err r)))
              (is (str/includes? (:out r) (str "a worktree still has it: "
                                              (str (fs/canonicalize elsewhere))))
                  "named, so the operator knows which worktree to close")
              (is (some #{"sk/t-work"} (branches)) "and it is still there"))
            (run {:dir src} "git" "worktree" "remove" "--force" elsewhere)))
        (testing "--force takes the one holding work, and nothing else"
          (is (str/includes? (:out (reap "--force")) "sk/t-work"))
          (is (= ["keepme" "main" "scratch" "sk/t-live"] (branches))
              "a branch that is not sk/<task-id> is never a candidate, forced or not"))))))

(deftest a-task-opened-before-sessions-tsv-existed-still-reads
  ;; roles.tsv was the runtime table until sessions.tsv replaced it. Tasks
  ;; written under the old shape are still running, and every helper they call
  ;; reads this function.
  (load-file (str (fs/path repo-root "scripts" "task_lib.bb")))
  (with-home
    (fn [{:keys [env src] :as h}]
      (let [dir (scaffold-task! h "t-legacy" "a claude\n")
            _ (run {:env env} cli "prepare" "t-legacy")
            ;; The ctx is built here rather than by task-ctx, which reads
            ;; SWARMKHAZAD_HOME from THIS process's environment and would
            ;; silently answer for the real ~/.swarmkhazad instead of the
            ;; sandbox — an empty read that looks like a passing assertion.
            ctx {:sessions-tsv (fs/path dir "state" "sessions.tsv")
                 :roles-tsv (fs/path dir "state" "roles.tsv")}
            read #((resolve 'task-lib/read-sessions-tsv) ctx)]
        (fs/delete (fs/path dir "state" "sessions.tsv"))
        (is (= [] (read)) "with neither table there is nothing to read")
        ;; role, harness, repo, worktree, receive-mode, model, branch, extra
        (spit (str (fs/path dir "state" "roles.tsv"))
              (str "a\tclaude\t" src "\t" (fs/path dir "worktrees" "fixture") "\ttask\tkimi\tnone\t--flag\n"
                   "b\tclaude\tnone\t" dir "\tbatch\tanthropic\tnone\t\n"))
        (let [rows (read)]
          (is (= ["a" "b"] (mapv :session rows))
              "an old row is one session named after its role — it already has mail/<role>/ and a pane called sk-<role>")
          (is (= "fixture" (:repo (first rows))) "the repo path becomes the repo name")
          (is (= (str (fs/path dir "worktrees" "fixture")) (:worktree-path (first rows))))
          (is (= "kimi" (:model (first rows))))
          (is (= "--flag" (:extra-args (first rows))))
          (is (nil? (:repo (second rows))) "`none` reads back as no repo, not as a repo called none")
          (is (= "batch" (:receive-mode (second rows)))))))))

(deftest prepare-refuses-without-truth-files
  (with-home
    (fn [{:keys [env] :as h}]
      (let [dir (scaffold-task! h "t-notruth" "a claude none\n")]
        (fs/delete (fs/path dir "metrics.md"))
        (let [result (run {:env env :ok? false} cli "prepare" "t-notruth")]
          (is (not= 0 (:exit result)))
          (is (str/includes? (:err result) "missing metrics.md")))))))

(deftest task-id-validation
  (with-home
    (fn [{:keys [env]}]
      (doseq [bad ["" "../x" "a/b" ".hidden" "sp ace"]]
        (let [result (run {:env env :ok? false} cli "paths" bad)]
          (is (not= 0 (:exit result)) (pr-str bad))))
      (is (str/includes? (:out (run {:env env} cli "paths" "ok-id_1.2")) "task-dir")))))

(deftest no-arguments-prints-the-usage-text
  ;; `print` to *err* followed by System/exit discards the buffer, so this
  ;; exited 1 with nothing on either stream — the CLI's own front door, silent.
  (with-home
    (fn [{:keys [env]}]
      (let [r (run {:env env :ok? false} cli)]
        (is (= 1 (:exit r)))
        (is (str/includes? (:err r) "Usage:"))
        (doseq [verb ["new" "prepare" "open" "close" "smoke" "paths" "portal" "telemetry"]]
          (is (str/includes? (:err r) (str "swarmkhazad " verb)) verb)))
      (testing "an unknown verb says the same thing rather than failing mutely"
        (let [r (run {:env env :ok? false} cli "frobnicate")]
          (is (= 1 (:exit r)))
          (is (str/includes? (:err r) "Usage:")))))))

(deftest a-task-opened-before-the-repos-file-can-still-be-reopened
  ;; A reboot kills the tmux socket, and `open` is how anyone gets a running
  ;; task back. Requiring the `repos` file unconditionally turned that into a
  ;; hard failure for every task that started before the file existed — which
  ;; is every task on disk when this branch lands.
  (with-home
    (fn [{:keys [env] :as h}]
      (let [dir (scaffold-task! h "t-old" "implement claude\nreview claude\n")]
        (run {:env env} cli "prepare" "t-old")
        (let [wt (fs/path dir "worktrees" "fixture")
              ;; A commit the old task made, so a rebuilt worktree would be
              ;; visibly the wrong one rather than an identical empty tree.
              _ (do (spit (str (fs/path wt "work.txt")) "x\n")
                    (git wt "add" "work.txt")
                    (git wt "-c" "user.email=t@e" "-c" "user.name=T" "commit" "-q" "-m" "work"))
              sha (git wt "rev-parse" "HEAD")]
          ;; Roll the folder back to the old shape: the runtime table is
          ;; roles.tsv, in its own columns, and nothing on disk says which repos
          ;; the task covers.
          ;; role, harness, repo, worktree, receive-mode, model, branch, extra
          (fs/delete (fs/path dir "state" "sessions.tsv"))
          (spit (str (fs/path dir "state" "roles.tsv"))
                (str "implement\tclaude\t" (:src h) "\t" wt "\ttask\tkimi\tnone\t--flag\n"
                     "review\tclaude\t" (:src h) "\t" wt "\tbatch\tanthropic\tnone\t\n"))
          (fs/delete (fs/path dir "repos"))
          ;; Deleted so the assertion below pins the LEGACY branch's own call.
          ;; The fixture's first `prepare` took the fresh path and already wrote
          ;; the plugin, so without this the file is on disk either way and the
          ;; test passes with the legacy call removed — RAN, the mutant survived.
          (fs/delete-tree (fs/path dir "plugin"))
          (let [r (run {:env env :ok? false} cli "prepare" "t-old")]
            (is (zero? (:exit r)) (str "an old task must reopen, not fail: " (:err r))))
          (testing "the old rows become the session table, unchanged in what they say"
            (let [rows (tsv-rows dir)]
              (is (= ["implement" "review"] (mapv first rows))
                  "an old row is one session named after its role — its mail dir and pane already carry that name")
              (is (= ["implement" "implement" "fixture" (str wt) "claude" "task" "kimi" "--flag"]
                     (first rows)))
              (is (= "batch" (nth (second rows) 5)) "and the second row keeps its own receive mode")))
          (testing "no worktree was rebuilt underneath it"
            (is (= sha (git wt "rev-parse" "HEAD"))
                "rebuilding sessions the new way would add worktrees beside these and point the task at the empty ones")
            (is (fs/exists? (fs/path wt "work.txt"))))
          (testing "and its mail dirs are there for the sessions it actually has"
            (is (fs/directory? (fs/path dir "mail" "implement")))
            (is (fs/directory? (fs/path dir "mail" "review"))))
          (testing "a legacy task gets the plugin too, and still nothing in its worktree"
            ;; `open` IS the resume path, and a resumed investigation whose task
            ;; folder has no plugin comes back with no skill and no subagent —
            ;; the investigate role's entire method lives in that skill, so the
            ;; pane would launch and improvise.
            (doseq [p ["plugin/.claude-plugin/plugin.json"
                       "plugin/skills/investigate/SKILL.md"
                       "plugin/agents/investigation-hypothesis-tester.md"]]
              (is (fs/regular-file? (fs/path dir p)) (str "task folder: " p))
              (is (not (fs/exists? (fs/path wt p)))
                  (str "legacy worktree must stay untouched: " p))))))))
  (testing "a task with neither repos nor roles.tsv is not a legacy task, it is broken"
    (with-home
      (fn [{:keys [env] :as h}]
        (let [dir (scaffold-task! h "t-nothing" "a claude\n")]
          (fs/delete (fs/path dir "repos"))
          (let [r (run {:env env :ok? false} cli "prepare" "t-nothing")]
            (is (not= 0 (:exit r)))
            (is (str/includes? (:err r) "missing repos"))))))))

