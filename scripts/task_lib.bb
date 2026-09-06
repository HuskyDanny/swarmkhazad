#!/usr/bin/env bb

;; task-lib — the one place that knows the shape of ~/.swarmkhazad/tasks/<task-id>/.
;;
;; Every other script asks this namespace for a path instead of spelling one out,
;; so the layout can move in one file. Nothing here touches tmux or an agent CLI;
;; it is the filesystem contract only: paths, the `roles` declaration, the clone
;; of the target repo, the per-role worktrees, and the roles.tsv the helpers read.
;;
;; Layout of one task folder:
;;
;;   goal.md metrics.md          control-owned truth (chmod 444 once locked)
;;   roles                       declaration: <role> <harness> <repo> [task|batch] [model=<vendor>] [cli args...]
;;   decision.md gotcha.md escalation.md   roles append one bullet per line
;;   draft-<role>.md             a role's full write-up
;;   evidence/<bar>.txt          the run role's measurements
;;   repos/<name>/               clone of the target repo (objects hardlinked from the local source)
;;   worktrees/<role>/           one worktree per role, off repos/<name>
;;   mail/<role>/{outbox,sent,failed,inbox/{new,in_process,completed}}   the handoff transport
;;   bin/{claude,codex,grok}     per-role harness shims
;;   prompts/<role>.md hooks/<role>.settings.json   generated per launch
;;   state/                      roles.tsv, tmux-socket, board/, daemon/, sessions/, judge/
;;   tmp/                        scratch (handoff drafts live here, never in the repo)

