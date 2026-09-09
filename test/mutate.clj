(ns mutate
  "Mutation runner: break one guard at a time and require the named test to
   notice.

   A green suite says the tests pass. It does not say they would fail if the
   code were wrong, and two mechanisms make a whole matrix vacuous while every
   row still reads correct — a sibling component that raises first, and a
   harness that never passed the arguments. Both were hit in this repo. So each
   entry in test/mutants.edn names the test that must fail, and this asserts
   that exact test among the failures rather than counting exit codes.

   Usage:
     bb mutate                      every mutant
     bb mutate contract schema      only mutants whose :ns matches one of these
     bb mutate --changed <ref>      only mutants a diff against <ref> can affect
     bb mutate --list               print the table, run nothing

   Restores every file from an in-memory copy in a `finally`, so an
   interrupted run does not leave a mutant on disk. A hard kill is the one
   case that escapes it, and that is why this refuses to start on a dirty
   tree: `original` is whatever the file says right now, so a stranded mutant
   from an earlier run becomes the text the next restore writes back. That
   happened — one run was killed mid-mutant, the next slurped the corrupted
   file as its baseline, and the mutant AFTER it reported `anchor-gone`
   because the code it anchored to had already been replaced. The report was
   correct and the cause was the tree.

   `git status --porcelain`, not `git diff` — `git diff` compares the worktree
   to the INDEX, so a stranded mutant that has been `git add`ed reads as
   clean. That happened too, in the same hour."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def table-file "test/mutants.edn")

(defn table [] (read-string (slurp table-file)))

(defn wanted
  "The mutants to run: all of them, or those whose :ns matches an argument.
   Matching is substring, the same rule `bb test` uses for a namespace."
  [rows args]
  (if (empty? args)
    rows
    (filter (fn [{:keys [ns]}] (some #(str/includes? ns %) args)) rows)))

(defn deftest-file
  "The test file holding a deftest, found by name. Derived rather than a field
   in the table: one more column to keep in sync is one more thing to get
   wrong, and the deftest name is already there.

   Read in-process rather than shelled to `rg`. ripgrep is not guaranteed on a
   GitHub macOS runner, and a missing binary here would fail SILENTLY —
   `deftest-file` returns nil, `--changed` quietly stops noticing that a test
   was weakened, and the job goes green having checked fewer mutants than it
   reported. That is the failure mode this runner exists to catch, so it does
   not get to live inside it.

   Repo-relative, because that is what `git diff --name-only` emits and the two
   are compared directly."
  [killed-by]
  (let [needle (str "(deftest " killed-by)
        root (fs/cwd)]
    (->> (fs/glob (fs/path root "test") "**.clj")
         sort
         (some #(when (str/includes? (slurp (str %)) needle)
                  (str (fs/relativize root %)))))))

(defn changed-mutants
  "The mutants a diff against `ref` could affect: the ones whose script
   changed, plus the ones whose TEST changed — a guard can also be lost by
   weakening the test that watches it, and that touches no script at all."
  [rows ref]
  (let [r (process/sh {:continue true} "git" "diff" "--name-only" (str ref "...HEAD"))
        changed (set (remove str/blank? (str/split-lines (or (:out r) ""))))]
    ;; Hard, not a warning. This printed to stderr and carried on with an
    ;; empty set, which selected no mutants, which reported "nothing to check"
    ;; and exited 0. RAN against a ref that does not resolve: the warning
    ;; printed and zero mutants were selected, exit 0. Unknown scope reported
    ;; as empty scope, and empty scope reported as success.
    ;;
    ;; A gate that cannot work out its own scope has not passed. It has failed
    ;; to run, and those are different answers.
    ;;
    ;; This is a latent bug, not one CI hit. It was written up as a CI failure
    ;; on the strength of an eleven-second green job, which was wrong: the job
    ;; had resolved origin/main, selected the three mutants the diff implied,
    ;; and killed all three. Eleven seconds is simply what that costs —
    ;; `bb test schema` is 0.09s because it globs and slurps and spawns
    ;; nothing, where `bb test contract` is 36s because it starts processes.
    ;; Reasoning "too fast to be real" from the timing of a different suite is
    ;; the same shape of error as the vacuous gates this file exists to find.
    (when-not (zero? (:exit r))
      (println (str "cannot diff against " ref " — so which mutants this branch"
                    " affects is unknown, and unknown is not empty:"))
      (println (str "  " (str/trim (str (:err r)))))
      (println "Fetch the ref first, e.g.")
      (println (str "  git fetch --no-tags --force origin +refs/heads/<base>:refs/remotes/origin/<base>"))
      (System/exit 1))
    ;; A :killed-by that names no deftest is a table error, and a silent one:
    ;; it would just narrow the selection. Say it and stop.
    (when-let [orphans (seq (remove #(deftest-file (:killed-by %)) rows))]
      (println "these entries name a deftest that does not exist:")
      (doseq [{:keys [file killed-by]} orphans]
        (println (str "  " killed-by "  (" file ")")))
      (println "Rename the entry's :killed-by to the test that actually guards it,"
               "or delete the entry.")
      (System/exit 1))
    (filter (fn [{:keys [file killed-by]}]
              (or (contains? changed file)
                  (when-let [tf (deftest-file killed-by)] (contains? changed tf))))
            rows)))

(defn run-ns
  "`bb test <ns>` — returns {:exit :out}. The suite's own runner exits
   non-zero on any failure (test/run_tests.clj)."
  [ns]
  (let [r (process/sh {:continue true} "bb" "test" ns)]
    {:exit (:exit r) :out (str (:out r) (:err r))}))

(defn check-one
  "Apply one mutant, run its namespace, restore, and say what happened.

   Three outcomes, and only one is a pass:
     :killed    the suite failed AND :killed-by is among the failures
     :survived  the suite passed — the guard can be removed unnoticed
     :wrong-test the suite failed, but not in the test that claims to guard it"
  [{:keys [file ns old new killed-by] :as m}]
  (let [path (str (fs/path (fs/cwd) file))
        original (slurp path)
        hits (count (re-seq (re-pattern (java.util.regex.Pattern/quote old)) original))]
    (cond
      (zero? hits)
      (assoc m :outcome :anchor-gone
             :detail (str "the anchor is not in " file " any more — the guard moved, "
                          "so this entry no longer describes it. Re-read the code and "
                          "update :old, or delete the entry if the guard is gone."))
      (> hits 1)
      (assoc m :outcome :anchor-ambiguous
             :detail (str "the anchor appears " hits " times in " file
                          " — extend :old until it names one site."))
      :else
      (try
        (spit path (str/replace original old new))
        (let [{:keys [exit out]} (run-ns ns)
              failed (set (map second (re-seq #"(?:FAIL|ERROR) in \(([^)]+)\)" out)))]
          (cond
            (zero? exit)
            (assoc m :outcome :survived
                   :detail (str "`bb test " ns "` still passed with the guard removed"))
            (contains? failed killed-by)
            (assoc m :outcome :killed :detail (str "killed by " killed-by))
            :else
            (assoc m :outcome :wrong-test
                   :detail (str "the suite failed, but not in " killed-by ". Failed: "
                                (if (seq failed) (str/join ", " (sort failed)) "(no test named)")
                                ". A mutant that dies in a different test proves that test, not this one."))))
        (finally (spit path original))))))

(defn dirty-tree
  "The porcelain lines, or nil when the tree is clean of tracked changes.
   Untracked files are ignored: they cannot be a stranded mutant, since every
   file this touches is one it found on disk and will write back."
  []
  (let [r (process/sh {:continue true} "git" "status" "--porcelain")
        lines (remove #(str/starts-with? % "??")
                      (remove str/blank? (str/split-lines (or (:out r) ""))))]
    (seq lines)))

(defn -main [args]
  (let [rows (table)]
    (if (some #{"--list"} args)
      (do (println (format "%-38s %-14s %s" "killed-by" "ns" "why"))
          (doseq [{:keys [killed-by ns why]} rows]
            (println (format "%-38s %-14s %s" killed-by ns why)))
          (println (count rows) "mutants"))
      (do
       (when-let [dirt (dirty-tree)]
         (println "the tree has tracked changes, and this runner would bake them in:")
         (doseq [l dirt] (println (str "  " l)))
         (println)
         (println "Each mutant restores the file to whatever it said BEFORE the mutant,")
         (println "so an edit already present becomes part of every restore — and the")
         (println "mutant after it reports anchor-gone against code that is no longer")
         (println "there. Commit or stash first. If a previous run was killed hard:")
         (println "  git checkout HEAD -- <file>")
         (System/exit 1))
       (let [ref (second (drop-while #(not= "--changed" %) args))
            todo (if ref
                   (changed-mutants rows ref)
                   (wanted rows (remove #(str/starts-with? % "--") args)))]
        (when (and ref (empty? todo))
          (println "no mutant covers anything this diff touched — nothing to check")
          (System/exit 0))
        (when (empty? todo)
          (println "no mutants match" (pr-str args) "— `bb mutate --list` shows the table")
          (System/exit 1))
        (println (count todo) "mutants"
                 (cond ref (str "affected by the diff against " ref)
                       (seq args) (str "matching " (pr-str args))
                       :else ""))
        (let [results (doall (for [m todo]
                               (let [r (check-one m)]
                                 (println (format "  %-9s %-38s %s"
                                                  (name (:outcome r)) (:killed-by r)
                                                  (if (= :killed (:outcome r)) "" (:detail r))))
                                 r)))
              bad (remove #(= :killed (:outcome %)) results)]
          (println)
          (println (- (count results) (count bad)) "killed," (count bad) "not")
          (when (seq bad)
            (doseq [{:keys [file outcome detail]} bad]
              (println (str "  " file " — " (name outcome) ": " detail))))
          (System/exit (if (seq bad) 1 0))))))))
