#!/usr/bin/env bb

;; run_evidence.bb — the run role's one job: measurements, not opinions.
;;
;; From the role's worktree it executes the repo's own test command and every
;; `measure:` command in metrics.md's Quantitative section, and writes one file
;; per bar under <task>/evidence/:
;;
;;   bar: <name>            command: <cmd>      cwd: <worktree>
;;   threshold: <bar text>  started_at: <ts>    duration_ms: <n>
;;   exit: <code>
;;   --- output ---
;;   <stdout and stderr, in order, clipped>
;;
;; The goal judge reads that directory at every Stop, so a bar is met when its
;; file says so, not when a role says so. Local only: SWARMKHAZAD_RUN_REMOTE is
;; a seam for a later remote runner and must stay unset here.

(ns run-evidence
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "task_lib.bb")))

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
   for an acceptance criterion to be: recorded, and invisible."
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
       :command (some->> measure (re-find #"`([^`]+)`") second)})))

(defn substitute [command ctx]
  (-> command
      (str/replace "<task-id>" (:task-id ctx))
      (str/replace "<id>" (:task-id ctx))
      (str/replace "<task-dir>" (str (:task-dir ctx)))))

(defn bars
  "Every Quantitative bar, names made unique. A bar whose measure is prose comes
   back with a nil :command; callers that run things check for it."
  [ctx metrics-md]
  (let [parsed (keep parse-bar (quantitative-lines metrics-md))]
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
  "Run everything this session owns; return [{:id :exit :file}]."
  [ctx worktree metrics-md repo]
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
        rows (cons repo-tests (filter #(mine? repo %) (bars ctx metrics-md)))]
    (vec (for [bar rows]
           (let [result (if (:command bar)
                          (run-command (:command bar) worktree)
                          {:exit "none"
                           :output (if (str/starts-with? (:id bar) repo-tests-bar)
                                     "no test command detected in the worktree (no package.json, bb.edn, pyproject.toml, go.mod, Cargo.toml or Makefile test target)\n"
                                     (str "this bar has no command to run — its measure is prose, so the run role\n"
                                          "has to satisfy it and overwrite this file with what it observed:\n\n"
                                          "  " (or (:measure bar) (:name bar)) "\n"))
                           :duration-ms 0 :started-at (str (java.time.Instant/now))})
                 file (write-evidence! ctx bar worktree result)]
             {:id (:id bar) :exit (:exit result) :duration-ms (:duration-ms result) :file (str file)})))))

(defn -main [& args]
  (when (seq (System/getenv "SWARMKHAZAD_RUN_REMOTE"))
    (binding [*out* *err*]
      (println "SWARMKHAZAD_RUN_REMOTE is set but there is no remote runner; evidence runs locally only. Unset it."))
    (System/exit 2))
  (let [ctx (task-lib/ctx-from-env)
        session (or (first args) (System/getenv "SWARMKHAZAD_SESSION"))
        _ (when (str/blank? session) (task-lib/fail! "SWARMKHAZAD_SESSION is not set"))
        row (task-lib/session-row ctx session)
        worktree (or (:worktree-path row) (str (fs/cwd)))
        metrics-md (if (fs/regular-file? (:metrics-file ctx)) (slurp (str (:metrics-file ctx))) "")
        ;; Only past one repo: a one-repo task's `repo-tests.txt` is the name
        ;; every reader and every old task already uses.
        repo (when (> (count (distinct (keep :repo (task-lib/read-sessions-tsv ctx)))) 1)
               (:repo row))
        results (measure-all! ctx worktree metrics-md repo)]
    (doseq [{:keys [id exit duration-ms file]} results]
      (println (format "%-24s exit=%-5s %6d ms  %s" id (str exit) duration-ms file)))
    (println (str "evidence: " (count results) " files in " (:evidence-dir ctx)))
    (System/exit (if (every? #(= 0 (:exit %)) results) 0 1))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
