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
                             ;; SWARMKHAZAD_CLOUD_ENV blank by default, because
                             ;; `:extra-env` ADDS to the inherited environment
                             ;; rather than replacing it. An operator with that
                             ;; variable exported — cmux exports it here — had it
                             ;; leak into the case below that says "deliberately
                             ;; NO SWARMKHAZAD_CLOUD_ENV", which then measured a
                             ;; successful dispatch and failed four assertions
                             ;; about a block that could not happen. Green on CI,
                             ;; red on the machine that owns a pool: a test whose
                             ;; answer depends on the shell that ran it.
                             ;;
                             ;; Blank, not absent, because blank is already the
                             ;; code's own "no environment" — `(keep not-empty)`
                             ;; in project-lib/cloud-envs — and `:extra-env`
                             ;; cannot unset a variable.
                             :env (merge env
                                         {"SWARMKHAZAD_CLOUD_ENV" ""
                                          "SWARMKHAZAD_SESSION" "run"
                                          "SWARMKHAZAD_TASK_DIR" (str dir)}
                                         extra-env)
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

(deftest the-old-remote-seam-is-a-notice-now-and-no-longer-stops-the-run
  ;; It used to exit 2 so a task could not believe in a runner that did not
  ;; exist. The runner exists, a bar opts in per line with @cloud, and the
  ;; variable has nothing left to mean — so it says so and gets out of the way.
  (with-task {"README.md" "one\n"} "## Quantitative\n- x — bar: y — measure: `echo hi`\n"
    (fn [{:keys [dir measure]}]
      (let [r (measure {"SWARMKHAZAD_RUN_REMOTE" "1"})]
        (is (str/includes? (:err r) "@cloud"))
        (is (str/includes? (:err r) "SWARMKHAZAD_CLOUD_ENV"))
        (is (fs/exists? (fs/path dir "evidence" "x.txt"))
            "the local bars still ran — a stale variable must not silence a measurement")))))

;; ---------------------------------------------------------------- the cloud tier

(def cloud-metrics
  (str "## Quantitative\n"
       "- checkout works — bar: the order appears — measure: @cloud drive it in a browser and screenshot each step\n"
       "- units — bar: green — measure: `echo local-ran`\n"))

(defn stub-bin!
  "A PATH holding fake `claude` and `gh`, so the dispatch is observable without
   creating a real cloud session. Each records its argv where the test can read
   it."
  [dir {:keys [pr claude-out claude-exit]}]
  (let [bin (fs/path dir "stub-bin")]
    (fs/create-dirs bin)
    (write! (fs/path bin "claude")
            (str "#!/usr/bin/env bash\n"
                 "printf '%s\\0' \"$@\" > " (str (fs/path dir "claude-argv")) "\n"
                 "echo " (or claude-out "'Created cloud session: x\nSession ID: session_01TEST\n'") "\n"
                 "exit " (or claude-exit 0) "\n"))
    (write! (fs/path bin "gh")
            (str "#!/usr/bin/env bash\n"
                 (if pr (str "echo " pr "\nexit 0\n") "exit 1\n")))
    (doseq [f ["claude" "gh"]]
      (fs/set-posix-file-permissions (fs/path bin f) "rwxr-xr-x"))
    (str bin)))

