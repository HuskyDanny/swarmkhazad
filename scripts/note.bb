#!/usr/bin/env bb

;; note.bb <kind> <claim> <why> — append one bullet to a task's own files.
;;
;;   decision    a fork you resolved, and why. The alternative you rejected.
;;   gotcha      something that cost you time and would cost the next one too.
;;   escalation  something only a human can clear. This is an ASK.
;;   finding     something you established that nobody asked for. NOT an ask.
;;   release     what must be true around the merge, not about the diff.
;;
;; Two things this does that appending by hand does not.
;;
;; The format is fixed: `- [<repo>] **<claim>** — <why>`. Every reader of these
;; files — the portal's Attention list, the summarizer, the next role — splits
;; on that shape, and a bullet written freehand is a line they silently skip.
;;
;; The repo tag is stamped from the session, so a finding in a three-repo task
;; says which repo it is about without the role having to remember. A one-repo
;; task gets no tag: there is nothing to disambiguate and it would be noise on
;; every line.
;;
;; `finding` exists because escalation.md was carrying both. Measured on gobel:
;; ten of its 22 escalation lines were things the swarm had worked out and
;; written down, not things waiting on a human — and they counted toward an
;; Attention badge that then read as 22 problems.
;;
;; `release` exists for the same reason and cuts the other way. GobelCutover's
;; escalation.md held fifteen bullets. Six were release preconditions — rotate
;; this secret before the deploy, set that env var, watch this window — and NOT
;; ONE was about the PR's code, which changed a single URL literal. As
;; escalations they read as fifteen problems blocking a merge; as release
;; lines they are a checklist for the person who deploys it. Same facts, two
;; readers, two moments, and only one of them is a reason not to merge.
;;
;; The test between them is WHO IS BLOCKED. An escalation blocks the swarm: it
;; cannot finish the work until a human clears it. A release line blocks
;; nothing here — the work is done and this is what happens next.

(ns note
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "handoff_lib.bb")))

(def kinds
  {"decision" :decision-file
   "gotcha" :gotcha-file
   "escalation" :escalation-file
   "finding" :finding-file
   "release" :release-file})

