(ns swarmkhazad.mcp-test
  "A task opened through the MCP tool belongs to a project.

   Three tasks — mith-3633, mith-3635, mith-3636 — were opened through this
   tool with no project. Each got the scaffold's lone `implement` role: no
   reviewer, no runner, nothing that ever read the work back. Nothing
   downstream could see it, because a one-role task is exactly what a task that
   MEANT one role looks like.

   So `project` is required at the tool, and `--project` on the CLI underneath
   it takes that project's checkouts, its role lineup and its cloud
   environment. The two halves are tested together because they are one seam:
   the tool's whole job is to turn its arguments into that command."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (str (fs/cwd)))
(def cli (str (fs/path repo-root "scripts" "swarmkhazad.bb")))
(def stub-bb (str (fs/path repo-root "test" "fixtures" "stub-bb.sh")))

(def real-bb
  "The interpreter by absolute path. The stub `bb` this test puts on PATH must
   catch the `open` the SERVER spawns, not the server itself."
  (str (fs/which "bb")))

(def project-roles
  [{:role "implement" :harness "claude" :model "anthropic:claude-opus-5[1m]"}
   {:role "review" :harness "claude" :model "anthropic:claude-opus-5[1m]"}
   {:role "run" :harness "claude" :model "anthropic:claude-haiku-4-5-20251001"}])

(defn with-sandbox
  "A home holding one project `duo` over two checkouts, a stub `bb` on PATH,
   and the two ways in. f gets {:home :a :b :argv :cli :mcp}.

   `:cli` runs `swarmkhazad` for real. `:mcp` starts the server, writes each
   request to its stdin as one line, and returns the responses it wrote — with
   the `open` it decided to spawn recorded rather than run, because a real one
   starts a tmux server and a session per role."
  [f]
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-mcp."})
        home (str (fs/path sandbox "home"))
        stubdir (str (fs/path sandbox "stubbin"))
        argv (str (fs/path sandbox "open-argv.txt"))
        a (str (fs/path sandbox "src" "alpha"))
        b (str (fs/path sandbox "src" "beta"))
        env {"SWARMKHAZAD_HOME" home
             "PATH" (str stubdir ":" (System/getenv "PATH"))
             "SWARMKHAZAD_STUB_BB_REAL" real-bb
             "SWARMKHAZAD_STUB_BB_STOP" "swarmkhazad.bb"
             "SWARMKHAZAD_STUB_BB_ARGV" argv}]
    (try
      (fs/create-dirs stubdir)
      (fs/create-dirs a)
      (fs/create-dirs b)
      (fs/copy stub-bb (fs/path stubdir "bb"))
      (fs/set-posix-file-permissions (fs/path stubdir "bb") "rwxr-xr-x")
      (fs/create-dirs (fs/path home "projects"))
      (spit (str (fs/path home "projects" "duo.edn"))
            (pr-str {:repos [a b] :roles project-roles :cloud-env "ccpool_fixture"}))
      (spit (str (fs/path home "projects" "empty.edn"))
            (pr-str {:repos [] :roles project-roles}))
      (f {:home home :a a :b b :argv argv
          :cli (fn [& args]
                 ;; The real interpreter: `new` spawns nothing, so nothing here
                 ;; needs the stub, and going through it would stop the command
                 ;; under test.
                 (apply process/sh {:continue true :dir repo-root
                                    :extra-env (dissoc env "PATH")}
                        real-bb cli args))
          :mcp (fn [requests]
                 (let [r (process/sh {:in (str/join "\n" (map json/generate-string requests))
                                      :out :string :err :string :continue true
                                      :dir repo-root :extra-env env}
                                     real-bb cli "mcp")]
                   (assoc r :responses
                          (mapv #(json/parse-string % true)
                                (remove str/blank? (str/split-lines (:out r)))))))})
      (finally (fs/delete-tree sandbox)))))

(defn tool-text [response]
  (str/join "\n" (map :text (get-in response [:result :content]))))

(defn call [n args]
  {:jsonrpc "2.0" :id n :method "tools/call"
   :params {:name "add_task" :arguments args}})

