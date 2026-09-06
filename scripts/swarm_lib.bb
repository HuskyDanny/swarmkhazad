#!/usr/bin/env bb

;; swarm-lib — open and close one task's swarm.
;;
;; open:  prepare the folder (task-lib), one tmux server on the task's socket,
;;        one session per role, the board card in the first role's lane, the
;;        New Task note in the _system outbox, handoffd, then each role's
;;        harness launched in its session with SWARMFORGE_ROLE / SWARMKHAZAD_*
;;        exported and <task>/bin plus this scripts dir on PATH.
;; close: archive every pane, stop handoffd, kill the tmux server.
;;
;; No terminal windows are opened; the portal (later) and `tmux -S <socket>
;; attach -t sk-<role>` are the ways in.

(ns swarm-lib
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "handoff_lib.bb")))

(def prompts-src-dir (fs/path (fs/parent script-dir) "prompts"))
(def pane-history-limit 10000)

(defn sq [value]
  (str "'" (str/replace (str value) #"'" "'\"'\"'") "'"))

(defn command-path [command]
  (let [result (process/sh {:continue true} "sh" "-c" (str "command -v " command))]
    (when (zero? (:exit result)) (not-empty (str/trim (:out result))))))

(defn check-dependencies! [roles]
  (doseq [command ["tmux" "git" "bb"]]
    (when-not (command-path command)
      (throw (ex-info (str "'" command "' is required but not on PATH") {})))))

(defn resolve-harnesses!
  "Resolve every declared harness to an absolute path from `open`'s own PATH and
   record it in state/harnesses.tsv. The tmux login shell re-sources rc files
   and rebuilds PATH, so a bare `claude` typed into the pane could resolve to a
   different binary than the one the operator ran `open` with — or to nothing."
  [ctx roles]
  (let [paths (into {} (for [h (distinct (map :harness roles))]
                         [h (or (command-path h)
                                (throw (ex-info (str "'" h "' is required but not on PATH") {})))]))]
    (spit (str (fs/path (:state-dir ctx) "harnesses.tsv"))
          (apply str (for [[h p] paths] (str h "\t" p "\n"))))
    paths))

;; ---------------------------------------------------------------- tmux

(defn tmux [ctx & args]
  (apply process/sh {:continue true} "tmux" "-S" (:tmux-socket ctx) args))

(defn tmux! [ctx & args]
  (let [result (apply tmux ctx args)]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "tmux " (str/join " " args) " failed: " (str/trim (:err result))) {})))
    (str/trim (:out result))))

(defn server-up? [ctx]
  (zero? (:exit (tmux ctx "list-sessions"))))

(defn kill-server! [ctx]
  (when (server-up? ctx) (tmux ctx "kill-server"))
  (fs/delete-if-exists (fs/path (:tmux-socket ctx))))

(defn boot-sessions! [ctx roles]
  (fs/create-dirs (fs/parent (fs/path (:tmux-socket ctx))))
  (spit (str (:tmux-socket-file ctx)) (str (:tmux-socket ctx) "\n"))
  (doseq [{:keys [role worktree-path]} roles
          :let [session (task-lib/session-name role)]]
    (tmux! ctx "new-session" "-d" "-s" session "-n" role "-c" worktree-path)
    (tmux! ctx "set-option" "-t" session "history-limit" (str pane-history-limit))
    (tmux! ctx "set-window-option" "-t" (str session ":" role) "allow-rename" "off")))

;; ---------------------------------------------------------------- prompts

(defn stage-prompt [role]
  (let [specific (fs/path prompts-src-dir (str role ".prompt"))
        fallback (fs/path prompts-src-dir "default.prompt")]
    (slurp (str (if (fs/regular-file? specific) specific fallback)))))

(defn role-header [ctx roles row]
  (let [names (mapv :role roles)
        idx (.indexOf names (:role row))
        next-role (get names (inc idx))]
    (str "# swarmkhazad · task " (:task-id ctx) " · role " (:role row) "\n\n"
         "- Task folder: " (:task-dir ctx) "\n"
         (if (:repo row)
           (str "- Your worktree: " (:worktree-path row) " (branch " (task-lib/role-branch ctx (:role row)) ", off the task's clone of " (:repo row) ")\n")
           "- You have no repo; work in the task folder.\n")
         "- Roles in order: " (str/join " → " names) ". You are #" (inc idx) " of " (count names) "."
         (if next-role (str " Forward finished work to `" next-role "`.\n") " You are the last role: your git_handoff goes to every other role and closes the task.\n")
         "- Helpers on PATH: ready_for_next.bb, done_with_current.bb, swarm_handoff.bb, merge_and_process.bb\n\n")))

(defn write-prompt! [ctx roles row]
  (fs/create-dirs (:prompts-dir ctx))
  (let [file (fs/path (:prompts-dir ctx) (str (:role row) ".md"))
        start (fs/path (:prompts-dir ctx) (str (:role row) ".start"))]
    (spit (str file)
          (str (role-header ctx roles row)
               (slurp (str (fs/path prompts-src-dir "constitution.prompt")))
               "\n## Stage: " (:role row) "\n\n"
               (stage-prompt (:role row))))
    (spit (str start)
          (str "You are role " (:role row) " in task " (:task-id ctx) ". Read " (:goal-file ctx) " and " (:metrics-file ctx)
               ", then run ready_for_next.bb and follow its output.\n"))
    {:prompt file :start start}))

;; ---------------------------------------------------------------- launch

