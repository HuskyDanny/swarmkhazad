#!/usr/bin/env bb

;; ship.bb — push each repo's task branch and open a draft PR on it.
;;
;; This is the only thing swarmkhazad does that leaves the machine, so every
;; guard here is about that:
;;
;;   * It refuses to run without a summary. The summary is where a human reads
;;     a verdict; requiring it means nothing ships unread.
;;   * The merge order comes from that summary, never from a second inference.
;;     Two things working out an order will disagree at the worst moment.
;;   * It prints exactly what will be pushed, to which remote, and as WHICH
;;     GITHUB ACCOUNT, and waits for `yes`. `--yes` skips the prompt; nothing
;;     else does.
;;   * The branch is always `sk/<task-id>`, checked against the source's
;;     default branch before every push. Never main, never a merge.
;;   * Draft PRs only.
;;   * It stops at the first failure. A pushed branch with a draft PR is not
;;     damage needing a rollback, and continuing past a failed push would open
;;     a PR that depends on work nobody has.
;;
;; Re-running is how you continue after a failure: a branch already at the
;; remote is not pushed again, and a repo that already has a PR for this head
;; prints it instead of opening a second.

(ns ship
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "task_lib.bb")))
(load-file (str (fs/path script-dir "board_lib.bb")))
(load-file (str (fs/path script-dir "summary.bb")))

(def owner->account
  "GitHub org → the account that opens PRs there. One entry today; the map
   exists because the answer is per-owner and guessing it wrong pushes as the
   wrong person. SWARMKHAZAD_GH_ACCOUNT overrides the whole thing."
  {"MithraAI" "allen-mithra"})

(def default-account "allen-mithra")

(def usage-text
  (str "Usage: swarmkhazad ship <task-id> [--yes]\n"
       "\n"
       "  Pushes each repo's sk/<task-id> branch and opens a draft PR on it,\n"
       "  in the order the task's summary gives. Requires that summary.\n"
       "\n"
       "  --yes   skip the confirmation. Everything it would have shown is\n"
       "          still printed.\n"))

(defn fail! [message]
  (binding [*out* *err*] (println message))
  (System/exit 1))

(defn git [dir & args]
  (let [r (apply process/sh {:continue true :dir (str dir)} "git" args)]
    (when-not (zero? (:exit r))
      (fail! (str "git " (str/join " " args) " failed in " dir "\n" (str/trim (:err r)))))
    (str/trim (:out r))))

(defn git-out
  "The output, or nil when the command failed. For questions, not actions."
  [dir & args]
  (let [r (apply process/sh {:continue true :dir (str dir)} "git" args)]
    (when (zero? (:exit r)) (str/trim (:out r)))))

;; ------------------------------------------------------------------ identity

