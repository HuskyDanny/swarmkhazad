#!/usr/bin/env bb

;; swarmkhazad — the CLI. One task folder in, one swarm out. See README.md.

(ns swarmkhazad
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "task_lib.bb")))

(def usage-text
  (str "Usage:\n"
       "  swarmkhazad new <task-id> [--repo <path>]...   scaffold goal.md, metrics.md, roles\n"
       "  swarmkhazad prepare <task-id>                  layout, clones, worktrees, mail dirs, roles.tsv\n"
       "  swarmkhazad paths <task-id>                    print the path map\n"))

(defn usage! []
  (binding [*out* *err*] (print usage-text))
  (System/exit 1))

(defn goal-template [task-id]
  (str "# " task-id " — <what this task is, one line>\n"
       "Opened by <who> · " (java.time.LocalDate/now) "\n\n"
       "## Goal\n- [ ] <role or repo> — <the outcome, one line>\n\n"
       "## Not-goal\n- <deliberately not doing X — why>\n\n"
       "## Hints\n- <absolute path> — why it matters\n"))

(defn metrics-template [task-id]
  (str "# " task-id " — bars\n\n"
       "## Quantitative\n- <metric> — bar: <threshold> — measure: `<command>`\n\n"
       "## Qualitative\n- <property> — bar: <what passing looks like> — judged by: Allen\n"))

(defn new! [task-id args]
  (let [repos (->> (partition 2 1 (cons nil args))
                   (keep (fn [[flag value]] (when (= flag "--repo") value))))
        ctx (task-lib/task-ctx task-id)]
    (when (fs/exists? (:task-dir ctx))
      (task-lib/fail! (str "task already exists: " (:task-dir ctx))))
    (fs/create-dirs (:task-dir ctx))
    (spit (str (:goal-file ctx)) (goal-template task-id))
    (spit (str (:metrics-file ctx)) (metrics-template task-id))
    (spit (str (:roles-file ctx)) (task-lib/roles-template repos))
    (println (str (:task-dir ctx)))))

(defn prepare! [task-id]
  (let [result (task-lib/prepare! (task-lib/task-ctx task-id))]
    (println "task:" (:task-dir result))
    (doseq [{:keys [clone fresh branch sha upstream]} (:clones result)]
      (println (str "clone: " clone (if fresh (str " " branch " @ " sha " origin=" (or upstream "none")) " (existing)"))))
    (doseq [{:keys [role harness receive-mode model worktree-path]} (:roles result)]
      (println (str "role: " role " " harness " " receive-mode " model=" model " " worktree-path)))))

(defn paths! [task-id]
  (doseq [[k v] (sort-by (comp str key) (task-lib/task-ctx task-id))]
    (println (name k) (str v))))

(defn -main [& args]
  (try
    (case (first args)
      "new" (if (second args) (new! (second args) (drop 2 args)) (usage!))
      "prepare" (if (second args) (prepare! (second args)) (usage!))
      "paths" (if (second args) (paths! (second args)) (usage!))
      (usage!))
    (catch clojure.lang.ExceptionInfo e
      (task-lib/fail! (str "swarmkhazad: " (ex-message e))))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
