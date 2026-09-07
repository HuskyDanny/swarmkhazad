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
        (testing "the four bullet files exist and are empty; nothing clones into the task folder"
          (doseq [f ["decision.md" "gotcha.md" "finding.md" "escalation.md"]]
            (is (= "" (slurp (str (fs/path dir f))))))
          (is (= #{"decision.md" "escalation.md" "evidence" "finding.md" "goal.md" "gotcha.md" "mail"
                   "metrics.md" "prompts" "repos" "roles" "state" "tmp" "worktrees"}
                 (set (map fs/file-name (fs/list-dir dir))))))
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
