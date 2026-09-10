(ns swarmkhazad.mail-test
  "The mail helpers off the happy path: swarm_handoff's validation gauntlet and
   note path, handoffd's failed/ path, batch mode, ambiguous states, the
   terminal-loop guard, the harness launch strings. No tmux, no daemon loop —
   helpers are run directly against a prepared task folder."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (str (fs/cwd)))
(def scripts (str (fs/path repo-root "scripts")))
(def cli (str (fs/path scripts "swarmkhazad.bb")))

(defn run [{:keys [dir env ok?]} & args]
  (let [result (apply process/sh (concat [{:continue true :dir (str (or dir repo-root)) :extra-env (or env {})}] args))]
    (when (and (not (false? ok?)) (not (zero? (:exit result))))
      (throw (ex-info (str "command failed: " (str/join " " args) "\n" (:out result) (:err result)) result)))
    result))

(defn git [dir & args]
  (str/trim (:out (apply run {:dir dir} "git" args))))

(defn write! [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn make-source-repo! [dir]
  (fs/create-dirs dir)
  (git dir "init" "-q" "-b" "main")
  (git dir "config" "user.email" "t@example.com")
  (git dir "config" "user.name" "T")
  (write! (fs/path dir "README.md") "one\n")
  (git dir "add" ".")
  (git dir "commit" "-q" "-m" "one")
  (git dir "remote" "add" "origin" "https://example.invalid/acme/fixture.git")
  (git dir "update-ref" "refs/remotes/origin/main" (git dir "rev-parse" "HEAD")))

(defn headers [file]
  (into {} (for [line (take-while (complement str/blank?) (str/split-lines (slurp (str file))))
                 :let [[k v] (str/split line #": " 2)]
                 :when (and k v)]
             [k v])))

(defn handoffs [dir]
  (if (fs/directory? dir)
    (->> (concat (fs/glob dir "*.handoff") (fs/glob dir "**/*.handoff")) (filter fs/regular-file?) distinct (sort-by str) vec)
    []))

(defn with-task
  "A prepared task over one repo with roles a (task), b (batch) and c. One repo
   means the session ids are the role names. f gets a map with :dir, :env (task
   id + home), and a `helper` fn that runs a script as a session."
  [f]
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-mail."})
        home (str (fs/path sandbox "home"))
        src (str (fs/path sandbox "src" "fixture"))
        stubdir (str (fs/path sandbox "stubbin"))
        id "t-mail"
        env {"SWARMKHAZAD_HOME" home "SWARMKHAZAD_TASK_ID" id
             "PATH" (str stubdir ":" (System/getenv "PATH"))}]
    (try
      (make-source-repo! src)
      (fs/create-dirs stubdir)
      (fs/copy (fs/path repo-root "test" "fixtures" "stub-claude.sh") (fs/path stubdir "claude"))
      (fs/set-posix-file-permissions (fs/path stubdir "claude") "rwxr-xr-x")
      (run {:env env} cli "new" id "--repo" src)
      (let [dir (fs/path home "tasks" id)]
        (spit (str (fs/path dir "roles")) "a claude task\nb claude batch\nc claude\n")
        (spit (str (fs/path dir "repos")) (str src "\n"))
        (run {:env env} cli "prepare" id)
        ;; These tests are about mail. The goal judge's gate on git_handoffs has
        ;; its own suite; here the stub `claude` on PATH answers met to every
        ;; grading, so a handoff is never refused for a reason about goals.
        ;; A pre-written verdict file no longer does this: the gate grades when
        ;; the handoff is sent, so it would call the model regardless.
        (letfn [(helper [session script & args]
                  (apply run {:dir (str (fs/path dir "worktrees" "fixture"))
                              :env (assoc env "SWARMKHAZAD_SESSION" session "SWARMFORGE_ROLE" session
                                          "SWARMKHAZAD_TASK_DIR" (str dir))
                              :ok? false}
                         "bb" (str (fs/path scripts script)) args))]
          (f {:dir dir :env env :src src :helper helper :sandbox (str sandbox)})))
      (finally
        (fs/delete-tree sandbox)))))

(defn draft! [dir name text]
  (let [file (fs/path dir "tmp" name)]
    (write! file text)
    (str file)))

(deftest swarm-handoff-rejects-every-malformed-draft-with-the-rule-it-broke
  (with-task
    (fn [{:keys [dir helper]}]
      (doseq [[label text needle status]
              [["unknown recipient" "type: note\nto: nobody\npriority: 50\nmessage: hi\n" "Unknown recipient role 'nobody'" 2]
               ["empty recipient" "type: note\nto: b,\npriority: 50\nmessage: hi\n" "empty recipient" 2]
               ["duplicate recipient" "type: note\nto: b,b\npriority: 50\nmessage: hi\n" "Duplicate recipient" 2]
               ["missing to" "type: note\npriority: 50\nmessage: hi\n" "Missing required header 'to'" 2]
               ["missing type" "to: b\npriority: 50\nmessage: hi\n" "Missing required header 'type'" 2]
               ["bad type" "type: email\nto: b\npriority: 50\nmessage: hi\n" "must be git_handoff or note" 2]
               ["bad priority" "type: note\nto: b\npriority: urgent\nmessage: hi\n" "two digits 00-99" 2]
               ["note without message" "type: note\nto: b\npriority: 50\n" "Missing required header 'message'" 2]
               ["message too long" (str "type: note\nto: b\npriority: 50\nmessage: " (apply str (repeat 201 "x")) "\n") "at most 200 characters" 2]
               ["git_handoff with message" "type: git_handoff\nto: b\npriority: 50\nmessage: hi\n" "only allowed for note" 2]
               ["reserved header" "type: note\nto: b\npriority: 50\nmessage: hi\ncommit: abc\n" "header 'commit' is not allowed" 2]
               ["duplicate header" "type: note\ntype: note\nto: b\npriority: 50\nmessage: hi\n" "duplicate header 'type'" 2]
               ["not a header line" "type note\nto: b\n" "expected `field: value`" 2]]]
        (let [draft (draft! dir (str "bad-" (str/replace label #"[^a-z]" "") ".txt") text)
              r (helper "a" "swarm_handoff.bb" draft)]
          (is (= status (:exit r)) (str label ": exit " (:exit r) " " (:err r)))
          (is (str/includes? (:err r) needle) (str label ": " (:err r)))
          (is (fs/exists? draft) (str label ": a rejected draft is left for repair"))))
      (testing "a draft outside the task's tmp/ is refused before parsing"
        (let [stray (str (fs/path dir "worktrees" "fixture" "note.txt"))]
          (write! stray "type: note\nto: b\npriority: 50\nmessage: hi\n")
          (let [r (helper "a" "swarm_handoff.bb" stray)]
            (is (= 1 (:exit r)))
            (is (str/includes? (:err r) "Draft must live under")))))
      (testing "a missing draft file"
        (let [r (helper "a" "swarm_handoff.bb" (str (fs/path dir "tmp" "nope.txt")))]
          (is (= 1 (:exit r)))
          (is (str/includes? (:err r) "Draft file not found"))))
      (testing "nothing reached any outbox"
        (is (empty? (handoffs (fs/path dir "mail"))))))))

(deftest swarm-handoff-queues-a-note-and-refuses-git-handoffs-it-cannot-honour
  (with-task
    (fn [{:keys [dir helper src]}]
      (testing "`to: all` is every other role — what the last role's broadcast needs"
        (let [r (helper "a" "swarm_handoff.bb" (draft! dir "all.txt" "type: note\nto: all\npriority: 50\nmessage: hi\n"))]
          (is (zero? (:exit r)) (:err r))
          (let [h (headers (first (handoffs (fs/path dir "mail" "a" "outbox"))))]
            (is (= "b,c" (get h "to")) "every declared role except the sender, in declaration order")))
        (doseq [f (handoffs (fs/path dir "mail" "a" "outbox"))] (fs/delete f)))
      (testing "a valid note lands in the sender's outbox with the generated headers, and the draft is consumed"
        (let [draft (draft! dir "n1.txt" "type: note\nto: b,c\npriority: 07\nmessage: hello there\n")
              r (helper "a" "swarm_handoff.bb" draft)]
          (is (zero? (:exit r)) (:err r))
          (is (str/includes? (:out r) "HANDOFF QUEUED"))
          (is (not (fs/exists? draft)))
          (let [files (handoffs (fs/path dir "mail" "a" "outbox"))
                h (headers (first files))]
            (is (= 1 (count files)))
            (is (str/starts-with? (fs/file-name (first files)) "07_"))
            (is (str/ends-with? (fs/file-name (first files)) "_from_a_to_b_c.handoff"))
            (is (= "a" (get h "from")))
            (is (= "b,c" (get h "to")))
            (is (= "note" (get h "type")))
            (is (= "hello there" (get h "message")))
            (is (= "t-mail" (get h "task_id")))
            (is (some? (get h "created_at")))
            (is (nil? (get h "commit")))
            (is (str/includes? (slurp (str (first files))) "\n\nRe-read your instructions.\n\nhello there\n")))))
      (testing "a git_handoff whose HEAD changes nothing is refused"
        (git (fs/path dir "worktrees" "fixture") "commit" "-q" "--allow-empty" "-m" "nothing")
        (let [draft (draft! dir "g0.txt" "type: git_handoff\nto: b\npriority: 50\n")
              r (helper "a" "swarm_handoff.bb" draft)]
          (is (= 1 (:exit r)) (:err r))
          (is (str/includes? (:err r) "changes no files"))
          (is (fs/exists? draft))))
      (testing "any session can send a note"
        (let [draft (draft! dir "c2.txt" "type: note\nto: a\npriority: 50\nmessage: from c\n")]
          (is (zero? (:exit (helper "c" "swarm_handoff.bb" draft))))))
      (testing "a role holding the terminal broadcast may not forward a git_handoff"
        (write! (fs/path dir "worktrees" "fixture" "x.txt") "x\n")
        (git (fs/path dir "worktrees" "fixture") "add" "x.txt")
        (git (fs/path dir "worktrees" "fixture") "commit" "-q" "-m" "x")
        (write! (fs/path dir "mail" "a" "inbox" "in_process" "50_20260101T000000000Z_from_b_to_a.handoff")
                "id: x\nfrom: b\nto: a\npriority: 50\ntype: git_handoff\ncommit: 0000000000\nnon-forwarding: true\n\nRe-read.\n")
        (let [draft (draft! dir "g1.txt" "type: git_handoff\nto: b\npriority: 50\n")
              r (helper "a" "swarm_handoff.bb" draft)]
          (is (= 1 (:exit r)))
          (is (str/includes? (:err r) "non-forwarding"))
          (is (str/includes? (:err r) "do not send a git_handoff"))
          (fs/delete (fs/path dir "mail" "a" "inbox" "in_process" "50_20260101T000000000Z_from_b_to_a.handoff"))))
      (testing "the same git_handoff cannot be queued twice while the first is live"
        (let [draft (draft! dir "g2.txt" "type: git_handoff\nto: b\npriority: 50\n")
              r1 (helper "a" "swarm_handoff.bb" draft)
              draft2 (draft! dir "g3.txt" "type: git_handoff\nto: b\npriority: 50\n")
              r2 (helper "a" "swarm_handoff.bb" draft2)]
          (is (zero? (:exit r1)) (:err r1))
          (is (= 1 (:exit r2)))
          (is (str/includes? (:err r2) "Duplicate active handoff"))
          (let [gits (filter #(= "git_handoff" (get (headers %) "type")) (handoffs (fs/path dir "mail" "a" "outbox")))
                h (headers (first gits))]
            (is (= 1 (count gits)) "exactly one live git_handoff")
            (is (= "x.txt" (get h "artifacts")) "the empty commit contributes nothing; the diff is against the parent")
            (is (= (git (fs/path dir "worktrees" "fixture") "rev-parse" "--short=10" "HEAD") (get h "commit")))))))))

(deftest handoffd-once-delivers-good-mail-and-quarantines-bad-mail
  (with-task
    (fn [{:keys [dir env helper]}]
      ;; The board card open would have created; the daemon moves it on git handoffs.
      (run {:env env} "bb" "-e" (str "(load-file \"" scripts "/board_lib.bb\") (board-lib/create-card! (task-lib/task-ctx \"t-mail\") \"t-mail\" \"a\")"))
      (write! (fs/path dir "mail" "a" "outbox" "50_20260101T000000001Z_from_a_to_nobody.handoff")
              "id: p1\nfrom: a\nto: nobody\npriority: 50\ntype: note\nmessage: hi\n\nhi\n")
      (write! (fs/path dir "mail" "a" "outbox" "50_20260101T000000002Z_from_a_to_.handoff")
              "id: p2\nfrom: a\npriority: 50\ntype: note\nmessage: hi\n\nhi\n")
      (write! (fs/path dir "worktrees" "fixture" "y.txt") "y\n")
      (git (fs/path dir "worktrees" "fixture") "add" "y.txt")
      (git (fs/path dir "worktrees" "fixture") "commit" "-q" "-m" "y")
      (is (zero? (:exit (helper "a" "swarm_handoff.bb" (draft! dir "ok.txt" "type: git_handoff\nto: b\npriority: 50\n")))))
      (let [r (run {:env env :ok? false} "bb" (str (fs/path scripts "handoffd.bb")) "--once" "t-mail")]
        (is (zero? (:exit r)) (:err r)))
      (testing "the poison files went to failed/ with the reason beside them; the good one was delivered"
        (let [failed (handoffs (fs/path dir "mail" "a" "failed"))]
          (is (= 2 (count failed)))
          (is (every? #(fs/regular-file? (str % ".error")) failed))
          (is (= #{"unknown recipient nobody" "missing to header"}
                 (set (map #(str/trim (slurp (str % ".error"))) failed)))))
        (is (empty? (handoffs (fs/path dir "mail" "a" "outbox"))) "outbox drained")
        (is (= 1 (count (handoffs (fs/path dir "mail" "a" "sent")))))
        (is (nil? (get (headers (first (handoffs (fs/path dir "mail" "a" "sent")))) "origin_repo"))
            "one repo, so there is nothing to disambiguate and every handoff would carry the same answer")
        (let [arrived (handoffs (fs/path dir "mail" "b" "inbox" "new"))]
          (is (= 1 (count arrived)))
          (is (= "b" (get (headers (first arrived)) "recipient")))
          (is (some? (get (headers (first arrived)) "enqueued_at"))))
        (let [log (slurp (str (fs/path dir "state" "daemon" "handoffd.log")))]
          (is (str/includes? log "failed"))
          (is (str/includes? log "delivered"))))
      (testing "the card moved to the recipient's lane on the git handoff"
        (is (str/includes? (slurp (str (fs/path dir "state" "board" "tasks.tsv"))) "t-mail\tb\t"))))))

(deftest batch-mode-takes-every-item-of-one-priority-as-one-unit
  (with-task
    (fn [{:keys [dir helper]}]
      (doseq [[n prio] [[1 "50"] [2 "50"] [3 "60"]]]
        (write! (fs/path dir "mail" "b" "inbox" "new" (str prio "_2026010" n "T000000000Z_from_a_to_b.handoff"))
                (str "id: n" n "\nfrom: a\nto: b\nrecipient: b\npriority: " prio "\ntype: note\nmessage: m" n "\n\nm" n "\n")))
      (let [r (helper "b" "ready_for_next.bb")]
        (is (zero? (:exit r)) (:err r))
        (is (str/includes? (:out r) "BATCH: "))
        (is (str/includes? (:out r) "COUNT: 2") "both priority-50 items, not the 60")
        (is (str/includes? (:out r) "BATCH_ITEM: 2"))
        (is (str/includes? (:out r) "m1"))
        (is (str/includes? (:out r) "m2"))
        (is (not (str/includes? (:out r) "m3"))))
      (let [batches (filter fs/directory? (fs/list-dir (fs/path dir "mail" "b" "inbox" "in_process")))]
        (is (= 1 (count batches)))
        (is (= 2 (count (handoffs (first batches)))))
        (is (every? #(some? (get (headers %) "dequeued_at")) (handoffs (first batches)))))
      (testing "a second ready_for_next re-prints the same batch rather than taking the 60"
        (let [r (helper "b" "ready_for_next.bb")]
          (is (str/includes? (:out r) "COUNT: 2"))
          (is (= 1 (count (handoffs (fs/path dir "mail" "b" "inbox" "new")))))))
      (testing "done_with_current completes the whole batch and reports the waiting 60"
        (let [r (helper "b" "done_with_current.bb")]
          (is (zero? (:exit r)) (:err r))
          (is (= 2 (count (re-seq #"COMPLETED: " (:out r)))))
          (is (str/includes? (:out r) "COMPLETED_BATCH: "))
          (is (str/includes? (:out r) "MAIL_WAITING"))
          (is (empty? (fs/list-dir (fs/path dir "mail" "b" "inbox" "in_process"))))
          (is (= 2 (count (handoffs (fs/path dir "mail" "b" "inbox" "completed")))))
          (is (every? #(some? (get (headers %) "completed_at")) (handoffs (fs/path dir "mail" "b" "inbox" "completed"))))))
      (testing "a single file in a batch role's in_process is refused, not guessed"
        (write! (fs/path dir "mail" "b" "inbox" "in_process" "50_x_from_a_to_b.handoff") "id: s\nfrom: a\nto: b\npriority: 50\ntype: note\nmessage: s\n\ns\n")
        (let [r (helper "b" "ready_for_next.bb")]
          (is (= 2 (:exit r)))
          (is (str/includes? (:err r) "TASK_IN_PROCESS_IS_SINGLE")))
        (let [r (helper "b" "done_with_current.bb")]
          (is (= 2 (:exit r)))
          (is (str/includes? (:err r) "CURRENT_WORK_IS_SINGLE_TASK")))))))

(deftest ambiguous-in-process-state-is-refused-by-both-receive-helpers
  (with-task
    (fn [{:keys [dir helper]}]
      (doseq [n [1 2]]
        (write! (fs/path dir "mail" "a" "inbox" "in_process" (str "50_x" n "_from_b_to_a.handoff"))
                (str "id: x" n "\nfrom: b\nto: a\npriority: 50\ntype: note\nmessage: x\n\nx\n")))
      (let [r (helper "a" "ready_for_next.bb")]
        (is (= 2 (:exit r)))
        (is (str/includes? (:err r) "AMBIGUOUS_TASK_STATE")))
      (let [r (helper "a" "done_with_current.bb")]
        (is (= 2 (:exit r)))
        (is (str/includes? (:err r) "AMBIGUOUS_TASK_STATE")))
      (testing "with nothing in process, done_with_current says so"
        (doseq [f (handoffs (fs/path dir "mail" "a" "inbox" "in_process"))] (fs/delete f))
        (let [r (helper "a" "done_with_current.bb")]
          (is (= 1 (:exit r)))
          (is (str/includes? (:err r) "NO_CURRENT_TASK"))))
      (testing "a batch dir in a task role's in_process is refused"
        (fs/create-dirs (fs/path dir "mail" "a" "inbox" "in_process" "batch_x_000001"))
        (let [r (helper "a" "ready_for_next.bb")]
          (is (= 2 (:exit r)))
          (is (str/includes? (:err r) "TASK_IN_PROCESS_IS_BATCH")))))))

(deftest note-bb-writes-the-bullet-so-every-reader-can-parse-it
  (with-task
    (fn [{:keys [dir helper]}]
      (let [note (fn [& args] (apply helper "a" "note.bb" args))
            lines (fn [f] (->> (str/split-lines (slurp (str (fs/path dir f))))
                               (remove str/blank?) vec))]
        (testing "one bullet per file, in the shape the portal and the summarizer split on"
          (doseq [[kind file] [["decision" "decision.md"] ["gotcha" "gotcha.md"]
                               ["escalation" "escalation.md"] ["finding" "finding.md"]
                               ["release" "release.md"]]]
            (is (zero? (:exit (note kind (str kind "-claim") (str kind "-why")))))
            (is (= [(str "- **" kind "-claim** — " kind "-why")] (lines file)) file)))
        (testing "a one-repo task gets no tag — there is nothing to disambiguate"
          (is (not (str/includes? (first (lines "finding.md")) "["))))
        (testing "appending, never replacing"
          (is (zero? (:exit (note "finding" "second" "also true"))))
          (is (= 2 (count (lines "finding.md")))))
        (testing "a newline inside an argument is folded, not written through"
          (is (zero? (:exit (note "gotcha" "line one\nline two" "why"))))
          (is (= 2 (count (lines "gotcha.md"))) "two bullets, not three lines")
          (is (str/includes? (last (lines "gotcha.md")) "line one line two")))
        (testing "what it refuses"
          (doseq [[label args needle]
                  [["unknown kind" ["notes" "c" "w"] "unknown kind"]
                   ["empty claim" ["finding" "" "w"] "the claim is empty"]
                   ["blank why" ["finding" "c" "   "] "the why is empty"]
                   ["a fourth argument" ["finding" "c" "w" "extra"] "too many arguments"]]]
            (let [r (apply note args)]
              (is (= 1 (:exit r)) label)
              (is (str/includes? (:err r) needle) (str label ": " (:err r))))))
        (testing "a refusal writes nothing"
          (is (= 2 (count (lines "finding.md")))))))))

(deftest a-shared-worktree-never-guesses-which-session-is-running
  ;; Roles working the same repo share its worktree, so the cwd names a REPO
  ;; and not a session. The pane exports SWARMKHAZAD_SESSION; a helper run by
  ;; hand from that directory has to be told, because delivering a's mail to
  ;; review's inbox is worse than refusing.
  (with-task
    (fn [{:keys [dir env]}]
      (let [wt (str (fs/path dir "worktrees" "fixture"))
            call (fn [extra]
                   (run {:dir wt
                         :env (merge env {"SWARMKHAZAD_TASK_DIR" (str dir)} extra)
                         :ok? false}
                        "bb" (str (fs/path scripts "ready_for_next.bb"))))]
        (testing "three sessions share this worktree, so the cwd settles nothing"
          (let [r (call {})]
            (is (= 1 (:exit r)))
            (is (str/includes? (:err r) "Set SWARMKHAZAD_SESSION") (:err r))))
        (testing "SWARMFORGE_ROLE alone is enough while the role runs in one repo"
          (let [r (call {"SWARMFORGE_ROLE" "a"})]
            (is (zero? (:exit r)) (:err r))))
        (testing "and the session, when given, is what is used"
          (let [r (call {"SWARMKHAZAD_SESSION" "b"})]
            (is (zero? (:exit r)) (:err r))))))))

(defn- resolve-map
  "project-lib's per-role tool table, reached after swarm_lib has been loaded
   (it arrives transitively through run_evidence.bb)."
  []
  @(resolve 'project-lib/role-denied-tools))

(deftest harness-argv-carries-each-cli-s-own-flags-in-both-modes
  (load-file (str (fs/path scripts "swarm_lib.bb")))
  (let [scratch (fs/create-temp-dir {:prefix "sk-argv."})
        prompt (fs/path scratch "r.md")
        _ (spit (str prompt) "PROMPT-TEXT")
        ctx {:task-id "t" :task-dir "/tmp/t" :goal-file "/tmp/t/goal.md" :metrics-file "/tmp/t/metrics.md"
             :hooks-dir (fs/path scratch "hooks") :bin-dir "/tmp/t/bin" :prompts-dir "/tmp/t/prompts"
             :plugin-dir "/tmp/t/plugin"}
        row (fn [h] {:session "r" :role "r" :repo "fixture" :harness h
                     :worktree-path "/tmp/t/worktrees/fixture" :extra-args "--flag va'lue"})
        argv (fn [h mode] ((resolve 'swarm-lib/harness-argv) ctx (row h) (str "/bin/" h) prompt mode "SMOKE"))
        start-with #(str/starts-with? % "You are role r working in fixture (session r) of task t.")]
    (try
      (testing "claude: system prompt file, hook settings, the task folder as a plugin, bypass, name in the pane, extra args, then the message after --"
        ;; `--plugin-dir <task>/plugin` is what makes the generated skill and
        ;; subagent LOADABLE, and it is why nothing is copied into a worktree
        ;; any more. Its own directory, not the task folder: a plugin root is
        ;; also read for `hooks/hooks.json`, and `<task>/hooks/` is where the
        ;; `--settings` file one slot to the left was just written. Two argv slots, unquoted: a `roles` line is whitespace-split
        ;; twice, so a quoted path would reach the CLI with its quotes and load
        ;; nothing — which is why this is built here and not declared there.
        (let [a (argv "claude" :interactive)]
          (is (= ["env" "CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN=1" "/bin/claude"
                  "--disallowedTools" "mcp__logfire"
                  "--append-system-prompt-file" (str prompt)
                  "--settings" (str (fs/path scratch "hooks" "r.settings.json"))
                  "--plugin-dir" "/tmp/t/plugin"
                  "--permission-mode" "bypassPermissions" "-n" "sk r" "--flag" "va'lue" "--"] (butlast a)))
          (is (start-with (last a))))
        (testing "and the plugin is named in print mode too — the smoke run loads the same session shape"
          (is (= "/tmp/t/plugin" (second (drop-while #(not= "--plugin-dir" %) (argv "claude" :smoke))))))
        (let [a (argv "claude" :smoke)]
          (is (some #{"-p"} a))
          (is (= "json" (second (drop-while #(not= "--output-format" %) a))))
          (is (not (some #{"-n"} a)) "no display name in print mode")
          (is (= "SMOKE" (last a)))))
      (testing "codex and copilot have no system-prompt flag: the prompt text leads the message"
        (let [a (argv "codex" :interactive)]
          (is (= ["/bin/codex" "-C" "/tmp/t/worktrees/fixture" "--no-alt-screen" "--yolo" "--flag" "va'lue"] (butlast a)))
          (is (str/starts-with? (last a) "PROMPT-TEXT\n\nYou are role r")))
        (is (= ["/bin/codex" "exec" "--skip-git-repo-check" "-C" "/tmp/t/worktrees/fixture" "--flag" "va'lue"] (butlast (argv "codex" :smoke))))
        (let [a (argv "copilot" :interactive)]
          (is (= ["/bin/copilot" "-C" "/tmp/t/worktrees/fixture" "--no-alt-screen" "--name" "sk r" "--yolo" "--flag" "va'lue" "-i"] (butlast a)))
          (is (str/starts-with? (last a) "PROMPT-TEXT\n\n")))
        (is (= "-p" (last (butlast (argv "copilot" :smoke))))))
      (testing "grok takes the prompt text as --rules and the message as --verbatim"
        (let [a (argv "grok" :interactive)]
          (is (= ["/bin/grok" "--cwd" "/tmp/t/worktrees/fixture" "--permission-mode" "bypassPermissions" "--flag" "va'lue" "--minimal" "--rules" "PROMPT-TEXT" "--verbatim"] (butlast a)))
          (is (start-with (last a)))))
      (testing "the launch script single-quotes every token, so a quote in an arg survives the shell"
        (let [line ((resolve 'swarm-lib/launch-script) ctx (row "claude") prompt)]
          (is (str/includes? line "exec 'env' 'CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN=1' '/tmp/t/bin/claude'"))
          (is (str/includes? line "'--flag' 'va'\"'\"'lue' '--' 'You are role r"))))
      (testing "a role's own tool set reaches the argv, and the variadic flag is followed by a flag"
        ;; --disallowedTools consumes words until the next --prefixed token, so
        ;; its POSITION is the load-bearing part: emitted after `extra` it
        ;; would swallow a role's non-flag argument and the `--` terminator
        ;; with it. Asserting presence alone would pass on that placement.
        (let [tools (fn [role]
                      (let [a ((resolve 'swarm-lib/harness-argv)
                               ctx (assoc (row "claude") :role role) "/bin/claude" prompt :interactive nil)
                            i (.indexOf a "--disallowedTools")]
                        (when (>= i 0)
                          {:values (take-while #(not (str/starts-with? % "--")) (drop (inc i) a))
                           :next (first (drop-while #(not (str/starts-with? % "--")) (drop (inc i) a)))})))]
          (let [t (tools "run")]
            (is (some #{"Edit"} (:values t)) "run produces evidence and does not fix code")
            (is (some #{"mcp__chrome-devtools"} (:values t)) "and does not drive a browser")
            (is (= "--append-system-prompt-file" (:next t))
                "the values end at a flag, never at `extra` or the `--` terminator"))
          (let [t (tools "qa")]
            (is (not (some #{"mcp__chrome-devtools"} (:values t))) "qa is the role that drives the browser")
            (is (not (some #{"Edit"} (:values t))) "and the one that fixes what it finds")
            (is (some #{"mcp__logfire"} (:values t))))
          (is (= ["mcp__logfire"] (vec (:values (tools "r"))))
              "an unlisted role still loses the universal set, and nothing is guessed beyond it")
          (is (empty? (filter #{"MultiEdit"} (mapcat val (resolve-map))))
              "MultiEdit is not a tool this CLI knows: an unknown name prints a typo warning and restricts nothing")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no launch command for harness" (argv "gemini" :interactive)))
      (finally (fs/delete-tree scratch)))))

(deftest close-of-a-task-that-was-never-opened-is-a-no-op
  (with-task
    (fn [{:keys [env]}]
      (let [r (run {:env env :ok? false} cli "close" "t-mail")]
        (is (zero? (:exit r)) (:err r))
        (is (str/includes? (:out r) "swarm closed"))))))

(defn with-two-repo-task
  "A prepared task over two repos with roles implement and review, so every
   role has two sessions and every session id is `<role>_<repo>`. This is the
   shape the one-repo fixture above cannot express: the role and the session
   stop being the same string."
  [f]
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-two."})
        home (str (fs/path sandbox "home"))
        gobel (str (fs/path sandbox "src" "gobel"))
        cirdan (str (fs/path sandbox "src" "cirdan"))
        stubdir (str (fs/path sandbox "stubbin"))
        id "t-two"
        env {"SWARMKHAZAD_HOME" home "SWARMKHAZAD_TASK_ID" id
             "PATH" (str stubdir ":" (System/getenv "PATH"))}]
    (try
      (make-source-repo! gobel)
      (make-source-repo! cirdan)
      (fs/create-dirs stubdir)
      (fs/copy (fs/path repo-root "test" "fixtures" "stub-claude.sh") (fs/path stubdir "claude"))
      (fs/set-posix-file-permissions (fs/path stubdir "claude") "rwxr-xr-x")
      (run {:env env} cli "new" id "--repo" gobel "--repo" cirdan)
      (let [dir (fs/path home "tasks" id)]
        (spit (str (fs/path dir "roles")) "implement claude\nreview claude\n")
        (spit (str (fs/path dir "repos")) (str gobel "\n" cirdan "\n"))
        (spit (str (fs/path dir "goal.md"))
              (str "# t-two\n\n## Goal\n"
                   "- [ ] implement @gobel — repoint the upstreams\n"
                   "- [ ] implement @cirdan — pin the image\n"
                   "- [ ] review — both diffs read clean\n"))
        (run {:env env} cli "prepare" id)
        (letfn [(helper [session repo script & args]
                  (apply run {:dir (str (fs/path dir "worktrees" repo))
                              :env (assoc env "SWARMKHAZAD_SESSION" session
                                          "SWARMKHAZAD_TASK_DIR" (str dir))
                              :ok? false}
                         "bb" (str (fs/path scripts script)) args))
                (commit! [repo name]
                  (write! (fs/path dir "worktrees" repo name) "x\n")
                  (git (fs/path dir "worktrees" repo) "add" name)
                  (git (fs/path dir "worktrees" repo) "commit" "-q" "-m" name))
                ;; Not trimmed: the tally is the last column, and an empty one
                ;; is a trailing tab that str/trim would eat.
                (card [] (first (str/split-lines (slurp (str (fs/path dir "state" "board" "tasks.tsv"))))))]
          (f {:dir dir :env env :helper helper :commit! commit! :card card})))
      (finally
        (fs/delete-tree sandbox)))))

(deftest a-role-hands-off-as-a-whole-not-one-repo-at-a-time
  (with-two-repo-task
    (fn [{:keys [dir env helper commit! card]}]
      (run {:env env} "bb" "-e" (str "(load-file \"" scripts "/board_lib.bb\") "
                                     "(board-lib/create-card! (task-lib/task-ctx \"t-two\") \"t-two\" \"implement\")"))
      (commit! "gobel" "a.txt")
      (commit! "cirdan" "b.txt")
      (testing "`to: review` names a role, and reaches every session that role has"
        (let [r (helper "implement_gobel" "gobel" "swarm_handoff.bb"
                        (draft! dir "one.txt" "type: git_handoff\nto: review\npriority: 50\n"))]
          (is (zero? (:exit r)) (str (:out r) (:err r))))
        (let [h (headers (first (handoffs (fs/path dir "mail" "implement_gobel" "outbox"))))]
          (is (= "review_gobel,review_cirdan" (get h "to"))
              "the sender only knows the name `review`; how many repos it holds is the table's business")
          (is (= "gobel" (get h "origin_repo"))
              "past one repo a recipient has to be told which tree moved — it may have no session there")))
      (run {:env env :ok? false} "bb" (str (fs/path scripts "handoffd.bb")) "--once" "t-two")
      (testing "one repo of two: the card records the handoff and stays put"
        (is (str/starts-with? (card) "t-two\timplement\t") (card))
        (is (str/ends-with? (card) "\timplement_gobel")
            "a review that started here would be reading a tree that is still moving")
        (is (empty? (handoffs (fs/path dir "mail" "review_gobel" "inbox" "new")))
            "and the mail is held, not only the card — a review woken now would read a tree its sibling is still writing")
        (is (empty? (handoffs (fs/path dir "mail" "review_cirdan" "inbox" "new")))))
      (testing "the other repo's sibling moves it"
        (is (zero? (:exit (helper "implement_cirdan" "cirdan" "swarm_handoff.bb"
                                  (draft! dir "two.txt" "type: git_handoff\nto: review\npriority: 50\n")))))
        (run {:env env :ok? false} "bb" (str (fs/path scripts "handoffd.bb")) "--once" "t-two")
        (is (str/starts-with? (card) "t-two\treview\t") (card))
        (is (str/ends-with? (card) "\t") "and the tally clears for the lane that just started"))
      (testing "each review session hears about BOTH repos, its own and the one it has no session in"
        (is (= #{"gobel" "cirdan"}
               (set (keep #(get (headers %) "origin_repo")
                          (handoffs (fs/path dir "mail" "review_gobel" "inbox" "new")))))))
      (testing "both review sessions have the work, and implement's sibling was never a recipient"
        (is (= 2 (count (handoffs (fs/path dir "mail" "review_gobel" "inbox" "new")))))
        (is (= 2 (count (handoffs (fs/path dir "mail" "review_cirdan" "inbox" "new")))))
        (is (empty? (handoffs (fs/path dir "mail" "implement_cirdan" "inbox" "new"))))))))

(deftest a-role-addressing-its-own-name-does-not-hand-off-to-itself
  (with-two-repo-task
    (fn [{:keys [dir helper commit!]}]
      (commit! "gobel" "a.txt")
      (let [r (helper "implement_gobel" "gobel" "swarm_handoff.bb"
                      (draft! dir "self.txt" "type: git_handoff\nto: implement\npriority: 50\n"))]
        (is (= 2 (:exit r)) (:out r))
        (is (str/includes? (:err r) "Unknown recipient role 'implement'")
            "its own sibling drops out of the expansion, which leaves nobody — refused, not silently sent to itself")))))

(deftest every-session-of-the-last-role-closes-the-task-not-just-the-last-row
  ;; What ends a task is the last ROLE finishing, not the last row of
  ;; sessions.tsv. Keyed on the row, only `review_cirdan` was terminal:
  ;; `review_gobel`'s `to: all` went out FORWARDING, so the board moved the card
  ;; into whichever lane the first recipient's role happened to be — backwards,
  ;; into implement — and no recipient saw a terminal inbound to stop on.
  (with-two-repo-task
    (fn [{:keys [dir env helper commit! card]}]
      (run {:env env} "bb" "-e" (str "(load-file \"" scripts "/board_lib.bb\") "
                                     "(board-lib/create-card! (task-lib/task-ctx \"t-two\") \"t-two\" \"review\")"))
      (commit! "gobel" "a.txt")
      (commit! "cirdan" "b.txt")
      (testing "the first session of the last role broadcasts, and it is terminal"
        (is (zero? (:exit (helper "review_gobel" "gobel" "swarm_handoff.bb"
                                  (draft! dir "one.txt" "type: git_handoff\nto: all\npriority: 50\n")))))
        (let [h (headers (first (handoffs (fs/path dir "mail" "review_gobel" "outbox"))))]
          (is (= "true" (get h "non-forwarding"))
              "not `review_cirdan`'s alone — every session of the last role ends its own repo")
          (is (not (str/includes? (get h "to") "review_gobel")) "and not to itself")))
      (run {:env env :ok? false} "bb" (str (fs/path scripts "handoffd.bb")) "--once" "t-two")
      (testing "one repo of two, so the task is not over"
        (is (str/starts-with? (card) "t-two\treview\t") (card))
        (is (str/ends-with? (card) "\treview_gobel")))
      (testing "the sibling's broadcast is what closes it"
        (is (zero? (:exit (helper "review_cirdan" "cirdan" "swarm_handoff.bb"
                                  (draft! dir "two.txt" "type: git_handoff\nto: all\npriority: 50\n")))))
        (is (= "true" (get (headers (first (handoffs (fs/path dir "mail" "review_cirdan" "outbox"))))
                           "non-forwarding")))
        (run {:env env :ok? false} "bb" (str (fs/path scripts "handoffd.bb")) "--once" "t-two")
        (is (str/starts-with? (card) "t-two\tdone\t") (card))
        (is (not (str/includes? (card) "\timplement"))
            "never backwards into a lane the task already left"))
      (testing "implement heard about both repos and has nothing to forward"
        (doseq [session ["implement_gobel" "implement_cirdan"]]
          (let [inbound (handoffs (fs/path dir "mail" session "inbox" "new"))]
            (is (= 2 (count inbound)) session)
            (is (every? #(= "true" (get (headers %) "non-forwarding")) inbound)
                (str session " must stop, not forward"))))))))

(deftest a-turn-that-completes-inside-one-pass-goes-out-in-that-pass
  ;; Both siblings hand off before the daemon looks. Files are processed in
  ;; stamp order, so the one that completes the turn is the LAST one seen — the
  ;; earlier siblings were held moments before, in the same pass. A single pass
  ;; would leave them for the next tick, and `--once` has no next tick.
  (with-two-repo-task
    (fn [{:keys [dir env helper commit!]}]
      (run {:env env} "bb" "-e" (str "(load-file \"" scripts "/board_lib.bb\") "
                                     "(board-lib/create-card! (task-lib/task-ctx \"t-two\") \"t-two\" \"implement\")"))
      (commit! "gobel" "a.txt")
      (commit! "cirdan" "b.txt")
      (is (zero? (:exit (helper "implement_gobel" "gobel" "swarm_handoff.bb"
                                (draft! dir "one.txt" "type: git_handoff\nto: review\npriority: 50\n")))))
      (is (zero? (:exit (helper "implement_cirdan" "cirdan" "swarm_handoff.bb"
                                (draft! dir "two.txt" "type: git_handoff\nto: review\npriority: 50\n")))))
      (run {:env env :ok? false} "bb" (str (fs/path scripts "handoffd.bb")) "--once" "t-two")
      (testing "one pass, and both repos' work is in both review inboxes"
        (is (= 2 (count (handoffs (fs/path dir "mail" "review_gobel" "inbox" "new")))))
        (is (= 2 (count (handoffs (fs/path dir "mail" "review_cirdan" "inbox" "new")))))
        (is (= #{"gobel" "cirdan"}
               (set (keep #(get (headers %) "origin_repo")
                          (handoffs (fs/path dir "mail" "review_gobel" "inbox" "new")))))))
      (testing "and nothing is left waiting in an outbox"
        (doseq [session ["implement_gobel" "implement_cirdan"]]
          (is (empty? (handoffs (fs/path dir "mail" session "outbox"))) session))))))


(defn base!
  "Give a session the inbound item a live one always has: an in-process handoff
   stamped with its worktree's HEAD. Without a base, `changed-files` falls back
   to the root commit and every worktree looks like it changed every file — so a
   role that changed nothing has no way to be seen as having changed nothing."
  [dir session repo]
  (let [head (str/trim (:out (process/sh {:dir (str (fs/path dir "worktrees" repo))}
                                         "git" "rev-parse" "--short=10" "HEAD")))]
    (write! (fs/path dir "mail" session "inbox" "in_process" "50_kickoff.handoff")
            (str "id: kickoff\nfrom: (New-Task)\nto: " session "\npriority: 50\n"
                 "type: note\ntask_id: t-two\ntask: t-two\n"
                 "task_base_commit: " head "\nmessage: start\n\nstart\n"))
    head))

(deftest a-role-that-changes-nothing-still-hands-off-and-is-still-graded
  ;; The outcome the helper had no channel for, found in the first live run.
  ;;
  ;; gobel's goal line asked which domain a host answered on before changing
  ;; anything. It checked, the `.co` URL in the code was already right, and the
  ;; correct diff was empty — at which point `swarm_handoff.bb` refused it
  ;; ("Result commit … changes no files; commit your work first"), leaving
  ;; `type: note` as the only way to say it had finished. A note is not graded,
  ;; does not move the board and does not complete its role's join, so ONE
  ;; missing outcome produced four symptoms: no verdict for gobel, review woken
  ;; early off the note, the sibling's real handoff held for a join that could
  ;; never complete, and a card stuck in `implement` with the work all done.
  (with-two-repo-task
    (fn [{:keys [dir env helper commit! card]}]
      (run {:env env} "bb" "-e" (str "(load-file \"" scripts "/board_lib.bb\") "
                                     "(board-lib/create-card! (task-lib/task-ctx \"t-two\") \"t-two\" \"implement\")"))
      (base! dir "implement_gobel" "gobel")
      (testing "an empty diff with no explanation is still refused"
        (let [r (helper "implement_gobel" "gobel" "swarm_handoff.bb"
                        (draft! dir "n1.txt" "type: git_handoff\nto: review\npriority: 50\n"))]
          (is (= 1 (:exit r)) (str (:out r) (:err r)))
          (is (str/includes? (:err r) "changes no files"))
          (is (str/includes? (:err r) "--no-change")
              "and the refusal names the flag, or a role reaches for the note instead")))
      (testing "--no-change without a reason is refused"
        (let [r (helper "implement_gobel" "gobel" "swarm_handoff.bb"
                        (draft! dir "n2.txt" "type: git_handoff\nto: review\npriority: 50\n")
                        "--no-change")]
          (is (= 1 (:exit r)) (str (:out r) (:err r)))
          (is (str/includes? (:err r) "needs a reason")
              "an unexplained empty diff is indistinguishable from a role that gave up")))
      (testing "--no-change with a reason queues a real git_handoff"
        (let [r (helper "implement_gobel" "gobel" "swarm_handoff.bb"
                        (draft! dir "n3.txt" "type: git_handoff\nto: review\npriority: 50\n")
                        "--no-change" "the .co host answers 401; .com is NXDOMAIN")]
          (is (zero? (:exit r)) (str (:out r) (:err r))))
        (let [f (first (handoffs (fs/path dir "mail" "implement_gobel" "outbox")))
              h (headers f)]
          (is (= "git_handoff" (get h "type")) "not a note — it has to be graded and to move the board")
          (is (= "the .co host answers 401; .com is NXDOMAIN" (get h "no_change"))
              "the reason travels on the handoff, so the next role reads it without opening a file")
          (is (str/includes? (slurp (str f)) "changed nothing, on purpose")
              "and the body tells the recipient to review the reasoning, not to hunt for a diff")))
      (testing "it holds the role's join exactly like any other handoff"
        (run {:env env :ok? false} "bb" (str (fs/path scripts "handoffd.bb")) "--once" "t-two")
        (is (str/starts-with? (card) "t-two\timplement\t") (card))
        (is (str/ends-with? (card) "\timplement_gobel"))
        (is (empty? (handoffs (fs/path dir "mail" "review_gobel" "inbox" "new")))
            "review is not woken by one sibling finishing; it is woken by the ROLE finishing"))
      (testing "and the sibling with a real commit completes the turn"
        (commit! "cirdan" "b.txt")
        (is (zero? (:exit (helper "implement_cirdan" "cirdan" "swarm_handoff.bb"
                                  (draft! dir "n4.txt" "type: git_handoff\nto: review\npriority: 50\n")))))
        (run {:env env :ok? false} "bb" (str (fs/path scripts "handoffd.bb")) "--once" "t-two")
        (is (str/starts-with? (card) "t-two\treview\t") (card))
        (is (= 2 (count (handoffs (fs/path dir "mail" "review_gobel" "inbox" "new"))))
            "both handoffs arrive together — the no-change one and the commit one")))))

(deftest no-change-is-refused-once-there-is-a-diff
  ;; The flag says "the right answer was to change nothing". A role that has
  ;; committed work and passes it anyway would send real changes labelled as a
  ;; no-op, and the next role would review the reasoning instead of the diff.
  (with-two-repo-task
    (fn [{:keys [dir helper commit!]}]
      (base! dir "implement_gobel" "gobel")
      (commit! "gobel" "late.txt")
      (let [r (helper "implement_gobel" "gobel" "swarm_handoff.bb"
                      (draft! dir "n5.txt" "type: git_handoff\nto: review\npriority: 50\n")
                      "--no-change" "still nothing")]
        (is (= 1 (:exit r)) (str (:out r) (:err r)))
        (is (str/includes? (:err r) "changes 1 file"))))))

(deftest the-turn-gate-holds-on-the-role-boundary-not-on-the-message-type
  ;; The gate asked "is this a git_handoff?" when the question is "does this
  ;; cross a role boundary?". A note addressed to the next role sailed past it
  ;; in the first live run and started review on trees still being written,
  ;; while its sibling's real handoff was held for a join that could never
  ;; complete — early start on one side and a deadlock on the other.
  (with-two-repo-task
    (fn [{:keys [dir env helper card]}]
      (run {:env env} "bb" "-e" (str "(load-file \"" scripts "/board_lib.bb\") "
                                     "(board-lib/create-card! (task-lib/task-ctx \"t-two\") \"t-two\" \"implement\")"))
      (testing "a note to the NEXT role is held for the role's join, like a handoff"
        (is (zero? (:exit (helper "implement_gobel" "gobel" "swarm_handoff.bb"
                                  (draft! dir "m1.txt"
                                          "type: note\nto: review\npriority: 50\nmessage: gobel is done\n")))))
        (run {:env env :ok? false} "bb" (str (fs/path scripts "handoffd.bb")) "--once" "t-two")
        (is (empty? (handoffs (fs/path dir "mail" "review_gobel" "inbox" "new")))
            "the type field is not what makes a message a turn change")
        (is (str/ends-with? (card) "\timplement_gobel")
            "and it counts toward its role's join instead of being invisible to it"))
      (testing "a note to its own role's sibling is chatter and goes straight out"
        (is (zero? (:exit (helper "implement_cirdan" "cirdan" "swarm_handoff.bb"
                                  (draft! dir "m2.txt"
                                          "type: note\nto: implement_gobel\npriority: 50\nmessage: fyi\n")))))
        (run {:env env :ok? false} "bb" (str (fs/path scripts "handoffd.bb")) "--once" "t-two")
        (is (= 1 (count (handoffs (fs/path dir "mail" "implement_gobel" "inbox" "new"))))
            "holding sibling chatter would deadlock a role that has to talk to finish"))
      (testing "a note from a role that does not hold the lane is not held either"
        (is (zero? (:exit (helper "review_gobel" "gobel" "swarm_handoff.bb"
                                  (draft! dir "m3.txt"
                                          "type: note\nto: implement_cirdan\npriority: 50\nmessage: question\n")))))
        (run {:env env :ok? false} "bb" (str (fs/path scripts "handoffd.bb")) "--once" "t-two")
        (is (= 1 (count (handoffs (fs/path dir "mail" "implement_cirdan" "inbox" "new"))))
            "a reviewer asking the active implementer a question waits on nobody")))))

(deftest a-role-crosses-off-the-escalation-it-has-since-cleared
  ;; An escalation is append-only and the portal's tick was the only writer of
  ;; the cross-off store, so an ask the swarm resolved by itself sat on the
  ;; Attention list looking like an open blocker. The role that raised it is the
  ;; one who knows it is gone.
  (with-task
    (fn [{:keys [dir helper]}]
      (let [note (fn [& args] (apply helper "a" "note.bb" args))
            handled (fn [] (let [f (fs/path dir "state" "attention-handled.tsv")]
                             (if (fs/exists? f)
                               (->> (str/split-lines (slurp (str f))) (remove str/blank?) vec)
                               [])))
            ;; the portal's own key, computed the portal's own way — if these two
            ;; ever drift, a cross-off written here stops matching the item there
            portal-key (fn [text]
                         (str/trim (:out (run {:dir dir}
                                              "bb" "-e"
                                              (str "(load-file \"" scripts "/task_lib.bb\") "
                                                   "(print (task-lib/attention-key {:kind \"escalation\" :text "
                                                   (pr-str text) "}))")))))]
        (is (zero? (:exit (note "escalation" "the staging cluster is unreachable" "cannot run the e2e bar"))))
        (is (zero? (:exit (note "escalation" "a second, unrelated ask" "still open"))))
        (testing "a match that hits nothing is refused, not silently ignored"
          (let [r (note "resolved" "no such bullet" "did a thing")]
            (is (= 1 (:exit r)))
            (is (str/includes? (:err r) "nothing crossed off")
                "silence here would leave the role believing the item was cleared")
            (is (empty? (handled)))))
        (testing "matching text crosses exactly that item off"
          (is (zero? (:exit (note "resolved" "staging cluster" "the VPN was down; reconnected and the bar passes now"))))
          (is (= 1 (count (handled))) "the unrelated ask is untouched")
          (is (str/starts-with? (first (handled))
                                (portal-key "**the staging cluster is unreachable** — cannot run the e2e bar"))
              "and the key is the one the portal computes, or the cross-off matches nothing on the page"))
        (testing "what was said stays said; what was done about it is recorded separately"
          (is (= 2 (count (->> (str/split-lines (slurp (str (fs/path dir "escalation.md"))))
                               (remove str/blank?))))
              "escalation.md is never edited — the role owns it and the record is a different file")
          (is (str/includes? (slurp (str (fs/path dir "finding.md")))
                             "resolved: staging cluster")
              "the how is a finding, so the next reader sees why it stopped being an ask"))))))
