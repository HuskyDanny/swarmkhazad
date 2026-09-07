#!/usr/bin/env bb

;; portal.bb — the one always-on surface: a local web page over ~/.swarmkhazad.
;;
;; Server-rendered, read-mostly. Every page is a view of the task folder:
;;   /                          task list, board lanes, attention counts, kickstart form
;;   /tasks/<id>                goal.md with live checkboxes (from the judge's verdicts),
;;                              metrics bars with the latest evidence, the three bullet
;;                              files, the board card, Attention, role cards
;;   /tasks/<id>/roles/<role>   the role's pane, streamed by polling
;;   /tasks/<id>/roles/<role>/pane   text/plain: live tmux capture, else the archive
;;   POST /tasks/<id>/roles/<role>/keys  type into that pane, or Escape to interrupt it
;;   POST /projects/<name>/review    sort a pasted brief, then show it for review
;;   POST /tasks/<id>/summary   ask whether the work is ready to merge, for its goals
;;   /projects                  POST: create a project (checkouts + role lineup)
;;   /projects/<name>/new       the only task form: goal, not-goal, bars
;;   /projects/<name>/tasks     POST: scaffold from the project, then open
;;   /tasks/<id>/doc?path=<rel> any text file inside the task folder (doc-file)
;;   POST /tasks                kickstart: new + roles + open, then redirect
;;
;; Nothing here writes into a task folder except kickstart, which only calls the
;; CLI. The portal never ticks a goal box: the checkboxes show what the judge
;; said, and goal.md itself stays 444. The keys route writes nothing either —
;; it types into a terminal the page already prints the attach command for.

(ns portal
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [org.httpkit.server :as http]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "task_lib.bb")))
(load-file (str (fs/path script-dir "project_lib.bb")))
(load-file (str (fs/path script-dir "handoff_lib.bb")))
(load-file (str (fs/path script-dir "board_lib.bb")))
(load-file (str (fs/path script-dir "run_evidence.bb")))
(load-file (str (fs/path script-dir "telemetry.bb")))
(load-file (str (fs/path script-dir "ask.bb")))
(load-file (str (fs/path script-dir "summary.bb")))

(def cli (str (fs/path script-dir "swarmkhazad.bb")))
(def default-port 8765)
(def pane-tail-lines 400)

;; ---------------------------------------------------------------- reading

(defn text [path]
  (when (fs/regular-file? path) (slurp (str path))))

(defn nonblank-lines [s]
  (->> (str/split-lines (or s "")) (map str/trim) (remove str/blank?) vec))

