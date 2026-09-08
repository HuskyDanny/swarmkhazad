#!/usr/bin/env bb

;; linear_intake.bb — a Linear issue key in, a goal.md skeleton out.
;;
;; `swarmkhazad open --linear <KEY>` (or `new --linear <KEY>`) fetches the issue
;; through the Linear MCP server and writes the task folder's goal.md from it:
;; the title as the heading, the description as the body, the acceptance-looking
;; lines as Goal checkboxes, the issue URL as the first Hint. `roles` gets one
;; line, `implement`.
;;
;; The fetch is a headless `claude -p` with exactly one MCP server and exactly
;; one tool allowed — mcp__linear-server__get_issue — asking for JSON back. That
;; is the whole integration: no Linear API key of our own, no HTTP client, no
;; second auth path. The operator's own Linear MCP config is the credential.

(ns linear-intake
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "task_lib.bb")))

(def fetch-timeout-ms 180000)
(def issue-key-re #"[A-Za-z][A-Za-z0-9]*-[0-9]+")

(def issue-schema
  (json/generate-string
   {:type "object"
    :properties {:found {:type "boolean" :description "True only when get_issue actually returned the issue. False when it errored, was not found, or access was denied — in that case put the error text in title and leave the rest empty."}
                 :identifier {:type "string" :description "The issue key, e.g. ENG-1234."}
                 :title {:type "string"}
                 :description {:type "string" :description "The issue description, verbatim markdown. Empty string when it has none."}
                 :url {:type "string"}
                 :state {:type "string" :description "The workflow state name, e.g. Todo."}
                 :acceptance {:type "array" :items {:type "string"}
                              :description "One line per acceptance criterion or checklist item found in the description, verbatim, without its bullet or checkbox. Empty when the description states none."}}
    :required ["found" "identifier" "title" "description" "url" "acceptance"]}))

(defn valid-issue-key? [k]
  (boolean (and (string? k) (re-matches issue-key-re k))))

(defn mcp-config []
  ;; The operator's own Linear MCP server, by name, from ~/.claude.json. Passing
  ;; it explicitly with --strict-mcp-config keeps every other server out of the
  ;; fetch: one server, one tool, nothing else reachable.
  (json/generate-string {:mcpServers {"linear-server" {:type "http" :url "https://mcp.linear.app/mcp"}}}))

(defn fetch-argv [issue-key]
  ["claude" "-p" "--model" (or (not-empty (System/getenv "SWARMKHAZAD_LINEAR_MODEL")) "haiku")
   "--json-schema" issue-schema
   "--strict-mcp-config" "--mcp-config" (mcp-config)
   "--allowed-tools" "mcp__linear-server__get_issue"
   "--permission-mode" "bypassPermissions"
   "--no-session-persistence" "--output-format" "json"
   "--system-prompt" (str "You read one Linear issue and report it. Call get_issue for the key you are given, once. "
                          "Copy the fields verbatim — never summarise, never invent an acceptance criterion the issue does not state. "
                          "Report through the structured output only.")
   "--" (str "Fetch Linear issue " issue-key " and report it.")])

(defn fetch-issue
  "The issue as a map, or throws with what went wrong. Runs the operator's own
   `claude` from PATH: this is intake, before any task folder or shim exists."
  [issue-key]
  (when-not (valid-issue-key? issue-key)
    (throw (ex-info (str "not a Linear issue key: " (pr-str issue-key)) {})))
  (let [p (process/process (fetch-argv issue-key) {:out :string :err :string})
        done (deref p fetch-timeout-ms nil)
        _ (when-not done (process/destroy-tree p))
        result (if done @p {:exit -1 :out "" :err (str "timed out after " fetch-timeout-ms " ms")})
        parsed (try (json/parse-string (:out result) true) (catch Exception _ nil))
        issue (:structured_output parsed)]
    (cond
      (not done) (throw (ex-info (str "Linear fetch timed out for " issue-key) {}))
      (not (zero? (:exit result))) (throw (ex-info (str "Linear fetch failed for " issue-key ": " (str/trim (str (:err result)))) {}))
      (not (map? issue)) (throw (ex-info (str "Linear fetch returned no issue for " issue-key
                                              ". Is the Linear MCP server authorised? " (str/trim (str (:result parsed)))) {}))
      ;; A missing or forbidden issue comes back as a well-formed answer whose
      ;; title is the error text. Without this it would become a goal.md.
      (not (true? (:found issue))) (throw (ex-info (str "Linear issue " issue-key " was not returned: "
                                                        (or (not-empty (str/trim (str (:title issue)))) "no reason given")) {}))
      (str/blank? (:title issue)) (throw (ex-info (str "Linear issue " issue-key " came back without a title") {}))
      (not= (str/lower-case (str (:identifier issue))) (str/lower-case issue-key))
      (throw (ex-info (str "Linear returned " (:identifier issue) " when asked for " issue-key) {}))
      :else issue)))

(defn goal-md
  "The skeleton: what the issue says, shaped as the contract. Nothing is
   invented — an issue with no acceptance criteria gets one checkbox naming the
   title, and the operator fills the rest in before `open`."
  [task-id {:keys [identifier title description url state acceptance]}]
  (let [lines (if (seq acceptance) acceptance [title])]
    (str "# " task-id " — " title "\n"
         "From Linear " identifier (when-not (str/blank? state) (str " (" state ")")) " · " (java.time.LocalDate/now) "\n\n"
         "## Goal\n"
         (str/join "" (for [l lines] (str "- [ ] implement — " (str/trim l) "\n")))
         "\n## Not-goal\n"
         "- <what this issue deliberately does not cover — fill in before open>\n"
         "\n## Hints\n"
         "- " url " — the issue\n"
         "\n## From the issue\n\n"
         (if (str/blank? description) "(the issue has no description)\n" (str (str/trim description) "\n")))))

(defn task-id-for
  "The task id an issue key implies when the operator names none: the key,
   lowercased. Task ids allow letters, digits, dot, dash and underscore."
  [issue-key]
  (str/lower-case issue-key))

(defn write-from-issue!
  "Write the task's goal.md, roles and repos from an already-fetched issue."
  [ctx issue repos]
  (spit (str (:goal-file ctx)) (goal-md (:task-id ctx) issue))
  (spit (str (:roles-file ctx)) (task-lib/roles-template repos))
  (spit (str (:repos-file ctx)) (task-lib/repos-text repos))
  issue)
