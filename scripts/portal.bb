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
;;   /tasks/<id>/doc?path=<rel> any text file inside the task folder (doc-file)
;;   POST /tasks                kickstart: new + roles + open, then redirect
;;
;; Nothing here writes into a task folder except kickstart, which only calls the
;; CLI. The portal never ticks a goal box: the checkboxes show what the judge
;; said, and goal.md itself stays 444.

(ns portal
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [org.httpkit.server :as http]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "task_lib.bb")))
(load-file (str (fs/path script-dir "handoff_lib.bb")))
(load-file (str (fs/path script-dir "board_lib.bb")))
(load-file (str (fs/path script-dir "run_evidence.bb")))
(load-file (str (fs/path script-dir "telemetry.bb")))

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

(defn pane-text
  "The live pane when the task's tmux server is up, else the archived capture."
  [ctx role]
  (let [live (try (handoff-lib/capture-pane ctx role) (catch Exception _ nil))
        archived (text (fs/path (:sessions-dir ctx) role "pane.txt"))
        s (or (not-empty live) archived "")]
    (str/join "\n" (take-last pane-tail-lines (str/split-lines s)))))

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

;; ---------------------------------------------------------------- kickstart

(defn parse-form [body]
  (into {} (for [pair (str/split (or body "") #"&") :when (not (str/blank? pair))
                 :let [[k v] (str/split pair #"=" 2)]]
             [(java.net.URLDecoder/decode k "UTF-8") (java.net.URLDecoder/decode (or v "") "UTF-8")])))

(defn kickstart!
  "new <id> --repo … ; write roles ; open in the background. Returns {:ok id} or {:error msg}."
  [{:strs [task-id repos roles]}]
  (let [id (str/trim (or task-id ""))
        repo-list (nonblank-lines repos)
        roles-text (str (str/trim (or roles "")) "\n")]
    (cond
      (not (task-lib/valid-task-id? id)) {:error (str "invalid task id: " (pr-str id))}
      (fs/exists? (:task-dir (task-lib/task-ctx id))) {:error (str "task already exists: " id)}
      (str/blank? (str/trim roles-text)) {:error "declare at least one role"}
      :else
      (let [ctx (task-lib/task-ctx id)
            new (apply process/sh {:continue true} "bb" cli "new" id (mapcat #(vector "--repo" %) repo-list))]
        (if-not (zero? (:exit new))
          {:error (str "new failed: " (:err new))}
          (do
            (spit (str (:roles-file ctx)) roles-text)
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

(defn page
  "One shell: an accent mark, the product name linking home, and a crumb —
   no nav bar, because there are only three kinds of page. The composer is a
   plain child; being position:fixed it needs no help from here."
  [{:keys [crumb title refresh]} & body]
  (str "<!doctype html>"
       (h/html [:html [:head [:meta {:charset "utf-8"}]
                       [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
                       (when refresh [:meta {:http-equiv "refresh" :content (str refresh)}])
                       [:title (str title " · swarmkhazad")]
                       [:style (h/raw css)]]
                [:body
                 [:main
                  [:h1.title [:span.mark "✳"] [:a {:href "/"} "swarmkhazad"]
                   (when crumb [:span.sub crumb])]
                  body]]])))

(defn html [status & body] {:status status :headers {"Content-Type" "text/html; charset=utf-8"} :body (apply str body)})
(defn plain [status s] {:status status :headers {"Content-Type" "text/plain; charset=utf-8"} :body (str s)})
(defn not-found [] (plain 404 "Not found"))

(def default-roles
  "implement claude <repo-path> task\nreview claude <repo-path> task\nrun claude <repo-path> task")

(defn kickstart-form
  "A composer pinned to the bottom, closed until you reach for it — <details>
   does the toggle, so the page still carries no script. An error forces it open
   and keeps what was typed, or the reason costs the reader their input."
  [error params]
  (let [stages (->> (fs/glob (fs/path (fs/parent script-dir) "prompts") "*.prompt") (map #(str/replace (fs/file-name %) #"\.prompt$" "")) sort)]
    [:details.composer {:open (boolean error)}
     [:summary "Start a task — a task id, the checkouts, and who is in the swarm"]
     [:div.inner
      (when error [:p.err error])
      [:form {:method "post" :action "/tasks"}
       [:div.fields
        [:label "task id"
         [:input {:type "text" :name "task-id" :placeholder "2026-09-06-something" :required true
                  :value (get params "task-id" "")}]]
        [:label "repos — one local checkout path per line"
         [:textarea {:name "repos" :rows 2 :placeholder "/Users/you/repos/thing"} (get params "repos")]]
        [:label (str "roles — " (str/trim task-lib/roles-grammar-comment))
         [:textarea {:name "roles" :rows 3} (or (not-empty (get params "roles")) default-roles)]]]
       [:div.go
        [:button {:type "submit"} "Open the swarm"]
        [:span.muted "stage prompts: " (str/join ", " stages) " · harnesses: " (str/join ", " (sort task-lib/known-agents))
         " · vendors: " (str/join ", " (sort task-lib/known-vendors))]]]]]))

(defn index-page [error params]
  (let [ids (task-lib/list-task-ids)]
    (page {:title "tasks"}
          [:section
           [:h2 "Tasks"]
           (if (seq ids)
             (for [id ids :let [ctx (task-lib/task-ctx id) att (attention ctx) l (lane ctx)]]
               [:a.row {:href (str "/tasks/" id)}
                [:div.grow
                 [:div.name.trunc id]
                 [:div.muted.trunc (str/join ", " (map :role (roles ctx)))]]
                (if (seq att)
                  [:span.status.unmet (count att) " needs you"]
                  [:span.status.met "0 waiting"])
                [:span.lane {:class (when (= "done" l) "done")} l]
                [:span.chev "›"]])
             [:p.empty "no tasks under " (str (task-lib/tasks-dir)) " — open the composer below to start one"])]
          (kickstart-form error params))))

(defn task-page [ctx]
  (let [id (:task-id ctx) vs (verdicts ctx) l (lane ctx) att (attention ctx)]
    (page {:title id :crumb id :refresh 5}
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
              [:tr [:td (:name b) [:div.muted [:code (:command b)]]] [:td (:threshold b)]
               [:td (if-let [e (:evidence b)]
                      [:div (if (:exit e)
                              [:span.status {:class (if (= "0" (:exit e)) "met" "unmet")} "exit " (:exit e)]
                              [:span.status.pending "no exit recorded"])
                       " " [:span.muted (:at e)]
                       (when (seq (:tail e)) [:pre (:tail e)])]
                      [:span.status.pending "no evidence yet"])]])]]]
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
     (when (and (= :post method) (= "/tasks" uri))
       (let [params (parse-form (if (string? body) body (some-> body slurp)))
             result (kickstart! params)]
         (if-let [id (:ok result)]
           {:status 303 :headers {"Location" (str "/tasks/" id)} :body ""}
           (html 400 (index-page (:error result) params)))))
     (when-let [[_ id] (and (= :get method) (re-matches #"/tasks/([^/]+)" uri))]
       (if-let [ctx (ctx-for id)] (html 200 (task-page ctx)) (not-found)))
     (when-let [[_ id] (and (= :get method) (re-matches #"/tasks/([^/]+)/doc" uri))]
       (let [ctx (ctx-for id) rel (query-value query-string "path")]
         (if-let [f (and ctx (doc-file ctx rel))] (plain 200 (slurp (str f))) (not-found))))
     (when-let [[_ id role] (and (= :get method) (re-matches #"/tasks/([^/]+)/roles/([^/]+)" uri))]
       (let [ctx (ctx-for id)]
         (if (and ctx (some #{role} (map :role (roles ctx)))) (html 200 (role-page ctx role)) (not-found))))
     (when-let [[_ id role] (and (= :get method) (re-matches #"/tasks/([^/]+)/roles/([^/]+)/pane" uri))]
       (let [ctx (ctx-for id)]
         (if (and ctx (some #{role} (map :role (roles ctx)))) (plain 200 (pane-text ctx role)) (not-found))))
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
