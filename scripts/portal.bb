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
;;   /tasks/<id>/doc?path=<rel> any text file inside the task folder (allowed-doc?)
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
         :tail (str/join "\n" (take-last 6 (str/split-lines (or out ""))))}))))

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

(defn allowed-doc?
  "A text file inside the task folder — never the clones or the worktrees, never
   anything a relative path can reach outside."
  [ctx rel]
  ;; An absolute rel needs no separate check: fs/path resolves it to itself, and
  ;; the under-the-task-folder test below then decides it like any other path.
  (when-not (str/blank? rel)
    (let [root (fs/canonicalize (:task-dir ctx))
          file (let [p (fs/path (:task-dir ctx) rel)] (when (fs/exists? p) (fs/canonicalize p)))]
      (boolean
       (and file
            (fs/regular-file? file)
            (fs/starts-with? file root)
            (not (fs/starts-with? file (fs/path root "repos")))
            (not (fs/starts-with? file (fs/path root "worktrees"))))))))

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
  "body{font:14px/1.45 -apple-system,Helvetica,Arial,sans-serif;margin:0;background:#f6f7f9;color:#1b1f23}
   header{background:#1b1f23;color:#fff;padding:.6em 1.2em}header a{color:#fff;text-decoration:none;font-weight:600}
   main{padding:1em 1.2em;max-width:1200px}section{background:#fff;border:1px solid #e1e4e8;border-radius:6px;padding:.8em 1em;margin:.8em 0}
   h2{font-size:1.05em;margin:0 0 .5em}pre{background:#0d1117;color:#c9d1d9;padding:.6em;border-radius:4px;overflow:auto;font-size:12px;max-height:60vh}
   table{border-collapse:collapse;width:100%}td,th{border-bottom:1px solid #eee;padding:.3em .5em;text-align:left;vertical-align:top}
   .lane{display:inline-block;padding:0 .5em;border-radius:1em;background:#dbeafe;font-size:12px}.done{background:#dcfce7}
   .status{display:inline-block;padding:0 .5em;border-radius:1em;font-size:12px}.met{background:#dcfce7}.unmet{background:#fee2e2}.pending{background:#f3f4f6}.ticked{background:#bbf7d0}
   .attention li{color:#991b1b}.attention .kind{font-weight:600}input[type=text],textarea{width:100%;font:inherit;box-sizing:border-box}textarea{font-family:ui-monospace,Menlo,monospace;font-size:12px}
   .cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:.6em}.card{border:1px solid #e1e4e8;border-radius:6px;padding:.6em}.card a{font-weight:600}.muted{color:#6a737d;font-size:12px}")

(defn page [title & body]
  (str "<!doctype html>"
       (h/html [:html [:head [:meta {:charset "utf-8"}] [:title (str title " · swarmkhazad")]
                       [:style (h/raw css)]]
                [:body [:header [:a {:href "/"} "swarmkhazad"] " · " title]
                 [:main body]]])))

(defn html [status & body] {:status status :headers {"Content-Type" "text/html; charset=utf-8"} :body (apply str body)})
(defn plain [status s] {:status status :headers {"Content-Type" "text/plain; charset=utf-8"} :body (str s)})
(defn not-found [] (plain 404 "Not found"))

(defn kickstart-form [error]
  (let [stages (->> (fs/glob (fs/path (fs/parent script-dir) "prompts") "*.prompt") (map #(str/replace (fs/file-name %) #"\.prompt$" "")) sort)]
    [:section [:h2 "Kickstart a task"]
     (when error [:p.attention {:style "color:#991b1b"} error])
     [:form {:method "post" :action "/tasks"}
      [:p [:label "task id " [:input {:type "text" :name "task-id" :placeholder "2026-09-06-something" :required true}]]]
      [:p [:label "repos, one local checkout path per line" [:textarea {:name "repos" :rows 2 :placeholder "/Users/you/repos/thing"}]]]
      [:p [:label (str "roles — " (str/trim task-lib/roles-grammar-comment))
           [:textarea {:name "roles" :rows 4} "implement claude <repo-path> task\nreview claude <repo-path> task\nrun claude <repo-path> task"]]]
      [:p.muted "stage prompts available: " (str/join ", " stages) " · harnesses: " (str/join ", " (sort task-lib/known-agents))
       " · vendors: " (str/join ", " (sort task-lib/known-vendors))]
      [:p [:button {:type "submit"} "open the swarm"]]]]))

(defn index-page [error]
  (let [ids (task-lib/list-task-ids)]
    (page "tasks"
          [:section [:h2 "Tasks"]
           (if (seq ids)
             [:table [:tr [:th "task"] [:th "lane"] [:th "roles"] [:th "attention"]]
              (for [id ids :let [ctx (task-lib/task-ctx id) att (attention ctx)]]
                [:tr [:td [:a {:href (str "/tasks/" id)} id]]
                 [:td [:span.lane {:class (when (= "done" (lane ctx)) "done")} (lane ctx)]]
                 [:td (str/join ", " (map :role (roles ctx)))]
                 [:td (if (seq att) [:span.status.unmet (str (count att))] [:span.status.met "0"])]])]
             [:p.muted "no tasks under " (str (task-lib/tasks-dir))])]
          (kickstart-form error))))

(defn task-page [ctx]
  (let [id (:task-id ctx) vs (verdicts ctx)]
    (page id
          [:meta {:http-equiv "refresh" :content "5"}]
          (let [att (attention ctx)]
            [:section.attention [:h2 "Attention " [:span.status {:class (if (seq att) "unmet" "met")} (count att)]]
             (if (seq att) [:ul (for [a att] [:li [:span.kind (:kind a)] " — " (:text a)])] [:p.muted "nothing needs a human"])])
          [:section [:h2 "Board: " [:span.lane {:class (when (= "done" (lane ctx)) "done")} (lane ctx)]]
           [:p.muted "goal.md and metrics.md are read-only; checkboxes show the judge's latest verdicts, not edits. "
            [:a {:href (str "/tasks/" id "/doc?path=goal.md")} "goal.md"] " · " [:a {:href (str "/tasks/" id "/doc?path=metrics.md")} "metrics.md"]]]
          [:section [:h2 "Goal"]
           [:ul {:style "list-style:none;padding:0"}
            (for [g (goal-lines (text (:goal-file ctx))) :let [{:keys [status roles]} (goal-status g vs)]]
              [:li [:input {:type "checkbox" :disabled true :checked (contains? #{:met :ticked} status)}] " " (:text g) " "
               [:span.status {:class (name status)} (name status) (when (seq roles) (str ": " (str/join ", " roles)))]])]]
          [:section [:h2 "Metrics bars"]
           [:table [:tr [:th "bar"] [:th "threshold"] [:th "latest evidence"]]
            (for [b (bars-with-evidence ctx)]
              [:tr [:td (:name b) [:div.muted [:code (:command b)]]] [:td (:threshold b)]
               [:td (if-let [e (:evidence b)]
                      [:div [:span.status {:class (if (= "0" (:exit e)) "met" "unmet")} "exit " (:exit e)] " " [:span.muted (:at e)] [:pre (:tail e)]]
                      [:span.status.pending "no evidence yet"])]])]]
          (let [t (telemetry/task-totals id)]
            [:section [:h2 "Telemetry"
                       (when t [:span.muted " · " (format "$%.4f" (:total-cost t)) " this task"])]
             (if t
               [:table [:tr [:th "role"] [:th "cost"] [:th "input"] [:th "output"] [:th "cache"] [:th "sessions"] [:th "active"]]
                (for [role (sort (keys (:cost t))) :let [tok (get (:tokens t) role {})]]
                  [:tr [:td role] [:td (format "$%.4f" (get (:cost t) role 0.0))]
                   [:td (long (get tok "input" 0.0))] [:td (long (get tok "output" 0.0))]
                   [:td (long (+ (get tok "cacheRead" 0.0) (get tok "cacheCreation" 0.0)))]
                   [:td (long (get (:sessions t) role 0.0))]
                   [:td (str (long (get (:active-seconds t) role 0.0)) "s")]])]
               [:p.muted "no telemetry for this task at " (telemetry/base-url)
                " — start VictoriaMetrics (`brew services start victoriametrics`) before `open`, or the roles' exports are dropped."])
             [:p.muted "dashboard: " [:a {:href (str (telemetry/base-url) "/vmui/#/?g0.expr=" (java.net.URLEncoder/encode (str "sum by (task_id, role) (claude_code.cost.usage{task_id=\"" id "\"})") "UTF-8"))} "vmui"]
              " · repo dashboard: dashboards/swarmkhazad.json"]])
          [:section [:h2 "Roles"]
           [:div.cards
            (for [c (role-cards ctx)]
              [:div.card [:a {:href (str "/tasks/" id "/roles/" (:role c))} (:role c)] " " [:span.muted (:harness c) " · " (:model c) " · " (:mode c)]
               [:div "judge: " (if-let [v (:verdict c)]
                                 [:span.status {:class (if (:met v) "met" "unmet")} (if (:met v) "met" (str "unmet: " (str/join "; " (:unmet v))))]
                                 [:span.status.pending "no verdict"])]
               [:div.muted "sent " (:sent c) " · inbox " (:inbox c)]
               [:div.muted {:style "white-space:pre-wrap;word-break:break-all"} (:last-line c)]])]]
          (for [[title k] [["decision.md" :decision-file] ["gotcha.md" :gotcha-file] ["escalation.md" :escalation-file]]]
            [:section [:h2 title] (let [ls (nonblank-lines (text (get ctx k)))] (if (seq ls) [:ul (for [l ls] [:li (str/replace l #"^- " "")])] [:p.muted "empty"]))])
          [:section [:h2 "Drafts"]
           (let [drafts (sort (map fs/file-name (fs/glob (:task-dir ctx) "draft-*.md")))]
             (if (seq drafts) [:ul (for [d drafts] [:li [:a {:href (str "/tasks/" id "/doc?path=" d)} d]])] [:p.muted "none yet"]))])))

(defn role-page [ctx role]
  (page (str (:task-id ctx) " · " role)
        [:section [:h2 "Pane " [:code (task-lib/session-name role)] " " [:span.muted "attach: tmux -S " (:tmux-socket ctx) " attach -t " (task-lib/session-name role)]]
         [:pre#pane {:style "max-height:80vh"} (pane-text ctx role)]
         [:script (h/raw (str "setInterval(async()=>{const r=await fetch('/tasks/" (:task-id ctx) "/roles/" role "/pane');const p=document.getElementById('pane');const stick=p.scrollTop+p.clientHeight>=p.scrollHeight-8;p.textContent=await r.text();if(stick)p.scrollTop=p.scrollHeight;},2000);"))]]))

;; ---------------------------------------------------------------- routing

(defn query-value [query key]
  (get (parse-form query) key))

(defn ctx-for [id]
  (when (and (task-lib/valid-task-id? id) (fs/directory? (:task-dir (task-lib/task-ctx id))))
    (task-lib/task-ctx id)))

(defn handle-request [{:keys [request-method uri query-string body]}]
  (let [method (or request-method :get)]
    (or
     (when (and (= :get method) (= "/" uri)) (html 200 (index-page nil)))
     (when (and (= :post method) (= "/tasks" uri))
       (let [result (kickstart! (parse-form (if (string? body) body (some-> body slurp))))]
         (if-let [id (:ok result)]
           {:status 303 :headers {"Location" (str "/tasks/" id)} :body ""}
           (html 400 (index-page (:error result))))))
     (when-let [[_ id] (and (= :get method) (re-matches #"/tasks/([^/]+)" uri))]
       (if-let [ctx (ctx-for id)] (html 200 (task-page ctx)) (not-found)))
     (when-let [[_ id] (and (= :get method) (re-matches #"/tasks/([^/]+)/doc" uri))]
       (let [ctx (ctx-for id) rel (query-value query-string "path")]
         (if (and ctx (allowed-doc? ctx rel)) (plain 200 (slurp (str (fs/path (:task-dir ctx) rel)))) (not-found))))
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
