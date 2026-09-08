#!/usr/bin/env bb

;; swarmkhazad — the CLI. One task folder in, one swarm out. See README.md.

(ns swarmkhazad
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def script-dir (fs/parent (fs/absolutize *file*)))
(load-file (str (fs/path script-dir "swarm_lib.bb")))

(def usage-text
  (str "Usage:\n"
       "  swarmkhazad new <task-id> [--repo <path>]... [--linear <KEY>]\n"
       "                                                 scaffold goal.md, metrics.md, roles\n"
       "  swarmkhazad prepare <task-id>                  layout, worktrees, mail dirs, sessions.tsv\n"
       "  swarmkhazad open <task-id>                     prepare, then spawn every declared role\n"
       "  swarmkhazad open --linear <KEY> [--repo <path>]...\n"
       "                                                 scaffold from a Linear issue, then open\n"
       "  swarmkhazad close <task-id> [--reclaim] [--force]\n"
       "                                                 archive panes, stop the daemon, kill the tmux server;\n"
       "                                                 --reclaim also cleans and removes the worktrees and\n"
       "                                                 deletes the task branch (kept if not on origin)\n"
       "  swarmkhazad smoke <task-id>                    each role: launch via its shim, read goal.md, send one note, exit\n"
       "  swarmkhazad paths <task-id>                    print the path map\n"
       "  swarmkhazad portal [--port <n>]                serve the portal on 127.0.0.1 (default 8765)\n"
       "  swarmkhazad telemetry <task-id>                cost, tokens and sessions per role, from VictoriaMetrics\n"
       "  swarmkhazad summary <task-id>                  ask whether the work is ready to merge, for its goals\n"
       "  swarmkhazad ship <task-id> [--yes]             push each repo's branch and open a draft PR, in the summary's merge order\n"
       "  swarmkhazad reap [--force]                     prune stale worktrees and delete orphaned sk/* branches from your checkouts\n"))

(defn usage! []
  ;; flush before exit: `print` leaves the text in the buffer and System/exit
  ;; discards it, so `swarmkhazad` with no arguments printed nothing at all.
  (binding [*out* *err*] (print usage-text) (flush))
  (System/exit 1))

(defn goal-template [task-id]
  (str "# " task-id " — <what this task is, one line>\n"
       "Opened by <who> · " (java.time.LocalDate/now) "\n\n"
       "## Goal\n"
       ;; The grammar goes in an HTML comment, not in a live checkbox: a
       ;; placeholder `@<repo>` inside a real goal line reads as a tag naming a
       ;; repo the task does not have, and prepare would refuse the task it had
       ;; just scaffolded.
       "<!-- one line per outcome: `- [ ] <role> @<repo> — <outcome>`.\n"
       "     The role and the @repo tags are both optional; a line with neither\n"
       "     belongs to every role and every repo. -->\n"
       "- [ ] <the outcome, one line>\n\n"
       "## Not-goal\n- <deliberately not doing X — why>\n\n"
       "## Hints\n- <absolute path> — why it matters\n"))

(defn metrics-template [task-id]
  (str "# " task-id " — bars\n\n"
       "## Quantitative\n- <metric> — bar: <threshold> — measure: `<command>`\n\n"
       "## Qualitative\n- <property> — bar: <what passing looks like> — judged by: Allen\n"))

(defn flag-values [args flag]
  (->> (partition 2 1 (cons nil args))
       (keep (fn [[f value]] (when (= f flag) value)))))

