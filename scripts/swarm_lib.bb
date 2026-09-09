#!/usr/bin/env bb

;; swarm-lib — open, close and smoke one task's swarm.
;;
;; open:  prepare the folder (task-lib), one tmux server on the task's socket,
;;        one session per role, the board card in the first role's lane, the
;;        New Task note in the _system outbox, handoffd, then each role's
;;        harness launched through its shim in its worktree with
;;        SWARMFORGE_ROLE / SWARMKHAZAD_* exported and <task>/bin plus this
;;        scripts dir on PATH.
;; close: archive every pane, stop handoffd, kill the tmux server, drop the
;;        trust entries open added.
;; smoke: every role once through its shim in print mode: read goal.md, send
;;        one note, exit clean, using its own model.
;;
;; No terminal windows are opened; the portal (later) and `tmux -S <socket>
;; attach -t sk-<role>` are the ways in.

(ns swarm-lib
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "handoff_lib.bb")))
(load-file (str (fs/path script-dir "board_lib.bb")))
;; For the bar parser only — `open` refuses a task declaring measure: commands
;; that no role will run. Same parser the portal and the runner use, so the
;; three cannot disagree about what a bar is.
(load-file (str (fs/path script-dir "run_evidence.bb")))
;; For `delete!` only — a task's cost outlives its folder otherwise, and the
;; dashboard goes on charting a task_id nothing can be opened from.
(load-file (str (fs/path script-dir "telemetry.bb")))

(def prompts-src-dir (fs/path (fs/parent script-dir) "prompts"))
(def pane-history-limit 10000)
(def daemon-stop-timeout-ms 5000)
(def smoke-timeout-ms 240000)

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
   record it in state/harnesses.tsv for the shims. The tmux login shell
   re-sources rc files and rebuilds PATH, so a bare `claude` typed into the pane
   could resolve to a different binary than the one the operator ran `open`
   with — or to nothing.

   Wrapper shims are skipped rather than pinned. `open` is often run from inside
   a terminal that puts its own wrapper first on PATH, and pinning that wrapper
   is worse than not resolving at all: every role launches, dies on an argv the
   wrapper rewrote, and relaunches, which reads as a swarm that started and did
   nothing. Skipping is reported, and a harness with nothing but wrappers fails
   here with the paths it rejected."
  [ctx roles]
  (let [resolved (into {} (for [h (distinct (map :harness roles))]
                            [h (task-lib/resolve-harness h)]))]
    (doseq [[h r] resolved]
      (when-not r
        (let [all (task-lib/harness-candidates h)]
          (throw (ex-info (if (seq all)
                            (str "'" h "' resolves only to wrapper shims, which rewrite the argv we pass:\n  "
                                 (str/join "\n  " all)
                                 "\nRun `open` outside that terminal, or pin the real binary with "
                                 "SWARMKHAZAD_HARNESS_" (str/upper-case h) "=/path/to/" h)
                            (str "'" h "' is required but not on PATH"))
                          {:harness h :candidates all}))))
      (doseq [skipped (:skipped r)]
        (binding [*out* *err*]
          (println (str "swarmkhazad: " h ": skipped wrapper shim " skipped))))
      (binding [*out* *err*]
        (println (str "swarmkhazad: " h " -> " (:path r) (when (:pinned r) " (pinned)")))))
    (spit (str (fs/path (:state-dir ctx) "harnesses.tsv"))
          (apply str (for [[h r] resolved] (str h "\t" (:path r) "\n"))))
    (into {} (for [[h r] resolved] [h (:path r)]))))

(defn write-shims!
  "Install scripts/shim.sh as <task>/bin/<harness> for every known harness, and
   the vendor table beside the shim's other inputs under state/."
  [ctx]
  (fs/create-dirs (:bin-dir ctx))
  (doseq [h task-lib/known-agents]
    (let [target (fs/path (:bin-dir ctx) h)]
      (fs/copy (fs/path script-dir "shim.sh") target {:replace-existing true})
      (fs/set-posix-file-permissions target "rwxr-xr-x")))
  (fs/copy task-lib/vendors-file (fs/path (:state-dir ctx) "vendors.tsv") {:replace-existing true}))

(defn shim-path [ctx harness]
  (str (fs/path (:bin-dir ctx) harness)))

;; ---------------------------------------------------------------- trust

(defn claude-json-path []
  (or (not-empty (System/getenv "SWARMKHAZAD_CLAUDE_JSON"))
      (str (fs/path (fs/home) ".claude.json"))))

