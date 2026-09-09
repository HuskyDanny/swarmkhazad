(ns swarmkhazad.telemetry-test
  "telemetry.bb against a stand-in VictoriaMetrics: the query API is one HTTP
   endpoint, so the tests serve it themselves and pin the queries sent, the
   shapes parsed, and what happens when it is not there."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as http]))

(def repo-root (str (fs/cwd)))
(def scripts (str (fs/path repo-root "scripts")))
(def cli (str (fs/path scripts "swarmkhazad.bb")))

(defn parse-query [query-string]
  (get (into {} (for [pair (str/split (or query-string "") #"&")
                      :let [[k v] (str/split pair #"=" 2)]]
                  [(java.net.URLDecoder/decode k "UTF-8") (java.net.URLDecoder/decode (or v "") "UTF-8")]))
       "query"))

(defn vector-result [rows]
  {:status 200 :headers {"Content-Type" "application/json"}
   :body (json/generate-string {:status "success"
                                :data {:resultType "vector"
                                       :result (for [[labels value] rows]
                                                 {:metric labels :value [1788699583 (str value)]})}})})

(defn with-fake-vm
  "Serve /api/v1/query with `answer`, a fn of the MetricsQL string. Returns
   [queries-seen result-of-f]."
  [answer f]
  (let [seen (atom [])
        stop (http/run-server (fn [req]
                                (let [q (parse-query (:query-string req))]
                                  (swap! seen conj q)
                                  (answer q)))
                              {:ip "127.0.0.1" :port 0})
        port (:local-port (meta stop))]
    (try
      [seen (f (str "http://127.0.0.1:" port "/opentelemetry"))]
      (finally (stop)))))

(defn totals
  "Call telemetry/task-totals in a child bb with the endpoint pointed at the fake."
  [endpoint task-id]
  (let [r (process/sh {:continue true :dir repo-root :extra-env {"SWARMKHAZAD_OTLP_ENDPOINT" endpoint}}
                      "bb" "-e" (str "(load-file \"" scripts "/telemetry.bb\") (print (pr-str (telemetry/task-totals \"" task-id "\")))"))]
    (when-not (zero? (:exit r)) (throw (ex-info (str "task-totals failed: " (:err r)) r)))
    (read-string (:out r))))

(def rows
  {:cost [[{:role "implement"} 0.25] [{:role "review"} 0.5]]
   :session [[{:role "implement"} 3] [{:role "review"} 1]]
   :active [[{:role "implement"} 42.5]]
   :token [[{:role "implement" :type "input"} 100]
           [{:role "implement" :type "output"} 20]
           [{:role "implement" :type "cacheRead"} 900]
           [{:role "implement" :type "cacheCreation"} 50]
           [{:role "review" :type "input"} 7]]})

(defn canned [q]
  (vector-result (cond
                   (str/includes? q "claude_code.cost.usage") (:cost rows)
                   (str/includes? q "claude_code.session.count") (:session rows)
                   (str/includes? q "claude_code.active_time.total") (:active rows)
                   (str/includes? q "claude_code.token.usage") (:token rows)
                   :else [])))

(deftest task-totals-asks-metricsql-for-each-metric-scoped-to-the-task-and-sums-per-role
  (let [[seen t] (with-fake-vm canned #(totals % "t-1"))]
    (testing "every query is scoped to the task, grouped by role, dotted names verbatim, over a lookback"
      (is (= 4 (count @seen)))
      (is (every? #(str/includes? % "{task_id=\"t-1\"}[7d]") @seen))
      (is (every? #(str/includes? % "sum_over_time(") @seen)
          "deltas must be summed over the window, and the window also outlives the 5-minute staleness of an instant query")
      (is (some #{"sum by (role) (sum_over_time(claude_code.cost.usage{task_id=\"t-1\"}[7d]))"} @seen))
      (is (some #{"sum by (role) (sum_over_time(claude_code.session.count{task_id=\"t-1\"}[7d]))"} @seen))
      (is (some #{"sum by (role) (sum_over_time(claude_code.active_time.total{task_id=\"t-1\"}[7d]))"} @seen))
      (is (some #{"sum by (role, type) (sum_over_time(claude_code.token.usage{task_id=\"t-1\"}[7d]))"} @seen)))
    (testing "cost per role and the task total"
      (is (= {"implement" 0.25 "review" 0.5} (:cost t)))
      (is (= 0.75 (:total-cost t))))
    (testing "tokens nest role → kind; sessions and active time come through"
      (is (= {"input" 100.0 "output" 20.0 "cacheRead" 900.0 "cacheCreation" 50.0} (get (:tokens t) "implement")))
      (is (= {"input" 7.0} (get (:tokens t) "review")))
      (is (= {"implement" 3.0 "review" 1.0} (:sessions t)))
      (is (= {"implement" 42.5} (:active-seconds t))))))

(deftest a-task-with-no-metrics-is-empty-not-an-error-and-an-absent-server-is-nil
  (testing "the server answers, but nothing matches: empty maps, zero total"
    (let [[_ t] (with-fake-vm (fn [_] (vector-result [])) #(totals % "t-empty"))]
      (is (= {} (:cost t)))
      (is (= 0.0 (:total-cost t)))))
  (testing "the server is not there at all: nil, so callers can say so instead of crashing"
    (is (nil? (totals "http://127.0.0.1:1/opentelemetry" "t-1"))))
  (testing "the server errors: nil as well"
    (let [[_ t] (with-fake-vm (fn [_] {:status 500 :body "boom"}) #(totals % "t-1"))]
      (is (nil? t))))
  (testing "the server answers 200 with an error body: nil"
    (let [[_ t] (with-fake-vm (fn [_] {:status 200 :body (json/generate-string {:status "error" :error "bad query"})}) #(totals % "t-1"))]
      (is (nil? t))))
  (testing "a non-200 whose body still parses as a good answer is not an answer — an intermediary can return one"
    (let [[_ t] (with-fake-vm (fn [q] (assoc (canned q) :status 503)) #(totals % "t-1"))]
      (is (nil? t)))))

(deftest the-lookback-window-is-overridable
  (let [[seen _] (with-fake-vm canned
                   (fn [endpoint]
                     (process/sh {:continue true :dir repo-root
                                  :extra-env {"SWARMKHAZAD_OTLP_ENDPOINT" endpoint "SWARMKHAZAD_TELEMETRY_LOOKBACK" "30m"}}
                                 "bb" "-e" (str "(load-file \"" scripts "/telemetry.bb\") (telemetry/task-totals \"t-1\")"))))]
    (is (every? #(str/includes? % "[30m]") @seen))))

(deftest the-endpoint-is-derived-from-the-one-env-var-the-shim-writes-to
  (let [base (fn [env] (str/trim (:out (process/sh {:dir repo-root :extra-env env}
                                                   "bb" "-e" (str "(load-file \"" scripts "/telemetry.bb\") (print (telemetry/base-url))")))))]
    (is (= "http://127.0.0.1:8428" (base {})) "default matches the shim's default OTLP endpoint")
    (is (= "http://vm.local:9999" (base {"SWARMKHAZAD_OTLP_ENDPOINT" "http://vm.local:9999/opentelemetry"})))
    (is (= "http://vm.local:9999" (base {"SWARMKHAZAD_OTLP_ENDPOINT" "http://vm.local:9999/opentelemetry/"})))
    (is (= "http://vm.local:9999" (base {"SWARMKHAZAD_OTLP_ENDPOINT" "http://vm.local:9999"})))))

(deftest the-cli-prints-a-row-per-role-and-says-so-when-there-is-no-telemetry
  (testing "with telemetry: a row per role, cache columns summed, a total line"
    (let [[_ out] (with-fake-vm canned
                    (fn [endpoint]
                      (let [r (process/sh {:continue true :dir repo-root :extra-env {"SWARMKHAZAD_OTLP_ENDPOINT" endpoint}}
                                          "bb" cli "telemetry" "t-1")]
                        (is (zero? (:exit r)) (:err r))
                        (:out r))))]
      (is (re-find #"implement\s+0\.2500\s+100\s+20\s+950\s+3" out) "cache is cacheRead plus cacheCreation, not either alone")
      (is (re-find #"review\s+0\.5000\s+7\s+0\s+0\s+1" out))
      (is (re-find #"TOTAL\s+0\.7500" out))))
  (testing "without: exit 1 and the endpoint named, so the operator knows what to start"
    (let [r (process/sh {:continue true :dir repo-root :extra-env {"SWARMKHAZAD_OTLP_ENDPOINT" "http://127.0.0.1:1/opentelemetry"}}
                        "bb" cli "telemetry" "t-1")]
      (is (= 1 (:exit r)))
      (is (str/includes? (:out r) "http://127.0.0.1:1"))))
  (testing "a bad task id is refused before any query"
    (let [r (process/sh {:continue true :dir repo-root} "bb" cli "telemetry" "../etc")]
      (is (= 1 (:exit r)))
      (is (str/includes? (:err r) "usage: swarmkhazad telemetry")))))

(def exported-metrics
  "Every metric Claude Code's OTLP exporter actually emits.

   RAN against the live VictoriaMetrics — /api/v1/label/__name__/values over the
   window the data spans. The set used to name four of these and call itself
   \"the four metrics Claude Code actually exports\", which was wrong in the
   direction that hurts: a panel querying commits, lines of code or edit-tool
   decisions is perfectly real and this test rejected it."
  #{"claude_code.cost.usage"
    "claude_code.token.usage"
    "claude_code.session.count"
    "claude_code.active_time.total"
    "claude_code.commit.count"
    "claude_code.lines_of_code.count"
    "claude_code.code_edit_tool.decision"})

(deftest the-dashboard-ships-with-the-repo-and-every-panel-queries-a-real-metric
  (let [file (fs/path repo-root "dashboards" "swarmkhazad.json")
        d (json/parse-string (slurp (str file)) true)
        panels (mapcat :panels (:rows d))
        exprs (mapcat :expr panels)
        all (str/join " " exprs)]
    (is (= "swarmkhazad" (:title d)))
    (is (= "swarmkhazad.json" (:filename d)) "vmui matches the file by this field")
    (is (<= 3 (count panels)))
    (is (every? (fn [e] (some #(str/includes? e %) exported-metrics)) exprs)
        "every panel queries a metric that is actually exported")
    (testing "every exported metric is charted — an unused one is a question nobody can ask"
      (doseq [m exported-metrics]
        (is (str/includes? all m) (str m " appears in no panel"))))
    (testing "the dimensions the shim writes are the dimensions something groups by"
      ;; A label written and never grouped by is dead weight; a label grouped by
      ;; and never written is a permanently empty chart. Both are silent.
      (doseq [dim ["role" "repo" "project" "model" "task_id"]]
        (is (str/includes? all (str "by (" dim))
            (str "nothing groups by " dim))))
    (testing "no panel can render a nameless line"
      ;; The bug Allen spotted on the page: `sum by (repo)` returns a series for
      ;; everything with no repo — this session, the judge, any Claude Code
      ;; outside a task — and it was BOTH unlabelled and the largest line on the
      ;; chart ($85.93 against $31.36 for the biggest real repo). It renders as a
      ;; blank legend entry, so the reader cannot tell what it is or that it is
      ;; not a repo at all.
      ;;
      ;; Filtering it out would be worse: the spend is real. It gets a name.
      (doseq [dim ["repo" "project" "language"]]
        (let [grouping (filter #(str/includes? % (str "by (" dim)) exprs)]
          (when (seq grouping)
            (is (every? #(str/includes? % "label_replace") grouping)
                (str "a panel grouped by " dim " can return a series with no " dim
                     ", and it must be named rather than left blank"))))))
    (testing "no panel is an unreadable pile of lines"
      ;; Measured on the live server before this: `sum by (task_id, role)` gave
      ;; 39 series on one chart and `by (role, type)` gave 52. Grouping by
      ;; task_id across every task that ever ran is what did it.
      (doseq [e exprs]
        (when (and (str/includes? e "by (task_id")
                   (str/includes? e ","))
          (is (str/includes? e "topk")
              (str "grouping by task_id and another label needs a topk: " e)))))
    (testing "a trend panel uses a window short enough to move"
      ;; sum_over_time(m[7d]) is the same number at every point inside a window
      ;; shorter than 7d, so it draws a flat line and shows no trend at all --
      ;; which is what the whole dashboard did. RAN: at [1h] the same expression
      ;; varies (implement: 16.99 then 10.2).
      ;; NOT `some panel uses [1h]`: five of them do, so changing any one back
      ;; to [7d] leaves the others to satisfy that and the mutant lives. The
      ;; invariant is per panel — a title promising "per hour" that queries
      ;; seven days is a lie, and a flat line.
      (is (seq (filter #(str/includes? (str/lower-case (:title %)) "per hour") panels))
          "the page needs at least one per-hour panel or nothing on it moves")
      (doseq [p panels
              :when (str/includes? (str/lower-case (:title p)) "per hour")]
        (is (every? #(str/includes? % "[1h]") (:expr p))
            (str "\"" (:title p) "\" says per hour and must query an hour"))))
    (testing "no two panels are the same chart wearing a different title"
      ;; A panel's signature is what it actually asks the database: which
      ;; metrics, which labels it groups by, over what window, under what label
      ;; filter. Two panels sharing all four are one chart shown twice, however
      ;; differently they are worded — and a dashboard grows them quietly,
      ;; because each one looked reasonable on the day it was added.
      ;;
      ;; Same metrics and grouping at a DIFFERENT window is caught too. That
      ;; pairing is sometimes deliberate (a trend beside a total), but it is
      ;; the shape a duplicate takes most often, so it has to be argued for
      ;; rather than accumulated.
      (let [sig (fn [p]
                  (let [blob (str/join " " (:expr p))
                        pull (fn [re] (sort (distinct (map second (re-seq re blob)))))]
                    ;; re-seq with NO capture group yields the matched STRINGS,
                    ;; so `map first` here took the first character of each and
                    ;; every panel signed as (\c) — the guard reported all six
                    ;; role panels as duplicates of each other. A test that
                    ;; fails for the wrong reason tests nothing.
                    {:metrics (sort (distinct (re-seq #"claude_code\.[a-z_.]+" blob)))
                     :groups (sort (distinct (mapcat #(map str/trim (str/split % #","))
                                                     (map second (re-seq #"by \(([^)]*)\)" blob)))))
                     :windows (pull #"\[(\d+[smhd])\]")
                     :filters (pull #"type[=~!]+\"([^\"]+)\"")}))
            dupes (->> panels
                       (group-by sig)
                       (filter (fn [[_ ps]] (< 1 (count ps))))
                       (map (fn [[_ ps]] (mapv :title ps))))
            near (->> panels
                      (group-by #(dissoc (sig %) :windows))
                      (filter (fn [[_ ps]] (< 1 (count ps))))
                      (map (fn [[_ ps]] (mapv :title ps))))]
        (is (empty? dupes) (str "identical panels: " (pr-str dupes)))
        (is (empty? near)
            (str "same metrics and grouping, only the window differs: " (pr-str near)))))
    (testing "the cost levers are charted, not just the totals"
      ;; A total says what was spent. A ratio says what to change.
      ;; Not `includes? "cacheRead"` over the whole dashboard: there are two
      ;; cache panels, so deleting either one leaves the other to satisfy that
      ;; check and the mutant lives. Each dimension is asserted on its own.
      (is (some #(and (str/includes? % "cacheRead") (str/includes? % "by (role)")) exprs)
          "cache hit ratio per role — the largest single cost lever")
      (is (some #(and (str/includes? % "cacheRead") (str/includes? % "by (model)")) exprs)
          "and per model, which is how you tell a cheap tier from a cheap prompt")
      (is (some #(and (str/includes? % "claude_code.cost.usage")
                      (str/includes? % "claude_code.commit.count")) exprs)
          "cost per commit — what a landed change actually costs")
      (is (some #(str/includes? % "-judge") exprs)
          "the goal judge runs as its own session and spends its own money"))))