(def ^{:doc
       "Where a retraction's reason is recorded. `retract` says a bullet was
        WRONG, not that its ask was met, and that is a fork the role resolved —
        so the why belongs with the other decisions rather than among the
        findings, which are things established."}
  retract-file-key :decision-file)

(defn resolve-escalations!
  "Cross off every escalation bullet containing `match`, and say how many.

   A role that escalates and then clears the blocker itself had no way to say
   so: the bullet is append-only, and the portal's tick was the only writer of
   the cross-off store — so the item sat on Attention as an open ask after the
   swarm had already dealt with it. Matching on the bullet's own text rather
   than a key because the role wrote that text; it does not know the hash.

   escalation.md is never edited. What a role said stays said; what was done
   about it is recorded separately, which is the same split the portal's tick
   has always used."
  [ctx match]
  (let [needle (str/lower-case match)
        lines (->> (str/split-lines (or (try (slurp (str (:escalation-file ctx))) (catch Exception _ nil)) ""))
                   (map str/trim)
                   (remove str/blank?)
                   (map #(str/replace % #"^- " "")))
        hits (filter #(str/includes? (str/lower-case %) needle) lines)]
    (doseq [l hits]
      (task-lib/set-handled! ctx (task-lib/attention-key {:kind "escalation" :text l}) true))
    (count hits)))

(def usage-text
  (str "Usage: note.bb <decision|gotcha|escalation|finding|release> <claim> <why>\n"
       "       note.bb resolved <match> <how>\n"
       "       note.bb retract  <match> <why>\n"
       "\n"
       "  decision    a fork you resolved — name the alternative you rejected\n"
       "  gotcha      something that cost you time and would cost the next one\n"
       "  escalation  something only a human can clear (an ask)\n"
       "  finding     something you established that nobody asked for (not an ask)\n"
       "  release     what must be true AROUND the merge — a secret to rotate, a\n"
       "              config to set, a deploy to watch, an ordering to respect.\n"
       "              Not about the diff, so it never blocks the PR: it reaches\n"
       "              the person who deploys, through the summary and the PR\n"
       "              body. The test against `escalation` is who is blocked — an\n"
       "              escalation stops the swarm, a release line stops nothing.\n"
       "  resolved    an escalation you have since cleared yourself — <match> is\n"
       "              any text from the bullet, <how> is what you did about it.\n"
       "              Crosses it off Attention and records the how as a finding.\n"
       "  retract     an escalation bullet that was WRONG — superseded, or a\n"
       "              probe you never meant to keep. Same cross-off, but the why\n"
       "              lands in decision.md, and readers skip the bullet instead\n"
       "              of weighing it. Use `resolved` when the ask was real and\n"
       "              you met it; `retract` when it should not have been asked.\n"
       "\n"
       "Both claim and why are one line each. The bullet is written for you:\n"
       "  - [<repo>] **<claim>** — <why>\n"))

(defn fail! [status message]
  (binding [*out* *err*] (println message))
  (System/exit status))

(defn one-line
  "Collapse the argument to a single line. A newline inside a bullet breaks
   every reader that counts lines, and silently truncating would hide half of
   what the role meant to say."
  [s]
  (-> (or s "") (str/replace #"\s*\n\s*" " ") str/trim))

(defn tag
  "The repo this session works in, or nil when the task has only one — the
   session id is the role's own name in that case, and so is the answer."
  [ctx session]
  (let [rows (task-lib/read-sessions-tsv ctx)
        repos (distinct (keep :repo rows))]
    (when (> (count repos) 1)
      (:repo (some #(when (= session (:session %)) %) rows)))))

(defn bullet [repo claim why]
  (str "- " (when repo (str "[" repo "] ")) "**" claim "** — " why "\n"))

(defn -main [& args]
  (when (some #{"--help" "-h"} args)
    (print usage-text)
    (System/exit 0))
  (let [[kind claim why] args
        file-key (kinds kind)
        cross-off? (#{"resolved" "retract"} kind)]
    (when-not (or file-key cross-off?)
      (fail! 1 (str "note.bb: unknown kind " (pr-str kind) "\n\n" usage-text)))
    (let [claim (one-line claim)
          why (one-line why)]
      (when (str/blank? claim) (fail! 1 (str "note.bb: the claim is empty\n\n" usage-text)))
      (when (str/blank? why) (fail! 1 (str "note.bb: the why is empty — a claim with no reason is a line nobody can act on\n\n" usage-text)))
      (when (seq (drop 3 args)) (fail! 1 (str "note.bb: too many arguments; quote the claim and the why\n\n" usage-text)))
      (let [ctx (task-lib/ctx-from-env)
            session (handoff-lib/session ctx)]
        (if cross-off?
          (let [retract? (= "retract" kind)
                n (resolve-escalations! ctx claim)
                dest (get ctx (if retract? retract-file-key :finding-file))]
            ;; A match that hits nothing is a typo, and silently doing nothing
            ;; would leave the role believing it had cleared the item.
            (when (zero? n)
              (fail! 1 (str "note.bb: no escalation bullet contains " (pr-str claim)
                            " — nothing crossed off")))
            (spit (str dest)
                  (bullet (tag ctx session)
                          (str (if retract? "retracted: " "resolved: ") claim)
                          why)
                  :append true)
            (println (str (if retract? "RETRACTED " "RESOLVED ") n
                          " escalation line(s); why recorded in " dest)))
          (let [file (get ctx file-key)]
            (spit (str file) (bullet (tag ctx session) claim why) :append true)
            (println (str "NOTED " kind ": " file))))))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (try
    (apply -main *command-line-args*)
    (catch clojure.lang.ExceptionInfo e
      (fail! (or (:exit (ex-data e)) 1) (ex-message e)))))
