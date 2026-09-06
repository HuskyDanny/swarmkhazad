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
;;    :roles [{:role "implement" :harness "claude" :model "anthropic"} ...]}
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

(defn write-project! [{:keys [name repos roles]}]
  (let [f (project-file name)]
    (fs/create-dirs (projects-dir))
    (spit (str f) (with-out-str (pprint/pprint {:repos (vec repos) :roles (vec roles)})))
    (read-project name)))

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

(defn stage-prompts
  "The stage prompts on disk — the roles a project can be built from. The name
   of the prompt is the name of the role, which is what makes a role card a
   choice rather than a free-text field."
  []
  (->> (fs/glob (fs/path (fs/parent script-dir) "prompts") "*.prompt")
       (map #(str/replace (fs/file-name %) #"\.prompt$" ""))
       sort
       vec))

(def default-roles
  [{:role "implement" :harness "claude" :model "anthropic"}
   {:role "review" :harness "claude" :model "anthropic"}
   {:role "run" :harness "claude" :model "anthropic"}])

(defn valid-role-spec?
  "The role itself. Its checkout is not re-checked here — a role may only name
   one of the project's repos, and those are validated as a set."
  [{:keys [role harness model]}]
  (and (task-lib/valid-role? role)
       (contains? task-lib/known-agents harness)
       (contains? task-lib/known-vendors model)))

;; ---------------------------------------------------------------- roles file

(defn roles-text
  "The project's `roles` declaration, in the same grammar a hand-written one
   uses — the swarm never learns that a project exists.

   Each role names its OWN checkout. An earlier version bound the whole lineup
   to the first repo and invented an `implement.<repo-name>` role for the rest,
   which is one-project-per-repo thinking wearing a multi-repo hat: it decided
   for you which checkout the swarm actually worked in. A role card carries a
   checkout, and this writes what the card says."
  [{:keys [repos roles]}]
  (let [fallback (or (first repos) "none")]
    (str task-lib/roles-grammar-comment
         (str/join "" (for [{:keys [role harness model repo]} roles]
                        (str role " " harness " " (or repo fallback) " task model=" model "\n"))))))

(defn unused-repos
  "Checkouts the project clones but no role works in. Not an error — a checkout
   can be there to be read — but the portal says so, because one nobody opens is
   usually a role that was meant to be picked and was not."
  [{:keys [repos roles]}]
  (let [used (set (keep :repo roles))]
    (vec (remove used repos))))

;; ---------------------------------------------------------------- task <-> project

(defn task-project-file [ctx]
  (fs/path (:task-dir ctx) "project"))

(defn task-project [ctx]
  (let [f (task-project-file ctx)]
    (when (fs/regular-file? f)
      (not-empty (str/trim (slurp (str f)))))))

(defn tasks-for [project-name]
  (->> (task-lib/list-task-ids)
       (filter #(= project-name (task-project (task-lib/task-ctx %))))
       vec))

;; ---------------------------------------------------------------- goal + metrics

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
