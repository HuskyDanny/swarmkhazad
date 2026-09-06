#!/usr/bin/env bb

;; handoff-lib — the mail transport's shared vocabulary: where a role's mail
;; lives, how a handoff file is parsed and rewritten, timestamps, sequence
;; numbers, and the TASK/BATCH printouts every receive helper emits.
;;
;; All mail lives in the task folder, never in a repo:
;;
;;   <task>/mail/<role>/outbox/{,tmp/}   swarm_handoff.bb writes here; handoffd consumes
;;   <task>/mail/<role>/sent/ failed/    what handoffd did with each outbound file
;;   <task>/mail/<role>/inbox/new/       delivered, waiting
;;   <task>/mail/<role>/inbox/in_process/  accepted by ready_for_next; batches are batch_* dirs
;;   <task>/mail/<role>/inbox/completed/ finished by done_with_current
;;
;; Every helper runs inside a role session, where SWARMKHAZAD_TASK_ID and
;; SWARMFORGE_ROLE are exported; the role can also be inferred from the cwd.

(ns handoff-lib
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "task_lib.bb")))

(defn ctx [] (task-lib/ctx-from-env))

(defn same-path? [a b]
  (try
    (= (str (fs/canonicalize a)) (str (fs/canonicalize b)))
    (catch Exception _
      (= (str a) (str b)))))

(defn infer-role-from-cwd [ctx]
  (let [here (str (fs/cwd))]
    (some (fn [row]
            (when (and (:repo row) (same-path? (:worktree-path row) here))
              (:role row)))
          (task-lib/read-roles-tsv ctx))))

(defn role
  "SWARMFORGE_ROLE, else the role whose worktree is the cwd."
  ([] (role (ctx)))
  ([ctx]
   (or (not-empty (System/getenv "SWARMFORGE_ROLE"))
       (infer-role-from-cwd ctx)
       (throw (ex-info "Set SWARMFORGE_ROLE." {:exit 1})))))

(defn role-row [ctx role-name]
  (or (task-lib/role-row ctx role-name)
      (throw (ex-info (str "Unknown role: " role-name) {:exit 1}))))

(defn role-known? [ctx role-name]
  (boolean (task-lib/role-row ctx role-name)))

(defn role-receive-mode [ctx role-name]
  (let [mode (:receive-mode (role-row ctx role-name))]
    (if (str/blank? mode) "task" mode)))

(defn role-names [ctx] (task-lib/role-names ctx))

(defn last-role? [ctx role-name]
  (= role-name (last (role-names ctx))))

;; ---------------------------------------------------------------- dirs

(defn mail-dir [ctx role-name] (task-lib/role-mail-dir ctx role-name))
(defn outbox-dir [ctx role-name] (fs/path (mail-dir ctx role-name) "outbox"))
(defn inbox-dir [ctx role-name] (fs/path (mail-dir ctx role-name) "inbox"))
(defn new-dir [ctx role-name] (fs/path (inbox-dir ctx role-name) "new"))
(defn in-process-dir [ctx role-name] (fs/path (inbox-dir ctx role-name) "in_process"))
(defn completed-dir [ctx role-name] (fs/path (inbox-dir ctx role-name) "completed"))

;; ---------------------------------------------------------------- time

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT (java.time.Instant/now)))

(defn id-timestamp []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'")
           (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)))

