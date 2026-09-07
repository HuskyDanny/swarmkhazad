(ns swarmkhazad.portal-test
  "portal.bb through its request handler: every page is a view of a task folder.
   A project supplies the checkouts and the swarm; the task e2e runs the real
   CLI with the stub harness and a real tmux server."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [hiccup2.core :as h]
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
        (spit (str (fs/path dir "roles")) "implement claude task model=kimi\nrun claude task\nreview claude task\n")
        (spit (str (fs/path dir "repos")) (str src "\n"))
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
        (write! (fs/path dir "finding.md") "- **FINDING-MARK the exporter already retries** — upstreams.py:88 wraps it\n")
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
            (is (not (str/includes? body "http-equiv=\"refresh\""))
                "no full-page reload: it would throw away the terminal's scroll and selection every 5s")
            (is (str/includes? body "id=\"detail\"") "the detail column is swapped by the poller instead")
            (is (str/includes? body "d.replaceWith(n)") "which is what the poller does")
            ;; Behaviour was verified in a real browser, which this suite cannot
            ;; drive; these two only catch the line being deleted, which is how
            ;; the bug got in — a fresh <details> has no `open`, so an expanded
            ;; escalation snapped shut on the next poll.
            (is (str/includes? body "n.innerHTML===d.innerHTML")
                "an unchanged column is not swapped at all, so a text selection survives")
            (is (str/includes? body "if(open.has(K(x)))x.open=true")
                "and an escalation the reader expanded is re-opened after a swap")
            (is (re-find #"<input disabled=\"disabled\" type=\"checkbox\" /> <span class=\"muted\">implement — </span>the route returns 200 <span class=\"status pending\">pending" body)
                "no verdict names it, and review's met does not carry the line while implement's and run's are unmet → pending, unchecked")
            (is (re-find #"tests green <span class=\"status unmet\">unmet: implement" body))
            (is (re-find #"<input checked=\"checked\" disabled=\"disabled\" type=\"checkbox\" /> <span class=\"muted\">control — </span>already ticked <span class=\"status ticked\">ticked" body))
            (is (not (str/includes? body "<h3 class=\"repo\">"))
                "one repo, so the goals are one list — a heading over every line is noise")
            (is (str/includes? body "<span class=\"lane\">not opened</span>")
                "and no fraction: `implement 1/1` is the same sentence as `implement`")
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
            (testing "a finding has a section of its own and is not an attention item"
              (is (str/includes? body "FINDING-MARK the exporter already retries")
                  "findings are shown — one written nowhere anyone reads is worse than none")
              (is (str/includes? body "finding.md"))
              (is (not (str/includes? (subs body 0 (str/index-of body "finding.md")) "FINDING-MARK"))
                  "and never above it, in Attention: a finding is not waiting on anyone"))
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
        (testing "the pane rail puts the task's terminal beside the task"
          (let [body (:body (request env :get (str "/tasks/" id)))]
            (is (str/includes? body "class=\"rail\""))
            (is (str/includes? body "PANE-MARK") "the pane's content is on the task page, not only on the role page")
            (is (str/includes? body "data-role=\"implement\" data-task=\"t-portal\"")
                "and the first role is what it watches by default")
            (is (str/includes? body (str "class=\"tab on\" href=\"/tasks/" id "?pane=implement\""))
                "the tabs are links, so which pane you are on lives in the URL")
            (is (str/includes? body "session closed — archived pane")
                "a closed task still has a terminal to read back"))
          (let [body (:body (request env :get (str "/tasks/" id) {:query "pane=run"}))]
            (is (str/includes? body "data-role=\"run\"") "?pane= picks the role")
            (is (str/includes? body (str "class=\"tab on\" href=\"/tasks/" id "?pane=run\""))))
          (let [body (:body (request env :get (str "/tasks/" id) {:query "pane=nobody"}))]
            (is (str/includes? body "data-role=\"implement\"")
                "an unknown role in the query falls back to the first, it does not 500 or render an empty rail")))
        (testing "unknown task ids and routes are 404, including traversal in the id"
          (is (= 404 (:status (request env :get "/tasks/nope"))))
          (is (= 404 (:status (request env :get "/tasks/..%2F..%2Fetc"))))
          (is (= 404 (:status (request env :get "/nothing")))))
        ;; Last, because it puts the task in a lane and every assertion above
        ;; reads the lane as `not opened`.
        (testing "a lane whose role holds one repo shows no fraction"
          (run {:env env} "bb" "-e" (str "(load-file \"" scripts "/board_lib.bb\") "
                                         "(board-lib/create-card! (task-lib/task-ctx \"" id "\") \"" id "\" \"implement\")"))
          (is (str/includes? (:body (request env :get (str "/tasks/" id))) "<span class=\"lane\">implement</span>")
              "`implement 1/1` is the same sentence as `implement`, and the fraction is then noise on every card")))
      (finally
        (fs/delete-tree sandbox)))))

(def real-brief
  "The shape a brief actually arrives in — written elsewhere, pasted whole. The
   preamble is the conversation that produced it and must not become a goal."
  (str "Some preamble that came with the paste. Not a goal.\n\n"
       "Goal\n\n"
       "- [ ] superset — superset mcp run starts on the built image, endpoints superset-mcp is non-empty.\n"
       "- [ ] superset — fastmcp is pinned, with the pin's reason recorded.\n\n"
       "Non-goals\n\n"
       "- No ingress, no public origin, no DNS. #781's ClusterIP isolation stays as it is.\n"
       "- Don't upgrade Superset to fix an import. Pin the dependency, not the reverse.\n"
       "- Not in scope: whether the MCP path is /mcp.\n\n"
       "Quantitive bars\n\n"
       "| bar | measure |\n"
       "|---|---|\n"
       "| the import that fails now, resolves | `docker run --rm <img> -c 'import fastmcp'` |\n"
       "| mcp run stays up 60s, no traceback | run it in the built image, bounded wait |\n"
       "| the Service has endpoints | `kubectl -n superset get endpoints superset-mcp` |\n"))

(deftest one-pasted-block-becomes-the-goal-the-not-goals-and-the-bars
  (load-file (str (fs/path repo-root "scripts" "task_lib.bb")))
  (load-file (str (fs/path repo-root "scripts" "project_lib.bb")))
  (load-file (str (fs/path repo-root "scripts" "run_evidence.bb")))
  (let [parse @(resolve 'project-lib/parse-brief)
        heading @(resolve 'project-lib/heading)
        {:keys [goal not-goal bars seen]} (parse real-brief)]

    (testing "each section keeps its own lines and nothing leaks between them"
      (is (= 2 (count goal)))
      (is (= 3 (count not-goal)))
      (is (= 3 (count bars))))

    (testing "the preamble is dropped — there is no honest way to read it as a goal"
      (is (not-any? #(str/includes? % "preamble") (concat goal not-goal bars))))

    (testing "the checkbox marker is stripped, and the role prefix the judge grades on is kept"
      (is (str/starts-with? (first goal) "superset — superset mcp run starts")))

    (testing "`Non-goals` is a not-goal, not a goal — the nearer name wins, not the first"
      (is (str/starts-with? (first not-goal) "No ingress"))
      (is (not-any? #(str/includes? % "No ingress") goal)))

    (testing "a typo in the heading still finds the section"
      (is (= #{:goal :not-goal :bars} seen) "`Quantitive bars` is one edit from `Quantitative bars`"))

    (testing "the table's own scaffolding is not a bar"
      (is (not-any? #(str/includes? % "|") bars) "no separator row and no column header")
      (is (str/starts-with? (first bars) "the import that fails now, resolves — measure: ")))

    (testing "the bars come back out as measures run_evidence can run"
      (let [ctx (@(resolve 'task-lib/task-ctx) "t-brief")
            runnable (@(resolve 'run-evidence/bars) ctx
                      (@(resolve 'project-lib/metrics-md) "t-brief" bars))]
        (is (= 3 (count runnable)) "every bar comes back, so none of them can go missing from the page")
        (is (= 2 (count (filter :command runnable))) "and two of the three cells carry a command")
        (is (= "docker run --rm <img> -c 'import fastmcp'" (:command (first runnable))))
        (let [prose (first (remove :command runnable))]
          (is (= "mcp run stays up 60s, no traceback" (:name prose)))
          (is (str/includes? (:measure prose) "bounded wait")
              "the prose measure is carried, so the page can say who has to run it"))))

    (testing "a heading is short and unbulleted, so a sentence about goals is not one"
      (is (= :goal (heading "Goal")))
      (is (= :goal (heading "## Goal")))
      (is (= :goal (heading "goals:")))
      (is (= :not-goal (heading "**Not-goal**")))
      (is (= :not-goal (heading "out of scope")))
      (is (= :bars (heading "Quality bars")))
      (is (= :bars (heading "Metrics")))
      (is (nil? (heading "* Goals"))
          "`*` is a bullet AND emphasis, so this normalises to exactly `goals`: only the
           bullet check keeps a list item from opening a section")
      (is (nil? (heading "- No ingress, no public origin")))
      (is (nil? (heading "Cause: fastmcp is the one unpinned dependency in the Dockerfile")))
      (is (nil? (heading "Notes"))))

    (testing "a block with no headings parses to nothing rather than to everything"
      (let [r (parse (str "staging's MCP pod is crash-looping. The web tier is fine.\n"
                          "superset-mcp-757cc8b6b8-27n9k 0/1 CrashLoopBackOff 10 restarts\n"
                          "Error: MCP service dependencies not installed\n"))]
        (is (empty? (:seen r)))
        (is (empty? (:goal r))
            "this exact paste once became twenty-five goal checkboxes")))))

(deftest an-attention-line-can-be-crossed-off-and-put-back
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-portal-att."})
        home (str (fs/path sandbox "home"))
        src (str (fs/path sandbox "src" "fixture"))
        env {"SWARMKHAZAD_HOME" home}
        id "t-att"
        page (fn [] (:body (request env :get (str "/tasks/" id))))
        index (fn [] (:body (request env :get "/")))
        keys-on-page (fn [b] (re-seq #"name=\"key\" type=\"hidden\" value=\"([0-9a-f]{16})\"" b))]
    (try
      (make-source-repo! src)
      (run {:env env} cli "new" id "--repo" src)
      (let [dir (fs/path home "tasks" id)]
        (spit (str (fs/path dir "roles")) "implement claude task\n")
        (spit (str (fs/path dir "repos")) (str src "\n"))
        (run {:env env} cli "prepare" id)
        ;; In a project, so the assertions below run against the swimlane card
        ;; and not the loose-task row — they count attention by different code
        ;; paths, and only one of them was covered.
        (write! (fs/path home "projects" "p-att.edn")
                (pr-str {:name "p-att" :repos [src]
                         :roles [{:role "implement" :harness "claude" :model "anthropic" :repo src}]}))
        (spit (str (fs/path dir "project")) "p-att\n")
        ;; and in a lane, because a task with no board card renders as a stray
        ;; line rather than a card, and the card is the count under test.
        (write! (fs/path dir "state" "board" "tasks.tsv")
                (str id "\timplement\t2026-09-07T00:00:00Z\t2026-09-07T00:00:00Z\n"))
        (spit (str (fs/path dir "escalation.md"))
              (str "- **the first thing** — needs Allen\n"
                   "- **the second thing** — also needs Allen\n"))
        (write! (fs/path dir "state" "denials.jsonl") "{\"tool\":\"Edit\"}\n")

        (testing "every line starts open, and the board card agrees with the page"
          (let [b (page)]
            (is (= 3 (count (keys-on-page b))) "two escalations and the denials line")
            (is (str/includes? b "<span class=\"status unmet\">3</span>")))
          (is (str/includes? (index) "class=\"tcard\"") "the task is a card in its project's swimlane")
          (is (str/includes? (index) "3 needs you")))

        (let [k (second (first (keys-on-page (page))))]
          (testing "crossing one off drops it from both counts without deleting it"
            (let [r (request env :post (str "/tasks/" id "/attention")
                             {:body (str "key=" k "&do=handle")})]
              (is (= 303 (:status r)))
              (is (= (str "/tasks/" id) (get (:headers r) "Location"))))
            (let [b (page)]
              (is (str/includes? b "<span class=\"status unmet\">2</span>"))
              (is (str/includes? b "1 crossed off"))
              (is (str/includes? b "class=\"att off\"") "and it renders struck through, still there")
              (is (= 3 (count (keys-on-page b))) "nothing was removed from the list"))
            (is (str/includes? (index) "2 needs you") "the swimlane card cannot disagree with the page"))

          (testing "the escalation file is untouched — the roles own it, the portal does not"
            (is (str/includes? (slurp (str (fs/path dir "escalation.md"))) "the first thing"))
            (is (= 2 (count (str/split-lines (slurp (str (fs/path dir "escalation.md"))))))))

          (testing "putting it back removes the row rather than recording both states"
            (request env :post (str "/tasks/" id "/attention") {:body (str "key=" k "&do=open")})
            (let [f (fs/path dir "state" "attention-handled.tsv")]
              (is (str/blank? (slurp (str f)))))
            (is (str/includes? (page) "<span class=\"status unmet\">3</span>"))))

        (testing "each line has its own key, so crossing one off does not cross its neighbour"
          (let [ks (map second (keys-on-page (page)))]
            (is (= 3 (count (distinct ks))) "three lines, three keys")
            (request env :post (str "/tasks/" id "/attention") {:body (str "key=" (first ks) "&do=handle")})
            (let [b (page)]
              (is (str/includes? b "<span class=\"status unmet\">2</span>") "exactly one went")
              (is (str/includes? b "1 crossed off")))
            (request env :post (str "/tasks/" id "/attention") {:body (str "key=" (first ks) "&do=open")})))

        (testing "the kind is part of the key, so two kinds reading the same cannot collide"
          ;; A failed-mail item's text is the path; an escalation line can say
          ;; exactly that path. Contrived, but legal input — and without the
          ;; kind in the hash, crossing off one would cross off the other.
          (let [path "mail/run/failed/50_x_from_run_to_nobody.handoff"]
            (write! (fs/path dir "mail" "run" "failed" "50_x_from_run_to_nobody.handoff") "id: x\n")
            (spit (str (fs/path dir "escalation.md")) (str "- " path "\n") :append true)
            (let [b (page)
                  ks (map second (keys-on-page b))]
              (is (= (count ks) (count (distinct ks)))
                  "the escalation and the failed mail read identically and still key apart"))
            ;; put the fixture back for the assertions that follow
            (fs/delete (fs/path dir "mail" "run" "failed" "50_x_from_run_to_nobody.handoff"))
            (spit (str (fs/path dir "escalation.md"))
                  (str "- **the first thing** — needs Allen\n"
                       "- **the second thing** — also needs Allen\n"))))

        (testing "a key this task never produced is ignored, so the store cannot be grown from a form"
          (request env :post (str "/tasks/" id "/attention") {:body "key=deadbeefdeadbeef&do=handle"})
          (let [f (fs/path dir "state" "attention-handled.tsv")]
            (is (or (not (fs/exists? f)) (str/blank? (slurp (str f))))))
          (is (str/includes? (page) "<span class=\"status unmet\">3</span>"))))
      (finally
        (fs/delete-tree sandbox)))))

(deftest the-model-sorts-the-paste-but-the-parser-still-decides
  (load-file (str (fs/path repo-root "scripts" "task_lib.bb")))
  (load-file (str (fs/path repo-root "scripts" "project_lib.bb")))
  (let [normalize @(resolve 'project-lib/normalize-brief)
        messy (str "so the pod is crash-looping and the web tier is fine\n"
                   "superset-mcp-757cc8b6b8 0/1 CrashLoopBackOff 10 restarts\n"
                   "we need mcp run to start and the endpoints to be non-empty\n"
                   "don't touch the web tier\n")
        sorted-md (str "## Goal\n- implement — superset mcp run starts and endpoints are non-empty\n\n"
                       "## Not-goal\n- Don't touch the web tier.\n\n"
                       "## Quantitative bars\n| bar | measure |\n|---|---|\n"
                       "| the Service has endpoints | `kubectl get endpoints superset-mcp` |\n")]

    (testing "a paste with no headings is sorted by the model into ones the parser reads"
      (let [asked (atom nil)
            r (normalize (fn [req] (reset! asked req) {:text sorted-md :cost 0.011 :model "sonnet"}) messy)]
        (is (= messy (:prompt @asked)) "the paste goes to the model verbatim")
        (is (str/includes? (:system @asked) "You do not write one")
            "and the system prompt is the one that tells it to sort, not to author")
        (is (= 1 (count (:goal (:parsed r)))))
        (is (= 1 (count (:not-goal (:parsed r)))))
        (is (= 1 (count (:bars (:parsed r)))))
        (is (not (:fell-back r)))
        (is (= 0.011 (:cost r)))
        (is (not-any? #(str/includes? % "CrashLoopBackOff") (:goal (:parsed r)))
            "the pod listing was evidence of the problem, not a goal")))

    (testing "the model's answer is re-parsed, so what the reviewer edits is what opens"
      (let [r (normalize (constantly {:text sorted-md}) messy)]
        (is (= (:goal (:parsed r)) (:goal (@(resolve 'project-lib/parse-brief) (:markdown r))))
            "markdown in, same sections out — there is no second path into goal.md")))

    (testing "a model that fails leaves the parser's own answer, and says so"
      (let [headed (str "## Goal\n- implement — a thing\n\n## Not-goal\n- another thing\n")]
        ;; The three notes are pinned separately, not just as "fell back". They
        ;; are what the reviewer reads to decide whether to retry or to edit,
        ;; and "the model returned nothing" and "its answer had no Goal
        ;; section" are different problems with different next actions.
        (doseq [[why reply expected]
                [["an error" {:error "timed out after 240s"} "timed out after 240s"]
                 ["an empty answer" {:text "   "} "the model returned nothing"]
                 ["an answer with no Goal" {:text "## Notes\n- nothing useful\n"} "had no Goal section"]]]
          (let [r (normalize (constantly reply) headed)]
            (is (:fell-back r) (str "falls back on " why))
            (is (str/includes? (:note r) "the parser alone"))
            (is (str/includes? (:note r) expected) (str "and says which failure it was: " why))
            (is (= 1 (count (:goal (:parsed r))))
                "and the parser's own split still opens the task, rather than nothing")))))

    (testing "a blank paste never reaches the model"
      (let [called (atom 0)
            r (normalize (fn [_] (swap! called inc) {:text sorted-md}) "   ")]
        (is (zero? @called))
        (is (empty? (:goal (:parsed r))))))))

(deftest the-pane-renders-a-terminal-and-still-escapes-what-is-in-it
  ;; every other test in this file drives the handler in a child bb, so the
  ;; namespace is not loaded here until we ask for it.
  (load-file (str (fs/path repo-root "scripts" "portal.bb")))
  (let [f @(resolve 'portal/ansi->hiccup)
        E (str (char 27))
        render (fn [s] (str (h/html [:pre (f s)])))]
    (testing "SGR runs become spans; a reset ends the run"
      (is (= (list "plain " [:span {:class "f2"} "green"] " back")
             (f (str "plain " E "[32mgreen" E "[0m back")))))
    (testing "codes combine, and the class list is stable so the output is diffable"
      (is (= (list [:span {:class "b f1"} "bold red"])
             (f (str E "[1;31mbold red")))))
    (testing "a code we do not render is dropped, never guessed at, and its text survives"
      (is (= (list "fancy" " plain") (f (str E "[38;5;213mfancy" E "[0m plain")))
          "256-colour is ignored: a wrong colour reads as meaning that is not there"))
    (testing "escapes that are not SGR are dropped — tmux emits title sets and cursor moves"
      (is (= (list "kept") (f (str E "]0;a title" (char 7) "kept")))))
    (testing "text with no escapes at all passes through whole"
      (is (= (list "no escapes at all") (f "no escapes at all"))))
    (testing "it returns a seq, because hiccup reads a vector as an element"
      (is (seq? (f "x")) "a vector here would render the first line as a tag name")
      (is (= "<pre>x</pre>" (render "x"))))
    (testing "pane content is still escaped — the terminal look must not open a sink"
      (is (= "<pre>a<span class=\"f1\">&lt;b&gt;&amp;c</span></pre>"
             (render (str "a" E "[31m<b>&c" E "[0m"))))
      (is (str/includes? (render (str E "[32m</pre><script>alert(1)</script>"))
                         "&lt;/pre&gt;&lt;script&gt;")
          "markup inside a coloured run is escaped like any other text"))
    (testing "a cursor move is dropped like any other non-SGR escape"
      (is (= (list "ab") (f (str "a" E "[2Kb")))))
    (testing "a long plain run does not overflow the stack"
      ;; A regex that also matched the text between escapes recursed once per
      ;; character, so a pane spinning on a long error 500'd the whole page.
      (let [long-line (apply str (repeat 200000 "x"))]
        (is (= (list long-line) (f long-line)))
        (is (= (list [:span {:class "f1"} long-line])
               (f (str E "[31m" long-line))))))))

(deftest the-pane-takes-typing-and-an-interrupt-from-the-page
  ;; A running agent owns its terminal: the inbox it reads between turns is no
  ;; use to one already mid-turn, so the only way to reach it is to type. That
  ;; was a tmux attach in another window until now. The pane here is `cat -v`,
  ;; which renders control characters visibly — so an Escape is observable as
  ;; ^[ rather than having to be taken on trust.
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-portal-keys."})
        home (str (fs/path sandbox "home"))
        src (str (fs/path sandbox "src" "fixture"))
        env {"SWARMKHAZAD_HOME" home}
        id "t-keys"
        socket (str (fs/path sandbox "k.sock"))
        tmux (fn [& args] (apply run {:ok? false} "tmux" "-S" socket args))
        pane (fn [] (:out (tmux "capture-pane" "-p" "-t" "sk-implement")))
        post (fn [role body] (request env :post (str "/tasks/" id "/roles/" role "/keys") {:body body}))]
    (try
      (make-source-repo! src)
      (run {:env env} cli "new" id "--repo" src)
      (let [dir (fs/path home "tasks" id)]
        (spit (str (fs/path dir "roles")) "implement claude task\n")
        (spit (str (fs/path dir "repos")) (str src "\n"))
        (run {:env env} cli "prepare" id)
        ;; the socket file is what the portal reads to find the server; a real
        ;; `open` writes it, and this test stands in for that one line.
        (write! (fs/path dir "state" "tmux-socket") socket)
        (tmux "new-session" "-d" "-s" "sk-implement" "cat -v")
        (Thread/sleep 500)
        (try
          (testing "typing lands in the pane"
            (let [r (post "implement" "do=send&text=hello+there")]
              (is (= 303 (:status r)) "and the browser goes back to the pane it typed into")
              (is (= (str "/tasks/" id "?pane=implement") (get (:headers r) "Location"))))
            (Thread/sleep 700)
            (is (str/includes? (pane) "hello there")))

          (testing "the text is typed, never interpreted — a tmux key name arrives as characters"
            (post "implement" "do=send&text=C-c")
            (Thread/sleep 700)
            (is (str/includes? (pane) "C-c") "sent with -l, so it is three characters")
            (is (str/includes? (:out (tmux "list-sessions")) "sk-implement")
                "and cat is still running: C-c was not a signal"))

          (testing "stop sends Escape, which cat -v shows as ^["
            (post "implement" "do=stop")
            (Thread/sleep 700)
            (is (str/includes? (pane) "^[")))

          (testing "an empty box types nothing rather than submitting a bare newline"
            (let [before (pane)]
              (is (= 303 (:status (post "implement" "do=send&text="))))
              (Thread/sleep 400)
              (is (= before (pane)))))

          (testing "a role this task never declared is not a pane to type into"
            (is (= 404 (:status (post "nobody" "do=send&text=x")))))
          (finally (tmux "kill-server"))))
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
          (is (not (str/includes? body "checked=\"checked\" name=\"role:brainstorm\"")) "and nothing else")
          (is (not (str/includes? body "name=\"role:constitution\""))
              "constitution is the preamble every role carries, not a role")
          (is (not (str/includes? body "name=\"role:default\""))
              "and default is the fallback for a role with no prompt of its own")
          (is (apply < (map #(str/index-of body (str "name=\"role:" % "\""))
                            ["specifier" "implement" "review" "hardener" "qa"]))
              "the cards run in pipeline order, because that order becomes the swimlane's columns")))
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
                                     "&role%3Arun=on&model%3Arun=kimi")})]
          (is (= 303 (:status r)))
          (is (= "/" (get (:headers r) "Location"))))
        (let [stored (edn/read-string (slurp (str (fs/path home "projects" (str project ".edn")))))]
          (is (= [src nested] (:repos stored)) "a project holds more than one checkout")
          (is (= [{:role "implement" :harness "claude" :model "anthropic"}
                  {:role "run" :harness "claude" :model "kimi"}] (:roles stored))
              "a role names no checkout: it can work in any of the project's repos"))
        (let [body (:body (request env :get "/"))]
          (is (str/includes? body (str "class=\"pname\">" project)))
          (is (str/includes? body "class=\"colname\">implement"))
          (is (str/includes? body "class=\"colname\">run"))
          (is (str/includes? body "class=\"colname\">done") "done is always the last column")
          (is (not (str/includes? body "class=\"colname\">review")) "a role that was not picked is not a column")
          (is (str/includes? body (str "href=\"/projects/" project "/new\"")) "New task goes to the project's own form")
          (is (not (str/includes? body "repo-of:"))
              "no role names a checkout: every role can work in every repo the project holds")))
      (testing "a second project with the same name is refused, and the first is untouched"
        (let [r (request env :post "/projects"
                         {:body (str "name=" project "&repo%3A" src "=on&role%3Areview=on")})]
          (is (= 400 (:status r)))
          (is (str/includes? (:body r) (str "project already exists: " project))))
        (let [stored (edn/read-string (slurp (str (fs/path home "projects" (str project ".edn")))))]
          (is (= [{:role "implement" :harness "claude" :model "anthropic"}
                  {:role "run" :harness "claude" :model "kimi"}] (:roles stored))
              "create is not a silent edit — that is what /projects/<name> is for")))
      (testing "the task form shows the swarm and the checkouts but never asks for them"
        (let [body (:body (request env :get (str "/projects/" project "/new")))]
          (is (str/includes? body "implement (anthropic)"))
          (is (str/includes? body "run (kimi)"))
          (is (str/includes? body src))
          (is (str/includes? body nested))
          (is (not (str/includes? body "name=\"roles\"")) "there is no roles field to get wrong"))
        (is (= 404 (:status (request env :get "/projects/nope/new")))))
      (testing "a task is refused without a valid id or a goal, and keeps what was typed"
        (let [r (request env :post (str "/projects/" project "/tasks")
                         {:body (str "task-id=bad%2Fid&brief="
                                     (java.net.URLEncoder/encode "Goal\nimplement — something" "UTF-8"))})]
          (is (= 400 (:status r)))
          (is (str/includes? (:body r) "invalid task id"))
          (is (str/includes? (:body r) "implement — something") "the brief survives the rejection"))
        (let [r (request env :post (str "/projects/" project "/tasks")
                         {:body (str "task-id=" id "&brief="
                                     (java.net.URLEncoder/encode "Goal\n\nNot-goal\n- nothing" "UTF-8"))})]
          (is (= 400 (:status r)))
          (is (str/includes? (:body r) "the Goal section is empty")))
        (testing "an unstructured paste is refused rather than read as goal lines"
          (let [r (request env :post (str "/projects/" project "/tasks")
                           {:body (str "task-id=" id "&brief="
                                       (java.net.URLEncoder/encode
                                        "the pod is crash-looping\nError: no module named fastmcp\npip install fastmcp"
                                        "UTF-8"))})]
            (is (= 400 (:status r)))
            (is (str/includes? (:body r) "no section headings found"))))
        (is (not (fs/exists? (fs/path home "tasks" id)))))
      (testing "good input: goal.md, metrics.md, the project link, the project's roles, open started"
        (let [r (request env :post (str "/projects/" project "/tasks")
                         {:body (str "task-id=" id "&brief="
                                     (java.net.URLEncoder/encode
                                      (str "## Goal\n"
                                           "- [ ] implement — the route returns 200\n"
                                           "- [ ] run — tests green\n\n"
                                           "## Non-goals\n"
                                           "- no schema change\n\n"
                                           "## Quantitative bars\n"
                                           "| bar | threshold | measure |\n"
                                           "|---|---|---|\n"
                                           "| repo tests | exits 0 | `true` |\n")
                                      "UTF-8"))})
              dir (fs/path home "tasks" id)]
          (is (= 303 (:status r)))
          (is (= (str "/tasks/" id) (get (:headers r) "Location")))
          (let [goal (slurp (str (fs/path dir "goal.md")))]
            (is (str/includes? goal "- [ ] implement — the route returns 200") "goal lines keep the role prefix the judge grades on")
            (is (str/includes? goal "- [ ] run — tests green"))
            (is (str/includes? goal "## Not-goal\n- no schema change")))
          (is (str/includes? (slurp (str (fs/path dir "metrics.md"))) "repo tests — bar: exits 0"))
          (is (= project (str/trim (slurp (str (fs/path dir "project"))))) "the task names its project, so the swimlane can find it")
          (let [roles (slurp (str (fs/path dir "roles")))
                repos (slurp (str (fs/path dir "repos")))]
            (is (str/includes? roles "implement claude task model=anthropic"))
            (is (str/includes? roles "run claude task model=kimi") "each role's vendor reaches the roles file")
            (is (not (str/includes? roles "review")) "only the picked roles")
            (is (str/includes? repos src) "and the project's checkouts reach the repos file")
            (is (str/includes? repos nested)))
          (let [deadline (+ (System/currentTimeMillis) 60000)]
            (while (and (not (fs/regular-file? (fs/path dir "state" "tmux-socket"))) (< (System/currentTimeMillis) deadline))
              (Thread/sleep 500)))
          (is (fs/regular-file? (fs/path dir "state" "tmux-socket"))
              (str "open did not start: " (slurp (str (fs/path dir "state" "portal-open.log")))))
          (is (= #{"fixture" "nested"} (set (map fs/file-name (fs/list-dir (fs/path dir "worktrees")))))
              "one task, two worktrees — one per checkout, added from the sources")
          (is (= ["implement_fixture" "implement_nested" "run_fixture" "run_nested"]
                 (mapv #(first (str/split % #"\t"))
                       (str/split-lines (slurp (str (fs/path dir "state" "sessions.tsv"))))))
              "and a session per (role, repo), because no goal line tagged a repo")
          (testing "the card appears in the project's swimlane, in the lane its own board says"
            (is (str/includes? (:body (request env :get "/")) (str "class=\"tcard\" href=\"/tasks/" id "\""))))
          (testing "a second task with the same id is refused"
            (let [r2 (request env :post (str "/projects/" project "/tasks")
                               {:body (str "task-id=" id "&brief="
                                           (java.net.URLEncoder/encode "Goal\n- implement — again" "UTF-8"))})]
              (is (= 400 (:status r2)))
              (is (str/includes? (:body r2) (str "task already exists: " id))
                  "refused by the portal's own check, not by the CLI's path-shaped message")
              (is (str/includes? (slurp (str (fs/path dir "goal.md"))) "the route returns 200")
                  "the live goal.md was not rewritten")))
          (testing "the live pane route reads the real tmux pane"
            (let [deadline (+ (System/currentTimeMillis) 30000)]
              (while (and (not (str/includes? (:body (request env :get (str "/tasks/" id "/roles/implement_fixture/pane"))) "launch.sh"))
                          (< (System/currentTimeMillis) deadline))
                (Thread/sleep 500)))
            (is (str/includes? (:body (request env :get (str "/tasks/" id "/roles/implement_fixture/pane"))) "launch.sh")))
          (testing "two repos, so the goal list is grouped under the repo each line names"
            (let [body (:body (request env :get (str "/tasks/" id)))]
              (is (str/includes? body "<h3 class=\"repo\">every repo</h3>")
                  "nothing is tagged yet, and an untagged line belongs to all of them"))
            ;; goal.md is 444 on purpose. Rewriting it here is the test standing
            ;; in for the human who writes the tags, not the portal reaching in.
            (fs/set-posix-file-permissions (fs/path dir "goal.md") "rw-r--r--")
            (spit (str (fs/path dir "goal.md"))
                  (str "# " id "\n\n## Goal\n"
                       "- [ ] implement @fixture — the route returns 200\n"
                       "- [ ] implement @nested — the exporter is wired\n"
                       "- [ ] run — tests green\n"))
            (let [body (:body (request env :get (str "/tasks/" id)))]
              (is (str/includes? body "<h3 class=\"repo\">fixture</h3>"))
              (is (str/includes? body "<h3 class=\"repo\">nested</h3>"))
              (is (str/includes? body "<h3 class=\"repo\">every repo</h3>"))
              (is (< (str/index-of body "the route returns 200")
                     (str/index-of body "the exporter is wired")
                     (str/index-of body "tests green"))
                  "each line under its own repo, and the untagged one last")
              (is (= 1 (count (re-seq #"the route returns 200" body)))
                  "under its own repo and nowhere else — a line repeated per repo is a line graded twice")
              (is (= 1 (count (re-seq #"the exporter is wired" body))))))
          (testing "the lane says how much of the role is done, not which repos it holds"
            (run {:env env} "bb" "-e"
                 (str "(load-file \"" scripts "/board_lib.bb\") "
                      "(let [ctx (task-lib/task-ctx \"" id "\")] "
                      "(board-lib/hand-off! ctx \"" id "\" \"implement_fixture\" \"run\"))"))
            (is (str/includes? (:body (request env :get (str "/tasks/" id))) "implement 1/2")
                "one of implement's two repos has handed off; the lane has not moved")
            (is (str/includes? (:body (request env :get "/")) "<span class=\"muted\">1/2</span>")
                "and the swimlane card carries the same fraction beside its status"))
          (testing "a shipped task has a column of its own, between the last role and done"
            ;; `in-review` is the state task.md adds so days of waiting read as
            ;; waiting rather than as a stall. With no column for it the card
            ;; fell through to `stray` and rendered as "not in a lane yet" —
            ;; which is exactly the reading it exists to prevent.
            (run {:env env} "bb" "-e"
                 (str "(load-file \"" scripts "/board_lib.bb\") "
                      "(board-lib/set-lane! (task-lib/task-ctx \"" id "\") \"" id "\" "
                      "board-lib/review-lane)"))
            (let [body (:body (request env :get "/"))]
              (is (str/includes? body "in-review") "the lane is drawn")
              (is (not (str/includes? body "not in a lane yet"))
                  "and the card is in it, not stranded beside the board")
              (is (< (.indexOf body ">run<") (.indexOf body ">in-review<") (.indexOf body ">done<"))
                  "after the last role and before done — a shipped task is not a finished one")))
          (run {:env env} cli "close" id)))
      (testing "a project is editable: the form comes back filled in, and saving rewrites it"
        (let [body (:body (request env :get (str "/projects/" project "/edit")))]
          (is (str/includes? body (str "checked=\"checked\" name=\"repo:" src "\"")))
          (is (str/includes? body (str "checked=\"checked\" name=\"repo:" nested "\"")))
          (is (str/includes? body "checked=\"checked\" name=\"role:implement\""))
          (is (str/includes? body "checked=\"checked\" name=\"role:run\""))
          (is (not (str/includes? body "checked=\"checked\" name=\"role:review\"")))
          (is (str/includes? body "readonly=\"readonly\"")
              "the name is fixed: a task points at its project by name, so a rename orphans it")
          (is (str/includes? body "Save project"))
          (is (str/includes? body "Delete project")))
        (is (= 404 (:status (request env :get "/projects/nope/edit"))))
        (let [r (request env :post (str "/projects/" project)
                         {:body (str "repo%3A" src "=on&role%3Aimplement=on&model%3Aimplement=kimi")})]
          (is (= 303 (:status r)))
          (is (= "/" (get (:headers r) "Location"))))
        (let [stored (edn/read-string (slurp (str (fs/path home "projects" (str project ".edn")))))]
          (is (= [src] (:repos stored)) "a checkout can be dropped")
          (is (= [{:role "implement" :harness "claude" :model "kimi"}] (:roles stored))
              "and a role, and a role's vendor, all in one save"))
        (testing "a bad edit is refused and the project on disk is untouched"
          (let [r (request env :post (str "/projects/" project) {:body "role%3Aimplement=on"})]
            (is (= 400 (:status r)))
            (is (str/includes? (:body r) "pick at least one checkout")))
          (is (= [src] (:repos (edn/read-string (slurp (str (fs/path home "projects" (str project ".edn"))))))))))
      (testing "and deletable: the lineup goes, the task it opened does not"
        (is (= 303 (:status (request env :post (str "/projects/" project "/delete")))))
        (is (not (fs/exists? (fs/path home "projects" (str project ".edn")))))
        (is (fs/directory? (fs/path home "tasks" id)) "the work survives its project")
        (let [body (:body (request env :get "/"))]
          (is (str/includes? body "Tasks outside a project"))
          (is (str/includes? body (str "href=\"/tasks/" id "\"")) "and the task is still reachable"))
        (is (= 404 (:status (request env :post (str "/projects/" project "/delete"))))))
      (finally
        (fs/delete-tree sandbox)))))
