#!/usr/bin/env bb

;; goal_judge.bb — khazad's GoalJudgeModel, at the handoff boundary.
;;
;; Two entry points, and the split is the whole design:
;;
;;   goal_judge.bb --grade <session>
;;     Grade this session's COMMITTED state against its own goal lines with a
;;     cheap model (schema-forced {met, unmet}, thinking off, bounded output),
;;     write state/judge/<session>.json, print it. swarm_handoff.bb calls this
;;     once, when a git_handoff is actually being sent, and refuses the handoff
;;     while the verdict is unmet — until the refusal budget for that session is
;;     spent, after which the work goes forward with the gap named on it.
;;
;;   goal_judge.bb          (Stop hook, hook JSON on stdin)
;;     A NUDGE, and nothing about goals: if the session has committed work and
;;     no git_handoff for that HEAD, keep the turn open and say so. That is the
;;     one failure a stopping role cannot see — it finishes, stops, and the
;;     board freezes with nobody watching.
;;
;; Grading used to run on EVERY stop. Measured on gobel: the same role graded
;; four times in one turn, three escalation lines saying the same thing, and a
;; verdict that flipped UNMET → MET on a tree with no commit and no evidence
;; write in between. Grading once, on the committed tree, at the moment the work
;; is handed over, removes the duplicates and the flipping by removing the
;; repetition rather than patching it.
;;
;; A judge that cannot run is never a silent pass: the verdict is met=false,
;; unmet=[judge_unavailable], and the refusal budget still applies, so an infra
;; fault delays a handoff rather than wedging the task.
;;
;; The judge call runs through the role's own environment — a kimi role grades
;; with kimi's small model — via `claude -p --json-schema`.

(ns goal-judge
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "handoff_lib.bb")))

(def max-nudges
  "How many times one turn may be held open to ask for a handoff. The nudge is
   for forgetting, not for arguing: a role that has been told twice and still
   has not sent one is telling you something the pane cannot fix."
  2)
(def judge-timeout-ms 120000)
(def max-chars 6000)
(def judge-unavailable "judge_unavailable")

(def verdict-schema
  (json/generate-string
   {:type "object"
    :properties {:met {:type "boolean" :description "True only when EVERY Goal checkbox and Acceptance line in goal.md is satisfied by the working state."}
                 :unmet {:type "array" :items {:type "string"} :description "The goal labels or acceptance lines NOT yet satisfied. Empty when met is true."}}
    :required ["met" "unmet"]}))

(def system-prompt
  (str "You are a goal-completion judge for one role of an agent swarm. You are given goal.md — the task's "
       "contract — and a summary of the role's working state: its commits and diff, its draft write-up, and any "
       "measurement evidence. Decide whether EVERY goal and acceptance line that this role is responsible for is "
       "met by the state as shown. A goal.md checkbox reads `- [ ] <role> — <outcome>`; grade ONLY the lines naming "
       "this role or naming none, and never a line under a heading that says the lines are not this role's. "
       "Judge only from the evidence given; a claim in the draft without a matching "
       "commit, file or measurement is not met. Be strict and literal. Report through the structured output only."))

