(ns swarmkhazad.judge-test
  "goal_judge.bb as a Stop hook, and the git_handoff gate it feeds. The judge
   model is a stub `claude` on PATH that returns whatever verdict the test asks
   for (SWARMKHAZAD_STUB_VERDICT), so the decision logic, the files it writes
   and the gate are pinned without a network call."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (str (fs/cwd)))
(def scripts (str (fs/path repo-root "scripts")))
(def cli (str (fs/path scripts "swarmkhazad.bb")))
(def stub (str (fs/path repo-root "test" "fixtures" "stub-claude.sh")))

(defn run [{:keys [dir env ok? in]} & args]
  (let [result (apply process/sh (concat [(cond-> {:continue true :dir (str (or dir repo-root)) :extra-env (or env {})}
                                            in (assoc :in in))]
                                         args))]
    (when (and (not (false? ok?)) (not (zero? (:exit result))))
      (throw (ex-info (str "command failed: " (str/join " " args) "\n" (:out result) (:err result)) result)))
    result))

(defn git [dir & args]
  (str/trim (:out (apply run {:dir dir} "git" args))))

(defn write! [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn make-source-repo! [dir]
  (fs/create-dirs dir)
  (git dir "init" "-q" "-b" "main")
  (git dir "config" "user.email" "t@example.com")
  (git dir "config" "user.name" "T")
  (write! (fs/path dir "README.md") "one\n")
  (git dir "add" ".")
  (git dir "commit" "-q" "-m" "one")
  (git dir "remote" "add" "origin" "https://example.invalid/acme/fixture.git")
  (git dir "update-ref" "refs/remotes/origin/main" (git dir "rev-parse" "HEAD")))

(defn with-task [f]
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-judge."})
        home (str (fs/path sandbox "home"))
        src (str (fs/path sandbox "src" "fixture"))
        stubdir (str (fs/path sandbox "stubbin"))
        id "t-judge"
        base-env {"SWARMKHAZAD_HOME" home "SWARMKHAZAD_TASK_ID" id
                  "PATH" (str stubdir ":" (System/getenv "PATH"))}]
    (try
      (make-source-repo! src)
      (fs/create-dirs stubdir)
      (fs/copy stub (fs/path stubdir "claude"))
      (fs/set-posix-file-permissions (fs/path stubdir "claude") "rwxr-xr-x")
      (run {:env base-env} cli "new" id "--repo" src)
      (let [dir (fs/path home "tasks" id)]
        (spit (str (fs/path dir "roles")) (str "a claude " src " task\nb claude " src " task\nc grok none\n"))
        (spit (str (fs/path dir "goal.md")) "# t-judge\n\n## Goal\n- [ ] a — GOAL-X\n\n## Not-goal\n- none\n\n## Hints\n- none\n")
        (run {:env base-env} cli "prepare" id)
        (letfn [(stop! [role verdict & [{:keys [session message]}]]
                  (run {:dir (str (fs/path dir "worktrees" (if (= role "c") "" role)))
                        :env (cond-> (assoc base-env "SWARMFORGE_ROLE" role "SWARMKHAZAD_TASK_DIR" (str dir))
                               verdict (assoc "SWARMKHAZAD_STUB_VERDICT" verdict))
                        :in (json/generate-string {"hook_event_name" "Stop" "session_id" (or session "s1")
                                                   "stop_hook_active" false "last_assistant_message" (or message "done")})
                        :ok? false}
                       "bb" (str (fs/path scripts "goal_judge.bb"))))
                (helper [role script & args]
                  (apply run {:dir (str (fs/path dir "worktrees" role))
                              :env (assoc base-env "SWARMFORGE_ROLE" role "SWARMKHAZAD_TASK_DIR" (str dir))
                              :ok? false}
                         "bb" (str (fs/path scripts script)) args))]
          (f {:dir dir :env base-env :stop! stop! :helper helper :src src})))
      (finally
        (fs/delete-tree sandbox)))))

(defn decision [r]
  (when-not (str/blank? (:out r)) (json/parse-string (:out r) true)))

(defn verdict-file [dir role]
  (json/parse-string (slurp (str (fs/path dir "state" "judge" (str role ".json")))) true))

