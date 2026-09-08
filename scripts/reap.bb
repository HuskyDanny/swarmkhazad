#!/usr/bin/env bb

;; reap.bb — clear the traces a task leaves in your own checkouts.
;;
;; Worktrees are added from ~/repos rather than from a clone, which is what lets
;; a role push a branch and open a PR from the checkout you already work in.
;; The cost is that `git worktree add` writes into the source: a registration
;; under .git/worktrees/, and a branch `sk/<task-id>`. Deleting a task folder
;; removes neither — the registration becomes `prunable` and the branch simply
;; outlives everything that knew what it was for.
;;
;; So: prune every stale registration, and report every `sk/*` branch whose task
;; folder is gone. Branches that hold nothing are deleted; branches that hold
;; commits are listed with what would be lost and need `--force`, because an
;; orphan branch is also what an unmerged, unpushed day of work looks like.
;;
;; Where it looks: the repo roots — SWARMKHAZAD_REPO_ROOTS, else ~/repos. The
;; branches are in your checkouts, so your checkouts are what it reads. It never
;; touches a checked-out branch, a worktree that still exists, or any ref that
;; is not `sk/<task-id>`.

(ns reap
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "task_lib.bb")))
(load-file (str (fs/path script-dir "project_lib.bb")))

(def usage-text
  (str "Usage: swarmkhazad reap [--force]\n"
       "\n"
       "  Prunes stale worktree registrations and deletes orphaned sk/<task-id>\n"
       "  branches from the checkouts under the repo roots.\n"
       "\n"
       "  Without --force, only branches holding no commits are deleted; one\n"
       "  that holds work is listed and left alone.\n"))

(defn- lines [text]
  (remove str/blank? (str/split-lines (str text))))

(defn sk-branches
  "The sk/* branches in a checkout: which worktree holds each, and how far it is
   ahead of the base it started from — the commits deleting it would lose.

   `%(worktreepath)` rather than a comparison against the source's own HEAD.
   That comparison saw only the checkout reap is standing in, so a branch held
   by any OTHER linked worktree — one of yours, or one this tool left behind —
   read as deletable; `git branch -D` refused, `task-lib/git` threw on the
   non-zero exit, and the sweep died there, with some checkouts reaped and the
   rest untouched. git knows the answer and gives it in the same call.

   `--merged` gives the safe set in one more call, so `rev-list` runs only for
   the branches that actually hold work — usually none of them."
  [repo]
  (let [base (task-lib/source-default-branch repo)
        merged (set (lines (task-lib/git repo "for-each-ref" "--merged" base
                                         "--format=%(refname:short)" "refs/heads/sk/")))]
    (for [line (lines (task-lib/git repo "for-each-ref"
                                    "--format=%(refname:short)%09%(worktreepath)"
                                    "refs/heads/sk/"))
          :let [[b worktree] (str/split line #"\t" -1)]]
      {:branch b
       :task-id (subs b (count "sk/"))
       :worktree (not-empty worktree)
       :ahead (if (merged b)
                0
                (let [r (process/sh {:continue true :dir (str repo)}
                                    "git" "rev-list" "--count" (str base ".." b))]
                  (if (zero? (:exit r)) (parse-long (str/trim (:out r))) 0)))})))

(defn live-task? [task-id]
  (fs/directory? (fs/path (task-lib/tasks-dir) task-id)))

(defn reap-repo!
  "Prune, then classify every sk/* branch in one checkout."
  [repo force?]
  ;; Prune first: a task's worktree lives INSIDE its task folder, so a folder
  ;; that is gone leaves a registration that is certainly stale, and pruning it
  ;; is what makes the branch deletable.
  (task-lib/git repo "worktree" "prune")
  (vec
   (for [{:keys [branch task-id ahead worktree]} (sk-branches repo)]
     (let [live (live-task? task-id)
           reason (cond
                    live "its task folder is still there"
                    ;; Not ours to delete, and git would refuse anyway. Saying
                    ;; which worktree holds it beats an error nobody can act on.
                    worktree (str "a worktree still has it: " worktree)
                    (and (pos? ahead) (not force?)) (str "it holds " ahead " commit(s); --force to delete anyway")
                    :else nil)]
       (when-not reason
         (task-lib/git repo "branch" "-D" branch))
       {:repo (task-lib/repo-name repo) :branch branch :ahead ahead
        :deleted (nil? reason) :kept reason}))))

(defn -main [& args]
  (when (some #{"--help" "-h"} args)
    (print usage-text)
    (System/exit 0))
  (let [force? (boolean (some #{"--force"} args))
        repos (project-lib/available-repos)
        ;; A checkout that cannot be read is one line of output, not the end of
        ;; the sweep: reap exists to clean up after things that went wrong, so
        ;; it has to survive finding one.
        rows (mapcat (fn [r]
                       (try (reap-repo! r force?)
                            (catch Exception e
                              [{:repo (task-lib/repo-name r) :branch "-" :ahead 0 :deleted false
                                :kept (str "could not be read: " (.getMessage e))}])))
                     repos)
        {deleted true kept false} (group-by :deleted rows)]
    (println (str "scanned " (count repos) " checkout(s) under "
                  (str/join ", " (project-lib/default-repo-roots))))
    (doseq [{:keys [repo branch ahead]} (sort-by (juxt :repo :branch) deleted)]
      (println (format "deleted  %-20s %s%s" repo branch (if (pos? ahead) (str "  (" ahead " commit(s))") ""))))
    (doseq [{:keys [repo branch kept]} (sort-by (juxt :repo :branch) kept)]
      (println (format "kept     %-20s %-28s %s" repo branch kept)))
    (println (str (count deleted) " deleted, " (count kept) " kept"))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