(defn goal-lines
  "The checkbox lines of goal.md's Goal section: {:text :ticked}."
  [goal-md]
  (->> (str/split-lines (or goal-md ""))
       (drop-while #(not (re-matches #"(?i)##\s+goal\s*" (str/trim %))))
       rest
       (take-while #(not (str/starts-with? (str/trim %) "## ")))
       (keep #(when-let [[_ box body] (re-matches #"\s*- \[([ xX])\]\s*(.*)" %)]
                {:text (str/trim body) :ticked (not= " " box)}))
       vec))

(defn verdicts
  "role → the latest judge verdict, from state/judge/<role>.json."
  [ctx]
  (let [dir (fs/path (:state-dir ctx) "judge")]
    (if (fs/directory? dir)
      (into (sorted-map)
            (for [f (fs/glob dir "*.json")
                  :let [v (try (json/parse-string (slurp (str f)) true) (catch Exception _ nil))]
                  :when v]
              [(str/replace (fs/file-name f) #"\.json$" "") v]))
      {})))

(defn mentions? [item line]
  (let [a (str/lower-case (str item)) b (str/lower-case line)]
    (or (str/includes? b a) (str/includes? a b))))

(defn goal-status
  "What the checkbox shows: :ticked (control ticked it in goal.md), :unmet (a
   verdict names it), :met (every verdict is met), :pending (no verdict yet)."
  [{:keys [text ticked]} verdicts]
  (let [naming (for [[role v] verdicts :when (some #(mentions? % text) (:unmet v))] role)]
    (cond
      ticked {:status :ticked :roles []}
      (seq naming) {:status :unmet :roles (vec naming)}
      (and (seq verdicts) (every? :met (vals verdicts))) {:status :met :roles (vec (keys verdicts))}
      :else {:status :pending :roles []})))

(defn evidence-for [ctx bar-id]
  (let [f (fs/path (:evidence-dir ctx) (str bar-id ".txt"))]
    (when-let [s (text f)]
      (let [[head out] (str/split s #"--- output ---\n" 2)
            headers (into {} (for [l (str/split-lines head) :let [[k v] (str/split l #": " 2)] :when v] [k v]))]
        {:exit (get headers "exit") :at (get headers "started_at")
         :tail (str/join "\n" (take-last 6 (str/split-lines (or out head))))}))))

(defn bars-with-evidence [ctx]
  (for [bar (run-evidence/bars ctx (or (text (:metrics-file ctx)) ""))]
    (assoc bar :evidence (evidence-for ctx (:id bar)))))

(defn count-files [dir]
  (if (fs/directory? dir) (count (filter fs/regular-file? (fs/list-dir dir))) 0))

(defn daemon-alive? [ctx]
  (let [pid-file (fs/path (:daemon-dir ctx) "handoffd.pid")]
    (when (fs/regular-file? pid-file)
      (zero? (:exit (process/sh {:continue true} "kill" "-0" (str/trim (slurp (str pid-file)))))))))

(defn opened? [ctx] (fs/regular-file? (:tmux-socket-file ctx)))

(defn attention
  "What needs a human: escalation lines, failed mail, denials, a down judge, a dead daemon."
  [ctx]
  (let [esc (nonblank-lines (text (:escalation-file ctx)))
        failed (when (fs/directory? (:mail-dir ctx))
                 (->> (fs/glob (:mail-dir ctx) "*/failed/*.handoff") (map #(str (fs/relativize (:task-dir ctx) %)))))
        denials (count (nonblank-lines (text (fs/path (:state-dir ctx) "denials.jsonl"))))
        down (for [[role v] (verdicts ctx) :when (:down v)] role)
        dead (and (opened? ctx) (false? (daemon-alive? ctx)))
        ;; kickstart runs `open` in the background; when it dies, its output is
        ;; the only record, and nothing rendered it. A task that never reached a
        ;; tmux socket but left a log is a failed open, not a quiet one.
        open-log (when-not (opened? ctx) (text (fs/path (:state-dir ctx) "portal-open.log")))]
    (vec (concat
          (map (fn [l] {:kind "escalation" :text (str/replace l #"^- " "")}) esc)
          (map (fn [f] {:kind "failed mail" :text f}) failed)
          (when (pos? denials) [{:kind "denials" :text (str denials " tool call(s) denied by the contract hook — state/denials.jsonl")}])
          (map (fn [r] {:kind "judge down" :text (str "role " r ": the goal judge was unavailable at its last stop")}) down)
          (when dead [{:kind "daemon" :text "handoffd is not running; mail is not being delivered"}])
          (when-not (str/blank? open-log)
            [{:kind "open failed" :text (str "the swarm never started; `open` left: " (last (nonblank-lines open-log)))}])))))

(defn roles [ctx]
  (if (fs/regular-file? (:roles-tsv ctx)) (task-lib/read-roles-tsv ctx) []))

(def sgr-class
  "The SGR codes an agent TUI actually emits, and the class each becomes.
   Anything else — 256-colour, truecolour, underline, blink — is dropped rather
   than guessed at, because a wrong colour reads as meaning that is not there."
  {"1" "b" "2" "d" "3" "i"
   "30" "f0" "31" "f1" "32" "f2" "33" "f3" "34" "f4" "35" "f5" "36" "f6" "37" "f7"
   "90" "f8" "91" "f9" "92" "f10" "93" "f11" "94" "f12" "95" "f13" "96" "f14" "97" "f15"
   "40" "g0" "41" "g1" "42" "g2" "43" "g3" "44" "g4" "45" "g5" "46" "g6" "47" "g7"})

(defn ansi->hiccup
  "Turn a pane capture into hiccup, one span per run of styling. Escapes that
   are not SGR are dropped: tmux emits cursor moves and title sets that mean
   nothing inside a <pre>. The text itself is never touched, so hiccup escapes
   it exactly as it escapes any other string.

   Walks the string with a Matcher rather than a regex that also matches the
   text between escapes. A pattern like `(?:[^\u001b]|...)+` recurses once per
   character in Java's engine, so a pane holding a few thousand plain characters
   threw StackOverflowError and the whole page 500'd — which is what a task
   whose panes are spinning on an error looks like, i.e. exactly the page you
   most want to load.

   Returns a SEQ, not a vector — hiccup reads a vector as an element, so
   returning one renders the first child as a tag name."
  [s]
  (let [s (str/replace (or s "") #"\u001b\][^\u0007\u001b]*(?:\u0007|\u001b\\)" "")
        m (re-matcher #"\u001b\[([0-9;]*)m" s)
        clean #(str/replace % #"\u001b\[[0-9;?]*[@-~]" "")]
    (loop [pos 0 active #{} out []]
      (if (.find m)
        (let [text (clean (subs s pos (.start m)))
              out (if (seq text)
                    (conj out (if (seq active)
                                [:span {:class (str/join " " (sort active))} text]
                                text))
                    out)]
          (recur (.end m)
                 (reduce (fn [acc c]
                           (cond
                             (contains? #{"" "0"} c) #{}
                             (contains? sgr-class c) (conj acc (sgr-class c))
                             :else acc))
                         active
                         (str/split (.group m 1) #";"))
                 out))
        (let [text (clean (subs s pos))]
          (seq (if (seq text)
                 (conj out (if (seq active)
                             [:span {:class (str/join " " (sort active))} text]
                             text))
                 out)))))))

(defn pane-state
  "Whether the pane the rail shows is a running tmux session, the capture taken
   when the task closed, or nothing yet. A closed task still has a terminal to
   read — that is the whole point of archiving it at close."
  [ctx role]
  (cond
    (not-empty (try (handoff-lib/capture-pane ctx role) (catch Exception _ nil))) :live
    (text (fs/path (:sessions-dir ctx) role "pane.txt")) :archived
    :else :none))

(defn pane-text
  "The live pane when the task's tmux server is up, else the archived capture."
  ([ctx role] (pane-text ctx role {}))
  ([ctx role {:keys [ansi]}]
  (let [live (try (handoff-lib/capture-pane ctx role :ansi (boolean ansi)) (catch Exception _ nil))
        archived (text (fs/path (:sessions-dir ctx) role "pane.txt"))
        s (or (not-empty live) archived "")]
    (str/join "\n" (take-last pane-tail-lines (str/split-lines s))))))

(defn role-cards [ctx]
  (let [vs (verdicts ctx)]
    (for [row (roles ctx)
          :let [role (:role row) mail (task-lib/role-mail-dir ctx role)]]
      {:role role :harness (:harness row) :model (:model row) :mode (:receive-mode row)
       :verdict (get vs role)
       :sent (count-files (fs/path mail "sent"))
       :inbox (+ (count-files (fs/path mail "inbox" "new")) (count-files (fs/path mail "inbox" "in_process")))
       :last-line (last (nonblank-lines (pane-text ctx role)))})))

(defn lane [ctx]
  (or (try (board-lib/card-lane ctx (:task-id ctx)) (catch Exception _ nil))
      (if (opened? ctx) "?" "not opened")))

(defn doc-file
  "The canonical path of a file inside the task folder, or nil — never the
   clones or the worktrees, never anything a relative path can reach outside.
   Returns the path rather than a boolean so the caller serves the bytes that
   were checked, not a second resolution of the same string."
  [ctx rel]
  ;; An absolute rel needs no separate check: fs/path resolves it to itself, and
  ;; the under-the-task-folder test below then decides it like any other path.
  (when-not (str/blank? rel)
    (let [root (fs/canonicalize (:task-dir ctx))
          file (let [p (fs/path (:task-dir ctx) rel)] (when (fs/exists? p) (fs/canonicalize p)))]
      (when (and file
                 (fs/regular-file? file)
                 (fs/starts-with? file root)
                 (not (fs/starts-with? file (fs/path root "repos")))
                 (not (fs/starts-with? file (fs/path root "worktrees"))))
        file))))

;; ---------------------------------------------------------------- projects

(defn parse-form [body]
  (into {} (for [pair (str/split (or body "") #"&") :when (not (str/blank? pair))
                 :let [[k v] (str/split pair #"=" 2)]]
             [(java.net.URLDecoder/decode k "UTF-8") (java.net.URLDecoder/decode (or v "") "UTF-8")])))

(defn create-project!
  "Write a project from the form. Repos come from the checkbox list plus any
   typed paths; roles come from the cards that were ticked, in the order the
   stage prompts are listed, which is the order the swimlane columns take."
  [{:strs [name extra-repos] :as params}]
  (let [nm (str/trim (or name ""))
        picked (->> (keys params)
                    (keep #(second (re-matches #"repo:(.+)" %)))
                    sort)
        repos (vec (distinct (concat picked (nonblank-lines extra-repos))))
        roles (vec (for [stage (project-lib/stage-prompts)
                         :when (get params (str "role:" stage))]
                     {:role stage
                      :harness (or (get params (str "harness:" stage)) "claude")
                      :model (or (get params (str "model:" stage)) "anthropic")
                      :repo (or (not-empty (get params (str "repo-of:" stage))) (first repos))}))]
    (cond
      (not (project-lib/valid-project-name? nm)) {:error (str "invalid project name: " (pr-str nm))}
      (project-lib/read-project nm) {:error (str "project already exists: " nm)}
      (empty? repos) {:error "pick at least one checkout"}
      (empty? roles) {:error "pick at least one role"}
      (not (every? task-lib/git-checkout? repos)) {:error (str "not a git checkout: "
                                                              (first (remove task-lib/git-checkout? repos)))}
      (not (every? #(contains? (set repos) (:repo %)) roles))
      {:error "a role was pointed at a checkout this project does not hold"}
      (not (every? project-lib/valid-role-spec? roles)) {:error "unknown harness or vendor in a role"}
      :else (do (project-lib/write-project! {:name nm :repos repos :roles roles})
                {:ok nm}))))

(defn kickstart-project!
  "A task inside a project: the repos and the role lineup come from the project,
   so the form contributes only the brief — one pasted block that parse-brief
   splits into the goal, the not-goals and the bars."
  [project {:strs [task-id brief]}]
  (let [id (str/trim (or task-id ""))
        {:keys [goal not-goal bars seen]} (project-lib/parse-brief brief)
        ctx (when (task-lib/valid-task-id? id) (task-lib/task-ctx id))]
    (cond
      (not (task-lib/valid-task-id? id)) {:error (str "invalid task id: " (pr-str id))}
      (fs/exists? (:task-dir ctx)) {:error (str "task already exists: " id)}
      ;; Refusing here is the point. Taking an unstructured paste as goal lines
      ;; is what turned one brief into twenty-five checkboxes, and a task opens
      ;; with those locked 444 — the swarm then runs against them for an hour.
      (empty? seen) {:error (str "no section headings found — give the brief a `Goal` heading "
                                 "(and `Not-goal` / `Bars` if it has them). "
                                 "Near spellings are fine; the text above the first heading is ignored.")}
      (empty? goal) {:error (str "the Goal section is empty — found "
                                 (str/join ", " (sort (map name seen))))}
      :else
      (let [new (apply process/sh {:continue true} "bb" cli "new" id
                       (mapcat #(vector "--repo" %) (:repos project)))]
        (if-not (zero? (:exit new))
          {:error (str "new failed: " (:err new))}
          (do
            (spit (str (:goal-file ctx)) (project-lib/goal-md id goal not-goal))
            (when (seq bars)
              (spit (str (:metrics-file ctx)) (project-lib/metrics-md id bars)))
            (spit (str (project-lib/task-project-file ctx)) (str (:name project) "\n"))
            (spit (str (:roles-file ctx)) (project-lib/roles-text project))
            (fs/create-dirs (:state-dir ctx))
            (let [log (fs/file (fs/path (:state-dir ctx) "portal-open.log"))]
              (process/process ["bb" cli "open" id] {:out log :err log}))
            {:ok id}))))))

;; ---------------------------------------------------------------- html

(def css
  "Warm paper, quiet labels, soft rows. Status is colour and a dot, not a
   filled chip — the eye finds the one amber line without reading any of them."
  ":root{--paper:#faf9f7;--surface:#fff;--row:#f1efec;--row-hover:#eae7e3;
     --ink:#1f1e1d;--muted:#8b8680;--line:#e5e1dc;--accent:#d97757;
     --amber:#b4762c;--blue:#4a72ab;--green:#4f8a5f;--red:#b4453a}
   @media(prefers-color-scheme:dark){:root{--paper:#1a1917;--surface:#211f1d;--row:#262421;
     --row-hover:#2e2b28;--ink:#eceae7;--muted:#918b84;--line:#332f2b;
     --amber:#d99a4e;--blue:#7ea3d6;--green:#79b98a;--red:#d97b6e}}
   *{box-sizing:border-box}
   body{margin:0;background:var(--paper);color:var(--ink);
     font:15px/1.55 ui-sans-serif,-apple-system,'Segoe UI',Helvetica,Arial,sans-serif;
     -webkit-font-smoothing:antialiased}
   main{max-width:1040px;margin:0 auto;padding:2.6rem 2rem 8rem}
   .title{display:flex;align-items:center;gap:.6rem;font-size:1.9rem;font-weight:600;
     letter-spacing:-.02em;margin:0 0 2.4rem}
   .title .mark{color:var(--accent);font-size:1.5rem;line-height:1}
   .title a{color:inherit;text-decoration:none}
   .title .sub{color:var(--muted);font-weight:400}
   h2{font-size:.9rem;font-weight:500;color:var(--muted);margin:2rem 0 .6rem;letter-spacing:.01em}
   section{margin:0 0 1.6rem}
   .row{display:flex;align-items:center;gap:.75rem;background:var(--row);border-radius:12px;
     padding:.85rem 1.1rem;margin-bottom:.4rem;text-decoration:none;color:inherit;
     transition:background .12s ease}
   a.row:hover{background:var(--row-hover)}
   .row .grow{flex:1;min-width:0}
   .row .name{font-weight:500}
   .row .chev{color:var(--muted);flex:none}
   .trunc{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
   .status{font-size:.9rem;color:var(--muted);white-space:nowrap}
   .status::before,.lane::before{content:'●';font-size:.55em;vertical-align:.25em;margin-right:.45em}
   .met,.ticked{color:var(--green)}.unmet{color:var(--red)}.pending{color:var(--muted)}
   .lane{font-size:.9rem;color:var(--blue);white-space:nowrap}
   .lane.done{color:var(--green)}
   .muted{color:var(--muted);font-size:.85rem}
   .attention .item{border:1px solid var(--line);border-radius:12px;margin-bottom:.4rem}
   .attention summary{display:flex;align-items:center;gap:.75rem;padding:.7rem 1.1rem;
     cursor:pointer;list-style:none}
   .attention .item:hover{background:var(--row)}
   .attention .kind{color:var(--amber);font-weight:500;white-space:nowrap}
   .attention .full{padding:0 1.1rem .85rem;font-size:.92rem;white-space:pre-wrap;
     word-break:break-word}
   .scroll{overflow-x:auto}
   table{border-collapse:collapse;width:100%;font-size:.92rem}
   table.bars{table-layout:fixed}
   table.bars th:first-child{width:44%}
   table.bars th:nth-child(2){width:16%}
   td pre{max-width:100%}
   th{text-align:left;font-weight:500;color:var(--muted);font-size:.8rem;
     padding:0 .9rem .5rem;border-bottom:1px solid var(--line)}
   td{padding:.7rem .9rem;border-bottom:1px solid var(--line);vertical-align:top}
   tr:last-child td{border-bottom:0}
   code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.85em;color:var(--muted)}
   pre{background:var(--surface);border:1px solid var(--line);color:var(--ink);
     padding:.7rem .9rem;border-radius:10px;overflow:auto;font-size:12.5px;line-height:1.5;
     max-height:60vh;margin:.5rem 0 0}
   ul.plain{list-style:none;padding:0;margin:0}
   ul.plain li{padding:.35rem 0}
   .cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:.6rem}
   .card{background:var(--row);border-radius:12px;padding:.9rem 1.1rem}
   .card a{font-weight:600;color:inherit;text-decoration:none}
   .card a:hover{color:var(--accent)}
   .composer{position:fixed;left:0;right:0;bottom:0;background:var(--paper);
     border-top:1px solid var(--line);padding:1rem 2rem 1.3rem}
   .composer .inner{max-width:1040px;margin:0 auto;max-height:60vh;overflow-y:auto}
   .composer .fields{display:flex;gap:.5rem;flex-wrap:wrap;margin-bottom:.55rem}
   input[type=text],textarea{font:inherit;color:inherit;background:var(--surface);
     border:1px solid var(--line);border-radius:10px;padding:.6rem .85rem;width:100%}
   textarea{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12.5px;resize:vertical}
   input[type=text]:focus,textarea:focus{outline:2px solid var(--accent);outline-offset:-1px}
   .composer .fields>label{flex:1;min-width:210px;font-size:.8rem;color:var(--muted)}
   .composer .go{display:flex;gap:.6rem;align-items:center}
   button{font:inherit;font-weight:500;color:#fff;background:var(--accent);border:0;
     border-radius:10px;padding:.6rem 1.2rem;cursor:pointer;white-space:nowrap;flex:none}
   .err{color:var(--red);font-size:.9rem;margin:0 0 .5rem}
   .empty{color:var(--muted);font-size:.9rem;padding:.6rem 0}
   main.wide{max-width:1560px}
   .split{display:grid;grid-template-columns:minmax(0,1fr) minmax(380px,600px);gap:1.6rem;
     align-items:start}
   .rail{position:sticky;top:1.4rem}
   .tabs{display:flex;gap:.3rem;margin-bottom:.5rem;flex-wrap:wrap}
   .tab{font-size:.82rem;color:var(--muted);text-decoration:none;padding:.28rem .7rem;
     border:1px solid var(--line);border-radius:999px;white-space:nowrap}
   .tab.on{color:var(--ink);background:var(--row);border-color:var(--row)}
   .railhead{display:flex;align-items:baseline;gap:.75rem;margin-bottom:.35rem}
   .railhead .status{flex:1;min-width:0}
   .rail pre{margin:0;max-height:72vh;min-height:320px}
   .nudge{display:flex;gap:.4rem;margin-top:.5rem}
   .nudge input[type=text]{padding:.45rem .7rem;font-size:.85rem}
   .nudge button{padding:.45rem .95rem;font-size:.85rem}
   button.ghost{color:var(--muted);background:none;border:1px solid var(--line)}
   button.ghost:hover{color:var(--red);border-color:var(--red)}
   pre.term{background:#16150f;border-color:#2a2822;color:#d6d2c4;font-size:12px;
     line-height:1.45;padding:.9rem 1rem}
   pre.term .b{font-weight:700}
   pre.term .d{opacity:.62}
   pre.term .i{font-style:italic}
   pre.term .f0{color:#5c5850}pre.term .f1{color:#e06c5f}pre.term .f2{color:#88b874}
   pre.term .f3{color:#d5a24a}pre.term .f4{color:#6f9bd1}pre.term .f5{color:#b98cc9}
   pre.term .f6{color:#5fb3b3}pre.term .f7{color:#d6d2c4}
   pre.term .f8{color:#7d7871}pre.term .f9{color:#f0897c}pre.term .f10{color:#a4d18f}
   pre.term .f11{color:#e8bf6a}pre.term .f12{color:#8fb7e3}pre.term .f13{color:#cfa7dd}
   pre.term .f14{color:#7fcdcd}pre.term .f15{color:#f2efe6}
   pre.term .g0{background:#2a2822}pre.term .g1{background:#5a2b26}
   pre.term .g2{background:#31462a}pre.term .g3{background:#5a4520}
   pre.term .g4{background:#2c3f57}pre.term .g5{background:#452f4d}
   pre.term .g6{background:#26494a}pre.term .g7{background:#403c34}
   @media(max-width:1100px){.split{grid-template-columns:minmax(0,1fr)}
     .rail{position:static;margin-top:1.6rem}}
   .project{margin:0 0 2.4rem}
   .project-head{display:flex;align-items:baseline;gap:.75rem;margin:0 0 .7rem}
   .pname{font-size:1.05rem;font-weight:600;color:var(--ink);margin:0;letter-spacing:-.01em}
   .project-head .muted{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
   .btn{font-size:.85rem;font-weight:500;color:#fff;background:var(--accent);border-radius:8px;
     padding:.35rem .8rem;text-decoration:none;white-space:nowrap}
   .btn:hover{filter:brightness(1.06)}
   .swim{display:flex;gap:.5rem;min-width:min-content}
   .col{flex:1 0 190px;min-width:190px}
   .colname{font-size:.72rem;font-weight:600;letter-spacing:.08em;text-transform:uppercase;
     color:var(--muted);padding:0 .2rem .4rem}
   .slot{min-height:96px;border:1px dashed var(--line);border-radius:12px;padding:.4rem;
     background:transparent}
   .col.live .slot{border-style:solid}
   .tcard{display:block;background:var(--surface);border:1px solid var(--line);border-radius:10px;
     padding:.6rem .7rem;margin-bottom:.4rem;text-decoration:none;color:inherit}
   .tcard:hover{background:var(--row)}
   .tcard .name{font-weight:600;font-size:.92rem;margin-bottom:.15rem}
   .picklist{max-height:210px;overflow-y:auto;border:1px solid var(--line);border-radius:10px;
     background:var(--surface);padding:.35rem .5rem;margin-top:.25rem}
   .pick{display:flex;align-items:center;gap:.5rem;padding:.18rem 0;font-size:.85rem;color:var(--ink)}
   .pick input{flex:none}
   .rolepick{margin:.3rem 0 .7rem;grid-template-columns:repeat(auto-fill,minmax(170px,1fr))}
   .rolecard{cursor:pointer}
   .rolecard .card-top{margin-bottom:.4rem}
   select{font:inherit;font-size:.85rem;color:inherit;background:var(--surface);
     border:1px solid var(--line);border-radius:8px;padding:.3rem .4rem;width:100%}
   .fields.stack{display:block}
   .fields.stack>label{display:block;min-width:0;margin-bottom:.7rem}
   .composer summary{max-width:1040px;margin:0 auto;list-style:none;cursor:pointer;
     background:var(--surface);border:1px solid var(--line);border-radius:12px;
     padding:.75rem 1rem;color:var(--muted);font-size:.95rem}
   summary::-webkit-details-marker{display:none}
   .composer summary:hover{color:var(--ink)}
   .composer[open] summary{margin-bottom:.75rem;color:var(--ink)}
   .row.head{align-items:flex-start}
   a.doc{color:var(--blue);text-decoration:none;font-size:.9rem;white-space:nowrap}
   .card-top{display:flex;align-items:center;justify-content:space-between}
   .card .pane-line{white-space:pre-wrap;word-break:break-all;margin-top:.35rem}")

(def poll-script
  "Two loops. The pane is appended to every two seconds and keeps its scroll
   unless the reader had it at the bottom; the detail column is re-fetched every
   five and swapped whole. They never touch each other's DOM, which is why the
   terminal survives a page that is still live.

   The swap has to carry two things across, because the server has no idea they
   exist. An unchanged column is not swapped at all — most polls change nothing,
   and replacing the node drops the reader's text selection every five seconds.
   And an escalation the reader opened is re-opened afterwards: a fresh <details>
   has no `open` attribute, so expanding one used to snap shut on the next poll,
   which reads as the page fighting you. The key is the summary text rather than
   a position, so an escalation appended above another does not hand its open
   state to a different row."
  (str "const P=()=>document.getElementById('pane');"
       "setInterval(async()=>{const p=P();if(!p)return;"
       "const r=await fetch('/tasks/'+encodeURIComponent(p.dataset.task)+'/roles/'+encodeURIComponent(p.dataset.role)+'/pane');"
       "if(!r.ok)return;const stick=p.scrollTop+p.clientHeight>=p.scrollHeight-8;"
       "p.textContent=await r.text();if(stick)p.scrollTop=p.scrollHeight;},2000);"
       "const K=x=>x.querySelector('summary')?.textContent;"
       "setInterval(async()=>{const d=document.getElementById('detail');if(!d)return;"
       "const r=await fetch(location.href);if(!r.ok)return;"
       "const n=new DOMParser().parseFromString(await r.text(),'text/html').getElementById('detail');"
       "if(!n||n.innerHTML===d.innerHTML)return;"
       "const open=new Set([...d.querySelectorAll('details[open]')].map(K));"
       "n.querySelectorAll('details').forEach(x=>{if(open.has(K(x)))x.open=true});"
       "d.replaceWith(n);},5000);"))

(defn page
  "One shell: an accent mark, the product name linking home, and a crumb —
   no nav bar, because there are only three kinds of page. The composer is a
   plain child; being position:fixed it needs no help from here."
  [{:keys [crumb title refresh rail wide poll]} & body]
  (str "<!doctype html>"
       (h/html [:html [:head [:meta {:charset "utf-8"}]
                       [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
                       (when refresh [:meta {:http-equiv "refresh" :content (str refresh)}])
                       [:title (str title " · swarmkhazad")]
                       [:style (h/raw css)]]
                [:body
                 [:main {:class (when wide "wide")}
                  [:h1.title [:span.mark "✳"] [:a {:href "/"} "swarmkhazad"]
                   (when crumb [:span.sub crumb])]
                  (if rail
                    [:div.split [:div#detail body] rail]
                    body)]
                 (when poll [:script (h/raw poll-script)])]])))

(defn html [status & body] {:status status :headers {"Content-Type" "text/html; charset=utf-8"} :body (apply str body)})
(defn plain [status s] {:status status :headers {"Content-Type" "text/plain; charset=utf-8"} :body (str s)})
(defn not-found [] (plain 404 "Not found"))

(def default-roles
  "implement claude <repo-path> task\nreview claude <repo-path> task\nrun claude <repo-path> task")

(defn project-form
  "New project: a name, the checkouts found under the repo roots, and the role
   cards. The roles are picked once here so no task ever asks for them again."
  [error params]
  (let [available (project-lib/available-repos)
        picked (set (keep #(second (re-matches #"repo:(.+)" %)) (keys params)))
        first-run? (empty? params)
        default (set (map :role project-lib/default-roles))]
    [:details.composer {:open (boolean error)}
     [:summary "New project — the checkouts and the swarm, set once"]
     [:div.inner
      (when error [:p.err error])
      [:form {:method "post" :action "/projects"}
       [:div.fields
        [:label "project name"
         [:input {:type "text" :name "name" :placeholder "lothlorien-analytics" :required true
                  :value (get params "name" "")}]]
        [:label "checkouts under " [:code (str/join ", " (project-lib/default-repo-roots))]
         [:div.picklist
          (if (seq available)
            (for [r available]
              [:label.pick [:input {:type "checkbox" :name (str "repo:" r)
                                    :checked (contains? picked r)}]
               [:span.trunc (str/replace r (str (fs/expand-home "~")) "~")]])
            [:p.empty "no git checkouts found — type a path below"])]]
        [:label "or paths not under those roots, one per line"
         [:textarea {:name "extra-repos" :rows 2 :placeholder "/Users/you/elsewhere/thing"}
          (get params "extra-repos")]]]
       [:p.muted "roles — the swimlane's columns, in this order"]
       [:div.cards.rolepick
        (for [stage (project-lib/stage-prompts)
              :let [on? (if first-run? (contains? default stage) (boolean (get params (str "role:" stage))))]]
          [:label.card.rolecard
           [:div.card-top [:span.name stage]
            [:input {:type "checkbox" :name (str "role:" stage) :checked on?}]]
           [:select {:name (str "model:" stage)}
            (for [v (sort task-lib/known-vendors)]
              [:option {:value v :selected (= v (get params (str "model:" stage) "anthropic"))} v])]
           ;; Which checkout this role works in. One repo and there is nothing
           ;; to choose; several and the choice is the whole point — a lineup
           ;; silently pinned to repo one is how multi-repo stops being real.
           (when (> (count picked) 1)
             [:select {:name (str "repo-of:" stage)}
              (for [r (sort picked)]
                [:option {:value r :selected (= r (get params (str "repo-of:" stage)))}
                 (task-lib/repo-name r)])])])]
       [:div.go
        [:button {:type "submit"} "Create project"]
        [:span.muted (count available) " checkouts found · harnesses: " (str/join ", " (sort task-lib/known-agents))]]]]]))

(defn project-section
  "One project: its role lanes as columns, its tasks as cards in the lane each
   one is actually in. The lane comes from the task's own board, so a card moves
   because a git handoff moved it, never because the portal said so."
  [project]
  (let [columns (conj (mapv :role (:roles project)) "done")
        cards (for [id (project-lib/tasks-for (:name project))
                    :let [ctx (task-lib/task-ctx id)]]
                {:id id :lane (lane ctx) :attention (count (attention ctx))})
        placed (set (map :lane cards))
        stray (remove #(contains? (set columns) (:lane %)) cards)]
    [:section.project
     [:div.project-head
      [:h2.pname (:name project)]
      [:span.muted (str/join ", " (map #(task-lib/repo-name %) (:repos project)))]
      (let [idle (project-lib/unused-repos project)]
        (when (seq idle)
          [:span.status.unmet "no role opens " (str/join ", " (map task-lib/repo-name idle))]))
      [:a.btn {:href (str "/projects/" (:name project) "/new")} "New task"]]
     [:div.scroll
      [:div.swim
       (for [col columns]
         [:div.col {:class (when (contains? placed col) "live")}
          [:div.colname col]
          [:div.slot
           (for [c cards :when (= col (:lane c))]
             [:a.tcard {:href (str "/tasks/" (:id c))}
              [:div.name.trunc (:id c)]
              (if (pos? (:attention c))
                [:span.status.unmet (:attention c) " needs you"]
                [:span.status.met "clear"])])]])]]
     (when (seq stray)
       [:p.muted "not in a lane yet: "
        (interpose ", " (for [c stray] [:a {:href (str "/tasks/" (:id c))} (:id c) " (" (:lane c) ")"]))])]))

(defn index-page [error params]
  (let [projects (project-lib/list-projects)
        owned (set (mapcat #(project-lib/tasks-for (:name %)) projects))
        loose (remove owned (task-lib/list-task-ids))]
    (page {:title "projects"}
          (if (seq projects)
            (for [p projects] (project-section p))
            [:section [:h2 "Projects"]
             [:p.empty "no projects yet — a project holds the checkouts and the swarm, so a task only has to say what it wants done. Open the composer below."]])
          (when (seq loose)
            [:section [:h2 "Tasks outside a project"]
             (for [id loose :let [ctx (task-lib/task-ctx id) att (attention ctx)]]
               [:a.row {:href (str "/tasks/" id)}
                [:div.grow [:div.name.trunc id]
                 [:div.muted.trunc (str/join ", " (map :role (roles ctx)))]]
                (if (seq att)
                  [:span.status.unmet (count att) " needs you"]
                  [:span.status.met "clear"])
                [:span.lane {:class (when (= "done" (lane ctx)) "done")} (lane ctx)]
                [:span.chev "›"]])])
          ;; a plain child: .composer is position:fixed, so it needs no help
          ;; from the shell to sit at the bottom of the viewport.
          (project-form error (or params {})))))

(def brief-placeholder
  "The shape a brief already has when it is written elsewhere, so the box asks
   for nothing to be reformatted — headings, bullets, and the bars as a table."
  (str "## Goal\n"
       "- implement — superset mcp run starts on the built image, endpoints non-empty\n"
       "- run — the staging pod goes 1/1 Ready and stays Ready\n\n"
       "## Not-goal\n"
       "- No ingress, no public origin, no DNS.\n"
       "- Don't upgrade Superset to fix an import. Pin the dependency.\n\n"
       "## Quantitative bars\n"
       "| bar | measure |\n"
       "|---|---|\n"
       "| the import that fails now, resolves | `docker run --rm <img> -c '...'` |\n"
       "| the failing string is gone | `kubectl -n superset logs deploy/superset-mcp` |"))

(defn new-task-page
  "The only form a task needs: what to do, what not to do, and how it is
   measured. Repos and roles are the project's, shown but not asked for."
  [project error params]
  (page {:title (str "new task · " (:name project)) :crumb (:name project)}
        [:section
         [:h2 "New task in " (:name project)]
         (when error [:p.err error])
         [:div.row.head
          [:div.grow
           [:div.name "the swarm"]
           [:div.muted (str/join " · " (for [{:keys [role model repo]} (:roles project)]
                                         (str role " (" model ") in " (task-lib/repo-name (or repo "none")))))]]]
         [:div.row.head
          [:div.grow
           [:div.name "checkouts"]
           [:div.muted (str/join " · " (:repos project))
            (let [idle (project-lib/unused-repos project)]
              (when (seq idle)
                [:span.status.pending "no role works in " (str/join ", " (map task-lib/repo-name idle))]))]]]
         [:form {:method "post" :action (str "/projects/" (:name project) "/review")}
          [:div.fields.stack
           [:label "task id"
            [:input {:type "text" :name "task-id" :required true
                     :placeholder (str (java.time.LocalDate/now) "-something")
                     :value (get params "task-id" "")}]]
           ;; One box, because a brief is written somewhere else and arrives as
           ;; one block. Three boxes asked the writer to take it apart by hand,
           ;; and the way that fails is silent: paste everything into the first
           ;; and every line of it becomes a goal.
           [:label (str "the brief — paste it whole. Sections are found by their heading "
                        "(Goal, Not-goal, Bars — near spellings are fine), and each line "
                        "under one is an item. A goal line starting with a role is graded "
                        "against that role.")
            [:textarea {:name "brief" :rows 16 :placeholder brief-placeholder}
             (get params "brief")]]]
          [:div.go [:button {:type "submit"} "Sort it"]
           [:span.muted "one model call splits it into goals, not-goals and bars; "
            "you review before anything opens"]]]]))

(defn review-page
  "The step between pasting a brief and opening a swarm. The model sorted the
   paste; this is where a person disagrees with it.

   What is shown is the markdown itself, in one editable box, and it is parsed
   again on submit by exactly the parser that read the paste. There is no path
   from the model's answer into goal.md that does not go through the box — so
   an edit here is the last word, and a model that added a goal nobody asked
   for is one keystroke from gone."
  [project {:keys [markdown parsed note cost model fell-back]} params]
  (let [{:keys [goal not-goal bars]} parsed]
    (page {:title (str "review · " (:name project)) :crumb (:name project)}
          [:section
           [:h2 "Review before the swarm opens"]
           [:p.muted
            (str (count goal) " goal, " (count not-goal) " not-goal, " (count bars) " bar")
            (when-not (= 1 (count goal)) "s")
            (cond
              note (list " · " [:span.status.pending note])
              cost (list " · sorted by " (or model "the model")
                         (format " · $%.4f" (double cost))))]
           (when (empty? goal)
             [:p.err "No goal line survived. Edit the box below — a task cannot open without one."])
           [:form {:method "post" :action (str "/projects/" (:name project) "/tasks")}
            [:div.fields.stack
             [:label "task id"
              [:input {:type "text" :name "task-id" :required true
                       :placeholder (str (java.time.LocalDate/now) "-something")
                       :value (get params "task-id" "")}]]
             [:label "the brief, sorted — edit anything, this is what opens"
              [:textarea {:name "brief" :rows 20} markdown]]]
            [:div.go
             [:button {:type "submit"} "Open the swarm"]
             [:span.muted "goal.md becomes read-only the moment it opens"]]]
           ;; The paste is kept so Back is not the only way to start over from
           ;; the original, which the model has by then already reworded.
           [:details.item
            [:summary [:span.kind "the paste"] [:span.muted "what was sorted"]]
            [:div.full [:pre (get params "paste" "")]]]]
          (when fell-back
            [:p.muted "The model was not reached, so this is the parser's own split. "
             "It only finds sections that already carry a heading."]))))

(defn pane-rail
  "The task's terminal, beside the task. `watching` is a role name; the tabs are
   plain links carrying it in the query string, so which pane you are on is in
   the URL and survives a reload rather than living in a variable."
  [ctx watching]
  (let [id (:task-id ctx)
        names (map :role (roles ctx))
        watching (or (some #{watching} names) (first names))
        state (when watching (pane-state ctx watching))]
    [:aside.rail
     [:div.tabs
      (for [r names]
        [:a.tab {:href (str "/tasks/" id "?pane=" r) :class (when (= r watching) "on")} r])]
     (if-not watching
       [:p.empty "no roles declared"]
       (list
        [:div.railhead
         [:span.status {:class (case state :live "met" :archived "pending" "unmet")}
          (case state :live "live session" :archived "session closed — archived pane" "no pane yet")]
         [:a.doc {:href (str "/tasks/" id "/roles/" watching)} "full screen ›"]]
        [:pre#pane.term {:data-task id :data-role watching}
         (ansi->hiccup (pane-text ctx watching {:ansi true}))]
        ;; The pane is the only way to reach an agent mid-turn: it owns the
        ;; terminal, and the inbox it reads between turns is no use to a role
        ;; that is already running. Until now that meant a tmux attach in
        ;; another window, which is why the attach line below has always been
        ;; here. It stays — this is the same thing without leaving the page.
        (when (= state :live)
          [:form.nudge {:method "post" :action (str "/tasks/" id "/roles/" watching "/keys")}
           [:input {:type "text" :name "text" :autocomplete "off"
                    :placeholder (str "say something to " watching "…")}]
           [:button {:name "do" :value "send"} "Send"]
           [:button.ghost {:name "do" :value "stop"
                           :title "Escape — interrupt the turn it is in the middle of"}
            "Stop"]])
        [:p.muted "attach: " [:code (str "tmux -S " (:tmux-socket ctx) " attach -t " (task-lib/session-name watching))]]))]))

(defn task-page [ctx watching]
  (let [id (:task-id ctx) vs (verdicts ctx) l (lane ctx) att (attention ctx)]
    (page {:title id :crumb id :wide true
           ;; No meta refresh here. A full reload every five seconds throws away
           ;; the terminal's scroll position and any text being selected in it,
           ;; which is exactly what this page now exists to show. The script at
           ;; the foot polls the pane and swaps the detail column instead.
           :rail (pane-rail ctx watching)
           :poll id}
          [:section
           [:div.row.head
            [:div.grow [:div.name "Board"]
             [:div.muted "goal.md and metrics.md are read-only here — the checkboxes show the judge's latest verdicts, never an edit"]]
            [:a.doc {:href (str "/tasks/" id "/doc?path=goal.md")} "goal.md"]
            [:a.doc {:href (str "/tasks/" id "/doc?path=metrics.md")} "metrics.md"]
            [:span.lane {:class (when (= "done" l) "done")} l]]]
          [:section.attention
           [:h2 "Attention " [:span.status {:class (if (seq att) "unmet" "met")} (count att)]]
           (if (seq att)
             (for [a att]
               [:details.item
                [:summary [:span.kind (:kind a)] [:span.grow.trunc (:text a)]]
                [:div.full (:text a)]])
             [:p.empty "nothing needs a human"])]
          [:section [:h2 "Goal"]
           [:ul.plain
            (for [g (goal-lines (text (:goal-file ctx))) :let [{:keys [status roles]} (goal-status g vs)]]
              [:li [:input {:type "checkbox" :disabled true :checked (contains? #{:met :ticked} status)}] " " (:text g) " "
               [:span.status {:class (name status)} (name status) (when (seq roles) (str ": " (str/join ", " roles)))]])]]
          [:section [:h2 "Metrics bars"]
           [:div.scroll
            [:table.bars [:tr [:th "bar"] [:th "threshold"] [:th "latest evidence"]]
            (for [b (bars-with-evidence ctx)]
              [:tr [:td (:name b)
                    [:div.muted (if (:command b)
                                  [:code (:command b)]
                                  ;; prose, not a command: say so rather than
                                  ;; showing an empty cell that reads as a bug.
                                  (list "by hand — " (:measure b)))]]
               [:td (:threshold b)]
               [:td (if-let [e (:evidence b)]
                      [:div (if (:exit e)
                              [:span.status {:class (if (= "0" (:exit e)) "met" "unmet")} "exit " (:exit e)]
                              [:span.status.pending "no exit recorded"])
                       " " [:span.muted (:at e)]
                       (when (seq (:tail e)) [:pre (:tail e)])]
                      [:span.status.pending "no evidence yet"])]])]]]
          (let [cached (summary/read-summary ctx)]
            [:section
             [:h2 "Ready to merge?"
              (when-let [h (:headers cached)]
                [:span.muted " · " (get h "model") " · $" (get h "cost") " · " (get h "at")])]
             [:form {:method "post" :action (str "/tasks/" id "/summary")}
              [:button {:type "submit"} (if cached "Ask again" "Ask")]
              [:span.muted " reads goal.md, the verdicts, decision/gotcha/escalation, "
               "every bar's evidence and each role's diff — one call, and it merges nothing"]]
             (if cached
               [:pre (:body cached)]
               [:p.empty "not asked yet"])])
          (let [t (telemetry/task-totals id)]
            [:section [:h2 "Telemetry"
                       (when t [:span.muted " · " (format "$%.4f" (:total-cost t)) " this task"])]
             (if t
               [:div.scroll
                [:table [:tr [:th "role"] [:th "cost"] [:th "input"] [:th "output"] [:th "cache"] [:th "sessions"] [:th "active"]]
                (for [role (sort (keys (:cost t))) :let [tok (get (:tokens t) role {})]]
                  [:tr [:td role] [:td (format "$%.4f" (get (:cost t) role 0.0))]
                   [:td (long (get tok "input" 0.0))] [:td (long (get tok "output" 0.0))]
                   [:td (long (+ (get tok "cacheRead" 0.0) (get tok "cacheCreation" 0.0)))]
                   [:td (long (get (:sessions t) role 0.0))]
                   [:td (str (long (get (:active-seconds t) role 0.0)) "s")]])]]
               [:p.empty "no telemetry for this task at " (telemetry/base-url)
                " — start VictoriaMetrics (`brew services start victoriametrics`) before `open`, or the roles' exports are dropped."])
             [:p.muted "dashboard: "
              ;; delta counters: an instant read returns one export interval, so the
              ;; link has to sum the window the same way telemetry.bb does.
              [:a {:href (str (telemetry/base-url) "/vmui/#/?g0.expr="
                              (java.net.URLEncoder/encode (str "sum by (role) (sum_over_time(claude_code.cost.usage{task_id=\"" id "\"}[7d]))") "UTF-8"))} "vmui"]
              " · repo dashboard: dashboards/swarmkhazad.json"]])
          [:section [:h2 "Roles"]
           [:div.cards
            (for [c (role-cards ctx)]
              [:div.card
               [:div.card-top [:a {:href (str "/tasks/" id "/roles/" (:role c))} (:role c)] [:span.chev "›"]]
               [:div.muted (:harness c) " · " (:model c) " · " (:mode c)]
               [:div "judge: " (if-let [v (:verdict c)]
                                 [:span.status {:class (if (:met v) "met" "unmet")} (if (:met v) "met" (str "unmet: " (str/join "; " (:unmet v))))]
                                 [:span.status.pending "no verdict"])]
               [:div.muted "sent " (:sent c) " · inbox " (:inbox c)]
               [:div.muted.pane-line (:last-line c)]])]]
          (for [[title k] [["decision.md" :decision-file] ["gotcha.md" :gotcha-file] ["escalation.md" :escalation-file]]]
            [:section [:h2 title]
             (let [ls (nonblank-lines (text (get ctx k)))]
               (if (seq ls) [:ul.plain (for [l ls] [:li (str/replace l #"^- " "")])] [:p.empty "empty"]))])
          [:section [:h2 "Drafts"]
           (let [drafts (sort (map fs/file-name (fs/glob (:task-dir ctx) "draft-*.md")))]
             (if (seq drafts)
               (for [d drafts] [:a.row {:href (str "/tasks/" id "/doc?path=" d)} [:span.grow.name d] [:span.chev "›"]])
               [:p.empty "none yet"]))])))

(defn role-page [ctx role]
  (let [id (:task-id ctx)]
    (page {:title (str id " · " role) :crumb [:span [:a {:href (str "/tasks/" id)} id] " · " role]}
          [:section
           [:h2 "Pane " [:code (task-lib/session-name role)]]
           [:p.muted "attach: " [:code (str "tmux -S " (:tmux-socket ctx) " attach -t " (task-lib/session-name role))]]
           [:pre#pane {:data-task id :data-role role :style "max-height:80vh"} (pane-text ctx role)]
           [:script (h/raw "setInterval(async()=>{const p=document.getElementById('pane');const r=await fetch('/tasks/'+encodeURIComponent(p.dataset.task)+'/roles/'+encodeURIComponent(p.dataset.role)+'/pane');const stick=p.scrollTop+p.clientHeight>=p.scrollHeight-8;p.textContent=await r.text();if(stick)p.scrollTop=p.scrollHeight;},2000);")]])))

;; ---------------------------------------------------------------- routing

(defn query-value [query key]
  (get (parse-form query) key))

(defn ctx-for [id]
  (when (and (task-lib/valid-task-id? id) (fs/directory? (:task-dir (task-lib/task-ctx id))))
    (task-lib/task-ctx id)))

(defn handle-request [{:keys [request-method uri query-string body]}]
  (let [method (or request-method :get)]
    (or
     (when (and (= :get method) (= "/" uri)) (html 200 (index-page nil nil)))
     (when (and (= :post method) (= "/projects" uri))
       (let [params (parse-form (if (string? body) body (some-> body slurp)))
             result (create-project! params)]
         (if (:ok result)
           {:status 303 :headers {"Location" "/"} :body ""}
           (html 400 (index-page (:error result) params)))))
     (when-let [[_ name] (and (= :get method) (re-matches #"/projects/([^/]+)/new" uri))]
       (if-let [project (project-lib/read-project name)]
         (html 200 (new-task-page project nil {}))
         (not-found)))
     ;; The brief goes to the model first and to a person second; only the
     ;; person's copy reaches /tasks. Two routes rather than a flag, because
     ;; "open a swarm" and "sort some text" fail in completely different ways.
     (when-let [[_ name] (and (= :post method) (re-matches #"/projects/([^/]+)/review" uri))]
       (if-let [project (project-lib/read-project name)]
         (let [params (parse-form (if (string? body) body (some-> body slurp)))
               paste (get params "brief" "")
               sorted (project-lib/normalize-brief ask/ask paste)]
           (html 200 (review-page project sorted (assoc params "paste" paste))))
         (not-found)))
     (when-let [[_ name] (and (= :post method) (re-matches #"/projects/([^/]+)/tasks" uri))]
       (if-let [project (project-lib/read-project name)]
         (let [params (parse-form (if (string? body) body (some-> body slurp)))
               result (kickstart-project! project params)]
           (if-let [id (:ok result)]
             {:status 303 :headers {"Location" (str "/tasks/" id)} :body ""}
             (html 400 (new-task-page project (:error result) params))))
         (not-found)))
     (when-let [[_ id] (and (= :get method) (re-matches #"/tasks/([^/]+)" uri))]
       (if-let [ctx (ctx-for id)]
         (html 200 (task-page ctx (query-value query-string "pane")))
         (not-found)))
     (when-let [[_ id] (and (= :get method) (re-matches #"/tasks/([^/]+)/doc" uri))]
       (let [ctx (ctx-for id) rel (query-value query-string "path")]
         (if-let [f (and ctx (doc-file ctx rel))] (plain 200 (slurp (str f))) (not-found))))
     (when-let [[_ id role] (and (= :get method) (re-matches #"/tasks/([^/]+)/roles/([^/]+)" uri))]
       (let [ctx (ctx-for id)]
         (if (and ctx (some #{role} (map :role (roles ctx)))) (html 200 (role-page ctx role)) (not-found))))
     (when-let [[_ id role] (and (= :get method) (re-matches #"/tasks/([^/]+)/roles/([^/]+)/pane" uri))]
       (let [ctx (ctx-for id)]
         (if (and ctx (some #{role} (map :role (roles ctx)))) (plain 200 (pane-text ctx role)) (not-found))))
     ;; Typing into a pane is what an attached operator already does, and the
     ;; attach command is printed beside the box — this route is that reach,
     ;; not a new one. The text is passed as one argv element to `tmux
     ;; send-keys -l`, never through a shell, so it is typed and never run; the
     ;; role must be one this task declared, like every other role route here.
     (when-let [[_ id] (and (= :post method) (re-matches #"/tasks/([^/]+)/summary" uri))]
       (if-let [ctx (ctx-for id)]
         (let [r (summary/summarize! ctx)]
           (if (:error r)
             (html 200 (page {:title (str id " · summary") :crumb id}
                             [:section [:h2 "Ready to merge?"]
                              [:p.err (:error r)]
                              [:p [:a.doc {:href (str "/tasks/" id)} "← back to " id]]]))
             {:status 303 :headers {"Location" (str "/tasks/" id)} :body ""}))
         (not-found)))
     (when-let [[_ id role] (and (= :post method) (re-matches #"/tasks/([^/]+)/roles/([^/]+)/keys" uri))]
       (let [ctx (ctx-for id)]
         (if (and ctx (some #{role} (map :role (roles ctx))))
           (let [params (parse-form (if (string? body) body (some-> body slurp)))]
             (if (= "stop" (get params "do"))
               (handoff-lib/press-key! ctx role "Escape")
               (handoff-lib/type-into-pane! ctx role (get params "text")))
             {:status 303 :headers {"Location" (str "/tasks/" id "?pane=" role)} :body ""})
           (not-found))))
     (not-found))))

(defn -main [& args]
  (let [port (parse-long (or (second (drop-while #(not= "--port" %) args))
                             (not-empty (System/getenv "SWARMKHAZAD_PORTAL_PORT"))
                             (str default-port)))]
    (http/run-server (fn [req] (try (handle-request req)
                                    (catch Exception e (plain 500 (str "portal error: " (ex-message e))))))
                     {:ip "127.0.0.1" :port port})
    (println (str "swarmkhazad portal: http://127.0.0.1:" port "/  tasks under " (task-lib/tasks-dir)))
    @(promise)))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
