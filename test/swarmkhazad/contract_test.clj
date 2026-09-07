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

