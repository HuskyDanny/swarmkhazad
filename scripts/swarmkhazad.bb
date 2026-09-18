#!/usr/bin/env bb

;; swarmkhazad — the CLI. One task folder in, one swarm out. See README.md.

(ns swarmkhazad
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "swarm_lib.bb")))

(def usage-text
  (str "Usage:\n"
       "  swarmkhazad new <task-id> [--project <name>] [--repo <path>]... [--role <name>]...\n"
       "                            [--linear <KEY>] [--investigate]\n"
       "                                                 scaffold goal.md, metrics.md, roles;\n"
       "                                                 --project takes that project's checkouts, role\n"
       "                                                 lineup and cloud environment, and --repo and\n"
       "                                                 --role then narrow to a subset of each\n"
       "  swarmkhazad prepare <task-id>                  layout, worktrees, mail dirs, sessions.tsv\n"
       "  swarmkhazad open <task-id>                     prepare, then spawn every declared role\n"
       "  swarmkhazad open --linear <KEY> [--project <name>] [--repo <path>]... [--role <name>]...\n"
       "                   [--investigate]\n"
       "                                                 scaffold from a Linear issue, then open;\n"
       "                                                 --investigate uses the investigate → run lineup\n"
       "                                                 instead of a single implement role\n"
       "  swarmkhazad mcp                                serve `add_task` as one MCP tool on stdio\n"
       "  swarmkhazad close <task-id> [--reclaim] [--force]\n"
       "                                                 archive panes, stop the daemon, kill the tmux server;\n"
       "                                                 --reclaim also cleans and removes the worktrees and\n"
       "                                                 deletes the task branch (kept if not on origin)\n"
       "  swarmkhazad delete <task-id> [--force]        everything close --reclaim does, then the telemetry and the\n"
       "                                                 task folder itself. Refuses while a repo holds commits\n"
       "                                                 origin has never seen; --force removes it anyway\n"
       "  swarmkhazad smoke <task-id>                    each role: launch via its shim, read goal.md, send one note, exit\n"
       "  swarmkhazad paths <task-id>                    print the path map\n"
       "  swarmkhazad portal [--port <n>]                serve the portal on 127.0.0.1 (default 8765)\n"
       "  swarmkhazad telemetry <task-id>                cost, tokens and sessions per role, from VictoriaMetrics\n"
       "  swarmkhazad summary <task-id>                  ask whether the work is ready to merge, for its goals\n"
       "  swarmkhazad ship <task-id> [--yes]             push each repo's branch and open a draft PR, in the summary's merge order\n"
       "  swarmkhazad reap [--force]                     prune stale worktrees and delete orphaned sk/* branches from your checkouts\n"))

(defn usage! []
  ;; flush before exit: `print` leaves the text in the buffer and System/exit
  ;; discards it, so `swarmkhazad` with no arguments printed nothing at all.
  (binding [*out* *err*] (print usage-text) (flush))
  (System/exit 1))

(defn goal-template [task-id roles]
  (str "# " task-id " — <what this task is, one line>\n"
       "Opened by <who> · " (java.time.LocalDate/now) "\n\n"
       "## Goal\n"
       ;; The grammar goes in an HTML comment, not in a live checkbox: a
       ;; placeholder `@<repo>` inside a real goal line reads as a tag naming a
       ;; repo the task does not have, and prepare would refuse the task it had
       ;; just scaffolded.
       "<!-- one line per outcome: `- [ ] <role> @<repo> — <outcome>`.\n"
       "     The role and the @repo tags are both optional; a line with neither\n"
       "     belongs to every role and every repo.\n"
       ;; Named, because the roles file is the only other place they are
       ;; written down and a word here that is not one of them is not a role
       ;; at all — the line quietly becomes everybody's.
       "     This task's roles: " (str/join ", " roles) ". -->\n"
       "- [ ] <the outcome, one line>\n\n"
       "## Not-goal\n- <deliberately not doing X — why>\n\n"
       "## Hints\n- <absolute path> — why it matters\n"))

