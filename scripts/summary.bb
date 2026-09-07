#!/usr/bin/env bb

;; summary.bb — "is this ready to merge, for its goals?", on one page.
;;
;; Everything the answer needs is already in the task folder and the role
;; worktrees: the contract (goal.md, metrics.md), what the judge said, what the
;; roles decided and tripped over (decision.md, gotcha.md, escalation.md), the
;; evidence each bar produced, and the diff itself. Reading all of that is the
;; slow part of deciding whether to merge, and it is the part nobody does at
;; 6pm.
;;
;; One call, started by a click, cached in state/summary.md until clicked again.
;; It never merges anything and never writes into the contract.

(ns summary
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "task_lib.bb")))
(load-file (str (fs/path script-dir "ask.bb")))

(def merge-order-heading
  "The section ship reads its order from. One name, in one place: the prompt
   asks for this heading and ship parses this heading, so a rename cannot
   leave the two disagreeing about a section that then silently goes missing."
  "Merge order")

(def diff-budget 60000)
(def file-budget 12000)

(defn summary-file [ctx] (fs/path (:state-dir ctx) "summary.md"))

(defn- clip [s n]
  (let [s (str s)]
    (if (<= (count s) n) s (str (subs s 0 n) "\n… clipped at " n " characters\n"))))

(defn- read-file [path n]
  (when (fs/regular-file? path) (clip (slurp (str path)) n)))

