(ns swarmkhazad.contract-test
  "scripts/hooks/run-contract.sh: SessionStart injects and locks the truth,
   PreToolUse denies every way of mutating it and lets everything else through.
   Fires hook JSON at the script the way Claude Code does."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (str (fs/cwd)))
(def hook (str (fs/path repo-root "scripts" "hooks" "run-contract.sh")))

(defn fire
  "Run the hook with stdin json and the task env; returns {:exit :out :json}."
  [task-dir env-extra payload]
  (let [r (process/sh {:continue true
                       :in (json/generate-string payload)
                       :extra-env (merge {"SWARMKHAZAD_TASK_DIR" (str task-dir)
                                          "SWARMKHAZAD_TASK_ID" "t-hook"
                                          "SWARMFORGE_ROLE" "implement"}
                                         env-extra)}
                      "bash" hook)]
    {:exit (:exit r) :out (:out r)
     :json (try (json/parse-string (:out r)) (catch Exception _ nil))}))

(defn session-start [task-dir & [source]]
  (fire task-dir {} {"hook_event_name" "SessionStart" "source" (or source "startup") "cwd" (str task-dir)}))

(defn context [result]
  (get-in (:json result) ["hookSpecificOutput" "additionalContext"]))

(defn edit [task-dir tool path]
  (fire task-dir {} {"hook_event_name" "PreToolUse" "tool_name" tool "cwd" (str task-dir)
                     "tool_input" {"file_path" path}}))

(defn bash [task-dir cwd command]
  (fire task-dir {} {"hook_event_name" "PreToolUse" "tool_name" "Bash" "cwd" (str cwd)
                     "tool_input" {"command" command}}))

(defn denied? [result]
  (= "deny" (get-in (:json result) ["hookSpecificOutput" "permissionDecision"])))

(defn allowed? [result]
  (and (zero? (:exit result)) (str/blank? (:out result))))

(defn perms [path]
  (str/trim (:out (process/sh "stat" "-f" "%Lp" (str path)))))

(defn fresh-task! [dir]
  (fs/create-dirs dir)
  (doseq [f ["goal.md" "metrics.md"]] (when (fs/exists? (fs/path dir f)) (fs/set-posix-file-permissions (fs/path dir f) "rw-r--r--")))
  (spit (str (fs/path dir "goal.md")) "# t-hook — probe\n\n## Goal\n- [ ] implement — GOAL-MARKER-7f3a\n\n## Not-goal\n- nothing\n\n## Hints\n- /x\n")
  (spit (str (fs/path dir "metrics.md")) "# t-hook — bars\n\n## Quantitative\n- suite — bar: 1/1 — measure: `true`  METRIC-MARKER-9c2e\n"))

(defn with-task [f]
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-contract."})
        task (fs/path sandbox "tasks" "t-hook")]
    (try
      (fresh-task! task)
      (f (str (fs/canonicalize task)) (str sandbox))
      (finally
        (doseq [p (fs/glob sandbox "**")] (try (fs/set-posix-file-permissions p (if (fs/directory? p) "rwxr-xr-x" "rw-r--r--")) (catch Exception _)))
        (fs/delete-tree sandbox)))))

(deftest no-task-dir-means-silent-exit
  (let [r (process/sh {:continue true :in "{\"hook_event_name\":\"SessionStart\"}"
                       :extra-env {"SWARMKHAZAD_TASK_DIR" ""}} "bash" hook)]
    (is (zero? (:exit r)))
    (is (str/blank? (:out r))))
  (with-task
    (fn [task _]
      (let [r (process/sh {:continue true :in "not json" :extra-env {"SWARMKHAZAD_TASK_DIR" task}} "bash" hook)]
        (is (zero? (:exit r)) "garbage stdin fails open")))))

