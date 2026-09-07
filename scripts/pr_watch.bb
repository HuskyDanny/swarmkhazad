#!/usr/bin/env bb

;; pr_watch.bb — turn what happens on a shipped PR back into work.
;;
;; A task used to end at the handoff. Now it can wait days in review, and the
;; feedback that arrives there — a review comment, a red check — is work in
;; exactly the form the swarm already handles. So it becomes a handoff:
;; `type: pr_comment` or `type: pr_check`, addressed to the role that last
;; committed in that repo, which is the one whose session archive, verdict and
;; evidence are all about that diff.
;;
;; There is no public endpoint, so nothing can be pushed to us: this polls.
;; handoffd calls it on its own loop, and the portal has a button that forces a
;; check now.
;;
;; Two things it deliberately does NOT do.
;;
;; It never replies or resolves. The handoff body carries the exact `gh`
;; commands, thread id filled in, and the role decides — because "resolve"
;; means the role agreed, and a tool that resolves on delivery would be
;; agreeing on its behalf. A thread the role disagrees with gets a reply with
;; the reason and stays open.
;;
;; It stops waking anyone about a check that has failed twice on the same
;; commit, and writes an escalation instead. A pipeline broken for a reason
;; nobody in the task can fix would otherwise spin a role for as long as it
;; stays broken.

(ns pr-watch
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "handoff_lib.bb")))

(def sender
  "The phantom sender for machine-originated mail. handoffd files a phantom's
   sent copy under the task's own mail dir rather than a session's."
  "(pr)")

(def max-check-failures
  "How many times one check may fail on one commit before this stops waking a
   role and escalates instead."
  2)

(def usage-text
  (str "Usage: pr_watch.bb <task-id>\n"
       "\n"
       "  Polls every shipped PR once and turns new review comments and failing\n"
       "  checks into handoffs. Safe to run repeatedly: it remembers what it has\n"
       "  already turned into work.\n"))

;; --------------------------------------------------------------- the record

(defn pr-dir [ctx] (fs/path (:state-dir ctx) "pr"))

(defn shipped
  "What ship recorded, one map per repo, each carrying its own checkout.

   The checkout comes from the task's `repos`, not from the PR record: `gh`
   picks its repo from the directory it runs in, and running every query from
   one directory answered every repo's poll with the first repo's PR — a
   review comment on gobel woke cirdan's session about cirdan."
  [ctx]
  (when (fs/directory? (pr-dir ctx))
    (let [source (into {} (for [r (try (task-lib/parse-repos ctx) (catch Exception _ nil))]
                            [(:name r) (:path r)]))]
      (vec (for [f (sort (fs/glob (pr-dir ctx) "*.json"))
                 :let [m (try (json/parse-string (slurp (str f)) true) (catch Exception _ nil))]
                 :when (and m (:url m))]
             (assoc m :source (get source (:repo m))))))))

(defn seen-file [ctx repo] (fs/path (pr-dir ctx) (str repo ".seen.json")))

(defn seen [ctx repo]
  (or (try (json/parse-string (slurp (str (seen-file ctx repo))) true) (catch Exception _ nil))
      {:handled [] :checks {}}))

(defn save-seen! [ctx repo m]
  (fs/create-dirs (pr-dir ctx))
  (spit (str (seen-file ctx repo)) (str (json/generate-string m {:pretty true}) "\n")))

;; ------------------------------------------------------------------ GitHub

