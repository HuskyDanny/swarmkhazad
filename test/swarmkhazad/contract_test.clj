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
