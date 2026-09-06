#!/usr/bin/env bb

;; task-lib — the one place that knows the shape of ~/.swarmkhazad/tasks/<task-id>/.
;; The layout itself is documented once, in README.md; every other script asks
;; this namespace for a path instead of spelling one out.
;;
;; This file is the filesystem contract only: paths, the `roles` declaration,
;; the clone of the target repo, the per-role worktrees, and roles.tsv. It never
;; touches tmux or an agent CLI.
;;
;; roles.tsv is the spawn-time snapshot of the `roles` declaration. Helpers read
;; the snapshot, not the declaration, so editing `roles` under a running swarm
;; changes nothing until the next `prepare` rewrites it.

(ns task-lib
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def known-agents #{"claude" "codex" "copilot" "grok"})
(def receive-modes #{"task" "batch"})
;; Vendors the harness shim knows how to configure. The shim (bin/claude) is the
;; layer that must be able to build each of these; the declaration is validated
;; here so a typo fails at `prepare`, not at the first agent launch.
(def known-vendors #{"anthropic" "glm" "kimi" "deepseek" "qwen"})

;; roles.tsv column order. Read by every helper; never index a column by number
;; anywhere else. A role without a repo carries the literal `none` in :repo.
;; :extra-args is space-joined — an argument can never contain whitespace because
;; the declaration is split on whitespace — and must be re-split into argv by the
;; consumer, never spliced into a shell string.
(def roles-tsv-columns
  [:role :harness :repo :worktree-path :receive-mode :model :extra-args])

(defn fail! [message]
  (binding [*out* *err*]
    (println message))
  (System/exit 1))

(defn sh-out [& args]
  (let [result (apply process/sh args)]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "command failed: " (str/join " " args) "\n" (:err result))
                      {:args args :exit (:exit result)})))
    (str/trim (:out result))))

(defn sh-ok? [& args]
  (zero? (:exit (apply process/sh (concat [{:continue true}] args)))))

(defn home []
  (fs/expand-home (or (not-empty (System/getenv "SWARMKHAZAD_HOME")) "~/.swarmkhazad")))

(defn tasks-dir []
  (fs/path (home) "tasks"))