(deftest session-start-injects-the-truth-locks-it-and-creates-the-bullet-files
  (with-task
    (fn [task _]
      (let [r (session-start task)
            ctx (context r)]
        (is (= "SessionStart" (get-in (:json r) ["hookSpecificOutput" "hookEventName"])))
        (is (str/starts-with? ctx "TASK CONTRACT · t-hook"))
        (is (str/includes? ctx "role implement"))
        (is (str/includes? ctx "GOAL-MARKER-7f3a") "goal.md verbatim")
        (is (str/includes? ctx "METRIC-MARKER-9c2e") "metrics.md verbatim")
        (is (str/includes? ctx "draft-implement.md") "names this role's draft")
        (is (str/includes? ctx "never tick a box"))
        (is (str/includes? ctx "escalation.md line, never an edit to the bar"))
        (doseq [f ["goal.md" "metrics.md"]]
          (is (= "444" (perms (fs/path task f))) (str f " locked")))
        (doseq [f ["decision.md" "gotcha.md" "escalation.md"]]
          (is (fs/regular-file? (fs/path task f)) (str f " created")))
        (is (not (str/includes? ctx "## decision.md")) "empty bullet files are not injected as sections"))
      (testing "bullets and the role's own draft come along; compact is flagged"
        (spit (str (fs/path task "decision.md")) "- **DECISION-MARKER-1b4d** — because\n")
        (spit (str (fs/path task "draft-implement.md")) "DRAFT-MARKER-55aa\n")
        (let [ctx (context (session-start task "compact"))]
          (is (str/includes? ctx "DECISION-MARKER-1b4d"))
          (is (str/includes? ctx "## draft-implement.md"))
          (is (str/includes? ctx "DRAFT-MARKER-55aa"))
          (is (str/includes? ctx "Context was compacted"))))
      (testing "a task with no contract is called out and the partial truth is not injected"
        (fs/set-posix-file-permissions (fs/path task "metrics.md") "rw-r--r--")
        (fs/delete (fs/path task "metrics.md"))
        (let [ctx (context (session-start task))]
          (is (str/includes? ctx "HAS NO CONTRACT — missing: metrics.md"))
          (is (not (str/includes? ctx "GOAL-MARKER"))))))))