(defn pr-number [url]
  (some-> (re-find #"/pull/(\d+)" (str url)) second parse-long))

(defn slug [url]
  (when-let [[_ owner name] (re-find #"github\.com/([^/]+)/([^/]+)/pull/" (str url))]
    [owner name]))

(def query
  "One query for the three things that become work: the review threads with
   their ids (a reply and a resolve both need the id, and `gh pr view` does not
   carry it), the issue comments, and the checks on the head commit."
  (str "query($owner:String!,$name:String!,$number:Int!){"
       " repository(owner:$owner,name:$name){ pullRequest(number:$number){"
       "  headRefOid isDraft state"
       "  reviewThreads(first:50){nodes{id isResolved isOutdated"
       "   comments(first:1){nodes{id author{login} body path line}}}}"
       "  comments(first:50){nodes{id author{login} body}}"
       "  commits(last:1){nodes{commit{statusCheckRollup{contexts(first:50){nodes{"
       "   __typename"
       "   ... on CheckRun{name conclusion detailsUrl}"
       "   ... on StatusContext{context state targetUrl}}}}}}}"
       "}}}"))

(defn fetch
  "The PR as GitHub sees it, or {:error}. A poll that cannot reach GitHub is a
   poll that does nothing — never an empty answer, which would read as `no
   comments` and mark everything handled."
  [{:keys [url account source]}]
  (cond
    (not (and source (fs/directory? source)))
    {:error (str "no checkout for this repo; is it still in the task's `repos`? " url)}

    (not (slug url))
    {:error (str "not a GitHub PR url: " url)}

    :else
    (let [[owner name] (slug url)
          token (let [r (process/sh {:continue true} "gh" "auth" "token" "-u" (or account ""))]
                  (when (zero? (:exit r)) (str/trim (:out r))))
          r (process/sh {:continue true
                         ;; `gh` reads the repo from its working directory.
                         :dir (str source)
                         :extra-env (cond-> {} token (assoc "GH_TOKEN" token))}
                        "gh" "api" "graphql"
                        "-f" (str "query=" query)
                        "-F" (str "owner=" owner)
                        "-F" (str "name=" name)
                        "-F" (str "number=" (pr-number url)))]
      (if-not (zero? (:exit r))
        {:error (str/trim (:err r))}
        (or (try (get-in (json/parse-string (str/trim (:out r)) true)
                         [:data :repository :pullRequest])
                 (catch Exception e {:error (.getMessage e)}))
            {:error "the query returned no pull request"})))))

;; ------------------------------------------------------------- what is new

(defn threads
  "Unresolved review threads, as items. An outdated thread still counts: the
   line moved, the question did not."
  [pr]
  (for [t (get-in pr [:reviewThreads :nodes])
        :when (not (:isResolved t))
        :let [c (first (get-in t [:comments :nodes]))]
        :when c]
    {:kind "pr_comment"
     :id (str "thread:" (:id t))
     :thread (:id t)
     :author (get-in c [:author :login])
     :where (when (:path c) (str (:path c) (when (:line c) (str ":" (:line c)))))
     :body (:body c)}))

(defn issue-comments [pr]
  (for [c (get-in pr [:comments :nodes])]
    {:kind "pr_comment"
     :id (str "comment:" (:id c))
     :author (get-in c [:author :login])
     :body (:body c)}))

(defn failing-checks [pr]
  (let [head (:headRefOid pr)]
    (for [n (get-in pr [:commits :nodes 0 :commit :statusCheckRollup :contexts :nodes])
          :let [name (or (:name n) (:context n))
                bad? (or (#{"FAILURE" "TIMED_OUT" "CANCELLED" "ACTION_REQUIRED"} (:conclusion n))
                         (#{"FAILURE" "ERROR"} (:state n)))]
          :when (and name bad?)]
      {:kind "pr_check"
       :id (str "check:" name "@" head)
       :check name
       :head head
       :url (or (:detailsUrl n) (:targetUrl n))})))

;; ------------------------------------------------------------- who it wakes

(defn last-committer
  "The session that most recently handed off work in this repo — the one whose
   archive, verdict and evidence are all about the diff under review. Falls
   back to the repo's first session, so feedback always reaches somebody."
  [ctx repo]
  (let [rows (filter #(= repo (:repo %)) (task-lib/read-sessions-tsv ctx))
        stamp (fn [row]
                (->> (fs/glob (fs/path (handoff-lib/mail-dir ctx (:session row)) "sent") "*.handoff")
                     (filter #(= "git_handoff" (handoff-lib/header-field % "type")))
                     (map (comp str fs/file-name))
                     sort
                     last))]
    (or (->> rows
             (keep (fn [row] (when-let [s (stamp row)] [s (:session row)])))
             (sort-by first)
             last
             second)
        (:session (first rows)))))

;; ------------------------------------------------------------- the handoffs

(defn reply-help [{:keys [thread]} {:keys [url]}]
  (if thread
    (str "Reply on the thread:\n"
         "  gh api graphql -f query='mutation{addPullRequestReviewThreadReply("
         "input:{pullRequestReviewThreadId:\"" thread "\",body:\"…\"}){clientMutationId}}'\n"
         "Resolve it ONLY if you agree and have pushed the fix:\n"
         "  gh api graphql -f query='mutation{resolveReviewThread("
         "input:{threadId:\"" thread "\"}){thread{isResolved}}}'\n"
         "If you disagree, reply with the reason and leave it open.\n")
    (str "Reply on the PR:\n  gh pr comment " url " --body '…'\n")))

(defn body-for [item pr-row]
  (case (:kind item)
    "pr_comment"
    (str "A review comment on " (:url pr-row) " needs an answer.\n\n"
         (when (:where item) (str "At " (:where item) "\n"))
         "From @" (or (:author item) "someone") ":\n\n"
         (str/trim (str (:body item))) "\n\n"
         "Fix it in your worktree, commit, push the task branch, then reply.\n\n"
         (reply-help item pr-row))
    "pr_check"
    (str "The check `" (:check item) "` is failing on " (:url pr-row)
         " at commit " (subs (str (:head item)) 0 (min 10 (count (str (:head item))))) ".\n\n"
         (when (:url item) (str "Logs: " (:url item) "\n\n"))
         "Read the failure, fix it in your worktree, commit and push the task branch.\n"
         "It will be checked again on the next poll. If it fails twice on the same\n"
         "commit nobody is woken again and it becomes an escalation instead.\n")))

(defn message-line [item]
  (case (:kind item)
    "pr_comment" (str "PR comment" (when (:where item) (str " on " (:where item)))
                      " needs an answer")
    "pr_check" (str "check " (:check item) " is failing on the PR")))

(defn queue!
  "Write the handoff into the task's system outbox, where handoffd picks it up
   like any other mail — so claim-once, retry and the failed/ path all come for
   free rather than being reimplemented here."
  [ctx item pr-row recipient]
  (let [dir (fs/path (task-lib/system-mail-dir ctx) "outbox")
        _ (fs/create-dirs dir)
        stamp (handoff-lib/fresh-stamp dir "pr")
        file (fs/path dir (str "40_" stamp "_from_pr_to_" recipient ".handoff"))
        headers {"id" (str stamp "_from_pr")
                 "from" sender
                 "to" recipient
                 "priority" "40"
                 "type" (:kind item)
                 "task_id" (:task-id ctx)
                 "task" (:task-id ctx)
                 "created_at" (handoff-lib/timestamp)
                 "message" (message-line item)
                 "pr_url" (str (:url pr-row))
                 "origin_repo" (str (:repo pr-row))}]
    (spit (str file) (handoff-lib/render-message headers (body-for item pr-row)))
    file))

(defn escalate!
  "Through note.bb, because it is the only writer of the bullet files and it
   stamps the repo tag from the session."
  [ctx recipient repo claim why]
  (process/sh {:continue true
               :extra-env {"SWARMKHAZAD_SESSION" recipient
                           "SWARMKHAZAD_TASK_ID" (:task-id ctx)
                           "SWARMKHAZAD_TASK_DIR" (str (:task-dir ctx))}}
              "bb" (str (fs/path script-dir "note.bb")) "escalation" claim why))

;; --------------------------------------------------------------- the poll

(defn poll-repo!
  "One PR. Returns a line per thing it did, for the caller to print or log."
  [ctx pr-row]
  (let [pr (fetch pr-row)]
    (if (:error pr)
      [(str "pr " (:repo pr-row) ": " (:error pr))]
      (let [repo (:repo pr-row)
            state (seen ctx repo)
            handled (set (:handled state))
            recipient (last-committer ctx repo)
            items (concat (threads pr) (issue-comments pr) (failing-checks pr))
            ;; Our own comments are our own replies coming back round.
            ours? (fn [i] (= (str/lower-case (str (:author i)))
                             (str/lower-case (str (:account pr-row)))))
            fresh (remove #(or (handled (:id %)) (ours? %)) items)]
        (loop [[item & more] fresh
               state state
               out []]
          (if-not item
            (do (save-seen! ctx repo state) out)
            (let [checks (:checks state)
                  n (inc (int (get checks (keyword (:id item)) 0)))
                  state (cond-> state
                          (= "pr_check" (:kind item)) (assoc-in [:checks (keyword (:id item))] n))]
              (cond
                ;; A check that keeps failing on one commit is not work a role
                ;; can do again — the second attempt already failed.
                (and (= "pr_check" (:kind item)) (>= n max-check-failures))
                (do (escalate! ctx recipient repo
                               (str "check " (:check item) " has failed " n " times on the same commit")
                               (str "nobody is being woken for it any more — " (:url pr-row)))
                    (recur more (update state :handled conj (:id item))
                           (conj out (str "escalated  " repo "  " (:check item)))))

                (nil? recipient)
                (recur more state (conj out (str "no session to wake in " repo)))

                :else
                (do (queue! ctx item pr-row recipient)
                    (recur more (update state :handled conj (:id item))
                           (conj out (str "handoff    " repo "  " (:kind item) " → " recipient))))))))))))

(defn poll!
  "Every shipped PR, once. Never throws: this runs inside handoffd's loop, and
   a GitHub outage must not take the daemon down with it."
  [ctx]
  (vec (mapcat (fn [row] (try (poll-repo! ctx row)
                              (catch Exception e [(str "pr " (:repo row) ": " (.getMessage e))])))
               (shipped ctx))))

(defn -main [& args]
  (when (some #{"--help" "-h"} args) (print usage-text) (System/exit 0))
  (let [id (first (remove #(str/starts-with? % "--") args))]
    (when (str/blank? id)
      (binding [*out* *err*] (print usage-text))
      (System/exit 1))
    (let [ctx (task-lib/task-ctx id)
          lines (poll! ctx)]
      (if (seq lines)
        (doseq [l lines] (println l))
        (println "nothing new on the pull requests")))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