(defn valid-task-id? [id]
  (boolean (and (string? id) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]{0,99}" id))))

;; A role is a path component (worktrees/<role>, mail/<role>) and a refname
;; segment (sk/<task-id>/<role>) and a handoff-filename field, so: alphanumeric
;; start, then letters/digits/dot/dash. No underscore (handoff filenames use it
;; as the field separator), no slash, no leading dot or dash.
(defn valid-role? [role]
  (boolean (and (string? role) (re-matches #"[A-Za-z0-9][A-Za-z0-9.-]{0,63}" role))))

(defn task-ctx
  "The path map for one task. Pure: builds paths, touches nothing."
  [task-id]
  (when-not (valid-task-id? task-id)
    (throw (ex-info (str "Invalid task id: " (pr-str task-id)) {:task-id task-id})))
  (let [task-dir (fs/path (tasks-dir) task-id)
        state-dir (fs/path task-dir "state")]
    {:task-id task-id
     :task-dir task-dir
     :goal-file (fs/path task-dir "goal.md")
     :metrics-file (fs/path task-dir "metrics.md")
     :roles-file (fs/path task-dir "roles")
     :decision-file (fs/path task-dir "decision.md")
     :gotcha-file (fs/path task-dir "gotcha.md")
     :escalation-file (fs/path task-dir "escalation.md")
     :repos-dir (fs/path task-dir "repos")
     :worktrees-dir (fs/path task-dir "worktrees")
     :mail-dir (fs/path task-dir "mail")
     :tmp-dir (fs/path task-dir "tmp")
     :state-dir state-dir
     :roles-tsv (fs/path state-dir "roles.tsv")}))

;; ---------------------------------------------------------------- roles file
;;
;; Grammar, one role per line, `#` comments and blank lines skipped:
;;
;;   <role> <harness> <repo-path|none> [task|batch] [model=<vendor>] [cli args...]
;;
;; The two optional tokens are recognised anywhere after the repo, in any order;
;; whatever is left is passed to the harness CLI verbatim.

(def roles-grammar-comment
  "# <role> <harness> <repo-path|none> [task|batch] [model=anthropic|glm|kimi|deepseek|qwen] [cli args...]\n")

(defn roles-template
  "A starter `roles` file: one implement role per repo, or one repo-less role."
  [repos]
  (str roles-grammar-comment
       (if (seq repos)
         (str/join "" (map #(str "implement claude " % " task\n") repos))
         "implement claude none task\n")))

(defn skip-line? [line]
  (or (str/blank? line) (str/starts-with? line "#")))

(defn git-checkout?
  "True when path is a working tree git recognises — a normal checkout or a
   linked worktree, whose .git is a file rather than a directory."
  [path]
  (and (fs/directory? path)
       (sh-ok? "git" "-C" (str path) "rev-parse" "--git-dir")))

(defn parse-role-line
  "One `roles` line → a role map, or throws with the line number."
  [line-no line]
  (let [fields (str/split (str/trim line) #"\s+")
        _ (when (< (count fields) 3)
            (throw (ex-info (format "roles line %d: need <role> <harness> <repo>; got %s" line-no (pr-str line)) {})))
        [role harness repo & trailing] fields
        harness (str/lower-case harness)
        receive-mode (or (some receive-modes trailing) "task")
        model-token (some #(when (str/starts-with? % "model=") %) trailing)
        model (if model-token (subs model-token (count "model=")) "anthropic")
        extra (remove #(or (receive-modes %) (str/starts-with? % "model=")) trailing)]
    (when-not (valid-role? role)
      (throw (ex-info (format "roles line %d: role %s must match [A-Za-z0-9][A-Za-z0-9.-]* (a path component and a refname segment; no underscore, slash, or leading dot/dash)" line-no (pr-str role)) {})))
    (when-not (known-agents harness)
      (throw (ex-info (format "roles line %d: unknown harness %s (want %s)" line-no (pr-str harness) (str/join "|" (sort known-agents))) {})))
    (when-not (known-vendors model)
      (throw (ex-info (format "roles line %d: unknown model vendor %s (want %s)" line-no (pr-str model) (str/join "|" (sort known-vendors))) {})))
    {:role role
     :harness harness
     :repo (when-not (= "none" repo) (str (fs/expand-home repo)))
     :receive-mode receive-mode
     :model model
     :extra-args (str/join " " extra)}))

(defn repo-name [repo-path]
  (str/replace (fs/file-name (fs/canonicalize (fs/path repo-path))) #"\.git$" ""))

(defn check-repos!
  "Every named repo is a git checkout, not shallow, and no two distinct
   checkouts share a basename (they would share one clone dir)."
  [rows]
  (doseq [{:keys [repo role]} rows
          :when repo]
    (when-not (git-checkout? repo)
      (throw (ex-info (format "role %s: repo %s is not a git checkout" role repo) {})))
    (when (= "true" (sh-out "git" "-C" repo "rev-parse" "--is-shallow-repository"))
      (throw (ex-info (format "role %s: repo %s is a shallow clone; the swarm needs full history to merge by SHA" role repo) {}))))
  (let [by-name (->> rows
                     (keep :repo)
                     (map #(str (fs/canonicalize (fs/path %))))
                     distinct
                     (group-by repo-name))]
    (doseq [[name paths] by-name
            :when (> (count paths) 1)]
      (throw (ex-info (format "repos %s share the basename %s and would share one clone; rename one checkout" (str/join " and " paths) (pr-str name)) {})))))

(defn parse-roles
  "Parse the task's `roles` declaration. Rejects duplicates and bad repos."
  [ctx]
  (when-not (fs/regular-file? (:roles-file ctx))
    (throw (ex-info (str "No roles declaration at " (:roles-file ctx)) {})))
  (let [rows (->> (str/split-lines (slurp (str (:roles-file ctx))))
                  (map-indexed (fn [i line] [(inc i) line]))
                  (remove (fn [[_ line]] (skip-line? line)))
                  (mapv (fn [[n line]] (parse-role-line n line))))]
    (when (empty? rows)
      (throw (ex-info (str "roles declaration is empty: " (:roles-file ctx)) {})))
    (let [dupes (->> rows (map :role) frequencies (filter (fn [[_ n]] (> n 1))) (map first))]
      (when (seq dupes)
        (throw (ex-info (str "duplicate roles: " (str/join ", " dupes)) {}))))
    (check-repos! rows)
    (mapv (fn [row]
            (assoc row :worktree-path (if (:repo row)
                                        (str (fs/path (:worktrees-dir ctx) (:role row)))
                                        (str (:task-dir ctx)))))
          rows)))

;; ---------------------------------------------------------------- roles.tsv

(defn write-roles-tsv! [ctx roles]
  (fs/create-dirs (:state-dir ctx))
  (spit (str (:roles-tsv ctx))
        (apply str
               (for [row roles]
                 (str (str/join "\t" (map #(str (or (get row %) "none")) roles-tsv-columns)) "\n")))))

;; ---------------------------------------------------------------- mail dirs

(def mail-subdirs ["outbox/tmp" "sent" "failed" "inbox/new" "inbox/in_process" "inbox/completed"])

(defn role-mail-dir [ctx role]
  (fs/path (:mail-dir ctx) role))

(defn prepare-mail-dirs! [ctx roles]
  (doseq [row roles
          sub mail-subdirs]
    (fs/create-dirs (fs/path (role-mail-dir ctx (:role row)) sub))))

;; ---------------------------------------------------------------- clone

(defn git [dir & args]
  (apply sh-out "git" "-C" (str dir) args))

(defn git-ok? [dir & args]
  (apply sh-ok? "git" "-C" (str dir) args))

(defn source-default-branch
  "The branch the source tracks upstream: origin/HEAD's target, else main if
   origin/main exists, else the source's own branch, else main."
  [src]
  (or (when (git-ok? src "symbolic-ref" "--quiet" "refs/remotes/origin/HEAD")
        (str/replace (git src "symbolic-ref" "refs/remotes/origin/HEAD") #"^refs/remotes/origin/" ""))
      (when (git-ok? src "rev-parse" "--verify" "--quiet" "refs/remotes/origin/main") "main")
      (let [b (git src "rev-parse" "--abbrev-ref" "HEAD")] (when-not (= b "HEAD") b))
      "main"))

(defn source-pin-sha
  "The source's origin/<branch> at open time; falls back to its HEAD when the
   source has no such remote ref."
  [src branch]
  (if (git-ok? src "rev-parse" "--verify" "--quiet" (str "refs/remotes/origin/" branch))
    (git src "rev-parse" (str "refs/remotes/origin/" branch))
    (git src "rev-parse" "HEAD")))

(defn source-origin-url [src]
  (when (git-ok? src "remote" "get-url" "origin")
    (git src "remote" "get-url" "origin")))

(defn clone-dir [ctx repo-path]
  (fs/path (:repos-dir ctx) (repo-name repo-path)))

(defn delete-refs!
  "Delete refs in one `git update-ref --stdin` call instead of one spawn per ref."
  [dir refs]
  (when (seq refs)
    (let [result (process/sh {:in (apply str (map #(str "delete " % "\n") refs))}
                             "git" "-C" (str dir) "update-ref" "--stdin")]
      (when-not (zero? (:exit result))
        (throw (ex-info (str "update-ref --stdin failed\n" (:err result)) {}))))))

(defn clone-repo!
  "Clone the local checkout at repo-path into repos/<name>.

   `git clone` of a local path hardlinks the object store, so this costs one
   directory walk rather than a copy. Afterwards the clone owes the source
   nothing: <branch> is pinned to the source's origin/<branch>, `origin` is
   repointed at the source's upstream URL (or removed when the source has none,
   so no later fetch can reach back into ~/repos), and every other ref and
   local branch is dropped. Nothing under the source is written; nothing is
   read from it again."
  [ctx repo-path]
  (let [src (str (fs/canonicalize (fs/path repo-path)))
        dest (clone-dir ctx repo-path)]
    (fs/create-dirs (:repos-dir ctx))
    (if (fs/exists? (fs/path dest ".git"))
      {:repo src :clone (str dest) :fresh false}
      (let [branch (source-default-branch src)
            sha (source-pin-sha src branch)
            upstream (source-origin-url src)
            remote-ref (str "refs/remotes/origin/" branch)]
        (sh-out "git" "clone" "--quiet" "--no-checkout" "--" src (str dest))
        (git dest "update-ref" remote-ref sha)
        (git dest "symbolic-ref" "refs/remotes/origin/HEAD" remote-ref)
        (git dest "checkout" "--quiet" "-B" branch sha)
        (delete-refs! dest (->> (str/split-lines (git dest "for-each-ref" "--format=%(refname)" "refs/remotes/origin/" "refs/heads/"))
                                (remove str/blank?)
                                (remove #{remote-ref "refs/remotes/origin/HEAD" (str "refs/heads/" branch)})))
        (if upstream
          (do (git dest "remote" "set-url" "origin" upstream)
              (git dest "branch" "--quiet" (str "--set-upstream-to=origin/" branch) branch))
          (git dest "remote" "remove" "origin"))
        {:repo src :clone (str dest) :fresh true :branch branch :sha sha :upstream upstream}))))

(defn clone-repos!
  "One clone per distinct repo named in roles."
  [ctx roles]
  (->> roles
       (keep :repo)
       distinct
       (mapv #(clone-repo! ctx %))))

;; ---------------------------------------------------------------- worktrees

(defn role-branch [ctx role]
  (str "sk/" (:task-id ctx) "/" role))

(defn clone-branch
  "The pinned branch of a clone: whatever its checked-out branch is."
  [clone]
  (git clone "rev-parse" "--abbrev-ref" "HEAD"))

(defn prepare-worktrees!
  "One worktree per role that names a repo, off that repo's clone, branch sk/<task-id>/<role>."
  [ctx roles]
  (fs/create-dirs (:worktrees-dir ctx))
  (doseq [{:keys [role repo worktree-path]} roles
          :when repo]
    (let [clone (clone-dir ctx repo)]
      (when-not (fs/exists? (fs/path worktree-path ".git"))
        (git clone "worktree" "add" "--quiet" "-B" (role-branch ctx role) worktree-path (clone-branch clone))))))

;; ---------------------------------------------------------------- prepare

(defn require-truth! [ctx]
  (doseq [f [(:goal-file ctx) (:metrics-file ctx) (:roles-file ctx)]]
    (when-not (fs/regular-file? f)
      (throw (ex-info (str "task is missing " (fs/file-name f) ": " f) {})))))

(defn create-layout! [ctx]
  (doseq [k [:repos-dir :worktrees-dir :mail-dir :tmp-dir :state-dir]]
    (fs/create-dirs (get ctx k)))
  (doseq [k [:decision-file :gotcha-file :escalation-file]]
    (when-not (fs/exists? (get ctx k))
      (spit (str (get ctx k)) ""))))

(defn prepare!
  "Build everything under the task folder that a swarm needs before any agent runs:
   layout, roles.tsv, clones, worktrees, mail dirs. Idempotent."
  [ctx]
  (require-truth! ctx)
  (let [roles (parse-roles ctx)]
    (create-layout! ctx)
    (let [clones (clone-repos! ctx roles)]
      (prepare-worktrees! ctx roles)
      (prepare-mail-dirs! ctx roles)
      (write-roles-tsv! ctx roles)
      {:task-id (:task-id ctx)
       :task-dir (str (:task-dir ctx))
       :roles roles
       :clones clones})))
