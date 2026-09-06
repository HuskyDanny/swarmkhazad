(ns swarmkhazad.swarm-test
  "`swarmkhazad open` / `close` end to end with a stub harness: real tmux server
   on the task socket, real handoffd, real mail helpers, a real merge by SHA
   between two role worktrees, board card to done, then teardown."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
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
        (spit (str (fs/path dir "roles")) (str "a claude " src " task --model sonnet\nb claude " src " task\n"))
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
                (is (= (git (fs/path dir "repos" "fixture") "rev-parse" "--short=10" "main") (get b-done "task_base_commit"))
                    "the base is b's HEAD when it accepted — the pinned clone commit, not a's commit")
                (is (= id (get b-done "task_id")))
                (is (some? (get b-done "completed_at")))))
            (testing "a's git_handoff carried the commit and its artifacts; b merged it by SHA"
              (let [h (headers (first (handoffs (fs/path dir "mail" "a" "sent"))))]
                (is (= 10 (count (get h "commit"))))
                (is (= "a.txt" (get h "artifacts")))
                (is (nil? (get h "non-forwarding")))
                (is (= "from a\n" (slurp (str (fs/path dir "worktrees" "b" "a.txt")))))
                (is (= (get h "commit") (subs (git (fs/path dir "worktrees" "b") "rev-parse" (str (get h "commit") "^{commit}")) 0 10)))))
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
      (spit (str (fs/path home "tasks" "t-noharness" "roles")) (str "a copilot " src "\n"))
      (let [result (run {:env (assoc env "PATH" "/usr/bin:/bin:/opt/homebrew/bin") :ok? false} cli "open" "t-noharness")]
        (is (not= 0 (:exit result)))
        (is (str/includes? (:err result) "'copilot' is required")))
      (finally
        (fs/delete-tree sandbox)))))
