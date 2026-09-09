#!/usr/bin/env bb

;; project-lib — the layer above tasks.
;;
;; A task is one swarm: one goal.md, one set of repos, one role lineup, one
;; board card walking the role lanes to `done`. Every task re-declared all of
;; that, so the answer to "run another task on this repo" was to write the roles
;; file again. A project holds the parts that do not change between tasks — the
;; checkouts and who is in the swarm — so a new task asks only what is actually
;; new: the goal, the not-goals and the bars.
;;
;; ~/.swarmkhazad/projects/<name>.edn, one map:
;;   {:repos ["/abs/path" ...]
;;    :roles [{:role "implement" :harness "claude" :model "anthropic"} ...]
;;    :cloud-env "ccpool_..."}
;;
;; EDN rather than another line grammar: bb reads it with no parser of ours, and
;; the role list is a list of maps rather than a positional line.
;;
;; The link runs task -> project, written as <task>/project. A task that names a
;; project its owner later deletes still opens; the swimlane just loses a column.

(ns project-lib
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "task_lib.bb")))

(defn projects-dir []
  (fs/path (task-lib/home) "projects"))

(defn valid-project-name?
  "Same shape as a task id: it is a filename and a form value, nothing more."
  [name]
  (boolean (and (string? name) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]{0,99}" name))))

(defn project-file [name]
  (when (valid-project-name? name)
    (fs/path (projects-dir) (str name ".edn"))))

(defn read-project
  "The project map, or nil. A file that is not readable EDN reads as absent
   rather than throwing: the portal lists projects on every render, and one bad
   file must not take the index down."
  [name]
  (when-let [f (project-file name)]
    (when (fs/regular-file? f)
      (try
        (let [m (edn/read-string (slurp (str f)))]
          (when (map? m)
            (assoc m :name name)))
        (catch Exception _ nil)))))

(defn list-projects []
  (let [dir (projects-dir)]
    (if (fs/directory? dir)
      (->> (fs/glob dir "*.edn")
           (map #(str/replace (fs/file-name %) #"\.edn$" ""))
           (filter valid-project-name?)
           sort
           (keep read-project)
           vec)
      [])))

(def cloud-env-var "SWARMKHAZAD_CLOUD_ENV")

(defn valid-cloud-env?
  "A self-hosted environment id, or blank. Blank is a real answer — a project
   with no cloud bars needs no environment — so it is not an error, it just
   means an @cloud bar in that project reports `blocked` and says why."
  [s]
  (or (str/blank? s) (boolean (re-matches #"ccpool_[A-Za-z0-9]{1,64}" (str s)))))

(defn known-cloud-envs
  "Every environment id already in use, for the picker to offer. There is no
   API that lists them — `claude` has no `environments` subcommand — so the
   only honest source is the ones already chosen here, plus whatever the
   variable names."
  []
  ;; list-projects already returns project MAPS, not names — mapping
  ;; read-project over them gives nil for every one, and an empty datalist is
  ;; indistinguishable from "no environments have been used yet".
  (->> (cons (System/getenv cloud-env-var) (map :cloud-env (list-projects)))
       (keep not-empty)
       distinct
       sort
       vec))

(defn write-project! [{:keys [name repos roles cloud-env]}]
  (let [f (project-file name)]
    (fs/create-dirs (projects-dir))
    (spit (str f) (with-out-str (pprint/pprint (cond-> {:repos (vec repos) :roles (vec roles)}
                                                 (not-empty cloud-env) (assoc :cloud-env cloud-env)))))
    (read-project name)))

(defn delete-project!
  "Remove the project. Its tasks are not touched — they keep their own repos and
   roles, snapshotted at scaffold time, and the index lists them again under
   `Tasks outside a project`. Deleting a project is forgetting a lineup, never
   deleting work."
  [name]
  (when-let [f (project-file name)]
    (fs/delete-if-exists f)))

;; ---------------------------------------------------------------- repo scan

(defn scan-repos
  "Every git checkout under a root, one and two levels deep. Two levels because
   the checkouts are not all direct children — ~/repos/mithra_ai/istari is one —
   and no deeper because level three is a repo's own src/ and node_modules/.
   A directory that is itself a checkout is never descended into, so a repo's
   vendored submodules do not show up as siblings of the repo."
  [root]
  (let [root (fs/expand-home (str root))]
    (if-not (fs/directory? root)
      []
      (->> (sort (fs/list-dir root))
           (filter fs/directory?)
           (mapcat (fn [d]
                     (if (task-lib/git-checkout? d)
                       [d]
                       (->> (sort (fs/list-dir d))
                            (filter fs/directory?)
                            (filter task-lib/git-checkout?)))))
           (map str)
           vec))))

(defn default-repo-roots []
  (if-let [env (not-empty (System/getenv "SWARMKHAZAD_REPO_ROOTS"))]
    (str/split env #":")
    [(str (fs/expand-home "~/repos"))]))

(defn available-repos []
  (vec (distinct (mapcat scan-repos (default-repo-roots)))))

;; ---------------------------------------------------------------- role cards

(def not-a-role
  "Two prompts on disk are not roles. `constitution.prompt` is the preamble
   every role's prompt carries, and `default.prompt` is what a role with no
   prompt of its own falls back to. Offering either as a role card invents a
   role that does no work."
  #{"constitution" "default"})

(def pipeline-order
  "The order roles run in, which is the order the cards are shown and therefore
   the order the swimlane's columns take. Filesystem order would be
   alphabetical, and `architect, brainstorm, cleaner, hardener…` is not a
   pipeline. A prompt not listed here is appended alphabetically."
  ["brainstorm" "specifier" "implement" "refactorer" "cleaner"
   "review" "architect" "hardener" "run" "qa"])

(defn stage-prompts
  "The stage prompts on disk — the roles a project can be built from. The name
   of the prompt is the name of the role, which is what makes a role card a
   choice rather than a free-text field."
  []
  (let [on-disk (->> (fs/glob (fs/path (fs/parent script-dir) "prompts") "*.prompt")
                     (map #(str/replace (fs/file-name %) #"\.prompt$" ""))
                     (remove not-a-role)
                     set)
        known (filterv on-disk pipeline-order)]
    (into known (sort (remove (set pipeline-order) on-disk)))))

(def opus "anthropic:claude-opus-5[1m]")

(def role-models
  "What each role runs on unless a project says otherwise.

   Not one default for everyone: the roles do different work and the cost of
   getting them wrong differs. The building roles get Opus because a quiet
   quality drop there ships wrong code; architect gets Fable for design work;
   specifier and review get a different VENDOR on purpose, so the diff is read
   by a model that did not write it and cannot agree with its own reasoning.

   `<vendor>:<model-id>` — the vendor picks the endpoint and the credential,
   the suffix names the exact model."
  {"implement"  opus
   "refactorer" opus
   "cleaner"    opus
   "hardener"   opus
   "run"        opus
   "qa"         opus
   "architect"  "anthropic:claude-fable-5-1"
   "specifier"  "glm"
   "review"     opus})

(defn default-model
  "A stage with no opinion recorded here runs on the operator's own login."
  [role]
  (get role-models role "anthropic"))

(def role-harness
  "Which launcher a role runs under unless a project says otherwise.

   cc_auto for everyone but specifier. Not for its permission mode — the swarm
   already states `bypassPermissions` for a bare `claude` role, so that part is
   a wash. What the lane adds is everything a role would otherwise have to be
   taught twice: `--effort xhigh`, an MCP set narrowed to the four servers a
   role actually uses (codegraph for a blast-radius read, chrome-devtools for a
   browser check) instead of the operator's whole `~/.claude.json`, the SSO wrap
   its Bash calls need, and its own brief — which `write-prompt!` now carries
   ahead of this task's rather than replacing it.

   The codegraph half of that was aspirational until `index-worktree!` existed:
   the server was loaded and reachable, but a task worktree sits outside its
   repo, so the index walk-up found nothing and the tool told the role to stop
   calling it. `prepare-worktrees!` now builds an index in each worktree and
   `constitution.prompt` names the tool, which is what makes this line true.

   The lane also starts the local model router (`lane_router_env`), which is the
   only way a `<vendor>/<model>` slug resolves at all. That matters for any role
   whose reviewers are left on their frontmatter vendors; it does NOT matter for
   a reviewer dispatched with an explicit `opus` or `sonnet`, which resolves on
   the ordinary path. So the router is the floor under the vendor case, not the
   reason for the default.

   specifier is the exception: one vendor, no panel, so cc_alt points straight
   at that vendor with no router in between.

   review reads on Opus rather than on a foreign vendor, which reverses an
   earlier default. Its second opinion no longer comes from its own model — it
   comes from `/code-review` and `/security-review`, which are built into the
   CLI, plus a panel picked from what the diff touches. A lead that can dispatch
   a panel is worth more than a lead that is itself one foreign vendor and reads
   everything alone."
  {"specifier" "cc_alt"})

(defn default-harness [role] (get role-harness role "cc_auto"))

(def default-roles
  (mapv (fn [r] {:role r :harness (default-harness r) :model (default-model r)})
        ["implement" "review" "run"]))

(defn valid-role-spec?
  "The role itself. Its checkout is not re-checked here — a role may only name
   one of the project's repos, and those are validated as a set."
  [{:keys [role harness model]}]
  (and (task-lib/valid-role? role)
       (contains? task-lib/known-agents harness)
       (contains? task-lib/known-vendors (first (task-lib/split-model model)))))

;; ---------------------------------------------------------------- roles file

(defn roles-text
  "The project's `roles` declaration, in the same grammar a hand-written one
   uses — the swarm never learns that a project exists.

   A role names no checkout. The project holds the repos and every role can
   work in all of them; which ones a role actually opens is decided by the
   `@repo` tags on its goal lines, task by task. Binding a role to one checkout
   here was one-project-per-repo thinking wearing a multi-repo hat: it settled,
   before the goal was written, which repo the work was allowed to touch."
  [{:keys [roles]}]
  (str task-lib/roles-grammar-comment
       (str/join "" (for [{:keys [role harness model]} roles]
                      (str role " " harness " task model=" model "\n")))))

(defn repos-text
  "The project's `repos` declaration — every checkout it holds."
  [{:keys [repos]}]
  (task-lib/repos-text repos))

;; ---------------------------------------------------------------- task <-> project

(defn task-project-file [ctx]
  (fs/path (:task-dir ctx) "project"))

(defn task-project [ctx]
  (let [f (task-project-file ctx)]
    (when (fs/regular-file? f)
      (not-empty (str/trim (slurp (str f)))))))

(defn cloud-env-for
  "Which self-hosted environment this task's @cloud bars dispatch to, most
   specific first: the environment variable, then the project the task belongs
   to. Nil when neither says.

   The project is the answer that survives. The variable was the first home for
   this and it is a poor one — an id nobody can remember, in a shell rc nobody
   reads, invisible on the page that claims to declare how a project runs. It
   stays as an override because a one-off dispatch at a different environment
   should not require editing the project."
  [ctx]
  (or (not-empty (or (System/getenv cloud-env-var) ""))
      (some-> (task-project ctx) read-project :cloud-env not-empty)))

(defn tasks-for [project-name]
  (->> (task-lib/list-task-ids)
       (filter #(= project-name (task-project (task-lib/task-ctx %))))
       vec))

;; ---------------------------------------------------------------- goal + metrics

(def section-names
  "Heading text → the section it opens. A brief is written by a person or by
   another agent, so the same three sections arrive under many names; the match
   is by edit distance against these, which absorbs the variants nobody thinks
   to list and the typos nobody means to make.

   `non-goals` is 5 edits from `goal` and would never match it anyway; what
   keeps the two apart is that both are spelled out here, not a tie-break. Add a
   name that collides with another section's and this stops being true — the
   list is the mechanism, so it is the thing to be careful with."
  {:goal ["goal" "goals" "goal lines" "objective" "objectives"]
   :not-goal ["not-goal" "not-goals" "not goal" "not goals" "non-goal" "non-goals"
              "nongoal" "nongoals" "out of scope" "out-of-scope" "non goals"]
   :bars ["quantitative bars" "quantitative" "quality bars" "metrics bars" "metrics"
          "bars" "acceptance" "acceptance criteria" "qualitative bars" "measures"]})

(defn edit-distance
  "Levenshtein, two rows. Short strings only — this compares headings."
  [a b]
  (let [b (vec b)]
    (last
     (reduce (fn [prev [i ca]]
               (reduce (fn [row [j cb]]
                         (conj row (min (inc (peek row))
                                        (inc (nth prev (inc j)))
                                        (+ (nth prev j) (if (= ca cb) 0 1)))))
                       [(inc i)]
                       (map-indexed vector b)))
             (vec (range (inc (count b))))
             (map-indexed vector a)))))

(defn bullet? [line]
  (boolean (re-find #"^\s*(?:[-*+•]\s|\[[ xX]?\]|\d+[.)]\s)" line)))

(defn heading
  "The section this line opens, or nil.

   A line carrying a bullet marker is an item, never a heading. `*` is a marker
   AND emphasis, so `* Goals` normalises to exactly `goals` and would otherwise
   open a section from inside a list; the dash forms are safe by distance alone.

   No length check is needed. An edit distance is at least the difference in
   length, so a line more than four characters longer than the name it is being
   compared to cannot match one — a sentence is out of reach for free."
  [line]
  (let [raw (str/trim line)]
    (when-not (or (str/blank? raw) (bullet? raw))
      (let [s (-> (str/lower-case raw)
                  (str/replace #"^#+\s*" "")
                  (str/replace #"[*_`:#]" "")
                  (str/replace #"\s+" " ")
                  str/trim)]
        (when (seq s)
          (first (for [[k names] section-names
                       n names
                       :when (<= (edit-distance s n) (max 1 (quot (count n) 4)))]
                   k)))))))

(defn strip-marker
  "One line of a section, with its bullet or checkbox marker removed and nothing
   else touched. The `- [ ] <role> — ` prefix the judge grades against is added
   when goal.md is written, so a line that already names its role keeps it."
  [line]
  (-> (str/trim line)
      (str/replace #"^[-*+•]\s*" "")
      (str/replace #"^\d+[.)]\s+" "")
      (str/replace #"^\[[ xX]?\]\s*" "")
      str/trim))

(defn table-row? [line]
  (let [raw (str/trim line)]
    (and (str/starts-with? raw "|") (str/ends-with? raw "|") (> (count raw) 1))))

(defn table-cells
  "The cells of a markdown table row, or nil when the row is structure rather
   than content: a separator (`|---|---|`) or a header naming the columns.

   Callers must ask `table-row?` first. Nil here means \"a table row with nothing
   in it\", which is not the same as \"not a table row\" — conflating the two put
   `| bar | measure |` into the bars as a bar of its own."
  [line]
  (when (table-row? line)
    (let [raw (str/trim line)
          cells (mapv str/trim (str/split (subs raw 1 (dec (count raw))) #"\|" -1))]
      (when-not (or (every? #(re-matches #":?-{2,}:?" %) cells)
                    (every? #(contains? #{"bar" "measure" "threshold" "command" "name" ""}
                                        (str/lower-case %))
                            cells))
        cells))))

(defn bar-line
  "One bars entry in the grammar run_evidence reads:
   `<name> — bar: <threshold> — measure: `<command>``.

   A brief usually carries the bars as a table, so a row becomes name + measure
   and, with three columns, the middle one is the threshold. A line already in
   the grammar passes through untouched — including one whose measure is prose,
   which stays in metrics.md as a bar a human runs rather than being dropped for
   not being a command."
  [line]
  (if (table-row? line)
    (when-let [[name a b] (table-cells line)]
      (when (seq name)
        (str name
             (when (and b (seq a)) (str " — bar: " a))
             (when-let [m (if b b a)] (when (seq m) (str " — measure: " m))))))
    (not-empty (strip-marker line))))

(defn parse-brief
  "Split one pasted block into the three things a task contributes.

   Sections are found by their heading, matched on edit distance, so `Not-goal`,
   `Non-goals` and a typo all land in the same place. Every non-blank line under
   a heading is one item.

   Text before the first heading is dropped. A paste opens with the conversation
   that produced it more often than not, and there is no honest way to tell that
   from a goal — the previous form took the whole block as goal lines, and one
   paste became twenty-five checkboxes, half of them fragments of a pod listing."
  [block]
  (let [result (reduce (fn [{:keys [section] :as acc} line]
                         (if-let [h (heading line)]
                           (assoc acc :section h :seen (conj (:seen acc) h))
                           (if (or (nil? section) (str/blank? line))
                             acc
                             (if-let [item (if (= :bars section)
                                             (bar-line line)
                                             (not-empty (strip-marker line)))]
                               (update acc section (fnil conj []) item)
                               acc))))
                       {:section nil :seen #{}}
                       (str/split-lines (or block "")))]
    (select-keys (merge {:goal [] :not-goal [] :bars []} result)
                 [:goal :not-goal :bars :seen])))

(def brief-system-prompt
  "The model's job is to SORT, not to write. Every added sentence is an
   acceptance criterion nobody agreed to, locked 444 the moment the swarm opens."
  (str
   "You restructure a task brief. You do not write one.\n\n"
   "You are given a block of text a person pasted — usually part of a chat, a "
   "ticket, or notes. Sort what is already in it into three sections and return "
   "markdown, nothing else.\n\n"
   "## Goal\n"
   "One line per outcome that must become true. Each line starts with the role "
   "that owns it and an em dash if the text says who does it; if it does not, "
   "leave the line without a prefix rather than inventing an owner. An outcome, "
   "not a step: `the pod goes 1/1 Ready`, not `edit the Dockerfile`.\n\n"
   "## Not-goal\n"
   "One line per thing explicitly out of scope, and per constraint that says "
   "what must NOT change.\n\n"
   "## Quantitative bars\n"
   "A markdown table, columns `bar | measure`. `bar` is what is checked, "
   "`measure` is how — put a shell command in backticks when the text gives "
   "one, and plain prose when it does not. Never invent a command.\n\n"
   "Rules:\n"
   "- Use the words already in the text. Reword only to make a fragment a "
   "sentence.\n"
   "- Drop narration, transcript, logs, and pasted output that state no "
   "requirement. A pod listing is evidence of the problem, not a goal.\n"
   "- An error string that names what must stop happening IS a bar. Put it in "
   "the table, not the goal.\n"
   "- Do not add a goal, a not-goal, or a bar the text does not ask for. If a "
   "section has nothing, write the heading and leave it empty.\n"
   "- Output only the three headings and their content. No preamble, no "
   "explanation, no code fence around the whole thing."))

(defn brief-md
  "The three sections back as one editable block — the same shape parse-brief
   reads, so what the reviewer sees is what gets parsed again on submit. There
   is no second path from the model's answer into goal.md."
  [{:keys [goal not-goal bars]}]
  (str "## Goal\n"
       (str/join "" (for [l goal] (str "- " l "\n")))
       "\n## Not-goal\n"
       (str/join "" (for [l not-goal] (str "- " l "\n")))
       "\n## Quantitative bars\n"
       (str/join "" (for [l bars] (str "- " l "\n")))))

(defn normalize-brief
  "One model call between the paste and the review. Returns the normalized
   markdown plus what it cost, or the deterministic parse re-rendered when the
   call fails — a brief that reaches the review page late is recoverable, one
   that never arrives is not.

   `ask-fn` is injected so the tests drive this without a model."
  [ask-fn block]
  (let [parsed (parse-brief block)
        fallback (fn [note]
                   {:markdown (brief-md parsed) :parsed parsed :note note :fell-back true})]
    (if (str/blank? (or block ""))
      (fallback "nothing to sort")
      (let [r (ask-fn {:prompt block
                       :system brief-system-prompt
                       :model (or (not-empty (or (System/getenv "SWARMKHAZAD_BRIEF_MODEL") "")) "sonnet")
                       :label "brief"})]
        (cond
          (:error r) (fallback (str "sorted by the parser alone — " (:error r)))
          (str/blank? (or (:text r) "")) (fallback "sorted by the parser alone — the model returned nothing")
          :else
          (let [md (str/trim (:text r))
                reparsed (parse-brief md)]
            (if (empty? (:goal reparsed))
              (fallback "sorted by the parser alone — the model's answer had no Goal section")
              {:markdown md :parsed reparsed :cost (:cost r) :model (:model r)})))))))

(defn goal-md
  "goal.md from the two things a task actually contributes. Goal lines carry the
   `- [ ] <role> — ` prefix the judge grades against, so a line typed without a
   role prefix is left alone rather than guessed at."
  [task-id goal-lines not-goal-lines]
  (str "# " task-id "\n"
       "Opened from the portal · " (java.time.LocalDate/now) "\n\n"
       "## Goal\n"
       (str/join "" (for [l goal-lines] (str "- [ ] " l "\n")))
       "\n## Not-goal\n"
       (str/join "" (for [l not-goal-lines] (str "- " l "\n")))))

(defn metrics-md [task-id bar-lines]
  (str "# " task-id " — bars\n\n"
       "## Quantitative\n"
       (str/join "" (for [l bar-lines] (str "- " l "\n")))))
