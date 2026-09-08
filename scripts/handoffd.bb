#!/usr/bin/env bb

;; handoffd.bb [--once] <task-id> — the delivery daemon, one per open task.
;;
;; Files are the transport; tmux carries only the wake-up. Every second it looks
;; at each role's outbox (and the _system outbox `open` writes the New Task note
;; to), copies each finished .handoff into every recipient's inbox/new with
;; `recipient` and `enqueued_at` headers, moves the card on the board, sends a
;; generic wake-up to each recipient's tmux session, and moves the original to
;; the sender's sent/ — or to failed/ with a .error file beside it.
;;
;; Runtime files: <task>/state/daemon/{handoffd.pid,handoffd.log,stop}.

(ns handoffd
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "handoff_lib.bb")))
(load-file (str (fs/path script-dir "board_lib.bb")))
(load-file (str (fs/path script-dir "pr_watch.bb")))

(def poll-ms 1000)
(def pr-poll-ms
  "How often a shipped PR is asked what has happened on it. A minute is slow
   enough that a task waiting days costs nothing and fast enough that a review
   comment does not sit unread over lunch. The portal's button skips the wait."
  60000)
(def wake-message "You have new handoff mail. If idle, run ready_for_next.bb.")
(def stopping (atom false))

(defn now [] (handoff-lib/timestamp))

(defn log! [ctx & parts]
  (fs/create-dirs (:daemon-dir ctx))
  (spit (str (fs/path (:daemon-dir ctx) "handoffd.log")) (str (now) " " (str/join " " parts) "\n") :append true))

(defn stop-file [ctx] (fs/path (:daemon-dir ctx) "stop"))
(defn pid-file [ctx] (fs/path (:daemon-dir ctx) "handoffd.pid"))

(defn should-stop? [ctx] (or @stopping (fs/exists? (stop-file ctx))))

(defn notify!
  "Type the wake-up into the recipient's pane. Best-effort: a role whose session
   is gone still gets its inbox file; only the nudge is lost."
  [ctx role]
  (when-not (handoff-lib/type-into-pane! ctx role wake-message)
    (log! ctx "wake-failed" role)))

(defn fail! [ctx path reason]
  (let [failed-dir (fs/path (fs/parent (fs/parent path)) "failed")
        target (fs/path failed-dir (fs/file-name path))]
    (log! ctx "failed" (str path) reason)
    (fs/create-dirs failed-dir)
    (fs/move path target)
    (spit (str target ".error") (str reason "\n"))))

