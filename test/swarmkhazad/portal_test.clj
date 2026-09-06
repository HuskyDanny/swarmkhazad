(ns swarmkhazad.portal-test
  "portal.bb through its request handler: every page is a view of a task folder.
   A project supplies the checkouts and the swarm; the task e2e runs the real
   CLI with the stub harness and a real tmux server."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
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
            (is (str/includes? (:body r) "action=\"/projects\"") "the new-project composer is on the index")
            (is (str/includes? (:body r) "Tasks outside a project") "a task with no project is still reachable")
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
                "the crumb links back to the task, which is the only way off the role page")
            (is (str/includes? body (str "data-role=\"implement\" data-task=\"" id "\""))
                "the poller reads the task and role from escaped attributes")
            (is (str/includes? body "p.dataset.task")
                "and from the dataset at runtime")
            (is (not (re-find (re-pattern (str "<script>[^<]*" id)) body))
                "so neither value is baked into the script string, which is the one raw sink on the page"))
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

(deftest a-project-supplies-the-repos-and-the-roles-so-a-task-only-brings-a-goal
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-portal-k."})
        home (str (fs/path sandbox "home"))
        src (str (fs/path sandbox "src" "fixture"))
        stubdir (str (fs/path sandbox "stubbin"))
        env {"SWARMKHAZAD_HOME" home "SWARMKHAZAD_REPO_ROOTS" (str (fs/path sandbox "src"))
             "PATH" (str stubdir ":" (System/getenv "PATH"))}
        ;; a checkout one level deeper than the root, the shape ~/repos has:
        ;; the checkouts are not all direct children (~/repos/mithra_ai/istari).
        nested (str (fs/path sandbox "src" "group" "nested"))
        project "p-kick"
        id "t-kick"]
    (try
      (make-source-repo! src)
      (make-source-repo! nested)
      (fs/create-dirs stubdir)
      (fs/copy stub (fs/path stubdir "claude"))
      (fs/set-posix-file-permissions (fs/path stubdir "claude") "rwxr-xr-x")
      (testing "the index scans the repo roots, so the checkouts are a list to tick, not a path to type"
        (let [body (:body (request env :get "/"))]
          (is (str/includes? body (str "name=\"repo:" src "\"")) "the checkout under the root is offered")
          (is (str/includes? body (str "name=\"repo:" nested "\""))
              "and one a level deeper, which is the shape ~/repos actually has")
          (is (not (str/includes? body (str "name=\"repo:" (fs/path sandbox "src" "group") "\"")))
              "but not the plain directory holding it")
          (is (str/includes? body "name=\"role:implement\"") "and every stage prompt is a role card")
          (is (str/includes? body "checked=\"checked\" name=\"role:implement\"") "with the default lineup ticked")
          (is (not (str/includes? body "checked=\"checked\" name=\"role:brainstorm\"")) "and nothing else")))
      (testing "a project is refused without a name, a checkout or a role, and nothing is written"
        (let [r (request env :post "/projects" {:body (str "name=bad%2Fid&repo%3A" src "=on&role%3Aimplement=on")})]
          (is (= 400 (:status r)))
          (is (str/includes? (:body r) "invalid project name"))
          (is (str/includes? (:body r) "open=\"open\"") "a rejection forces the composer open")
          (is (str/includes? (:body r) "value=\"bad/id\"") "and keeps the name that was typed")
          (is (str/includes? (:body r) (str "checked=\"checked\" name=\"repo:" src "\"")) "and the checkout that was ticked"))
        (let [r (request env :post "/projects" {:body (str "name=" project "&role%3Aimplement=on")})]
          (is (= 400 (:status r)))
          (is (str/includes? (:body r) "pick at least one checkout")))
        (let [r (request env :post "/projects" {:body (str "name=" project "&repo%3A" src "=on")})]
          (is (= 400 (:status r)))
          (is (str/includes? (:body r) "pick at least one role")))
        (let [r (request env :post "/projects" {:body (str "name=" project "&repo%3A" sandbox "=on&role%3Aimplement=on")})]
          (is (= 400 (:status r)))
          (is (str/includes? (:body r) "not a git checkout") "a path that is not a checkout is refused here, not at open"))
        (is (not (fs/exists? (fs/path home "projects" (str project ".edn"))))))
      (testing "a project written once: the role cards become the swimlane's columns"
        (let [r (request env :post "/projects"
                         {:body (str "name=" project "&repo%3A" src "=on&repo%3A" nested "=on"
                                     "&role%3Aimplement=on&model%3Aimplement=anthropic"
                                     "&repo-of%3Aimplement=" (java.net.URLEncoder/encode src "UTF-8")
                                     "&role%3Arun=on&model%3Arun=kimi"
                                     "&repo-of%3Arun=" (java.net.URLEncoder/encode nested "UTF-8"))})]
          (is (= 303 (:status r)))
          (is (= "/" (get (:headers r) "Location"))))
        (let [stored (edn/read-string (slurp (str (fs/path home "projects" (str project ".edn")))))]
          (is (= [src nested] (:repos stored)) "a project holds more than one checkout")
          (is (= [{:role "implement" :harness "claude" :model "anthropic" :repo src}
                  {:role "run" :harness "claude" :model "kimi" :repo nested}] (:roles stored))
              "each role keeps the checkout its own card named, not the first one"))
        (let [r (request env :post "/projects"
                         {:body (str "name=p-elsewhere&repo%3A" src "=on&role%3Aimplement=on"
                                     "&repo-of%3Aimplement=" (java.net.URLEncoder/encode nested "UTF-8"))})]
          (is (= 400 (:status r)))
          (is (str/includes? (:body r) "a role was pointed at a checkout this project does not hold")))
        (let [body (:body (request env :get "/"))]
          (is (str/includes? body (str "class=\"pname\">" project)))
          (is (str/includes? body "class=\"colname\">implement"))
          (is (str/includes? body "class=\"colname\">run"))
          (is (str/includes? body "class=\"colname\">done") "done is always the last column")
          (is (not (str/includes? body "class=\"colname\">review")) "a role that was not picked is not a column")
          (is (str/includes? body (str "href=\"/projects/" project "/new\"")) "New task goes to the project's own form")))
      (testing "the task form shows the swarm and the checkouts but never asks for them"
        (let [body (:body (request env :get (str "/projects/" project "/new")))]
          (is (str/includes? body "implement (anthropic) in fixture"))
          (is (str/includes? body "run (kimi) in nested") "the form says which checkout each role works in")
          (is (str/includes? body src))
          (is (str/includes? body nested))
          (is (not (str/includes? body "name=\"roles\"")) "there is no roles field to get wrong"))
        (is (= 404 (:status (request env :get "/projects/nope/new")))))
      (testing "a task is refused without a valid id or a goal, and keeps what was typed"
        (let [r (request env :post (str "/projects/" project "/tasks")
                         {:body "task-id=bad%2Fid&goal=implement+%E2%80%94+something"})]
          (is (= 400 (:status r)))
          (is (str/includes? (:body r) "invalid task id"))
          (is (str/includes? (:body r) "implement — something") "the goal survives the rejection"))
        (let [r (request env :post (str "/projects/" project "/tasks") {:body (str "task-id=" id "&goal=")})]
          (is (= 400 (:status r)))
          (is (str/includes? (:body r) "write at least one goal line")))
        (is (not (fs/exists? (fs/path home "tasks" id)))))
      (testing "good input: goal.md, metrics.md, the project link, the project's roles, open started"
        (let [r (request env :post (str "/projects/" project "/tasks")
                         {:body (str "task-id=" id
                                     "&goal=" (java.net.URLEncoder/encode "implement — the route returns 200\nrun — tests green" "UTF-8")
                                     "&not-goal=" (java.net.URLEncoder/encode "no schema change" "UTF-8")
                                     "&bars=" (java.net.URLEncoder/encode "repo tests — bar: exits 0 — measure: `true`" "UTF-8"))})
              dir (fs/path home "tasks" id)]
          (is (= 303 (:status r)))
          (is (= (str "/tasks/" id) (get (:headers r) "Location")))
          (let [goal (slurp (str (fs/path dir "goal.md")))]
            (is (str/includes? goal "- [ ] implement — the route returns 200") "goal lines keep the role prefix the judge grades on")
            (is (str/includes? goal "- [ ] run — tests green"))
            (is (str/includes? goal "## Not-goal\n- no schema change")))
          (is (str/includes? (slurp (str (fs/path dir "metrics.md"))) "repo tests — bar: exits 0"))
          (is (= project (str/trim (slurp (str (fs/path dir "project"))))) "the task names its project, so the swimlane can find it")
          (let [roles (slurp (str (fs/path dir "roles")))]
            (is (str/includes? roles (str "implement claude " src " task model=anthropic")))
            (is (str/includes? roles (str "run claude " nested " task model=kimi"))
                "one task, two checkouts: each role's own repo and vendor reach the roles file")
            (is (not (str/includes? roles "review")) "only the picked roles"))
          (let [deadline (+ (System/currentTimeMillis) 60000)]
            (while (and (not (fs/regular-file? (fs/path dir "state" "tmux-socket"))) (< (System/currentTimeMillis) deadline))
              (Thread/sleep 500)))
          (is (fs/regular-file? (fs/path dir "state" "tmux-socket"))
              (str "open did not start: " (slurp (str (fs/path dir "state" "portal-open.log")))))
          (is (= #{"fixture" "nested"} (set (map fs/file-name (filter fs/directory? (fs/list-dir (fs/path dir "repos"))))))
              "one task, two clones — the project's checkouts both land in it")
          (is (every? #(fs/directory? (fs/path dir "worktrees" %)) ["implement" "run"])
              "and each role has its own worktree off its own clone")
          (testing "the card appears in the project's swimlane, in the lane its own board says"
            (is (str/includes? (:body (request env :get "/")) (str "class=\"tcard\" href=\"/tasks/" id "\""))))
          (testing "a second task with the same id is refused"
            (let [r2 (request env :post (str "/projects/" project "/tasks")
                               {:body (str "task-id=" id "&goal=" (java.net.URLEncoder/encode "implement — again" "UTF-8"))})]
              (is (= 400 (:status r2)))
              (is (str/includes? (:body r2) (str "task already exists: " id))
                  "refused by the portal's own check, not by the CLI's path-shaped message")
              (is (str/includes? (slurp (str (fs/path dir "goal.md"))) "the route returns 200")
                  "the live goal.md was not rewritten")))
          (testing "the live pane route reads the real tmux pane"
            (let [deadline (+ (System/currentTimeMillis) 30000)]
              (while (and (not (str/includes? (:body (request env :get (str "/tasks/" id "/roles/implement/pane"))) "launch.sh"))
                          (< (System/currentTimeMillis) deadline))
                (Thread/sleep 500)))
            (is (str/includes? (:body (request env :get (str "/tasks/" id "/roles/implement/pane"))) "launch.sh")))
          (run {:env env} cli "close" id)))
      (finally
        (fs/delete-tree sandbox)))))
