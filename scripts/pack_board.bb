#!/usr/bin/env bb

;; pack_board.bb — the task's board: one card per task, moving through the role
;; lanes as git handoffs flow, ending in `done`. Lives at
;; <task>/state/board/tasks.tsv, one row: name lane created_at updated_at task_id.
;;
;;   pack_board.bb create --name <name> --lane <lane> [--task-id <id>]
;;   pack_board.bb move --name <name> --lane <lane>
;;   pack_board.bb done --name <name>
;;   pack_board.bb list
;;   pack_board.bb lanes
;;   pack_board.bb archive --role <role>       snapshot the role's tmux pane to state/sessions/<role>/pane.txt
;;   pack_board.bb archive-all
;;
;; The task is taken from SWARMKHAZAD_TASK_ID; pass --task <id> to name another.

(ns pack-board
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str])
  (:import [java.nio.channels FileChannel]
           [java.nio.file OpenOption StandardOpenOption]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "task_lib.bb")))

(def flags {"--name" :name "--lane" :lane "--role" :role "--task-id" :task-id "--task" :task})

(defn exit! [status message]
  (binding [*out* *err*] (when message (println message)))
  (System/exit status))

(defn parse-args [args]
  (loop [args args opts {} positionals []]
    (if (empty? args)
      (assoc opts :positional positionals)
      (let [head (first args)
            flag (get flags head)]
        (cond
          (nil? flag) (recur (rest args) opts (conj positionals head))
          (nil? (second args)) (exit! 1 (str "Missing value for " head))
          :else (recur (drop 2 args) (assoc opts flag (second args)) positionals))))))

(defn ctx [opts]
  (if-let [id (:task opts)]
    (task-lib/task-ctx id)
    (task-lib/ctx-from-env)))

(defn tasks-file [ctx] (fs/path (:board-dir ctx) "tasks.tsv"))

(defn with-board-lock [ctx f]
  (let [path (fs/path (:board-dir ctx) "tasks.lock")
        options (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE])]
    (fs/create-dirs (:board-dir ctx))
    (with-open [channel (FileChannel/open path options)]
      (.lock channel)
      (f))))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT (java.time.Instant/now)))

(defn read-rows [file]
  (if (fs/exists? file)
    (->> (str/split-lines (slurp (str file))) (remove str/blank?) vec)
    []))

(defn write-rows! [file rows]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file) :prefix ".tasks."})]
    (spit (str tmp) (if (seq rows) (str (str/join "\n" rows) "\n") ""))
    (fs/move tmp file {:replace-existing true :atomic-move true})))

(defn row-name [line] (first (str/split line #"\t")))

(defn find-row [rows name]
  (some #(when (= name (row-name %)) %) rows))

(defn require-value! [value label]
  (when (str/blank? value) (exit! 1 (str "Missing " label))))

(defn create! [opts]
  (let [{:keys [name lane task-id]} opts
        c (ctx opts)
        file (tasks-file c)]
    (require-value! name "task name")
    (require-value! lane "lane")
    (with-board-lock c
      (fn []
        (let [rows (read-rows file)]
          (when (find-row rows name)
            (exit! 1 (str "Duplicate task name: " name)))
          (let [now (timestamp)]
            (write-rows! file (conj rows (str/join "\t" [name lane now now (or task-id name)])))))))))

(defn set-lane! [opts lane]
  (let [{:keys [name]} opts
        c (ctx opts)
        file (tasks-file c)]
    (require-value! name "task name")
    (require-value! lane "lane")
    (with-board-lock c
      (fn []
        (let [rows (read-rows file)]
          (when-not (find-row rows name)
            (exit! 1 (str "Unknown task name: " name)))
          (write-rows! file
                       (mapv (fn [line]
                               (let [[n _ created _ task-id] (str/split line #"\t" -1)]
                                 (if (= n name)
                                   (str/join "\t" [n lane created (timestamp) task-id])
                                   line)))
                             rows)))))))

(defn list! [opts]
  (let [file (tasks-file (ctx opts))]
    (when (fs/exists? file) (print (slurp (str file))) (flush))))

(defn lanes! [opts]
  (doseq [role (task-lib/role-names (ctx opts))] (println role)))

(defn tmux-socket [ctx]
  (when (fs/regular-file? (:tmux-socket-file ctx))
    (not-empty (str/trim (slurp (str (:tmux-socket-file ctx)))))))

(defn capture-pane [ctx role]
  (when-let [socket (tmux-socket ctx)]
    (let [result (process/sh {:continue true} "tmux" "-S" socket "capture-pane" "-p" "-t" (task-lib/session-name role) "-S" "-")]
      (when (zero? (:exit result)) (:out result)))))

(defn archive-session! [ctx role]
  (when-let [text (or (System/getenv "SWARMKHAZAD_PANE_STUB") (capture-pane ctx role))]
    (let [file (fs/path (:sessions-dir ctx) role "pane.txt")]
      (fs/create-dirs (fs/parent file))
      (spit (str file) text))))

(defn archive! [opts]
  (let [role (or (:role opts) (second (:positional opts)))]
    (require-value! role "role")
    (archive-session! (ctx opts) role)))

(defn archive-all! [opts]
  (let [c (ctx opts)]
    (doseq [role (task-lib/role-names c)]
      (archive-session! c role))))

(def commands
  {"create" create!
   "move" #(set-lane! % (:lane %))
   "done" #(set-lane! % "done")
   "list" list!
   "lanes" lanes!
   "archive" archive!
   "archive-all" archive-all!})

(defn -main [& args]
  (let [opts (parse-args args)
        command (get commands (first (:positional opts)))]
    (try
      (if command
        (command opts)
        (exit! 1 "Usage: pack_board.bb create|move|done|list|lanes|archive|archive-all [--name n] [--lane l] [--role r] [--task id]"))
      (catch clojure.lang.ExceptionInfo e
        (exit! (or (:exit (ex-data e)) 1) (ex-message e))))
    (System/exit 0)))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
