#!/usr/bin/env bb

;; note.bb <kind> <claim> <why> — append one bullet to a task's own files.
;;
;;   decision    a fork you resolved, and why. The alternative you rejected.
;;   gotcha      something that cost you time and would cost the next one too.
;;   escalation  something only a human can clear. This is an ASK.
;;   finding     something you established that nobody asked for. NOT an ask.
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

(ns note
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "handoff_lib.bb")))

(def kinds
  {"decision" :decision-file
   "gotcha" :gotcha-file
   "escalation" :escalation-file
   "finding" :finding-file})

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
  (str "Usage: note.bb <decision|gotcha|escalation|finding> <claim> <why>\n"
       "       note.bb resolved <match> <how>\n"
       "\n"
       "  decision    a fork you resolved — name the alternative you rejected\n"
       "  gotcha      something that cost you time and would cost the next one\n"
       "  escalation  something only a human can clear (an ask)\n"
       "  finding     something you established that nobody asked for (not an ask)\n"
       "  resolved    an escalation you have since cleared yourself — <match> is\n"
       "              any text from the bullet, <how> is what you did about it.\n"
       "              Crosses it off Attention and records the how as a finding.\n"
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
        file-key (kinds kind)]
    (when-not (or file-key (= "resolved" kind))
      (fail! 1 (str "note.bb: unknown kind " (pr-str kind) "\n\n" usage-text)))
    (let [claim (one-line claim)
          why (one-line why)]
      (when (str/blank? claim) (fail! 1 (str "note.bb: the claim is empty\n\n" usage-text)))
      (when (str/blank? why) (fail! 1 (str "note.bb: the why is empty — a claim with no reason is a line nobody can act on\n\n" usage-text)))
      (when (seq (drop 3 args)) (fail! 1 (str "note.bb: too many arguments; quote the claim and the why\n\n" usage-text)))
      (let [ctx (task-lib/ctx-from-env)
            session (handoff-lib/session ctx)]
        (if (= "resolved" kind)
          (let [n (resolve-escalations! ctx claim)]
            ;; A match that hits nothing is a typo, and silently doing nothing
            ;; would leave the role believing it had cleared the item.
            (when (zero? n)
              (fail! 1 (str "note.bb: no escalation bullet contains " (pr-str claim)
                            " — nothing crossed off")))
            (spit (str (:finding-file ctx))
                  (bullet (tag ctx session) (str "resolved: " claim) why) :append true)
            (println (str "RESOLVED " n " escalation line(s); how recorded in "
                          (:finding-file ctx))))
          (let [file (get ctx file-key)]
            (spit (str file) (bullet (tag ctx session) claim why) :append true)
            (println (str "NOTED " kind ": " file))))))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (try
    (apply -main *command-line-args*)
    (catch clojure.lang.ExceptionInfo e
      (fail! (or (:exit (ex-data e)) 1) (ex-message e)))))
