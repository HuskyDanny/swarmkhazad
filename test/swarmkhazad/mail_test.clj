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
  "A prepared task with roles a (task), b (batch), c (no repo). f gets a map with
   :dir, :env (task id + home), and a `helper` fn that runs a script as a role."
  [f]
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-mail."})
        home (str (fs/path sandbox "home"))
        src (str (fs/path sandbox "src" "fixture"))
        id "t-mail"
        env {"SWARMKHAZAD_HOME" home "SWARMKHAZAD_TASK_ID" id}]
    (try
      (make-source-repo! src)
      (run {:env env} cli "new" id "--repo" src)
      (let [dir (fs/path home "tasks" id)]
        (spit (str (fs/path dir "roles")) (str "a claude " src " task\nb claude " src " batch\nc claude none\n"))
        (run {:env env} cli "prepare" id)
        (letfn [(helper [role script & args]
                  (let [row-dir (if (= role "c") (str dir) (str (fs/path dir "worktrees" role)))]
                    (apply run {:dir row-dir :env (assoc env "SWARMFORGE_ROLE" role "SWARMKHAZAD_TASK_DIR" (str dir)) :ok? false}
                           "bb" (str (fs/path scripts script)) args)))]
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
        (let [stray (str (fs/path dir "worktrees" "a" "note.txt"))]
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
        (git (fs/path dir "worktrees" "a") "commit" "-q" "--allow-empty" "-m" "nothing")
        (let [draft (draft! dir "g0.txt" "type: git_handoff\nto: b\npriority: 50\n")
              r (helper "a" "swarm_handoff.bb" draft)]
          (is (= 1 (:exit r)) (:err r))
          (is (str/includes? (:err r) "changes no files"))
          (is (fs/exists? draft))))
      (testing "a role without a repo can send notes but not git handoffs"
        (let [draft (draft! dir "c1.txt" "type: git_handoff\nto: a\npriority: 50\n")
              r (helper "c" "swarm_handoff.bb" draft)]
          (is (= 1 (:exit r)))
          (is (str/includes? (:err r) "has no repo")))
        (let [draft (draft! dir "c2.txt" "type: note\nto: a\npriority: 50\nmessage: from c\n")]
          (is (zero? (:exit (helper "c" "swarm_handoff.bb" draft))))))
      (testing "a role holding the terminal broadcast may not forward a git_handoff"
        (write! (fs/path dir "worktrees" "a" "x.txt") "x\n")
        (git (fs/path dir "worktrees" "a") "add" "x.txt")
        (git (fs/path dir "worktrees" "a") "commit" "-q" "-m" "x")
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
            (is (= (git (fs/path dir "worktrees" "a") "rev-parse" "--short=10" "HEAD") (get h "commit")))))))))

(deftest handoffd-once-delivers-good-mail-and-quarantines-bad-mail
  (with-task
    (fn [{:keys [dir env helper]}]
      ;; The board card open would have created; the daemon moves it on git handoffs.
      (run {:env env} "bb" "-e" (str "(load-file \"" scripts "/board_lib.bb\") (board-lib/create-card! (task-lib/task-ctx \"t-mail\") \"t-mail\" \"a\")"))
      (write! (fs/path dir "mail" "a" "outbox" "50_20260101T000000001Z_from_a_to_nobody.handoff")
              "id: p1\nfrom: a\nto: nobody\npriority: 50\ntype: note\nmessage: hi\n\nhi\n")
      (write! (fs/path dir "mail" "a" "outbox" "50_20260101T000000002Z_from_a_to_.handoff")
              "id: p2\nfrom: a\npriority: 50\ntype: note\nmessage: hi\n\nhi\n")
      (write! (fs/path dir "worktrees" "a" "y.txt") "y\n")
      (git (fs/path dir "worktrees" "a") "add" "y.txt")
      (git (fs/path dir "worktrees" "a") "commit" "-q" "-m" "y")
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

(deftest harness-launch-strings-carry-each-cli-s-own-flags
  (load-file (str (fs/path scripts "swarm_lib.bb")))
  (let [ctx {:task-id "t" :task-dir "/tmp/t" :goal-file "/tmp/t/goal.md" :metrics-file "/tmp/t/metrics.md"}
        row (fn [h] {:role "r" :harness h :worktree-path "/tmp/t/worktrees/r" :extra-args "--flag va'lue"})
        cmd (fn [h] ((resolve 'swarm-lib/harness-command) ctx (row h) (str "/bin/" h) "/tmp/t/prompts/r.md"))]
    (is (str/includes? (cmd "claude") "'/bin/claude' --append-system-prompt-file '/tmp/t/prompts/r.md'"))
    (is (str/includes? (cmd "claude") "--permission-mode bypassPermissions -n 'sk r' '--flag' 'va'\"'\"'lue' -- 'You are role r"))
    (is (str/includes? (cmd "codex") "'/bin/codex' -C '/tmp/t/worktrees/r' --no-alt-screen --yolo '--flag' 'va'\"'\"'lue' \"$(cat '/tmp/t/prompts/r.md')"))
    (is (str/includes? (cmd "copilot") "'/bin/copilot' -C '/tmp/t/worktrees/r' --no-alt-screen --name 'sk r' --yolo"))
    (is (str/includes? (cmd "copilot") " -i \"$(cat '/tmp/t/prompts/r.md')"))
    (is (str/includes? (cmd "grok") "'/bin/grok' --cwd '/tmp/t/worktrees/r' --permission-mode bypassPermissions '--flag' 'va'\"'\"'lue' --minimal --rules \"$(cat '/tmp/t/prompts/r.md')\" --verbatim 'You are role r"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no launch command for harness" (cmd "gemini")))))

(deftest close-of-a-task-that-was-never-opened-is-a-no-op
  (with-task
    (fn [{:keys [env]}]
      (let [r (run {:env env :ok? false} cli "close" "t-mail")]
        (is (zero? (:exit r)) (:err r))
        (is (str/includes? (:out r) "swarm closed"))))))