(defn claude-worktree-dirs [roles]
  (->> roles
       (filter #(= "claude" (:harness %)))
       (map :worktree-path)
       (map #(str (fs/canonicalize (fs/path %))))
       distinct))

(defn update-claude-json!
  "Read-modify-write ~/.claude.json with f, only when the file exists (Claude
   has run for this user) and f changes something."
  [f]
  (let [path (claude-json-path)]
    (when (fs/regular-file? path)
      (let [cfg (json/parse-string (slurp path))
            new (f cfg)]
        (when-not (= cfg new)
          (spit path (json/generate-string new {:pretty true})))))))

(defn trust-worktrees!
  "Pre-accept Claude Code's folder-trust dialog for every claude role's cwd.
   Neither --permission-mode bypassPermissions nor --dangerously-skip-permissions
   skips that dialog, hooks do not run until it is accepted, and a trusted parent
   does not cover a new child (all RAN); the only non-interactive door is
   `projects[<realpath>].hasTrustDialogAccepted` in ~/.claude.json. Additive:
   one key per worktree, nothing else in the file is touched."
  [ctx roles]
  (update-claude-json!
   (fn [cfg]
     (reduce #(assoc-in %1 ["projects" %2 "hasTrustDialogAccepted"] true) cfg (claude-worktree-dirs roles)))))

(defn untrust-worktrees!
  "close undoes trust-worktrees!: an entry that outlived its task would pre-trust
   whatever is created at that path next."
  [ctx roles]
  (update-claude-json!
   (fn [cfg]
     (reduce #(update %1 "projects" dissoc %2) cfg (claude-worktree-dirs roles)))))

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

(defn boot-sessions!
  "One tmux session per (role, repo), each opened in that repo's worktree.

   The window carries the session's own name rather than the role's: two
   sessions of one role differ only by repo, and a pane titled `implement`
   twice is a pane you cannot tell apart."
  [ctx rows]
  (fs/create-dirs (fs/parent (fs/path (:tmux-socket ctx))))
  (spit (str (:tmux-socket-file ctx)) (str (:tmux-socket ctx) "\n"))
  (doseq [{:keys [session worktree-path]} rows
          :let [name (task-lib/session-name session)]]
    (tmux! ctx "new-session" "-d" "-s" name "-n" session "-c" worktree-path)
    (tmux! ctx "set-option" "-t" name "history-limit" (str pane-history-limit))
    (tmux! ctx "set-window-option" "-t" (str name ":" session) "allow-rename" "off")))

;; ---------------------------------------------------------------- prompts

(defn stage-prompt [role]
  (let [specific (fs/path prompts-src-dir (str role ".prompt"))
        fallback (fs/path prompts-src-dir "default.prompt")]
    (slurp (str (if (fs/regular-file? specific) specific fallback)))))

(defn role-header [ctx rows row]
  (let [roles (vec (distinct (map :role rows)))
        idx (.indexOf roles (:role row))
        next-role (get roles (inc idx))
        mine (->> rows (filter #(= (:role row) (:role %))) (mapv :repo))]
    (str "# swarmkhazad · task " (:task-id ctx) " · " (:session row) "\n\n"
         "- Task folder: " (:task-dir ctx) "\n"
         "- Your repo: " (:repo row) "\n"
         "- Your worktree: " (:worktree-path row) " (branch " (task-lib/task-branch ctx) ")\n"
         (when (> (count mine) 1)
           (str "- As " (:role row) " you cover " (str/join ", " mine)
                " — one session each, run in that order. This one is only " (:repo row) ".\n"))
         ;; The lineup names ROLES, and so does the address. A session that was
         ;; told to forward to the next row of sessions.tsv was told to forward
         ;; to a sibling of its own role — the card then moved into the lane it
         ;; was already in, and the task never advanced past `implement`.
         "- Roles in order: " (str/join " → " roles) ". You are `" (:role row) "`, #" (inc idx) " of " (count roles) "."
         (if next-role
           (str " Forward finished work to `" next-role "` — the role, not a session. Every session it has gets it.\n")
           " You are the last role: your git_handoff goes to every other session and closes the task.\n")
         "- Helpers on PATH: ready_for_next.bb, done_with_current.bb, swarm_handoff.bb\n\n")))

(defn own-drafts
  "Rewrite every `draft-<role>.md` a prompt names into the draft of that role's
   session IN THIS REPO.

   The prompts name roles, because that is what a reader understands and what
   `draft-implement.md` means to a person. The judge (goal_judge.bb) and the
   handoff (swarm_handoff.bb) both read `draft-<session>.md`, because two
   sessions of one role would otherwise overwrite each other's write-up. Past
   one repo those two names differ, so a role that followed its own instructions
   literally wrote a file nothing read — the judge reports `(not written)` for
   real work and the handoff drops it from `artifacts:`.

   A cross-role reference is rewritten to the sibling in THIS repo: review is
   told to read implement's draft, and it wants the implement that worked on the
   tree it is reviewing. In a one-repo task session and role are the same string
   and every replacement here is a no-op."
  [text rows row]
  (let [in-repo (fn [role]
                  (or (some #(when (and (= role (:role %)) (= (:repo row) (:repo %))) (:session %)) rows)
                      (some #(when (= role (:role %)) (:session %)) rows)
                      role))]
    (reduce (fn [t role] (str/replace t (str "draft-" role ".md") (str "draft-" (in-repo role) ".md")))
            (reduce #(str/replace %1 %2 (str "draft-" (:session row) ".md"))
                    text
                    ;; Both spellings of "your own draft" a prompt uses. A
                    ;; placeholder left unresolved is the same defect as a role
                    ;; name left unresolved: the agent writes a file whose name
                    ;; nothing downstream reads.
                    ["draft-<your role>.md" "draft-<role>.md"])
            (distinct (map :role rows)))))

(defn write-prompt!
  "prompts/<session>.md: the lane's own brief, then the session header, the
   constitution, the stage prompt.

   The stage prompt is the ROLE's — every repo gets the same instructions for
   what implement or review means; only the header and the draft names differ.

   The lane's brief leads because the swarm's text has to be able to overrule
   it: cc_auto tells a session to finish with commit-push-pr and a run-folder
   nudge, and a swarm role hands off instead. Later instruction wins, so the
   general layer goes first and the specific one after — the ordinary overlay,
   which is not what the flag was doing before. A lane appending no file of its
   own (cc_full, cc_alt) contributes nothing and the prompt is unchanged."
  [ctx rows row]
  (fs/create-dirs (:prompts-dir ctx))
  (let [file (fs/path (:prompts-dir ctx) (str (:session row) ".md"))
        lane (task-lib/lane-system-prompt (:harness row))]
    (spit (str file)
          (str (when lane (str (slurp lane) "\n\n---\n\n"))
               (role-header ctx rows row)
               (own-drafts (str (slurp (str (fs/path prompts-src-dir "constitution.prompt")))
                                "\n## Stage: " (:role row) "\n\n"
                                (stage-prompt (:role row)))
                           rows row)))
    file))

;; ---------------------------------------------------------------- hooks

(def contract-hook (str (fs/path script-dir "hooks" "run-contract.sh")))

(defn lock-truth!
  "goal.md, metrics.md and repos are read-only from the moment a swarm opens.
   The SessionStart hook repeats this per role; the PreToolUse hook stops the
   chmod.

   `repos` is here because it is not only read at open. `ship` reads it hours
   later to decide which branches to push and open PRs from (ship.bb:314), and
   `sessions.tsv` was written from it once — so an edit mid-run cannot add a
   session, only make the file disagree with the panes that are running."
  [ctx]
  (doseq [f [(:goal-file ctx) (:metrics-file ctx) (:repos-file ctx)]]
    (when (fs/exists? f)
      (fs/set-posix-file-permissions f "r--r--r--"))))

(def goal-judge (str (fs/path script-dir "goal_judge.bb")))

(def hook-settings
  "The settings a claude role loads via --settings: the contract hook on
   SessionStart (startup, resume, compact) and on every file or shell tool, and
   the goal judge on Stop.

   HOOKS ONLY, and that is a constraint rather than a coincidence. `--settings`
   has two precedence rules, both measured against the real CLI:

     hook arrays   union — every file's hooks run, whatever the order
     scalar keys   the FIRST --settings wins

   RAN, two files setting the same `env` key, with a third carrying the observer
   so only the order varied:

     --settings A --settings B --settings C   ->  SK_PROBE=AAA
     --settings B --settings A --settings C   ->  SK_PROBE=BBB

   A lane passes its own --settings and appends ours (`lane_exec … \"$@\"`), so
   ours is always LAST — and last loses for anything that is not a hook. Put a
   scalar in here and the lane's value silently wins on a lane harness while
   yours applies everywhere else, which is the worst shape a bug can have. If a
   role ever needs a non-hook setting, it goes on the argv, not in this file."
  {:hooks {:SessionStart [{:hooks [{:type "command" :command contract-hook :timeout 10}]}]
           :PreToolUse [{:matcher "Edit|Write|MultiEdit|NotebookEdit|Bash"
                         :hooks [{:type "command" :command contract-hook :timeout 10}]}]
           :Stop [{:hooks [{:type "command" :command goal-judge :timeout 180}]}]}})

(defn write-hook-settings!
  "This session's own settings, and only its own.

   Settings files MERGE — measured against the real CLI, not assumed:

     claude --settings A --settings B   both files' SessionStart hooks fire,
                                        in either order

   So a lane's file and this one both apply, and copying the lane's hooks in
   here would only make this file lie about whose hooks they are. Two flags do
   NOT merge and are last-wins, which is why the argv still appends them:
   `--append-system-prompt-file` and `--model`."
  [ctx row]
  (fs/create-dirs (:hooks-dir ctx))
  (let [file (fs/path (:hooks-dir ctx) (str (:session row) ".settings.json"))]
    (spit (str file) (json/generate-string hook-settings {:pretty true}))
    file))

(defn start-text [ctx row]
  (str "You are role " (:role row) " working in " (:repo row) " (session " (:session row)
       ") of task " (:task-id ctx) ". Read " (:goal-file ctx) " and " (:metrics-file ctx)
       ", then run ready_for_next.bb and follow its output."))

;; ---------------------------------------------------------------- launch

(def claude-print-flags
  ["-p" "--tools" "Read,Write,Bash" "--strict-mcp-config" "--mcp-config" "{\"mcpServers\":{}}"
   "--no-session-persistence" "--output-format" "json"])

(defn harness-argv
  "The argv for one harness, every element a plain string. :interactive is the
   pane launch with the start text as the first message; :smoke is one
   print-mode run with `text` as the message. claude and grok take the assembled
   prompt as a system prompt; codex and copilot have no such flag, so for them
   the prompt's content leads the message."
  [ctx row bin prompt mode text]
  (let [wt (:worktree-path row)
        extra (task-lib/extra-argv row)
        message (if (= mode :interactive) (start-text ctx row) text)
        prompt-text (slurp (str prompt))
        led (str prompt-text "\n\n" message)
        name (str "sk " (:session row))]
    (vec
     (case (if (task-lib/lane-agents (:harness row)) "claude" (:harness row))
       ;; A lane script execs claude with its own flags and appends ours, and
       ;; the parser takes the last occurrence — so this same argv, handed to a
       ;; lane, inherits the lane's model, effort, MCP set and SSO wrap while
       ;; still overriding the two flags the swarm has to own.
       ;;
       ;; --append-system-prompt-file is NOT one of them any more. Last-wins
       ;; applies to it too, so passing ours REPLACED the lane's brief rather
       ;; than layering over it. The file written by write-prompt! now carries
       ;; the lane's own text ahead of the swarm's, so the flag stays single
       ;; and nothing is dropped.
       "claude" (concat ["env" "CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN=1" bin]
                        (when (= mode :smoke) claude-print-flags)
                        ["--append-system-prompt-file" (str prompt)
                         "--settings" (str (write-hook-settings! ctx row))
                         ;; The task folder IS the plugin root — its manifest is
                         ;; at <task>/.claude-plugin/plugin.json and its
                         ;; components at <task>/agents and <task>/skills (see
                         ;; task-lib's install-plugin!). Naming the path here is
                         ;; what replaced copying the skill and the subagent
                         ;; into every worktree, which meant writing into repos
                         ;; the swarm does not own.
                         ;;
                         ;; Two argv slots, built here rather than declared on a
                         ;; `roles` line: that line is whitespace-split twice, so
                         ;; a path with a space in it would arrive as two flags
                         ;; and a quoted one would arrive with its quotes. The
                         ;; flag is repeatable (`--plugin-dir A --plugin-dir B`)
                         ;; rather than last-wins, so a lane's own plugins load
                         ;; alongside this one instead of losing to it.
                         ;;
                         ;; The task folder being the plugin root means a plugin
                         ;; loader reads THREE more names inside it than
                         ;; install-plugin! writes: `hooks/hooks.json`,
                         ;; `commands/`, and `.mcp.json`. `hooks/` already
                         ;; exists here and holds `<session>.settings.json`, so
                         ;; the collision is one filename away — anything that
                         ;; later writes `<task>/hooks/hooks.json` is writing
                         ;; hooks into every role of that task, not settings for
                         ;; one session. Roles can write into the task folder,
                         ;; so treat those three names as load-bearing.
                         "--plugin-dir" (str (:task-dir ctx))]
                        ;; A lane already declares its own permission posture —
                        ;; cc_auto bypasses, cc_control screens — and restating
                        ;; ours would collapse the two into one choice.
                        (when-not (task-lib/lane-agents (:harness row))
                          ["--permission-mode" "bypassPermissions"])
                        (when (= mode :interactive) ["-n" name])
                        extra
                        ["--" message])
       "codex" (concat [bin]
                       (if (= mode :interactive) ["-C" wt "--no-alt-screen" "--yolo"] ["exec" "--skip-git-repo-check" "-C" wt])
                       extra [led])
       "copilot" (concat [bin "-C" wt "--no-alt-screen" "--name" name "--yolo"] extra
                         [(if (= mode :interactive) "-i" "-p") led])
       "grok" (concat [bin "--cwd" wt "--permission-mode" "bypassPermissions"] extra
                      ["--minimal" "--rules" prompt-text "--verbatim" message])
       (throw (ex-info (str "no launch command for harness " (:harness row)) {}))))))

(defn launch-script [ctx row prompt]
  (str "#!/bin/bash\n"
       "# swarmkhazad launch for session " (:session row) " of task " (:task-id ctx) " — generated by open\n"
       "export SWARMFORGE_ROLE=" (sq (:role row)) "\n"
       "export SWARMKHAZAD_SESSION=" (sq (:session row)) "\n"
       "export SWARMKHAZAD_REPO=" (sq (str (:repo row))) "\n"
       "export SWARMKHAZAD_TASK_ID=" (sq (:task-id ctx)) "\n"
       "export SWARMKHAZAD_TASK_DIR=" (sq (:task-dir ctx)) "\n"
       "export PATH=" (sq (str (:bin-dir ctx))) ":" (sq (str script-dir)) ":\"$PATH\"\n"
       "cd " (sq (:worktree-path row)) " || exit 1\n"
       "exec " (str/join " " (map sq (harness-argv ctx row (shim-path ctx (:harness row)) prompt :interactive nil))) "\n"))

(defn launch-session!
  "Write prompts/<session>.launch.sh and type `bash <path>` into its pane.
   The command itself is typed, not the launch: a tty still in canonical mode
   (zsh not yet up) drops everything past 1024 bytes, and a full launch line with
   temp-dir paths is longer than that (RAN: the pane showed a truncated command
   and no launch)."
  [ctx rows row]
  (let [prompt (write-prompt! ctx rows row)
        script (fs/path (:prompts-dir ctx) (str (:session row) ".launch.sh"))]
    (spit (str script) (launch-script ctx row prompt))
    (fs/set-posix-file-permissions script "rwxr-xr-x")
    (tmux! ctx "send-keys" "-t" (task-lib/session-name (:session row)) (str "bash " (sq script)) "Enter")
    (str script)))

;; ---------------------------------------------------------------- board + mail

(defn queue-new-task-note!
  "The task's opening mail: from (New Task) to one session of the first role.
   Its created_at is the wall-clock start of the task.

   One call per session, not one per role. `handoffd` holds a git_handoff until
   EVERY session of the sender's role has handed off, because delivering the
   first sibling's the moment it landed started the next role on trees its
   siblings were still writing. Seeding one session of a three-repo role leaves
   that join unable to clear: the other two have empty inboxes and nothing
   downstream will ever address them, so their only ways out are to sit forever
   or to decide for themselves that the brief is their instruction. Task
   gobelhygine took the second — two implement sessions worked with no mail and
   wrote a decision.md line saying why, and the constitution's `NO_TASK means
   stop and wait` was correct advice that would have stalled the task."
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

;; ---------------------------------------------------------------- open gates

(defn unmeasured-bars
  "Bars carrying a `measure:` command that no role in this task will run.

   metrics.md declares the bars, `run_evidence.bb` executes them into
   evidence/<bar>.txt, and one role's prompt is what tells it to. A task whose
   roles omit that role declares bars nobody measures — and afterwards an
   unmeasured bar is not visibly different from one that ran and passed.

   Measured: a two-role task (implement, review) named five `measure:`
   commands. evidence/ held nothing but two files a role had written by hand,
   and the merge verdict reported the bars as missing evidence rather than as
   never configured, which sends a human looking for a broken runner instead
   of a missing role.

   Keyed on the PROMPT, not on a role being named `run`: the prompt is what
   invokes the runner, so renaming the role cannot make this wrong in either
   direction."
  [ctx roles]
  (let [metrics (if (fs/regular-file? (:metrics-file ctx))
                  (slurp (str (:metrics-file ctx)))
                  "")
        ;; A still-angle-bracketed command is a template placeholder, not a bar
        ;; anyone meant to run. Counting it refused a freshly scaffolded task,
        ;; and the scaffold is the one file the operator has not written yet.
        placeholder? #(re-find #"<[^>]+>" (str %))
        commanded (remove #(placeholder? (:command %))
                          (filter :command (run-evidence/bars ctx metrics)))]
    (when (and (seq commanded)
               (not (some #(str/includes? (stage-prompt (:role %)) "run_evidence.bb")
                          roles)))
      (vec commanded))))

(defn require-measurable!
  "Refuse to open a task whose quantitative bars have no one to measure them.

   Refusing rather than warning, because the failure leaves no trace: a bar
   that never ran writes no evidence file, and neither does one that ran and
   produced nothing."
  [ctx roles]
  (when-let [orphans (unmeasured-bars ctx roles)]
    (throw (ex-info
            (str "metrics.md declares " (count orphans)
                 " measure: command(s) that no role will run:\n"
                 (str/join "\n" (for [b orphans]
                                  (str "  - " (:name b) " — measure: `" (:command b) "`")))
                 "\n\nAdd a role whose prompt runs run_evidence.bb — the `run` role —"
                 "\nor move these under ## Qualitative, which names a human judge.")
            {:exit 1}))))

(defn- dir-kb [p]
  (let [r (process/sh {:continue true} "du" "-sk" (str p))]
    (when (zero? (:exit r))
      (parse-long (or (first (str/split (str/trim (str (:out r))) #"\s+")) "0")))))

(def disk-warn-kb
  "Warn above 5 GB of task folders."
  (* 5 1024 1024))

(defn disk-warning
  "One line when the task folders have grown past the threshold, naming the
   worst task AND its biggest child directory, or nil.

   The child is what makes it actionable, because the two causes have
   different fixes: `worktrees/` is a stale task that `close --reclaim` gives
   back, while `tmp/` is a role that cloned a repo into scratch, which nothing
   in the system ever reclaims.

   Measured over the WHOLE task folder, not over worktrees/ alone. The 2.2G
   task was 2.2G of tmp/ and 8.5M of worktrees/, so a worktrees-only sum
   reported the worst offender as the smallest."
  []
  (let [root (task-lib/tasks-dir)]
    (when (fs/directory? root)
      (let [gb (fn [kb] (format "%.1fG" (/ (double kb) 1024 1024)))
            tasks (vec (for [d (fs/list-dir root)
                             :when (fs/directory? d)
                             :let [kb (dir-kb d)]
                             :when kb]
                         {:name (str (fs/file-name d)) :kb kb :path d}))
            total (reduce + 0 (map :kb tasks))]
        (when (> total disk-warn-kb)
          (let [worst (last (sort-by :kb tasks))
                child (when worst
                        (last (sort-by :kb (for [c (fs/list-dir (:path worst))
                                                 :when (fs/directory? c)
                                                 :let [kb (dir-kb c)]
                                                 :when kb]
                                             {:name (str (fs/file-name c)) :kb kb}))))]
            (str "swarmkhazad: task folders hold " (gb total)
                 " across " (count tasks) " task(s)"
                 (when worst (str "; largest is " (:name worst) " at " (gb (:kb worst))))
                 (when child (str ", mostly " (:name child) "/ at " (gb (:kb child))))
                 ".\n  worktrees/ → `swarmkhazad close <task> --reclaim`."
                 "  tmp/ → role scratch, nothing reclaims it.")))))))

(defn write-runtime-stamp!
  "Record which copy of swarmkhazad opened this task, and at what commit.

   A task's hooks and its daemon are launched with the absolute path of
   whichever checkout ran `open`, and that path is very often a development
   worktree. Two things follow, and both happened in one run. The daemon keeps
   the code it loaded at boot while the hooks pick up new code on every
   invocation, so a task can execute two versions at once — measured:
   handoffd.bb was rewritten at 07:59, inside a daemon lifetime of 07:26 to
   08:11. And reviewing that run afterwards meant reading four file mtimes to
   work out which findings were real and which were already fixed.

   So: stamp it. This does not pin the code — a snapshot per task would, at
   the cost of a copy and a staleness question of its own — but it makes the
   question answerable instead of archaeological, which is the half that
   actually cost time."
  [ctx]
  (let [dir (str script-dir)
        g (fn [& args] (let [r (apply process/sh {:continue true :dir dir} "git" args)]
                         (when (zero? (:exit r)) (str/trim (str (:out r))))))
        sha (or (g "rev-parse" "HEAD") "unknown")
        branch (or (g "rev-parse" "--abbrev-ref" "HEAD") "unknown")
        dirty? (not (str/blank? (or (g "status" "--porcelain") "")))
        stamp (fs/path (:state-dir ctx) "runtime.tsv")]
    (fs/create-dirs (:state-dir ctx))
    (spit (str stamp)
          (str/join "" (for [[k v] [["script_dir" dir]
                                    ["branch" branch]
                                    ["commit" sha]
                                    ["dirty" (str dirty?)]
                                    ["opened_at" (str (java.time.Instant/now))]]]
                         (str k "\t" v "\n"))))
    (when dirty?
      (binding [*out* *err*]
        (println (str "swarmkhazad: opening from a checkout with uncommitted changes ("
                      dir " on " branch " @ " sha ")."
                      "\n  The daemon keeps the code it boots with while hooks re-read it"
                      " per call, so editing\n  these scripts mid-run makes one task execute"
                      " two versions. Recorded in " stamp "."))))
    stamp))

(defn open!
  "Open the swarm for task-id. Returns the ctx plus :sessions and :commands.

   The board card is a ROLE's — a lane is a stage of the work, and a role
   working three repos is still in one stage. The opening note is a SESSION's,
   because mail is delivered to a pane."
  [task-id]
  (let [ctx (task-lib/task-ctx task-id)
        {:keys [roles repos sessions]} (task-lib/prepare! ctx)]
    (check-dependencies!)
    ;; Before anything is spawned: a contract nobody can measure is a contract
    ;; that will read as met.
    (require-measurable! ctx roles)
    (write-runtime-stamp! ctx)
    (when-let [w (disk-warning)]
      (binding [*out* *err*] (println w)))
    (resolve-harnesses! ctx roles)
    (stop-handoffd! ctx)
    (kill-server! ctx)
    (boot-sessions! ctx sessions)
    (write-shims! ctx)
    (lock-truth! ctx)
    (trust-worktrees! ctx sessions)
    (when-not (board-lib/card-lane ctx task-id)
      ;; One card for the role — a lane is a stage of the work, and a role
      ;; holding three repos is still one stage. One note per SESSION of that
      ;; role: mail is delivered to a pane, and every pane of the first role
      ;; has work the moment the task opens.
      (let [lane (:role (first sessions))]
        (board-lib/create-card! ctx task-id lane)
        (doseq [row sessions :when (= lane (:role row))]
          (queue-new-task-note! ctx (:session row)))))
    (start-handoffd! ctx)
    (assoc ctx :roles roles :repos repos :sessions sessions
           :commands (mapv #(launch-session! ctx sessions %) sessions))))

;; ---------------------------------------------------------------- smoke

(defn smoke-prompt
  "The smoke asks the session to note itself: a one-session task has no other
   recipient."
  [ctx session]
  (str "Smoke test for session " session ". Do exactly these steps and nothing else.\n"
       "1. Read " (:goal-file ctx) " (one Read call).\n"
       "2. Write the file " (fs/path (:tmp-dir ctx) (str "smoke-" session ".txt")) " with exactly these four lines:\n"
       "type: note\nto: " session "\npriority: 50\nmessage: smoke from " session "\n"
       "3. Run: swarm_handoff.bb " (fs/path (:tmp-dir ctx) (str "smoke-" session ".txt")) "\n"
       "4. Reply with the single line HANDOFF_OK if that command printed HANDOFF QUEUED, else the error text."))

(defn smoke-note
  "The note this session's smoke queued, if any."
  [ctx session]
  (some (fn [f]
          (let [h (:headers (handoff-lib/parse-message f))]
            (when (and (= "note" (get h "type")) (= session (get h "from"))
                       (str/starts-with? (or (get h "message") "") "smoke from"))
              f)))
        (handoff-lib/handoff-files (handoff-lib/outbox-dir ctx session))))

(defn smoke-role!
  "Launch, read goal.md, send one note, exit clean — through the shim."
  [ctx rows row]
  (let [role (:session row)
        prompt (write-prompt! ctx rows row)
        argv (harness-argv ctx row (shim-path ctx (:harness row)) prompt :smoke (smoke-prompt ctx role))
        started (System/currentTimeMillis)
        p (process/process argv {:dir (:worktree-path row)
                                 :out :string :err :string
                                 :extra-env {"SWARMFORGE_ROLE" (:role row)
                                             "SWARMKHAZAD_SESSION" (:session row)
                                             "SWARMKHAZAD_REPO" (str (:repo row))
                                             "SWARMKHAZAD_TASK_ID" (:task-id ctx)
                                             "SWARMKHAZAD_TASK_DIR" (str (:task-dir ctx))
                                             "PATH" (str (:bin-dir ctx) ":" script-dir ":" (System/getenv "PATH"))}})
        done (deref p smoke-timeout-ms nil)
        _ (when-not done (process/destroy-tree p))
        result (if done @p {:exit -1 :out "" :err "timed out"})
        json-out (try (json/parse-string (:out result)) (catch Exception _ nil))
        models (vec (keys (get json-out "modelUsage")))
        expected (:model-main (get (task-lib/read-vendors) (:model row)))
        note (smoke-note ctx role)
        ok? (and done (zero? (:exit result)) (some? note)
                 (or (nil? expected) (some #{expected} models))
                 (or (not= "claude" (:harness row)) (some? json-out)))]
    (when note (fs/delete note))
    {:role role :repo (:repo row) :harness (:harness row) :vendor (:model row) :ok ok?
     :exit (:exit result) :seconds (quot (- (System/currentTimeMillis) started) 1000)
     :models models :expected expected :note (some? note)
     :cost (get json-out "total_cost_usd") :turns (get json-out "num_turns")
     :detail (when-not ok? (str/trim (str (:err result) "\n" (subs (or (:out result) "") 0 (min 600 (count (or (:out result) "")))))))}))

(defn smoke!
  "Prepare the task (no tmux, no daemon) and smoke every declared role.

   One session per role, not every session: the smoke proves harness
   resolution, the vendor pin and the mail round-trip, none of which vary by
   repo — and a role over four repos would otherwise pay for four launches to
   learn the same thing once."
  [task-id]
  (let [ctx (task-lib/task-ctx task-id)
        {:keys [roles sessions]} (task-lib/prepare! ctx)
        one-each (mapv #(first (filter (fn [r] (= (:role %) (:role r))) sessions)) roles)]
    (check-dependencies!)
    (resolve-harnesses! ctx roles)
    (write-shims! ctx)
    (doall (pmap #(smoke-role! ctx sessions %) one-each))))

(defn pushed?
  "Whether the source already has this branch on its remote, at the same commit.

   The one question worth asking before deleting a worktree: work that is on
   origin can be got back, and work that is not cannot. A task that shipped
   answers yes for every repo, which is the case task.md §10 describes.

   A branch that is no longer in this checkout answers yes too, because there is
   nothing left to lose. That case is reached now that the question is asked
   once and up front for every repo a task has, including the ones an earlier
   `close --reclaim` already deleted the branch from. Asking git for a ref that
   is not there exits non-zero and `task-lib/git` throws, so without this a
   second `close --reclaim` died where it used to report \"already gone\"."
  [source branch]
  (let [remote (str "refs/remotes/origin/" branch)
        resolves? (fn [ref]
                    (task-lib/git-ok? source "rev-parse" "--verify" "--quiet" (str ref "^{commit}")))]
    (boolean (or (not (resolves? branch))
                 (and (resolves? remote)
                      (= (task-lib/git source "rev-parse" (str remote "^{commit}"))
                         (task-lib/git source "rev-parse" (str branch "^{commit}"))))))))

(defn unpushed-repos
  "The repos whose task branch still holds commits origin has never seen.

   Asked BEFORE anything is torn down. reclaim! asks the same question per repo
   and answers it by keeping that worktree, which is right for `close`: the
   task folder survives, and the branch name is still written in it. `delete`
   removes the folder, so the same answer there would strip the only pointer to
   those commits. It refuses instead."
  [ctx]
  (let [branch (task-lib/task-branch ctx)
        sources (into {} (for [r (try (task-lib/parse-repos ctx) (catch Exception _ nil))]
                           [(:name r) (:path r)]))]
    (vec (distinct (for [row (task-lib/read-sessions-tsv ctx)
                         :let [source (get sources (:repo row))]
                         :when (and source (not (pushed? source branch)))]
                     (:repo row))))))

(defn reclaim!
  "Give the disk back: clean each worktree, remove it, drop the task branch from
   the source it was added to. Returns a line per repo, for the caller to print.

   This is where the space is. A worktree is a checkout, and a checkout that has
   built anything is mostly untracked build output — 6.4G across three of them
   in the task that started all this, none of it in git. Nothing else removes
   it: `reap` only fires on tasks whose folder is already gone, so a task folder
   that is kept for its notes keeps three checkouts alive with it.

   A worktree holding commits the source's remote has never seen is KEPT, and
   said so, because that is also what an unpushed day of work looks like.
   `--force` takes it anyway.

   Not the same question `reap` asks. This one is `pushed?` — the branch is at
   the same commit as `origin/<branch>`. reap asks whether the branch is merged
   into its base and how far ahead it is otherwise. A branch merged but never
   pushed, or pushed to a fork, is answered differently by the two, and the two
   are asked at different times: this one about a live task, reap's about a
   branch whose task folder is already gone."
  [ctx force?]
  (let [rows (task-lib/read-sessions-tsv ctx)
        branch (task-lib/task-branch ctx)
        by-repo (into {} (for [r rows :when (:repo r)] [(:repo r) r]))
        sources (into {} (for [r (try (task-lib/parse-repos ctx) (catch Exception _ nil))]
                           [(:name r) (:path r)]))
        ;; One `pushed?` decision for the whole codebase, and `delete` asks the
        ;; same function before it tears anything down.
        ;; A set, never nil: the cond branch below calls it as a function, and
        ;; `(nil repo)` is a NullPointerException in the middle of a reclaim.
        kept (if force? #{} (set (unpushed-repos ctx)))]
    (vec
     (for [[repo row] (sort by-repo)
           :let [worktree (:worktree-path row)
                 source (get sources repo)]]
       (cond
         (not (and worktree (fs/directory? worktree)))
         (str "  " repo ": already gone")

         (kept repo)
         (str "  " repo ": kept — " branch " is not on origin; --force to remove it anyway")

         :else
         (do
           ;; `worktree remove` refuses a tree with untracked files in it, and a
           ;; worktree that has built anything is nothing but untracked files.
           (process/sh {:continue true :dir (str worktree)} "git" "clean" "-xdf")
           (process/sh {:continue true :dir (str worktree)} "git" "worktree" "remove" "--force" (str worktree))
           (when (fs/directory? worktree) (fs/delete-tree worktree))
           (when source
             (process/sh {:continue true :dir (str source)} "git" "worktree" "prune")
             (process/sh {:continue true :dir (str source)} "git" "branch" "-D" branch))
           (str "  " repo ": worktree removed, " branch " deleted from " (or source "its source"))))))))

(defn close!
  "Tear the swarm down: archive panes, stop the daemon, kill the tmux server,
   drop the trust entries open added. With `reclaim?`, also give the disk back."
  ([task-id] (close! task-id false false))
  ([task-id reclaim? force?]
   (let [ctx (task-lib/task-ctx task-id)]
     (when (server-up? ctx)
       (handoff-lib/archive-all! ctx))
     (stop-handoffd! ctx)
     (kill-server! ctx)
     (untrust-worktrees! ctx (task-lib/read-sessions-tsv ctx))
     (when reclaim?
       (println "reclaiming:")
       (doseq [line (reclaim! ctx force?)] (println line)))
     ctx)))

(defn delete!
  "Remove the task and everything it left behind: the swarm, its worktrees and
   branches, its telemetry, and the folder itself.

   `close --reclaim` gives the disk back but deliberately keeps the folder — the
   goal, the decisions, the mail — and it has no reach into the metrics store.
   So a task finished months ago still sits on the board and still draws a line
   on the spend chart, with nothing behind the name to open. That is what this
   removes, and it is why the telemetry is part of it rather than a second step
   someone has to remember.

   Refuses while any repo holds unpushed commits, before touching anything.
   `--force` is the same override reclaim takes, and means the same thing.

   Returns a map the caller prints: {:removed bool :kept [repo] :metrics
   :forgotten|:refused|:unreachable}. The metrics key is a
   report, not a promise — a server that is not running keeps its series, and
   the operator has to be told that rather than shown a clean exit."
  [task-id force?]
  (let [ctx (task-lib/task-ctx task-id)]
    (if-not (fs/directory? (:task-dir ctx))
      ;; No folder, but the metrics store may still hold its series — a task
      ;; closed and reclaimed before this command existed leaves exactly that,
      ;; and it is most of what a long-running dashboard is charting. Forget it
      ;; and say so. A name with neither folder nor series is a typo, and stays
      ;; an error.
      (if (telemetry/has-series? task-id)
        {:removed true :orphan true :kept [] :reclaimed []
         :metrics (telemetry/forget-task! task-id)}
        (throw (ex-info (str "no such task: " task-id) {:task-id task-id})))
      (let [kept (when-not force? (unpushed-repos ctx))]
        (if (seq kept)
          {:removed false :kept (vec kept)}
          ;; `close --reclaim` IS the teardown — archive, stop the daemon, kill
          ;; the server, drop the trust entries, reclaim the worktrees — and it
          ;; prints the reclaim lines itself. Repeating those four calls here is
          ;; how a fifth teardown step added to `close!` would come to be
          ;; silently skipped by `delete`.
          (let [ctx (close! task-id true force?)
                metrics (telemetry/forget-task! task-id)]
            (fs/delete-tree (:task-dir ctx))
            {:removed true :kept [] :metrics metrics}))))))
