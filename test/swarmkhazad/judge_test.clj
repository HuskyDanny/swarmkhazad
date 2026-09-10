(ns swarmkhazad.judge-test
  "goal_judge.bb's two entry points, and the budget between them.

   `--grade <session>` is what swarm_handoff.bb calls at the moment a
   git_handoff is sent: it grades the committed tree once, and the gate refuses
   while the verdict is unmet, up to max-refusals. The Stop hook is no longer a
   grader at all — it is a nudge for a session that has committed work and not
   handed it off.

   The judge model is a stub `claude` on PATH returning whatever verdict the
   test asks for (SWARMKHAZAD_STUB_VERDICT), so the decisions, the files and the
   gate are pinned without a network call. The stub records its argv, which is
   how a test asserts the judge was NOT called."
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

(defn with-task
  "A prepared task over `repo-names` (default one repo), roles a and b on
   claude and c on grok. f gets the helpers defined here."
  [{:keys [repo-names goal]} f]
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-judge."})
        home (str (fs/path sandbox "home"))
        stubdir (str (fs/path sandbox "stubbin"))
        names (or repo-names ["fixture"])
        srcs (mapv #(str (fs/path sandbox "src" %)) names)
        id "t-judge"
        base-env {"SWARMKHAZAD_HOME" home "SWARMKHAZAD_TASK_ID" id
                  ;; stubdir first, so `claude` is the verdict stub and `bb` is
                  ;; the shim that can be told to fail for one script.
                  "PATH" (str stubdir ":" (System/getenv "PATH"))
                  "SWARMKHAZAD_STUB_BB_REAL" (str/trim (:out (process/sh "which" "bb")))}]
    (try
      (doseq [s srcs] (make-source-repo! s))
      (fs/create-dirs stubdir)
      (fs/copy stub (fs/path stubdir "claude"))
      (fs/set-posix-file-permissions (fs/path stubdir "claude") "rwxr-xr-x")
      (fs/copy (fs/path repo-root "test" "fixtures" "stub-bb.sh") (fs/path stubdir "bb"))
      (fs/set-posix-file-permissions (fs/path stubdir "bb") "rwxr-xr-x")
      (run {:env base-env} cli "new" id "--repo" (first srcs))
      (let [dir (fs/path home "tasks" id)]
        (spit (str (fs/path dir "roles")) "a claude task\nb claude task\nc grok task\n")
        (spit (str (fs/path dir "repos")) (str/join "" (map #(str % "\n") srcs)))
        (spit (str (fs/path dir "goal.md"))
              (or goal "# t-judge\n\n## Goal\n- [ ] a — GOAL-X\n\n## Not-goal\n- none\n"))
        (run {:env base-env} cli "prepare" id)
        (letfn [(worktree [repo] (str (fs/path dir "worktrees" repo)))
                (env-for [session extra]
                  (merge (assoc base-env
                                "SWARMKHAZAD_SESSION" session
                                "SWARMFORGE_ROLE" (first (str/split session #"_"))
                                "SWARMKHAZAD_TASK_DIR" (str dir))
                         extra))
                (in-session [session repo extra script & args]
                  (apply run {:dir (worktree repo) :env (env-for session extra) :ok? false}
                         "bb" (str (fs/path scripts script)) args))
                (stop! [session repo & [{:keys [turn]}]]
                  (run {:dir (worktree repo) :env (env-for session nil) :ok? false
                        :in (json/generate-string {"hook_event_name" "Stop"
                                                   "session_id" (or turn "turn-1")})}
                       "bb" (str (fs/path scripts "goal_judge.bb"))))
                (commit! [repo file]
                  (write! (fs/path (worktree repo) file) (str file "\n"))
                  (git (worktree repo) "add" file)
                  (git (worktree repo) "commit" "-q" "-m" (str "add " file)))
                (handoff! [session repo to verdict]
                  (let [draft (fs/path dir "tmp" (str session "-draft.txt"))]
                    (write! draft (str "type: git_handoff\nto: " to "\npriority: 50\n"))
                    (in-session session repo (when verdict {"SWARMKHAZAD_STUB_VERDICT" verdict})
                                "swarm_handoff.bb" (str draft))))]
          (f {:dir dir :env base-env :srcs srcs
              :worktree worktree :stop! stop! :commit! commit!
              :handoff! handoff! :in-session in-session})))
      (finally
        (fs/delete-tree sandbox)))))

(defn decision [result]
  (try (json/parse-string (:out result) true) (catch Exception _ nil)))

(defn verdict-file [dir session]
  (let [f (fs/path dir "state" "judge" (str session ".json"))]
    (when (fs/regular-file? f) (json/parse-string (slurp (str f)) true))))

(defn judge-called? [dir role]
  (fs/regular-file? (fs/path dir "tmp" (str "judge-" role ".argv"))))

(defn queued [dir session]
  (first (sort (map str (fs/glob (fs/path dir "mail" session "outbox") "*.handoff")))))

;; ------------------------------------------------------------ the Stop hook

(deftest the-stop-hook-is-a-nudge-and-never-a-grader
  (with-task {}
    (fn [{:keys [dir stop! commit! handoff!]}]
      (testing "a clean worktree stops freely, and nothing was graded"
        (let [r (stop! "a" "fixture")]
          (is (nil? (decision r)) (:out r))
          (is (not (judge-called? dir "a")) "the Stop hook must not call the model at all")
          (is (nil? (verdict-file dir "a")) "and must not write a verdict")))
      (testing "committed work with no handoff keeps the turn open"
        (commit! "fixture" "x.txt")
        (let [r (stop! "a" "fixture")]
          (is (= "block" (:decision (decision r))))
          (is (str/includes? (:reason (decision r)) "no git_handoff for its HEAD"))
          (is (not (judge-called? dir "a")) "still no model call: the nudge is not about goals")))
      (testing "the nudge is bounded — twice in one turn, then the stop is allowed"
        (is (= "block" (:decision (decision (stop! "a" "fixture")))) "second nudge")
        (is (nil? (decision (stop! "a" "fixture"))) "third stop in the same turn is allowed"))
      (testing "a new turn gets a fresh budget"
        (is (= "block" (:decision (decision (stop! "a" "fixture" {:turn "turn-2"}))))))
      (testing "once a handoff is queued for that HEAD the stop is allowed"
        (is (zero? (:exit (handoff! "a" "fixture" "b" nil))))
        (is (nil? (decision (stop! "a" "fixture" {:turn "turn-3"})))))
      (testing "but a NEW commit after that handoff is unsent work again"
        (commit! "fixture" "y.txt")
        (let [r (stop! "a" "fixture" {:turn "turn-4"})]
          (is (= "block" (:decision (decision r)))
              "the handoff already sent was for the old HEAD; this one has not been handed over")))
      (testing "a worktree git cannot answer for counts as unsent work, never as none"
        ;; The two errors are not symmetric: a spurious nudge costs a line in a
        ;; pane, a missed one leaves the board frozen with nobody watching.
        (let [wt (fs/path dir "worktrees" "fixture")
              dotgit (fs/path wt ".git")
              saved (slurp (str dotgit))]
          (spit (str dotgit) "gitdir: /nowhere/at/all\n")
          (try
            (is (= "block" (:decision (decision (stop! "a" "fixture" {:turn "turn-5"})))))
            (finally (spit (str dotgit) saved)))))
      (testing "a session holding the terminal broadcast is never nudged"
        (write! (fs/path dir "mail" "b" "inbox" "in_process" "50_20260101T000000000Z_from_a_to_b.handoff")
                "id: x\nfrom: a\nto: b\npriority: 50\ntype: git_handoff\ncommit: 0000000000\nnon-forwarding: true\n\nRe-read.\n")
        (is (nil? (decision (stop! "b" "fixture"))))))))

;; ------------------------------------------------------------ the gate

(deftest the-handoff-gate-grades-once-and-refuses-while-unmet
  (with-task {}
    (fn [{:keys [dir commit! handoff!]}]
      (commit! "fixture" "x.txt")
      (testing "an unmet verdict refuses the handoff and says which attempt this is"
        (let [r (handoff! "a" "fixture" "b" "{\"met\":false,\"unmet\":[\"GOAL-X\"]}")]
          (is (= 1 (:exit r)))
          (is (str/includes? (:err r) "Goal judge says unmet for role a: GOAL-X") (:err r))
          (is (str/includes? (:err r) "attempt 1 of 3"))
          (is (judge-called? dir "a") "the gate is where the model runs")
          (is (false? (:met (verdict-file dir "a"))) "and the verdict is written")
          (is (fs/exists? (fs/path dir "tmp" "a-draft.txt")) "the draft is left for another attempt")))
      (testing "the same gap again escalates only once"
        (let [r (handoff! "a" "fixture" "b" "{\"met\":false,\"unmet\":[\"GOAL-X\"]}")]
          (is (= 1 (:exit r)))
          (is (str/includes? (:err r) "attempt 2 of 3")))
        (is (= 1 (count (re-seq #"goal judge says unmet" (slurp (str (fs/path dir "escalation.md"))))))
            "one line per distinct verdict — the Stop-hook judge wrote one per turn, which is how gobel got 22"))
      (testing "a different gap is a new line"
        (let [r (handoff! "a" "fixture" "b" "{\"met\":false,\"unmet\":[\"GOAL-Y\"]}")]
          (is (= 1 (:exit r)))
          (is (str/includes? (:err r) "attempt 3 of 3")))
        (is (= 2 (count (re-seq #"goal judge says unmet" (slurp (str (fs/path dir "escalation.md"))))))))
      (testing "past the budget the work goes forward carrying the gap"
        (let [r (handoff! "a" "fixture" "b" "{\"met\":false,\"unmet\":[\"GOAL-Y\"]}")]
          (is (zero? (:exit r)) (:err r))
          (is (str/includes? (:err r) "after 3 refusals — forwarding"))
          (let [sent (queued dir "a")]
            (is (some? sent) "the handoff was queued")
            (is (str/includes? (slurp sent) "unmet: GOAL-Y")
                "the recipient reads the gap on the handoff itself, not only in a file"))))
      (testing "and the budget resets, so the next piece of work starts clean"
        (is (not (fs/exists? (fs/path dir "state" "judge" "a.refusals"))))))))

(deftest a-met-verdict-lets-the-handoff-straight-through
  (with-task {}
    (fn [{:keys [dir commit! handoff!]}]
      (commit! "fixture" "x.txt")
      (let [r (handoff! "a" "fixture" "b" "{\"met\":true,\"unmet\":[]}")]
        (is (zero? (:exit r)) (:err r))
        (is (true? (:met (verdict-file dir "a"))))
        (is (= "" (slurp (str (fs/path dir "escalation.md")))) "a met verdict escalates nothing")
        (is (not (str/includes? (slurp (queued dir "a")) "unmet:")) "and nothing rides on the handoff")))))

(deftest a-down-judge-is-never-a-silent-pass
  (with-task {}
    (fn [{:keys [dir commit! handoff!]}]
      (commit! "fixture" "x.txt")
      (doseq [[label mode] [["the judge process fails" "down"] ["the judge returns no JSON" "garbage"]]]
        (fs/delete-if-exists (fs/path dir "state" "judge" "a.json"))
        (fs/delete-if-exists (fs/path dir "state" "judge" "a.refusals"))
        (testing label
          (let [r (handoff! "a" "fixture" "b" mode)]
            (is (= 1 (:exit r)) "an unavailable judge refuses rather than waving the handoff through")
            (is (str/includes? (:err r) "judge_unavailable") (:err r))
            (is (false? (:met (verdict-file dir "a"))))
            (is (str/includes? (slurp (str (fs/path dir "escalation.md"))) "judge_unavailable")
                "and says so where a human will see it"))))
      (testing "an unavailable judge still spends the budget, so a broken model delays rather than wedges"
        (is (str/includes? (:err (handoff! "a" "fixture" "b" "down")) "attempt 2 of 3"))
        (is (str/includes? (:err (handoff! "a" "fixture" "b" "down")) "attempt 3 of 3"))
        (is (zero? (:exit (handoff! "a" "fixture" "b" "down"))))))))

(deftest a-crashing-judge-process-is-not-a-stale-pass
  (with-task {}
    (fn [{:keys [dir commit! handoff! in-session]}]
      (commit! "fixture" "x.txt")
      (testing "first, a met verdict is on disk"
        (is (zero? (:exit (handoff! "a" "fixture" "b" "{\"met\":true,\"unmet\":[]}"))))
        (is (true? (:met (verdict-file dir "a")))))
      (testing "then the judge process itself dies: the handoff is refused, not passed on the old verdict"
        (commit! "fixture" "y.txt")
        (write! (fs/path dir "tmp" "a-draft.txt") "type: git_handoff\nto: b\npriority: 50\n")
        (let [r (in-session "a" "fixture" {"SWARMKHAZAD_STUB_BB_FAIL" "goal_judge.bb"}
                            "swarm_handoff.bb" (str (fs/path dir "tmp" "a-draft.txt")))]
          (is (= 1 (:exit r)) "the verdict already on disk says met — using it here would be a stale pass")
          (is (str/includes? (:err r) "judge_unavailable") (:err r)))))))

(deftest a-harness-with-no-judge-is-not-graded-at-all
  (with-task {}
    (fn [{:keys [dir commit! handoff!]}]
      (commit! "fixture" "x.txt")
      (let [r (handoff! "c" "fixture" "a" "{\"met\":false,\"unmet\":[\"GOAL-X\"]}")]
        (is (zero? (:exit r)) (:err r))
        (is (not (judge-called? dir "c")) "grok has no judge: nothing graded, nothing refused")
        (is (nil? (verdict-file dir "c")))))))

;; ------------------------------------------------------------ note.bb

(deftest a-note-in-a-multi-repo-task-says-which-repo
  (with-task {:repo-names ["fixture" "other"]}
    (fn [{:keys [dir in-session]}]
      (is (zero? (:exit (in-session "a_other" "other" nil "note.bb"
                                    "finding" "the exporter already retries"
                                    "upstreams.py:88 wraps it, so the plan is redundant"))))
      (is (= "- [other] **the exporter already retries** — upstreams.py:88 wraps it, so the plan is redundant"
             (str/trim (slurp (str (fs/path dir "finding.md")))))
          "past one repo a bullet has to say which, or a cross-repo finding has no home")
      (testing "the tag follows the session, not the cwd"
        (is (zero? (:exit (in-session "a_fixture" "fixture" nil "note.bb"
                                      "gotcha" "the shim rewrites argv" "so resolve the binary first"))))
        (is (str/starts-with? (str/trim (slurp (str (fs/path dir "gotcha.md")))) "- [fixture]"))))))

;; ------------------------------------------------------------ granularity

(deftest a-session-is-graded-on-its-own-repo-s-goal-lines
  (with-task {:repo-names ["fixture" "other"]
              :goal (str "# t-judge\n\n## Goal\n"
                         "- [ ] a @fixture — MINE-FIXTURE\n"
                         "- [ ] a @other — MINE-OTHER\n"
                         "- [ ] b — THEIRS\n"
                         "- [ ] SHARED with no role and no repo\n\n"
                         "## Not-goal\n- none\n")}
    (fn [{:keys [dir commit! handoff!]}]
      (commit! "fixture" "x.txt")
      (is (zero? (:exit (handoff! "a_fixture" "fixture" "a_other" "{\"met\":true,\"unmet\":[]}"))))
      ;; judge-<session>, not judge-<role>: a role with two repos grades twice,
      ;; and one file per role would have the second overwrite the first.
      (let [prompt (slurp (str (fs/path dir "tmp" "judge-a_fixture.argv")))
            goals (subs prompt (str/index-of prompt "<goals_md>") (str/index-of prompt "</goals_md>"))
            mine (str/replace goals #"(?s)## Not yours.*" "")]
        (testing "this session's own repo line is graded, and so is the untagged one"
          (is (str/includes? mine "MINE-FIXTURE"))
          (is (str/includes? mine "SHARED with no role and no repo")))
        (testing "the same role's OTHER repo is not — that is a different session's verdict"
          (is (not (str/includes? mine "MINE-OTHER"))
              "gobel's judge called a task partially met by grading three repos as one"))
        (testing "and another role's line is never this one's to grade"
          (is (not (str/includes? mine "THEIRS"))))))))

(deftest one-goal-line-is-escalated-once-however-often-it-is-graded
  ;; escalation.md is append-only, so a line written twice cannot be taken
  ;; back. Comparing each verdict against the PREVIOUS one alone let an unmet
  ;; set of A, A, {A,B}, A write A three times — and GobelCutover did exactly
  ;; that: four of its fifteen bullets are one goal line, `Secret rotated
  ;; first, then the PR merged`, with four different timestamps. One of those
  ;; four is spelled with a trailing full stop, which is why the key is what
  ;; the line is ABOUT and not how the judge worded it.
  (with-task {}
    (fn [{:keys [dir commit! handoff!]}]
      (commit! "fixture" "x.txt")
      (doseq [v ["{\"met\":false,\"unmet\":[\"GOAL-X\"]}"
                 "{\"met\":false,\"unmet\":[\"GOAL-X\"]}"
                 "{\"met\":false,\"unmet\":[\"GOAL-X.\",\"GOAL-Y\"]}"
                 "{\"met\":false,\"unmet\":[\"GOAL-X\"]}"]]
        (handoff! "a" "fixture" "b" v))
      (let [lines (->> (str/split-lines (slurp (str (fs/path dir "escalation.md"))))
                       (remove str/blank?) vec)]
        (is (= 2 (count lines)) (str "one bullet per goal line, not per grading: " lines))
        (is (str/includes? (first lines) "GOAL-X"))
        (is (str/includes? (second lines) "GOAL-Y"))
        (is (not (str/includes? (second lines) "GOAL-X"))
            "the second bullet names what is NEW, not the list the reader has already seen")))))

(deftest the-judge-s-own-escalation-goes-through-note-bb-like-everyone-else
  ;; "note.bb is the only writer of the bullet files" was true of the roles —
  ;; the hook denies them — and false of the tool: the judge appended straight
  ;; to escalation.md, and its line was the only one in a three-repo task that
  ;; did not say which repo it was about.
  (with-task {:repo-names ["fixture" "other"]
              :goal (str "# t-judge\n\n## Goal\n- [ ] a @fixture — GOAL-X\n\n## Not-goal\n- none\n")}
    (fn [{:keys [dir commit! handoff!]}]
      (commit! "fixture" "x.txt")
      (is (= 1 (:exit (handoff! "a_fixture" "fixture" "b" "{\"met\":false,\"unmet\":[\"GOAL-X\"]}"))))
      (let [line (str/trim (slurp (str (fs/path dir "escalation.md"))))]
        (is (str/starts-with? line "- [fixture] **")
            (str "the repo tag every other bullet carries, and note.bb's format: " line))
        (is (str/includes? line "a_fixture: goal judge says unmet — GOAL-X"))
        (is (str/includes? line "** — at ") "claim and why, split where every reader splits them")
        (is (= 1 (count (str/split-lines line))) "one line, so the file stays parseable")))))

