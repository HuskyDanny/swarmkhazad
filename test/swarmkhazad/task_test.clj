(ns swarmkhazad.task-test
  "task_lib.bb + `swarmkhazad prepare`: the task-folder layout, the roles
   declaration, and the clone that leaves the source checkout untouched."
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
  [{:keys [env src]} task-id roles-text]
  (run {:env env} cli "new" task-id "--repo" src)
  (let [dir (fs/path (get env "SWARMKHAZAD_HOME") "tasks" task-id)]
    (spit (str (fs/path dir "roles")) roles-text)
    dir))

(defn prepare-fails
  "Scaffold a task with roles-text, run prepare, return its stderr (asserting non-zero exit)."
  [h label task-id roles-text]
  (scaffold-task! h task-id roles-text)
  (let [result (run {:env (:env h) :ok? false} cli "prepare" task-id)]
    (is (not= 0 (:exit result)) label)
    (:err result)))

(defn tsv-rows [dir]
  (->> (slurp (str (fs/path dir "state" "roles.tsv"))) str/split-lines (mapv #(str/split % #"\t" -1))))

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

(deftest prepare-clones-worktrees-and-writes-roles-tsv
  (with-home
    (fn [{:keys [env src shas] :as h}]
      (let [dir (scaffold-task! h "t-prep" (str "implement claude " src " task model=kimi --model sonnet\n"
                                                 "review claude " src " model=deepseek batch\n"
                                                 "brainstorm claude none\n"))
            before (snapshot src)
            out (:out (run {:env env} cli "prepare" "t-prep"))
            clone (fs/path dir "repos" "fixture")]
        (testing "the clone is pinned to the source's origin/main, not its local HEAD"
          (is (= (:upstream-sha shas) (git clone "rev-parse" "HEAD")))
          (is (= (:upstream-sha shas) (git clone "rev-parse" "refs/remotes/origin/main")))
          (is (= "main" (git clone "branch" "--show-current")))
          (is (= "one\n" (slurp (str (fs/path clone "README.md"))))))
        (testing "origin now means the source's upstream URL, and only main survives"
          (is (= "https://example.invalid/acme/fixture.git" (git clone "remote" "get-url" "origin")))
          (is (= ["refs/remotes/origin/HEAD" "refs/remotes/origin/main"]
                 (str/split-lines (git clone "for-each-ref" "--format=%(refname)" "refs/remotes/")))
              "the source's `scratch` branch did not come along")
          (is (= (:upstream-sha shas) (git clone "rev-parse" "origin/HEAD")) "origin/HEAD is not dangling")
          (is (= ["main"] (->> (str/split-lines (git clone "for-each-ref" "--format=%(refname:short)" "refs/heads/"))
                               (remove #(str/starts-with? % "sk/"))))
              "no stray local branch from the source's checked-out branch, only main and the role branches")
          (is (= "origin/main" (git clone "rev-parse" "--abbrev-ref" "main@{upstream}"))))
        (testing "objects are hardlinked from the source, not copied"
          (let [obj (->> (fs/glob clone ".git/objects/**/*") (filter fs/regular-file?) first)
                links (str/trim (:out (run {} "stat" "-f" "%l" (str obj))))]
            (is (some? obj))
            (is (>= (Long/parseLong links) 2) (str obj " has link count " links))))
        (testing "one worktree per repo-bearing role, on its own branch off main"
          (is (= (:upstream-sha shas) (git (fs/path dir "worktrees" "implement") "rev-parse" "HEAD")))
          (is (= "sk/t-prep/implement" (git (fs/path dir "worktrees" "implement") "branch" "--show-current")))
          (is (= "sk/t-prep/review" (git (fs/path dir "worktrees" "review") "branch" "--show-current")))
          (is (not (fs/exists? (fs/path dir "worktrees" "brainstorm"))) "a `none` repo gets no worktree")
          (is (= 3 (count (str/split-lines (git clone "worktree" "list"))))))
        (testing "mail dirs exist per role"
          (doseq [role ["implement" "review" "brainstorm"]
                  sub ["outbox/tmp" "sent" "failed" "inbox/new" "inbox/in_process" "inbox/completed"]]
            (is (fs/directory? (fs/path dir "mail" role sub)) (str role "/" sub))))
        (testing "roles.tsv carries the declaration, first column is the role, optional tokens in any order"
          (let [rows (tsv-rows dir)]
            (is (= ["implement" "review" "brainstorm"] (mapv first rows)))
            (is (= ["implement" "claude" src (str (fs/path dir "worktrees" "implement")) "task" "kimi" "--model sonnet"]
                   (first rows)))
            (is (= ["review" "claude" src (str (fs/path dir "worktrees" "review")) "batch" "deepseek" ""]
                   (second rows))
                "`model=deepseek batch` parses the same as `batch model=deepseek`")
            (is (= ["brainstorm" "claude" "none" (str dir) "task" "anthropic" ""]
                   (nth rows 2))
                "a role without a repo works in the task folder and says `none`, never an empty cell")))
        (testing "the three bullet files exist and are empty; only the dirs this stage fills exist"
          (doseq [f ["decision.md" "gotcha.md" "escalation.md"]]
            (is (= "" (slurp (str (fs/path dir f))))))
          (is (= #{"decision.md" "escalation.md" "evidence" "goal.md" "gotcha.md" "mail" "metrics.md" "prompts" "repos" "roles" "state" "tmp" "worktrees"}
                 (set (map fs/file-name (fs/list-dir dir))))))
        (testing "nothing under the source checkout changed"
          (is (= before (snapshot src)))
          (is (= "" (git src "status" "--porcelain")))
          (is (not (fs/exists? (fs/path src ".git" "worktrees")))))
        (testing "prepare is idempotent"
          (let [again (:out (run {:env env} cli "prepare" "t-prep"))]
            (is (str/includes? again "(existing)"))
            (is (= 3 (count (str/split-lines (git clone "worktree" "list")))))))
        (is (str/includes? out "role: implement claude task model=kimi"))
        (is (str/includes? out "origin=https://example.invalid/acme/fixture.git"))))))

(deftest prepare-handles-unusual-sources
  (with-home
    (fn [{:keys [env src sandbox shas] :as h}]
      (testing "a source with no origin remote leaves the clone with NO origin, so nothing can fetch from ~/repos later"
        (let [lonely (str (fs/path sandbox "src" "lonely"))]
          (make-source-repo! lonely)
          (git lonely "remote" "remove" "origin")
          (let [dir (scaffold-task! h "t-lonely" (str "a claude " lonely "\n"))
                out (:out (run {:env env} cli "prepare" "t-lonely"))
                clone (fs/path dir "repos" "lonely")]
            (is (= "" (git clone "remote")) "no remote at all")
            (is (str/includes? out "origin=none"))
            (is (= "main" (git clone "branch" "--show-current"))))))
      (testing "a source whose upstream default branch is master is pinned to master, not renamed"
        (let [old (str (fs/path sandbox "src" "oldstyle"))
              old-shas (make-source-repo! old "master")
              dir (scaffold-task! h "t-master" (str "a claude " old "\n"))
              _ (run {:env env} cli "prepare" "t-master")
              clone (fs/path dir "repos" "oldstyle")]
          (is (= "master" (git clone "branch" "--show-current")))
          (is (= (:upstream-sha old-shas) (git clone "rev-parse" "HEAD")))
          (is (= (:upstream-sha old-shas) (git clone "rev-parse" "origin/HEAD")))
          (is (= "sk/t-master/a" (git (fs/path dir "worktrees" "a") "branch" "--show-current")))))
      (testing "a source that is itself a linked worktree is a valid checkout"
        (let [linked (str (fs/path sandbox "src" "fixture-linked"))]
          (git src "worktree" "add" "-q" linked "scratch")
          (let [before (snapshot src)
                dir (scaffold-task! h "t-linked" (str "a claude " linked "\n"))
                _ (run {:env env} cli "prepare" "t-linked")
                clone (fs/path dir "repos" "fixture-linked")]
            (is (= (:upstream-sha shas) (git clone "rev-parse" "HEAD")) "still pinned to the source's origin/main")
            (is (= before (snapshot src)) "the main checkout of that worktree is untouched too")))))))

(deftest prepare-rejects-bad-declarations
  (with-home
    (fn [{:keys [env src sandbox] :as h}]
      (doseq [[label roles-text needle]
              [["duplicate role" (str "a claude " src "\na claude " src "\n") "duplicate roles"]
               ["underscore" (str "my_role claude " src "\n") "must match"]
               ["slash in role (absolute path escape)" (str "/tmp/pwn claude " src "\n") "must match"]
               ["dot-dot role (relative escape)" "../../x claude none\n" "must match"]
               ["slash inside an otherwise valid role" "a/../../x claude none\n" "must match"]
               ["leading dash role" (str "-rf claude " src "\n") "must match"]
               ["unknown harness" (str "a gemini " src "\n") "unknown harness"]
               ["unknown vendor" (str "a claude " src " model=llama\n") "unknown model vendor"]
               ["missing repo" "a claude /nope/not-a-repo\n" "not a git checkout"]
               ["too few fields" "a claude\n" "need <role> <harness> <repo>"]
               ["empty" "# only a comment\n" "empty"]]]
        (let [id (str "t-bad-" (str/replace label #"[^a-z]" ""))
              err (prepare-fails h label id roles-text)]
          (is (str/includes? err needle) (str label ": " err))))
      (testing "two different checkouts with the same basename would share one clone — refused"
        (let [twin (str (fs/path sandbox "other" "fixture"))]
          (make-source-repo! twin)
          (is (str/includes? (prepare-fails h "basename collision" "t-twin"
                                            (str "a claude " src "\nb claude " twin "\n"))
                             "share the basename"))))
      (testing "a shallow source is refused with a reason"
        (let [shallow (str (fs/path sandbox "src" "shallow"))]
          (run {} "git" "clone" "-q" "--depth" "1" (str "file://" src) shallow)
          (is (str/includes? (prepare-fails h "shallow" "t-shallow" (str "a claude " shallow "\n"))
                             "shallow"))))
      (testing "nothing escaped the sandbox on any rejected declaration"
        (is (not (fs/exists? "/tmp/pwn")))
        (is (not (fs/exists? (fs/path (get env "SWARMKHAZAD_HOME") "x"))))))))

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
