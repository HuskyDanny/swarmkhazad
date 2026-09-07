#!/usr/bin/env bb

;; ready_for_next.bb — accept the next inbox item for this role.
;;
;; Prints NO_TASK, or TASK: <path> (task mode) / BATCH: <dir> (batch mode) with
;; the payload, after merging any inbound git_handoff commit into this role's
;; worktree. If work is already in process it is re-printed, not re-dequeued.
;; Refuses ambiguous state (two in-process items) rather than guessing.

(ns ready-for-next
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "handoff_lib.bb")))

(defn fail! [status & lines]
  (binding [*out* *err*] (doseq [l lines] (println l)))
  (System/exit status))

(defn current-head [worktree]
  (let [r (process/sh {:continue true :dir (str worktree)} "git" "rev-parse" "--short=10" "HEAD")]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn merge-inbound! [worktree file]
  (when (= "git_handoff" (handoff-lib/header-field file "type"))
    (let [from (handoff-lib/header-field file "from")
          commit (handoff-lib/header-field file "commit")]
      (when (and worktree from commit)
        (let [r (process/sh {:continue true :dir (str worktree)} "bb" (str (fs/path script-dir "merge_and_process.bb")) from commit)]
          (print (:out r))
          (when-not (zero? (:exit r))
            (fail! 1 (str/trim (str (:err r) "\n" (:out r))))))))))

(defn accept-one! [worktree source target]
  (when (fs/exists? target)
    (fail! 2 (str "AMBIGUOUS_TASK_STATE: target already exists: " target)))
  (fs/move source target)
  (handoff-lib/set-header! target "dequeued_at" (handoff-lib/timestamp))
  (when-let [head (current-head worktree)]
    (handoff-lib/set-header! target "task_base_commit" head))
  (merge-inbound! worktree target))

(defn task-mode! [ctx role worktree]
  (let [{:keys [dir files batches]} (handoff-lib/in-process-state ctx role)]
    (when (seq batches)
      (fail! 2 "TASK_IN_PROCESS_IS_BATCH: this role receives in task mode but a batch is in process." (str/join "\n" (map #(str "- " %) batches))))
    (when (> (count files) 1)
      (fail! 2 "AMBIGUOUS_TASK_STATE: multiple tasks are already in process." (str/join "\n" (map #(str "- " %) files))))
    (if (= 1 (count files))
      (do (merge-inbound! worktree (first files))
          (handoff-lib/print-task (first files)))
      (let [new-files (handoff-lib/handoff-files (handoff-lib/new-dir ctx role))]
        (if (empty? new-files)
          (println "NO_TASK")
          (let [source (first new-files)
                target (fs/path dir (fs/file-name source))]
            (accept-one! worktree source target)
            (handoff-lib/print-task target)))))))

(defn new-batch-dir [in-process]
  (loop [n 1]
    (let [dir (fs/path in-process (format "batch_%s_%06d" (handoff-lib/stamp) n))]
      (if (fs/exists? dir) (recur (inc n)) dir))))

(defn batch-mode! [ctx role worktree]
  (let [{:keys [dir files batches]} (handoff-lib/in-process-state ctx role)]
    (when (seq files)
      (fail! 2 "TASK_IN_PROCESS_IS_SINGLE: this role receives in batch mode but a single task is in process." (str/join "\n" (map #(str "- " %) files))))
    (when (> (count batches) 1)
      (fail! 2 "AMBIGUOUS_TASK_STATE: multiple batches are already in process." (str/join "\n" (map #(str "- " %) batches))))
    (if (= 1 (count batches))
      (do (doseq [f (handoff-lib/handoff-files (first batches))] (merge-inbound! worktree f))
          (handoff-lib/print-batch (first batches)))
      (let [new-files (handoff-lib/handoff-files (handoff-lib/new-dir ctx role))]
        (if (empty? new-files)
          (println "NO_TASK")
          (let [priority (or (handoff-lib/header-field (first new-files) "priority") "50")
                selected (filter #(= priority (or (handoff-lib/header-field % "priority") "50")) new-files)
                batch (new-batch-dir dir)]
            (fs/create-dirs batch)
            (doseq [source selected]
              (accept-one! worktree source (fs/path batch (fs/file-name source))))
            (handoff-lib/print-batch batch)))))))

(defn -main []
  (let [ctx (task-lib/ctx-from-env)
        role (handoff-lib/session ctx)
        row (handoff-lib/session-row ctx role)
        worktree (when (:repo row) (:worktree-path row))]
    (doseq [d [(handoff-lib/new-dir ctx role) (handoff-lib/in-process-dir ctx role) (handoff-lib/completed-dir ctx role)]]
      (fs/create-dirs d))
    (case (handoff-lib/session-receive-mode ctx role)
      "batch" (batch-mode! ctx role worktree)
      "task" (task-mode! ctx role worktree)
      (fail! 2 (str "INVALID_RECEIVE_MODE for role " role)))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (try
    (-main)
    (catch clojure.lang.ExceptionInfo e
      (fail! (or (:exit (ex-data e)) 1) (ex-message e)))))
