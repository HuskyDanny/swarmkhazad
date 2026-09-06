#!/usr/bin/env bb

;; swarm_handoff.bb <draft-file> — the strict outbound gate.
;;
;; A role writes a four-line draft under <task>/tmp/ and runs this. The helper
;; validates it, fills what the agent must never type (commit, artifacts, task),
;; and installs the finished handoff atomically into the role's outbox, where
;; handoffd picks it up. Drafts:
;;
;;   type: git_handoff          type: note
;;   to: <role>[,<role>...]     to: <role>[,<role>...]
;;   priority: NN               priority: NN
;;                              message: <one line>
;;
;; For git_handoff the commit is the sender worktree's HEAD; artifacts are the
;; files that commit changed plus the sender's draft-<role>.md when it exists.
;; The last role's git_handoff is the terminal broadcast: it is marked
;; non-forwarding, recipients merge and stop, and the board card goes to done.

(ns swarm-handoff
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "handoff_lib.bb")))

(def usage-text
  (str "Usage: swarm_handoff.bb <draft-file>\n\n"
       "Write the draft under the task folder's tmp/ directory.\n\n"
       "type: git_handoff\nto: <role>[,<role>...]\npriority: NN\n\n"
       "type: note\nto: <role>[,<role>...]\npriority: NN\nmessage: <one line, max 200 chars>\n"))