(ns task-lib
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def known-agents #{"claude" "codex" "copilot" "grok"})
(def receive-modes #{"task" "batch"})
(def known-vendors #{"anthropic" "glm" "kimi" "deepseek" "qwen"})

;; roles.tsv column order. Read by every helper; never index a column by number
;; anywhere else.
(def roles-tsv-columns
  [:role :harness :repo :worktree-path :session :display :receive-mode :model :extra-args])

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

(defn expand-home [p]
  (let [s (str p)]
    (cond
      (= s "~") (System/getProperty "user.home")
      (str/starts-with? s "~/") (str (System/getProperty "user.home") (subs s 1))
      :else s)))

(defn home []
  (fs/path (expand-home (or (not-empty (System/getenv "SWARMKHAZAD_HOME"))
                            "~/.swarmkhazad"))))

(defn tasks-dir []
  (fs/path (home) "tasks"))

(defn script-dir []
  (fs/parent (fs/absolutize *file*)))

(defn valid-task-id? [id]
  (boolean (and (string? id) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]{0,99}" id))))

(defn tmux-socket-path [task-id]
  (str (fs/path "/tmp" (str "swarmkhazad-" (System/getProperty "user.name")) (str task-id ".sock"))))

(defn task-ctx
  "The path map for one task. Pure: builds paths, touches nothing."
  [task-id]
  (when-not (valid-task-id? task-id)
    (throw (ex-info (str "Invalid task id: " (pr-str task-id)) {:task-id task-id})))
  (let [task-dir (fs/path (tasks-dir) task-id)
        state-dir (fs/path task-dir "state")]
    {:task-id task-id
     :task-dir task-dir
     :script-dir (script-dir)
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
     :bin-dir (fs/path task-dir "bin")
     :prompts-dir (fs/path task-dir "prompts")
     :hooks-dir (fs/path task-dir "hooks")
     :tmp-dir (fs/path task-dir "tmp")
     :state-dir state-dir
     :roles-tsv (fs/path state-dir "roles.tsv")
     :tmux-socket-file (fs/path state-dir "tmux-socket")
     :tmux-socket (tmux-socket-path task-id)
     :board-dir (fs/path state-dir "board")
     :daemon-dir (fs/path state-dir "daemon")
     :sessions-dir (fs/path state-dir "sessions")
     :judge-dir (fs/path state-dir "judge")}))

(defn ctx-from-env
  "The ctx of the task this process runs inside, from SWARMKHAZAD_TASK_ID."
  []
  (let [id (System/getenv "SWARMKHAZAD_TASK_ID")]
    (when (str/blank? id)
      (throw (ex-info "SWARMKHAZAD_TASK_ID is not set" {:exit 1})))
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

(defn skip-line? [line]
  (or (str/blank? line) (str/starts-with? line "#")))

(defn display-name [role]
  (->> (str/split (str/replace role #"[-_]" " ") #"\s+")
       (remove str/blank?)
       (map str/capitalize)
       (str/join " ")))

(defn session-name [role]
  (str "sk-" role))

(defn repo-name [repo-path]
  (str/replace (fs/file-name (fs/path (expand-home repo-path))) #"\.git$" ""))

(defn parse-role-line
  "One `roles` line → a role map, or throws with the line number."
  [line-no line]
  (let [fields (str/split (str/trim line) #"\s+")
        _ (when (< (count fields) 3)
            (throw (ex-info (format "roles line %d: need <role> <harness> <repo>; got %s" line-no (pr-str line)) {})))
        [role harness repo & trailing] fields
        harness (str/lower-case harness)
        [receive-mode trailing] (if (receive-modes (first trailing))
                                  [(first trailing) (rest trailing)]
                                  ["task" trailing])
        model-token (first (filter #(str/starts-with? % "model=") trailing))
        model (if model-token (subs model-token (count "model=")) "anthropic")
        extra (remove #(str/starts-with? % "model=") trailing)]
    (when (str/includes? role "_")
      (throw (ex-info (format "roles line %d: role %s may not contain underscores (handoff filenames)" line-no (pr-str role)) {})))
    (when-not (known-agents harness)
      (throw (ex-info (format "roles line %d: unknown harness %s (want %s)" line-no (pr-str harness) (str/join "|" (sort known-agents))) {})))
    (when-not (known-vendors model)
      (throw (ex-info (format "roles line %d: unknown model vendor %s (want %s)" line-no (pr-str model) (str/join "|" (sort known-vendors))) {})))
    {:role role
     :harness harness
     :repo (if (= "none" repo) nil (expand-home repo))
     :receive-mode receive-mode
     :model model
     :extra-args (str/join " " extra)}))

(defn parse-roles
  "Parse the task's `roles` declaration. Rejects duplicates and missing repos."
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
    (doseq [{:keys [repo role]} rows
            :when repo]
      (when-not (fs/directory? (fs/path repo ".git"))
        (throw (ex-info (format "role %s: repo %s is not a git checkout" role repo) {}))))
    (mapv (fn [row]
            (assoc row
                   :session (session-name (:role row))
                   :display (display-name (:role row))
                   :worktree-path (if (:repo row)
                                    (str (fs/path (:worktrees-dir ctx) (:role row)))
                                    (str (:task-dir ctx)))
                   :repo-name (when (:repo row) (repo-name (:repo row)))))
          rows)))

;; ---------------------------------------------------------------- roles.tsv

(defn write-roles-tsv! [ctx roles]
  (fs/create-dirs (:state-dir ctx))
  (spit (str (:roles-tsv ctx))
        (apply str
               (for [row roles]
                 (str (str/join "\t" (map #(str (get row % "")) roles-tsv-columns)) "\n")))))

(defn read-roles-tsv
  "roles.tsv → vector of role maps, keyed by roles-tsv-columns."
  [ctx]
  (let [file (:roles-tsv ctx)]
    (if (fs/regular-file? file)
      (->> (str/split-lines (slurp (str file)))
           (remove str/blank?)
           (mapv (fn [line]
                   (let [cols (str/split line #"\t" -1)]
                     (zipmap roles-tsv-columns (concat cols (repeat "")))))))
      [])))

(defn role-row [ctx role]
  (some #(when (= role (:role %)) %) (read-roles-tsv ctx)))

(defn role-names [ctx]
  (mapv :role (read-roles-tsv ctx)))

;; ---------------------------------------------------------------- mail dirs

(def mail-subdirs ["outbox/tmp" "sent" "failed" "inbox/new" "inbox/in_process" "inbox/completed"])

(defn role-mail-dir [ctx role]
  (fs/path (:mail-dir ctx) role))

(defn system-mail-dir
  "Where phantom senders (New Task, Retry) queue and archive their mail."
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

(defn source-main-sha
  "The source checkout's origin/main at open time; falls back to its HEAD."
  [src]
  (if (git-ok? src "rev-parse" "--verify" "--quiet" "refs/remotes/origin/main")
    (git src "rev-parse" "refs/remotes/origin/main")
    (git src "rev-parse" "HEAD")))

(defn source-origin-url [src]
  (when (git-ok? src "remote" "get-url" "origin")
    (git src "remote" "get-url" "origin")))

(defn clone-dir [ctx repo-path]
  (fs/path (:repos-dir ctx) (repo-name repo-path)))

(defn clone-repo!
  "Clone the local checkout at repo-path into repos/<name>.

   `git clone` of a local path hardlinks the object store, so this costs one
   directory walk rather than a copy. Afterwards the clone owes the source
   nothing: main is pinned to the source's origin/main, `origin` is repointed at
   the source's upstream URL, and every other origin/* ref is dropped. Nothing
   under the source is written; nothing is read from it again."
  [ctx repo-path]
  (let [src (str (fs/canonicalize (fs/path repo-path)))
        dest (clone-dir ctx repo-path)]
    (fs/create-dirs (:repos-dir ctx))
    (if (fs/directory? (fs/path dest ".git"))
      {:repo src :clone (str dest) :fresh false}
      (let [sha (source-main-sha src)
            upstream (source-origin-url src)]
        (sh-out "git" "clone" "--quiet" "--no-checkout" "--" src (str dest))
        (git dest "update-ref" "refs/remotes/origin/main" sha)
        (doseq [ref (->> (str/split-lines (git dest "for-each-ref" "--format=%(refname)" "refs/remotes/origin/"))
                         (remove str/blank?)
                         (remove #{"refs/remotes/origin/main" "refs/remotes/origin/HEAD"}))]
          (git dest "update-ref" "-d" ref))
        (git dest "checkout" "--quiet" "-B" "main" sha)
        (doseq [branch (->> (str/split-lines (git dest "for-each-ref" "--format=%(refname:short)" "refs/heads/"))
                            (remove str/blank?)
                            (remove #{"main"}))]
          (git dest "branch" "--quiet" "-D" branch))
        (git dest "branch" "--quiet" "--set-upstream-to=origin/main" "main")
        (when upstream
          (git dest "remote" "set-url" "origin" upstream))
        {:repo src :clone (str dest) :fresh true :sha sha :upstream upstream}))))

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

(defn prepare-worktrees!
  "One worktree per role that names a repo, off that repo's clone, branch sk/<task-id>/<role>."
  [ctx roles]
  (fs/create-dirs (:worktrees-dir ctx))
  (doseq [{:keys [role repo worktree-path]} roles
          :when repo]
    (let [clone (clone-dir ctx repo)]
      (when-not (fs/exists? (fs/path worktree-path ".git"))
        (git clone "worktree" "add" "--quiet" "-B" (role-branch ctx role) worktree-path "main")))))

;; ---------------------------------------------------------------- prepare

(defn require-truth! [ctx]
  (doseq [f [(:goal-file ctx) (:metrics-file ctx) (:roles-file ctx)]]
    (when-not (fs/regular-file? f)
      (throw (ex-info (str "task is missing " (fs/file-name f) ": " f) {})))))

(defn create-layout! [ctx]
  (doseq [k [:evidence-dir :repos-dir :worktrees-dir :mail-dir :bin-dir :prompts-dir :hooks-dir
             :tmp-dir :state-dir :board-dir :daemon-dir :sessions-dir :judge-dir]]
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
