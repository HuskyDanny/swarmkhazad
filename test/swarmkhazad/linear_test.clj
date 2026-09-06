(ns swarmkhazad.linear-test
  "Linear intake: an issue key in, a goal.md skeleton out. The fetch is a
   headless `claude -p` against the operator's Linear MCP server, so the tests
   put a stub `claude` on PATH and pin the argv it is called with, the shape it
   must return, and every way the fetch can fail."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (str (fs/cwd)))
(def scripts (str (fs/path repo-root "scripts")))
(def cli (str (fs/path scripts "swarmkhazad.bb")))
(def stub (str (fs/path repo-root "test" "fixtures" "stub-linear-claude.sh")))

(def real-issue
  {:found true
   :identifier "MITH-3437"
   :title "nenya: promote bounce_type to a stored, indexed column"
   :description "The bundle withheld chart 304.\n\n## Done when\n\n* EXPLAIN shows an index range scan.\n* Chart 304 renders under 1 s warm."
   :url "https://linear.app/mithra/issue/MITH-3437/nenya-promote"
   :state "Backlog"
   :acceptance ["EXPLAIN shows an index range scan." "Chart 304 renders under 1 s warm."]})

(defn with-sandbox
  "A home, a stub claude on PATH, and a runner. f gets {:home :run :argv-file}."
  [f]
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-linear."})
        home (str (fs/path sandbox "home"))
        stubdir (str (fs/path sandbox "stubbin"))
        argv-file (str (fs/path sandbox "argv.txt"))]
    (try
      (fs/create-dirs stubdir)
      (fs/copy stub (fs/path stubdir "claude"))
      (fs/set-posix-file-permissions (fs/path stubdir "claude") "rwxr-xr-x")
      (f {:home home
          :argv-file argv-file
          :run (fn [{:keys [issue mode]} & args]
                 (apply process/sh
                        {:continue true :dir repo-root
                         :extra-env (cond-> {"SWARMKHAZAD_HOME" home
                                             "PATH" (str stubdir ":" (System/getenv "PATH"))
                                             "SWARMKHAZAD_STUB_ARGV" argv-file}
                                      issue (assoc "SWARMKHAZAD_STUB_ISSUE" (json/generate-string issue))
                                      mode (assoc "SWARMKHAZAD_STUB_MODE" mode))}
                        "bb" cli args))})
      (finally (fs/delete-tree sandbox)))))

