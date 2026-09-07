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
  "prompts/<session>.md: the session header, the constitution, the stage prompt.

   The stage prompt is the ROLE's — every repo gets the same instructions for
   what implement or review means; only the header and the draft names differ."
  [ctx rows row]
  (fs/create-dirs (:prompts-dir ctx))
  (let [file (fs/path (:prompts-dir ctx) (str (:session row) ".md"))]
    (spit (str file)
          (str (role-header ctx rows row)
               (own-drafts (str (slurp (str (fs/path prompts-src-dir "constitution.prompt")))
                                "\n## Stage: " (:role row) "\n\n"
                                (stage-prompt (:role row)))
                           rows row)))
    file))

;; ---------------------------------------------------------------- hooks

(def contract-hook (str (fs/path script-dir "hooks" "run-contract.sh")))

(defn lock-truth!
  "goal.md and metrics.md are read-only from the moment a swarm opens. The
   SessionStart hook repeats this per role; the PreToolUse hook stops the chmod."
  [ctx]
  (doseq [f [(:goal-file ctx) (:metrics-file ctx)]]
    (when (fs/exists? f)
      (fs/set-posix-file-permissions f "r--r--r--"))))

(def goal-judge (str (fs/path script-dir "goal_judge.bb")))

(def hook-settings
  "The settings a claude role loads via --settings: the contract hook on
   SessionStart (startup, resume, compact) and on every file or shell tool, and
   the goal judge on Stop."
  {:hooks {:SessionStart [{:hooks [{:type "command" :command contract-hook :timeout 10}]}]
           :PreToolUse [{:matcher "Edit|Write|MultiEdit|NotebookEdit|Bash"
                         :hooks [{:type "command" :command contract-hook :timeout 10}]}]
           :Stop [{:hooks [{:type "command" :command goal-judge :timeout 180}]}]}})

(defn write-hook-settings! [ctx row]
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
     (case (:harness row)
       "claude" (concat ["env" "CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN=1" bin]
                        (when (= mode :smoke) claude-print-flags)
                        ["--append-system-prompt-file" (str prompt)
                         "--settings" (str (write-hook-settings! ctx row))
                         "--permission-mode" "bypassPermissions"]
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
  "Open the swarm for task-id. Returns the ctx plus :sessions and :commands.

   The board card is a ROLE's — a lane is a stage of the work, and a role
   working three repos is still in one stage. The opening note is a SESSION's,
   because mail is delivered to a pane."
  [task-id]
  (let [ctx (task-lib/task-ctx task-id)
        {:keys [roles repos sessions]} (task-lib/prepare! ctx)]
    (check-dependencies!)
    (resolve-harnesses! ctx roles)
    (stop-handoffd! ctx)
    (kill-server! ctx)
    (boot-sessions! ctx sessions)
    (write-shims! ctx)
    (lock-truth! ctx)
    (trust-worktrees! ctx sessions)
    (when-not (board-lib/card-lane ctx task-id)
      (board-lib/create-card! ctx task-id (:role (first sessions)))
      (queue-new-task-note! ctx (:session (first sessions))))
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

(defn close!
  "Tear the swarm down: archive panes, stop the daemon, kill the tmux server,
   drop the trust entries open added."
  [task-id]
  (let [ctx (task-lib/task-ctx task-id)]
    (when (server-up? ctx)
      (handoff-lib/archive-all! ctx))
    (stop-handoffd! ctx)
    (kill-server! ctx)
    (untrust-worktrees! ctx (task-lib/read-sessions-tsv ctx))
    ctx))
