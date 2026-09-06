#!/usr/bin/env bb

;; stop_handoff_daemon.bb <task-id> — ask handoffd to stop (stop file), then TERM,
;; then KILL after a grace period. Idempotent.

(ns stop-handoff-daemon
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "task_lib.bb")))

(def timeout-ms 5000)

(defn alive? [pid] (zero? (:exit (process/sh {:continue true} "kill" "-0" pid))))

(defn stop! [ctx]
  (let [daemon-dir (:daemon-dir ctx)
        pid-file (fs/path daemon-dir "handoffd.pid")
        stop-file (fs/path daemon-dir "stop")]
    (fs/create-dirs daemon-dir)
    (spit (str stop-file) "")
    (when (fs/exists? pid-file)
      (let [pid (str/trim (slurp (str pid-file)))]
        (when (and (re-matches #"[0-9]+" pid) (alive? pid))
          (process/sh {:continue true} "kill" "-TERM" pid)
          (loop [waited 0]
            (when (and (< waited timeout-ms) (alive? pid))
              (Thread/sleep 100)
              (recur (+ waited 100))))
          (when (alive? pid)
            (process/sh {:continue true} "kill" "-KILL" pid))))
      (fs/delete-if-exists pid-file))
    (fs/delete-if-exists stop-file)))

(defn -main [& args]
  (when (str/blank? (first args))
    (binding [*out* *err*] (println "Usage: stop_handoff_daemon.bb <task-id>"))
    (System/exit 1))
  (stop! (task-lib/task-ctx (first args))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
