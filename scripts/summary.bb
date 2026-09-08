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

(def base-unresolved
  "What the diff section says when there is no base to count against.

   It must NOT read as an empty diff. Measured: superset's worktree held commit
   ef72964 — one Dockerfile line, the task's whole deliverable — and the summary
   rendered `diff — superset` as `(none)`, identical to gobel's correctly
   zero-diff repo beside it. The verdict came back `NOT READY — both diffs are
   empty`, and the one thing a human reads before merging was wrong about the
   only thing that had changed."
  (str "BASE UNRESOLVED — the diff was NOT computed.\n"
       "No pin in state/base.tsv, and none of origin/HEAD, origin/main,\n"
       "origin/master, main or master resolves in this worktree.\n"
       "This is a MISSING measurement, not an empty one. Do not read it as\n"
       "'no change': a repo holding commits looks exactly like this.\n"
       "Fix: `git -C <source-checkout> remote set-head origin -a`, then re-run."))

(defn base-ref
  "The commit this repo's work is counted against.

   The pin in state/base.tsv first — recorded when the worktree was made, and
   the only answer here that cannot move. Every ref below it belongs to the
   source checkout, which is SHARED with every other worktree of it and with
   the operator's own branch: one `git fetch` in ~/repos/<repo> re-points
   origin/main under a running task.

   Then the derivable refs, for tasks opened before the pin existed. Order
   matters and the old one was wrong twice over: `origin/HEAD` is a symref
   `git clone` creates and other setups never do, so it is simply absent in
   some checkouts; and a LOCAL `main` can sit arbitrarily far behind its
   remote. Measured in gobel — local main was 5 commits behind origin/main, so
   falling back to it would have reported 5 unrelated commits as this task's
   diff. A wrong base fabricates a diff as easily as it loses one, so every
   remote-tracking ref is tried before any local branch.

   nil when nothing resolves, and the caller must say so out loud."
  [ctx repo worktree]
  (let [ok? (fn [r] (git worktree "rev-parse" "--verify" "--quiet" r))]
    (or (when-let [pin (get (task-lib/read-base-tsv ctx) repo)]
          ;; A pinned sha the worktree does not hold is worse than no pin: the
          ;; range silently fails and the section goes empty again.
          (when (ok? (str pin "^{commit}")) pin))
        (first (filter ok? ["origin/HEAD" "origin/main" "origin/master"
                            "main" "master"])))))

(defn worktree-diff
  "What one repo's worktree actually changed: the stat, then the patch. Both are
   clipped — a summary that costs more than reading the diff is not a summary."
  [ctx repo worktree budget]
  (when (and worktree (fs/directory? worktree))
    (if-let [base (base-ref ctx repo worktree)]
      (let [range (str base "..HEAD")
            stat (git worktree "diff" "--stat" range)
            log (git worktree "log" "--oneline" range)
            patch (git worktree "diff" range)
            dirty (git worktree "status" "--porcelain")]
        (when (or (seq (or stat "")) (seq (or dirty "")))
          (str "base: " base "\n\n"
               "commits:\n" (or (not-empty log) "(none)") "\n\n"
               "stat:\n" (or (not-empty stat) "(none)") "\n\n"
               (when (seq (or dirty "")) (str "uncommitted:\n" dirty "\n\n"))
               "patch:\n" (clip patch budget))))
      base-unresolved)))

(defn live-escalations
  "escalation.md with the crossed-off bullets marked, not hidden.

   The file is append-only and the roles own it — a bullet that turned out to
   be wrong cannot be removed, only followed by another bullet retracting it.
   Measured: escalation.md opened with `- [superset] **probe** — probe`, then
   an entire entry whose only content was `Ignore the bare probe line above`,
   then two more retracting a third. The verdict spent a `nit` on the noise
   and still had to work out which entries were live.

   `note.bb resolved` and `note.bb retract` already record that, in the same
   store the portal's tick writes. Reading it here is what makes the record
   reach the one reader that decides a merge. Marked rather than dropped,
   because a retraction is itself a fact about the run, and a bullet that
   silently vanished would read as a file nobody wrote to."
  [ctx]
  (let [handled (task-lib/handled ctx)
        raw (or (read-file (:escalation-file ctx) file-budget) "")
        lines (remove str/blank? (map str/trim (str/split-lines raw)))
        live (fn [l]
               (let [text (str/replace l #"^- " "")]
                 (if (contains? handled (task-lib/attention-key {:kind "escalation" :text text}))
                   (str "- [CROSSED OFF — dealt with or retracted; do not treat as an open ask] "
                        text)
                   l)))]
    (when (seq lines)
      (str/join "\n" (map live lines)))))

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
     (section "escalation.md — what they say needs a human" (live-escalations ctx))
     ;; Splitting findings out of escalation.md took them away from the only
     ;; reader that weighs them before a merge. Ten of gobel's 22 escalation
     ;; lines were findings; a verdict that cannot see them is reading half the
     ;; task's own notes.
     (section "finding.md — what they established that nobody asked for" (read-file (:finding-file ctx) file-budget))
     (section "evidence — each bar's own output" (evidence-section ctx))
     ;; One diff per repo, not per session: roles sharing a repo share its
     ;; worktree, so a per-session loop would print the same diff twice.
     (str/join "" (for [[repo worktree] repos]
                    (section (str "diff — " repo)
                             (worktree-diff ctx repo worktree per-repo)))))))

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
   "`no change`. A repo whose diff section says BASE UNRESOLVED is NOT `no "
   "change` — its diff was never computed, so it can only be NOT READY, and "
   "say that the measurement is missing rather than that nothing changed. "
   "Skip this section entirely when there is only one repo.\n\n"
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
