#!/usr/bin/env bb

;; done_with_current.bb — finish the in-process item (task or batch, per the
;; role's receive mode): stamp completed_at, move it to inbox/completed,
;; archive this role's pane, then print MAIL_WAITING or NO_TASK.

(ns done-with-current
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "handoff_lib.bb")))

(defn fail! [status & lines]
  (binding [*out* *err*] (doseq [l lines] (println l)))
  (System/exit status))

(defn complete-file! [source completed-dir stamp]
  (let [target (fs/path completed-dir (fs/file-name source))]
    (when (fs/exists? target) (fs/delete target))
    (handoff-lib/set-header! source "completed_at" stamp)
    (fs/move source target)
    (println "COMPLETED:" (str target))))

(defn -main []
  (let [ctx (task-lib/ctx-from-env)
        role (handoff-lib/role ctx)
        mode (handoff-lib/role-receive-mode ctx role)
        {:keys [files batches]} (handoff-lib/in-process-state ctx role)
        completed (handoff-lib/completed-dir ctx role)
        stamp (handoff-lib/timestamp)]
    (fs/create-dirs completed)
    (case mode
      "task"
      (do (when (seq batches) (fail! 2 "CURRENT_WORK_IS_BATCH but this role receives in task mode."))
          (when (empty? files) (fail! 1 "NO_CURRENT_TASK"))
          (when (> (count files) 1) (fail! 2 "AMBIGUOUS_TASK_STATE: multiple tasks are in process." (str/join "\n" (map #(str "- " %) files))))
          (complete-file! (first files) completed stamp))
      "batch"
      (do (when (seq files) (fail! 2 "CURRENT_WORK_IS_SINGLE_TASK but this role receives in batch mode."))
          (when (empty? batches) (fail! 1 "NO_CURRENT_BATCH"))
          (when (> (count batches) 1) (fail! 2 "AMBIGUOUS_TASK_STATE: multiple batches are in process."))
          (let [source (first batches)
                target (fs/path completed (fs/file-name source))]
            (when (fs/exists? target) (fail! 2 (str "AMBIGUOUS_TASK_STATE: completed batch already exists: " target)))
            (fs/create-dirs target)
            (doseq [f (handoff-lib/handoff-files source)]
              (complete-file! f target stamp))
            (fs/delete source)
            (println "COMPLETED_BATCH:" (str target))))
      (fail! 2 (str "INVALID_RECEIVE_MODE for role " role)))
    (handoff-lib/finish-done! ctx role)))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (try
    (-main)
    (catch clojure.lang.ExceptionInfo e
      (fail! (or (:exit (ex-data e)) 1) (ex-message e)))))
