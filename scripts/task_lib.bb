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

;; scripts/vendors.tsv — the cc_alt vendor table, the single source both the
;; bash shim (a copy under <task>/state/) and the smoke's model check read:
;;   vendor  base_url  keychain_service  model_main  model_small  ctx_tokens
(def vendors-file (fs/path (fs/parent (fs/absolutize *file*)) "vendors.tsv"))
(def vendor-columns [:vendor :base-url :keychain-service :model-main :model-small :ctx-tokens])

(defn read-vendors []
  (into {} (for [line (remove str/blank? (str/split-lines (slurp (str vendors-file))))
                 :let [m (zipmap vendor-columns (concat (str/split line #"\t" -1) (repeat "")))]]
             [(:vendor m) m])))

;; Vendors a role may declare: every row of vendors.tsv plus `anthropic`, which
;; means the user's own login, direct. Validated at `prepare` so a typo fails
;; there, not at the first agent launch.
(def known-vendors (conj (set (keys (read-vendors))) "anthropic"))

;; roles.tsv column order. Read by every helper; never index a column by number
;; anywhere else. A role without a repo carries the literal `none` in :repo.
;; :extra-args is space-joined — an argument can never contain whitespace because
;; the declaration is split on whitespace — and must be re-split into argv by the
;; consumer, never spliced into a shell string.
(def roles-tsv-columns
  [:role :harness :repo :worktree-path :receive-mode :model :branch :extra-args])

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

(defn tmux-socket-path
  "One tmux server per task. Lives in /tmp because a unix socket path is capped
   at ~100 bytes and the task folder is already longer than that."
  [task-id]
  (str (fs/path "/tmp" (str "swarmkhazad-" (System/getProperty "user.name")) (str task-id ".sock"))))

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
     :evidence-dir (fs/path task-dir "evidence")
     :repos-dir (fs/path task-dir "repos")
     :worktrees-dir (fs/path task-dir "worktrees")
     :mail-dir (fs/path task-dir "mail")
     :tmp-dir (fs/path task-dir "tmp")
     :bin-dir (fs/path task-dir "bin")
     :prompts-dir (fs/path task-dir "prompts")
     :hooks-dir (fs/path task-dir "hooks")
     :state-dir state-dir
     :roles-tsv (fs/path state-dir "roles.tsv")
     :tmux-socket (tmux-socket-path task-id)
     :tmux-socket-file (fs/path state-dir "tmux-socket")
     :board-dir (fs/path state-dir "board")
     :daemon-dir (fs/path state-dir "daemon")
     :sessions-dir (fs/path state-dir "sessions")}))

(defn ctx-from-env
  "The ctx of the task this process runs inside: SWARMKHAZAD_TASK_ID is set in
   every role session and every daemon the swarm starts."
  []
  (let [id (System/getenv "SWARMKHAZAD_TASK_ID")]
    (when (str/blank? id)
      (throw (ex-info "SWARMKHAZAD_TASK_ID is not set; run this inside a swarm role" {:exit 1})))
    (task-ctx id)))

(defn list-task-ids []
  (let [dir (tasks-dir)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (map fs/file-name)
           (filter valid-task-id?)
           sort
           vec)
      [])))

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
        branch-token (some #(when (str/starts-with? % "branch=") %) trailing)
        branch (when branch-token (subs branch-token (count "branch=")))
        extra (remove #(or (receive-modes %) (str/starts-with? % "model=") (str/starts-with? % "branch=")) trailing)]
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
     :branch branch
     :extra-args (str/join " " extra)}))

