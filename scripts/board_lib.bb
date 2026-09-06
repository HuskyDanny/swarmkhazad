#!/usr/bin/env bb

;; board-lib — the task's board: one card per task, moving through the role
;; lanes as git handoffs flow, ending in `done`.
;;
;; <task>/state/board/tasks.tsv, one row per card: name  lane  created_at  updated_at
;; Callers load this file and call in-process; there is no CLI.

(ns board-lib
  (:require [babashka.fs :as fs]
            [clojure.string :as str])
  (:import [java.nio.channels FileChannel]
           [java.nio.file OpenOption StandardOpenOption]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "task_lib.bb")))

(defn tasks-file [ctx] (fs/path (:board-dir ctx) "tasks.tsv"))

(defn with-lock [ctx f]
  (let [path (fs/path (:board-dir ctx) "tasks.lock")
        options (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE])]
    (fs/create-dirs (:board-dir ctx))
    (with-open [channel (FileChannel/open path options)]
      (.lock channel)
      (f))))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT (java.time.Instant/now)))

(defn rows
  "The cards, as maps."
  [ctx]
  (let [file (tasks-file ctx)]
    (if (fs/exists? file)
      (->> (str/split-lines (slurp (str file)))
           (remove str/blank?)
           (mapv (fn [line]
                   (zipmap [:name :lane :created-at :updated-at] (str/split line #"\t" -1)))))
      [])))

(defn write-rows! [ctx rows]
  (let [file (tasks-file ctx)
        tmp (fs/create-temp-file {:dir (fs/parent file) :prefix ".tasks."})]
    (spit (str tmp) (apply str (for [r rows] (str (str/join "\t" [(:name r) (:lane r) (:created-at r) (:updated-at r)]) "\n"))))
    (fs/move tmp file {:replace-existing true :atomic-move true})))

(defn card-lane [ctx name]
  (some #(when (= name (:name %)) (:lane %)) (rows ctx)))

(defn create-card! [ctx name lane]
  (with-lock ctx
    (fn []
      (let [current (rows ctx)]
        (when (some #(= name (:name %)) current)
          (throw (ex-info (str "Duplicate card: " name) {})))
        (let [now (timestamp)]
          (write-rows! ctx (conj current {:name name :lane lane :created-at now :updated-at now})))))))

(defn set-lane! [ctx name lane]
  (with-lock ctx
    (fn []
      (let [current (rows ctx)]
        (when-not (some #(= name (:name %)) current)
          (throw (ex-info (str "Unknown card: " name) {})))
        (write-rows! ctx (mapv #(if (= name (:name %)) (assoc % :lane lane :updated-at (timestamp)) %) current))))))
