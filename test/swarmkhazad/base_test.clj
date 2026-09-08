(ns swarmkhazad.base-test
  "The base a task's work is counted against.

   Every case here comes from one live run. A summary reported `NOT READY —
   both diffs are empty` for a task whose whole deliverable was a commit
   sitting on the branch: the worktree's `refs/remotes/origin/HEAD` symref had
   never been created, there was no local `main` either, so the old chain
   [origin/HEAD main master] resolved to nothing, the diff was never computed,
   and `(none)` rendered identically to the repo beside it that had correctly
   changed nothing."
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

;; Loaded at the top level, not inside a deftest: `load-file` runs at runtime
;; while the symbols below are resolved at analysis time, so an in-test load
;; fails with "Unable to resolve symbol" before it ever executes.
(def script-dir (str (fs/path (fs/cwd) "scripts")))
(load-file (str (fs/path script-dir "task_lib.bb")))
(load-file (str (fs/path script-dir "summary.bb")))
(load-file (str (fs/path script-dir "goal_judge.bb")))
(load-file (str (fs/path script-dir "handoffd.bb")))
(load-file (str (fs/path script-dir "portal.bb")))

(defn- tmp-ctx
  "A ctx carrying only what the base functions read."
  []
  (let [d (fs/create-temp-dir {:prefix "sk-base-"})]
    {:base-tsv (fs/path d "base.tsv") :dir d}))

(deftest base-tsv-round-trips
  (let [ctx (tmp-ctx)]
    (testing "absent file reads as no pins, not as a crash"
      (is (= {} (task-lib/read-base-tsv ctx))))

    (testing "what prepare computed is what a later reader gets"
      (task-lib/write-base-tsv! ctx [{:name "superset" :start "795bcd9ee1"}
                                     {:name "gobel" :start "8a67e031f3"}])
      (is (= {"superset" "795bcd9ee1" "gobel" "8a67e031f3"}
             (task-lib/read-base-tsv ctx))))

    (testing "a re-run never re-points an existing base"
      ;; `open` re-runs to resume a task after a reboot. Re-pinning then would
      ;; move the base to whatever origin/main has since become, which is the
      ;; drift the pin exists to stop.
      (task-lib/write-base-tsv! ctx [{:name "superset" :start "ef72964fc2"}])
      (is (= "795bcd9ee1" (get (task-lib/read-base-tsv ctx) "superset"))
          "the original pin must survive a second prepare"))

    (testing "a repo with no computable start is left unpinned rather than blank"
      (task-lib/write-base-tsv! ctx [{:name "empty" :start nil}
                                     {:name "blank" :start ""}])
      (let [pins (task-lib/read-base-tsv ctx)]
        (is (nil? (get pins "empty")))
        (is (nil? (get pins "blank")))))))

(deftest chain-tries-remotes-before-local-branches
  ;; Not a behaviour test — a guard on the ORDER, which is the half that was
  ;; wrong in two directions at once. `origin/HEAD` is a symref `git clone`
  ;; writes and other setups never do, so leading with it loses the diff; a
  ;; local `main` can sit arbitrarily far behind its remote (gobel's was 5
  ;; commits behind origin/main), so falling back to it reports unrelated
  ;; history as the task's work. A wrong base fabricates a diff as readily as
  ;; it loses one, so every remote-tracking ref is tried before any local one.
  (doseq [f ["scripts/summary.bb" "scripts/goal_judge.bb"]]
    (let [src (slurp f)]
      (testing (str f " keeps remote refs ahead of local ones")
        (is (str/includes? src "\"origin/HEAD\" \"origin/main\" \"origin/master\"")
            (str f " must try origin/main and origin/master"))
        (is (not (str/includes? src "[\"origin/HEAD\" \"main\" \"master\"]"))
            (str f " still has the chain that resolved to nothing")))))

  (testing "no resolver falls back to a fixed commit count"
    ;; `HEAD~10..HEAD` counted ten commits of unrelated history as this task's
    ;; work whenever the refs failed.
    ;;
    ;; Matched WITH its quote characters, so this looks for the string literal
    ;; in code and not for the name in prose. The first version of this
    ;; assertion matched the docstring that explains the removal, which is a
    ;; test that can never pass however correct the code is.
    (is (not (str/includes? (slurp "scripts/goal_judge.bb") "\"HEAD~10..HEAD\"")))))

(deftest an-uncomputed-diff-does-not-read-as-an-empty-one
  (let [src (slurp "scripts/summary.bb")]
    (testing "the section says the measurement is missing"
      (is (str/includes? src "BASE UNRESOLVED")))
    (testing "and the prompt is told not to call it 'no change'"
      (is (str/includes? src "is NOT `no ")
          "the summariser must distinguish an uncomputed diff from an empty one"))))

(deftest pin-wins-over-a-resolvable-ref
  ;; The discriminating case: this worktree HAS origin/HEAD, so the chain alone
  ;; would answer. A pin must still take precedence, because refs are shared
  ;; with the source checkout and move when anyone fetches in it.
  (let [ctx (tmp-ctx)
        wt (str (fs/cwd))
        head (str/trim (:out (process/sh {:dir wt} "git" "rev-parse" "HEAD")))
        prev (str/trim (:out (process/sh {:dir wt} "git" "rev-parse" "HEAD~1")))
        repo (fs/file-name (fs/cwd))]
    (task-lib/write-base-tsv! ctx [{:name repo :start prev}])
    (testing "the pin is used even though origin/HEAD resolves"
      (is (= prev (summary/base-ref ctx repo wt))))
    (testing "a pin the worktree does not hold falls through instead of breaking the range"
      ;; A pinned sha that is not present makes `<pin>..HEAD` fail silently and
      ;; the section goes empty again — the original bug by another route.
      ;;
      ;; Asserted as "not the bogus pin, and resolvable", NOT as "equals HEAD".
      ;; The first version compared the fallback to HEAD, which only holds
      ;; while the branch tip happens to equal origin/main — it passed until
      ;; origin/main moved mid-session and then failed on a correct code path.
      ;; That is the same wrong-reason-pass this whole branch is about.
      (let [ctx2 (tmp-ctx)
            bogus (apply str (repeat 40 "0"))]
        (task-lib/write-base-tsv! ctx2 [{:name repo :start bogus}])
        (let [got (summary/base-ref ctx2 repo wt)
              r (process/sh {:continue true :dir wt} "git" "rev-parse" "--verify" "--quiet"
                            (str got "^{commit}"))]
          (is (not= bogus got) "the unresolvable pin must be ignored")
          (is (some? got) "and something from the chain must answer")
          (is (zero? (:exit r)) (str "what it returned must be a real commit: " got))
          ;; Deliberately NOT asserted: that the fallback is an ancestor of
          ;; HEAD. It often is not, and finding that out is the argument for
          ;; the pin. Measured while writing this: origin/main advanced past
          ;; this worktree's base mid-session, so `origin/HEAD` resolved to a
          ;; commit HEAD does not descend from, and `origin/HEAD..HEAD` would
          ;; have reported a diff that is neither the task's work nor empty.
          ;; A pin cannot drift that way; a derived ref always can.
          )))))

(deftest unmet-is-held-to-the-lines-the-session-owns
  (let [others #{"implement"}]
    (testing "another role's line is dropped, and recorded"
      ;; Verbatim from the run: review_superset was blocked on this.
      (let [v (goal-judge/own-unmet-only
               {:met false
                :unmet ["implement @gobel — URL repoint to .com if .co is not live"]}
               "review" others)]
        (is (true? (:met v)) "every reason belonged to another role, so this role is met")
        (is (empty? (:unmet v)))
        (is (= 1 (count (:dropped-unmet v))) "the drop must be auditable, not silent")))

    (testing "the session's own unmet line survives"
      (let [v (goal-judge/own-unmet-only
               {:met false :unmet ["review — the diff was never checked against the goal"]}
               "review" others)]
        (is (false? (:met v)))
        (is (= 1 (count (:unmet v))))))

    (testing "a paraphrase that names no role survives"
      ;; The unsafe direction: dropping a real unmet item turns a block into a
      ;; pass. Only an item OPENING with another role's name is dropped.
      (let [v (goal-judge/own-unmet-only
               {:met false :unmet ["the diff still contains an unrelated reformat"]}
               "review" others)]
        (is (false? (:met v)))
        (is (= 1 (count (:unmet v))))))

    (testing "a mixed verdict keeps the real item and stays unmet"
      (let [v (goal-judge/own-unmet-only
               {:met false :unmet ["implement @gobel — repoint the URL"
                                   "review — no evidence recorded"]}
               "review" others)]
        (is (false? (:met v)))
        (is (= ["review — no evidence recorded"] (:unmet v)))))

    (testing "a judge that is down is never rewritten"
      (let [v (goal-judge/own-unmet-only
               {:met false :unmet ["judge_unavailable"] :down true} "review" others)]
        (is (false? (:met v)))
        (is (= ["judge_unavailable"] (:unmet v)))))))

;; ---------------------------------------------------------------------------
;; The open gates, and the record of which code ran. Same origin as everything
;; above: one live run.

(load-file (str (fs/path script-dir "swarm_lib.bb")))

(defn- metrics-ctx
  "A ctx carrying only what the measure-owner gate reads."
  [body]
  (let [d (fs/create-temp-dir {:prefix "sk-gate-"})
        m (fs/path d "metrics.md")]
    (spit (str m) body)
    {:metrics-file m :state-dir (fs/path d "state") :task-dir d :task-id "t"}))

(def two-commanded-bars
  (str "# t — bars\n\n## Quantitative\n"
       "- the pin is bounded — bar: one line — measure: `grep -n fastmcp Dockerfile`\n"
       "- repo tests — bar: exits 0 — measure: `uv run pytest -q`\n\n"
       "## Qualitative\n- the diff is small — judged by: Allen\n"))

(deftest bars-need-a-role-that-runs-them
  (testing "commanded bars with no measuring role are named, not ignored"
    ;; Measured: a two-role task declared five measure: commands, evidence/
    ;; stayed empty, and afterwards that is indistinguishable from bars that
    ;; ran and passed.
    (let [ctx (metrics-ctx two-commanded-bars)
          roles [{:role "implement"} {:role "review"}]
          orphans (swarm-lib/unmeasured-bars ctx roles)]
      (is (= 2 (count orphans)))
      (is (= ["the pin is bounded" "repo tests"] (mapv :name orphans)))
      (is (thrown? clojure.lang.ExceptionInfo (swarm-lib/require-measurable! ctx roles)))))

  (testing "a role whose prompt runs the runner clears the gate"
    ;; Keyed on the PROMPT: run.prompt is what invokes run_evidence.bb, so this
    ;; stays correct if the role is ever renamed.
    (let [ctx (metrics-ctx two-commanded-bars)
          roles [{:role "implement"} {:role "review"} {:role "run"}]]
      (is (nil? (swarm-lib/unmeasured-bars ctx roles)))
      (is (nil? (swarm-lib/require-measurable! ctx roles)))))

  (testing "qualitative-only metrics need no measuring role"
    (let [ctx (metrics-ctx "## Quantitative\n\n## Qualitative\n- small — judged by: Allen\n")]
      (is (nil? (swarm-lib/unmeasured-bars ctx [{:role "implement"}])))))

  (testing "a prose measure is not a commanded bar"
    ;; parse-bar keeps a prose measure with a nil :command — someone still has
    ;; to run it, but run_evidence.bb cannot, so it must not trip this gate.
    (let [ctx (metrics-ctx (str "## Quantitative\n"
                                "- looks right — bar: green — measure: eyeball the dashboard\n"))]
      (is (nil? (swarm-lib/unmeasured-bars ctx [{:role "implement"}])))))

  (testing "a template placeholder is not a bar anyone meant to run"
    ;; The first version of this gate refused a freshly scaffolded task: the
    ;; metrics template wrote `- <metric> — bar: <threshold> — measure:
    ;; `<command>`` as a live bullet, which every parser reads as a real
    ;; commanded bar. Found by shim_test's `open t-trust`. The template now
    ;; hides its grammar in an HTML comment, and this guards the tasks
    ;; scaffolded before it did.
    (let [ctx (metrics-ctx (str "## Quantitative\n"
                                "- <metric> — bar: <threshold> — measure: `<command>`\n"))]
      (is (nil? (swarm-lib/unmeasured-bars ctx [{:role "implement"}])))))

  (testing "the scaffold that shim_test opens no longer declares a live bar"
    ;; Guarding the template itself, not just the tolerance for it.
    (let [src (slurp "scripts/swarmkhazad.bb")
          tmpl (subs src (str/index-of src "defn metrics-template")
                     (str/index-of src "defn flag-values"))]
      (is (not (str/includes? tmpl "\"## Quantitative\\n- <metric>"))
          "the grammar must be a comment, not a bullet"))))

(deftest a-crossed-off-escalation-is-not-an-open-ask
  ;; escalation.md is append-only, so a wrong bullet can only be followed by
  ;; another retracting it. Measured: the file opened with `probe — probe`, and
  ;; the next entry existed only to say `ignore the line above`.
  (let [d (fs/create-temp-dir {:prefix "sk-esc-"})
        ctx {:escalation-file (fs/path d "escalation.md")
             :state-dir (fs/path d "state")}
        wrong "- [superset] **probe** — probe"
        real "- [gobel] **the one-commit bar is unmeetable here** — zero-diff is correct"]
    (spit (str (:escalation-file ctx)) (str wrong "\n" real "\n"))

    (testing "both bullets read as live until one is crossed off"
      (let [out (summary/live-escalations ctx)]
        (is (str/includes? out "**probe**"))
        (is (not (str/includes? out "CROSSED OFF")))))

    (testing "a crossed-off bullet is marked, and the real ask is untouched"
      (task-lib/set-handled!
       ctx
       (task-lib/attention-key {:kind "escalation" :text (str/replace wrong #"^- " "")})
       true)
      (let [out (summary/live-escalations ctx)]
        (is (str/includes? out "CROSSED OFF"))
        (is (= 1 (count (filter #(str/includes? % "CROSSED OFF") (str/split-lines out))))
            "only the retracted bullet is marked")
        (is (str/includes? out "one-commit bar is unmeetable")
            "the live ask must survive verbatim")))

    (testing "nothing is deleted — the file still holds both"
      (is (str/includes? (slurp (str (:escalation-file ctx))) "**probe**")))))

(deftest note-bb-offers-retract-alongside-resolved
  ;; `resolved` means the ask was real and is now met; `retract` means it should
  ;; not have been asked. Same cross-off, different destination, so a reader can
  ;; tell a met ask from a withdrawn one.
  (let [src (slurp "scripts/note.bb")]
    (is (str/includes? src "note.bb retract"))
    (is (str/includes? src "#{\"resolved\" \"retract\"}")
        "both kinds must cross off through the same store")
    (is (str/includes? src "retracted: "))))

(deftest the-open-gates-run-before-anything-is-spawned
  (let [src (slurp "scripts/swarm_lib.bb")
        idx (fn [s] (str/index-of src s))]
    (testing "the measure-owner gate precedes boot"
      (is (< (idx "(require-measurable! ctx roles)")
             (idx "(boot-sessions! ctx sessions)"))))
    (testing "the runtime is stamped before boot"
      (is (< (idx "(write-runtime-stamp! ctx)")
             (idx "(boot-sessions! ctx sessions)"))))))

(deftest prepare-pins-the-base-in-a-checkout-shaped-like-the-real-one
  ;; The case the suite did not have, and the reason the bug shipped. Every
  ;; other fixture builds a source repo with `update-ref refs/remotes/origin/
  ;; main` and a local `main` — so the old chain ["origin/HEAD" "main"
  ;; "master"] missed origin/HEAD and landed on the LOCAL main, which in a
  ;; fixture is the same commit. The real checkout had no local main at all,
  ;; and the chain fell off the end.
  ;;
  ;; So: origin/main only, no origin/HEAD, no local main. Then prepare, and
  ;; the base must be written down and resolvable.
  (let [sandbox (fs/create-temp-dir {:prefix "sk-prep-"})
        home (fs/path sandbox "home")
        src (fs/path sandbox "src" "fixture")
        g (fn [& args] (let [r (apply process/sh {:continue true :dir (str src)} "git" args)]
                         (str/trim (str (:out r)))))
        cli (str (fs/path (fs/cwd) "scripts" "swarmkhazad.bb"))
        run (fn [& args] (apply process/sh {:continue true
                                            :extra-env {"SWARMKHAZAD_HOME" (str home)}}
                                "bb" cli args))]
    (fs/create-dirs src)
    (g "init" "-q" "-b" "main")
    (g "config" "user.email" "t@example.com")
    (g "config" "user.name" "T")
    (spit (str (fs/path src "README.md")) "one\n")
    (g "add" ".")
    (g "commit" "-q" "-m" "one")
    (g "remote" "add" "origin" "https://example.invalid/acme/fixture.git")
    (let [sha (g "rev-parse" "HEAD")]
      (g "update-ref" "refs/remotes/origin/main" sha)
      ;; The two absences that matter. `checkout --detach` first, because a
      ;; branch cannot be deleted while it is checked out.
      (g "checkout" "-q" "--detach" sha)
      (g "branch" "-q" "-D" "main")

      (testing "the fixture really is missing what the real checkout was missing"
        (is (= "" (g "rev-parse" "--verify" "--quiet" "origin/HEAD")))
        (is (= "" (g "rev-parse" "--verify" "--quiet" "main")))
        (is (= sha (g "rev-parse" "--verify" "--quiet" "origin/main"))))

      (let [id "t-pin-fixture"
            new-r (run "new" id "--repo" (str src))
            prep-r (run "prepare" id)
            dir (fs/path home "tasks" id)
            base-tsv (fs/path dir "state" "base.tsv")]
        (testing "new and prepare both succeed on such a checkout"
          (is (zero? (:exit new-r)) (str (:out new-r) (:err new-r)))
          (is (zero? (:exit prep-r)) (str (:out prep-r) (:err prep-r))))

        (testing "prepare wrote the base down"
          (is (fs/regular-file? base-tsv)
              "state/base.tsv must exist after prepare")
          (is (= {"fixture" sha} (task-lib/read-base-tsv {:base-tsv base-tsv}))))

        (testing "and the readers resolve it rather than falling off the chain"
          (let [wt (str (fs/path dir "worktrees" "fixture"))
                ctx {:base-tsv base-tsv}]
            (is (= sha (summary/base-ref ctx "fixture" wt)))
            (is (= sha (goal-judge/base-ref ctx "fixture" wt)))))))))

(deftest a-daemon-outlives-nothing
  ;; A handoffd is a bare `bb` process whose life was tied to nothing. Deleting
  ;; a task folder left it polling a directory that was not there, and no other
  ;; part of the system looks for that: `close` needs a task folder to work
  ;; from, and `reap` only ever inspects git. Three were found running in one
  ;; day — t-e2e, t-kick, t-ship — one from a generation since replaced.
  (let [d (fs/create-temp-dir {:prefix "sk-daemon-"})
        task (fs/path d "tasks" "t-x")]
    (fs/create-dirs task)
    (let [ctx {:task-dir task :daemon-dir (fs/path task "state" "daemon")}]
      (testing "a daemon whose task exists keeps running"
        (is (false? (handoffd/orphaned? ctx)))
        (is (false? (handoffd/should-stop? ctx))))

      (testing "a daemon whose task folder is gone stops itself"
        (fs/delete-tree task)
        (is (true? (handoffd/orphaned? ctx)))
        (is (true? (handoffd/should-stop? ctx))))

      (testing "an emptied state dir is NOT orphaned — close keeps the notes"
        ;; `close` stops the daemon and leaves the folder for its bullet files.
        ;; A daemon that quit because state/ was thin would stop serving a task
        ;; still being worked.
        (fs/create-dirs task)
        (is (false? (handoffd/orphaned? ctx)))))))

(deftest a-reboot-is-visible-rather-than-silent
  ;; The tmux socket lives under /tmp (task_lib.bb: a unix socket path is
  ;; capped), and macOS clears /tmp at boot. The FILE recording its path is in
  ;; the task folder and survives, so `opened?` read every task as open after
  ;; a reboot while nothing was running — in the one place you would look to
  ;; find out otherwise.
  (let [d (fs/create-temp-dir {:prefix "sk-reboot-"})
        sock (fs/path d "live.sock")
        sock-file (fs/path d "tmux-socket")
        ctx {:tmux-socket-file sock-file}]
    (testing "no socket file at all is not opened"
      (is (false? (portal/opened? ctx))))

    (testing "a socket file naming a path that does not exist is not opened"
      ;; This is the post-reboot state exactly: the file is intact, /tmp is not.
      (spit (str sock-file) (str sock "\n"))
      (is (false? (portal/opened? ctx))))

    (testing "a socket file naming something that exists is opened"
      (spit (str sock) "")
      (is (true? (portal/opened? ctx))))

    (testing "an empty socket file is not opened"
      (spit (str sock-file) "\n")
      (is (false? (portal/opened? ctx))))))

(deftest the-runtime-stamp-says-which-code-ran
  ;; Reviewing the run that produced all of this meant reading four file mtimes
  ;; to tell which findings were real and which were already fixed, because a
  ;; task records nothing about the checkout that opened it.
  (let [d (fs/create-temp-dir {:prefix "sk-stamp-"})
        ctx {:state-dir (fs/path d "state")}
        stamp (swarm-lib/write-runtime-stamp! ctx)
        rows (into {} (for [l (str/split-lines (slurp (str stamp)))
                            :let [[k v] (str/split l #"\t" 2)]
                            :when v]
                        [k v]))]
    (testing "it records the checkout, its commit, and whether it was dirty"
      (is (contains? rows "script_dir"))
      (is (contains? rows "branch"))
      (is (#{"true" "false"} (get rows "dirty")))
      (is (contains? rows "opened_at")))
    (testing "the commit is a real sha, not a placeholder"
      (is (re-matches #"[0-9a-f]{40}" (get rows "commit"))))))
