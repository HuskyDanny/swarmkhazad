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

(def cli-agents
  "Harnesses that are a CLI on PATH."
  #{"claude" "codex" "copilot" "grok"})

(def lane-agents
  "Harnesses that are one of the operator's own launcher scripts.

   A lane is claude plus a fixed set of flags and an environment — the SSO wrap,
   an effort level, its own MCP set, and a model router keyed on the model name.
   Naming one here means a role inherits all of that; the swarm appends its own
   three flags afterwards and, because the parser takes the last occurrence,
   keeps the role's prompt, its truth-lock settings and its permission mode.

   `cc_auto` resolves to `<claude-config-dir>/scripts/cc-auto.sh`. By convention
   rather than a table: the path is derivable, and a table of one operator's
   absolute paths in a shared repo is stale on any other machine."
  #{"cc_full" "cc_auto" "cc_control" "cc_alt"})

(def known-agents (into cli-agents lane-agents))

(defn cc-home
  "The operator's Claude Code config directory — where the lanes live."
  []
  (or (not-empty (or (System/getenv "CLAUDE_CONFIG_DIR") ""))
      (str (fs/path (System/getProperty "user.home") ".claude"))))

(defn lane-script
  "The launcher a lane harness names, or nil if it is not a lane."
  [harness]
  (when (lane-agents harness)
    (fs/path (cc-home) "scripts" (str (str/replace harness "_" "-") ".sh"))))

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

(defn split-model
  "`<vendor>[:<model-id>]` -> [vendor model-id-or-nil].

   Split on the FIRST colon only: a model id carries colons of its own
   (`moonshotai/kimi-k3:exacto`), and splitting on the last one would hand the
   vendor half `kimi:moonshotai/kimi-k3` and lose the endpoint."
  [s]
  (let [i (str/index-of (or s "") ":")]
    (if i
      [(subs s 0 i) (not-empty (subs s (inc i)))]
      [(or s "") nil])))

(def sessions-tsv-columns
  "state/sessions.tsv: the (role, repo) pairs this task runs, and where each
   one's working directory is. Written once at prepare, read everywhere else —
   the path used to be re-derived in five places, which is how :worktree-path
   drifted from the truth.

   Denormalized: each row also carries its role's harness and model, so the
   shim and every helper read one table. The shim reads the session from column
   1 and the vendor from column 7 (pinned by shim_test)."
  [:session :role :repo :worktree-path :harness :receive-mode :model :extra-args])

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
  (boolean (and (string? role) (re-matches #"[A-Za-z0-9][A-Za-z0-9-]{0,63}" role))))

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
     ;; What the swarm worked out, as opposed to what it is waiting on. Kept
     ;; apart from escalation.md because the portal counts asks, and a finding
     ;; counted as an ask reads as a problem nobody is solving.
     :finding-file (fs/path task-dir "finding.md")
     :evidence-dir (fs/path task-dir "evidence")
     :repos-file (fs/path task-dir "repos")
     :worktrees-dir (fs/path task-dir "worktrees")
     :mail-dir (fs/path task-dir "mail")
     :tmp-dir (fs/path task-dir "tmp")
     :bin-dir (fs/path task-dir "bin")
     :prompts-dir (fs/path task-dir "prompts")
     :hooks-dir (fs/path task-dir "hooks")
     :state-dir state-dir
     :roles-tsv (fs/path state-dir "roles.tsv")
     :sessions-tsv (fs/path state-dir "sessions.tsv")
     ;; repo -> the commit its work is counted against, pinned at prepare.
     ;; Every ref that could be derived instead is SHARED with the operator's
     ;; own checkout and moves under a running task.
     :base-tsv (fs/path state-dir "base.tsv")
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
;;   <role> <harness> <repo-path|none> [task|batch] [model=<vendor>[:<model-id>]] [cli args...]
;;
;; The two optional tokens are recognised anywhere after the repo, in any order;
;; whatever is left is passed to the harness CLI verbatim.

(def roles-grammar-comment
  "# <role> <harness> [task|batch] [model=anthropic|glm|kimi|deepseek|qwen[:<model-id>]] [cli args...]\n")

(def repos-grammar-comment
  "# one checkout per line; the task branches sk/<task-id> off origin/<default>\n# <abs-path> [branch=<name>]\n")

(defn roles-template
  "A starter `roles` file. Repos are the task's, not a role's — see repos-text."
  [_repos]
  (str roles-grammar-comment "implement claude task\n"))

(defn repos-text [repos]
  (str repos-grammar-comment (str/join "" (map #(str % "\n") repos))))

(defn skip-line? [line]
  (or (str/blank? line) (str/starts-with? line "#")))

(defn git-checkout?
  "True when path is a working tree git recognises — a normal checkout or a
   linked worktree, whose .git is a file rather than a directory."
  [path]
  (and (fs/directory? path)
       (sh-ok? "git" "-C" (str path) "rev-parse" "--git-dir")))

(defn git [dir & args]
  (apply sh-out "git" "-C" (str dir) args))

(defn git-ok? [dir & args]
  (apply sh-ok? "git" "-C" (str dir) args))

(defn resolvable
  "The commit a ref names, but only when this checkout actually holds it."
  [src rev]
  (when (git-ok? src "rev-parse" "--verify" "--quiet" (str rev "^{commit}"))
    (git src "rev-parse" (str rev "^{commit}"))))

(defn has-branch?
  "True when the source knows this branch, locally or on origin."
  [src branch]
  (boolean (or (resolvable src (str "refs/remotes/origin/" branch))
               (resolvable src (str "refs/heads/" branch)))))

(defn parse-role-line
  "One `roles` line → a role map, or throws with the line number."
  [line-no line]
  (let [fields (str/split (str/trim line) #"\s+")
        _ (when (< (count fields) 2)
            (throw (ex-info (format "roles line %d: need <role> <harness>; got %s" line-no (pr-str line)) {})))
        [role harness & trailing] fields
        harness (str/lower-case harness)
        receive-mode (or (some receive-modes trailing) "task")
        model-token (some #(when (str/starts-with? % "model=") %) trailing)
        model (if model-token (subs model-token (count "model=")) "anthropic")
        extra (remove #(or (receive-modes %) (str/starts-with? % "model=")) trailing)]
    (when-not (valid-role? role)
      (throw (ex-info (format "roles line %d: role %s must match [A-Za-z0-9][A-Za-z0-9.-]* (a path component and a refname segment; no underscore, slash, or leading dot/dash)" line-no (pr-str role)) {})))
    (when-not (known-agents harness)
      (throw (ex-info (format "roles line %d: unknown harness %s (want %s)" line-no (pr-str harness) (str/join "|" (sort known-agents))) {})))
    ;; Only the vendor half is a closed set — it selects a base URL and a
    ;; keychain service, both of which have to exist. The model id after the
    ;; colon is the vendor's own namespace and is not ours to enumerate.
    (let [[vendor model-id] (split-model model)]
      (when-not (known-vendors vendor)
        (throw (ex-info (format "roles line %d: unknown model vendor %s (want %s, optionally %s:<model-id>)"
                                line-no (pr-str vendor) (str/join "|" (sort known-vendors)) "<vendor>") {})))
      (when (and model-id (str/blank? (str/trim model-id)))
        (throw (ex-info (format "roles line %d: model=%s: names a vendor and an empty model id" line-no vendor) {}))))
    (when (some #(str/starts-with? % "/") trailing)
      (throw (ex-info (format "roles line %d: a role no longer names a repo — put checkouts in the task's `repos` file. Got %s"
                              line-no (pr-str line)) {})))
    {:role role
     :harness harness
     :receive-mode receive-mode
     :model model
     :extra-args (str/join " " extra)}))

(defn repo-name [repo-path]
  (str/replace (fs/file-name (fs/canonicalize (fs/path repo-path))) #"\.git$" ""))

(defn parse-repo-line
  "One `repos` line → {:path :branch}, or throws with the line number."
  [line-no line]
  (let [[path & trailing] (str/split (str/trim line) #"\s+")
        branch-token (some #(when (str/starts-with? % "branch=") %) trailing)
        unknown (remove #(str/starts-with? % "branch=") trailing)]
    (when (seq unknown)
      (throw (ex-info (format "repos line %d: unknown token %s (want branch=<name>)"
                              line-no (pr-str (first unknown))) {})))
    {:path (str (fs/expand-home path))
     :branch (when branch-token (not-empty (subs branch-token (count "branch="))))}))

(defn check-repos!
  "Every repo is a git checkout, and no two share a basename. The basename is
   the worktree's directory name and the `@tag` on a goal line, so a collision
   would make both ambiguous."
  [repos]
  (doseq [{:keys [path branch]} repos]
    (when-not (git-checkout? path)
      (throw (ex-info (format "repos: %s is not a git checkout" path) {})))
    ;; A named branch that does not exist would silently start the task from
    ;; HEAD — the operator asked for one base and would get another.
    (when (and branch (not (has-branch? path branch)))
      (throw (ex-info (format "repos: %s has no branch %s" path branch) {}))))
  (doseq [[name paths] (->> repos
                            (map #(str (fs/canonicalize (fs/path (:path %)))))
                            distinct
                            (group-by repo-name))
          :when (> (count paths) 1)]
    (throw (ex-info (format "repos %s share the basename %s; it names a worktree and tags goal lines, so rename one checkout"
                            (str/join " and " paths) (pr-str name)) {}))))

(defn parse-repos
  "The task's `repos` file → [{:name :path :branch}].

   Repos belong to the task, not to a role. Every role can work in all of them;
   which ones a role actually touches is a property of its goal lines."
  [ctx]
  (when-not (fs/regular-file? (:repos-file ctx))
    (throw (ex-info (str "No repos declaration at " (:repos-file ctx)) {})))
  (let [rows (->> (str/split-lines (slurp (str (:repos-file ctx))))
                  (map-indexed (fn [i line] [(inc i) line]))
                  (remove (fn [[_ line]] (skip-line? line)))
                  (mapv (fn [[n line]] (parse-repo-line n line))))]
    (when (empty? rows)
      (throw (ex-info (str "repos declaration is empty: " (:repos-file ctx)) {})))
    (check-repos! rows)
    (mapv #(assoc % :name (repo-name (:path %))) rows)))

(defn parse-roles
  "Parse the task's `roles` declaration. Rejects duplicates."
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
    rows))

;; -------------------------------------------------------------- goal lines
;;
;; goal.md's checkboxes carry the two facts that decide the session table:
;; which role owns a line, and which repos it touches.

(defn goal-line
  "One goal.md checkbox → {:ticked :role :repos :text}, or nil for other lines.

   `- [ ] implement @gobel @cirdan — wire the exporter`. The role is the first
   bare word before the em dash, `@tags` name repos, and both are optional: an
   untagged line belongs to every role and every repo, which is what a
   single-repo task writes and what the judge already assumed."
  [line]
  (when-let [[_ box head body]
             (or (re-matches #"\s*- \[([ xX])\]\s*(.*?)\s*—\s*(.*)" line)
                 (when-let [[_ box body] (re-matches #"\s*- \[([ xX])\]\s*(.*)" line)]
                   [nil box "" body]))]
    (let [tokens (remove str/blank? (str/split head #"\s+"))
          tag? #(str/starts-with? % "@")]
      {:ticked (not= " " box)
       :role (first (remove tag? tokens))
       :repos (mapv #(subs % 1) (filter tag? tokens))
       :text (str/trim body)})))

(defn goal-repos
  "role → the repo names its goal lines tag, defaulting to every repo.

   A role whose lines carry no tag works everywhere: that is both the one-repo
   task and the honest reading of a line that never said. An unknown tag is a
   typo, and a typo that silently widened a role to every repo would be found
   only by watching it open the wrong worktree."
  [goals-md roles repo-names]
  (let [known (set repo-names)
        lines (keep goal-line (str/split-lines (or goals-md "")))]
    (doseq [l lines
            tag (:repos l)
            :when (not (known tag))]
      (throw (ex-info (format "goal line tags @%s, which is not one of this task's repos (%s): %s"
                              tag (str/join ", " (sort known)) (pr-str (:text l))) {})))
    (into {} (for [{:keys [role]} roles
                   :let [mine (filter #(or (nil? (:role %)) (= role (:role %))) lines)
                         tagged (distinct (mapcat :repos mine))]]
               [role (if (seq tagged) (vec tagged) (vec repo-names))]))))

(defn session-id
  "The name of a (role, repo) pair. It is a tmux session, a mail directory and
   a verdict filename, so there is one spelling of it.

   The separator is `_` because a role name cannot contain one and tmux cannot
   take a `.`: RAN — `new-session -s sk-implement.gobel` succeeds and every
   later `-t sk-implement.gobel` fails `can't find pane: gobel`, since tmux
   reads a dot as `session.pane`."
  [role repo-name]
  (str role "_" repo-name))

;; ------------------------------------------------------------- sessions.tsv

(defn sessions
  "The (role, repo) pairs this task runs, in lineup order.

   A one-repo task names its sessions after the roles: `to: review` rather than
   `to: review_gobel`, which is the same task swarmkhazad always ran and the
   same ids its tasks already on disk carry. Past one repo the id has to say
   which, so it does. Nothing derives that rule a second time — the id is a
   column in sessions.tsv, and every reader looks it up there.

   Denormalized on purpose: a session carries its role's harness and model, so
   everything downstream reads one table. Roles sharing a repo share its
   worktree — they are serialized, and a second checkout of the same branch
   would be two views of one branch racing each other."
  [ctx roles repos role->repos]
  (let [path-of (into {} (map (juxt :name #(str (fs/path (:worktrees-dir ctx) (:name %)))) repos))
        one-repo? (= 1 (count repos))]
    (vec (for [{:keys [role] :as row} roles
               name (get role->repos role)]
           (assoc row
                  :session (if one-repo? role (session-id role name))
                  :repo name
                  :worktree-path (path-of name))))))

(defn write-sessions-tsv! [ctx rows]
  (fs/create-dirs (:state-dir ctx))
  (spit (str (:sessions-tsv ctx))
        (apply str
               (for [row rows]
                 (str (str/join "\t" (map #(str (or (get row %) "")) sessions-tsv-columns)) "\n")))))

(defn read-sessions-tsv
  "sessions.tsv → session maps in lineup order.

   Falls back to a pre-multi-repo roles.tsv, whose row is one session in one
   repo. Tasks opened before this table existed are still running."
  [ctx]
  (let [file (:sessions-tsv ctx)
        legacy (:roles-tsv ctx)]
    (cond
      (fs/regular-file? file)
      (->> (str/split-lines (slurp (str file)))
           (remove str/blank?)
           (mapv (fn [line]
                   (let [row (zipmap sessions-tsv-columns (concat (str/split line #"\t" -1) (repeat "")))]
                     (into {} (for [[k v] row] [k (not-empty v)]))))))

      (fs/regular-file? legacy)
      (->> (str/split-lines (slurp (str legacy)))
           (remove str/blank?)
           (mapv (fn [line]
                   (let [[role harness repo worktree receive model _branch extra]
                         (concat (str/split line #"\t" -1) (repeat ""))
                         un-none #(when-not (= "none" %) (not-empty %))
                         repo (un-none repo)]
                     ;; The session id is the ROLE, always. A task written
                     ;; under the old shape already has mail/<role>/ and a
                     ;; pane called sk-<role>; renaming it here would orphan
                     ;; both, which is the opposite of what the fallback is
                     ;; for. The old shape was one repo per role anyway.
                     {:session role
                      :role role
                      :repo (when repo (repo-name repo))
                      :worktree-path (not-empty worktree)
                      :harness harness
                      :receive-mode receive
                      :model model
                      :extra-args (not-empty extra)}))))

      :else [])))

(defn session-row [ctx session]
  (some #(when (= session (:session %)) %) (read-sessions-tsv ctx)))

(defn session-names [ctx]
  (mapv :session (read-sessions-tsv ctx)))

(defn role-names
  "The lineup, deduplicated — a role appears once per repo in sessions.tsv."
  [ctx]
  (->> (read-sessions-tsv ctx) (map :role) distinct vec))

(defn role-sessions [ctx role]
  (filterv #(= role (:role %)) (read-sessions-tsv ctx)))

(defn extra-argv
  "A session's :extra-args back to argv. Never splice the string into a shell."
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
    ;; A lane is a script at a known path, not a name on PATH — the operator
    ;; reaches it through a shell alias, and an alias is not a file a child
    ;; process can exec.
    (if-let [script (lane-script harness)]
      (when (fs/executable? script) {:path (str script) :skipped []})
      (let [all (harness-candidates harness)
            [skipped [chosen]] (split-with wrapper-shim? all)]
        (when chosen {:path chosen :skipped (vec skipped)})))))

(defn session-name [session]
  (str "sk-" session))

;; ---------------------------------------------------------------- mail dirs

(def mail-subdirs ["outbox/tmp" "sent" "failed" "inbox/new" "inbox/in_process" "inbox/completed"])

(defn session-mail-dir [ctx session]
  (fs/path (:mail-dir ctx) session))

(defn system-mail-dir
  "Where phantom senders — the New Task note `open` queues — leave their mail."
  [ctx]
  (fs/path (:mail-dir ctx) "_system"))

(defn prepare-mail-dirs! [ctx rows]
  (doseq [row rows
          sub mail-subdirs]
    (fs/create-dirs (fs/path (session-mail-dir ctx (:session row)) sub)))
  (doseq [sub ["outbox/tmp" "sent" "failed"]]
    (fs/create-dirs (fs/path (system-mail-dir ctx) sub))))

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
   the operator's local work. Falls back to the source's own branch, then HEAD.

   `^{commit}` inside resolvable is what makes that fallback real. A ref can
   name a commit the checkout does not hold: a shallow clone's origin/main
   points past its own boundary, and plain rev-parse answers with the sha
   regardless. Starting a worktree there dies on a nonexistent object."
  [src branch]
  (or (resolvable src (str "refs/remotes/origin/" branch))
      (resolvable src (str "refs/heads/" branch))
      (git src "rev-parse" "HEAD")))

(defn read-base-tsv
  "repo -> pinned base commit, or {} when the task predates the pin."
  [ctx]
  (if-not (fs/regular-file? (:base-tsv ctx))
    {}
    (into {} (for [line (str/split-lines (slurp (str (:base-tsv ctx))))
                   :when (not (str/blank? line))
                   :let [[repo sha] (str/split line #"\t" -1)]
                   :when (and (not (str/blank? repo)) (not (str/blank? sha)))]
               [repo sha]))))

(defn write-base-tsv!
  "Persist the base each repo's work is counted against — append-only per repo.

   `prepare-worktrees!` already computes this sha to start the branch from, and
   until now threw it away, so every later reader re-derived it from refs
   instead. Those refs are the source checkout's, shared with every other
   worktree of it: a `git fetch` in ~/repos/<repo> moves origin/main under a
   running task, and a checkout whose `refs/remotes/origin/HEAD` symref was
   never created has no answer at all. Measured: a summary rendered an EMPTY
   diff for a repo holding a one-line commit, because origin/HEAD did not exist
   yet and there was no local `main` either — and an empty diff is indis-
   tinguishable from a repo that correctly changed nothing.

   Never overwrites an existing entry. `open` re-runs to resume a task after a
   reboot, and re-pinning then would silently re-point the base at whatever
   origin/main has since become — which is the exact drift this file prevents."
  [ctx worktrees]
  (let [existing (read-base-tsv ctx)
        merged (reduce (fn [m {:keys [name start]}]
                         (if (or (contains? m name) (str/blank? (str start)))
                           m
                           (assoc m name start)))
                       existing worktrees)]
    (when (seq merged)
      (fs/create-dirs (fs/parent (:base-tsv ctx)))
      (spit (str (:base-tsv ctx))
            (str/join "" (for [[repo sha] (sort merged)] (str repo "\t" sha "\n")))))
    merged))

(defn task-branch
  "Every repo's worktree for this task sits on one branch name. The task id is
   in it, so two tasks on the same checkout never collide."
  [ctx]
  (str "sk/" (:task-id ctx)))

;; ------------------------------------------------------------------ worktrees

(defn prepare-worktrees!
  "One worktree per repo, added from the source checkout at ~/repos.

   The start ref is explicit — the source's origin/<default> at open time, the
   shared upstream state rather than whatever the operator has checked out. A
   bare `worktree add <path>` inherits the source's current HEAD instead: RAN
   in a sandbox, a worktree added while the source sat on a dirty feature
   branch started from `someone else's work in progress`, which is gobel's own
   escalation reproduced.

   Nothing is copied and nothing is cloned. The worktree shares the source's
   object store, so a second task on the same repo costs a checkout.

   Reattaching to a task branch that already exists uses it as-is. `-b` would
   refuse and `-B` would reset it to the start ref, throwing away every commit
   a role had made — the one case where re-running prepare could lose work."
  [ctx repos]
  (fs/create-dirs (:worktrees-dir ctx))
  (let [branch-name (task-branch ctx)
        worktrees
        (mapv (fn [{:keys [name path branch]}]
            (let [dir (str (fs/path (:worktrees-dir ctx) name))
                  branch (or branch (source-default-branch path))
                  start (source-pin-sha path branch)]
              (when-not (fs/exists? (fs/path dir ".git"))
                ;; A task folder deleted by hand leaves its worktree registered
                ;; in the source, and `worktree add` then refuses the same path
                ;; as "missing but already registered". Prune first.
                (git path "worktree" "prune")
                (if (git-ok? path "rev-parse" "--verify" "--quiet" (str "refs/heads/" branch-name))
                  (git path "worktree" "add" "--quiet" dir branch-name)
                  (git path "worktree" "add" "--quiet" "-b" branch-name dir start)))
                {:name name :source path :path dir :branch branch-name :start start}))
              repos)]
    ;; The sha the branch started from IS the task's base. Keep it, so no later
    ;; reader has to re-derive it from refs that move.
    (write-base-tsv! ctx worktrees)
    worktrees))

;; ---------------------------------------------------------------- prepare

(defn legacy-task?
  "A task opened before `repos` existed: no repos file, but a roles.tsv from the
   old shape. It is still running, and `open` is how anyone resumes it after a
   reboot kills its tmux socket."
  [ctx]
  (and (not (fs/regular-file? (:repos-file ctx)))
       (fs/regular-file? (:roles-tsv ctx))))

(defn require-truth! [ctx]
  (doseq [f (cond-> [(:goal-file ctx) (:metrics-file ctx) (:roles-file ctx)]
              ;; A legacy task predates this file. Demanding it turned "resume
              ;; the task you already have" into a hard failure on the one
              ;; command that resumes it.
              (not (legacy-task? ctx)) (conj (:repos-file ctx)))]
    (when-not (fs/regular-file? f)
      (throw (ex-info (str "task is missing " (fs/file-name f) ": " f) {})))))

(defn create-layout! [ctx]
  (doseq [k [:worktrees-dir :mail-dir :tmp-dir :state-dir :prompts-dir
             :evidence-dir :board-dir :daemon-dir :sessions-dir]]
    (fs/create-dirs (get ctx k)))
  (doseq [k [:decision-file :gotcha-file :escalation-file :finding-file]]
    (when-not (fs/exists? (get ctx k))
      (spit (str (get ctx k)) ""))))

(defn prepare!
  "Build everything under the task folder that a swarm needs before any agent
   runs: layout, worktrees, sessions.tsv, mail dirs. Idempotent."
  [ctx]
  (require-truth! ctx)
  (if (legacy-task? ctx)
    ;; Migrate on read, and touch nothing else. Its worktrees exist, at paths
    ;; its own rows record — one per ROLE, not one per repo. Rebuilding sessions
    ;; the new way here would add fresh worktrees beside them and point the task
    ;; at the empty ones, which is how a resume loses a day of work.
    (let [rows (read-sessions-tsv ctx)]
      (create-layout! ctx)
      (prepare-mail-dirs! ctx rows)
      (write-sessions-tsv! ctx rows)
      {:task-id (:task-id ctx)
       :task-dir (str (:task-dir ctx))
       :roles (parse-roles ctx)
       :repos (vec (for [r (distinct (keep :repo rows))] {:name r}))
       :sessions rows
       :legacy true})
    (let [roles (parse-roles ctx)
          repos (parse-repos ctx)
          role->repos (goal-repos (slurp (str (:goal-file ctx))) roles (mapv :name repos))]
      (create-layout! ctx)
      (let [worktrees (prepare-worktrees! ctx repos)
            rows (sessions ctx roles repos role->repos)]
        (prepare-mail-dirs! ctx rows)
        (write-sessions-tsv! ctx rows)
        {:task-id (:task-id ctx)
         :task-dir (str (:task-dir ctx))
         :roles roles
         :repos worktrees
         :sessions rows}))))

;; ---------------------------------------------------------------------------
;; Attention cross-offs.
;;
;; Task state, so it lives here rather than in the portal: a role that fixes the
;; thing it escalated has to be able to cross the item off itself, and the
;; portal is not the only writer any more. One key function, shared — a second
;; copy of this hash in the other writer would drift and quietly stop matching.

(defn attention-key
  "A stable id for one attention item. Escalations are append-only bullets and
   the other kinds are derived from files, so the text is the only thing that
   survives a re-render — there is no row id to use. Hashed because the raw text
   is a paragraph and this goes in a form field."
  [{:keys [kind text]}]
  (let [d (java.security.MessageDigest/getInstance "SHA-1")
        b (.digest d (.getBytes (str kind "\u0000" text) "UTF-8"))]
    (apply str (map #(format "%02x" %) (take 8 b)))))

(defn handled-file [ctx] (fs/path (:state-dir ctx) "attention-handled.tsv"))

(defn handled
  "key → when it was crossed off. A separate file, never escalation.md: the
   roles own that one and append to it, and a writer that edited it would be
   rewriting what a role said rather than recording what was done about it."
  [ctx]
  (into {} (for [l (str/split-lines (or (try (slurp (str (handled-file ctx))) (catch Exception _ nil)) ""))
                 :let [[k at] (str/split l #"\t" 2)]
                 :when (seq (str/trim (or k "")))]
             [k (or at "")])))

(defn set-handled!
  "Cross one off, or put it back. Rewrites the file rather than appending, so
   unticking actually removes the row instead of leaving both states in it."
  [ctx key on?]
  (let [now (if on?
              (assoc (handled ctx) key (str (java.time.Instant/now)))
              (dissoc (handled ctx) key))]
    (fs/create-dirs (:state-dir ctx))
    (spit (str (handled-file ctx))
          (str/join "" (for [[k at] (sort now)] (str k "\t" at "\n"))))))