(defn metrics-template [task-id]
  (str "# " task-id " — bars\n\n"
       ;; The grammar goes in an HTML comment, not in a live bullet — the same
       ;; reason goal-template hides its own. A placeholder `measure:` command
       ;; is a real commanded bar to every parser that reads this file, so
       ;; `open` refused the task it had just scaffolded, and `run_evidence.bb`
       ;; would have tried to execute `<command>`.
       "## Quantitative\n"
       "<!-- one line per bar: `- <metric> — bar: <threshold> — measure: `<command>``.\n"
       "     A bar with a `measure:` command needs a role that runs it (the `run`\n"
       "     role); a bar judged by a human belongs under Qualitative. -->\n\n"
       "## Qualitative\n"
       "<!-- `- <property> — bar: <what passing looks like> — judged by: <who>` -->\n"))

(defn flag-values [args flag]
  (->> (partition 2 1 (cons nil args))
       (keep (fn [[f value]] (when (= f flag) value)))))

(def value-flags
  "Flags that consume the token after them. The set has to be explicit: a
   positional scan that assumes EVERY flag takes a value swallows the token
   after a boolean one, so `open --linear K --investigate --repo /p` read `/p`
   as the task id and scaffolded a task called `/p`."
  #{"--linear" "--repo" "--project" "--role"})

(defn positional-args
  "The bare arguments, with every flag — and the value of a value-taking flag —
   removed."
  [args]
  (loop [[a & more :as all] (vec args) out []]
    (cond
      (empty? all) out
      (value-flags a) (recur (rest more) out)
      (str/starts-with? a "-") (recur more out)
      :else (recur more (conj out a)))))

(defn project-scope!
  "The project `--project` names, the checkouts a task inside it runs in and
   the roles it runs, or nil when no project was named. Exits on a project that
   does not exist, a checkout it does not hold, or a role it does not list.

   The project owns the scope and the task picks inside it, exactly as the
   portal's form does: `--repo` and `--role` narrow, they never widen, and
   naming none of either means all of them. Widening is one edit on the
   project's page — a decision with a name on it — rather than a project that
   grows every time a task needs one more tree or one more stage.

   `--role` exists for the door that cannot hand-edit. `new` leaves the `roles`
   file unlocked and two lines long, so the CLI never needed a flag for it —
   but `open --linear` scaffolds AND launches in one call, and that is the form
   the MCP gateway shells, so there is no moment between the two in which
   anything could edit the file. Without the flag an unattended caller can
   narrow the checkouts and not the lineup."
  [args]
  (when-let [name (first (flag-values args "--project"))]
    (let [project (project-lib/read-project name)
          _ (when-not project
              (task-lib/fail! (str "no such project: " name
                                   (if-let [known (seq (map :name (project-lib/list-projects)))]
                                     (str " — known: " (str/join ", " known))
                                     " — create one on the portal's index page"))))
          scope (set (:repos project))
          picked (flag-values args "--repo")
          outside (remove scope picked)]
      (when (seq outside)
        (task-lib/fail! (str "not in project " name ": " (str/join ", " outside)
                             " — add the checkout on the project's edit page first, "
                             "then open the task")))
      (when (empty? scope)
        (task-lib/fail! (str "project " name " holds no checkouts — add one on its edit page")))
      (let [{:keys [roles error]} (project-lib/pick-roles project (flag-values args "--role"))]
        (when error (task-lib/fail! error))
        {:project project
         :repos (if (seq picked) (vec picked) (vec (:repos project)))
         :roles roles}))))

(defn new!
  "Scaffold a task folder. With --linear <KEY> the goal comes from the issue
   instead of the template, and `roles` is one implement role.

   With `--project <name>` the task belongs to that project: its checkouts are
   the project's (narrowed by `--repo`), its `roles` file is the project's
   lineup rather than the single implement role, and it drops a `project` file
   so the portal lists it there and its @cloud bars find the project's
   environment. `--investigate` still wins over the lineup — the investigation
   lane IS a different pair of roles, and a project cannot mean otherwise.

   Without `--project`, `--repo` takes any checkout, and that is deliberate.
   The portal has two levels — a project owns the repo scope and a task picks a
   subset of it — and this is below both: no project, no scope, whatever paths
   you name. It is the door you use when the portal is the thing that is
   broken. `check-repos!` still refuses a path that is not a git checkout."
  [task-id args]
  (let [scope (project-scope! args)
        project (:project scope)
        repos (if scope (:repos scope) (flag-values args "--repo"))
        issue-key (first (flag-values args "--linear"))
        investigate? (boolean (some #{"--investigate"} args))
        ctx (task-lib/task-ctx task-id)]
    (when (fs/exists? (:task-dir ctx))
      (task-lib/fail! (str "task already exists: " (:task-dir ctx))))
    ;; Fetch before creating anything: a fetch that fails must leave no folder
    ;; behind, or the retry hits "task already exists" on a template goal.
    (let [issue (when issue-key
                  (load-file (str (fs/path script-dir "linear_intake.bb")))
                  ((resolve 'linear-intake/fetch-issue) issue-key))]
      (fs/create-dirs (:task-dir ctx))
      (spit (str (:metrics-file ctx)) (metrics-template task-id))
      ;; The lineup is written FIRST, and in one place, because goal.md's role
      ;; prefixes have to name roles this task actually has. It used to be
      ;; written by whichever lane ran and then overwritten by the project
      ;; block below, which left the goal already on disk prefixed for a lineup
      ;; that was about to change. The Linear lane wrote `- [ ] implement — `
      ;; on every acceptance line whatever the roles were, so every
      ;; `--investigate` ticket got a goal owned by `implement` in a task whose
      ;; roles are `investigate` and `run`.
      (spit (str (:roles-file ctx))
            (if (and project (not investigate?))
              (project-lib/roles-text (assoc project :roles (:roles scope)))
              (task-lib/roles-template repos investigate?)))
      (spit (str (:repos-file ctx)) (task-lib/repos-text repos))
      (let [lead (first (task-lib/declared-roles ctx))]
        (if issue
          (do (spit (str (:goal-file ctx))
                    ((resolve 'linear-intake/goal-md) task-id issue lead))
              (println (str "linear: " (:identifier issue) " " (:title issue))))
          (spit (str (:goal-file ctx)) (goal-template task-id (task-lib/declared-roles ctx)))))
      (when project
        (spit (str (project-lib/task-project-file ctx)) (str (:name project) "\n"))
        (println (str "project: " (:name project))))
      (println (str (:task-dir ctx))))))

(defn prepare! [task-id]
  (let [result (task-lib/prepare! (task-lib/task-ctx task-id))]
    (println "task:" (:task-dir result))
    (doseq [{:keys [name source path branch start]} (:repos result)]
      (println (str "repo: " name " " path " " branch " @ " start " from " source)))
    (doseq [{:keys [session harness receive-mode model worktree-path]} (:sessions result)]
      (println (str "session: " session " " harness " " receive-mode " model=" model " " worktree-path)))))

(defn paths! [task-id]
  (doseq [[k v] (sort-by (comp str key) (task-lib/task-ctx task-id))]
    (println (name k) (str v))))

(defn open! [task-id]
  (let [ctx (swarm-lib/open! task-id)]
    (println "swarm open:" task-id)
    (println "tmux socket:" (:tmux-socket ctx))
    (doseq [{:keys [session harness model worktree-path]} (:sessions ctx)]
      (println (str "  " (task-lib/session-name session) "  " harness " model=" model "  " worktree-path)))
    (println (str "attach: tmux -S " (:tmux-socket ctx) " attach -t sk-<role>_<repo>"))
    ;; Say whether the harnesses are still there before saying how to attach to
    ;; them. Every line above is printed whether they launched or died the same
    ;; second, which is how a task once reported eight healthy sessions that
    ;; were eight shell prompts.
    (let [failed (swarm-lib/failed-launches! ctx (:sessions ctx))]
      (when (seq failed)
        (binding [*out* *err*]
          (doseq [[s status] failed]
            (println (str "EXITED " status "  " (task-lib/session-name s)
                          "  — see `tmux -S " (:tmux-socket ctx)
                          " capture-pane -p -t " (task-lib/session-name s) "`")))
          (println (str (count failed) " of " (count (:sessions ctx))
                        " sessions' harnesses exited non-zero at launch."))
          ;; All of them means the swarm did not start, whatever the lines above
          ;; say. Some of them is still a working swarm, so it reports and exits
          ;; zero rather than making a partial launch unopenable.
          (when (= (count failed) (count (:sessions ctx)))
            (throw (ex-info "no session came up" {:exit 1}))))))))

(defn open-cmd!
  "`open <task-id>`, or `open --linear <KEY> [--repo <path>]...`, which scaffolds
   the task from the issue first — the task id is the key, lowercased, unless one
   is given. An existing task folder is opened as it stands: intake never
   overwrites a goal someone has already edited."
  [args]
  (let [issue-key (first (flag-values args "--linear"))
        ;; A flag's VALUE is not the task id. Dropping every `--flag value` pair
        ;; first is the difference between `open --linear MITH-3437` scaffolding
        ;; `mith-3437` and scaffolding a task literally named `MITH-3437`.
        named (first (positional-args args))
        task-id (or named (when issue-key
                            (load-file (str (fs/path script-dir "linear_intake.bb")))
                            ((resolve 'linear-intake/task-id-for) issue-key)))]
    (when-not task-id (usage!))
    (when (and issue-key (not (fs/exists? (:task-dir (task-lib/task-ctx task-id)))))
      (new! task-id args))
    (open! task-id)))

(defn close! [task-id args]
  (swarm-lib/close! task-id
                    (boolean (some #{"--reclaim"} args))
                    (boolean (some #{"--force"} args)))
  (println "swarm closed:" task-id))

(defn delete! [task-id args]
  (let [{:keys [removed kept metrics orphan]}
        (swarm-lib/delete! task-id (boolean (some #{"--force"} args)))]
    (if-not removed
      (binding [*out* *err*]
        (println (str "refused: " (str/join ", " kept)
                      (if (= 1 (count kept)) " still holds" " still hold")
                      " commits origin has never seen."))
        (println (str "  push them, or `swarmkhazad delete " task-id " --force` to lose them."))
        (System/exit 1))
      (do
        (when orphan
          (println "  no task folder — this id survived only in the metrics store"))
        (println (case metrics
                   :forgotten "  telemetry: series dropped"
                   :refused "  telemetry: the server refused the delete — its series are still there"
                   :unreachable "  telemetry: no server answered — its series are still there"))
        (println "task deleted:" task-id)))))

(defn smoke! [task-id]
  (let [results (swarm-lib/smoke! task-id)]
    (doseq [{:keys [role harness vendor ok exit seconds models expected note cost turns detail]} results]
      (println (str (if ok "OK   " "FAIL ") role "  " harness " model=" vendor
                    "  exit=" exit "  " seconds "s  note=" (if note "sent" "none")
                    (when (seq models) (str "  used=" (str/join "," models)))
                    (when (and expected (not (some #{expected} models))) (str "  expected=" expected))
                    (when cost (format "  cost=$%.4f" (double cost)))
                    (when turns (str "  turns=" turns))))
      (when detail (println (str "     " (str/replace detail #"\n" "\n     ")))))
    (System/exit (if (every? :ok results) 0 1))))

(defn -main [& args]
  (try
    (case (first args)
      "new" (if (second args) (new! (second args) (drop 2 args)) (usage!))
      "prepare" (if (second args) (prepare! (second args)) (usage!))
      "open" (open-cmd! (rest args))
      "close" (if (second args) (close! (second args) args) (usage!))
      "delete" (if (second args) (delete! (second args) args) (usage!))
      "smoke" (if (second args) (smoke! (second args)) (usage!))
      "paths" (if (second args) (paths! (second args)) (usage!))
      "mcp" (do (load-file (str (fs/path script-dir "mcp_gateway.bb")))
                (apply (resolve 'mcp-gateway/-main) (rest args)))
      "portal" (do (load-file (str (fs/path script-dir "portal.bb")))
                   (apply (resolve 'portal/-main) (rest args)))
      "telemetry" (do (load-file (str (fs/path script-dir "telemetry.bb")))
                      (apply (resolve 'telemetry/-main) (rest args)))
      "summary" (do (load-file (str (fs/path script-dir "summary.bb")))
                    (apply (resolve 'summary/-main) (rest args)))
      "ship" (do (load-file (str (fs/path script-dir "ship.bb")))
                 (apply (resolve 'ship/-main) (rest args)))
      "reap" (do (load-file (str (fs/path script-dir "reap.bb")))
                 (apply (resolve 'reap/-main) (rest args)))
      (usage!))
    (catch clojure.lang.ExceptionInfo e
      (task-lib/fail! (str "swarmkhazad: " (ex-message e))))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
