#!/usr/bin/env bb

;; ask.bb — one model call, no session, no tools.
;;
;; Two places want to ask a question and get an answer back: the brief
;; normaliser, which turns a paste into the three sections, and the summariser,
;; which reads a whole task and says whether it is ready to merge. Both are
;; one-shot, both are started by a person clicking something, and neither wants
;; a swarm.
;;
;; This is deliberately NOT the judge's path. The judge is a Stop hook inside a
;; role's own session and calls `claude` off PATH, which inside that session is
;; the role's shim. Here there is no role and no shim, so the binary is resolved
;; the same way `open` resolves it — first candidate on PATH that is not a
;; wrapper shim — or a call from a cmux pane would go to cmux's wrapper.

(ns ask
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "task_lib.bb")))

(def default-timeout-ms 240000)

(defn- argv [{:keys [bin model system schema]}]
  (concat [bin "-p" "--model" model]
          (when schema ["--json-schema" schema])
          ;; No tools and no MCP: the question carries everything the answer
          ;; needs, and a call that can read the disk is a call that can wander.
          ["--tools" "" "--strict-mcp-config" "--mcp-config" "{\"mcpServers\":{}}"
           "--no-session-persistence" "--output-format" "json"]
          (when system ["--system-prompt" system])))

(defn ask
  "One call. Returns {:text ... :cost ... :model ...}, or {:error ...} — never
   throws, because every caller is a web request that must still render.

   The prompt goes on STDIN rather than in the argv. A brief or a task summary
   is far past the point where an argument list is a sane place to put text,
   and putting it there is what `Argument list too long` looked like the last
   time something in this repo tried.

   `:schema` asks for structured output and puts the parsed object in :json."
  [{:keys [prompt model system schema timeout-ms extra-env label]
    :or {model "sonnet" timeout-ms default-timeout-ms label "ask"}}]
  (if-let [bin (:path (task-lib/resolve-harness "claude"))]
    (let [p (process/process (argv {:bin bin :model model :system system :schema schema})
                             {:in prompt :out :string :err :string
                              :extra-env (merge {"MAX_THINKING_TOKENS" "0"
                                                 ;; keep this call out of the task's own
                                                 ;; per-role spend, the way the judge does
                                                 "OTEL_RESOURCE_ATTRIBUTES" (str "role=" label)}
                                                extra-env)})
          done (deref p timeout-ms nil)
          _ (when-not done (process/destroy-tree p))
          r (if done @p {:exit -1 :out "" :err (str "timed out after " timeout-ms "ms")})
          parsed (try (json/parse-string (:out r)) (catch Exception _ nil))]
      (cond
        (not done) {:error (str label " timed out after " (quot timeout-ms 1000) "s")}
        (not (zero? (:exit r))) {:error (str/trim (str (:err r) " "
                                                       (subs (or (:out r) "") 0 (min 400 (count (or (:out r) ""))))))}
        (nil? parsed) {:error (str label ": the harness returned no JSON")}
        :else {:text (get parsed "result")
               :json (get parsed "structured_output")
               :cost (get parsed "total_cost_usd")
               :model (first (keys (get parsed "modelUsage")))}))
    {:error "no claude binary on PATH that is not a wrapper shim — set SWARMKHAZAD_HARNESS_CLAUDE"}))