(deftest pre-tool-use-denies-every-mutation-of-the-truth-and-nothing-else
  (with-task
    (fn [task sandbox]
      (session-start task)
      (testing "file tools"
        (is (denied? (edit task "Edit" (str task "/goal.md"))))
        (is (denied? (edit task "Write" (str task "/metrics.md"))))
        (is (denied? (edit task "MultiEdit" "goal.md")) "relative to the tool cwd")
        (is (denied? (edit task "Edit" (str task "/../t-hook/goal.md"))) "dot-dot path")
        (is (denied? (edit task "Edit" (str task "/decision.md")))
            "the bullet files are note.bb's to write — the tag and the format come from it")
        (is (denied? (edit task "Write" (str task "/finding.md"))))
        (is (allowed? (edit task "Write" (str task "/draft-implement.md")))
            "a role's own write-up is its own to edit")
        (is (allowed? (edit task "Edit" (str sandbox "/elsewhere/goal.md"))) "a goal.md outside the task is not ours")
        (is (str/includes? (get-in (:json (edit task "Edit" (str task "/goal.md"))) ["hookSpecificOutput" "permissionDecisionReason"])
                           "escalation.md line")))
      (testing "bash mutations"
        (doseq [cmd ["chmod 644 $SWARMKHAZAD_TASK_DIR/goal.md"
                     (str "chmod 644 " task "/metrics.md")
                     (str "chmod -R 644 " task)
                     "sed -i '' 's/\\[ \\]/[x]/' goal.md"
                     "echo '- [x]' >> goal.md"
                     "echo x >goal.md"
                     (str "printf x | tee " task "/metrics.md")
                     (str "mv " task "/goal.md " task "/old.md")
                     (str "rm -f " task "/goal.md")
                     (str "python3 -c \"open('" task "/goal.md','w').write('x')\"")]]
          (is (denied? (bash task task cmd)) cmd)))
      (testing "the bullet files are locked in bash too, or the lock has a hole the shell walks through"
        (doseq [cmd ["printf -- '- **x** — y\\n' >> decision.md"
                     "chmod 644 escalation.md"
                     (str "echo x > " task "/finding.md")
                     (str "python3 -c \"open('" task "/gotcha.md','a').write('x')\"")]]
          (is (denied? (bash task task cmd)) cmd))
        (is (str/includes? (get-in (:json (bash task task "printf x >> decision.md"))
                                   ["hookSpecificOutput" "permissionDecisionReason"])
            "note.bb")
            "and the denial says what to run instead"))
      (testing "bash reads and unrelated commands pass"
        (doseq [cmd [(str "cat " task "/goal.md 2>/dev/null")
                     "grep -c '^- \\[ \\]' goal.md"
                     "sed -n 1,5p metrics.md"
                     "cat decision.md"
                     "grep -c FIND finding.md"
                     "git status --short"]]
          (is (allowed? (bash task task cmd)) cmd))
        (is (allowed? (bash task sandbox "echo x > goal.md")) "a goal.md in some other cwd is not ours"))
      (testing "denials are logged under the task's state, with the reason that fired"
        (let [log (fs/path task "state" "denials.jsonl")]
          (is (fs/regular-file? log))
          (let [entries (mapv #(json/parse-string %) (str/split-lines (slurp (str log))))
                reasons (mapv #(get % "reason") entries)]
            (is (every? #(= "implement" (get % "role")) entries))
            (is (some #(str/includes? % "truth") reasons) "the truth denials are logged")
            (is (some #(str/includes? % "note.bb") reasons) "and so are the bullet-file ones")
            (is (some #(= "Bash" (get % "tool")) entries))
            (is (some #(= "Edit" (get % "tool")) entries))))))))

(deftest the-truth-lock-holds-against-the-three-ways-round-it
  ;; `chmod 444` alone only stops a plain write; this hook is what stops the
  ;; chmod. Each case below defeated it, and each is what a role that cannot
  ;; meet a bar is most motivated to try.
  (with-task
    (fn [task sandbox]
      (session-start task)
      (testing "a name split by quotes is the name the shell will open"
        ;; The pre-filter and the match both read the raw word, so `go""al.md`
        ;; was a file the guard had never heard of. One word to the shell.
        (doseq [cmd ["chmod 644 go\"\"al.md"
                     "chmod 644 'goal'.md"
                     ;; Relative, so the command names neither the task dir nor
                     ;; any spelling the pre-filter recognises — the only thing
                     ;; standing between this and the file is the pre-filter
                     ;; reading the command with its quotes removed.
                     (str "chmod 644 " task "/go\"\"al.md")
                     (str "chmod 644 " task "/'goal'.md")
                     (str "rm -f " task "/met\"\"rics.md")]]
          (is (denied? (bash task task cmd)) cmd)))
      (testing "a tool the denylist never heard of is not therefore a reader"
        ;; patch, ed, ex, sh — none were on the mutating-verb list, and all of
        ;; them rewrite a file. The list is an allowlist of READERS now, so the
        ;; next tool nobody thought of denies instead of passing.
        (doseq [cmd [(str "patch " task "/goal.md < /tmp/p.diff")
                     (str "printf '1d\nw\n' | ed -s " task "/goal.md")
                     (str "ex -sc '1d|x' " task "/goal.md")
                     (str "sh -c 'echo x > " task "/goal.md'")
                     (str "install -m 644 /dev/null " task "/metrics.md")]]
          (is (denied? (bash task task cmd)) cmd)))
      (testing "a symlink beside the truth is the truth"
        ;; Only the DIRECTORY was canonicalized, so `notes.md -> goal.md` was an
        ;; unknown name in a trusted directory. Three allowed operations —
        ;; chmod the link, write the link — rewrote the acceptance criteria.
        (let [link (str task "/notes.md")]
          (fs/create-sym-link link (str task "/goal.md"))
          (is (denied? (bash task task (str "chmod 644 " link))))
          (is (denied? (bash task task (str "printf zzz > " link))))
          (is (denied? (edit task "Write" link)))
          (is (denied? (edit task "Edit" "notes.md")) "relative to the tool cwd, too")
          (fs/delete link))
        (let [link (str task "/notes.md")]
          (fs/create-sym-link link (str task "/decision.md"))
          (is (denied? (edit task "Write" link))
              "the bullet files are note.bb's to write, by whatever name they are reached")
          (fs/delete link)))
      (testing "and the reads the contract promises still pass"
        ;; The failure mode of an allowlist is over-denial, and a guard that
        ;; denies `grep bar metrics.md` is a guard someone switches off. Tested
        ;; on the arguments that look most like commands: a grep pattern and a
        ;; sed range.
        (doseq [cmd [(str "grep -n 'bar:' " task "/metrics.md")
                     (str "sed -n '1,5p' " task "/goal.md")
                     (str "awk '/Goal/{print}' " task "/goal.md")
                     (str "cat " task "/goal.md | wc -l")
                     (str "diff " task "/goal.md " task "/metrics.md")
                     (str "head -20 " task "/goal.md")
                     "note.bb escalation 'a claim' 'a why'"]]
          (is (allowed? (bash task task cmd)) cmd))
        (is (allowed? (bash task sandbox "patch elsewhere/goal.md < /tmp/p.diff"))
            "and a goal.md outside the task is still not ours")))))

(deftest the-truth-lock-holds-against-three-more-found-by-probing-for-them
  ;; sec3's three were not the whole set. These came from asking what else
  ;; reaches a file without spelling its name the way the guard expects.
  (with-task
    (fn [task sandbox]
      (session-start task)
      (testing "every spelling of the variable the shell would expand"
        ;; Only `$SWARMKHAZAD_TASK_DIR` and `${SWARMKHAZAD_TASK_DIR}` were
        ;; substituted, so the default- and error-forms — which expand to the
        ;; same directory — resolved to a path outside the task. RAN, allowed.
        (doseq [cmd ["chmod 644 ${SWARMKHAZAD_TASK_DIR:-}/goal.md"
                     "chmod 644 ${SWARMKHAZAD_TASK_DIR:?}/metrics.md"
                     "rm -f ${SWARMKHAZAD_TASK_DIR}/goal.md"
                     "rm -f $SWARMKHAZAD_TASK_DIR/goal.md"]]
          (is (denied? (bash task task cmd)) cmd)))
      (testing "a path this cannot see until the shell builds it"
        ;; The substitution has not run, so the word scan sees `/goal.md` — not
        ;; a path in the task dir — and nothing matched. A command that reaches
        ;; into the task folder AND builds a path is treated as naming the truth
        ;; rather than assumed innocent.
        (doseq [cmd [(str "chmod 644 $(echo " task ")/goal.md")
                     (str "chmod 644 `echo " task "`/goal.md")
                     "rm -f $(printf %s ${SWARMKHAZAD_TASK_DIR})/metrics.md"]]
          (is (denied? (bash task task cmd)) cmd)))
      (testing "tools that can write are not readers, however ordinary they look"
        ;; `git` and `bb` were on the reader allowlist and both write:
        ;; `git checkout -- goal.md` restores the file over itself.
        (doseq [cmd [(str "git -C " task " checkout -- goal.md")
                     (str "bb -e '(spit \"" task "/goal.md\" \"x\")'")
                     (str "echo " task "/goal.md | xargs chmod 644")
                     (str "find " task " -name goal.md -exec chmod 644 {} +")]]
          (is (denied? (bash task task cmd)) cmd)))
      (testing "and the work a role actually does is untouched"
        ;; The whole risk of an allowlist is over-denial, and scratch under
        ;; tmp/ plus the role's own draft are what a role writes all day.
        (doseq [cmd ["printf x > $SWARMKHAZAD_TASK_DIR/tmp/draft.txt"
                     "rm -rf $SWARMKHAZAD_TASK_DIR/tmp/draft.txt"
                     (str "mkdir -p " task "/tmp/work")
                     (str "printf x > " task "/draft-implement.md")
                     (str "git -C " sandbox " status --short")]]
          (is (allowed? (bash task task cmd)) cmd)))
      (testing "a hard link is the same file, by whatever name"
        ;; The reach `follow_link` cannot see: not a symlink, and named whatever
        ;; its maker chose. `stat` tells them apart. Closed at both ends —
        ;; nothing in a task can make one, AND an existing one is recognised.
        (doseq [cmd [(str "ln " task "/goal.md " task "/hard.md")
                     "ln goal.md hard.md"
                     (str "cp -l " task "/goal.md " task "/hard.md")
                     (str "link " task "/metrics.md " task "/hard.md")]]
          (is (denied? (bash task task cmd)) cmd))
        (let [hard (str task "/hard.md")]
          ;; Made outside the hook, which is the only way it can exist.
          (process/sh "ln" (str task "/goal.md") hard)
          (is (denied? (edit task "Write" hard))
              "a Write here rewrites goal.md, and the name gives nothing away")
          (is (denied? (bash task task (str "chmod 644 " hard))))
          (is (denied? (bash task task "printf x > hard.md")) "relative, too")
          (fs/delete hard))
        (let [hard (str task "/hardnote.md")]
          (process/sh "ln" (str task "/decision.md") hard)
          (is (denied? (edit task "Write" hard)) "and the bullet files the same way")
          (fs/delete hard)))
      (testing "a cd the hook cannot resolve does not move the truth out of reach"
        ;; Relative paths resolve against the cwd the TOOL CALL reported, and a
        ;; cd inside the command changes what they mean. A literal target the
        ;; hook can resolve was fine; `$SWARMKHAZAD_HOME/tasks/$SWARMKHAZAD_TASK_ID`
        ;; and a variable were not. RAN, both allowed.
        (doseq [cmd ["cd $SWARMKHAZAD_TASK_DIR/../t-hook && chmod 644 goal.md"
                     "D=$SWARMKHAZAD_TASK_DIR; cd $D; sed -i \"\" s/a/b/ goal.md"
                     (str "cd " task " && chmod 644 goal.md")
                     ;; Denied before this change too, but only because the
                     ;; hook's own unquoted `for w in $cmd` expanded the glob
                     ;; against the same disk — luck, not understanding.
                     (str "cd " task " && chmod 644 goa?.md")
                     (str "cd " task " && printf x >> decision.md")
                     ;; Joined to the operator, which is a different word to the
                     ;; scanner: `>>decision.md` is one token, so the previous
                     ;; word is `printf` and the redirect has to be read off the
                     ;; token itself.
                     (str "cd " task " && printf x >>decision.md")
                     (str "cd " task " && printf x >goal.md")]]
          (is (denied? (bash task sandbox cmd)) cmd))
        (testing "and reading after a cd is still reading"
          ;; `cd` is not a writer. Denying `cd <task> && cat goal.md` is exactly
          ;; the over-denial that gets a guard switched off — it was denied by
          ;; the first version of this rule, because `cd` was not on the reader
          ;; allowlist.
          (doseq [cmd [(str "cd " task " && cat goal.md")
                       (str "cd " task " && grep -n 'bar:' metrics.md")
                       (str "cd " task " && head -5 goal.md")]]
            (is (allowed? (bash task sandbox cmd)) cmd)))))))

(defn tool-call
  "A PreToolUse payload for any tool name and any tool_input shape — `edit` and
   `bash` above only build the shapes the hook knows by name, which is exactly
   the assumption under test."
  [task-dir tool input]
  (fire task-dir {} {"hook_event_name" "PreToolUse" "tool_name" tool
                     "cwd" (str task-dir) "tool_input" input}))

(deftest a-tool-this-hook-has-never-heard-of-does-not-get-a-free-pass
  ;; The tool dispatch named the writers — Edit, Write, MultiEdit, NotebookEdit,
  ;; Bash — and waved everything else through before looking at a path. The same
  ;; denylist mistake as the verb list, one level up, and a live one: roles load
  ;; the operator's whole ~/.claude.json, so a filesystem MCP server is a tool
  ;; that exists. RAN: `mcp__fs__write` with the path nested under
  ;; `tool_input.edits[0].path` was allowed without the path being read once.
  (with-task
    (fn [task _]
      (session-start task)
      (testing "an MCP write is asked the same question the file tools are asked"
        (is (denied? (tool-call task "mcp__fs__write"
                                {"edits" [{"path" (str task "/goal.md") "new" "x"}]}))
            "nested two levels deep, and found without a schema for this tool")
        (is (denied? (tool-call task "mcp__fs__write" {"path" (str task "/metrics.md")})))
        (is (denied? (tool-call task "mcp__fs__write" {"path" (str task "/decision.md")}))
            "the bullet files too — note.bb is the writer whatever tool is asking")
        (is (denied? (tool-call task "ApplyPatch" {"target" "goal.md"}))
            "relative to the tool cwd, like everywhere else")
        (is (denied? (tool-call task "mcp__fs__write" {"path" (str task "/../t-hook/goal.md")}))))
      (testing "and the reads the contract promises still pass"
        ;; The catch-all can be strict only because the readers are named. A
        ;; role must always be able to read its own truth.
        (is (allowed? (tool-call task "Read" {"file_path" (str task "/goal.md")})))
        (is (allowed? (tool-call task "Grep" {"pattern" "bar" "path" (str task "/metrics.md")})))
        (is (allowed? (tool-call task "Glob" {"pattern" (str task "/*.md")})))
        (is (allowed? (tool-call task "mcp__linear-server__get_issue" {"id" "MITH-1"}))
            "and an unrelated MCP call names nothing of ours")
        (is (allowed? (tool-call task "mcp__fs__write" {"path" (str task "/draft-implement.md")}))
            "a role's own write-up is its own to write, by any tool")))))


(deftest the-guard-does-not-deny-the-swarms-own-mail-loop
  ;; Found in the first live run, not by any test here: twelve denials in two
  ;; minutes, all of them the same command.
  ;;
  ;;   cd <task> && ls -la && ready_for_next.bb 2>&1
  ;;   -> "goal.md and metrics.md are the task's truth (chmod 444)…"
  ;;
  ;; That is the mail loop — the single most common command in the system, and
  ;; the first thing every role runs. Two independent causes, both of which
  ;; produce a denial that reads as the truth-lock working:
  ;;
  ;;   1. The reader allowlist named `note.bb` and none of the other helpers,
  ;;      so a role could write a bullet but not collect its own mail.
  ;;   2. The pipeline split is on `|;&`, so `ready_for_next.bb 2>&1` arrives as
  ;;      TWO segments and the second one's first word is the file descriptor
  ;;      `1`. Read as a command name, `1` is on no allowlist.
  ;;
  ;; An over-denial is not a safe failure here: it stops the swarm dead while
  ;; looking exactly like the guard doing its job.
  (with-task
    (fn [task _]
      (session-start task)
      (testing "the exact command the live run denied"
        (is (allowed? (bash task task (str "cd " task " && ls -la && ready_for_next.bb 2>&1")))))
      (testing "every helper a role is told to run"
        (doseq [c ["ready_for_next.bb"
                   "done_with_current.bb"
                   "swarm_handoff.bb tmp/draft.txt"
                   "run_evidence.bb"
                   "goal_judge.bb"
                   "note.bb finding 'a claim' 'a why'"]]
          (is (allowed? (bash task task (str "cd " task " && " c)))
              (str "helper denied: " c))))
      (testing "a redirect is not a command name"
        ;; Each of these splits on `&` or leaves a `>`-leading token where the
        ;; head-word scan looks for a command.
        (is (allowed? (bash task task "ready_for_next.bb 2>&1")))
        (is (allowed? (bash task task "ready_for_next.bb > /tmp/out.txt 2>&1")))
        (is (allowed? (bash task task "cat goal.md 2>&1 | head -3")))
        (is (allowed? (bash task task "ls -la 1>&2"))))
      (testing "and the lock still holds against every way round it"
        ;; The reason this test exists is that widening an allowlist is exactly
        ;; how a guard is quietly turned off. Each of these was a kill in the
        ;; mutation run and must stay one.
        (is (denied? (bash task task (str "cd " task " && chmod 644 goal.md"))))
        (is (denied? (bash task task (str "cd " task " && sed -i '' s/a/b/ goal.md"))))
        (is (denied? (bash task task (str "cd " task " && echo x >> escalation.md"))))
        (is (denied? (bash task task (str "cd " task " && patch goal.md < /tmp/p.diff"))))
        (is (denied? (bash task task (str "ready_for_next.bb 2>&1 && chmod 644 " task "/goal.md")))
            "a redirect earlier in the line does not buy the rest of it a pass")
        (is (denied? (bash task task (str "cd " task " && ready_for_next.bb > goal.md")))
            "an allowed reader is still not allowed to redirect over the truth")))))

(deftest read-only-inspectors-are-readers-and-writers-still-are-not
  ;; From a live run: a role inspecting the task folder with `find` was
  ;; refused, and the refusal text talked about editing goal.md — so it spent
  ;; turns probing the hook, then wrote the confusion into append-only
  ;; escalation.md, where the merge verdict later spent a `nit` on it.
  (with-task
    (fn [task _sandbox]
      (session-start task)

      (testing "read-only inspectors that name the truth are allowed"
        (is (allowed? (bash task task (str "cd " task " && find . -maxdepth 2 -type f"))))
        (is (allowed? (bash task task (str "du -sh " task "/goal.md"))))
        (is (allowed? (bash task task (str "cat " task "/goal.md"))))
        (is (allowed? (bash task task (str "cd " task " && ls -la")))))

      (testing "tools that can WRITE the truth are still refused, allowlist or not"
        ;; `git` is the one deliberately left off: `git checkout -- goal.md`
        ;; restores the file from the index, which is a write by another name.
        (is (denied? (bash task task (str "cd " task " && git checkout -- goal.md"))))
        (is (denied? (bash task task (str "curl -s -o " task "/metrics.md https://example.invalid"))))
        ;; `env` is skipped as a command PREFIX, so listing it would have
        ;; laundered whatever followed.
        (is (denied? (bash task task (str "env FOO=1 perl -pi -e 's/a/b/' " task "/goal.md")))))

      (testing "the refusal names the rule that fired, not an edit nobody attempted"
        ;; Two rules can refuse, and the message now says which. `perl` is a
        ;; mutating VERB, so it is refused for writing; `patch` is a tool this
        ;; hook has never heard of, so it is refused for not being a reader.
        ;; The two used to share one sentence, and a command denied for its
        ;; verb was told a word from its last pipeline segment was not a
        ;; reader — one live denial named `tail` when the trigger was
        ;; `install`, and the role went looking for a problem it did not have.
        (let [r (bash task task (str "cd " task " && perl -pi -e 's/a/b/' goal.md"))
              why (get-in (:json r) ["hookSpecificOutput" "permissionDecisionReason"])]
          (is (denied? r))
          (is (str/includes? why "perl")
              "the reason must name the verb that was refused")
          (is (str/includes? why "which writes")))
        (let [r (bash task task (str "cd " task " && patch goal.md < p.diff"))
              why (get-in (:json r) ["hookSpecificOutput" "permissionDecisionReason"])]
          (is (denied? r))
          (is (str/includes? why "patch")
              "the reason must name the command that was not recognised")
          (is (str/includes? why "not a reader this hook knows")))))))

(deftest a-command-is-parsed-as-commands-not-as-words
  ;; Every case here was denied in one live run of task gobelhygine: 13
  ;; denials in state/denials.jsonl, all false positives, not one of them a
  ;; write. The head-word scan splits on `|;&` and reads each segment's first
  ;; word as a command name, and three kinds of word are not command names.
  (with-task
    (fn [task _sandbox]
      (session-start task)

      (testing "a shell keyword is not a command"
        ;; `for f in ...; do cat "$f"; done` splits into three segments whose
        ;; first words are `for`, `do` and `done`. Denied four times.
        (is (allowed? (bash task task (str "cd " task " && for f in goal.md metrics.md; do echo \"--- $f\"; cat \"$f\"; done"))))
        (is (allowed? (bash task task (str "cd " task " && if grep -q x goal.md; then echo hit; fi"))))
        (is (allowed? (bash task task (str "cd " task " && while IFS= read -r l; do echo \"$l\"; done < goal.md"))))
        (testing "and the body of the loop is still judged"
          ;; The split is on `;`, so ignoring the `for` segment checks nothing
          ;; less — `chmod` arrives as its own segment.
          (is (denied? (bash task task (str "cd " task " && for f in goal.md; do chmod 644 \"$f\"; done"))))))

      (testing "text inside quotes is an argument, never a command"
        ;; note.bb is the tool this hook TELLS roles to use, and a semicolon in
        ;; the claim text refused it: two live denials, head words `whoever`
        ;; and `waiting`, both words of prose.
        (is (allowed? (bash task task (str "cd " task " && note.bb finding 'goal line 2 says 13; whoever removes them should delete all 14' 'a count-driven pass leaves one behind'"))))
        (is (allowed? (bash task task (str "cd " task " && note.bb gotcha 'a claim with a | pipe & an ampersand' 'the scan split on both'"))))
        (testing "including a word that would be a mutating verb outside them"
          ;; A claim quoting `RUN uv pip install --system` matched `install`.
          (is (allowed? (bash task task (str "cd " task " && note.bb finding 'RUN uv pip install --system --no-cache . at Dockerfile:18' 'gobel is not in the repos list' 2>&1 | tail -3")))))
        (testing "but a quoted path is still the file it names"
          (is (denied? (bash task task (str "cd " task " && chmod 644 'goal.md'"))))
          (is (denied? (bash task task (str "cd " task " && python3 -c \"open('goal.md','w')\""))))))

      (testing "git is judged on its subcommand, because only some of them write"
        (is (allowed? (bash task task (str "cd " task " && ls -la 2>/dev/null; echo === ; git log --oneline -5 && git status --short"))))
        (is (allowed? (bash task task (str "cd " task " && git -C worktrees/cirdan log --oneline -5"))))
        (is (allowed? (bash task task (str "cd " task " && git diff --stat && git rev-parse --abbrev-ref HEAD"))))
        (testing "and the writing ones still are not"
          ;; `-C` takes a VALUE: reading it as the subcommand would have made
          ;; every `git -C x checkout` look like a subcommand named `x`.
          (is (denied? (bash task task (str "cd " task " && git checkout -- goal.md"))))
          (is (denied? (bash task task (str "cd " task " && git -C . restore goal.md"))))
          (is (denied? (bash task task (str "cd " task " && git -C . checkout HEAD -- metrics.md")))))))))

(deftest repos-is-the-scope-and-the-scope-is-fixed-when-the-swarm-opens
  ;; goal.md and metrics.md were the only locked files, and `repos` is read
  ;; long after open: `ship` consults it to decide which branches to push and
  ;; open PRs from, and `sessions.tsv` was written from it once. So an edit
  ;; mid-run cannot add a session — it can only make the file disagree with the
  ;; panes that are running, and then push a branch nobody worked.
  (with-task
    (fn [task _sandbox]
      (spit (str (fs/path task "repos")) "/tmp/alpha\n")
      (session-start task)

      (testing "SessionStart locks it, like the truth"
        (is (= "444" (perms (fs/path task "repos")))))

      (testing "an edit is refused, and the reason is about scope, not about a bar"
        ;; The message matters: `repos` is not a bar, and telling a role that a
        ;; bar it cannot meet belongs in escalation.md when it was trying to
        ;; add a checkout sends it to argue with the wrong file.
        (let [r (edit task "Write" (str (fs/path task "repos")))
              why (get-in (:json r) ["hookSpecificOutput" "permissionDecisionReason"])]
          (is (denied? r))
          (is (str/includes? why "the task's scope"))
          (is (str/includes? why "push a branch nobody worked"))
          (is (not (str/includes? why "never an edit to the bar"))
              "that sentence is goal.md's, and it is not what this file is")))

      (testing "and so is the chmod that would undo the lock"
        (is (denied? (bash task task (str "chmod 644 " task "/repos"))))
        (is (denied? (bash task task (str "cd " task " && echo /tmp/beta >> repos"))))
        (is (denied? (bash task task (str "cd " task " && sed -i '' 's|alpha|beta|' repos")))))

      (testing "reading it is still free — every role's own worktree is in there"
        (is (allowed? (bash task task (str "cat " task "/repos"))))
        (is (allowed? (bash task task (str "cd " task " && grep -c . repos"))))
        (is (allowed? (bash task task (str "cd " task " && wc -l repos && git status --short")))))

      (testing "a repos file in a worktree is not the task's"
        ;; Roles work in a checkout that may have its own `repos`; only the one
        ;; in the task folder is the scope.
        (let [wt (fs/path task "worktrees" "alpha")]
          (fs/create-dirs wt)
          (spit (str (fs/path wt "repos")) "not the task's\n")
          (is (allowed? (bash task wt "chmod 644 repos"))))))))