(deftest a-cloud-bar-is-dispatched-and-comes-back-pending-never-met
  (with-task {"README.md" "one\n"} cloud-metrics
    (fn [{:keys [dir measure]}]
      (let [wt (fs/path dir "worktrees" "fixture")
            bin (stub-bin! dir {:pr "482"})]
        ;; the runner reaches exactly one GitHub account, so the origin has to
        ;; be one of its repos for the dispatch to be allowed at all
        (git (str wt) "remote" "set-url" "origin" "https://github.com/MithraAI/istari.git")
        (let [r (measure {"SWARMKHAZAD_CLOUD_ENV" "ccpool_TEST"
                          "PATH" (str bin ":" (System/getenv "PATH"))})
              f (fs/path dir "evidence" "checkout-works.txt")
              h (headers f)
              argv (str/split (slurp (str (fs/path dir "claude-argv"))) #"\u0000")]
          (testing "the bar is PENDING — dispatching is not passing, and the judge reads this file"
            (is (= "pending" (get h "exit")))
            (is (not= "0" (get h "exit"))
                "exit 0 would tell the goal judge a bar was met by having been sent somewhere")
            (is (= 1 (:exit r)) "so the run as a whole is not green either"))
          (testing "the dispatch names the environment and carries repo, branch and PR"
            (is (= "--environment" (nth argv 0)))
            (is (= "ccpool_TEST" (nth argv 1)))
            (is (= "-p" (nth argv 2)))
            (let [brief (nth argv 3)]
              (is (str/includes? brief "https://github.com/MithraAI/istari"))
              (is (str/includes? brief "PR:     #482"))
              (is (str/includes? brief "drive it in a browser and screenshot each step")
                  "the measure reaches the runner with its @cloud marker stripped")
              (is (not (str/includes? brief "@cloud")))
              (is (str/includes? brief "post ONE comment on PR #482")
                  "the comment IS the callback — pr_watch polls it")))
          (testing "the evidence says how to read the result, since nothing here can"
            (is (str/includes? (output f) "session_01TEST"))
            (is (str/includes? (output f) "PENDING, not met"))
            (is (str/includes? (output f) "teleport")))
          (testing "and the local bar beside it still ran locally"
            (is (= "0" (get (headers (fs/path dir "evidence" "units.txt")) "exit")))
            (is (str/includes? (output (fs/path dir "evidence" "units.txt")) "local-ran"))))))))

(deftest the-environment-comes-from-the-project-so-nobody-has-to-remember-it
  ;; It lived only in SWARMKHAZAD_CLOUD_ENV, which is the worst place for the
  ;; one setting an @cloud bar cannot run without: an unmemorable id, in a shell
  ;; rc, invisible on the page that claims to declare how a project runs.
  (with-task {"README.md" "one\n"} cloud-metrics
    (fn [{:keys [dir measure]}]
      (let [bin (stub-bin! dir {:pr "482"})
            home (fs/parent (fs/parent dir))]
        (git (str (fs/path dir "worktrees" "fixture")) "remote" "set-url" "origin"
             "https://github.com/MithraAI/istari.git")
        (write! (fs/path home "projects" "p.edn")
                (pr-str {:repos [] :roles [] :cloud-env "ccpool_FROMPROJECT"}))
        (write! (fs/path dir "project") "p\n")
        ;; deliberately NO SWARMKHAZAD_CLOUD_ENV in this environment
        (measure {"PATH" (str bin ":" (System/getenv "PATH"))})
        (let [f (fs/path dir "evidence" "checkout-works.txt")
              argv (str/split (slurp (str (fs/path dir "claude-argv"))) #"\u0000")]
          (is (= "pending" (get (headers f) "exit")) "it dispatched, with no variable set")
          (is (= "ccpool_FROMPROJECT" (nth argv 1))
              "and at the environment the PROJECT names"))))))

(deftest the-variable-still-overrides-the-project-for-a-one-off
  (with-task {"README.md" "one\n"} cloud-metrics
    (fn [{:keys [dir measure]}]
      (let [bin (stub-bin! dir {:pr "482"})
            home (fs/parent (fs/parent dir))]
        (git (str (fs/path dir "worktrees" "fixture")) "remote" "set-url" "origin"
             "https://github.com/MithraAI/istari.git")
        (write! (fs/path home "projects" "p.edn")
                (pr-str {:repos [] :roles [] :cloud-env "ccpool_FROMPROJECT"}))
        (write! (fs/path dir "project") "p\n")
        (measure {"SWARMKHAZAD_CLOUD_ENV" "ccpool_OVERRIDE"
                  "PATH" (str bin ":" (System/getenv "PATH"))})
        (let [argv (str/split (slurp (str (fs/path dir "claude-argv"))) #"\u0000")]
          (is (= "ccpool_OVERRIDE" (nth argv 1))
              "most specific first — a one-off dispatch elsewhere must not need a project edit"))))))

(deftest a-cloud-bar-that-cannot-be-dispatched-says-which-of-the-three-reasons
  (testing "an owner the environment cannot reach"
    (with-task {"README.md" "one\n"} cloud-metrics
      (fn [{:keys [dir measure]}]
        (let [bin (stub-bin! dir {:pr "482"})
              r (measure {"SWARMKHAZAD_CLOUD_ENV" "ccpool_TEST"
                          "PATH" (str bin ":" (System/getenv "PATH"))})
              f (fs/path dir "evidence" "checkout-works.txt")]
          ;; the fixture's origin is acme/fixture, and the runner is baked for
          ;; one account only — a dispatch here would fail in the cloud, where
          ;; no one reads the error
          (is (= "blocked" (get (headers f) "exit")))
          (is (str/includes? (output f) "only reach github.com/MithraAI"))
          (is (str/includes? (output f) "example.invalid/acme/fixture")
              "and it names the origin it refused — the whole URL, since the whole URL is what is checked")
          (is (not (fs/exists? (fs/path dir "claude-argv")))
              "and nothing was dispatched")
          (is (= 1 (:exit r)))))))
  (testing "no environment id"
    (with-task {"README.md" "one\n"} cloud-metrics
      (fn [{:keys [dir measure]}]
        (let [bin (stub-bin! dir {:pr "482"})]
          (git (str (fs/path dir "worktrees" "fixture")) "remote" "set-url" "origin"
               "https://github.com/MithraAI/istari.git")
          (measure {"PATH" (str bin ":" (System/getenv "PATH"))})
          (let [f (fs/path dir "evidence" "checkout-works.txt")]
            (is (= "blocked" (get (headers f) "exit")))
            (is (str/includes? (output f) "no self-hosted environment for this task"))
            (is (str/includes? (output f) "on the project")
                "the fix is a field on the page, not an id to remember")
            (is (not (fs/exists? (fs/path dir "claude-argv")))))))))
  (testing "nothing to answer on — no PR, and no ticket either"
    (with-task {"README.md" "one\n"} cloud-metrics
      (fn [{:keys [dir measure]}]
        (let [bin (stub-bin! dir {:pr nil})]
          (git (str (fs/path dir "worktrees" "fixture")) "remote" "set-url" "origin"
               "https://github.com/MithraAI/istari.git")
          (measure {"SWARMKHAZAD_CLOUD_ENV" "ccpool_TEST"
                    "PATH" (str bin ":" (System/getenv "PATH"))})
          (let [f (fs/path dir "evidence" "checkout-works.txt")]
            (is (= "blocked" (get (headers f) "exit")))
            (is (str/includes? (output f) "no return channel"))
            (is (str/includes? (output f) "ticket:")
                "and it names the other channel, so an investigation is not left guessing")
            (is (not (fs/exists? (fs/path dir "claude-argv")))
                "with neither, the findings have nowhere to land, so it is not sent")))))))

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

;; --------------------------------------------------------- more than one repo

(defn with-two-repo-task
  "gobel has a bb.edn so `bb test` is its test command; cirdan has nothing, so
   its own answer is `no test command detected`. One shared `repo tests` bar
   could only ever have carried one of those two answers."
  [metrics f]
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-evidence2."})
        home (str (fs/path sandbox "home"))
        gobel (str (fs/path sandbox "src" "gobel"))
        cirdan (str (fs/path sandbox "src" "cirdan"))
        id "t-ev2"
        env {"SWARMKHAZAD_HOME" home "SWARMKHAZAD_TASK_ID" id}]
    (try
      (make-source-repo! gobel {"README.md" "g\n"
                                "bb.edn" "{:tasks {test (println \"gobel tests ran\")}}\n"})
      (make-source-repo! cirdan {"README.md" "c\n"})
      (run {:env env} cli "new" id "--repo" gobel "--repo" cirdan)
      (let [dir (fs/path home "tasks" id)]
        (spit (str (fs/path dir "roles")) "run claude task\n")
        (spit (str (fs/path dir "repos")) (str gobel "\n" cirdan "\n"))
        (spit (str (fs/path dir "metrics.md")) metrics)
        (run {:env env} cli "prepare" id)
        (f {:dir dir
            :measure (fn [session repo]
                       (run {:dir (str (fs/path dir "worktrees" repo))
                             :env (merge env {"SWARMKHAZAD_SESSION" session
                                              "SWARMKHAZAD_TASK_DIR" (str dir)})
                             :ok? false}
                            "bb" (str (fs/path scripts "run_evidence.bb"))))}))
      (finally
        (fs/delete-tree sandbox)))))

(deftest a-bar-tagged-with-a-repo-is-measured-in-that-repo-and-nowhere-else
  (with-two-repo-task
    (str "# t-ev2 — bars\n\n## Quantitative\n"
         "- the exporter is repointed @gobel — bar: no matches — measure: `echo GOBEL-BAR`\n"
         "- the image is pinned @cirdan — bar: pinned — measure: `echo CIRDAN-BAR`\n"
         "- the cluster answers — bar: endpoints exist — measure: `echo CLUSTER-BAR`\n")
    (fn [{:keys [dir measure]}]
      (let [ev (fs/path dir "evidence")
            files #(set (map (comp str fs/file-name) (fs/list-dir ev)))]
        (measure "run_gobel" "gobel")
        (testing "gobel's session runs gobel's bar and the untagged one, not cirdan's"
          (is (= #{"repo-tests-gobel.txt" "the-exporter-is-repointed.txt" "the-cluster-answers.txt"}
                 (files))
              "a bar measured in the wrong worktree is a wrong answer nobody can see is wrong"))
        (measure "run_cirdan" "cirdan")
        (testing "cirdan's session adds its own, and its own repo tests"
          (is (= #{"repo-tests-gobel.txt" "repo-tests-cirdan.txt"
                   "the-exporter-is-repointed.txt" "the-image-is-pinned.txt"
                   "the-cluster-answers.txt"}
                 (files))))
        (testing "each repo's test command is recorded against its own name"
          (is (str/includes? (output (fs/path ev "repo-tests-gobel.txt")) "gobel tests ran"))
          (is (str/includes? (output (fs/path ev "repo-tests-cirdan.txt")) "no test command detected")
              "one shared file would have had the second session overwrite the first, and the last writer's repo would silently be the task's answer"))
        (testing "the tagged bars ran where they were tagged"
          (is (str/includes? (output (fs/path ev "the-exporter-is-repointed.txt")) "GOBEL-BAR"))
          (is (str/includes? (output (fs/path ev "the-image-is-pinned.txt")) "CIRDAN-BAR"))
          (is (str/includes? (headers (fs/path ev "the-image-is-pinned.txt")) "cirdan")
              "and the header says the worktree it ran in"))))))

(deftest a-one-repo-task-keeps-the-name-every-reader-already-uses
  (with-task {"README.md" "one\n"} (str "# t-ev — bars\n\n## Quantitative\n- x — bar: y — measure: `echo z`\n")
    (fn [{:keys [dir measure]}]
      (measure)
      (is (fs/regular-file? (fs/path dir "evidence" "repo-tests.txt"))
          "one repo, so there is nothing to disambiguate and the suffix would only break old readers"))))
