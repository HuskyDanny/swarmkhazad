(ns swarmkhazad.portal-test
  "portal.bb through its request handler: every page is a view of a task folder.
   Kickstart runs the real CLI with the stub harness and a real tmux server."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (str (fs/cwd)))
(def scripts (str (fs/path repo-root "scripts")))
(def cli (str (fs/path scripts "swarmkhazad.bb")))
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

(defn request
  "Run the portal's handler in a child bb with SWARMKHAZAD_HOME pointed at the
   sandbox; returns {:status :body :headers}."
  [env method uri & [{:keys [query body]}]]
  (let [form (str "(load-file \"" scripts "/portal.bb\") "
                  "(let [r (portal/handle-request {:request-method " method " :uri " (pr-str uri)
                  " :query-string " (pr-str query) " :body " (pr-str body) "})] "
                  "(println (:status r)) (println (pr-str (:headers r))) (print (:body r)))")
        r (run {:env env} "bb" "-e" form)
        [status headers & body] (str/split-lines (:out r))]
    {:status (parse-long status) :headers (read-string headers) :body (str/join "\n" body)}))

(deftest the-task-page-is-a-view-of-the-folder-and-the-index-lists-it
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-portal."})
        home (str (fs/path sandbox "home"))
        src (str (fs/path sandbox "src" "fixture"))
        env {"SWARMKHAZAD_HOME" home}
        id "t-portal"]
    (try
      (make-source-repo! src)
      (run {:env env} cli "new" id "--repo" src)
      (let [dir (fs/path home "tasks" id)]
        (spit (str (fs/path dir "roles")) (str "implement claude " src " task model=kimi\nrun claude " src " task\nreview claude " src " task\n"))
        (spit (str (fs/path dir "goal.md")) "# t-portal\n\n## Goal\n- [ ] implement — the route returns 200\n- [ ] run — tests green\n- [x] control — already ticked\n\n## Not-goal\n- [ ] not a goal box\n")
        (spit (str (fs/path dir "metrics.md")) "## Quantitative\n- every role called — bar: each ≥ 1 — measure: `echo x`\n- wall clock — bar: < 30 min — measure: `echo y`\n- notes only — bar: n/a — measure: `echo z`\n- silent measure — bar: n/a — measure: `echo -n`\n")
        (run {:env env} cli "prepare" id)
        (write! (fs/path dir "state" "judge" "implement.json") "{\"met\":false,\"unmet\":[\"run — tests green\"],\"decision\":\"block\"}")
        (write! (fs/path dir "state" "judge" "run.json") "{\"met\":false,\"unmet\":[\"judge_unavailable\"],\"down\":true}")
        (write! (fs/path dir "state" "judge" "review.json") "{\"met\":true,\"unmet\":[]}")
        (write! (fs/path dir "evidence" "every-role-called.txt") "bar: every role called\ncommand: echo x\nexit: 0\nstarted_at: 2026-09-06T00:00:00Z\n--- output ---\nimplement 1\nrun 1\n")
        (write! (fs/path dir "evidence" "wall-clock.txt") "bar: wall clock\ncommand: echo y\nexit: 7\nstarted_at: 2026-09-06T00:01:00Z\n--- output ---\nEVIDENCE-FAILED\n")
        (write! (fs/path dir "evidence" "notes-only.txt") "a note the run role wrote by hand\nHAND-WRITTEN-TAIL\n")
        (write! (fs/path dir "evidence" "silent-measure.txt") "bar: silent measure\ncommand: echo -n\nexit: 0\nstarted_at: 2026-09-06T00:02:00Z\n--- output ---\n")
        (write! (fs/path dir "escalation.md") "- **needs Allen** — a bar cannot be met\n")
        (write! (fs/path dir "gotcha.md") "- **PATH is rebuilt by tmux** — resolve binaries first\n")
        (write! (fs/path dir "mail" "run" "failed" "50_x_from_run_to_nobody.handoff") "id: x\n")
        (write! (fs/path dir "state" "denials.jsonl") "{\"tool\":\"Edit\"}\n{\"tool\":\"Bash\"}\n")
        (write! (fs/path dir "state" "sessions" "implement" "pane.txt") "line one\nlast pane line PANE-MARK\n")
        (write! (fs/path dir "draft-implement.md") "# draft\nDRAFT-MARK\n")
        (testing "index lists the task with its lane and attention count"
          (let [r (request env :get "/")]
            (is (= 200 (:status r)))
            (is (str/includes? (:body r) "href=\"/tasks/t-portal\""))
            (is (str/includes? (:body r) "not opened"))
            (is (str/includes? (:body r) "implement, run, review"))
            (is (re-find #"unmet\">4 needs you<" (:body r)) "escalation + failed mail + denials + judge down = 4 attention items")
            (is (str/includes? (:body r) "action=\"/tasks\"") "the kickstart form is on the index")
            (is (str/includes? (:body r) "<details class=\"composer\">") "the composer starts collapsed when there is nothing to report")
            (is (str/includes? (:body r) "harnesses: claude, codex, copilot, grok"))))
        (testing "the task page: checkboxes follow the verdicts, never the file"
          (let [body (:body (request env :get (str "/tasks/" id)))]
            (is (str/includes? body "http-equiv=\"refresh\""))
            (is (< (str/index-of body "http-equiv=\"refresh\"") (str/index-of body "<body"))
                "the refresh meta belongs in <head>; <meta> is not valid flow content inside <main>")
            (is (re-find #"<input disabled=\"disabled\" type=\"checkbox\" /> implement — the route returns 200 <span class=\"status pending\">pending" body)
                "no verdict names it, and review's met does not carry the line while implement's and run's are unmet → pending, unchecked")
            (is (re-find #"tests green <span class=\"status unmet\">unmet: implement" body))
            (is (re-find #"<input checked=\"checked\" disabled=\"disabled\" type=\"checkbox\" /> control — already ticked <span class=\"status ticked\">ticked" body))
            (is (not (str/includes? body "not a goal box")) "only the Goal section's boxes")
            (testing "metrics bars with the latest evidence"
              (is (str/includes? body "every role called"))
              (is (str/includes? body "<span class=\"status met\">exit 0"))
              (is (str/includes? body "run 1"))
              (is (str/includes? body "<span class=\"status unmet\">exit 7") "a non-zero measure reads as unmet, not met")
              (is (str/includes? body "EVIDENCE-FAILED"))
              (is (str/includes? body "<span class=\"status pending\">no exit recorded")
                  "an evidence file with no exit header is pending, never a red pill with no number")
              (is (str/includes? body "HAND-WRITTEN-TAIL")
                  "and its tail still shows, marker or no marker")
              (is (not (str/includes? body "<pre></pre>"))
                  "a measure that printed nothing gets no empty output box"))
            (testing "a background open that died is surfaced, not left in a log nobody reads"
              (write! (fs/path dir "state" "portal-open.log") "swarmkhazad: role a: repo /nope is not a git checkout\n")
              (let [b (:body (request env :get (str "/tasks/" id)))]
                (is (str/includes? b "the swarm never started"))
                (is (str/includes? b "/nope is not a git checkout")))
              (fs/delete (fs/path dir "state" "portal-open.log")))
            (testing "attention lists everything a human must see"
              (is (str/includes? body "needs Allen"))
              (is (str/includes? body "mail/run/failed/50_x_from_run_to_nobody.handoff"))
              (is (str/includes? body "2 tool call(s) denied"))
              (is (str/includes? body "role run: the goal judge was unavailable"))
              (is (str/includes? body "<details class=\"item\">")
                  "each attention item collapses to one line; the full text stays in the page"))
            (testing "bullets, drafts, role cards"
              (is (str/includes? body "PATH is rebuilt by tmux"))
              (is (str/includes? body "href=\"/tasks/t-portal/doc?path=draft-implement.md\""))
              (is (str/includes? body "href=\"/tasks/t-portal/roles/implement\""))
              (is (str/includes? body "claude · kimi · task"))
              (is (str/includes? body "unmet: run — tests green"))
              (is (str/includes? body "PANE-MARK") "the card shows the pane's last line"))))
        (testing "the role page and the pane route serve the archived pane when no server is up"
          (let [body (:body (request env :get (str "/tasks/" id "/roles/implement")))]
            (is (str/includes? body "PANE-MARK"))
            (is (re-find (re-pattern (str "class=\"sub\"><span><a href=\"/tasks/" id "\">" id "</a> · implement")) body)
                "the crumb links back to the task, which is the only way off the role page"))
          (let [r (request env :get (str "/tasks/" id "/roles/implement/pane"))]
            (is (= 200 (:status r)))
            (is (= "text/plain; charset=utf-8" (get (:headers r) "Content-Type")))
            (is (= "line one\nlast pane line PANE-MARK" (:body r))))
          (is (= 404 (:status (request env :get (str "/tasks/" id "/roles/nobody/pane"))))))
        (testing "doc-file: inside the task folder, text, never the clones or worktrees or outside"
          (is (= 200 (:status (request env :get (str "/tasks/" id "/doc") {:query "path=goal.md"}))))
          (is (str/includes? (:body (request env :get (str "/tasks/" id "/doc") {:query "path=draft-implement.md"})) "DRAFT-MARK"))
          (is (= 200 (:status (request env :get (str "/tasks/" id "/doc") {:query "path=state%2Fjudge%2Frun.json"}))))
          (is (= 404 (:status (request env :get (str "/tasks/" id "/doc") {:query "path=repos%2Ffixture%2FREADME.md"}))))
          (is (= 404 (:status (request env :get (str "/tasks/" id "/doc") {:query "path=worktrees%2Fimplement%2FREADME.md"}))))
          (is (= 404 (:status (request env :get (str "/tasks/" id "/doc") {:query "path=..%2F..%2F..%2Fsrc%2Ffixture%2FREADME.md"}))))
          (is (= 404 (:status (request env :get (str "/tasks/" id "/doc") {:query (str "path=" (fs/path sandbox "src" "fixture" "README.md"))}))))
          (is (= 404 (:status (request env :get (str "/tasks/" id "/doc") {:query "path=state"}))) "a directory is not a doc")
          (is (= 404 (:status (request env :get (str "/tasks/" id "/doc") {:query "path=nope.md"}))))
          (fs/create-sym-link (fs/path dir "leak.md") (fs/path sandbox "src" "fixture" "README.md"))
          (is (= 404 (:status (request env :get (str "/tasks/" id "/doc") {:query "path=leak.md"}))) "a symlink out of the folder is outside"))
        (testing "unknown task ids and routes are 404, including traversal in the id"
          (is (= 404 (:status (request env :get "/tasks/nope"))))
          (is (= 404 (:status (request env :get "/tasks/..%2F..%2Fetc"))))
          (is (= 404 (:status (request env :get "/nothing"))))))
      (finally
        (fs/delete-tree sandbox)))))

(deftest kickstart-creates-the-task-writes-roles-and-opens-the-swarm
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-portal-k."})
        home (str (fs/path sandbox "home"))
        src (str (fs/path sandbox "src" "fixture"))
        stubdir (str (fs/path sandbox "stubbin"))
        env {"SWARMKHAZAD_HOME" home "PATH" (str stubdir ":" (System/getenv "PATH"))}
        id "t-kick"]
    (try
      (make-source-repo! src)
      (fs/create-dirs stubdir)
      (fs/copy stub (fs/path stubdir "claude"))
      (fs/set-posix-file-permissions (fs/path stubdir "claude") "rwxr-xr-x")
      (testing "bad input is a 400 with the form and the reason, and nothing is created"
        (let [r (request env :post "/tasks" {:body "task-id=bad%2Fid&repos=&roles=a+claude+none"})]
          (is (= 400 (:status r)))
          (is (str/includes? (:body r) "invalid task id"))
          (is (str/includes? (:body r) "open=\"open\"") "a rejection forces the composer open, or the reason is hidden inside it")
          (is (str/includes? (:body r) "value=\"bad/id\"") "and keeps the id that was typed")
          (is (str/includes? (:body r) "a claude none") "and the roles that were typed, not the placeholder three"))
        (let [r (request env :post "/tasks" {:body (str "task-id=" id "&repos=" src "&roles=")})]
          (is (= 400 (:status r)))
          (is (str/includes? (:body r) "declare at least one role")))
        (is (not (fs/exists? (fs/path home "tasks" id)))))
      (testing "good input: new, roles written, open started, redirect to the task page"
        (let [r (request env :post "/tasks" {:body (str "task-id=" id "&repos=" (java.net.URLEncoder/encode src "UTF-8")
                                                        "&roles=" (java.net.URLEncoder/encode (str "a claude " src " task\nb claude " src " task") "UTF-8"))})
              dir (fs/path home "tasks" id)]
          (is (= 303 (:status r)))
          (is (= (str "/tasks/" id) (get (:headers r) "Location")))
          (is (= (str "a claude " src " task\nb claude " src " task\n") (slurp (str (fs/path dir "roles")))))
          (is (fs/regular-file? (fs/path dir "goal.md")))
          (let [deadline (+ (System/currentTimeMillis) 60000)]
            (while (and (not (fs/regular-file? (fs/path dir "state" "tmux-socket"))) (< (System/currentTimeMillis) deadline))
              (Thread/sleep 500)))
          (is (fs/regular-file? (fs/path dir "state" "tmux-socket")) (str "open did not start: " (slurp (str (fs/path dir "state" "portal-open.log")))))
          (testing "a second kickstart with the same id is refused"
            (let [r2 (request env :post "/tasks" {:body (str "task-id=" id "&repos=&roles=a+claude+none")})]
              (is (= 400 (:status r2)))
              (is (str/includes? (:body r2) (str "task already exists: " id)) "refused by the portal's own check, not by the CLI's path-shaped message")
          (is (= (str "a claude " src " task\nb claude " src " task\n") (slurp (str (fs/path home "tasks" id "roles")))) "the live roles file was not rewritten")))
          (testing "the live pane route reads the real tmux pane"
            (let [deadline (+ (System/currentTimeMillis) 30000)]
              (while (and (not (str/includes? (:body (request env :get (str "/tasks/" id "/roles/a/pane"))) "launch.sh"))
                          (< (System/currentTimeMillis) deadline))
                (Thread/sleep 500)))
            (is (str/includes? (:body (request env :get (str "/tasks/" id "/roles/a/pane"))) "launch.sh")))
          (run {:env env} cli "close" id)))
      (finally
        (fs/delete-tree sandbox)))))
