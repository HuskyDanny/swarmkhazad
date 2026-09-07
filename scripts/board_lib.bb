#!/usr/bin/env bb

;; board-lib — the task's board: one card per task, moving through the role
;; lanes as git handoffs flow, ending in `done`.
;;
;; <task>/state/board/tasks.tsv, one row per card:
;;   name  lane  created_at  updated_at  handed
;;
;; The lane is a ROLE, never a session. A role with three repos has three
;; sessions and hands off three times; `handed` is the ones that have, and the
;; card only moves when it covers them all. A review that started on the first
;; handoff would be reading two trees that are still moving.
;;
;; A row written before `handed` existed splits to nil there and reads as an
;; empty set, which is the right answer for a one-repo task.
;;
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
                   (zipmap [:name :lane :created-at :updated-at :handed] (str/split line #"\t" -1)))))
      [])))

(defn write-rows! [ctx rows]
  (let [file (tasks-file ctx)
        tmp (fs/create-temp-file {:dir (fs/parent file) :prefix ".tasks."})]
    (spit (str tmp) (apply str (for [r rows]
                                (str (str/join "\t" [(:name r) (:lane r) (:created-at r) (:updated-at r)
                                                     (or (:handed r) "")])
                                     "\n"))))
    (fs/move tmp file {:replace-existing true :atomic-move true})))

(defn card-row [ctx name]
  (some #(when (= name (:name %)) %) (rows ctx)))

(defn card-lane [ctx name]
  (:lane (card-row ctx name)))

(defn handed
  "The sessions that have handed off in the card's current lane."
  [row]
  (->> (str/split (or (:handed row) "") #",") (remove str/blank?) set))

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
        (write-rows! ctx (mapv #(if (= name (:name %))
                                  (assoc % :lane lane :handed "" :updated-at (timestamp))
                                  %)
                               current))))))

(defn hand-off!
  "Record that one session handed off, and move the card to `next-lane` only
   once every session of the lane's role has.

   `role->sessions` maps a role to its session ids. A lane with no entry — an
   old card whose lane is a session id, or `done` — moves on the first handoff,
   which is what a one-repo task has always done."
  [ctx name session next-lane role->sessions]
  (with-lock ctx
    (fn []
      (let [current (rows ctx)
            row (some #(when (= name (:name %)) %) current)]
        (when-not row (throw (ex-info (str "Unknown card: " name) {})))
        (let [expected (set (get role->sessions (:lane row)))]
          (if (and (seq expected) (not (contains? expected session)))
            ;; The sender's role is not the lane's, so its turn is already over:
            ;; the card moved on when the last of its siblings handed off, and
            ;; this is one of them catching up. Counting it against the role
            ;; running NOW would hold it for a turn it never belonged to, and
            ;; put its name in that role's tally.
            true
            (let [done (conj (handed row) session)
                  moving? (every? done expected)
                  update (if moving?
                           {:lane next-lane :handed ""}
                           {:handed (str/join "," (sort done))})]
              ;; A held handoff comes back through here on every daemon tick,
              ;; and rewriting the board once a second would bury the one
              ;; timestamp a reader wants — when the turn last actually moved.
              (when (or moving? (not= done (handed row)))
                (write-rows! ctx (mapv #(if (= name (:name %))
                                          (merge % update {:updated-at (timestamp)})
                                          %)
                                       current)))
              moving?)))))))
