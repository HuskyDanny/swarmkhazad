(ns swarmkhazad.evidence-test
  "run_evidence.bb: metrics.md Quantitative bars → one evidence file each, plus
   the repo's own test command, from the run role's worktree."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (str (fs/cwd)))
(def scripts (str (fs/path repo-root "scripts")))
(def cli (str (fs/path scripts "swarmkhazad.bb")))

(defn run [{:keys [dir env ok?]} & args]
  (let [result (apply process/sh (concat [{:continue true :dir (str (or dir repo-root)) :extra-env (or env {})}] args))]
    (when (and (not (false? ok?)) (not (zero? (:exit result))))
      (throw (ex-info (str "command failed: " (str/join " " args) "\n" (:out result) (:err result)) result)))
    result))

(defn git [dir & args]
  (str/trim (:out (apply run {:dir dir} "git" args))))

(defn write! [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn make-source-repo! [dir files]
  (fs/create-dirs dir)
  (git dir "init" "-q" "-b" "main")
  (git dir "config" "user.email" "t@example.com")
  (git dir "config" "user.name" "T")
  (doseq [[name text] files] (write! (fs/path dir name) text))
  (git dir "add" ".")
  (git dir "commit" "-q" "-m" "one")
  (git dir "remote" "add" "origin" "https://example.invalid/acme/fixture.git")
  (git dir "update-ref" "refs/remotes/origin/main" (git dir "rev-parse" "HEAD")))

(defn headers [file]
  (into {} (for [line (take-while #(not= "--- output ---" %) (str/split-lines (slurp (str file))))
                 :let [[k v] (str/split line #": " 2)]
                 :when (and k v)]
             [k v])))

(defn output [file]
  (second (str/split (slurp (str file)) #"--- output ---\n" 2)))

(defn with-task [repo-files metrics f]
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-evidence."})
        home (str (fs/path sandbox "home"))
        src (str (fs/path sandbox "src" "fixture"))
        id "t-ev"
        env {"SWARMKHAZAD_HOME" home "SWARMKHAZAD_TASK_ID" id}]
    (try
      (make-source-repo! src repo-files)
      (run {:env env} cli "new" id "--repo" src)
      (let [dir (fs/path home "tasks" id)]
        (spit (str (fs/path dir "roles")) "implement claude task\nrun claude task\n")
        (spit (str (fs/path dir "repos")) (str src "\n"))
        (spit (str (fs/path dir "metrics.md")) metrics)
        (run {:env env} cli "prepare" id)
        (f {:dir dir
            :measure (fn [& [extra-env]]
                       (run {:dir (str (fs/path dir "worktrees" "fixture"))
                             :env (merge env {"SWARMKHAZAD_SESSION" "run" "SWARMKHAZAD_TASK_DIR" (str dir)} extra-env)
                             :ok? false}
                            "bb" (str (fs/path scripts "run_evidence.bb"))))}))
      (finally
        (fs/delete-tree sandbox)))))

(def metrics
  (str "# t-ev — bars\n\n"
       "## Quantitative\n"
       "- every role called — bar: each count ≥ 1 — measure: `echo role-count-for-<id>; ls " "$SWARMKHAZAD_TASK_DIR/state | head -1`\n"
       "- wall clock — bar: < 30 min — measure: `echo 12 min` — note: diff the two headers\n"
       "- a bar with no command yet — bar: something — judged later\n"
       "- Spend (USD) — bar: < $10 — measure: `echo 'sum=3.2' >&2; exit 3`\n"
       "- wall clock — bar: a second bar with the same name — measure: `pwd`\n\n"
       "## Qualitative\n"
       "- portal is up — bar: Allen can click it — measure: `echo never-run` — judged by: Allen\n"))

(deftest every-quantitative-measure-runs-from-the-worktree-and-lands-as-one-evidence-file
  (with-task {"README.md" "one\n" "bb.edn" "{:tasks {test {:task (println \"tests ran\")}}}\n"} metrics
    (fn [{:keys [dir measure]}]
      (let [r (measure)
            ev (fs/path dir "evidence")]
        (is (= 1 (:exit r)) "one measure exited 3, so the run reports failure")
        (testing "one file per bar, names slugged and made unique; the Qualitative measure never ran"
          (is (= #{"repo-tests.txt" "every-role-called.txt" "wall-clock.txt" "spend-usd.txt"
                   "wall-clock-2.txt" "a-bar-with-no-command-yet.txt"}
                 (set (map fs/file-name (fs/list-dir ev)))))
          (is (not (str/includes? (str/join (map slurp (map str (fs/list-dir ev)))) "never-run"))))
        (testing "a bar whose measure is prose still gets a file saying who has to run it"
          ;; It was dropped before, which left an acceptance criterion recorded
          ;; in metrics.md and visible nowhere else.
          (let [f (fs/path ev "a-bar-with-no-command-yet.txt")]
            (is (= "none" (get (headers f) "exit")))
            (is (str/includes? (output f) "no command to run"))
            (is (str/includes? (output f) "the run role"))))
        (testing "the repo's own test command was detected from bb.edn and run in the worktree"
          (let [h (headers (fs/path ev "repo-tests.txt"))]
            (is (= "bb test" (get h "command")))
            (is (= "0" (get h "exit")))
            (is (= (str (fs/path dir "worktrees" "fixture")) (get h "cwd")))
            (is (str/includes? (output (fs/path ev "repo-tests.txt")) "tests ran"))))
        (testing "<id> is substituted; the role's environment reaches the command; threshold carried"
          (let [f (fs/path ev "every-role-called.txt") h (headers f)]
            (is (= "echo role-count-for-t-ev; ls $SWARMKHAZAD_TASK_DIR/state | head -1" (get h "command")))
            (is (= "each count ≥ 1" (get h "threshold")))
            (is (= "0" (get h "exit")))
            (is (str/includes? (output f) "role-count-for-t-ev\n"))
            (is (some? (get h "started_at")))
            (is (re-matches #"\d+" (get h "duration_ms")))))
        (testing "a failing measure keeps its exit code and its stderr"
          (let [f (fs/path ev "spend-usd.txt") h (headers f)]
            (is (= "3" (get h "exit")))
            (is (= "Spend (USD)" (get h "bar")))
            (is (str/includes? (output f) "sum=3.2"))))
        (testing "the second bar with the same name got its own file, run from the worktree"
          (is (= (str (fs/real-path (fs/path dir "worktrees" "fixture"))) (str/trim (output (fs/path ev "wall-clock-2.txt"))))))
        (testing "the summary names each bar with its exit"
          (is (str/includes? (:out r) "repo-tests"))
          (is (re-find #"spend-usd\s+exit=3" (:out r)))
          (is (str/includes? (:out r) "evidence: 6 files in")))))))

(deftest a-repo-without-a-test-command-is-recorded-as-such-and-a-timeout-is-exit-124
  (with-task {"README.md" "one\n"}
    (str "## Quantitative\n- slow — bar: fast — measure: `sleep 5; echo late`\n- quick — bar: ok — measure: `echo fine`\n")
    (fn [{:keys [dir measure]}]
      (let [r (measure {"SWARMKHAZAD_EVIDENCE_TIMEOUT_MS" "700"})
            ev (fs/path dir "evidence")]
        (is (= 1 (:exit r)))
        (let [h (headers (fs/path ev "repo-tests.txt"))]
          (is (= "(none)" (get h "command")))
          (is (= "none" (get h "exit")))
          (is (str/includes? (output (fs/path ev "repo-tests.txt")) "no test command detected")))
        (let [h (headers (fs/path ev "slow.txt"))]
          (is (= "124" (get h "exit")))
          (is (str/includes? (output (fs/path ev "slow.txt")) "timed out after 700 ms"))
          (is (not (str/includes? (output (fs/path ev "slow.txt")) "late"))))
        (is (= "0" (get (headers (fs/path ev "quick.txt")) "exit")) "the bar after the timed-out one still ran")))))

(deftest the-remote-seam-refuses-to-run-while-set
  (with-task {"README.md" "one\n"} "## Quantitative\n- x — bar: y — measure: `echo hi`\n"
    (fn [{:keys [dir measure]}]
      (let [r (measure {"SWARMKHAZAD_RUN_REMOTE" "1"})]
        (is (= 2 (:exit r)))
        (is (str/includes? (:err r) "no remote runner"))
        (is (not (fs/exists? (fs/path dir "evidence" "x.txt"))))))))

(deftest test-command-detection-prefers-the-lockfile-s-package-manager
  (let [detect (fn [files]
                 (let [d (fs/create-temp-dir {:prefix "sk-detect."})]
                   (try
                     (doseq [[n t] files] (write! (fs/path d n) t))
                     (str/trim (:out (process/sh "bb" "-e" (str "(load-file \"" scripts "/run_evidence.bb\") (print (run-evidence/detect-test-command \"" d "\"))"))))
                     (finally (fs/delete-tree d)))))]
    (is (= "pnpm test" (detect {"package.json" "{}" "pnpm-lock.yaml" ""})))
    (is (= "bun run test" (detect {"package.json" "{}" "bun.lockb" ""})))
    (is (= "bun run test" (detect {"package.json" "{}" "bun.lock" ""}))
        "bun's current text lockfile, and `run test` — `bun test` is Bun's own runner, which fails a Vitest suite outright")
    (is (= "npm test" (detect {"package.json" "{}"})))
    (is (= "pytest" (detect {"pyproject.toml" ""})))
    (is (= "go test ./..." (detect {"go.mod" ""})))
    (is (= "make test" (detect {"Makefile" "build:\n\techo\ntest:\n\techo\n"})))
    (is (= "nil" (detect {"Makefile" "build:\n\techo\n"})) "a Makefile without a test target is not a test command")))