(deftest unmet-blocks-the-stop-writes-the-verdict-and-escalates-once-per-distinct-verdict
  (with-task
    (fn [{:keys [dir stop!]}]
      (let [r (stop! "a" "{\"met\":false,\"unmet\":[\"GOAL-X\",\"tests\"]}")]
        (is (zero? (:exit r)) (:err r))
        (is (= "block" (:decision (decision r))))
        (is (str/includes? (:reason (decision r)) "goals unmet: GOAL-X; tests"))
        (is (str/includes? (:reason (decision r)) "escalation.md")))
      (let [v (verdict-file dir "a")]
        (is (false? (:met v)))
        (is (= ["GOAL-X" "tests"] (:unmet v)))
        (is (= "block" (:decision v)))
        (is (= "claude-haiku-stub" (:model v)))
        (is (= "s1" (:session v))))
      (testing "the judge got goal.md and the working state, thinking off, bounded, schema-forced"
        (let [raw (slurp (str (fs/path dir "tmp" "judge-a.argv")))
              argv (str/split-lines raw)
              prompt (subs raw (str/index-of raw "<goals_md>"))]
          (is (some #{"--json-schema"} argv))
          (is (= "haiku" (second (drop-while #(not= "--model" %) argv))))
          (is (some #{"--no-session-persistence"} argv))
          (is (str/includes? prompt "<goals_md>"))
          (is (str/includes? prompt "GOAL-X"))
          (is (str/includes? prompt "### git status"))
          (is (str/includes? prompt "(not written)") "the missing draft is reported, not invented")
          (is (str/includes? prompt "### role's last message\ndone"))))
      (testing "escalation.md got one line naming the unmet items"
        (let [esc (slurp (str (fs/path dir "escalation.md")))]
          (is (= 1 (count (re-seq #"goal judge says unmet" esc))))
          (is (str/includes? esc "**a: goal judge says unmet — GOAL-X; tests**"))))
      (testing "the same verdict again does not add a second line; a different one does"
        (stop! "a" "{\"met\":false,\"unmet\":[\"tests\",\"GOAL-X\"]}")
        (is (= 1 (count (re-seq #"goal judge says unmet" (slurp (str (fs/path dir "escalation.md")))))))
        (stop! "a" "{\"met\":false,\"unmet\":[\"tests\"]}")
        (is (= 2 (count (re-seq #"goal judge says unmet" (slurp (str (fs/path dir "escalation.md"))))))))
      (testing "the block budget: three blocks in one session, then the stop is allowed with the gap named"
        (let [r (stop! "a" "{\"met\":false,\"unmet\":[\"tests\"]}")]
          (is (nil? (decision r)) (str "fourth stop allowed: " (:out r)))
          (is (str/includes? (:reason (verdict-file dir "a")) "max blocks reached; unmet: tests")))
        (let [r (stop! "a" "{\"met\":false,\"unmet\":[\"tests\"]}" {:session "s2"})]
          (is (= "block" (:decision (decision r))) "a new session has a fresh budget"))))))

(deftest met-without-a-handoff-blocks-once-to-ask-for-it-and-met-with-a-handoff-allows
  (with-task
    (fn [{:keys [dir stop! helper]}]
      (write! (fs/path dir "worktrees" "a" "x.txt") "x\n")
      (git (fs/path dir "worktrees" "a") "add" "x.txt")
      (git (fs/path dir "worktrees" "a") "commit" "-q" "-m" "x")
      (let [r (stop! "a" nil)]
        (is (= "block" (:decision (decision r))))
        (is (str/includes? (:reason (decision r)) "no git_handoff for your current HEAD"))
        (is (true? (:met (verdict-file dir "a"))))
        (is (= "" (slurp (str (fs/path dir "escalation.md")))) "met writes no escalation"))
      (testing "with the verdict met, the gate lets the git_handoff through"
        (write! (fs/path dir "tmp" "g.txt") "type: git_handoff\nto: b\npriority: 50\n")
        (let [r (helper "a" "swarm_handoff.bb" (str (fs/path dir "tmp" "g.txt")))]
          (is (zero? (:exit r)) (:err r))))
      (testing "now the stop is allowed"
        (let [r (stop! "a" nil)]
          (is (nil? (decision r)) (:out r))
          (is (= "allow" (:decision (verdict-file dir "a"))))))
      (testing "a handoff for an older commit does not count: new work, new handoff"
        (write! (fs/path dir "worktrees" "a" "x2.txt") "x2\n")
        (git (fs/path dir "worktrees" "a") "add" "x2.txt")
        (git (fs/path dir "worktrees" "a") "commit" "-q" "-m" "x2")
        (let [r (stop! "a" nil {:session "s-later"})]
          (is (= "block" (:decision (decision r))))
          (is (str/includes? (:reason (decision r)) "no git_handoff for your current HEAD"))))
      (testing "a repo-less role with a met verdict is simply allowed"
        (let [r (stop! "c" nil)]
          (is (nil? (decision r)) (:out r)))))))

(deftest the-gate-refuses-a-git-handoff-without-a-met-verdict
  (with-task
    (fn [{:keys [dir stop! helper]}]
      (write! (fs/path dir "worktrees" "a" "x.txt") "x\n")
      (git (fs/path dir "worktrees" "a") "add" "x.txt")
      (git (fs/path dir "worktrees" "a") "commit" "-q" "-m" "x")
      (write! (fs/path dir "tmp" "g.txt") "type: git_handoff\nto: b\npriority: 50\n")
      (testing "no verdict yet"
        (let [r (helper "a" "swarm_handoff.bb" (str (fs/path dir "tmp" "g.txt")))]
          (is (= 1 (:exit r)))
          (is (str/includes? (:err r) "No goal-judge verdict yet"))))
      (testing "unmet verdict"
        (stop! "a" "{\"met\":false,\"unmet\":[\"GOAL-X\"]}")
        (let [r (helper "a" "swarm_handoff.bb" (str (fs/path dir "tmp" "g.txt")))]
          (is (= 1 (:exit r)))
          (is (str/includes? (:err r) "Goal judge says unmet for role a: GOAL-X"))
          (is (fs/exists? (fs/path dir "tmp" "g.txt")) "the draft is left for later")))
      (testing "a note is never gated"
        (write! (fs/path dir "tmp" "n.txt") "type: note\nto: b\npriority: 50\nmessage: hi\n")
        (is (zero? (:exit (helper "a" "swarm_handoff.bb" (str (fs/path dir "tmp" "n.txt")))))))
      (testing "a role on a harness without hooks is not gated"
        (let [roles (slurp (str (fs/path dir "state" "roles.tsv")))
              with-grok (str/replace roles #"(?m)^b\tclaude" "b\tgrok")]
          (is (not= roles with-grok) "the roles.tsv row for b was rewritten to grok")
          (spit (str (fs/path dir "state" "roles.tsv")) with-grok)
          (write! (fs/path dir "worktrees" "b" "y.txt") "y\n")
          (git (fs/path dir "worktrees" "b") "add" "y.txt")
          (git (fs/path dir "worktrees" "b") "commit" "-q" "-m" "y")
          (write! (fs/path dir "tmp" "gb.txt") "type: git_handoff\nto: a\npriority: 50\n")
          (let [r (helper "b" "swarm_handoff.bb" (str (fs/path dir "tmp" "gb.txt")))]
            (is (zero? (:exit r)) (:err r))))))))

(deftest a-down-judge-is-never-a-silent-pass
  (with-task
    (fn [{:keys [dir stop! helper]}]
      (doseq [[label mode] [["judge process fails" "down"] ["judge returns no JSON" "garbage"]]]
        (fs/delete-if-exists (fs/path dir "state" "judge" "a.json"))
        (let [r (stop! "a" mode)]
          (is (zero? (:exit r)) (str label ": " (:err r)))
          (is (nil? (decision r)) (str label ": the stop is allowed — an infra fault must not wedge the role"))
          (let [v (verdict-file dir "a")]
            (is (false? (:met v)) label)
            (is (= ["judge_unavailable"] (:unmet v)) label)
            (is (true? (:down v)) label)
            (is (str/includes? (:reason v) "judge unavailable") label))))
      (testing "escalation.md says the judge was down"
        (is (str/includes? (slurp (str (fs/path dir "escalation.md"))) "judge unavailable")))
      (testing "the gate still refuses the git_handoff"
        (write! (fs/path dir "worktrees" "a" "x.txt") "x\n")
        (git (fs/path dir "worktrees" "a") "add" "x.txt")
        (git (fs/path dir "worktrees" "a") "commit" "-q" "-m" "x")
        (write! (fs/path dir "tmp" "g.txt") "type: git_handoff\nto: b\npriority: 50\n")
        (let [r (helper "a" "swarm_handoff.bb" (str (fs/path dir "tmp" "g.txt")))]
          (is (= 1 (:exit r)))
          (is (str/includes? (:err r) "judge_unavailable")))))))

(deftest the-hook-is-inert-outside-a-role-and-on-other-events
  (let [r (process/sh {:continue true :in "{\"hook_event_name\":\"Stop\"}" :extra-env {"SWARMKHAZAD_TASK_DIR" "" "SWARMFORGE_ROLE" ""}}
                      "bb" (str (fs/path scripts "goal_judge.bb")))]
    (is (zero? (:exit r)))
    (is (str/blank? (:out r))))
  (with-task
    (fn [{:keys [dir env]}]
      (let [r (run {:env (assoc env "SWARMFORGE_ROLE" "a" "SWARMKHAZAD_TASK_DIR" (str dir))
                    :in "{\"hook_event_name\":\"PreToolUse\"}" :ok? false}
                   "bb" (str (fs/path scripts "goal_judge.bb")))]
        (is (zero? (:exit r)))
        (is (str/blank? (:out r)))
        (is (not (fs/exists? (fs/path dir "state" "judge" "a.json"))) "no verdict for a non-Stop event")))))
