#!/usr/bin/env bb

;; handoff-lib — the mail transport's shared vocabulary: where a role's mail
;; lives, how a handoff file is parsed and rewritten, timestamps, the
;; in-process inspection every receive helper does, pane archiving, and the
;; TASK/BATCH printouts.
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

(defn infer-session-from-cwd
  "The session whose worktree is the cwd — but only when exactly one is.

   Roles sharing a repo share its worktree, so a directory names a repo and not
   a session. Guessing between implement_gobel and review_gobel would put mail
   in the wrong inbox, which is worse than refusing."
  [ctx]
  (let [here (str (fs/cwd))
        hits (filter #(and (:worktree-path %) (same-path? (:worktree-path %) here))
                     (task-lib/read-sessions-tsv ctx))]
    (when (= 1 (count hits)) (:session (first hits)))))

(defn session
  "SWARMKHAZAD_SESSION, else a role that runs in exactly one repo, else the cwd."
  ([] (session (ctx)))
  ([ctx]
   (or (not-empty (System/getenv "SWARMKHAZAD_SESSION"))
       (when-let [r (not-empty (System/getenv "SWARMFORGE_ROLE"))]
         (let [rows (task-lib/role-sessions ctx r)]
           (when (= 1 (count rows)) (:session (first rows)))))
       (infer-session-from-cwd ctx)
       (throw (ex-info "Set SWARMKHAZAD_SESSION." {:exit 1})))))

(defn session-row [ctx name]
  (or (task-lib/session-row ctx name)
      (throw (ex-info (str "Unknown session: " name) {:exit 1}))))

(defn session-known? [ctx name]
  (boolean (task-lib/session-row ctx name)))

(defn session-receive-mode [ctx name]
  (let [mode (:receive-mode (session-row ctx name))]
    (if (str/blank? mode) "task" mode)))

(defn session-names [ctx] (task-lib/session-names ctx))

(defn last-session? [ctx name]
  (= name (last (session-names ctx))))

;; ---------------------------------------------------------------- dirs

(defn mail-dir [ctx name] (task-lib/session-mail-dir ctx name))
(defn outbox-dir [ctx name] (fs/path (mail-dir ctx name) "outbox"))
(defn inbox-dir [ctx name] (fs/path (mail-dir ctx name) "inbox"))
(defn new-dir [ctx name] (fs/path (inbox-dir ctx name) "new"))
(defn in-process-dir [ctx name] (fs/path (inbox-dir ctx name) "in_process"))
(defn completed-dir [ctx name] (fs/path (inbox-dir ctx name) "completed"))

;; ---------------------------------------------------------------- time

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT (java.time.Instant/now)))

(defn stamp
  "Millisecond UTC stamp for ids and filenames: sorts by time, needs no counter
   and no lock. A writer that finds its filename taken waits a millisecond."
  []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmssSSS'Z'")
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

(defn in-process-state
  "What a role has accepted and not finished: single files and batch dirs.
   Both receive helpers validate against this one view."
  [ctx session]
  (let [dir (in-process-dir ctx session)]
    {:dir dir :files (handoff-files dir) :batches (batch-dirs dir)}))

(defn in-process-files
  "Single in-process handoffs plus every file inside an in-process batch."
  [ctx session]
  (let [{:keys [files batches]} (in-process-state ctx session)]
    (into files (mapcat handoff-files batches))))

(defn glob-handoffs
  "Every .handoff at any depth under dir. `**` alone does not match depth 0
   in babashka.fs (RAN), hence both patterns."
  [dir]
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
    ;; Only set past one repo, and the recipient may have no session there —
    ;; a review with no superset goal line still needs to know superset moved.
    (when-let [r (get h "origin_repo")] (println "ORIGIN_REPO:" r))
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

;; ---------------------------------------------------------------- panes

(defn tmux-socket [ctx]
  (when (fs/regular-file? (:tmux-socket-file ctx))
    (not-empty (str/trim (slurp (str (:tmux-socket-file ctx)))))))

(defn capture-pane
  "The pane's scrollback. `-e` keeps the SGR escapes, without which an agent TUI
   arrives as flat grey text and every colour it used to mean something with is
   gone. Callers that want plain text strip them; the portal renders them."
  [ctx session & {:keys [ansi] :or {ansi false}}]
  (when-let [socket (tmux-socket ctx)]
    (let [args (concat ["tmux" "-S" socket "capture-pane" "-p"]
                       (when ansi ["-e"])
                       ["-t" (task-lib/session-name session) "-S" "-"])
          r (apply process/sh {:continue true} args)]
      (when (zero? (:exit r)) (:out r)))))

(defn- tmux-send!
  [ctx session args]
  (when-let [socket (tmux-socket ctx)]
    (zero? (:exit (apply process/sh {:continue true}
                        (concat ["tmux" "-S" socket "send-keys"
                                 "-t" (task-lib/session-name session)]
                                args))))))

(defn type-into-pane!
  "Type text into a role's pane and submit it — the only way to reach an agent
   that is already running, since it owns the terminal.

   The text goes with `-l` so nothing inside it is read as a tmux key name, then
   Enter, then C-j: the harness input boxes differ on which one submits, and a
   spare newline in a box that took the first is harmless. The pauses are what
   makes it land in a TUI that redraws between keystrokes.

   Best-effort. A role whose session is gone returns false, and the caller
   decides whether that matters — handoffd logs it and moves on, because the
   inbox file is delivered either way."
  [ctx session text]
  (boolean
   (when (seq (or text ""))
     (when (tmux-send! ctx session ["-l" text])
       (Thread/sleep 150)
       (tmux-send! ctx session ["C-m"])
       (Thread/sleep 50)
       (tmux-send! ctx session ["C-j"])
       true))))

(defn press-key!
  "Send one tmux key name — Escape to interrupt the turn a role is in the middle
   of, C-c to signal it. A key name cannot go through `type-into-pane!`, which
   sends literally by design."
  [ctx session key]
  (boolean (tmux-send! ctx session [key])))

(defn archive-session!
  "Snapshot the pane to state/sessions/<session>/pane.txt — what the portal
   shows once the session is gone."
  [ctx session]
  (when-let [text (or (System/getenv "SWARMKHAZAD_PANE_STUB") (capture-pane ctx session))]
    (let [file (fs/path (:sessions-dir ctx) session "pane.txt")]
      (fs/create-dirs (fs/parent file))
      (spit (str file) text))))

(defn archive-all! [ctx]
  (doseq [r (session-names ctx)] (archive-session! ctx r)))

;; ---------------------------------------------------------------- done

(defn finish-done!
  "Archive the pane and say whether more mail waits."
  [ctx session]
  (try
    (archive-session! ctx session)
    (catch Exception e
      (binding [*out* *err*]
        (println (str "archive failed session=" session " error=" (.getMessage e))))))
  (if (seq (handoff-files (new-dir ctx session)))
    (println "MAIL_WAITING")
    (println "NO_TASK")))