(def held-logged
  "Paths whose hold has already been logged. Emptied per path when it delivers."
  (atom #{}))

(defn update-board! [ctx headers recipients]
  ;; A turn changes when a message crosses a ROLE boundary — not when it carries
  ;; `type: git_handoff`.
  ;;
  ;; Keying on the type looked equivalent and is not: a role that finds nothing
  ;; to commit still finishes, and says so in a `note`. The first live run did
  ;; exactly that — gobel established the hostname was already correct, so it
  ;; had a zero diff and sent `type: note` to both reviewers. The gate never
  ;; inspects a note, so it went straight through and started review on trees
  ;; its sibling was still writing. Worse, the sibling's real `git_handoff` was
  ;; then held for a role completion that could never arrive: superset's handoff
  ;; sat in its outbox for seven minutes while the daemon re-held it every tick.
  ;; Early start on one side and a deadlock on the other, from one predicate.
  ;;
  ;; A message that stays inside a role is sibling chatter and goes straight
  ;; out. A message from a role that is not the one holding the lane also goes
  ;; straight out — `hand-off!` recognises that sender as one whose turn is
  ;; already over, so a reviewer asking the active implementer a question is not
  ;; held behind its own role's join.
  (let [sender-role (:role (task-lib/session-row ctx (get headers "from")))
        ;; The lane a card moves INTO is the recipient's role, not the session
        ;; that happens to be first in `to`. A role with three repos is one
        ;; column.
        next-lane (if (= "true" (get headers "non-forwarding"))
                    "done"
                    (or (:role (task-lib/session-row ctx (first recipients))) (first recipients)))]
    (when (and sender-role (not= sender-role next-lane))
      (board-lib/hand-off! ctx
                           (or (handoff-lib/task-key headers) (:task-id ctx))
                           (get headers "from")
                           next-lane))))

(defn phantom? [from] (boolean (re-matches #"\(.+\)" (or from ""))))

(defn sent-dir [ctx sender]
  (if (or (phantom? sender) (not (handoff-lib/session-known? ctx sender)))
    (fs/path (task-lib/system-mail-dir ctx) "sent")
    (fs/path (handoff-lib/mail-dir ctx sender) "sent")))

(defn deliver!
  "One outbox file to every recipient's inbox — unless it is a git_handoff and
   the sender's role has not finished.

   A role is one column of the pipeline, however many repos it holds. Delivering
   the first session's handoff the moment it lands started the next role on a
   set of trees still being written by that role's siblings — the hazard the
   board's own join exists to describe, which until now it only described.
   `update-board!` already computes the answer and threw it away.

   Holding means leaving the file where it is: the daemon sees it again next
   tick, and the same code delivers it once the last sibling has handed off."
  [ctx path]
  (let [{:keys [headers body]} (handoff-lib/parse-message path)
        sender (get headers "from")
        recipients (handoff-lib/recipient-list headers)]
    (when-not recipients (throw (ex-info "missing to header" {})))
    (doseq [r recipients :when (not (handoff-lib/session-known? ctx r))]
      (throw (ex-info (str "unknown recipient " r) {})))
    (if (false? (update-board! ctx headers recipients))
      ;; Logged once per file, not once per tick: a hold is re-evaluated every
      ;; second and lasts as long as the slowest sibling, so logging it on every
      ;; pass wrote two lines a second and buried everything else in the file.
      (when-not (contains? @held-logged (str path))
        (swap! held-logged conj (str path))
        (log! ctx "held" (str path) "until the rest of" (str (get headers "from")) "'s role hands off"))
      (do
        (doseq [r recipients]
          (let [target (fs/path (handoff-lib/new-dir ctx r) (fs/file-name path))]
            (fs/create-dirs (fs/parent target))
            (when-not (fs/exists? target)
              (spit (str target) (handoff-lib/render-message (assoc headers "recipient" r "enqueued_at" (now)) body)))
            (notify! ctx r)))
        (swap! held-logged disj (str path))
        (let [dir (sent-dir ctx sender)]
          (fs/create-dirs dir)
          (fs/move path (fs/path dir (fs/file-name path))))
        (log! ctx "delivered" (str path) "to" (str/join "," recipients))))))

(defn outbox-files [ctx]
  (->> (conj (mapv #(handoff-lib/outbox-dir ctx %) (handoff-lib/session-names ctx))
             (fs/path (task-lib/system-mail-dir ctx) "outbox"))
       (mapcat handoff-lib/handoff-files)
       (map str)
       distinct
       sort))

(defn poll-once!
  "Every outbox, until a pass moves nothing.

   A handoff held for its role's siblings becomes deliverable the moment the
   last of them lands, and that can happen later in this same pass — files go in
   stamp order, and the sibling that completes the turn is usually not the first
   one. A single pass would leave the earlier ones sitting for another tick; the
   loop lets a finished turn go out as one piece."
  [ctx]
  (loop [before nil]
    (doseq [path (outbox-files ctx)
            :while (not (should-stop? ctx))]
      (try
        (deliver! ctx (fs/path path))
        (catch Exception e
          (log! ctx "error" path (.getMessage e))
          (try (fail! ctx (fs/path path) (.getMessage e))
               (catch Exception nested (log! ctx "failed-to-archive" path (.getMessage nested)))))))
    (let [after (set (outbox-files ctx))]
      ;; Stop as soon as a pass leaves the same files behind: they are held on
      ;; something no further pass of this loop can change.
      (when (and (seq after) (not= after before) (not (should-stop? ctx)))
        (recur after)))))

(def last-pr-poll (atom 0))

(defn poll-prs!
  "Ask GitHub what has happened on the task's PRs, at most every pr-poll-ms.
   Only after ship has recorded one: a task that never shipped has nothing to
   ask about, and asking would be a network call per second for nothing."
  [ctx]
  (let [now (System/currentTimeMillis)]
    (when (and (fs/directory? (fs/path (:state-dir ctx) "pr"))
               (> (- now @last-pr-poll) pr-poll-ms))
      (reset! last-pr-poll now)
      (doseq [line (pr-watch/poll! ctx)] (log! ctx "pr" line)))))

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
      (try (poll-prs! ctx)
           ;; A GitHub outage must not take the delivery daemon down with it.
           (catch Exception e (log! ctx "pr-poll-failed" (.getMessage e))))
      (Thread/sleep poll-ms))
    (finally
      (shutdown! ctx))))

(defn -main [& args]
  (let [once? (boolean (some #{"--once"} args))
        id (first (remove #{"--once"} args))]
    (when (str/blank? id)
      (binding [*out* *err*] (println "Usage: handoffd.bb [--once] <task-id>"))
      (System/exit 1))
    (let [ctx (task-lib/task-ctx id)]
      (if once?
        ;; `--once` is the whole loop once, PRs included — a debugging run that
        ;; quietly skipped half of what the daemon does would be worse than no
        ;; debugging run at all.
        (do (poll-once! ctx)
            (try (poll-prs! ctx)
                 (catch Exception e (log! ctx "pr-poll-failed" (.getMessage e)))))
        (run-daemon! ctx)))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
