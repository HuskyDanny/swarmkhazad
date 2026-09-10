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
;;
;; A git_handoff also PUBLISHES: the branch goes to origin and a draft PR is
;; opened on it if there is not one already. That is a precondition rather than
;; a courtesy — the cloud runner clones from origin and comments its findings
;; on the PR, so a task whose branch is local has nowhere for its answers to
;; come back to, and a branch that lives only in a worktree is one
;; `close --reclaim` from gone.

(ns swarm-handoff
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "handoff_lib.bb")))

(def usage-text
  (str "Usage: swarm_handoff.bb <draft-file>\n"
       "       swarm_handoff.bb <draft-file> --no-change '<why nothing needed changing>'\n\n"
       "Write the draft under the task folder's tmp/ directory.\n\n"
       "type: git_handoff\nto: <role>[,<role>...]\npriority: NN\n\n"
       "type: note\nto: <role>[,<role>...]\npriority: NN\nmessage: <one line, max 200 chars>\n\n"
       "--no-change is a git_handoff with an empty diff: you finished, and the right\n"
       "answer was to change nothing. It is graded, it moves the board and it wakes the\n"
       "next role exactly like any other handoff — a note does none of those things.\n"))

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

(defn expand-recipient
  "A name in `to` is a session or a role. A role expands to every session it
   has, so `to: review` reaches the reviewer in all three repos without the
   sender knowing how many there are — and the sender's own sessions drop out,
   the same rule `to: all` already follows. In a one-repo task the session and
   the role are the same string and this is a no-op."
  [ctx sender name]
  (if (handoff-lib/session-known? ctx name)
    [name]
    (let [sender-role (:role (task-lib/session-row ctx sender))]
      (->> (task-lib/role-sessions ctx name)
           (remove #(= sender-role (:role %)))
           (mapv :session)))))

(defn validate-recipients [ctx sender to]
  ;; -1 keeps trailing empties, so `to: b,` is an empty recipient, not a quiet `b`.
  ;; `to: all` is every other session — the shape the last role's terminal
  ;; broadcast needs, and the one a role reaches for first (the live fixture's
  ;; run role wrote it and was refused, then had to list its siblings by hand).
  (let [named (cond
                (str/blank? to) []
                (= "all" (str/trim to)) (vec (remove #{sender} (handoff-lib/session-names ctx)))
                :else (mapv str/trim (str/split to #"," -1)))
        recipients (vec (distinct (mapcat #(expand-recipient ctx sender %) named)))]
    [recipients
     (cond-> []
       (str/blank? to) (conj "Missing required header 'to'.")
       (some str/blank? named) (conj "Header 'to' contains an empty recipient.")
       (not= (count named) (count (distinct named))) (conj "Duplicate recipient in 'to'.")
       :always (into (for [r named :when (and (not (str/blank? r)) (empty? (expand-recipient ctx sender r)))]
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
  (let [dirs (concat (for [r (handoff-lib/session-names ctx)] (handoff-lib/outbox-dir ctx r))
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

(defn body-text [type sender commit message no-change]
  (case type
    "git_handoff" (if no-change
                    (str "Re-read your instructions.\n\n" sender " changed nothing, on purpose: "
                         no-change "\n\nIts tree is unchanged at " commit
                         ". Read its draft for the evidence behind that call, and review the"
                         " reasoning rather than a diff.\n")
                    (str "Re-read your instructions.\n\n" sender " committed " commit
                         " in its repo. Nothing to merge: if that is your repo too, it is already"
                         " on your branch; if it is not, read the diff there rather than pulling it.\n"))
    "note" (str "Re-read your instructions.\n\n" message "\n")))

(defn origin-repo
  "The repo the sender worked in, but only when the task holds more than one.
   A recipient with no session in that repo still has to be told which tree
   moved; a one-repo task would be stamping the same answer on every handoff."
  [ctx row]
  (let [repos (distinct (keep :repo (task-lib/read-sessions-tsv ctx)))]
    (when (> (count repos) 1) (:repo row))))

(defn write-handoff! [ctx {:keys [sender recipients headers commit artifacts non-forwarding? base unmet repo no-change]}]
  (let [out (handoff-lib/outbox-dir ctx sender)
        stamp (handoff-lib/fresh-stamp out sender)
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
            ;; Not a flag the recipient has to infer from an empty artifact
            ;; list: the next role, the judge and the summarizer all need to
            ;; tell "changed nothing, on purpose" from "changed nothing, stalled".
            no-change (assoc "no_change" no-change)
            (and (= type "git_handoff") repo) (assoc "origin_repo" repo)
            (and (= type "git_handoff") base) (assoc "task_base_commit" base)
            non-forwarding? (assoc "non-forwarding" "true")
            (seq unmet) (assoc "unmet" (str/join "; " unmet))
            (= type "note") (assoc "message" (get headers "message")))]
    (fs/create-dirs (fs/path out "tmp"))
    (spit (str tmp) (handoff-lib/render-message h (body-text type sender commit (get headers "message") no-change)))
    (fs/move tmp final)
    final))

;; ---------------------------------------------------------------- judge gate

(defn judge-verdict [ctx sender]
  (let [f (fs/path (:state-dir ctx) "judge" (str sender ".json"))]
    (when (fs/regular-file? f)
      (try (cheshire.core/parse-string (slurp (str f)) true) (catch Exception _ nil)))))

(def max-refusals
  "How many times one session's git_handoff may be refused for unmet goals
   before the work goes forward carrying the gap. Refusing forever wedges every
   later role on one that cannot meet its bar. Counted per (role, repo) and NOT
   per turn: a role told its goals are unmet, which works on them and comes
   back, is on its second attempt at the same bar however many turns it took."
  3)

(defn judge-state [ctx sender file]
  (let [f (fs/path (:state-dir ctx) "judge" (str sender "." file))]
    (when (fs/regular-file? f)
      (try (cheshire.core/parse-string (slurp (str f)) true) (catch Exception _ nil)))))

(defn refusals [ctx sender] (or (:count (judge-state ctx sender "refusals")) 0))

(defn record-refusal! [ctx sender]
  (fs/create-dirs (fs/path (:state-dir ctx) "judge"))
  (spit (str (fs/path (:state-dir ctx) "judge" (str sender ".refusals")))
        (cheshire.core/generate-string {:count (inc (refusals ctx sender))})))

(defn clear-refusals! [ctx sender]
  (fs/delete-if-exists (fs/path (:state-dir ctx) "judge" (str sender ".refusals"))))

(defn grade-now!
  "Ask the judge to grade this session's committed state, right now.

   The judge runs here rather than on every Stop: it grades the tree being
   handed over, once per attempt. A judge that cannot run answers met=false
   with unmet=[judge_unavailable], which the refusal budget then handles like
   any other gap — an infra fault delays a handoff, it does not wedge the task."
  [ctx sender]
  (let [r (process/sh {:continue true
                       :extra-env {"SWARMKHAZAD_SESSION" sender}}
                      "bb" (str (fs/path script-dir "goal_judge.bb")) "--grade" sender)]
    ;; No fallback to the verdict already on disk. That file is the PREVIOUS
    ;; grading, and a met one would wave this handoff through on the strength
    ;; of work that was judged before the commit being sent now.
    (or (try (cheshire.core/parse-string (str/trim (:out r)) true) (catch Exception _ nil))
        {:met false :unmet ["judge_unavailable"] :down true})))

(defn require-met-verdict!
  "A git_handoff is graded when it is sent, and refused while the goals are
   unmet — up to a point. After max-refusals the work goes forward carrying the
   gap: refusing forever wedges every later role on one that cannot meet its
   bar, which is worse than forwarding work that says plainly what is
   unfinished. Never silent — the unmet items ride on the handoff and are
   already in escalation.md.

   Only claude sessions are graded; the other harnesses have no judge."
  [ctx row sender]
  (when (= "claude" (:harness row))
    (let [v (grade-now! ctx sender)
          spent (>= (refusals ctx sender) max-refusals)]
      (cond
        (:met v) nil
        spent (binding [*out* *err*]
                (println (str "Goal judge still says unmet for " sender " after " max-refusals
                              " refusals — forwarding, with the gap named on the handoff.")))
        :else (do (record-refusal! ctx sender)
                  (exit! 1 (str "Goal judge says unmet for role " sender ": " (str/join "; " (:unmet v))
                                ". A git_handoff is refused until the verdict is met (attempt "
                                (refusals ctx sender) " of " max-refusals
                                "). Address the items, then send it again, or write the block to escalation.md.")))))))

;; ---------------------------------------------------------------- publish

(defn publish-branch!
  "Put this commit on origin and make sure the branch has a draft PR, before
   the handoff is queued.

   Here rather than in a role's prompt, because a prompt is advice and this is
   a precondition for three things that come after it. The next role reviews a
   branch; the cloud runner clones from ORIGIN and comments its findings on the
   PR, so a task with no PR has nowhere for its answers to come back to; and a
   branch that exists only in a worktree is one `close --reclaim` from gone.
   Every one of those failures is silent — the work looks finished until
   somebody goes looking for it.

   The push is required and a failure stops the handoff: the role sees git's
   own error and can fix it or escalate. Publishing is skipped, with a line
   saying so, when there is nowhere to push — a checkout whose origin is not a
   GitHub repo, or a commit that adds nothing past the default branch."
  [ctx row]
  (load-file (str (fs/path script-dir "ship.bb")))
  (let [entry (first (filter #(= (:repo row) (:name %)) (task-lib/parse-repos ctx)))
        plan (when entry ((resolve 'ship/repo-plan) ctx entry))]
    (cond
      (nil? entry)
      (println (str "publish: no `repos` entry named " (pr-str (:repo row)) " — nothing pushed"))

      (nil? plan)
      (println (str "publish: " (:name entry) " has no commits past its default branch — nothing pushed"))

      (nil? (:slug plan))
      (println (str "publish: " (:name entry) " has no github.com origin — nothing pushed"))

      :else
      (do (println (str "publishing " (:repo plan) " " (:branch plan) " before the handoff…"))
          (let [{:keys [url opened?]} ((resolve 'ship/publish!)
                                       ctx plan
                                       ((resolve 'ship/title) ctx)
                                       ;; No summary at this point, and there
                                       ;; will not be one until someone asks for
                                       ;; a verdict. `ship` sets the body again
                                       ;; when there is.
                                       ((resolve 'ship/body-for) ctx plan nil))]
            (println (str (if opened? "DRAFT PR OPENED: " "PUSHED, PR ALREADY OPEN: ") url)))))))

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
  ;; `--no-change '<why>'` is the one outcome this helper had no channel for: the
  ;; role finished, and the right answer was to change nothing. Its reason is
  ;; required — an empty diff with no explanation is indistinguishable from a
  ;; role that gave up, and that is exactly the distinction the next role, the
  ;; judge and the board all need to make.
  (let [no-change? (boolean (some #{"--no-change"} args))
        why (when no-change? (second (drop-while #(not= "--no-change" %) args)))
        positional (remove #{"--no-change" why} args)
        _ (when (and no-change? (str/blank? why))
            (exit! 1 "--no-change needs a reason: swarm_handoff.bb <draft> --no-change '<why nothing needed changing>'"))
        _ (when (not= 1 (count positional)) (exit! 1 usage-text))
        ctx (task-lib/ctx-from-env)
        draft (fs/absolutize (fs/path (first positional)))
        sender (handoff-lib/session ctx)
        row (handoff-lib/session-row ctx sender)]
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
            _ (when (and git? (inbound-non-forwarding? ctx sender))
                (exit! 1 "Current inbound handoff is non-forwarding (terminal); merge it and run done_with_current.bb, do not send a git_handoff."))
            _ (when git? (require-met-verdict! ctx row sender))
            commit (when git? (git worktree "rev-parse" "--short=10" "HEAD"))
            base (when git? (task-base ctx sender))
            files (when git? (changed-files worktree base commit))
            draft-doc (str "draft-" sender ".md")
            artifacts (when git? (cond-> (vec files)
                                   (fs/regular-file? (fs/path (:task-dir ctx) draft-doc)) (conj draft-doc)))]
        (when (and git? (empty? files) (not no-change?))
          (exit! 1 (str "Result commit " commit " changes no files" (when base (str " since task base " base))
                        "; commit your work first, or pass --no-change '<why nothing needed changing>'"
                        " if the right answer was to change nothing.")))
        (when (and no-change? (seq files))
          (exit! 1 (str "--no-change, but commit " commit " changes " (count files)
                        " file(s); send it as an ordinary handoff.")))
        (when-let [dup (and git? (duplicate-active ctx sender recipients commit))]
          (exit! 1 (str "Duplicate active handoff for the same from/to/commit: " dup)))
        ;; Before the handoff is written, so a push that fails leaves no
        ;; handoff claiming work the next role cannot fetch.
        (when (and git? (not no-change?)) (publish-branch! ctx row))
        (let [verdict (when git? (judge-verdict ctx sender))
              ;; Forwarded past a spent budget: the recipient reads what is
              ;; unfinished on the handoff itself, not only in a file. Any
              ;; unmet verdict that reached this point is a spent one — the
              ;; gate above refused every other kind.
              unmet (when (and verdict (not (:met verdict))) (:unmet verdict))
              final (write-handoff! ctx {:sender sender :recipients recipients :headers headers
                                         :commit commit :artifacts artifacts :base base :unmet unmet
                                         :no-change (when no-change? why)
                                         :repo (when git? (origin-repo ctx row))
                                         :non-forwarding? (and git? (handoff-lib/last-role? ctx sender))})]
          (fs/delete draft)
          ;; The budget is about one piece of work. Once it is handed over the
          ;; next attempt starts at zero, or a session that struggled early
          ;; would find the gate already spent when it mattered.
          (when git? (clear-refusals! ctx sender))
          (println "HANDOFF QUEUED:" (str final))
          (when git? (complete-current! ctx sender)))))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (try
    (apply -main *command-line-args*)
    (catch clojure.lang.ExceptionInfo e
      (exit! (or (:exit (ex-data e)) 1) (ex-message e)))))
