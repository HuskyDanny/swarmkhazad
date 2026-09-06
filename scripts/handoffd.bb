#!/usr/bin/env bb

;; handoffd.bb [--once] <task-id> — the delivery daemon, one per open task.
;;
;; Files are the transport; tmux carries only the wake-up. Every second it looks
;; at each role's outbox (and the _system outbox `open` writes the New Task note
;; to), copies each finished .handoff into every recipient's inbox/new with
;; `recipient` and `enqueued_at` headers, moves the card on the board, sends a
;; generic wake-up to each recipient's tmux session, and moves the original to
;; the sender's sent/ (or failed/ with a .error file next to it).
;;
;; Runtime files: <task>/state/daemon/{handoffd.pid,handoffd.log,stop}.

(ns handoffd
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "handoff_lib.bb")))

(def poll-ms 1000)
(def wake-message "You have new handoff mail. If idle, run ready_for_next.bb.")
(def stopping (atom false))

(defn now [] (handoff-lib/timestamp))

(defn log! [ctx & parts]
  (fs/create-dirs (:daemon-dir ctx))
  (spit (str (fs/path (:daemon-dir ctx) "handoffd.log")) (str (now) " " (str/join " " parts) "\n") :append true))

(defn stop-file [ctx] (fs/path (:daemon-dir ctx) "stop"))
(defn pid-file [ctx] (fs/path (:daemon-dir ctx) "handoffd.pid"))

(defn should-stop? [ctx] (or @stopping (fs/exists? (stop-file ctx))))

(defn tmux-socket [ctx]
  (when (fs/regular-file? (:tmux-socket-file ctx))
    (not-empty (str/trim (slurp (str (:tmux-socket-file ctx)))))))

(defn notify!
  "Type the wake-up into the recipient's pane. Best-effort: a role whose session
   is gone still gets its inbox file; only the nudge is lost."
  [ctx role]
  (when-let [socket (tmux-socket ctx)]
    (let [target (task-lib/session-name role)
          ok? (zero? (:exit (process/sh {:continue true} "tmux" "-S" socket "send-keys" "-t" target "-l" wake-message)))]
      (when ok?
        (Thread/sleep 150)
        (process/sh {:continue true} "tmux" "-S" socket "send-keys" "-t" target "C-m")
        (Thread/sleep 50)
        (process/sh {:continue true} "tmux" "-S" socket "send-keys" "-t" target "C-j"))
      (when-not ok? (log! ctx "wake-failed" role)))))

(defn move-with-collision [source target-dir]
  (fs/create-dirs target-dir)
  (let [base (fs/file-name source)
        target (fs/path target-dir base)]
    (fs/move source (if (fs/exists? target) (fs/path target-dir (str (now) "_" base)) target))))

(defn fail! [ctx path reason]
  (let [failed-dir (fs/path (fs/parent (fs/parent path)) "failed")]
    (log! ctx "failed" (str path) reason)
    (spit (str path ".error") (str reason "\n"))
    (move-with-collision path failed-dir)))

(defn pack-board! [ctx & args]
  (let [result (apply process/sh {:continue true} "bb" (str (fs/path script-dir "pack_board.bb")) (concat args ["--task" (:task-id ctx)]))]
    (when-not (zero? (:exit result))
      (throw (ex-info (str/trim (str "pack_board " (str/join " " args) ": " (:err result) (:out result))) {})))))

(defn update-board! [ctx headers recipients]
  (when (= "git_handoff" (get headers "type"))
    (let [name (or (handoff-lib/task-key headers) (:task-id ctx))]
      (if (= "true" (get headers "non-forwarding"))
        (pack-board! ctx "done" "--name" name)
        (pack-board! ctx "move" "--name" name "--lane" (first recipients))))))

(defn phantom? [from] (boolean (re-matches #"\(.+\)" (or from ""))))

(defn sent-dir [ctx sender]
  (if (or (phantom? sender) (not (handoff-lib/role-known? ctx sender)))
    (fs/path (task-lib/system-mail-dir ctx) "sent")
    (fs/path (handoff-lib/mail-dir ctx sender) "sent")))

(defn deliver! [ctx path]
  (let [{:keys [headers body]} (handoff-lib/parse-message path)
        sender (get headers "from")
        recipients (handoff-lib/recipient-list headers)]
    (when-not recipients (throw (ex-info "missing to header" {})))
    (doseq [r recipients :when (not (handoff-lib/role-known? ctx r))]
      (throw (ex-info (str "unknown recipient " r) {})))
    (update-board! ctx headers recipients)
    (doseq [r recipients]
      (let [target (fs/path (handoff-lib/new-dir ctx r) (fs/file-name path))]
        (fs/create-dirs (fs/parent target))
        (when-not (fs/exists? target)
          (spit (str target) (handoff-lib/render-message (assoc headers "recipient" r "enqueued_at" (now)) body)))
        (notify! ctx r)))
    (move-with-collision path (sent-dir ctx sender))
    (when (and (not (phantom? sender)) (handoff-lib/role-known? ctx sender))
      (process/sh {:continue true} "bb" (str (fs/path script-dir "pack_board.bb")) "archive" "--role" sender "--task" (:task-id ctx)))
    (log! ctx "delivered" (str path) "to" (str/join "," recipients))))

(defn outbox-files [ctx]
  (->> (conj (mapv #(handoff-lib/outbox-dir ctx %) (handoff-lib/role-names ctx))
             (fs/path (task-lib/system-mail-dir ctx) "outbox"))
       (mapcat handoff-lib/handoff-files)
       (map str)
       distinct
       sort))

(defn poll-once! [ctx]
  (doseq [path (outbox-files ctx)
          :while (not (should-stop? ctx))]
    (try
      (deliver! ctx (fs/path path))
      (catch Exception e
        (log! ctx "error" path (.getMessage e))
        (try (fail! ctx (fs/path path) (.getMessage e))
             (catch Exception nested (log! ctx "failed-to-archive" path (.getMessage nested))))))))

(defn sleep-poll! [ctx ms]
  (loop [remaining ms]
    (when (and (pos? remaining) (not (should-stop? ctx)))
      (Thread/sleep (min remaining 100))
      (recur (- remaining 100)))))

(defn shutdown! [ctx]
  ;; Runs from the TERM shutdown hook and from the stop-file path; log once.
  (when (compare-and-set! stopping false true)
    (fs/delete-if-exists (pid-file ctx))
    (log! ctx "stopped")))

(defn run-daemon! [ctx]
  (fs/create-dirs (:daemon-dir ctx))
  (fs/delete-if-exists (stop-file ctx))
  (spit (str (pid-file ctx)) (str (.pid (java.lang.ProcessHandle/current)) "\n"))
  (.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (shutdown! ctx))))
  (log! ctx "started")
  (try
    (while (not (should-stop? ctx))
      (poll-once! ctx)
      (sleep-poll! ctx poll-ms))
    (finally
      (shutdown! ctx))))

(defn -main [& args]
  (let [once? (boolean (some #{"--once"} args))
        id (first (remove #{"--once"} args))]
    (when (str/blank? id)
      (binding [*out* *err*] (println "Usage: handoffd.bb [--once] <task-id>"))
      (System/exit 1))
    (let [ctx (task-lib/task-ctx id)]
      (if once? (poll-once! ctx) (run-daemon! ctx)))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
