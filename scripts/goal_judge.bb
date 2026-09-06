#!/usr/bin/env bb

;; goal_judge.bb — khazad's GoalJudgeModel as a Stop hook on every claude role.
;;
;; When a role tries to end its turn, this grades the role's working state
;; against goal.md with a cheap model (schema-forced {met, unmet}, thinking
;; off, bounded output) and decides, as khazad's decide_stop does:
;;
;;   unmet, budget left        block the stop: "goals unmet: …" — keep working
;;   met, no handoff yet       block once more: "send your git_handoff" — nothing
;;                             else wakes a role that stops with met work unsent
;;   met and handed off        allow
;;   terminal inbound          allow (the last role's broadcast: merge and stop)
;;   budget exhausted          allow, but the verdict stands
;;   judge down                allow the stop (an infra fault must not wedge the
;;                             role) — but the verdict is met=false,
;;                             unmet=[judge_unavailable]: never a silent pass
;;
;; The verdict is written to state/judge/<role>.json; swarm_handoff.bb refuses a
;; git_handoff unless the latest verdict says met. Unmet items go to
;; escalation.md as one bullet per distinct verdict. Evidence under
;; <task>/evidence/ is part of the state the judge reads.
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

(def max-blocks 3)
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
       "met by the state as shown. Judge only from the evidence given; a claim in the draft without a matching "
       "commit, file or measurement is not met. Be strict and literal. Report through the structured output only."))

(defn clip [s]
  (let [s (str s)]
    (if (> (count s) max-chars) (str (subs s 0 max-chars) "\n…[truncated]") s)))

(defn sh [dir & args]
  (let [r (apply process/sh {:continue true :dir (str dir)} args)]
    (if (zero? (:exit r)) (str/trim (:out r)) "")))

;; ---------------------------------------------------------------- state

(defn git-state [worktree]
  (when (and worktree (fs/directory? (fs/path worktree)))
    (let [upstream (sh worktree "git" "rev-parse" "--abbrev-ref" "--symbolic-full-name" "@{upstream}")
          base (if (str/blank? upstream)
                 (sh worktree "git" "rev-parse" "--abbrev-ref" "origin/HEAD")
                 upstream)
          range (if (str/blank? base) "HEAD~10..HEAD" (str base "..HEAD"))]
      (str "### git status\n" (or (not-empty (sh worktree "git" "status" "--short")) "(clean)") "\n\n"
           "### commits (" range ")\n" (or (not-empty (sh worktree "git" "log" "--oneline" range)) "(none)") "\n\n"
           "### diff --stat\n" (or (not-empty (sh worktree "git" "diff" "--stat" range)) "(none)") "\n"))))

(defn files-section [title paths]
  (when (seq paths)
    (str "### " title "\n"
         (str/join "\n" (for [p paths] (str "--- " (fs/file-name p) "\n" (clip (slurp (str p))))))
         "\n")))

(defn working-state [ctx role worktree last-message]
  (let [draft (fs/path (:task-dir ctx) (str "draft-" role ".md"))
        evidence (when (fs/directory? (:evidence-dir ctx))
                   (->> (fs/list-dir (:evidence-dir ctx)) (filter fs/regular-file?) (sort-by str)))]
    (str "role: " role "\n\n"
         (or (git-state worktree) "### repo\n(this role has no repo)\n") "\n"
         (if (fs/regular-file? draft)
           (str "### draft-" role ".md\n" (clip (slurp (str draft))) "\n\n")
           (str "### draft-" role ".md\n(not written)\n\n"))
         (or (files-section "evidence" evidence) "### evidence\n(none)\n\n")
         (when-not (str/blank? last-message)
           (str "### role's last message\n" (clip last-message) "\n")))))

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
                            :extra-env {"MAX_THINKING_TOKENS" "0" "CLAUDE_CODE_MAX_OUTPUT_TOKENS" "600"}})
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

