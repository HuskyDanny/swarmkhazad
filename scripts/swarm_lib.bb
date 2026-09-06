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
(load-file (str (fs/path script-dir "board_lib.bb")))

(def prompts-src-dir (fs/path (fs/parent script-dir) "prompts"))
(def pane-history-limit 10000)
(def daemon-stop-timeout-ms 5000)

(defn sq [value]
  (str "'" (str/replace (str value) #"'" "'\"'\"'") "'"))

(defn command-path [command]
  (let [result (process/sh {:continue true} "sh" "-c" (str "command -v " command))]
    (when (zero? (:exit result)) (not-empty (str/trim (:out result))))))

(defn check-dependencies! []
  (doseq [command ["tmux" "git" "bb"]]
    (when-not (command-path command)
      (throw (ex-info (str "'" command "' is required but not on PATH") {})))))

(defn resolve-harnesses!
  "Resolve every declared harness to an absolute path from `open`'s own PATH and
   record it in state/harnesses.tsv for the bin/ shims. The tmux login shell
   re-sources rc files and rebuilds PATH, so a bare `claude` typed into the pane
   could resolve to a different binary than the one the operator ran `open`
   with — or to nothing."
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

(defn write-prompt!
  "prompts/<role>.md: the role header, the constitution, the stage prompt."
  [ctx roles row]
  (fs/create-dirs (:prompts-dir ctx))
  (let [file (fs/path (:prompts-dir ctx) (str (:role row) ".md"))]
    (spit (str file)
          (str (role-header ctx roles row)
               (slurp (str (fs/path prompts-src-dir "constitution.prompt")))
               "\n## Stage: " (:role row) "\n\n"
               (stage-prompt (:role row))))
    file))

(defn start-text [ctx row]
  (str "You are role " (:role row) " in task " (:task-id ctx) ". Read " (:goal-file ctx) " and " (:metrics-file ctx)
       ", then run ready_for_next.bb and follow its output."))

;; ---------------------------------------------------------------- launch

(defn harness-command
  "The CLI invocation for one harness. claude and grok take the assembled prompt
   as a system prompt; codex and copilot have no such flag, so for them the
   prompt file's content leads the first message instead."
  [ctx row binary prompt]
  (let [wt (:worktree-path row)
        extra (str/join " " (map sq (task-lib/extra-argv row)))
        start (sq (start-text ctx row))
        prompt-then-start (str "\"$(cat " (sq prompt) ")\n\n\"" start)
        name (sq (str "sk " (:role row)))
        bin (sq binary)]
    (case (:harness row)
      "claude" (str "env CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN=1 " bin " --append-system-prompt-file " (sq prompt)
                    " --permission-mode bypassPermissions -n " name " " extra " -- " start)
      "codex" (str bin " -C " (sq wt) " --no-alt-screen --yolo " extra " " prompt-then-start)
      "copilot" (str bin " -C " (sq wt) " --no-alt-screen --name " name " --yolo " extra " -i " prompt-then-start)
      "grok" (str bin " --cwd " (sq wt) " --permission-mode bypassPermissions " extra
                  " --minimal --rules \"$(cat " (sq prompt) ")\" --verbatim " start)
      (throw (ex-info (str "no launch command for harness " (:harness row)) {})))))

(defn launch-script [ctx row binary prompt]
  (str "#!/bin/bash\n"
       "# swarmkhazad launch for role " (:role row) " of task " (:task-id ctx) " — generated by open\n"
       "export SWARMFORGE_ROLE=" (sq (:role row)) "\n"
       "export SWARMKHAZAD_TASK_ID=" (sq (:task-id ctx)) "\n"
       "export SWARMKHAZAD_TASK_DIR=" (sq (:task-dir ctx)) "\n"
       "export PATH=" (sq (str (:bin-dir ctx))) ":" (sq (str script-dir)) ":\"$PATH\"\n"
       "cd " (sq (:worktree-path row)) " || exit 1\n"
       "exec " (harness-command ctx row binary prompt) "\n"))

(defn launch-role!
  "Write prompts/<role>.launch.sh and type `bash <path>` into the role's pane.
   The command itself is typed, not the launch: a tty still in canonical mode
   (zsh not yet up) drops everything past 1024 bytes, and a full launch line with
   temp-dir paths is longer than that (RAN: the pane showed a truncated command
   and no launch)."
  [ctx roles harnesses row]
  (let [prompt (write-prompt! ctx roles row)
        script (fs/path (:prompts-dir ctx) (str (:role row) ".launch.sh"))]
    (spit (str script) (launch-script ctx row (get harnesses (:harness row)) prompt))
    (fs/set-posix-file-permissions script "rwxr-xr-x")
    (tmux! ctx "send-keys" "-t" (task-lib/session-name (:role row)) (str "bash " (sq script)) "Enter")
    (str script)))

;; ---------------------------------------------------------------- board + mail

(defn queue-new-task-note!
  "The task's opening mail: from (New Task) to the first role. Its created_at is
   the wall-clock start of the task."
  [ctx first-role]
  (let [stamp (handoff-lib/stamp)
        out (fs/path (task-lib/system-mail-dir ctx) "outbox")
        file (fs/path out (str "50_" stamp "_from_New-Task_to_" first-role ".handoff"))
        headers {"id" (str stamp "_from_New-Task")
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

(defn process-alive? [pid]
  (zero? (:exit (process/sh {:continue true} "kill" "-0" pid))))

(defn stop-handoffd!
  "Ask handoffd to stop (stop file), then TERM, then KILL after a grace period."
  [ctx]
  (let [pid-file (fs/path (:daemon-dir ctx) "handoffd.pid")
        stop-file (fs/path (:daemon-dir ctx) "stop")]
    (fs/create-dirs (:daemon-dir ctx))
    (spit (str stop-file) "")
    (when (fs/exists? pid-file)
      (let [pid (str/trim (slurp (str pid-file)))]
        (when (and (re-matches #"[0-9]+" pid) (process-alive? pid))
          (process/sh {:continue true} "kill" "-TERM" pid)
          (loop [waited 0]
            (when (and (< waited daemon-stop-timeout-ms) (process-alive? pid))
              (Thread/sleep 100)
              (recur (+ waited 100))))
          (when (process-alive? pid)
            (process/sh {:continue true} "kill" "-KILL" pid))))
      (fs/delete-if-exists pid-file))
    (fs/delete-if-exists stop-file)))

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
        _ (check-dependencies!)
        harnesses (resolve-harnesses! ctx roles)]
    (stop-handoffd! ctx)
    (kill-server! ctx)
    (boot-sessions! ctx roles)
    (fs/create-dirs (:bin-dir ctx))
    (when-not (board-lib/card-lane ctx task-id)
      (board-lib/create-card! ctx task-id (:role (first roles)))
      (queue-new-task-note! ctx (:role (first roles))))
    (start-handoffd! ctx)
    (assoc ctx :roles roles :commands (mapv #(launch-role! ctx roles harnesses %) roles))))

(defn close!
  "Tear the swarm down: archive panes, stop the daemon, kill the tmux server."
  [task-id]
  (let [ctx (task-lib/task-ctx task-id)]
    (when (server-up? ctx)
      (handoff-lib/archive-all! ctx))
    (stop-handoffd! ctx)
    (kill-server! ctx)
    ctx))