(def allowed-fields #{"type" "to" "priority" "message"})
(def allowed-types #{"git_handoff" "note"})
(def max-message 200)

(defn exit! [status message]
  (binding [*out* *err*] (when message (println message)))
  (System/exit status))

(defn git [dir & args]
  (let [result (apply process/sh {:continue true :dir (str dir)} "git" args)]
    (when-not (zero? (:exit result))
      (exit! 1 (str/trim (str "git " (str/join " " args) " failed in " dir "\n" (:err result)))))
    (str/trim (:out result))))

;; ---------------------------------------------------------------- draft

(defn parse-draft [draft]
  (loop [lines (str/split-lines (slurp (str draft))) line-no 0 headers {} errors []]
    (if-let [line (first lines)]
      (let [line-no (inc line-no)]
        (cond
          (str/blank? line) {:headers headers :errors errors}
          (not (str/includes? line ": "))
          (recur (next lines) line-no headers (conj errors (format "Line %d: expected `field: value`." line-no)))
          :else
          (let [[field value] (str/split line #": " 2)]
            (cond
              (not (allowed-fields field))
              (recur (next lines) line-no headers
                     (conj errors (format "Line %d: header '%s' is not allowed (want %s)." line-no field (str/join ", " (sort allowed-fields)))))
              (contains? headers field)
              (recur (next lines) line-no headers (conj errors (format "Line %d: duplicate header '%s'." line-no field)))
              :else (recur (next lines) line-no (assoc headers field (str/trim value)) errors)))))
      {:headers headers :errors errors})))

(defn validate-recipients [ctx sender to]
  ;; -1 keeps trailing empties, so `to: b,` is an empty recipient, not a quiet `b`.
  (let [recipients (if (str/blank? to) [] (mapv str/trim (str/split to #"," -1)))]
    [recipients
     (cond-> []
       (str/blank? to) (conj "Missing required header 'to'.")
       (some str/blank? recipients) (conj "Header 'to' contains an empty recipient.")
       (not= (count recipients) (count (distinct recipients))) (conj "Duplicate recipient in 'to'.")
       :always (into (for [r recipients :when (and (not (str/blank? r)) (not (handoff-lib/role-known? ctx r)))]
                       (format "Unknown recipient role '%s'." r))))]))

(defn base-errors [{:strs [type priority message]}]
  (cond-> []
    (str/blank? type) (conj "Missing required header 'type'.")
    (and type (not (allowed-types type))) (conj (format "Header 'type' must be git_handoff or note; got '%s'." type))
    (and priority (not (handoff-lib/valid-priority? priority))) (conj (format "Header 'priority' must be two digits 00-99; got '%s'." priority))
    (and (= type "note") (str/blank? message)) (conj "Missing required header 'message' for note.")
    (and (= type "note") (> (count (or message "")) max-message)) (conj (format "Header 'message' must be at most %d characters; got %d." max-message (count message)))
    (and (= type "git_handoff") message) (conj "Header 'message' is only allowed for note.")))

;; ---------------------------------------------------------------- state

(defn in-process-files [ctx sender]
  (handoff-lib/in-process-files ctx sender))

(defn inbound-non-forwarding? [ctx sender]
  (boolean (some #(= "true" (handoff-lib/header-field % "non-forwarding")) (in-process-files ctx sender))))

(defn task-base [ctx sender]
  (some #(handoff-lib/header-field % "task_base_commit") (in-process-files ctx sender)))

(defn changed-files [worktree base sha]
  (let [out (if base
              (git worktree "diff" "--name-only" "--diff-filter=ACMRT" base sha)
              (let [r (process/sh {:continue true :dir (str worktree)} "git" "diff" "--name-only" "--diff-filter=ACMRT" (str sha "^") sha)]
                (if (zero? (:exit r))
                  (:out r)
                  (git worktree "diff-tree" "--root" "--no-commit-id" "--name-only" "--diff-filter=ACMRT" "-r" sha))))]
    (->> (str/split-lines out) (remove str/blank?) distinct vec)))

(defn duplicate-active
  "Another live handoff with the same from/to/commit: in any outbox, or in a
   recipient's inbox new/in_process."
  [ctx sender recipients commit]
  (let [dirs (concat (for [r (handoff-lib/role-names ctx)] (handoff-lib/outbox-dir ctx r))
                     (for [r recipients state ["new" "in_process"]] (fs/path (handoff-lib/inbox-dir ctx r) state)))]
    (->> dirs
         (mapcat handoff-lib/glob-handoffs)
         (filter (fn [f]
                   (let [h (:headers (handoff-lib/parse-message f))]
                     (and (= "git_handoff" (get h "type"))
                          (= sender (get h "from"))
                          (= commit (get h "commit"))
                          (= (set recipients) (set (handoff-lib/recipient-list h)))))))
         first)))

;; ---------------------------------------------------------------- write

(defn body-text [type sender commit message]
  (case type
    "git_handoff" (str "Re-read your instructions.\n\nmerge_and_process.bb " sender " " commit "\n")
    "note" (str "Re-read your instructions.\n\n" message "\n")))

(defn fresh-stamp
  "A millisecond stamp no other file in this outbox carries."
  [out sender]
  (loop []
    (let [s (handoff-lib/stamp)]
      (if (seq (fs/glob out (str "*_" s "_from_" sender "_to_*.handoff")))
        (do (Thread/sleep 1) (recur))
        s))))

(defn write-handoff! [ctx {:keys [sender recipients headers commit artifacts non-forwarding? base unmet]}]
  (let [out (handoff-lib/outbox-dir ctx sender)
        stamp (fresh-stamp out sender)
        type (get headers "type")
        priority (or (get headers "priority") "50")
        filename (str priority "_" stamp "_from_" sender "_to_" (str/join "_" recipients) ".handoff")
        tmp (fs/path out "tmp" (str filename ".tmp"))
        final (fs/path out filename)
        h (cond-> {"id" (str stamp "_from_" sender)
                   "from" sender
                   "to" (str/join "," recipients)
                   "priority" priority
                   "type" type
                   "task_id" (:task-id ctx)
                   "task" (:task-id ctx)
                   "created_at" (handoff-lib/timestamp)}
            (= type "git_handoff") (assoc "role" sender "commit" commit "artifacts" (str/join "," artifacts))
            (and (= type "git_handoff") base) (assoc "task_base_commit" base)
            non-forwarding? (assoc "non-forwarding" "true")
            (seq unmet) (assoc "unmet" (str/join "; " unmet))
            (= type "note") (assoc "message" (get headers "message")))]
    (fs/create-dirs (fs/path out "tmp"))
    (spit (str tmp) (handoff-lib/render-message h (body-text type sender commit (get headers "message"))))
    (fs/move tmp final)
    final))

;; ---------------------------------------------------------------- judge gate

(defn judge-verdict [ctx sender]
  (let [f (fs/path (:state-dir ctx) "judge" (str sender ".json"))]
    (when (fs/regular-file? f)
      (try (cheshire.core/parse-string (slurp (str f)) true) (catch Exception _ nil)))))

(defn require-met-verdict!
  "A git_handoff needs the goal judge's latest verdict for this role to say met.
   Only claude roles have the Stop hook that produces one; other harnesses pass."
  [ctx row sender]
  (when (= "claude" (:harness row))
    (let [v (judge-verdict ctx sender)]
      (cond
        (nil? v)
        (exit! 1 (str "No goal-judge verdict yet for role " sender ". End your turn so the Stop hook grades your work; hand off after it says met."))
        ;; The budget is the escape hatch. The judge has told this role the same
        ;; thing max-blocks times and it still cannot meet the bar; refusing
        ;; forever would wedge the whole task on one role, which is worse than
        ;; forwarding work that says plainly what is unfinished. Never silent:
        ;; the unmet items ride on the handoff and are already in escalation.md.
        (and (not (:met v)) (:exhausted v))
        (binding [*out* *err*]
          (println (str "Goal judge still says unmet for role " sender " after " (count (:unmet v))
                        " item(s), but its block budget is spent — forwarding, with the gap named on the handoff.")))
        (not (:met v))
        (exit! 1 (str "Goal judge says unmet for role " sender ": " (str/join "; " (:unmet v))
                      ". A git_handoff is refused until the verdict is met. Address the items, end your turn to be re-graded, or write the block to escalation.md."))))))

(defn complete-current! [ctx sender]
  (when (seq (in-process-files ctx sender))
    (let [result (process/sh {:continue true} "bb" (str (fs/path script-dir "done_with_current.bb")))]
      (print (:out result))
      (binding [*out* *err*] (print (:err result)))
      (flush)
      (when-not (zero? (:exit result))
        (exit! (:exit result) "CURRENT COMPLETION FAILED after handoff queued.")))))

(defn -main [& args]
  (when (some #{"--help" "-h"} args) (print usage-text) (System/exit 0))
  (when (not= 1 (count args)) (exit! 1 usage-text))
  (let [ctx (task-lib/ctx-from-env)
        draft (fs/absolutize (fs/path (first args)))
        sender (handoff-lib/role ctx)
        row (handoff-lib/role-row ctx sender)]
    (when-not (fs/regular-file? draft) (exit! 1 (str "Draft file not found: " draft)))
    (when-not (fs/starts-with? (fs/canonicalize draft) (fs/canonicalize (:tmp-dir ctx)))
      (exit! 1 (str "Draft must live under " (:tmp-dir ctx) "; got " draft)))
    (let [{:keys [headers errors]} (parse-draft draft)
          type (get headers "type")
          [recipients recipient-errors] (validate-recipients ctx sender (get headers "to"))
          errors (vec (concat errors (base-errors headers) recipient-errors))]
      (when (seq errors)
        (binding [*out* *err*]
          (println "HANDOFF INVALID:" (str draft))
          (println)
          (doseq [e errors] (println "-" e))
          (println)
          (print usage-text))
        (System/exit 2))
      (let [git? (= type "git_handoff")
            worktree (:worktree-path row)
            _ (when (and git? (nil? (:repo row)))
                (exit! 1 (str "Role " sender " has no repo; it can send notes, not git handoffs.")))
            _ (when (and git? (inbound-non-forwarding? ctx sender))
                (exit! 1 "Current inbound handoff is non-forwarding (terminal); merge it and run done_with_current.bb, do not send a git_handoff."))
            _ (when git? (require-met-verdict! ctx row sender))
            commit (when git? (git worktree "rev-parse" "--short=10" "HEAD"))
            base (when git? (task-base ctx sender))
            files (when git? (changed-files worktree base commit))
            draft-doc (str "draft-" sender ".md")
            artifacts (when git? (cond-> files (fs/regular-file? (fs/path (:task-dir ctx) draft-doc)) (conj draft-doc)))]
        (when (and git? (empty? files))
          (exit! 1 (str "Result commit " commit " changes no files" (when base (str " since task base " base)) "; commit your work first.")))
        (when-let [dup (and git? (duplicate-active ctx sender recipients commit))]
          (exit! 1 (str "Duplicate active handoff for the same from/to/commit: " dup)))
        (let [verdict (when git? (judge-verdict ctx sender))
              ;; Forwarded past a spent budget: the recipient reads what is
              ;; unfinished on the handoff itself, not only in a file.
              unmet (when (and verdict (not (:met verdict)) (:exhausted verdict)) (:unmet verdict))
              final (write-handoff! ctx {:sender sender :recipients recipients :headers headers
                                         :commit commit :artifacts artifacts :base base :unmet unmet
                                         :non-forwarding? (and git? (handoff-lib/last-role? ctx sender))})]
          (fs/delete draft)
          (println "HANDOFF QUEUED:" (str final))
          (when git? (complete-current! ctx sender)))))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (try
    (apply -main *command-line-args*)
    (catch clojure.lang.ExceptionInfo e
      (exit! (or (:exit (ex-data e)) 1) (ex-message e)))))
