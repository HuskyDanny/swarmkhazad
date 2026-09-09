#!/usr/bin/env bb

;; mcp_gateway.bb — swarmkhazad as one MCP tool, over stdio.
;;
;; `swarmkhazad mcp` speaks JSON-RPC 2.0 on stdin/stdout and exposes exactly one
;; tool, `add_task`. It exists so an unattended loop session can open a task
;; without shelling out: a Bash call in a `bypassPermissions` session is
;; unbounded, and a session that may run ONE named tool is a much smaller thing
;; to leave running overnight.
;;
;; The tool does no work of its own. It runs `swarmkhazad open --linear <KEY>`,
;; which is the path a human uses at the terminal, so the issue arrives through
;; linear_intake.bb's verbatim fetch — one MCP server, one allowed tool,
;; schema-forced — and goal.md is provably the ticket's own text rather than a
;; session's paraphrase of it. That guarantee is the reason the tool takes an
;; issue KEY and not a description.
;;
;; One tool, not five. `close`, `paths` and the rest are already reachable from
;; the terminal and from the portal, and every one of them added here is another
;; thing an unattended session can do at 3am.

(ns mcp-gateway
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
;; For `valid-issue-key?` only. Validating at the trust boundary is right;
;; owning a private copy of the grammar is what drifts, and the fetch this tool
;; ends up calling validates against that one.
(load-file (str (fs/path script-dir "linear_intake.bb")))

(def protocol-version "2024-11-05")

(def add-task-tool
  {:name "add_task"
   :description
   (str "Open a swarmkhazad task from a Linear issue. The issue is fetched verbatim through the "
        "operator's own Linear MCP credential and becomes the task's goal.md; nothing is "
        "summarised. An existing task folder for this key is opened as it stands rather than "
        "overwritten.\n\n"
        "`repo` is required in practice: a task with no checkout is refused at prepare "
        "(`repos declaration is empty`), because every role's session is opened in a worktree. "
        "Pass the checkout the ticket's `repo:*` label maps to.\n\n"
        "`investigate` picks the two-role investigation lineup (investigate → run) instead of the "
        "default single `implement` role. Use it for a ticket with no Evidence label.")
   :inputSchema
   {:type "object"
    :properties {:issue_key {:type "string"
                             :description "The Linear issue key, e.g. MITH-1234."}
                 :repo {:type "array" :items {:type "string"}
                        :description "Absolute paths of the checkouts this task works in."}
                 :investigate {:type "boolean"
                               :description "Open the investigation lineup (investigate → run). Default false."}}
    :required ["issue_key"]}})

(def max-repos
  "A ticket names one checkout, sometimes two. The cap is not about disk — it is
   that `open` builds a worktree and a codegraph index per repo, so a list a
   model got wrong is minutes of work nobody asked for."
  4)

(def open-timeout-ms
  "`open` adds a worktree and indexes it per repo, which is seconds; ten minutes
   is the outer bound of a bad day. Bounded at all because this server is the
   only thing an unattended loop session talks to, and a `sh` with no deadline
   wedges it in a way that looks exactly like a slow ticket."
  600000)

(defn validate
  "The arguments as `open` should receive them, or {:error <why>}.

   Both fields are checked HERE as well as downstream, and that is not
   duplication: they arrive from a MODEL and become argv elements, a task id,
   and a git checkout path. `open` and `check-repos!` would refuse a bad one
   too, but only after being handed whatever string came in."
  [{:keys [issue_key repo investigate]}]
  (let [repos (cond (string? repo) [repo] (sequential? repo) (vec repo) :else [])
        bad (remove #(and (string? %) (str/starts-with? % "/")) repos)]
    (cond
      (not (linear-intake/valid-issue-key? issue_key))
      {:error (str "not a Linear issue key: " (pr-str issue_key))}

      (seq bad)
      ;; A leading `-` would be read as a flag rather than as the value of
      ;; `--repo`, and a relative path resolves against whatever directory this
      ;; server happens to have been started in.
      {:error (str "every repo must be an absolute path; got " (pr-str (first bad)))}

      (> (count repos) max-repos)
      {:error (str "at most " max-repos " repos; got " (count repos))}

      :else {:issue-key issue_key :repos repos :investigate (true? investigate)})))

(defn add-task!
  "Shell `swarmkhazad open`. Returns {:ok? :text}."
  [args]
  (let [{:keys [error issue-key repos investigate]} (validate args)]
    (if error
      {:ok? false :text error}
      (let [argv (concat ["bb" (str (fs/path script-dir "swarmkhazad.bb")) "open" "--linear" issue-key]
                         (when investigate ["--investigate"])
                         (mapcat (fn [p] ["--repo" p]) repos))
            ;; `:in ""` and not the default. The default is INHERIT, and this
            ;; process's stdin is the JSON-RPC transport — so `open`, and the
            ;; headless `claude -p` it runs for the Linear fetch, would read the
            ;; loop session's next request frames as their own input. Two ways
            ;; that ends, both silent: the frames are consumed and the stream
            ;; desynchronises, or `open` blocks on a stdin that never EOFs until
            ;; the timeout kills it. An empty stream closes immediately, which
            ;; is what a child with nothing to read should see.
            p (process/process argv {:in "" :out :string :err :string :continue true})
            done (deref p open-timeout-ms nil)
            _ (when-not done (process/destroy-tree p))
            r (if done @p {:exit -1 :out "" :err (str "`open` did not finish within " open-timeout-ms " ms and was killed")})]
        {:ok? (zero? (:exit r))
         :text (str/trim (str (:out r) (:err r)))}))))

;; ---------------------------------------------------------------- JSON-RPC

(defn result [id value]
  {:jsonrpc "2.0" :id id :result value})

(defn rpc-error [id code message]
  {:jsonrpc "2.0" :id id :error {:code code :message message}})

(defn tool-result [{:keys [ok? text]}]
  {:content [{:type "text" :text text}] :isError (not ok?)})

(defn keywordize-keys [m]
  (into {} (for [[k v] m] [(keyword (str k)) v])))

(defn handle
  "One request → one response map, or nil for a notification.

   A notification is a message with no `id`, and the spec says it gets no reply.
   Answering one is not harmless: the client is not waiting for it, so the extra
   frame lands on the NEXT request's read and desynchronises the stream."
  [{:strs [id method params]}]
  (let [notification? (nil? id)]
    (cond
      notification? nil

      (= "initialize" method)
      (result id {:protocolVersion protocol-version
                  :capabilities {:tools {}}
                  :serverInfo {:name "swarmkhazad" :version "1"}})

      (= "tools/list" method)
      (result id {:tools [add-task-tool]})

      (= "tools/call" method)
      (let [name (get params "name")
            args (keywordize-keys (or (get params "arguments") {}))]
        (if (= "add_task" name)
          (result id (tool-result (add-task! args)))
          ;; A tool error, not a protocol error: the client shows the text to
          ;; the model, which can then correct itself. A -32602 is shown to
          ;; nobody and reads as a broken server.
          (result id (tool-result {:ok? false :text (str "no such tool: " (pr-str name))}))))

      :else (rpc-error id -32601 (str "method not found: " method)))))

(defn -main [& _]
  (let [out *out*]
    (doseq [line (line-seq (java.io.BufferedReader. *in*))
            :when (not (str/blank? line))]
      (let [response (try
                       (handle (json/parse-string line))
                       (catch Exception e
                         (rpc-error nil -32700 (str "cannot handle request: " (ex-message e)))))]
        (when response
          (binding [*out* out]
            (println (json/generate-string response))
            (flush)))))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
