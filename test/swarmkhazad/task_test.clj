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
  "A local checkout that looks like ~/repos/x: a main branch, an `origin`
   remote pointing at a URL, and an origin/main ref one commit BEHIND local
   main — so the test can tell which the clone pinned to."
  [dir]
  (fs/create-dirs dir)
  (git dir "init" "-q" "-b" "main")
  (git dir "config" "user.email" "t@example.com")
  (git dir "config" "user.name" "T")
  (write! (fs/path dir "README.md") "one\n")
  (git dir "add" ".")
  (git dir "commit" "-q" "-m" "one")
  (let [upstream-sha (git dir "rev-parse" "HEAD")]
    (git dir "remote" "add" "origin" "https://example.invalid/acme/fixture.git")
    (git dir "update-ref" "refs/remotes/origin/main" upstream-sha)
    (write! (fs/path dir "README.md") "two (local only)\n")
    (git dir "commit" "-q" "-am" "two local")
    (git dir "branch" "-q" "scratch")
    {:upstream-sha upstream-sha :local-sha (git dir "rev-parse" "HEAD")}))

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
        (f {:sandbox sandbox :home (str home) :src (str src) :shas shas
            :env {"SWARMKHAZAD_HOME" (str home)}}))
      (finally
        (fs/delete-tree sandbox)))))

(defn scaffold-task!
  [{:keys [env src]} task-id roles-text]
  (run {:env env} cli "new" task-id "--repo" src)
  (let [dir (fs/path (get env "SWARMKHAZAD_HOME") "tasks" task-id)]
    (spit (str (fs/path dir "roles")) roles-text)
    dir))

(deftest new-scaffolds-the-three-truth-files
  (with-home
    (fn [{:keys [env src] :as h}]
      (let [dir (scaffold-task! h "t-new" "")]
        (is (fs/regular-file? (fs/path dir "goal.md")))
        (is (fs/regular-file? (fs/path dir "metrics.md")))
        (is (str/includes? (slurp (str (fs/path dir "goal.md"))) "## Not-goal"))
        (is (str/includes? (slurp (str (fs/path dir "metrics.md"))) "measure:"))
        (let [again (run {:env env :ok? false} cli "new" "t-new")]
          (is (not= 0 (:exit again)) "a second `new` on the same id refuses"))
        (is (= "t-new\n" (:out (run {:env env} cli "tasks"))))))))

(deftest prepare-clones-worktrees-and-writes-roles-tsv
  (with-home
    (fn [{:keys [env src shas] :as h}]
      (let [dir (scaffold-task! h "t-prep" (str "implement claude " src " task model=kimi --model sonnet\n"
                                                 "review claude " src " batch model=deepseek\n"
                                                 "brainstorm claude none\n"))
            before (snapshot src)
            out (:out (run {:env env} cli "prepare" "t-prep"))
            clone (fs/path dir "repos" "fixture")]
        (testing "the clone is pinned to the source's origin/main, not its local HEAD"
          (is (= (:upstream-sha shas) (git clone "rev-parse" "HEAD")))
          (is (= (:upstream-sha shas) (git clone "rev-parse" "refs/remotes/origin/main")))
          (is (= "one\n" (slurp (str (fs/path clone "README.md"))))))
        (testing "origin now means the source's upstream URL, and only main survives"
          (is (= "https://example.invalid/acme/fixture.git" (git clone "remote" "get-url" "origin")))
          (is (= ["refs/remotes/origin/HEAD" "refs/remotes/origin/main"]
                 (str/split-lines (git clone "for-each-ref" "--format=%(refname)" "refs/remotes/")))
              "the source's `scratch` branch did not come along")
          (is (= ["main"] (->> (str/split-lines (git clone "for-each-ref" "--format=%(refname:short)" "refs/heads/"))
                               (remove #(str/starts-with? % "sk/"))))
              "no stray local branch from the source's checked-out branch, only main and the role branches"))
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
        (testing "mail dirs exist per role and for the system sender"
          (doseq [role ["implement" "review" "brainstorm"]
                  sub ["outbox/tmp" "sent" "failed" "inbox/new" "inbox/in_process" "inbox/completed"]]
            (is (fs/directory? (fs/path dir "mail" role sub)) (str role "/" sub)))
          (is (fs/directory? (fs/path dir "mail" "_system" "outbox"))))
        (testing "roles.tsv carries the declaration, first column is the role"
          (let [rows (->> (slurp (str (fs/path dir "state" "roles.tsv"))) str/split-lines (mapv #(str/split % #"\t" -1)))]
            (is (= ["implement" "review" "brainstorm"] (mapv first rows)))
            (is (= ["implement" "claude" src (str (fs/path dir "worktrees" "implement")) "sk-implement" "Implement" "task" "kimi" "--model sonnet"]
                   (first rows)))
            (is (= "batch" (nth (second rows) 6)))
            (is (= "deepseek" (nth (second rows) 7)))
            (is (= (str dir) (nth (nth rows 2) 3)) "a role without a repo works in the task folder")))
        (testing "the three bullet files exist and are empty"
          (doseq [f ["decision.md" "gotcha.md" "escalation.md"]]
            (is (= "" (slurp (str (fs/path dir f)))))))
        (testing "nothing under the source checkout changed"
          (is (= before (snapshot src)))
          (is (= "" (git src "status" "--porcelain")))
          (is (not (fs/exists? (fs/path src ".git" "worktrees")))))
        (testing "prepare is idempotent"
          (let [again (:out (run {:env env} cli "prepare" "t-prep"))]
            (is (str/includes? again "(existing)"))
            (is (= 3 (count (str/split-lines (git clone "worktree" "list")))))))
        (is (str/includes? out "role: implement claude task model=kimi"))))))

(deftest prepare-rejects-bad-declarations
  (with-home
    (fn [{:keys [env src] :as h}]
      (doseq [[label roles-text needle]
              [["duplicate role" (str "a claude " src "\na claude " src "\n") "duplicate roles"]
               ["underscore" (str "my_role claude " src "\n") "underscores"]
               ["unknown harness" (str "a gemini " src "\n") "unknown harness"]
               ["unknown vendor" (str "a claude " src " model=llama\n") "unknown model vendor"]
               ["missing repo" "a claude /nope/not-a-repo\n" "not a git checkout"]
               ["too few fields" "a claude\n" "need <role> <harness> <repo>"]
               ["empty" "# only a comment\n" "empty"]]]
        (let [id (str "t-bad-" (str/replace label #"[^a-z]" ""))]
          (scaffold-task! h id roles-text)
          (let [result (run {:env env :ok? false} cli "prepare" id)]
            (is (not= 0 (:exit result)) label)
            (is (str/includes? (:err result) needle) (str label ": " (:err result)))))))))

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
