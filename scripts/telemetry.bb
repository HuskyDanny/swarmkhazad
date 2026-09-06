#!/usr/bin/env bb

;; telemetry.bb — what a task has spent, read back out of VictoriaMetrics.
;;
;; The shim already exports Claude Code's OTEL metrics tagged task_id and role
;; (scripts/shim.sh). This is the read side: one query helper the portal's
;; Telemetry section and `swarmkhazad telemetry <task-id>` both use, so the
;; numbers on the page and the numbers on the terminal cannot disagree.
;;
;; Metric names arrive dotted (claude_code.cost.usage), which MetricsQL accepts
;; verbatim — the metrics.md spend bar is written that way and runs as written.

(ns telemetry
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "task_lib.bb")))

(defn base-url []
  ;; The OTLP endpoint the shim writes to is <base>/opentelemetry; the query API
  ;; is on the same server, so one env var configures both directions.
  (-> (or (not-empty (System/getenv "SWARMKHAZAD_OTLP_ENDPOINT")) "http://127.0.0.1:8428/opentelemetry")
      (str/replace #"/opentelemetry/?$" "")))

(defn query
  "One instant MetricsQL query. Returns [{:labels {} :value n}], or nil when
   VictoriaMetrics is not reachable — absent telemetry is not an error."
  [q]
  (try
    (let [r (http/get (str (base-url) "/api/v1/query")
                      {:query-params {"query" q} :throw false :timeout 3000})]
      (when (= 200 (:status r))
        (let [body (json/parse-string (:body r) true)]
          (when (= "success" (:status body))
            (vec (for [row (get-in body [:data :result])]
                   {:labels (:metric row) :value (parse-double (str (second (:value row))))}))))))
    (catch Exception _ nil)))

(def lookback
  "How far back a task's counters are looked up. Long enough that a task read
   back the next day still reports; SWARMKHAZAD_TELEMETRY_LOOKBACK overrides."
  (or (not-empty (System/getenv "SWARMKHAZAD_TELEMETRY_LOOKBACK")) "7d"))

(defn task-totals
  "Per role: cost, tokens by kind, sessions, active seconds. Plus the task total."
  [task-id]
  ;; sum_over_time over a window, not an instant read and not last_over_time.
  ;; Claude Code exports these as DELTA counters: each sample is the increment
  ;; since the previous export, so a series rises and falls (measured on a live
  ;; three-role task: samples 0.092, 0.048, 0.209, … whose sum, $3.01, matched
  ;; the three sessions' own reported $3.03, while last_over_time said $0.40).
  ;; The window also outlives the 5-minute staleness an instant query stops at.
  (let [sel (str "{task_id=\"" task-id "\"}[" lookback "]")
        ;; A nil from `query` means the server did not answer — that must stay
        ;; nil all the way out, so a caller can say "no telemetry" instead of
        ;; showing a confident $0.00.
        rows->map (fn [rows] (when rows (into {} (for [{:keys [labels value]} rows] [(:role labels) value]))))
        by-role (fn [metric] (rows->map (query (str "sum by (role) (sum_over_time(" metric sel "))"))))
        tokens (query (str "sum by (role, type) (sum_over_time(claude_code.token.usage" sel "))"))
        cost (by-role "claude_code.cost.usage")]
    (when (some? cost)
      {:cost cost
       :sessions (by-role "claude_code.session.count")
       :active-seconds (by-role "claude_code.active_time.total")
       :tokens (reduce (fn [m {:keys [labels value]}]
                         (update-in m [(:role labels) (or (:type labels) "other")] (fnil + 0) value))
                       {} tokens)
       :total-cost (reduce + 0.0 (vals cost))})))

(defn -main [& args]
  (let [task-id (first args)]
    (when-not (task-lib/valid-task-id? task-id)
      (task-lib/fail! "usage: swarmkhazad telemetry <task-id>"))
    (if-let [t (task-totals task-id)]
      (do
        (println (format "%-16s %10s %10s %10s %10s %8s" "role" "cost" "input" "output" "cache" "sessions"))
        (doseq [role (sort (keys (:cost t)))
                :let [tok (get (:tokens t) role {})]]
          (println (format "%-16s %10.4f %10.0f %10.0f %10.0f %8.0f"
                           role (get (:cost t) role 0.0)
                           (get tok "input" 0.0) (get tok "output" 0.0)
                           (+ (get tok "cacheRead" 0.0) (get tok "cacheCreation" 0.0))
                           (get (:sessions t) role 0.0))))
        (println (format "%-16s %10.4f" "TOTAL" (:total-cost t))))
      (do (println (str "no telemetry: " (base-url) " is not answering, or nothing has been exported for " task-id))
          (System/exit 1)))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