(deftest new-with-linear-writes-the-goal-from-the-issue-and-one-implement-role
  (with-sandbox
    (fn [{:keys [home run argv-file]}]
      (let [r (run {:issue real-issue} "new" "mith-3437" "--linear" "MITH-3437")
            dir (fs/path home "tasks" "mith-3437")
            goal (slurp (str (fs/path dir "goal.md")))]
        (is (zero? (:exit r)) (:err r))
        (is (str/includes? (:out r) "linear: MITH-3437 nenya: promote"))
        (testing "the fetch asked for exactly one issue, one server, one tool, schema-forced"
          (let [argv (str/split-lines (slurp argv-file))]
            (is (some #{"-p"} argv))
            (is (some #{"--json-schema"} argv))
            (is (= "mcp__linear-server__get_issue" (second (drop-while #(not= "--allowed-tools" %) argv)))
                "one tool: the fetch cannot do anything but read the issue")
            (is (some #{"--strict-mcp-config"} argv) "no other MCP server is reachable from the fetch")
            (is (str/includes? (str/join " " argv) "mcp.linear.app"))
            (is (str/includes? (last argv) "MITH-3437"))))
        (testing "the heading, provenance line and state come from the issue"
          (is (str/includes? goal "# mith-3437 — nenya: promote bounce_type to a stored, indexed column"))
          (is (str/includes? goal "From Linear MITH-3437 (Backlog)")))
        (testing "each acceptance line becomes one unticked Goal checkbox, verbatim"
          (is (str/includes? goal "## Goal\n- [ ] implement — EXPLAIN shows an index range scan.\n- [ ] implement — Chart 304 renders under 1 s warm.\n"))
          (is (not (str/includes? goal "- [x]"))))
        (testing "the URL is the first hint and the description is kept whole"
          (is (str/includes? goal "- https://linear.app/mithra/issue/MITH-3437/nenya-promote — the issue"))
          (is (str/includes? goal "## From the issue\n\nThe bundle withheld chart 304."))
          (is (str/includes? goal "* Chart 304 renders under 1 s warm.")))
        (testing "Not-goal is left for the operator, never invented"
          (is (str/includes? goal "## Not-goal\n- <what this issue deliberately does not cover")))
        (testing "roles is one implement role; metrics.md is still the template"
          (is (= "implement claude none task" (last (str/split-lines (slurp (str (fs/path dir "roles")))))))
          (is (str/includes? (slurp (str (fs/path dir "metrics.md"))) "## Quantitative")))))))

(deftest an-issue-without-acceptance-lines-gets-one-checkbox-naming-the-title
  (with-sandbox
    (fn [{:keys [home run]}]
      (let [r (run {:issue (assoc real-issue :acceptance [] :description "" :state "")} "new" "t" "--linear" "MITH-3437")
            goal (slurp (str (fs/path home "tasks" "t" "goal.md")))]
        (is (zero? (:exit r)) (:err r))
        (is (str/includes? goal "## Goal\n- [ ] implement — nenya: promote bounce_type to a stored, indexed column\n"))
        (is (str/includes? goal "(the issue has no description)"))
        (is (not (str/includes? goal "()")) "an issue with no state gets no empty parentheses")))))

(deftest a-fetch-that-does-not-return-the-issue-fails-and-leaves-no-task-behind
  (doseq [[label opts expected]
          [["the issue does not exist"
            {:issue {:found false :identifier "MITH-9" :title "Could not find referenced Issue" :description "" :url "" :acceptance []}}
            "was not returned: Could not find referenced Issue"]
           ["the fetch process fails" {:mode "fail"} "Linear fetch failed"]
           ["the fetch returns no JSON" {:mode "garbage"} "returned no issue"]
           ["another issue comes back"
            {:issue (assoc real-issue :identifier "MITH-1")}
            "returned MITH-1 when asked for MITH-3437"]]]
    (with-sandbox
      (fn [{:keys [home run]}]
        (let [r (run opts "new" "mith-3437" "--linear" "MITH-3437")]
          (is (= 1 (:exit r)) label)
          (is (str/includes? (:err r) expected) (str label ": " (:err r)))
          (is (not (fs/exists? (fs/path home "tasks" "mith-3437")))
              (str label ": a half-created task would block the retry with \"task already exists\"")))))))

(deftest the-issue-key-is-validated-before-anything-runs
  (with-sandbox
    (fn [{:keys [home run argv-file]}]
      (doseq [bad ["not-a-key" "MITH" "3437" "MITH-3437; rm -rf /" "../etc"]]
        (let [r (run {} "new" "t" "--linear" bad)]
          (is (= 1 (:exit r)) bad)
          (is (str/includes? (:err r) "not a Linear issue key") bad)
          (is (not (fs/exists? argv-file)) (str bad ": no fetch was attempted"))
          (is (not (fs/exists? (fs/path home "tasks" "t"))) bad))))))

(deftest open-linear-derives-the-task-id-from-the-key-and-does-not-touch-an-existing-task
  (with-sandbox
    (fn [{:keys [home run]}]
      (testing "open --linear with no id scaffolds under the lowercased key"
        ;; `open` then tries to spawn a swarm; with no repo and a stub claude the
        ;; scaffolding still happens first, which is what this pins.
        (run {:issue real-issue} "open" "--linear" "MITH-3437")
        (let [dir (fs/path home "tasks" "mith-3437")]
          (is (fs/directory? dir))
          (is (str/includes? (slurp (str (fs/path dir "goal.md"))) "nenya: promote"))))
      (testing "a second open --linear opens the existing task and leaves the edited goal alone"
        (let [goal (fs/path home "tasks" "mith-3437" "goal.md")]
          (fs/set-posix-file-permissions goal "rw-r--r--")
          (spit (str goal) "# edited by hand\n")
          (let [r (run {:issue real-issue} "open" "--linear" "MITH-3437")]
            (is (not (str/includes? (str (:err r)) "task already exists"))
                "re-opening must not be refused as a duplicate scaffold"))
          (is (= "# edited by hand\n" (slurp (str goal)))
              "intake must never overwrite a goal someone has already worked on")))
      (run {} "close" "mith-3437"))))

(deftest the-goal-skeleton-is-built-without-any-network
  (let [goal-md (fn [issue]
                  (:out (process/sh {:dir repo-root}
                                    "bb" "-e" (str "(load-file \"" scripts "/linear_intake.bb\") "
                                                   "(print (linear-intake/goal-md \"t-1\" " (pr-str issue) "))"))))]
    (testing "a description that is only whitespace still reads as absent"
      (is (str/includes? (goal-md (assoc real-issue :description "   \n  ")) "(the issue has no description)")))
    (testing "acceptance lines are trimmed but not otherwise touched"
      (is (str/includes? (goal-md (assoc real-issue :acceptance ["  keeps its **markdown**  "]))
                         "- [ ] implement — keeps its **markdown**\n")))))
