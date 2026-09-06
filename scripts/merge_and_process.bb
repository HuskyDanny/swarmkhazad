#!/usr/bin/env bb

;; merge_and_process.bb <sender> <commit> — merge a handoff's commit into the
;; current worktree by bare SHA. Every role's worktree hangs off the same clone,
;; so the object is already local; no fetch, no remote, no branch name.

(ns merge-and-process
  (:require [babashka.process :as process]
            [clojure.string :as str]))

(defn exit! [status message]
  (binding [*out* *err*] (when message (println message)))
  (System/exit status))

(defn already-merged? [sha]
  (zero? (:exit (process/sh {:continue true} "git" "merge-base" "--is-ancestor" sha "HEAD"))))

(defn merge-commit! [sender sha]
  (when-not (already-merged? sha)
    (let [result (process/sh {:continue true} "git" "merge" "--no-edit" "-m" (str "Merge " sender " " sha) sha)]
      (when-not (zero? (:exit result))
        (exit! 1 (str/trim (str (:err result) "\n" (:out result)))))))
  (println "MERGED:" sender sha))

(defn -main [& args]
  (when (some #{"--help" "-h"} args)
    (println "Usage: merge_and_process.bb <sender> <commit>")
    (System/exit 0))
  (when (not= 2 (count args))
    (exit! 1 "Usage: merge_and_process.bb <sender> <commit>"))
  (merge-commit! (first args) (second args)))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