(defn roles-of [home task-id]
  (->> (str/split-lines (slurp (str (fs/path home "tasks" task-id "roles"))))
       (remove #(or (str/blank? %) (str/starts-with? % "#")))
       vec))

(defn repos-of [home task-id]
  (->> (str/split-lines (slurp (str (fs/path home "tasks" task-id "repos"))))
       (remove #(or (str/blank? %) (str/starts-with? % "#")))
       vec))

;; ---------------------------------------------------------------- the tool

(deftest the-tool-requires-a-project-and-says-which-ones-exist
  (with-sandbox
    (fn [{:keys [mcp argv]}]
      (let [{:keys [responses]}
            (mcp [{:jsonrpc "2.0" :id 1 :method "tools/list"}
                  (call 2 {:issue_key "MITH-1"})
                  (call 3 {:issue_key "MITH-1" :project "duoo"})
                  (call 4 {:issue_key "MITH-1" :project ""})])
            [listed missing wrong blank] responses]

        (testing "the schema says what a project decides, so a client can choose one"
          ;; `:required` itself is pinned by the gateway's own protocol test.
          (let [tool (first (get-in listed [:result :tools]))]
            (is (str/includes? (get-in tool [:inputSchema :properties :project :description])
                               "checkouts, roles and cloud environment"))
            (is (str/includes? (:description tool) "NARROWS")
                "and `repo` is no longer the field that carries the scope")))

        (testing "and the server refuses anyway, because the caller is a model"
          (doseq [[label r] [["absent" missing] ["misspelt" wrong] ["blank" blank]]]
            (is (true? (get-in r [:result :isError])) label)
            (is (str/includes? (tool-text r) "`project` is required")
                (str label ": the refusal names the field"))
            (is (str/includes? (tool-text r) "Known projects: duo, empty")
                (str label ": and hands back the answer, so the retry is informed"))))

        (testing "a refusal opens nothing"
          ;; The half that matters. A refusal that still ran `open` would be a
          ;; message, not a gate.
          (is (not (fs/exists? argv))))))))

(deftest a-call-that-names-a-project-passes-it-through-to-open
  (with-sandbox
    (fn [{:keys [mcp argv a b]}]
      (testing "the project reaches the command, beside the issue key"
        (mcp [(call 1 {:issue_key "MITH-3437" :project "duo"})])
        ;; The stub records `"$@"`, so the script it was asked to run is the
        ;; first line and the interpreter is not there at all.
        (let [got (str/split-lines (slurp argv))]
          (is (str/ends-with? (first got) "/swarmkhazad.bb"))
          (is (= ["open" "--linear" "MITH-3437" "--project" "duo"] (rest got))
              "no repos named: the task takes the project's, decided below")))

      (testing "a repo subset is passed as the narrowing it is"
        (mcp [(call 1 {:issue_key "MITH-3437" :project "duo" :repo [b]})])
        (let [got (vec (str/split-lines (slurp argv)))]
          (is (= ["--repo" b] (take-last 2 got)))
          (is (some #{"--project"} got) "and the project is still there")))

      (testing "the investigation lane is a flag on a project task, not an escape from one"
        (mcp [(call 1 {:issue_key "MITH-3437" :project "duo" :investigate true :repo [a]})])
        (let [got (set (str/split-lines (slurp argv)))]
          (is (contains? got "--investigate"))
          (is (contains? got "--project"))))

      (testing "an unusable repo is refused before anything is spawned"
        (fs/delete-if-exists argv)
        (let [r (first (:responses (mcp [(call 1 {:issue_key "MITH-3437" :project "duo"
                                                  :repo ["relative/path"]})])))]
          (is (true? (get-in r [:result :isError])))
          (is (str/includes? (tool-text r) "absolute path"))
          (is (not (fs/exists? argv))))))))

;; ---------------------------------------------------------------- the CLI

(deftest new-with-a-project-takes-its-lineup-its-checkouts-and-its-name
  (with-sandbox
    (fn [{:keys [cli home a b]}]
      (testing "the roles are the project's, not the scaffold's lone implement role"
        (let [r (cli "new" "t-full" "--project" "duo")]
          (is (zero? (:exit r)) (str (:err r) (:out r)))
          (is (= ["implement claude task model=anthropic:claude-opus-5[1m]"
                  "review claude task model=anthropic:claude-opus-5[1m]"
                  "run claude task model=anthropic:claude-haiku-4-5-20251001"]
                 (roles-of home "t-full")))
          (is (= [a b] (repos-of home "t-full"))
              "and the checkouts are the project's whole set when none is named")
          (is (= "duo\n" (slurp (str (fs/path home "tasks" "t-full" "project"))))
              "and the task says which project it belongs to — where its cloud environment is read from")
          (is (str/includes? (:out r) "project: duo"))))

      (testing "--repo narrows inside the project"
        (let [r (cli "new" "t-narrow" "--project" "duo" "--repo" b)]
          (is (zero? (:exit r)) (:err r))
          (is (= [b] (repos-of home "t-narrow")))
          (is (= 3 (count (roles-of home "t-narrow"))) "the lineup is not narrowed with it")))

      (testing "--investigate keeps the project but takes the investigation pair"
        ;; A lane and a scope are different questions. The project cannot mean
        ;; "not an investigation", and silently dropping the flag would be the
        ;; alternative.
        (let [r (cli "new" "t-inv" "--project" "duo" "--investigate")
              roles (roles-of home "t-inv")]
          (is (zero? (:exit r)) (:err r))
          (is (str/starts-with? (first roles) "investigate "))
          (is (str/starts-with? (second roles) "run "))
          (is (= "duo\n" (slurp (str (fs/path home "tasks" "t-inv" "project")))))))

      (testing "a checkout the project does not hold is refused, not added"
        (let [outside (str (fs/path home "nope"))
              r (cli "new" "t-outside" "--project" "duo" "--repo" outside)]
          (is (= 1 (:exit r)))
          (is (str/includes? (:err r) "not in project duo"))
          (is (str/includes? (:err r) outside) "and says which one")
          (is (not (fs/exists? (fs/path home "tasks" "t-outside")))
              "and leaves no half-scaffolded folder behind")))

      (testing "a project that does not exist is refused with the ones that do"
        (let [r (cli "new" "t-nope" "--project" "duoo")]
          (is (= 1 (:exit r)))
          (is (str/includes? (:err r) "no such project: duoo"))
          (is (str/includes? (:err r) "known: duo, empty"))))

      (testing "a project holding no checkouts is refused where the fix is named"
        ;; Otherwise it scaffolds, and `prepare` refuses it later with
        ;; `repos declaration is empty` — pointing at the task, not the project.
        (let [r (cli "new" "t-empty" "--project" "empty")]
          (is (= 1 (:exit r)))
          (is (str/includes? (:err r) "holds no checkouts"))))

      (testing "and without a project the door below both levels is unchanged"
        (let [r (cli "new" "t-bare" "--repo" (str (fs/path home "anywhere")))]
          (is (zero? (:exit r)) (:err r))
          (is (= ["implement claude task"] (roles-of home "t-bare")))
          (is (not (fs/exists? (fs/path home "tasks" "t-bare" "project")))))))))