(defn- git [dir & args]
  (let [r (apply process/sh {:continue true :dir (str dir)} "git" args)]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn base-ref
  "The ref this worktree's work is counted against. Same fallback chain the goal
   judge uses: a source checkout cloned without an upstream has no origin/HEAD."
  [worktree]
  (first (filter #(git worktree "rev-parse" "--verify" "--quiet" %)
                 ["origin/HEAD" "main" "master"])))

(defn worktree-diff
  "What one role's worktree actually changed: the stat, then the patch. Both are
   clipped — a summary that costs more than reading the diff is not a summary."
  [worktree budget]
  (when (and worktree (fs/directory? worktree))
    (when-let [base (base-ref worktree)]
      (let [range (str base "..HEAD")
            stat (git worktree "diff" "--stat" range)
            log (git worktree "log" "--oneline" range)
            patch (git worktree "diff" range)
            dirty (git worktree "status" "--porcelain")]
        (when (or (seq (or stat "")) (seq (or dirty "")))
          (str "commits:\n" (or (not-empty log) "(none)") "\n\n"
               "stat:\n" (or (not-empty stat) "(none)") "\n\n"
               (when (seq (or dirty "")) (str "uncommitted:\n" dirty "\n\n"))
               "patch:\n" (clip patch budget)))))))

(defn evidence-section [ctx]
  (let [dir (:evidence-dir ctx)]
    (when (fs/directory? dir)
      (str/join "\n" (for [f (sort (fs/list-dir dir))]
                       (str "--- " (fs/file-name f) "\n" (clip (slurp (str f)) 2500)))))))

(defn gather
  "Everything the question needs, as one document. Sections that are empty say
   so: a missing decision.md and an unread one look identical otherwise, and the
   answer leans on which it was."
  [ctx]
  (let [repos (->> (task-lib/read-sessions-tsv ctx)
                   (keep (fn [r] (when (:worktree-path r) [(or (:repo r) "?") (:worktree-path r)])))
                   distinct)
        per-repo (int (max 4000 (quot diff-budget (max 1 (count repos)))))
        section (fn [title body] (str "## " title "\n" (or (not-empty (str/trim (str body))) "(none)") "\n\n"))]
    (str
     (section "goal.md" (read-file (:goal-file ctx) file-budget))
     (section "metrics.md" (read-file (:metrics-file ctx) file-budget))
     (section "decision.md — what the roles chose, and why" (read-file (:decision-file ctx) file-budget))
     (section "gotcha.md — what tripped them" (read-file (:gotcha-file ctx) file-budget))
     (section "escalation.md — what they say needs a human" (read-file (:escalation-file ctx) file-budget))
     ;; Splitting findings out of escalation.md took them away from the only
     ;; reader that weighs them before a merge. Ten of gobel's 22 escalation
     ;; lines were findings; a verdict that cannot see them is reading half the
     ;; task's own notes.
     (section "finding.md — what they established that nobody asked for" (read-file (:finding-file ctx) file-budget))
     (section "evidence — each bar's own output" (evidence-section ctx))
     ;; One diff per repo, not per session: roles sharing a repo share its
     ;; worktree, so a per-session loop would print the same diff twice.
     (str/join "" (for [[repo worktree] repos]
                    (section (str "diff — " repo) (worktree-diff worktree per-repo)))))))

(def system-prompt
  (str
   "You advise on whether a task's work is ready to merge. You decide nothing "
   "and you merge nothing; you tell a tired reader what they would have found "
   "by reading everything themselves.\n\n"
   "You are given a task's contract (goal.md, metrics.md), what its roles "
   "decided and tripped over, what they escalated, the evidence each bar "
   "produced, and the diff from each role's worktree.\n\n"
   "Answer in markdown, in exactly these sections:\n\n"
   "## Verdict\n"
   "One line, starting with one of: READY TO MERGE / READY WITH FOLLOW-UPS / "
   "NOT READY / NEEDS A DECISION. Then one sentence saying why, in plain "
   "words. Judge the diff against THIS task's goals only.\n\n"
   "## Against the goals\n"
   "One line per goal line, in the order they appear in goal.md, each saying "
   "met / not met / partly, and the evidence you are relying on. Name the file "
   "or the bar. If a goal is met but only the judge says so and no evidence "
   "shows it, say that.\n\n"
   "## Findings\n"
   "Ordered most relevant to this task's goals first, least relevant last. For "
   "each: one line, then what it means for merging.\n"
   "- A regression, a behaviour change nobody asked for, or an architectural "
   "redesign beyond the goals is a BLOCKER and needs explicit approval. Say so "
   "plainly and put it first.\n"
   "- A small optimisation, a nit, a naming quibble, a missing comment: worth "
   "mentioning, never a blocker. Mark it `nit`. Do not pad this list with them.\n"
   "- Anything the not-goals exclude is out of scope. Do not report it as a "
   "finding; if it matters enough to raise, put one line at the end under "
   "`out of scope, noted` and leave it there.\n"
   "- Treat decision.md as settled. A decision recorded there with a reason is "
   "not a finding — re-litigating it is the single most expensive thing you can "
   "do here. If you think one is wrong, that is a NEEDS A DECISION verdict with "
   "the reason, not a finding.\n"
   "- Treat gotcha.md as known. Do not report something the roles already wrote "
   "down and worked around.\n\n"
   "## Per repo\n"
   "One line per repo you were given a diff for, `<repo>: <verdict> — <why>`, "
   "using the same verdict words as above. A repo whose diff is empty says "
   "`no change`. Skip this section entirely when there is only one repo.\n\n"
   "## " merge-order-heading "\n"
   "The repos, one per line, in the order they must merge, each `<repo> — "
   "<why it goes here>`. Name every repo that has a diff, even when the order "
   "does not matter — say so as the reason. A repo that depends on another "
   "merges after it. Skip this section entirely when there is only one repo.\n\n"
   "## Risk\n"
   "What could go wrong if this merges as it stands. Blast radius: who else "
   "moves with this change, what breaks if it is wrong, and how it is noticed. "
   "Say low/medium/high and why. Do not hedge everything to medium.\n\n"
   "## Before merge\n"
   "A numbered list of what a person must do first. Commands where you can give "
   "them. Nothing that is already done.\n\n"
   "## After merge\n"
   "What must happen once it lands, and what to expect. Name the infrastructure "
   "operations explicitly — an AWS console or CLI action, a secret to rotate, a "
   "migration to apply, a deploy to watch, a rollback if it goes wrong — and "
   "what a healthy result looks like so the reader knows when to stop watching. "
   "If none are needed, say `none` rather than inventing one.\n\n"
   "Rules: be specific and short. Cite file:line where you can. Say `I cannot "
   "tell from what I was given` rather than guessing — a confident wrong answer "
   "here gets something merged. Never claim a command was run; only the "
   "evidence section records what ran."))

(defn summarize!
  "One call, then written to state/summary.md with a header saying when and at
   what cost. Returns {:ok path} or {:error ...}."
  [ctx]
  (let [doc (gather ctx)
        r (ask/ask {:prompt (str "<task id=\"" (:task-id ctx) "\">\n" doc "\n</task>")
                    :system system-prompt
                    :model (or (not-empty (or (System/getenv "SWARMKHAZAD_SUMMARY_MODEL") "")) "opus")
                    :timeout-ms 600000
                    :label "summary"})]
    (if (or (:error r) (str/blank? (or (:text r) "")))
      {:error (or (:error r) "the model returned nothing")}
      (let [f (summary-file ctx)]
        (fs/create-dirs (fs/parent f))
        (spit (str f)
              (str "at: " (str (java.time.Instant/now)) "\n"
                   "model: " (or (:model r) "?") "\n"
                   "cost: " (format "%.4f" (double (or (:cost r) 0))) "\n"
                   "input-chars: " (count doc) "\n"
                   "--- summary ---\n"
                   (str/trim (:text r)) "\n"))
        {:ok (str f) :cost (:cost r)}))))

(defn read-summary
  "The cached summary as {:headers {} :body ...}, or nil."
  [ctx]
  (when-let [s (and (fs/regular-file? (summary-file ctx)) (slurp (str (summary-file ctx))))]
    (let [[head body] (str/split s #"--- summary ---\n" 2)]
      {:headers (into {} (for [l (str/split-lines (or head ""))
                               :let [[k v] (str/split l #": " 2)]
                               :when v]
                           [(str/trim k) (str/trim v)]))
       :body (or body head)})))

(defn -main [& args]
  (let [id (first args)
        _ (when (str/blank? id) (task-lib/fail! "usage: summary.bb <task-id>"))
        ctx (task-lib/task-ctx id)
        r (summarize! ctx)]
    (if (:error r)
      (task-lib/fail! (str "summary failed: " (:error r)))
      (println (str "summary: " (:ok r) (when (:cost r) (format "  $%.4f" (double (:cost r)))))))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