(defn verdict-file [ctx role] (fs/path (:state-dir ctx) "judge" (str role ".json")))
(defn blocks-file [ctx role] (fs/path (:state-dir ctx) "judge" (str role ".blocks")))

(defn read-json [path]
  (when (fs/regular-file? path)
    (try (json/parse-string (slurp (str path)) true) (catch Exception _ nil))))

(defn blocks-so-far [ctx role session-id]
  (let [b (read-json (blocks-file ctx role))]
    (if (= session-id (:session b)) (or (:count b) 0) 0)))

(defn record-block! [ctx role session-id]
  (spit (str (blocks-file ctx role))
        (json/generate-string {:session session-id :count (inc (blocks-so-far ctx role session-id))})))

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

(defn decide
  "khazad's decide_stop: {:block? bool :reason str}."
  [{:keys [met unmet down]} {:keys [terminal? sent? repo? blocks]}]
  (cond
    terminal? {:block? false :reason "terminal broadcast received; merge and stop"}
    (>= blocks max-blocks) {:block? false :reason (str "max blocks reached; " (if met "goals met" (str "unmet: " (str/join "; " unmet))))}
    down {:block? false :reason "judge unavailable; verdict is met=false until it returns"}
    (not met) {:block? true :reason (str "goals unmet: " (str/join "; " unmet) ". Keep working on these, then stop again. A bar you cannot meet is an escalation.md line.")}
    (and repo? (not sent?)) {:block? true :reason "goals met, but no git_handoff for your current HEAD has been queued. Commit if needed, then run swarm_handoff.bb on a git_handoff draft, then stop."}
    :else {:block? false :reason nil}))

(defn escalate! [ctx role verdict previous]
  (when (and (not (:met verdict))
             (not= (set (:unmet verdict)) (set (:unmet previous))))
    (spit (str (:escalation-file ctx))
          (str "- **" role ": goal judge says unmet — " (str/join "; " (:unmet verdict)) "** — at "
               (handoff-lib/timestamp) (when (:down verdict) (str "; judge unavailable: " (:error verdict))) "\n")
          :append true)))

(defn -main []
  (let [task-dir (System/getenv "SWARMKHAZAD_TASK_DIR")
        role (System/getenv "SWARMFORGE_ROLE")]
    (when (or (str/blank? task-dir) (str/blank? role) (not (fs/directory? task-dir)))
      (System/exit 0))
    (let [input (try (json/parse-string (slurp *in*)) (catch Exception _ {}))
          _ (when-not (= "Stop" (get input "hook_event_name")) (System/exit 0))
          ctx (task-lib/ctx-from-env)
          row (task-lib/role-row ctx role)
          worktree (when (:repo row) (:worktree-path row))
          session-id (or (get input "session_id") "unknown")
          goals (if (fs/regular-file? (:goal-file ctx)) (slurp (str (:goal-file ctx))) "")
          previous (read-json (verdict-file ctx role))
          verdict (grade goals (working-state ctx role worktree (get input "last_assistant_message")))
          facts {:terminal? (terminal-inbound? ctx role)
                 :sent? (handoff-sent? ctx role worktree)
                 :repo? (boolean (:repo row))
                 :blocks (blocks-so-far ctx role session-id)}
          decision (decide verdict facts)]
      (fs/create-dirs (fs/path (:state-dir ctx) "judge"))
      (spit (str (verdict-file ctx role))
            (json/generate-string (merge verdict {:role role :at (handoff-lib/timestamp) :session session-id
                                                  :decision (if (:block? decision) "block" "allow") :reason (:reason decision)})
                                  {:pretty true}))
      (escalate! ctx role verdict previous)
      (when (:block? decision)
        (record-block! ctx role session-id)
        (println (json/generate-string {:decision "block" :reason (:reason decision)})))
      (System/exit 0))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (try
    (-main)
    (catch Exception e
      ;; Fail OPEN on our own bugs: a hook that crashes must not wedge the role.
      (binding [*out* *err*] (println "goal_judge:" (ex-message e)))
      (System/exit 0))))