(defn valid-priority? [value]
  (boolean (and value (re-matches #"[0-9][0-9]" value))))

;; ---------------------------------------------------------------- files

(defn handoff-files [dir]
  (if (fs/exists? dir)
    (->> (fs/list-dir dir)
         (filter #(and (fs/regular-file? %) (str/ends-with? (fs/file-name %) ".handoff")))
         (sort-by #(fs/file-name %))
         vec)
    []))

(defn batch-dirs [dir]
  (if (fs/exists? dir)
    (->> (fs/list-dir dir)
         (filter #(and (fs/directory? %) (str/starts-with? (fs/file-name %) "batch_")))
         (sort-by #(fs/file-name %))
         vec)
    []))

(defn in-process-files
  "Single in-process handoffs plus every file inside an in-process batch."
  [dir]
  (into (handoff-files dir) (mapcat handoff-files (batch-dirs dir))))

(defn glob-handoffs [dir]
  (if (fs/directory? dir)
    (->> (concat (fs/glob dir "*.handoff") (fs/glob dir "**/*.handoff"))
         (filter fs/regular-file?)
         distinct
         vec)
    []))

;; ---------------------------------------------------------------- headers

(defn parse-message [path]
  (let [content (slurp (str path))
        [header body] (str/split content #"\n\n" 2)
        headers (into {}
                      (for [line (str/split-lines (or header ""))
                            :let [[k v] (str/split line #": " 2)]
                            :when (and k v)]
                        [k v]))]
    {:headers headers :body (or body "") :content content}))

(def header-order
  ["id" "from" "to" "recipient" "priority" "type" "role" "task_id" "task" "commit"
   "artifacts" "task_base_commit" "non-forwarding" "message" "created_at" "enqueued_at"
   "dequeued_at" "completed_at"])

(defn render-message [headers body]
  (let [ordered (concat header-order (sort (remove (set header-order) (keys headers))))]
    (str (str/join "\n" (for [k ordered :let [v (get headers k)] :when v] (str k ": " v)))
         "\n\n"
         body)))

(defn header-field [file field]
  (get (:headers (parse-message file)) field))

(defn body [file]
  (:body (parse-message file)))

(defn set-header!
  "Rewrite one header atomically (temp file + move)."
  [file field value]
  (let [{:keys [headers body]} (parse-message file)
        tmp (fs/create-temp-file {:dir (fs/parent file) :prefix ".headers."})]
    (spit (str tmp) (render-message (assoc headers field value) body))
    (fs/move tmp file {:replace-existing true})))

(defn recipient-list [headers]
  (some->> (get headers "to")
           (#(str/split % #","))
           (map str/trim)
           (remove str/blank?)
           seq))

(defn task-key [headers]
  (or (not-empty (get headers "task_id")) (get headers "task")))

;; ---------------------------------------------------------------- printing

(defn print-task [file]
  (let [h (:headers (parse-message file))]
    (println "TASK:" (str file))
    (println "FROM:" (or (get h "from") "unknown"))
    (println "TYPE:" (or (get h "type") "unknown"))
    (println "PRIORITY:" (or (get h "priority") "50"))
    (when-let [t (get h "task")] (println "TASK_NAME:" t))
    (when-let [t (get h "task_id")] (println "TASK_ID:" t))
    (when-let [a (get h "artifacts")] (println "ARTIFACTS:" a))
    (println "PAYLOAD:")
    (print (body file))
    (flush)))

(defn print-batch [batch-dir]
  (let [files (handoff-files batch-dir)]
    (when (empty? files)
      (throw (ex-info (str "AMBIGUOUS_TASK_STATE: batch contains no tasks: " batch-dir) {:exit 2})))
    (println "BATCH:" (str batch-dir))
    (println "COUNT:" (count files))
    (when-let [name (header-field (first files) "task")] (println "TASK_NAME:" name))
    (println "PRIORITY:" (or (header-field (first files) "priority") "50"))
    (doseq [[index file] (map-indexed vector files)]
      (println)
      (println "BATCH_ITEM:" (inc index))
      (print-task file))))

;; ---------------------------------------------------------------- sequence

(defn next-sequence
  "A task-wide six-digit counter, serialised with a lock directory so two roles
   sending in the same second never collide."
  [ctx]
  (let [dir (:mail-dir ctx)
        seq-file (fs/path dir "sequence")
        lock-dir (fs/path dir "sequence.lock")]
    (fs/create-dirs dir)
    (loop [tries 0]
      (when-not (try (fs/create-dir lock-dir) true (catch Exception _ false))
        (when (> tries 200) (throw (ex-info (str "sequence lock is stuck: " lock-dir) {:exit 1})))
        (Thread/sleep 50)
        (recur (inc tries))))
    (try
      (let [last-value (if (fs/exists? seq-file) (str/trim (slurp (str seq-file))) "0")
            n (inc (if (re-matches #"[0-9]+" last-value) (Long/parseLong last-value) 0))]
        (spit (str seq-file) (format "%06d\n" n))
        (format "%06d" n))
      (finally
        (fs/delete-tree lock-dir)))))

;; ---------------------------------------------------------------- done

(defn archive-current-role! [ctx role-name]
  (let [result (process/sh {:continue true}
                           "bb" (str (fs/path script-dir "pack_board.bb")) "archive" "--role" role-name)]
    (when-not (zero? (:exit result))
      (binding [*out* *err*] (print (str (:err result) (:out result)))))))

(defn announce-follow-up! [ctx role-name]
  (if (seq (handoff-files (new-dir ctx role-name)))
    (println "MAIL_WAITING")
    (println "NO_TASK")))

(defn finish-done!
  "Archive the role's pane and say whether more mail waits."
  [ctx role-name]
  (try
    (archive-current-role! ctx role-name)
    (catch Exception e
      (binding [*out* *err*]
        (println (str "archive failed role=" role-name " error=" (.getMessage e))))))
  (announce-follow-up! ctx role-name))

(defn -main [& args]
  (try
    (let [c (ctx)]
      (case (first args)
        "role" (println (role c))
        "header-field" (if-let [v (header-field (second args) (nth args 2))] (println v) (System/exit 1))
        "body" (print (body (second args)))
        "set-header" (set-header! (second args) (nth args 2) (nth args 3))
        "print-task" (print-task (second args))
        "next-sequence" (println (next-sequence c))
        (do (binding [*out* *err*] (println "Usage: handoff_lib.bb role|header-field|body|set-header|print-task|next-sequence"))
            (System/exit 2))))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*] (println (ex-message e)))
      (System/exit (or (:exit (ex-data e)) 1)))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