(defn new!
  "Scaffold a task folder. With --linear <KEY> the goal comes from the issue
   instead of the template, and `roles` is one implement role."
  [task-id args]
  (let [repos (flag-values args "--repo")
        issue-key (first (flag-values args "--linear"))
        ctx (task-lib/task-ctx task-id)]
    (when (fs/exists? (:task-dir ctx))
      (task-lib/fail! (str "task already exists: " (:task-dir ctx))))
    ;; Fetch before creating anything: a fetch that fails must leave no folder
    ;; behind, or the retry hits "task already exists" on a template goal.
    (let [issue (when issue-key
                  (load-file (str (fs/path script-dir "linear_intake.bb")))
                  ((resolve 'linear-intake/fetch-issue) issue-key))]
      (fs/create-dirs (:task-dir ctx))
      (spit (str (:metrics-file ctx)) (metrics-template task-id))
      (if issue
        (do ((resolve 'linear-intake/write-from-issue!) ctx issue repos)
            (println (str "linear: " (:identifier issue) " " (:title issue))))
        (do (spit (str (:goal-file ctx)) (goal-template task-id))
            (spit (str (:roles-file ctx)) (task-lib/roles-template repos))
            (spit (str (:repos-file ctx)) (task-lib/repos-text repos))))
      (println (str (:task-dir ctx))))))

(defn prepare! [task-id]
  (let [result (task-lib/prepare! (task-lib/task-ctx task-id))]
    (println "task:" (:task-dir result))
    (doseq [{:keys [name source path branch start]} (:repos result)]
      (println (str "repo: " name " " path " " branch " @ " start " from " source)))
    (doseq [{:keys [session harness receive-mode model worktree-path]} (:sessions result)]
      (println (str "session: " session " " harness " " receive-mode " model=" model " " worktree-path)))))

(defn paths! [task-id]
  (doseq [[k v] (sort-by (comp str key) (task-lib/task-ctx task-id))]
    (println (name k) (str v))))

(defn open! [task-id]
  (let [ctx (swarm-lib/open! task-id)]
    (println "swarm open:" task-id)
    (println "tmux socket:" (:tmux-socket ctx))
    (doseq [{:keys [session harness model worktree-path]} (:sessions ctx)]
      (println (str "  " (task-lib/session-name session) "  " harness " model=" model "  " worktree-path)))
    (println (str "attach: tmux -S " (:tmux-socket ctx) " attach -t sk-<role>_<repo>"))))

(defn open-cmd!
  "`open <task-id>`, or `open --linear <KEY> [--repo <path>]...`, which scaffolds
   the task from the issue first — the task id is the key, lowercased, unless one
   is given. An existing task folder is opened as it stands: intake never
   overwrites a goal someone has already edited."
  [args]
  (let [issue-key (first (flag-values args "--linear"))
        ;; A flag's VALUE is not the task id. Dropping every `--flag value` pair
        ;; first is the difference between `open --linear MITH-3437` scaffolding
        ;; `mith-3437` and scaffolding a task literally named `MITH-3437`.
        positional (loop [[a & more :as all] (vec args) out []]
                     (cond
                       (empty? all) out
                       (str/starts-with? a "-") (recur (rest more) out)
                       :else (recur more (conj out a))))
        named (first positional)
        task-id (or named (when issue-key
                            (load-file (str (fs/path script-dir "linear_intake.bb")))
                            ((resolve 'linear-intake/task-id-for) issue-key)))]
    (when-not task-id (usage!))
    (when (and issue-key (not (fs/exists? (:task-dir (task-lib/task-ctx task-id)))))
      (new! task-id args))
    (open! task-id)))

(defn close! [task-id args]
  (swarm-lib/close! task-id
                    (boolean (some #{"--reclaim"} args))
                    (boolean (some #{"--force"} args)))
  (println "swarm closed:" task-id))

(defn smoke! [task-id]
  (let [results (swarm-lib/smoke! task-id)]
    (doseq [{:keys [role harness vendor ok exit seconds models expected note cost turns detail]} results]
      (println (str (if ok "OK   " "FAIL ") role "  " harness " model=" vendor
                    "  exit=" exit "  " seconds "s  note=" (if note "sent" "none")
                    (when (seq models) (str "  used=" (str/join "," models)))
                    (when (and expected (not (some #{expected} models))) (str "  expected=" expected))
                    (when cost (format "  cost=$%.4f" (double cost)))
                    (when turns (str "  turns=" turns))))
      (when detail (println (str "     " (str/replace detail #"\n" "\n     ")))))
    (System/exit (if (every? :ok results) 0 1))))

(defn -main [& args]
  (try
    (case (first args)
      "new" (if (second args) (new! (second args) (drop 2 args)) (usage!))
      "prepare" (if (second args) (prepare! (second args)) (usage!))
      "open" (open-cmd! (rest args))
      "close" (if (second args) (close! (second args) args) (usage!))
      "smoke" (if (second args) (smoke! (second args)) (usage!))
      "paths" (if (second args) (paths! (second args)) (usage!))
      "portal" (do (load-file (str (fs/path script-dir "portal.bb")))
                   (apply (resolve 'portal/-main) (rest args)))
      "telemetry" (do (load-file (str (fs/path script-dir "telemetry.bb")))
                      (apply (resolve 'telemetry/-main) (rest args)))
      "summary" (do (load-file (str (fs/path script-dir "summary.bb")))
                    (apply (resolve 'summary/-main) (rest args)))
      "ship" (do (load-file (str (fs/path script-dir "ship.bb")))
                 (apply (resolve 'ship/-main) (rest args)))
      "reap" (do (load-file (str (fs/path script-dir "reap.bb")))
                 (apply (resolve 'reap/-main) (rest args)))
      (usage!))
    (catch clojure.lang.ExceptionInfo e
      (task-lib/fail! (str "swarmkhazad: " (ex-message e))))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