(defn repo-name [repo-path]
  (str/replace (fs/file-name (fs/canonicalize (fs/path repo-path))) #"\.git$" ""))

(defn check-repos!
  "Every named repo is a git checkout, and no two distinct checkouts share a
   basename (they would share one clone dir).

   A shallow source is allowed. Measured: cloning one, adding worktrees off the
   clone and merging a bare SHA between them all work — every handoff commit
   descends from the pinned HEAD, which is inside the shallow window, so the
   merge base is always present. The one loss is that git declines to hardlink
   objects out of a shallow repository, so the clone costs disk. That is worth
   a warning, not a refusal: most working checkouts on this machine are shallow."
  [rows]
  (doseq [{:keys [repo role]} rows
          :when repo]
    (when-not (git-checkout? repo)
      (throw (ex-info (format "role %s: repo %s is not a git checkout" role repo) {})))
    (when (= "true" (sh-out "git" "-C" repo "rev-parse" "--is-shallow-repository"))
      (binding [*out* *err*]
        (println (format "note: role %s: %s is shallow; its clone copies objects instead of hardlinking them" role repo)))))
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

(defn read-roles-tsv
  "roles.tsv → vector of role maps keyed by roles-tsv-columns, in declaration
   order. A `none` repo or branch reads back as nil so `(when (:branch row) …)`
   is honest — the file writes the literal `none` for an absent value, and a
   reader that skips this sees the string and treats it as a real branch."
  [ctx]
  (let [file (:roles-tsv ctx)]
    (if (fs/regular-file? file)
      (->> (str/split-lines (slurp (str file)))
           (remove str/blank?)
           (mapv (fn [line]
                   (let [row (zipmap roles-tsv-columns (concat (str/split line #"\t" -1) (repeat "")))
                         un-none #(when-not (= "none" %) (not-empty %))]
                     (-> row (update :repo un-none) (update :branch un-none))))))
      [])))

(defn role-row [ctx role]
  (some #(when (= role (:role %)) %) (read-roles-tsv ctx)))

(defn role-names [ctx]
  (mapv :role (read-roles-tsv ctx)))

(defn extra-argv
  "roles.tsv :extra-args back to argv. Never splice the string into a shell."
  [row]
  (vec (remove str/blank? (str/split (or (:extra-args row) "") #"\s+"))))

;; ------------------------------------------------------- harness resolution
;;
;; Which binary a harness name means is a fact about this machine's PATH, not
;; about opening a swarm — `open` writes it into state/harnesses.tsv, and the
;; portal needs the same answer for its own one-shot calls. It lives here so
;; there is one rule rather than two that can drift, which is exactly how a
;; wrapper shim got pinned once already.

(def wrapper-shim-markers
  "Path fragments that mark a per-session wrapper standing in for the real CLI.

   A terminal that injects its own agent integration puts one first on PATH and
   rewrites the argv it forwards. Ours carries `--settings <path>`, which cmux's
   wrapper merges and hands back INLINE, so the exec dies with `Argument list
   too long` and the role relaunches forever — a swarm that starts and does
   nothing, with the reason only visible in the pane.

   These are deliberately narrow. The first version of this test rejected
   anything under a temp directory, which is true of cmux's shim and equally
   true of every stub binary a test puts on PATH — it sent the smoke suite at
   the live vendors. A wrong binary that is merely reported beats a right one
   that is silently skipped, so anything not listed here is resolved normally
   and the choice is printed at open."
  ["/cmux-cli-shims/" ".app/Contents/"])

(defn wrapper-shim? [path]
  (let [real (str (try (fs/real-path path) (catch Exception _ path)))]
    (boolean (some #(str/includes? real %) wrapper-shim-markers))))

(defn harness-candidates
  "Every executable of that name on PATH, in PATH order."
  [command]
  (->> (str/split (or (System/getenv "PATH") "") #":")
       (remove str/blank?)
       (map #(fs/path % command))
       (filter fs/executable?)
       (map str)
       distinct
       vec))

(defn resolve-harness
  "The real binary for a harness. An explicit SWARMKHAZAD_HARNESS_<NAME> wins;
   otherwise the first candidate on PATH that is not a wrapper shim. Returns
   {:path ... :skipped [...]} or nil."
  [harness]
  (if-let [pinned (not-empty (or (System/getenv (str "SWARMKHAZAD_HARNESS_" (str/upper-case harness))) ""))]
    {:path pinned :skipped [] :pinned true}
    (let [all (harness-candidates harness)
          [skipped [chosen]] (split-with wrapper-shim? all)]
      (when chosen {:path chosen :skipped (vec skipped)}))))

(defn session-name [role]
  (str "sk-" role))

;; ---------------------------------------------------------------- mail dirs

(def mail-subdirs ["outbox/tmp" "sent" "failed" "inbox/new" "inbox/in_process" "inbox/completed"])

(defn role-mail-dir [ctx role]
  (fs/path (:mail-dir ctx) role))

(defn system-mail-dir
  "Where phantom senders — the New Task note `open` queues — leave their mail."
  [ctx]
  (fs/path (:mail-dir ctx) "_system"))

(defn prepare-mail-dirs! [ctx roles]
  (doseq [row roles
          sub mail-subdirs]
    (fs/create-dirs (fs/path (role-mail-dir ctx (:role row)) sub)))
  (doseq [sub ["outbox/tmp" "sent" "failed"]]
    (fs/create-dirs (fs/path (system-mail-dir ctx) sub))))

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
  "The source's origin/<branch> at open time — the shared upstream state, not
   the operator's local work. Falls back to the source's own branch, then HEAD."
  [src branch]
  (cond
    (git-ok? src "rev-parse" "--verify" "--quiet" (str "refs/remotes/origin/" branch))
    (git src "rev-parse" (str "refs/remotes/origin/" branch))
    (git-ok? src "rev-parse" "--verify" "--quiet" (str "refs/heads/" branch))
    (git src "rev-parse" (str "refs/heads/" branch))
    :else (git src "rev-parse" "HEAD")))

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
  [ctx repo-path & [want-branch]]
  (let [src (str (fs/canonicalize (fs/path repo-path)))
        dest (clone-dir ctx repo-path)]
    (fs/create-dirs (:repos-dir ctx))
    (when (and want-branch (not (git-ok? src "rev-parse" "--verify" "--quiet" (str "refs/heads/" want-branch))))
      (throw (ex-info (format "%s has no branch %s; the clone can only pin a branch the checkout already has (the swarm never fetches)"
                              src want-branch) {})))
    (if (fs/exists? (fs/path dest ".git"))
      {:repo src :clone (str dest) :fresh false}
      (let [branch (or want-branch (source-default-branch src))
            sha (source-pin-sha src branch)
            upstream (source-origin-url src)
            remote-ref (str "refs/remotes/origin/" branch)]
        (sh-out "git" "clone" "--quiet" "--no-checkout" "--" src (str dest))
        ;; `git clone` transfers what the source's LOCAL branches reach. When the
        ;; source has fetched but not merged, its origin/<branch> is ahead of its
        ;; local one and that commit never arrives — pinning to it fails with
        ;; "nonexistent object". Fall back to what the clone actually holds: the
        ;; source's own branch tip, which git wrote as origin/<branch> here.
        (let [sha (if (git-ok? dest "cat-file" "-e" (str sha "^{commit}"))
                    sha
                    (git dest "rev-parse" remote-ref))]
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
          ;; Roles commit as the human's checkout would; the clone has no local
          ;; identity of its own and a role must never be asked to configure one.
          (doseq [key ["user.name" "user.email"]]
            (when (git-ok? src "config" "--get" key)
              (git dest "config" key (git src "config" "--get" key))))
          {:repo src :clone (str dest) :fresh true :branch branch :sha sha :upstream upstream})))))

(defn clone-repos!
  "One clone per distinct repo named in roles. Roles sharing a repo share its
   clone, so they must agree on the branch it is pinned to."
  [ctx roles]
  (let [with-repo (filter :repo roles)
        ;; Group by the canonical path — ~/x and /abs/x are one clone, so they
        ;; must be one group or a real disagreement hides between the spellings.
        ;; And keep the nils: a role that names no branch is asking for the
        ;; default, which disagrees with a sibling's branch= just as loudly as
        ;; a second branch name would.
        branches (->> with-repo (group-by #(str (fs/canonicalize (fs/path (:repo %)))))
                      (map (fn [[repo rows]] [repo (distinct (map :branch rows))])))]
    (doseq [[repo bs] branches
            :when (> (count bs) 1)]
      (throw (ex-info (format "roles disagree on the branch for %s: %s — one clone cannot be two branches"
                              repo (str/join ", " (sort (map #(or % "<the repo's default>") bs)))) {})))
    (->> branches
         (mapv (fn [[repo bs]] (clone-repo! ctx repo (first bs)))))))

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
  (doseq [k [:repos-dir :worktrees-dir :mail-dir :tmp-dir :state-dir :prompts-dir
             :evidence-dir :board-dir :daemon-dir :sessions-dir]]
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