(defn origin-slug
  "owner/name from a remote URL, both SSH and HTTPS forms, or nil."
  [url]
  (when url
    (some->> (or (second (re-find #"github\.com[:/]+([^/]+/[^/]+?)(?:\.git)?/?$" url)))
             (#(str/split % #"/"))
             (#(when (= 2 (count %)) {:owner (first %) :name (second %)})))))

(defn repo-account
  "The account this checkout says it pushes as: `swarmkhazad.ghAccount` in its
   own git config, or nil.

   The answer belongs with the checkout, because that is the thing that knows.
   `gh` cannot supply it: its credential helper serves only the ACTIVE account
   — RAN, asked for a non-active user it returns nothing at all — and
   `gh auth switch` is per HOST and global, so there is no per-repo account in
   gh to read. Git has no such notion either: `user.name` is commit authorship
   and credentials are selected by host, not by repository. What git does have
   is per-repo config, so that is where this lives.

   Set it once per checkout, and a new repo needs no code change:
     git -C <checkout> config swarmkhazad.ghAccount <account>"
  [source]
  (let [r (process/sh {:continue true :dir (str source)}
                      "git" "config" "--get" "swarmkhazad.ghAccount")]
    (when (zero? (:exit r)) (not-empty (str/trim (str (:out r)))))))

(defn account-for
  "Which of the authenticated accounts pushes to this owner, most specific
   answer first: the environment override, then what the checkout itself says,
   then the owner map, then the default.

   The checkout beats the map on purpose. The map is a central list that has to
   be edited for every new owner, and the cost of it being wrong is not an
   error — `HuskyDanny` was absent, fell through to the `allen-mithra` default,
   and the push failed `Invalid username or token`, which reads as a broken
   credential rather than the wrong identity. Both accounts' tokens are already
   on disk and `gh auth token -u <account>` serves either, so nothing about the
   token side needs to change; only the choosing did."
  ([owner] (account-for owner nil))
  ([owner source]
   (or (not-empty (or (System/getenv "SWARMKHAZAD_GH_ACCOUNT") ""))
       (when source (repo-account source))
       (get owner->account owner)
       default-account)))

(def gh-token
  "The account's token, supplied per command. Never `gh auth switch` — that
   rewrites the operator's global gh state to push one branch.

   Memoised, and called only from the actions: reaching for a credential while
   building the plan means the tool touches the keychain before the operator
   has said yes, which is the one thing the confirmation exists to prevent."
  (memoize
   (fn [account]
     (let [r (process/sh {:continue true} "gh" "auth" "token" "-u" account)]
       (when (zero? (:exit r)) (str/trim (:out r)))))))

;; ------------------------------------------------------------- merge order

(defn summary-section
  "One `## <name>` section's body from the summary, or nil."
  [body name]
  (let [lines (str/split-lines (or body ""))
        after (rest (drop-while #(not (re-matches (re-pattern (str "(?i)##\\s+" name "\\s*")) (str/trim %))) lines))]
    (when (seq after)
      (str/join "\n" (take-while #(not (str/starts-with? (str/trim %) "## ")) after)))))

(defn merge-order
  "The repo names the summary's `## Merge order` section lists, first mention
   first. Parsed by scanning for the task's own repo names rather than by
   demanding a format, because the section is written by a model and a strict
   parser would turn a readable answer into a refusal.

   A single-repo task has no order to give and needs none."
  [body repo-names]
  (if (< (count repo-names) 2)
    (vec repo-names)
    (when-let [section (summary-section body summary/merge-order-heading)]
      (->> (str/split-lines section)
           (mapcat (fn [line] (filter #(re-find (re-pattern (str "(?i)\\b" (java.util.regex.Pattern/quote %) "\\b")) line)
                                      repo-names)))
           distinct
           vec))))

;; ------------------------------------------------------------------- plan

(defn repo-plan
  "What shipping one repo would do, or nil when it has nothing to ship."
  [ctx {:keys [name path]}]
  (let [branch (task-lib/task-branch ctx)
        base (task-lib/source-default-branch path)
        commits (some-> (git-out path "rev-list" "--count" (str "origin/" base ".." branch)) parse-long)
        ;; config, not `remote get-url`: get-url applies url.<x>.insteadOf,
        ;; so a checkout that rewrites github.com to a mirror answers with the
        ;; mirror path, which has no owner in it — and the account then falls
        ;; back to the default without anyone being told. The declared URL is
        ;; what says whose repo this is.
        slug (origin-slug (git-out path "config" "--get" "remote.origin.url"))]
    (when (and commits (pos? commits))
      {:repo name
       :source path
       :branch branch
       :base base
       :commits commits
       :head (git-out path "rev-parse" branch)
       :slug slug
       ;; `path` so the checkout gets a say — see account-for.
       :account (account-for (:owner slug) path)
       ;; Already at the remote with the same tip: a re-run after a failure
       ;; further down the order must not push it a second time.
       :pushed? (= (git-out path "rev-parse" branch)
                   (git-out path "rev-parse" (str "refs/remotes/origin/" branch)))})))

(defn goal-text [ctx]
  (if (fs/regular-file? (:goal-file ctx)) (slurp (str (:goal-file ctx))) ""))

(defn title [ctx]
  (or (some->> (str/split-lines (goal-text ctx))
               (some #(second (re-matches #"#\s+(.+)" (str/trim %))))
               str/trim
               not-empty)
      (:task-id ctx)))

(defn release-lines
  "release.md's bullets, claim and why intact.

   In the PR body because that is where the person who merges is standing.
   These are not review comments and not defects — they are what has to be true
   around the merge, and until now they were escalations, which put a deploy
   checklist on the Attention list of a task that was finished."
  [ctx]
  (->> (str/split-lines (if (fs/regular-file? (:release-file ctx))
                          (slurp (str (:release-file ctx)))
                          ""))
       (map str/trim)
       (remove str/blank?)
       (map #(str/replace % #"^-\s*" ""))))

(defn body-for
  "The PR body. `summary` may be nil: the first push happens at a role's
   handoff, long before anyone has asked for a verdict, and the PR has to exist
   by then — the cloud runner clones the branch and comments its findings on
   the PR, so a task with no PR has nowhere for its answers to come back to.
   `ship` sets the body again once there IS a verdict."
  [ctx plan summary]
  (let [verdict (some #(when (str/starts-with? (str/trim %) "READY") (str/trim %))
                      (str/split-lines (str (summary-section (:body summary) "Verdict"))))
        goals (->> (str/split-lines (goal-text ctx))
                   (keep task-lib/goal-line)
                   (filter #(or (empty? (:repos %)) (some #{(:repo plan)} (:repos %)))))
        release (release-lines ctx)]
    (str "Opened by swarmkhazad for task `" (:task-id ctx) "`, branch `" (:branch plan) "`.\n\n"
         (if verdict
           (str "**Summary verdict:** " verdict "\n\n")
           (str "**No verdict yet** — the swarm is still working. This is a draft so the branch has "
                "somewhere to be reviewed against, and so the cloud runner has a place to comment "
                "its findings.\n\n"))
         "## Goals this repo carries\n"
         (if (seq goals)
           (str/join "\n" (for [g goals] (str "- [" (if (:ticked g) "x" " ") "] "
                                              (when (:role g) (str (:role g) " — ")) (:text g))))
           "(none tagged for this repo)")
         (when (seq release)
           (str "\n\n## Before this merges\n"
                "Not review comments — what the roles found has to be true around the merge.\n"
                "Order matters where a line says so.\n\n"
                (str/join "\n" (for [l release] (str "- [ ] " l)))))
         "\n\nThe full verdict, the evidence and the decision log are in the task folder:\n`"
         (:task-dir ctx) "`\n\nDraft on purpose: swarmkhazad never merges.\n")))

;; ------------------------------------------------------------------ actions

(defn push!
  "Push with the account's token supplied for this one command. The refspec is
   explicit and the branch is checked against the source's default first — a
   push spec is the one place a typo reaches main."
  [{:keys [repo source branch base account]}]
  (when (or (= branch base) (not (str/starts-with? branch "sk/")))
    (fail! (str "refusing to push " branch " in " source ": ship only ever pushes sk/<task-id>")))
  (let [;; The helper reads the credential out of the environment rather than
        ;; carrying it. Spelled into the `-c` value, the token was an argv
        ;; element of `git` — readable in `ps` by anything running as this user,
        ;; which is what a swarm of unattended agents is — and git re-exports
        ;; every `-c` to its children in GIT_CONFIG_PARAMETERS, so every hook
        ;; the push ran saw it too. The variable names are ours, so git has no
        ;; reason to put them in config.
        helper "!f(){ echo username=\"$SK_GH_ACCOUNT\"; echo password=\"$SK_GH_TOKEN\"; };f"
        r (process/sh {:continue true :dir (str source)
                       :extra-env {"SK_GH_ACCOUNT" (str account)
                                   "SK_GH_TOKEN" (or (gh-token account) "")
                                   ;; Nothing here can answer a prompt, and a
                                   ;; push that blocks on one hangs the run.
                                   "GIT_TERMINAL_PROMPT" "0"}}
                      "git"
                      ;; `credential.helper` is a LIST, and `-c` appends to it
                      ;; rather than replacing it — git then takes the first
                      ;; complete answer. On a machine with osxkeychain in the
                      ;; system config (this one), that helper answers for
                      ;; github.com first and ours is never consulted: ship
                      ;; printed one account and pushed as whichever the
                      ;; keychain happened to hold. An empty value clears every
                      ;; helper before it, so this pair is the whole list.
                      ;; RAN: without the reset `git credential fill` for
                      ;; github.com answers from the keychain; with it, ours.
                      "-c" "credential.helper="
                      "-c" (str "credential.helper=" helper)
                      "push" "--set-upstream" "origin" (str branch ":refs/heads/" branch))]
    (when-not (zero? (:exit r))
      (fail! (str "push failed for " repo "\n" (str/trim (:err r)))))
    (str/trim (str (:err r) (:out r)))))

(defn existing-pr [{:keys [source branch account]}]
  (let [r (process/sh {:continue true :dir (str source)
                       :extra-env (cond-> {} (gh-token account) (assoc "GH_TOKEN" (gh-token account)))}
                      "gh" "pr" "list" "--head" branch "--state" "open" "--json" "number,url" "--limit" "1")]
    (when (zero? (:exit r))
      (first (try (json/parse-string (str/trim (:out r)) true) (catch Exception _ nil))))))

(defn open-pr! [{:keys [source branch base account] :as plan} title body]
  (let [body-file (fs/create-temp-file {:prefix "swarmkhazad-pr." :suffix ".md"})]
    (spit (str body-file) body)
    (try
      (let [r (process/sh {:continue true :dir (str source)
                           :extra-env (cond-> {} (gh-token account) (assoc "GH_TOKEN" (gh-token account)))}
                          "gh" "pr" "create" "--draft"
                          "--base" base "--head" branch
                          "--title" title "--body-file" (str body-file))]
        (when-not (zero? (:exit r))
          (fail! (str "gh pr create failed for " (:repo plan) "\n" (str/trim (:err r)))))
        (str/trim (:out r)))
      (finally (fs/delete-if-exists body-file)))))

(defn set-pr-body!
  "Rewrite an open PR's body. Best-effort: the PR exists and the branch is
   pushed either way, and failing the whole ship over a body edit would be a
   worse trade than a stale description."
  [{:keys [source account]} number body]
  (let [body-file (fs/create-temp-file {:prefix "swarmkhazad-pr." :suffix ".md"})]
    (spit (str body-file) body)
    (try
      (zero? (:exit (process/sh {:continue true :dir (str source)
                                 :extra-env (cond-> {} (gh-token account) (assoc "GH_TOKEN" (gh-token account)))}
                                "gh" "pr" "edit" (str number) "--body-file" (str body-file))))
      (finally (fs/delete-if-exists body-file)))))

(defn record! [ctx plan url]
  (let [dir (fs/path (:state-dir ctx) "pr")]
    (fs/create-dirs dir)
    (spit (str (fs/path dir (str (:repo plan) ".json")))
          (str (json/generate-string (assoc (select-keys plan [:repo :branch :base :head :account :source])
                                            :url url
                                            :at (str (java.time.Instant/now)))
                                     {:pretty true})
               "\n"))))

(defn publish!
  "One repo: branch at the remote, draft PR open on it, both recorded. Returns
   {:url :opened?}.

   The whole of what `ship` does per repo, and the whole of what a role's
   handoff needs, because they are the same thing at two moments. A branch that
   lives only in a worktree is one `close --reclaim` from gone, and a PR that
   does not exist yet is a return channel the cloud runner cannot use — so the
   first handoff opens it, and ship rewrites the body once there is a verdict."
  [ctx plan title body]
  (when-not (:pushed? plan) (push! plan))
  (if-let [pr (existing-pr plan)]
    (do (set-pr-body! plan (:number pr) body)
        (record! ctx plan (:url pr))
        {:url (:url pr) :opened? false})
    (let [url (open-pr! plan title body)]
      (record! ctx plan url)
      {:url url :opened? true})))

;; -------------------------------------------------------------------- main

(defn confirm! [plans]
  (println)
  (println "This pushes to GitHub and opens draft pull requests:")
  (println)
  (doseq [{:keys [repo slug branch base commits account pushed?]} plans]
    (println (format "  %-16s %s → %s  at %s/%s  as %s%s"
                     repo branch base
                     (or (:owner slug) "?") (or (:name slug) "?")
                     account
                     (if pushed? "  (already pushed)" (str "  (" commits " commit(s))")))))
  (println)
  (print "Type yes to go ahead: ")
  (flush)
  (when-not (= "yes" (str/trim (or (read-line) "")))
    (println "nothing was pushed")
    (System/exit 1)))

(defn -main [& args]
  (when (some #{"--help" "-h"} args) (print usage-text) (System/exit 0))
  (let [id (first (remove #(str/starts-with? % "--") args))
        yes? (boolean (some #{"--yes"} args))]
    (when (str/blank? id) (fail! usage-text))
    (let [ctx (task-lib/task-ctx id)]
      (when-not (fs/directory? (:task-dir ctx)) (fail! (str "no such task: " id)))
      (let [summary (summary/read-summary ctx)]
        (when-not summary
          (fail! (str "no summary for " id " — run `swarmkhazad summary " id "` (or the portal's "
                      "\"Ready to merge?\" button) first. Nothing ships that nobody has read a verdict on.")))
        (let [repos (task-lib/parse-repos ctx)
              names (mapv :name repos)
              order (merge-order (:body summary) names)]
          (when-not (seq order)
            (fail! (str "the summary has no `## " summary/merge-order-heading "` section naming this task's repos ("
                        (str/join ", " names) "). Re-run the summary; ship never works the order out itself.")))
          ;; Once per repo. `repo-plan` is about seven git spawns, and computing
          ;; it over `order` and again over `repos` forked twice what a three
          ;; repo ship needs for the same two answers.
          (let [all (into {} (for [r repos :let [p (repo-plan ctx r)] :when p] [(:name r) p]))
                plans (vec (keep all order))
                missing (remove (set order) (keys all))]
            (when (seq missing)
              (fail! (str "the summary's merge order does not name " (str/join ", " missing)
                          ", which has commits to ship. Re-run the summary.")))
            (when (empty? plans)
              (fail! (str "nothing to ship: no repo has commits past its default branch")))
            (if yes?
              (do (println "shipping without a prompt (--yes):")
                  (doseq [{:keys [repo branch base account commits]} plans]
                    (println (format "  %-16s %s → %s  as %s  (%d commit(s))" repo branch base account commits))))
              (confirm! plans))
            (doseq [plan plans]
              (println (format (if (:pushed? plan) "already pushed  %-16s %s" "pushing         %-16s %s")
                               (:repo plan) (:branch plan)))
              (let [{:keys [url opened?]} (publish! ctx plan (title ctx) (body-for ctx plan summary))]
                (println (format (if opened? "draft PR        %-16s %s" "PR already open %-16s %s")
                                 (:repo plan) url))))
            ;; Only once every repo is up: a half-shipped task is still the
            ;; last role's, and the lane is what says whose it is.
            (if (board-lib/card-lane ctx id)
              (board-lib/set-lane! ctx id board-lib/review-lane)
              (board-lib/create-card! ctx id board-lib/review-lane))
            (println (str (count plans) " repo(s) shipped; the card is in " board-lib/review-lane))))))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (try
    (apply -main *command-line-args*)
    (catch clojure.lang.ExceptionInfo e
      (fail! (ex-message e)))))
