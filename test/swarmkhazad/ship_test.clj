(ns swarmkhazad.ship-test
  "ship.bb and pr_watch.bb — going out, and what comes back. The remotes here
   are local bare repos and `gh` is a stub, so a wrong push in this suite lands
   in /tmp rather than on GitHub."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (str (fs/cwd)))
(def scripts (str (fs/path repo-root "scripts")))
(def cli (str (fs/path scripts "swarmkhazad.bb")))

(defn run [{:keys [dir env in ok?]} & args]
  (let [result (apply process/sh (concat [(cond-> {:continue true :dir (str (or dir repo-root))
                                                   :extra-env (or env {})}
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

(defn make-repo!
  "A checkout whose origin URL says GitHub — so the owner→account map has
   something to read — but whose pushes go to a local bare repo through
   insteadOf. That rewrite is also why ship reads remote.origin.url from
   config: `git remote get-url` would answer with the local path."
  [sandbox name]
  (let [dir (str (fs/path sandbox "src" name))
        bare (str (fs/path sandbox "remotes" (str name ".git")))
        url (str "https://github.com/MithraAI/" name ".git")]
    (run {} "git" "init" "-q" "--bare" "-b" "main" bare)
    (fs/create-dirs dir)
    (git dir "init" "-q" "-b" "main")
    (git dir "config" "user.email" "t@example.com")
    (git dir "config" "user.name" "T")
    (write! (fs/path dir "README.md") "one\n")
    (git dir "add" ".")
    (git dir "commit" "-q" "-m" "one")
    (git dir "remote" "add" "origin" url)
    (git dir "config" (str "url." bare ".insteadOf") url)
    (git dir "push" "-q" "origin" "main")
    (git dir "remote" "set-head" "origin" "main")
    dir))

(def order-summary
  (str "at: 2026-09-07T00:00:00Z\nmodel: opus\ncost: 0.1000\n--- summary ---\n"
       "## Verdict\nREADY WITH FOLLOW-UPS — both diffs do what the goals say.\n\n"
       "## Merge order\n"
       "- gobel — nothing depends on it\n"
       "- cirdan — its image pin is what makes gobel's exporter start\n"))

(defn with-shipped-task
  "Two repos, each with one commit on the task branch, a stub gh on PATH, and
   no summary yet — writing one is the test's move."
  [f]
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-ship."})
        home (str (fs/path sandbox "home"))
        stubdir (str (fs/path sandbox "stubbin"))
        gh-log (str (fs/path sandbox "gh.log"))
        id "t-ship"
        env {"SWARMKHAZAD_HOME" home "GH_STUB_LOG" gh-log
             ;; On the base env, not only on the poll helper: handoffd does the
             ;; polling in the field, and a canned answer only the direct
             ;; caller could see would hide that.
             "GH_STUB_GRAPHQL" (str (fs/path sandbox "graphql.json"))
             "PATH" (str stubdir ":" (System/getenv "PATH"))}
        dir (fs/path home "tasks" id)]
    (try
      (let [gobel (make-repo! sandbox "gobel")
            cirdan (make-repo! sandbox "cirdan")]
        (fs/create-dirs stubdir)
        (fs/copy (fs/path repo-root "test" "fixtures" "stub-gh.sh") (fs/path stubdir "gh"))
        (fs/set-posix-file-permissions (fs/path stubdir "gh") "rwxr-xr-x")
        (fs/create-dirs dir)
        (write! (fs/path dir "goal.md")
                (str "# Repoint the exporter and pin the image\n\n## Goal\n"
                     "- [ ] implement @gobel — repoint DEFAULT_UPSTREAMS\n"
                     "- [ ] implement @cirdan — pin fastmcp in the Dockerfile\n"
                     "- [ ] review — both diffs read clean\n"))
        (write! (fs/path dir "metrics.md") "# bars\n")
        (write! (fs/path dir "roles") "implement claude\nreview claude\n")
        (write! (fs/path dir "repos") (str gobel "\n" cirdan "\n"))
        (run {:env env} cli "prepare" id)
        (doseq [[repo file] [["gobel" "exporter"] ["cirdan" "pin"]]]
          (let [wt (fs/path dir "worktrees" repo)]
            (write! (fs/path wt (str file ".txt")) "x\n")
            (git wt "add" (str file ".txt"))
            (git wt "-c" "user.email=t@e" "-c" "user.name=T" "commit" "-q" "-m" file)))
        (letfn [(ship [{:keys [in extra-env]} & args]
                  (apply run (cond-> {:env (merge env extra-env) :ok? false}
                               in (assoc :in in))
                         "bb" (str (fs/path scripts "ship.bb")) id args))
                (gh-calls [] (if (fs/regular-file? gh-log)
                               (->> (str/split-lines (slurp gh-log)) (remove str/blank?) vec)
                               []))
                (remote-branches [name]
                  (->> (str/split-lines (git (fs/path sandbox "remotes" (str name ".git"))
                                             "for-each-ref" "--format=%(refname:short)" "refs/heads/"))
                       (remove str/blank?) sort vec))
                (summary! [text] (write! (fs/path dir "state" "summary.md") text))
                (graphql! [repo text]
                  (write! (fs/path sandbox (str "graphql.json" (when repo (str "." repo)))) text))
                (poll [& [extra-env]]
                  (run {:env (merge env extra-env) :ok? false}
                       "bb" (str (fs/path scripts "pr_watch.bb")) id))
                (inbox [session] (->> (fs/glob (fs/path dir "mail" session "inbox" "new") "*.handoff")
                                      (map str) sort vec))]
          (f {:dir dir :env env :sandbox (str sandbox) :ship ship :gh-calls gh-calls
              :remote-branches remote-branches :summary! summary!
              :graphql! graphql! :poll poll :inbox inbox
              :deliver! (fn [] (run {:env env :ok? false}
                                    "bb" (str (fs/path scripts "handoffd.bb")) "--once" id))})))
      (finally
        (fs/delete-tree sandbox)))))

(deftest nothing-ships-that-nobody-has-read-a-verdict-on
  (with-shipped-task
    (fn [{:keys [ship remote-branches summary! gh-calls]}]
      (testing "no summary, no push — the summary is where a human reads a verdict"
        (let [r (ship {} "--yes")]
          (is (= 1 (:exit r)))
          (is (str/includes? (:err r) "no summary for t-ship"))
          (is (str/includes? (:err r) "Nothing ships that nobody has read a verdict on."))))
      (testing "a summary with no merge order is a summary ship will not act on"
        (summary! "at: x\n--- summary ---\n## Verdict\nREADY TO MERGE — looks fine.\n")
        (let [r (ship {} "--yes")]
          (is (= 1 (:exit r)))
          (is (str/includes? (:err r) "no `## Merge order` section"))
          (is (str/includes? (:err r) "ship never works the order out itself"))))
      (testing "nor one whose order forgets a repo that has commits"
        (summary! (str "at: x\n--- summary ---\n## Merge order\n- gobel — first\n"))
        (let [r (ship {} "--yes")]
          (is (= 1 (:exit r)))
          (is (str/includes? (:err r) "does not name cirdan"))))
      (is (= ["main"] (remote-branches "gobel")) "not one branch left the machine")
      (is (= ["main"] (remote-branches "cirdan")))
      (is (empty? (gh-calls)) "and gh was never called"))))

(deftest the-confirmation-is-the-gate-and-only-yes-opens-it
  (with-shipped-task
    (fn [{:keys [ship remote-branches summary! gh-calls]}]
      (summary! order-summary)
      (testing "the plan says what goes where, at which repo, and as whom"
        (let [r (ship {:in "no\n"})]
          (is (= 1 (:exit r)))
          (is (str/includes? (:out r) "gobel            sk/t-ship → main  at MithraAI/gobel  as allen-mithra  (1 commit(s))"))
          (is (str/includes? (:out r) "cirdan           sk/t-ship → main  at MithraAI/cirdan  as allen-mithra  (1 commit(s))"))
          (is (str/includes? (:out r) "nothing was pushed"))))
      (is (= ["main"] (remote-branches "gobel")) "answering anything but yes pushes nothing")
      (is (empty? (gh-calls)))
      (testing "an empty answer is not a yes either"
        (is (= 1 (:exit (ship {:in "\n"}))))
        (is (= ["main"] (remote-branches "gobel")))))))

(deftest yes-pushes-every-repo-in-the-summary-s-order-and-opens-draft-prs
  (with-shipped-task
    (fn [{:keys [dir sandbox ship remote-branches summary! gh-calls]}]
      (summary! order-summary)
      (let [r (ship {:in "yes\n"})]
        (is (zero? (:exit r)) (:err r))
        (is (str/includes? (:out r) "the card is in in-review")))
      (testing "both branches are at their remotes, and nothing else is"
        (is (= ["main" "sk/t-ship"] (remote-branches "gobel")))
        (is (= ["main" "sk/t-ship"] (remote-branches "cirdan"))))
      (testing "one draft PR per repo, in the order the summary gave"
        (let [creates (filter #(str/includes? % "pr create") (gh-calls))]
          (is (= 2 (count creates)))
          (is (str/starts-with? (first creates) "gobel ") "gobel first, as the summary said")
          (is (str/starts-with? (second creates) "cirdan "))
          (is (every? #(str/includes? % "--draft") creates) "draft only — swarmkhazad never merges")
          (is (every? #(str/includes? % "--base main --head sk/t-ship") creates))
          (is (every? #(str/includes? % "--title Repoint the exporter and pin the image") creates)
              "the title is goal.md's own heading, not the task id")))
      (testing "the account is resolved from the origin owner, and only once the operator said yes"
        ;; Once, for two repos and five actions: the token is memoised and
        ;; fetched from the actions, so planning never touches the keychain.
        ;; The two refused-confirmation cases above are what prove the timing —
        ;; they end with no gh call at all.
        (is (= 1 (count (filter #(str/includes? % "auth token -u allen-mithra") (gh-calls))))))
      (testing "the PR body carries this repo's goals and nobody else's"
        (let [body (slurp (str (fs/path sandbox "gh.log.body-gobel")))]
          (is (str/includes? body "READY WITH FOLLOW-UPS") "the summary's verdict, so a reviewer starts from it")
          (is (str/includes? body "repoint DEFAULT_UPSTREAMS"))
          (is (str/includes? body "both diffs read clean") "an untagged goal line belongs to every repo")
          (is (not (str/includes? body "pin fastmcp")) "and cirdan's line is cirdan's PR's business")
          (is (str/includes? body "swarmkhazad never merges"))))
      (testing "what was opened is recorded per repo, so the PR loop has somewhere to start"
        (doseq [[repo url] [["gobel" "https://github.com/acme/gobel/pull/1"]
                            ["cirdan" "https://github.com/acme/cirdan/pull/1"]]]
          (let [f (fs/path dir "state" "pr" (str repo ".json"))
                m (json/parse-string (slurp (str f)) true)]
            (is (= repo (:repo m)))
            (is (= "sk/t-ship" (:branch m)))
            (is (= "allen-mithra" (:account m)))
            (is (= url (:url m)))
            (is (= 40 (count (:head m))) "the sha it shipped, so a later commit is visibly newer"))))
      (testing "the card is in its own lane: shipped and finished are different states"
        (is (str/includes? (slurp (str (fs/path dir "state" "board" "tasks.tsv"))) "\tin-review\t"))))))

(deftest a-failure-stops-the-run-where-it-failed
  (with-shipped-task
    (fn [{:keys [dir ship remote-branches summary! gh-calls]}]
      (summary! order-summary)
      (testing "gobel's PR fails, so cirdan is never pushed"
        (let [r (ship {:in "yes\n" :extra-env {"GH_STUB_PR_FAILS" "gobel"}})]
          (is (= 1 (:exit r)))
          (is (str/includes? (:err r) "gh pr create failed for gobel"))
          (is (str/includes? (:err r) "no upstream configured") "the tool's own words, not a summary of them")))
      (is (= ["main" "sk/t-ship"] (remote-branches "gobel")) "gobel's push had already happened")
      (is (= ["main"] (remote-branches "cirdan"))
          "and cirdan's did not — a PR that depends on work nobody has is worse than a stop")
      (is (not (fs/exists? (fs/path dir "state" "pr" "gobel.json"))) "nothing is recorded for a PR that was not opened")
      (testing "re-running continues: the pushed branch is not pushed twice, the PR opens"
        (let [before (count (filter #(str/includes? % "pr create") (gh-calls)))
              r (ship {:in "yes\n"})]
          (is (zero? (:exit r)) (:err r))
          (is (str/includes? (:out r) "already pushed  gobel"))
          (is (= (+ 2 before) (count (filter #(str/includes? % "pr create") (gh-calls))))
              "one create per repo on the continuation, and gobel's failed one is not repeated twice"))
        (is (= ["main" "sk/t-ship"] (remote-branches "cirdan")))))))

(deftest a-failed-push-stops-the-run-before-the-next-repo
  (with-shipped-task
    (fn [{:keys [dir sandbox ship remote-branches summary! gh-calls]}]
      (summary! order-summary)
      ;; A remote that refuses the push: the bare repo is replaced by a file,
      ;; so `git push` fails the way a revoked token or a protected branch
      ;; would — the process exits non-zero and says why.
      (fs/delete-tree (fs/path sandbox "remotes" "gobel.git"))
      (spit (str (fs/path sandbox "remotes" "gobel.git")) "not a repository\n")
      (let [r (ship {:in "yes\n"})]
        (is (= 1 (:exit r)))
        (is (str/includes? (:err r) "push failed for gobel")))
      (is (= ["main"] (remote-branches "cirdan"))
          "the second repo is untouched: continuing past a failed push opens a PR on work nobody has")
      (is (empty? (filter #(str/includes? % "pr create") (gh-calls)))
          "and no PR was opened for the repo whose push failed")
      (is (not (fs/exists? (fs/path dir "state" "pr" "cirdan.json")))))))

(deftest the-order-is-the-summary-s-not-the-repos-file-s
  (with-shipped-task
    (fn [{:keys [ship summary! gh-calls]}]
      ;; repos declares gobel then cirdan; the summary says the other way.
      (summary! (str "at: x\n--- summary ---\n## Merge order\n"
                     "- cirdan — the image pin has to land first\n"
                     "- gobel — its exporter needs that image\n"))
      (is (zero? (:exit (ship {:in "yes\n"}))))
      (let [creates (filter #(str/includes? % "pr create") (gh-calls))]
        (is (= 2 (count creates)))
        (is (str/starts-with? (first creates) "cirdan ")
            "ship follows the summary, not the repos file — two things inferring an order will disagree")
        (is (str/starts-with? (second creates) "gobel "))))))

(deftest the-account-comes-from-the-repo-s-owner
  (with-shipped-task
    (fn [{:keys [sandbox ship summary! gh-calls]}]
      (summary! order-summary)
      ;; An owner the map does not know falls back to the default; a known one
      ;; does not. Both answers have to be visible, or a wrong map is silent.
      (let [gobel (str (fs/path sandbox "src" "gobel"))]
        (git gobel "config" "remote.origin.url" "https://github.com/someone-else/gobel.git")
        (git gobel "config" (str "url." (fs/path sandbox "remotes" "gobel.git") ".insteadOf")
             "https://github.com/someone-else/gobel.git"))
      (let [r (ship {:in "no\n"})]
        (is (str/includes? (:out r) "at someone-else/gobel  as allen-mithra")
            "an unknown owner takes the default account, and says which repo it read that from")
        (is (str/includes? (:out r) "at MithraAI/cirdan  as allen-mithra")))
      (testing "and the map decides, not the default"
        (let [r (ship {:in "no\n" :extra-env {"SWARMKHAZAD_GH_ACCOUNT" "someone-entirely-else"}})]
          (is (str/includes? (:out r) "as someone-entirely-else")
              "SWARMKHAZAD_GH_ACCOUNT overrides both, for a checkout the map has never heard of"))))))

(deftest a-repo-that-already-has-a-pr-is-left-alone
  (with-shipped-task
    (fn [{:keys [dir ship summary! gh-calls]}]
      (summary! order-summary)
      (let [r (ship {:in "yes\n" :extra-env {"GH_STUB_EXISTING" "https://github.com/MithraAI/gobel/pull/9"}})]
        (is (zero? (:exit r)) (:err r))
        (is (str/includes? (:out r) "PR already open"))
        (is (empty? (filter #(str/includes? % "pr create") (gh-calls)))
            "a second run must not open a second PR on the same head"))
      (is (= "https://github.com/MithraAI/gobel/pull/9"
             (:url (json/parse-string (slurp (str (fs/path dir "state" "pr" "gobel.json"))) true)))
          "and the PR it found is what gets recorded"))))

(deftest ship-never-pushes-anything-but-the-task-branch
  (with-shipped-task
    (fn [{:keys [ship summary! gh-calls remote-branches]}]
      (summary! order-summary)
      (is (zero? (:exit (ship {:in "yes\n"}))))
      (testing "the refspec is explicit on both sides"
        ;; The push does not go through gh, so this reads the remote instead:
        ;; main is untouched and the only new ref is the task branch.
        (is (= ["main" "sk/t-ship"] (remote-branches "gobel")))
        (is (not-any? #(str/includes? % "merge") (gh-calls)) "and no merge was ever asked for")))))

(deftest the-two-contracts-that-live-in-two-files
  ;; Both of these are couplings a sandbox run cannot see: they hold between
  ;; two definitions, not between a command and its output.
  (let [form (str "(load-file \"" scripts "/ship.bb\") "
                  "(prn {:asks (clojure.string/includes? summary/system-prompt "
                  ;; The trailing newline matters: without it `Merge ordering`
                  ;; contains `Merge order` and this passes on a renamed heading.
                  "               (str \"## \" summary/merge-order-heading \"\\n\")) "
                  "      :known (with-redefs [ship/owner->account {\"acme\" \"acme-bot\"}] "
                  "               (ship/account-for \"acme\")) "
                  "      :unknown (with-redefs [ship/owner->account {\"acme\" \"acme-bot\"}] "
                  "                 (ship/account-for \"nobody\"))})")
        r (run {} "bb" "-e" form)
        m (read-string (str/trim (:out r)))]
    (testing "the summary prompt asks for the very heading ship parses"
      (is (:asks m) "one def, used in both places — a section nobody writes is a section ship refuses on"))
    (testing "the owner decides the account, and an unknown owner takes the default"
      (is (= "acme-bot" (:known m))
          "pushing as the wrong person is the failure this map exists to prevent")
      (is (= "allen-mithra" (:unknown m))))))

;; ------------------------------------------------------------- the PR loop

(def live-pr
  "One unresolved review thread, one resolved one, an issue comment from the
   reviewer, one from us, a failing check and a passing one."
  (str "{\"data\":{\"repository\":{\"pullRequest\":{"
       "\"headRefOid\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"isDraft\":true,\"state\":\"OPEN\","
       "\"reviewThreads\":{\"nodes\":["
       "{\"id\":\"THREAD_1\",\"isResolved\":false,\"isOutdated\":false,"
       " \"comments\":{\"nodes\":[{\"id\":\"C1\",\"author\":{\"login\":\"a-reviewer\"},"
       "  \"body\":\"This drops the retry. Was that deliberate?\",\"path\":\"exporter.txt\",\"line\":3}]}},"
       "{\"id\":\"THREAD_2\",\"isResolved\":true,\"isOutdated\":false,"
       " \"comments\":{\"nodes\":[{\"id\":\"C2\",\"author\":{\"login\":\"a-reviewer\"},"
       "  \"body\":\"already dealt with\",\"path\":\"x\",\"line\":1}]}}]},"
       "\"comments\":{\"nodes\":["
       "{\"id\":\"IC1\",\"author\":{\"login\":\"a-reviewer\"},\"body\":\"Nice, one question above.\"},"
       "{\"id\":\"IC2\",\"author\":{\"login\":\"allen-mithra\"},\"body\":\"Opened by swarmkhazad.\"}]},"
       "\"commits\":{\"nodes\":[{\"commit\":{\"statusCheckRollup\":{\"contexts\":{\"nodes\":["
       "{\"__typename\":\"CheckRun\",\"name\":\"build\",\"conclusion\":\"FAILURE\","
       " \"detailsUrl\":\"https://example.invalid/run/1\"},"
       "{\"__typename\":\"CheckRun\",\"name\":\"lint\",\"conclusion\":\"SUCCESS\",\"detailsUrl\":null}"
       "]}}}}]}}}}}"))

(def quiet-pr
  (str "{\"data\":{\"repository\":{\"pullRequest\":{\"headRefOid\":\"b\",\"isDraft\":true,"
       "\"state\":\"OPEN\",\"reviewThreads\":{\"nodes\":[]},\"comments\":{\"nodes\":[]},"
       "\"commits\":{\"nodes\":[]}}}}}"))

(defn handoff-lib-files [dir]
  (if (fs/directory? dir) (vec (fs/glob dir "*.handoff")) []))

(defn headers-of [file]
  (into {} (for [line (take-while (complement str/blank?) (str/split-lines (slurp (str file))))
                 :let [[k v] (str/split line #": " 2)]
                 :when (and k v)]
             [k v])))

(defn ship! [{:keys [ship summary!]}]
  (summary! order-summary)
  (is (zero? (:exit (ship {:in "yes\n"})))))

(deftest a-review-comment-and-a-red-check-become-work-for-whoever-last-committed
  (with-shipped-task
    (fn [{:keys [dir poll graphql! inbox deliver!] :as h}]
      (ship! h)
      (graphql! nil live-pr)
      (graphql! "cirdan" quiet-pr)
      (let [r (poll)]
        (is (zero? (:exit r)) (:err r))
        (is (= 3 (count (re-seq #"handoff " (:out r))))
            "the unresolved thread, the reviewer's comment, the failing check — and nothing else")
        (is (not (str/includes? (:out r) "cirdan")) "cirdan's PR has nothing on it"))
      (deliver!)
      (testing "each one is a handoff to the session that last handed off in that repo"
        (let [files (inbox "implement_gobel")]
          (is (= 3 (count files))
              "three queued in the same millisecond, three arrived — the stamp is the filename's uniqueness")
          (is (empty? (inbox "implement_cirdan")))
          (is (empty? (inbox "review_gobel")) "review never handed off, so the diff under review is not its")
          (let [hs (map headers-of files)]
            (is (= #{"pr_comment" "pr_check"} (set (map #(get % "type") hs))))
            (is (every? #(= "gobel" (get % "origin_repo")) hs)
                "the repo it is about, so a session in another repo is never confused by it")
            (is (every? #(str/includes? (get % "pr_url") "/pull/") hs))
            (is (some #(str/includes? (get % "message") "needs an answer") hs))
            (is (some #(str/includes? (get % "message") "check build is failing") hs)))))
      (testing "a resolved thread and our own comment are not work"
        (let [bodies (map #(slurp (str %)) (inbox "implement_gobel"))]
          (is (not-any? #(str/includes? % "already dealt with") bodies))
          (is (not-any? #(str/includes? % "Opened by swarmkhazad") bodies))))
      (testing "the reply and the resolve are runnable, with the thread id filled in"
        (let [body (first (filter #(str/includes? % "THREAD_1")
                                  (map #(slurp (str %)) (inbox "implement_gobel"))))]
          (is (str/includes? body "addPullRequestReviewThreadReply"))
          (is (str/includes? body "resolveReviewThread"))
          (is (str/includes? body "If you disagree, reply with the reason and leave it open.")
              "resolving means the role agreed; a tool that resolved on delivery would agree for it"))))))

(deftest polling-again-does-not-make-the-same-comment-into-work-twice
  (with-shipped-task
    (fn [{:keys [poll graphql! inbox deliver!] :as h}]
      (ship! h)
      (graphql! nil live-pr)
      (graphql! "cirdan" quiet-pr)
      (poll)
      (deliver!)
      (let [r (poll)]
        (is (str/includes? (:out r) "nothing new on the pull requests")))
      (deliver!)
      (is (= 3 (count (inbox "implement_gobel"))) "a poll every 60s must not be a handoff every 60s"))))

(deftest a-check-failing-twice-on-one-commit-escalates-instead-of-waking-anyone
  (with-shipped-task
    (fn [{:keys [dir poll graphql! inbox deliver!] :as h}]
      (ship! h)
      (graphql! nil live-pr)
      (graphql! "cirdan" quiet-pr)
      (poll)
      ;; The pipeline is still red on the same commit, so the check comes round
      ;; again — the state file remembers it failed once already.
      (let [f (fs/path dir "state" "pr" "gobel.seen.json")
            m (json/parse-string (slurp (str f)) true)]
        (spit (str f) (json/generate-string
                       (update m :handled #(vec (remove (fn [h] (str/starts-with? h "check:")) %))))))
      (let [r (poll)]
        (is (str/includes? (:out r) "escalated  gobel  build")))
      (deliver!)
      (is (= 3 (count (inbox "implement_gobel"))) "nobody is woken a second time about the same red commit")
      (let [esc (slurp (str (fs/path dir "escalation.md")))]
        (is (str/includes? esc "[gobel]") "tagged, because a two-repo task's escalation has to say which")
        (is (str/includes? esc "check build has failed 2 times on the same commit"))
        (is (str/includes? esc "nobody is being woken for it any more"))))))

(deftest an-outage-does-nothing-rather-than-reading-silence-as-no-comments
  (with-shipped-task
    (fn [{:keys [dir poll graphql! inbox deliver!] :as h}]
      (ship! h)
      (graphql! nil live-pr)
      (graphql! "cirdan" quiet-pr)
      (let [r (poll {"GH_STUB_API_FAILS" "1"})]
        (is (zero? (:exit r)) "a poll that cannot reach GitHub is not a crash")
        (is (str/includes? (:out r) "could not reach api.github.com")))
      (is (not (fs/exists? (fs/path dir "state" "pr" "gobel.seen.json")))
          "nothing was marked handled — an empty answer read as `no comments` would lose every one of them")
      (is (empty? (handoff-lib-files (fs/path dir "mail" "_system" "outbox")))
          "and nothing was queued")
      (testing "and the comments still arrive once GitHub is back"
        (poll)
        (deliver!)
        (is (= 3 (count (inbox "implement_gobel"))))))))

(deftest each-repo-is-asked-about-its-own-pull-request
  (with-shipped-task
    (fn [{:keys [poll graphql! inbox deliver!] :as h}]
      (ship! h)
      ;; Only cirdan's PR has anything on it. `gh` picks its repo from the
      ;; directory it runs in, so a poller that ran everything from one place
      ;; answered gobel's poll with cirdan's PR and woke the wrong session.
      (graphql! nil quiet-pr)
      (graphql! "cirdan" live-pr)
      (poll)
      (deliver!)
      (is (= 3 (count (inbox "implement_cirdan"))))
      (is (empty? (inbox "implement_gobel")))
      (is (every? #(= "cirdan" (get (headers-of %) "origin_repo")) (inbox "implement_cirdan"))))))

(deftest the-role-that-last-committed-in-that-repo-is-the-one-woken
  (with-shipped-task
    (fn [{:keys [dir graphql! inbox deliver!] :as h}]
      (ship! h)
      (graphql! nil live-pr)
      (graphql! "cirdan" quiet-pr)
      ;; review handed off after implement did. It is the session whose archive,
      ;; verdict and evidence are all about the diff now under review, so the
      ;; comment is its to answer — not the first session the table happens to
      ;; list for that repo.
      (write! (fs/path dir "mail" "review_gobel" "sent"
                       "50_29991231T235959999Z_from_review_gobel_to_run.handoff")
              "id: later\nfrom: review_gobel\nto: run\ntype: git_handoff\n\nlater\n")
      ;; handoffd's own loop does the polling; nothing here calls pr_watch.
      (deliver!)
      (deliver!)
      (is (= 3 (count (inbox "review_gobel")))
          "handoffd polls the PRs itself — the portal's button only skips the wait")
      (is (empty? (inbox "implement_gobel"))))))

(deftest the-daemon-polls-the-prs-on-its-own-loop
  ;; Every other test here drives `handoffd --once`. This one runs the real
  ;; daemon, because the loop is the thing that runs in the field and `--once`
  ;; is only a debugging door into it.
  (with-shipped-task
    (fn [{:keys [dir env graphql! inbox] :as h}]
      (ship! h)
      (graphql! nil live-pr)
      (graphql! "cirdan" quiet-pr)
      (let [proc (process/process {:dir repo-root :extra-env env :out :string :err :string}
                                  "bb" (str (fs/path scripts "handoffd.bb")) "t-ship")
            deadline (+ (System/currentTimeMillis) 60000)]
        (try
          (while (and (empty? (inbox "implement_gobel")) (< (System/currentTimeMillis) deadline))
            (Thread/sleep 200))
          (finally
            (spit (str (fs/path dir "state" "daemon" "stop")) "")
            (deref proc 20000 nil)
            (when (.isAlive (:proc proc)) (.destroy (:proc proc))))))
      (is (= 3 (count (inbox "implement_gobel")))
          "the daemon asked GitHub itself — nothing in the field calls pr_watch by hand")
      (is (str/includes? (slurp (str (fs/path dir "state" "daemon" "handoffd.log"))) "pr handoff")
          "and said so in its own log"))))