(defn harness-command [row binary prompt start]
  (let [wt (:worktree-path row)
        extra (str/join " " (map sq (task-lib/extra-argv row)))
        initial (str "\"$(cat " (sq start) ")\"")
        name (sq (str "sk " (:role row)))
        bin (sq binary)]
    (case (:harness row)
      "claude" (str "CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN=1 " bin " --append-system-prompt-file " (sq prompt)
                    " --permission-mode bypassPermissions -n " name " " extra " " initial)
      "codex" (str bin " -C " (sq wt) " --no-alt-screen --yolo " extra " " initial)
      "copilot" (str bin " -C " (sq wt) " --no-alt-screen --name " name " --yolo " extra " -i " initial)
      "grok" (str bin " --cwd " (sq wt) " --permission-mode bypassPermissions " extra
                  " --minimal --rules \"$(cat " (sq prompt) ")\" --verbatim " initial))))

(defn launch-command [ctx row binary prompt start]
  (str "export SWARMFORGE_ROLE=" (sq (:role row))
       " SWARMKHAZAD_TASK_ID=" (sq (:task-id ctx))
       " SWARMKHAZAD_TASK_DIR=" (sq (:task-dir ctx))
       " && export PATH=" (sq (str (:bin-dir ctx))) ":" (sq (str script-dir)) ":\"$PATH\""
       " && cd " (sq (:worktree-path row))
       " && " (harness-command row binary prompt start)))

(defn launch-role! [ctx roles harnesses row]
  (let [{:keys [prompt start]} (write-prompt! ctx roles row)
        command (launch-command ctx row (get harnesses (:harness row)) prompt start)]
    (tmux! ctx "send-keys" "-t" (task-lib/session-name (:role row)) command "Enter")
    command))

;; ---------------------------------------------------------------- board + mail

(defn pack-board! [ctx & args]
  (let [result (apply process/sh {:continue true} "bb" (str (fs/path script-dir "pack_board.bb")) (concat args ["--task" (:task-id ctx)]))]
    (when-not (zero? (:exit result))
      (throw (ex-info (str/trim (str "pack_board " (str/join " " args) ": " (:err result))) {})))))

(defn board-has-card? [ctx]
  (let [file (fs/path (:board-dir ctx) "tasks.tsv")]
    (and (fs/regular-file? file)
         (some #(= (:task-id ctx) (first (str/split % #"\t"))) (str/split-lines (slurp (str file)))))))

(defn queue-new-task-note!
  "The task's opening mail: from (New Task) to the first role. Its created_at is
   the wall-clock start of the task."
  [ctx first-role]
  (let [stamp (handoff-lib/id-timestamp)
        seq (handoff-lib/next-sequence ctx)
        out (fs/path (task-lib/system-mail-dir ctx) "outbox")
        file (fs/path out (str "50_" stamp "_" seq "_from_New-Task_to_" first-role ".handoff"))
        headers {"id" (str stamp "_" seq "_from_New-Task")
                 "from" "(New Task)"
                 "to" first-role
                 "priority" "50"
                 "type" "note"
                 "task_id" (:task-id ctx)
                 "task" (:task-id ctx)
                 "message" (str "New task " (:task-id ctx) ": read goal.md and metrics.md in the task folder and begin.")
                 "created_at" (handoff-lib/timestamp)}]
    (fs/create-dirs out)
    (spit (str file) (handoff-lib/render-message headers (str "Re-read your instructions.\n\n" (get headers "message") "\n")))
    file))

;; ---------------------------------------------------------------- daemon

(defn stop-handoffd! [ctx]
  (process/sh {:continue true} "bb" (str (fs/path script-dir "stop_handoff_daemon.bb")) (:task-id ctx)))

(defn start-handoffd! [ctx]
  (fs/create-dirs (:daemon-dir ctx))
  (process/process ["bb" (str (fs/path script-dir "handoffd.bb")) (:task-id ctx)]
                   {:out (str (fs/path (:daemon-dir ctx) "handoffd.log")) :err :out
                    :extra-env {"SWARMKHAZAD_HOME" (str (task-lib/home))}}))

;; ---------------------------------------------------------------- open / close

(defn open!
  "Open the swarm for task-id. Returns the ctx plus :roles and :commands."
  [task-id]
  (let [ctx (task-lib/task-ctx task-id)
        {:keys [roles]} (task-lib/prepare! ctx)
        _ (check-dependencies! roles)
        harnesses (resolve-harnesses! ctx roles)]
    (stop-handoffd! ctx)
    (kill-server! ctx)
    (boot-sessions! ctx roles)
    (fs/create-dirs (:bin-dir ctx))
    (when-not (board-has-card? ctx)
      (pack-board! ctx "create" "--name" task-id "--lane" (:role (first roles)))
      (queue-new-task-note! ctx (:role (first roles))))
    (start-handoffd! ctx)
    (let [commands (mapv #(launch-role! ctx roles harnesses %) roles)]
      (spit (str (fs/path (:state-dir ctx) "opened-at")) (str (handoff-lib/timestamp) "\n"))
      (assoc ctx :roles roles :commands commands))))

(defn close!
  "Tear the swarm down: archive panes, stop the daemon, kill the tmux server."
  [task-id]
  (let [ctx (task-lib/task-ctx task-id)]
    (when (server-up? ctx)
      (process/sh {:continue true} "bb" (str (fs/path script-dir "pack_board.bb")) "archive-all" "--task" task-id))
    (stop-handoffd! ctx)
    (kill-server! ctx)
    (spit (str (fs/path (:state-dir ctx) "closed-at")) (str (handoff-lib/timestamp) "\n"))
    ctx))