(defn goals-for-session
  "goal.md, with the Goal checkboxes split into this session's and the others'.

   The convention is `- [ ] <role> @<repo> — <outcome>`, so a task's goal.md
   names work no single session can do. Graded whole, every session is unmet
   until the last one finishes — measured on the fixture: implement met all
   seven of its own lines and was blocked on `run —` and `review —`.

   A line is this session's when it names no role or names this role, AND tags
   no repo or tags this repo. Both defaults are the same one: a line that never
   said belongs to everyone, which is what a single-repo task writes."
  [goals-md role repo]
  (let [lines (str/split-lines (or goals-md ""))
        box? #(some? (task-lib/goal-line %))
        mine? (fn [l] (when-let [g (task-lib/goal-line l)]
                        (and (or (nil? (:role g)) (= role (:role g)))
                             (or (empty? (:repos g)) (boolean (some #{repo} (:repos g)))))))
        [mine others] [(filter #(and (box? %) (mine? %)) lines)
                       (filter #(and (box? %) (not (mine? %))) lines)]]
    {:mine (vec mine)
     :others (vec others)
     :whole (str/join "\n" (remove #(and (box? %) (not (mine? %))) lines))}))

(defn clip [s]
  (let [s (str s)]
    (if (> (count s) max-chars) (str (subs s 0 max-chars) "\n…[truncated]") s)))

(defn sh [dir & args]
  (let [r (apply process/sh {:continue true :dir (str dir)} args)]
    (if (zero? (:exit r)) (str/trim (:out r)) "")))

;; ---------------------------------------------------------------- state

(declare base-ref)

(defn git-state
  "The worktree's state, counted against one base — `base-ref`, the same one
   summary.bb uses, so the judge and the merge verdict cannot disagree about
   what a role changed.

   Two resolutions were removed here, both able to report a wrong diff rather
   than no diff. `@{upstream}` led: once a role pushes its task branch, that
   resolves to `origin/sk/<task-id>`, whose range against HEAD is EMPTY — the
   judge would see a pushed role as having committed nothing. And the last
   resort was `HEAD~10..HEAD`, which counts ten commits of unrelated history as
   this task's work in any repo where the refs failed."
  [ctx repo worktree]
  (when (and worktree (fs/directory? (fs/path worktree)))
    (if-let [base (base-ref ctx repo worktree)]
      (let [range (str base "..HEAD")]
        (str "### git status\n" (or (not-empty (sh worktree "git" "status" "--short")) "(clean)") "\n\n"
             "### commits (" range ")\n" (or (not-empty (sh worktree "git" "log" "--oneline" range)) "(none)") "\n\n"
             "### diff --stat\n" (or (not-empty (sh worktree "git" "diff" "--stat" range)) "(none)") "\n"))
      (str "### repo\nBASE UNRESOLVED — no pin in state/base.tsv and no\n"
           "origin/HEAD, origin/main, origin/master, main or master in this\n"
           "worktree, so the commits and diff were NOT computed. This is a\n"
           "missing measurement: do not grade it as 'nothing was committed'.\n"))))

(defn files-section [title paths]
  (when (seq paths)
    (str "### " title "\n"
         (str/join "\n" (for [p paths] (str "--- " (fs/file-name p) "\n" (clip (slurp (str p))))))
         "\n")))

(defn working-state
  "What the judge grades: the session's commits and diff, its draft write-up,
   and the measurements under evidence/.

   The role's last assistant message used to be part of this. It is not any
   more: grading happens when the handoff is sent, and what is being handed
   over is the commit, never the sentence the role wrote about it."
  [ctx session worktree repo]
  (let [draft (fs/path (:task-dir ctx) (str "draft-" session ".md"))
        evidence (when (fs/directory? (:evidence-dir ctx))
                   (->> (fs/list-dir (:evidence-dir ctx)) (filter fs/regular-file?) (sort-by str)))]
    (str "session: " session "\n\n"
         (or (git-state ctx repo worktree) "### repo\n(unavailable)\n") "\n"
         (if (fs/regular-file? draft)
           (str "### draft-" session ".md\n" (clip (slurp (str draft))) "\n\n")
           (str "### draft-" session ".md\n(not written)\n\n"))
         (or (files-section "evidence" evidence) "### evidence\n(none)\n\n"))))

;; ---------------------------------------------------------------- judge call

(defn judge-argv [prompt-text]
  ["claude" "-p" "--model" "haiku"
   "--json-schema" verdict-schema
   "--tools" "" "--strict-mcp-config" "--mcp-config" "{\"mcpServers\":{}}"
   "--no-session-persistence" "--output-format" "json"
   "--system-prompt" system-prompt
   "--" prompt-text])

(defn grade
  "Call the judge. Any failure — exit, timeout, no JSON, no verdict — is
   {:met false :unmet [judge_unavailable] :down true}. Never met on failure."
  [goals-md state]
  (let [prompt-text (str "<goals_md>\n" goals-md "\n</goals_md>\n\n<working_state>\n" state "\n</working_state>")
        p (process/process (judge-argv prompt-text)
                           {:out :string :err :string
                            :extra-env {"MAX_THINKING_TOKENS" "0" "CLAUDE_CODE_MAX_OUTPUT_TOKENS" "600"
                                        ;; The judge runs through the role's shim and would otherwise
                                        ;; export its own spend and session count under the role's own
                                        ;; labels — measured on the fixture, where three roles reported
                                        ;; 5, 4 and 3 sessions for one session each plus their gradings.
                                        "OTEL_RESOURCE_ATTRIBUTES" (str "task_id=" (System/getenv "SWARMKHAZAD_TASK_ID")
                                                                        ",role=" (System/getenv "SWARMKHAZAD_SESSION") "-judge")}})
        done (deref p judge-timeout-ms nil)
        _ (when-not done (process/destroy-tree p))
        result (if done @p {:exit -1 :out "" :err "timed out"})
        parsed (try (json/parse-string (:out result)) (catch Exception _ nil))
        verdict (get parsed "structured_output")]
    (if (and done (zero? (:exit result)) (map? verdict) (contains? verdict "met"))
      {:met (boolean (get verdict "met"))
       :unmet (mapv str (or (get verdict "unmet") []))
       :model (first (keys (get parsed "modelUsage")))
       :cost (get parsed "total_cost_usd")}
      {:met false :unmet [judge-unavailable] :down true
       :error (str/trim (str (:err result) " " (subs (or (:out result) "") 0 (min 300 (count (or (:out result) ""))))))})))

;; ---------------------------------------------------------------- decision

(defn verdict-file [ctx session] (fs/path (:state-dir ctx) "judge" (str session ".json")))
(defn nudges-file [ctx session] (fs/path (:state-dir ctx) "judge" (str session ".nudges")))

(defn read-json [path]
  (when (fs/regular-file? path)
    (try (json/parse-string (slurp (str path)) true) (catch Exception _ nil))))

(defn nudges-so-far [ctx session turn]
  (let [b (read-json (nudges-file ctx session))]
    (if (= turn (:turn b)) (or (:count b) 0) 0)))

(defn record-nudge! [ctx session turn]
  (spit (str (nudges-file ctx session))
        (json/generate-string {:turn turn :count (inc (nudges-so-far ctx session turn))})))

(defn handoff-sent?
  "Has this role queued a git_handoff for its current HEAD (outbox or sent)?"
  [ctx role worktree]
  (let [head (when worktree (sh worktree "git" "rev-parse" "--short=10" "HEAD"))]
    (boolean
     (and (not (str/blank? head))
          (some (fn [f]
                  (let [h (:headers (handoff-lib/parse-message f))]
                    (and (= "git_handoff" (get h "type")) (= head (get h "commit")))))
                (concat (handoff-lib/handoff-files (handoff-lib/outbox-dir ctx role))
                        (handoff-lib/handoff-files (fs/path (handoff-lib/mail-dir ctx role) "sent"))))))))

(defn terminal-inbound?
  "The last role's broadcast landed here: merge and stop, nothing to send."
  [ctx role]
  (boolean (some #(= "true" (handoff-lib/header-field % "non-forwarding"))
                 (concat (handoff-lib/in-process-files ctx role)
                         (handoff-lib/handoff-files (handoff-lib/completed-dir ctx role))))))

(defn base-ref
  "The commit a role's work is counted against.

   state/base.tsv first: pinned when the worktree was made, and the only answer
   that cannot move. `origin/HEAD` used to lead, but it is a symref `git clone`
   creates and other setups never do — measured absent in a live task, which is
   how a summary came to read an empty diff for a repo holding a commit. Local
   `main`/`master` come last because they are the operator's branches and can
   sit behind their remote (gobel's was 5 commits behind), and a base that is
   too old fabricates a diff rather than losing one.

   nil when nothing resolves; callers treat that as 'cannot tell', never as 'no
   commits'."
  ([ctx repo worktree]
   (let [ok? (fn [r] (zero? (:exit (process/sh {:continue true :dir (str worktree)}
                                               "git" "rev-parse" "--verify" "--quiet" r))))]
     (or (when (and ctx repo)
           (when-let [pin (get (task-lib/read-base-tsv ctx) repo)]
             (when (ok? (str pin "^{commit}")) pin)))
         (first (filter ok? ["origin/HEAD" "origin/main" "origin/master"
                             "main" "master"]))))))

(defn committed-past-base?
  "The worktree holds commits past the task branch's base.

   A git that cannot answer counts as committed. The two errors are not
   symmetric: a spurious nudge costs one line in a pane nobody is reading,
   a missed one leaves the board frozen."
  [ctx repo worktree]
  (if (nil? worktree)
    false
    (if-let [base (base-ref ctx repo worktree)]
      (let [r (process/sh {:continue true :dir (str worktree)} "git" "rev-list" "--count" (str base "..HEAD"))]
        (or (not (zero? (:exit r))) (not= "0" (str/trim (:out r)))))
      true)))

(defn nudge
  "What to say when a session ends its turn, or nil to let it stop.

   Nothing here is about goals — those are graded once, when the handoff is
   sent. This is the one thing a stopping role cannot see about itself."
  [{:keys [terminal? sent? committed? nudges]}]
  (cond
    terminal? nil
    (not committed?) nil
    sent? nil
    (>= nudges max-nudges) nil
    :else (str "You have committed work in this worktree and no git_handoff for its HEAD. "
               "Nothing else wakes the next role: write a git_handoff draft under the task's tmp/ "
               "and run swarm_handoff.bb on it. If the work is not ready, say why in escalation.md first.")))

(defn escalate!
  "One escalation line per DISTINCT unmet verdict. Graded once per handoff
   attempt now, so a repeat only happens when the role tried again and still
   fell short on something new.

   Through note.bb, like every other writer. Appending here directly made the
   contract's `note.bb is the only writer` true of the roles — the hook denies
   them — and false of the tool itself, and the line it wrote carried no
   `[repo]` tag: in a task with three repos, the judge's own verdicts were the
   only escalations that did not say which one they were about."
  [ctx session verdict previous]
  (when (and (not (:met verdict))
             (seq (:unmet verdict))
             (not= (set (:unmet verdict)) (set (:unmet previous))))
    (process/sh {:continue true
                 :extra-env {"SWARMKHAZAD_SESSION" session
                             "SWARMKHAZAD_TASK_ID" (:task-id ctx)
                             "SWARMKHAZAD_TASK_DIR" (str (:task-dir ctx))}}
                "bb" (str (fs/path script-dir "note.bb")) "escalation"
                (str session ": goal judge says unmet — " (str/join "; " (:unmet verdict)))
                (str "at " (handoff-lib/timestamp)
                     (when (:down verdict) (str "; judge unavailable: " (:error verdict)))))))

;; ---------------------------------------------------------------- entry

(defn session-ctx
  "The task ctx, the session's row, and the goal.md its verdict is about."
  [session]
  (let [ctx (task-lib/ctx-from-env)
        row (task-lib/session-row ctx session)
        goals-md (if (fs/regular-file? (:goal-file ctx)) (slurp (str (:goal-file ctx))) "")
        split (goals-for-session goals-md (or (:role row) session) (:repo row))]
    {:ctx ctx
     :row row
     :worktree (:worktree-path row)
     ;; The roles named by the lines this session does NOT own. Kept so the
     ;; verdict can be held to the partition instead of merely being shown it.
     :other-roles (set (keep #(:role (task-lib/goal-line %)) (:others split)))
     :goals (str (:whole split)
                 (when (seq (:others split))
                   (str "\n\n## Not yours — other roles own these; do not grade them\n"
                        (str/join "\n" (:others split)) "\n")))}))

(defn own-unmet-only
  "Drop unmet items that name another role's goal line.

   The ownership partition is computed correctly — `:mine` versus `:others` —
   but until now it only SUGGESTED ownership to the judge: the other lines are
   put in the prompt under a heading saying not to grade them, and a cheap
   model grades them anyway. Measured: review_superset came back unmet on
   `implement @gobel — ... URL repoint to .com ...`, a line describing a change
   its own goal line calls a send-back, and which it could not act on from its
   own repo. That blocked its handoff and was written verbatim into
   append-only escalation.md, where it could not be removed.

   Only the unambiguous case is dropped: an item that OPENS with another
   role's name. A paraphrase of this session's own line never does, so this
   cannot quietly turn a real unmet verdict into a pass. What was dropped is
   recorded on the verdict, because a silent correction is the other way to
   lose a measurement."
  [verdict own-role other-roles]
  (if (or (:down verdict) (empty? (:unmet verdict)) (empty? other-roles))
    verdict
    (let [foreign? (fn [item]
                     (let [t (str/lower-case (str/trim (str item)))]
                       (some (fn [r] (and (not= r own-role)
                                          (str/starts-with? t (str/lower-case (str r)))))
                             other-roles)))
          dropped (vec (filter foreign? (:unmet verdict)))
          kept (vec (remove foreign? (:unmet verdict)))]
      (if (empty? dropped)
        verdict
        (assoc verdict
               :unmet kept
               ;; Every reason the model gave belonged to someone else, so on
               ;; its own lines this session is met. Leaving met=false here
               ;; would keep the block this exists to remove.
               :met (if (empty? kept) true (:met verdict))
               :dropped-unmet dropped)))))

(defn grade!
  "Grade the session's committed state now, write the verdict, return it.

   This is the only place a verdict is produced. Called by swarm_handoff.bb at
   the moment a git_handoff is sent, so the tree being graded is the tree being
   handed over — gobel's judge produced a wrong \"not committed\" verdict once by
   grading something else."
  [session]
  (let [{:keys [ctx row worktree goals other-roles]} (session-ctx session)
        previous (read-json (verdict-file ctx session))
        verdict (-> (grade goals (working-state ctx session worktree (:repo row)))
                    (own-unmet-only (or (:role row) session) other-roles))]
    (fs/create-dirs (fs/path (:state-dir ctx) "judge"))
    (spit (str (verdict-file ctx session))
          (json/generate-string (merge verdict {:role session :at (handoff-lib/timestamp)})
                                {:pretty true}))
    (escalate! ctx session verdict previous)
    verdict))

(defn grade-cmd!
  "`goal_judge.bb --grade <session>` — grade and print the verdict as JSON."
  [session]
  (println (json/generate-string (grade! session)))
  (System/exit 0))

(defn stop-hook!
  "The Stop hook: a nudge when work is committed and unsent, nothing more."
  [session]
  (let [input (try (json/parse-string (slurp *in*)) (catch Exception _ {}))]
    (when-not (= "Stop" (get input "hook_event_name")) (System/exit 0))
    (let [ctx (task-lib/ctx-from-env)
          row (task-lib/session-row ctx session)
          worktree (:worktree-path row)
          turn (or (get input "session_id") "unknown")
          reason (nudge {:terminal? (terminal-inbound? ctx session)
                         :sent? (handoff-sent? ctx session worktree)
                         :committed? (committed-past-base? ctx (:repo row) worktree)
                         :nudges (nudges-so-far ctx session turn)})]
      (when reason
        (fs/create-dirs (fs/path (:state-dir ctx) "judge"))
        (record-nudge! ctx session turn)
        (println (json/generate-string {:decision "block" :reason reason})))
      (System/exit 0))))

(defn -main [& args]
  (let [task-dir (System/getenv "SWARMKHAZAD_TASK_DIR")
        session (or (first (remove #(str/starts-with? % "--") args))
                    (System/getenv "SWARMKHAZAD_SESSION"))]
    (when (or (str/blank? task-dir) (str/blank? session) (not (fs/directory? task-dir)))
      (System/exit 0))
    (if (some #{"--grade"} args)
      (grade-cmd! session)
      (stop-hook! session))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (try
    (apply -main *command-line-args*)
    (catch Exception e
      ;; Fail OPEN on our own bugs: a hook that crashes must not wedge the role.
      (binding [*out* *err*] (println "goal_judge:" (ex-message e)))
      (System/exit 0))))
