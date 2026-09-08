(ns swarmkhazad.swarm-test
  "`swarmkhazad open` / `close` end to end with a stub harness: real tmux server
   on the task socket, real handoffd, real mail helpers, a real merge by SHA
   between two role worktrees, board card to done, then teardown."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (str (fs/cwd)))
(def cli (str (fs/path repo-root "scripts" "swarmkhazad.bb")))
(def stub (str (fs/path repo-root "test" "fixtures" "stub-claude.sh")))

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

(defn wait-until [label timeout-ms pred]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) (do (println "timed out waiting for" label) false)
        :else (do (Thread/sleep 500) (recur))))))

(defn tmux-sessions [socket]
  (let [r (process/sh {:continue true} "tmux" "-S" socket "list-sessions" "-F" "#{session_name}")]
    (if (zero? (:exit r)) (->> (:out r) str/split-lines (remove str/blank?) set) #{})))

(deftest open-runs-a-two-role-pipeline-to-done-and-close-tears-it-down
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-swarm."})
        home (str (fs/path sandbox "home"))
        src (str (fs/path sandbox "src" "fixture"))
        stubdir (str (fs/path sandbox "stubbin"))
        env {"SWARMKHAZAD_HOME" home
             "PATH" (str stubdir ":" (System/getenv "PATH"))}
        id "t-e2e"]
    (try
      (make-source-repo! src)
      (fs/create-dirs stubdir)
      (fs/copy stub (fs/path stubdir "claude"))
      (fs/set-posix-file-permissions (fs/path stubdir "claude") "rwxr-xr-x")
      (run {:env env} cli "new" id "--repo" src)
      (let [dir (fs/path home "tasks" id)]
        (spit (str (fs/path dir "roles")) "a claude task --model sonnet\nb claude task\n")
        (spit (str (fs/path dir "repos")) (str src "\n"))
        (let [out (:out (run {:env env} cli "open" id))
              socket (str/trim (slurp (str (fs/path dir "state" "tmux-socket"))))
              board (fs/path dir "state" "board" "tasks.tsv")]
          (try
            (testing "open reports the sessions and the socket"
              (is (str/includes? out "sk-a"))
              (is (str/includes? out "sk-b"))
              (is (str/includes? out socket))
              (is (= #{"sk-a" "sk-b"} (tmux-sessions socket))))
            (testing "the New Task note was queued with a created_at and the card sits in the first lane"
              (is (wait-until "New Task note delivered" 15000
                              #(seq (handoffs (fs/path dir "mail" "_system" "sent")))))
              (let [note (headers (first (handoffs (fs/path dir "mail" "_system" "sent"))))]
                (is (= "(New Task)" (get note "from")))
                (is (= "a" (get note "to")))
                (is (some? (get note "created_at")))))
            (testing "the pipeline runs to done through real mail, real merges, real tmux wake-ups"
              ;; Terminal state, all of it: the daemon marks the card done before it
              ;; moves the broadcast to sent/, so wait for every artifact, not the first.
              (is (wait-until "pipeline finished" 90000
                              #(and (fs/exists? board)
                                    (str/includes? (slurp (str board)) (str id "\tdone"))
                                    (fs/exists? (fs/path dir "tmp" "done-a"))
                                    (fs/exists? (fs/path dir "tmp" "done-b"))
                                    (seq (handoffs (fs/path dir "mail" "b" "sent")))
                                    (seq (handoffs (fs/path dir "mail" "a" "inbox" "new")))
                                    (seq (handoffs (fs/path dir "mail" "b" "inbox" "completed")))))
                  (str "a: " (when (fs/exists? (fs/path dir "tmp" "a-handoff.txt")) (slurp (str (fs/path dir "tmp" "a-handoff.txt"))))
                       " b: " (when (fs/exists? (fs/path dir "tmp" "b-handoff.txt")) (slurp (str (fs/path dir "tmp" "b-handoff.txt"))))
                       " log: " (slurp (str (fs/path dir "state" "daemon" "handoffd.log")))
                       " pane a: " (:out (process/sh {:continue true} "tmux" "-S" socket "capture-pane" "-p" "-t" "sk-a" "-S" "-")))))
            (testing "each role sent at least one handoff — the metrics.md bar"
              (doseq [role ["a" "b"]]
                (is (>= (count (handoffs (fs/path dir "mail" role "sent"))) 1) (str role "/sent"))))
            (testing "a's inbound note was completed with a completed_at; b's inbound git_handoff too"
              (let [a-done (headers (first (handoffs (fs/path dir "mail" "a" "inbox" "completed"))))
                    b-done (headers (first (handoffs (fs/path dir "mail" "b" "inbox" "completed"))))]
                (is (= "note" (get a-done "type")))
                (is (some? (get a-done "completed_at")))
                (is (some? (get a-done "dequeued_at")))
                (is (= "git_handoff" (get b-done "type")))
                (is (= "a" (get b-done "from")))
                (is (= (get (headers (first (handoffs (fs/path dir "mail" "a" "sent")))) "commit")
                       (get b-done "task_base_commit"))
                    "the base is the worktree's HEAD when b accepted — sharing a repo with a, that is a's own commit")
                (is (= id (get b-done "task_id")))
                (is (some? (get b-done "completed_at")))))
            (testing "a's git_handoff carried the commit and its artifacts; b merged it by SHA"
              (let [h (headers (first (handoffs (fs/path dir "mail" "a" "sent"))))]
                (is (= 10 (count (get h "commit"))))
                (is (= "a.txt" (get h "artifacts")))
                (is (nil? (get h "non-forwarding")))
                (is (= "from a\n" (slurp (str (fs/path dir "worktrees" "fixture" "a.txt")))))
                (is (= (get h "commit") (subs (git (fs/path dir "worktrees" "fixture") "rev-parse" (str (get h "commit") "^{commit}")) 0 10))
                    "roles in one repo share its worktree, so the merge is a no-op and the commit is simply there")))
            (testing "the daemon typed a wake-up into each recipient's pane"
              (doseq [role ["a" "b"]]
                (let [pane (:out (process/sh {:continue true} "tmux" "-S" socket "capture-pane" "-p" "-t" (str "sk-" role) "-S" "-"))]
                  (is (str/includes? pane "You have new handoff mail") (str "wake-up in sk-" role)))))
            (testing "b's terminal broadcast is non-forwarding and landed in a's inbox/new"
              (let [sent (headers (first (handoffs (fs/path dir "mail" "b" "sent"))))
                    arrived (handoffs (fs/path dir "mail" "a" "inbox" "new"))]
                (is (= "true" (get sent "non-forwarding")))
                (is (= "a" (get sent "to")))
                (is (= 1 (count arrived)))
                (is (= "a" (get (headers (first arrived)) "recipient")))
                (is (some? (get (headers (first arrived)) "enqueued_at")))))
            (testing "the harness was launched with the role prompt, bypass permissions, and the declared extra args"
              (let [argv (str/split-lines (slurp (str (fs/path dir "tmp" "launch-a.argv"))))]
                (is (some #{"--append-system-prompt-file"} argv))
                (is (= (str (fs/path dir "hooks" "a.settings.json"))
                       (second (drop-while #(not= "--settings" %) argv)))
                    "the role loads its contract hooks via --settings")
                (let [settings (json/parse-string (slurp (str (fs/path dir "hooks" "a.settings.json"))))]
                  (is (str/ends-with? (get-in settings ["hooks" "SessionStart" 0 "hooks" 0 "command"]) "hooks/run-contract.sh"))
                  (is (= "Edit|Write|MultiEdit|NotebookEdit|Bash" (get-in settings ["hooks" "PreToolUse" 0 "matcher"]))))
                (is (= "444" (str/trim (:out (process/sh "stat" "-f" "%Lp" (str (fs/path dir "goal.md")))))) "open locked goal.md")
                (is (some #{"--permission-mode"} argv))
                (is (some #{"bypassPermissions"} argv))
                (is (= ["--model" "sonnet"] (filterv #{"--model" "sonnet"} argv)))
                (is (str/includes? (last argv) "ready_for_next.bb") "the initial prompt tells the role how to start")
                (is (str/includes? (slurp (str (fs/path dir "prompts" "a.md"))) "Forward finished work to `b`"))
                (is (str/includes? (slurp (str (fs/path dir "prompts" "b.md"))) "You are the last role"))))
            (testing "nothing was written to the source checkout"
              (is (= "" (git src "status" "--porcelain")))
              (is (= "one\n" (slurp (str (fs/path src "README.md")))))
              (is (not (fs/exists? (fs/path src "a.txt")))))
            (finally
              ;; done_with_current already archived both panes; drop those so the
              ;; archives asserted below can only have come from close itself.
              (fs/delete-tree (fs/path dir "state" "sessions"))
              (run {:env env :ok? false} cli "close" id)))
          (testing "close archived every pane, stopped the daemon, and killed the server"
            (is (= #{} (tmux-sessions socket)))
            (is (not (fs/exists? (fs/path dir "state" "daemon" "handoffd.pid"))))
            (is (str/includes? (slurp (str (fs/path dir "state" "daemon" "handoffd.log"))) "stopped"))
            (doseq [role ["a" "b"]]
              (is (fs/regular-file? (fs/path dir "state" "sessions" role "pane.txt")) (str role " pane archived"))))))
      (finally
        (process/sh {:continue true} "tmux" "-S" (str "/tmp/swarmkhazad-" (System/getProperty "user.name") "/" id ".sock") "kill-server")
        (fs/delete-tree sandbox)))))

(deftest open-refuses-a-missing-harness
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-swarm."})
        home (str (fs/path sandbox "home"))
        src (str (fs/path sandbox "src" "fixture"))
        env {"SWARMKHAZAD_HOME" home}]
    (try
      (make-source-repo! src)
      (run {:env env} cli "new" "t-noharness" "--repo" src)
      (spit (str (fs/path home "tasks" "t-noharness" "roles")) "a copilot\n")
      (spit (str (fs/path home "tasks" "t-noharness" "repos")) (str src "\n"))
      (let [result (run {:env (assoc env "PATH" "/usr/bin:/bin:/opt/homebrew/bin") :ok? false} cli "open" "t-noharness")]
        (is (not= 0 (:exit result)))
        (is (str/includes? (:err result) "'copilot' is required")))
      (finally
        (fs/delete-tree sandbox)))))

(deftest a-prompt-past-one-repo-points-at-the-next-role-and-its-own-draft
  ;; The two things a session reads out of its prompt and cannot derive: who to
  ;; forward to, and what to call its write-up. Both were session-major and both
  ;; were wrong past one repo — sessions.tsv is ordered role-major, so "the next
  ;; row" is a sibling of your own role, and `draft-<role>.md` is a file two
  ;; sessions of that role would each write and the judge would read from
  ;; neither. One repo hides both: session and role are the same string.
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-prompt."})
        home (str (fs/path sandbox "home"))
        gobel (str (fs/path sandbox "src" "gobel"))
        cirdan (str (fs/path sandbox "src" "cirdan"))
        env {"SWARMKHAZAD_HOME" home}
        dir (fs/path home "tasks" "t-pr")]
    (try
      (make-source-repo! gobel)
      (make-source-repo! cirdan)
      (write! (fs/path dir "goal.md")
              "## Goal\n- [ ] implement — the change\n- [ ] tidy — sweep it\n- [ ] review — read it\n")
      (write! (fs/path dir "metrics.md") "# bars\n")
      ;; `tidy` has no prompts/tidy.prompt, so it falls back to default.prompt —
      ;; the only stage prompt carrying the bare `draft-<role>.md` placeholder.
      ;; With only roles that ship their own file, that placeholder is never
      ;; loaded and a rewrite that missed it would look tested and not be.
      (write! (fs/path dir "roles") "implement claude\ntidy claude\nreview claude\n")
      (write! (fs/path dir "repos") (str gobel "\n" cirdan "\n"))
      (run {:env env} cli "prepare" "t-pr")
      ;; write-prompt! runs at open, which boots tmux and a harness. The prompt
      ;; text is what this is about, so call it directly with the real rows.
      (run {:env env}
           "bb" "-e" (str "(load-file \"" repo-root "/scripts/swarm_lib.bb\") "
                          "(let [ctx (task-lib/task-ctx \"t-pr\") "
                          "      rows (task-lib/read-sessions-tsv ctx)] "
                          "  (doseq [r rows] (swarm-lib/write-prompt! ctx rows r)))"))
      (let [prompt #(slurp (str (fs/path dir "prompts" (str % ".md"))))]
        (testing "the lineup is roles, and the address is the next ROLE"
          (is (str/includes? (prompt "implement_gobel") "Roles in order: implement → tidy → review"))
          (is (str/includes? (prompt "implement_gobel") "Forward finished work to `tidy`")
              "not `implement_cirdan` — a sibling of its own role, which moved the card into the lane it was already in")
          (is (str/includes? (prompt "implement_cirdan") "Forward finished work to `tidy`")
              "every session of a role forwards to the same next role")
          (is (str/includes? (prompt "review_cirdan") "You are the last role")
              "and every session of the LAST role broadcasts — not only the last row of the table"))
        (testing "each session is told to write its own draft"
          (is (str/includes? (prompt "implement_gobel") "`draft-implement_gobel.md`"))
          (is (str/includes? (prompt "implement_cirdan") "`draft-implement_cirdan.md`"))
          (is (not (str/includes? (prompt "implement_gobel") "draft-<your role>.md"))
              "no placeholder survives into a prompt an agent reads literally"))
        (testing "including the one in the fallback stage prompt"
          (is (str/includes? (prompt "tidy_gobel") "`draft-tidy_gobel.md`"))
          (is (not (str/includes? (prompt "tidy_gobel") "draft-<role>.md"))))
        (testing "a cross-role reference resolves to the sibling in the SAME repo"
          (is (str/includes? (prompt "review_gobel") "`draft-implement_gobel.md`")
              "review wants the implement that worked on the tree it is reviewing")
          (is (str/includes? (prompt "review_cirdan") "`draft-implement_cirdan.md`"))
          (is (not (str/includes? (prompt "review_gobel") "draft-implement.md"))
              "which nobody wrote"))
        (testing "the session's own repo is still what the header pins it to"
          (is (str/includes? (prompt "review_gobel") "- Your repo: gobel"))
          (is (str/includes? (prompt "review_cirdan") "- Your repo: cirdan"))))
      (finally
        (fs/delete-tree sandbox)))))

(deftest a-one-repo-prompt-still-reads-the-way-it-always-did
  ;; The rewrite above is a no-op when session and role are the same string, and
  ;; every task already on disk is that shape.
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-prompt1."})
        home (str (fs/path sandbox "home"))
        src (str (fs/path sandbox "src" "fixture"))
        env {"SWARMKHAZAD_HOME" home}
        dir (fs/path home "tasks" "t-pr1")]
    (try
      (make-source-repo! src)
      (write! (fs/path dir "goal.md") "## Goal\n- [ ] implement — x\n- [ ] review — y\n")
      (write! (fs/path dir "metrics.md") "# bars\n")
      (write! (fs/path dir "roles") "implement claude\nreview claude\n")
      (write! (fs/path dir "repos") (str src "\n"))
      (run {:env env} cli "prepare" "t-pr1")
      (run {:env env}
           "bb" "-e" (str "(load-file \"" repo-root "/scripts/swarm_lib.bb\") "
                          "(let [ctx (task-lib/task-ctx \"t-pr1\") "
                          "      rows (task-lib/read-sessions-tsv ctx)] "
                          "  (doseq [r rows] (swarm-lib/write-prompt! ctx rows r)))"))
      (let [p (slurp (str (fs/path dir "prompts" "implement.md")))]
        (is (str/includes? p "Forward finished work to `review`"))
        (is (str/includes? p "`draft-implement.md`") "the name every task on disk already uses"))
      (finally
        (fs/delete-tree sandbox)))))
