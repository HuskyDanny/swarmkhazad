(ns swarmkhazad.shim-test
  "bin/<harness> shims: per-role model env from roles.tsv + vendors.tsv, the
   real binary from harnesses.tsv, OTEL tags; trust seeding and removal; the
   smoke command."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (str (fs/cwd)))
(def cli (str (fs/path repo-root "scripts" "swarmkhazad.bb")))
(def stub (str (fs/path repo-root "test" "fixtures" "stub-claude.sh")))

(defn run [{:keys [dir env ok?]} & args]
  (let [result (apply process/sh (concat [{:continue true :dir (str (or dir repo-root)) :extra-env (or env {})}] args))]
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

(defn env-map [file]
  (into {} (for [line (str/split-lines (slurp (str file)))
                 :let [[k v] (str/split line #"=" 2)]
                 :when k]
             [k (or v "")])))

(defn executable! [path text]
  (spit (str path) text)
  (fs/set-posix-file-permissions path "rwxr-xr-x"))

(defn with-sandbox [f]
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-shim."})
        home (str (fs/path sandbox "home"))
        src (str (fs/path sandbox "src" "fixture"))
        stubdir (str (fs/path sandbox "stubbin"))
        claude-json (str (fs/path sandbox "claude.json"))
        env {"SWARMKHAZAD_HOME" home
             "SWARMKHAZAD_CLAUDE_JSON" claude-json
             ;; The operator's shell may carry a vendor routing (a cc_alt session, a
             ;; local model router); an anthropic role must not inherit it.
             "ANTHROPIC_BASE_URL" "http://example.invalid:1"
             "ANTHROPIC_DEFAULT_OPUS_MODEL" "leaked/from-parent"
             "PATH" (str stubdir ":" (System/getenv "PATH"))}]
    (try
      (make-source-repo! src)
      (fs/create-dirs stubdir)
      (fs/copy stub (fs/path stubdir "claude"))
      (fs/set-posix-file-permissions (fs/path stubdir "claude") "rwxr-xr-x")
      ;; A keychain stand-in on PATH: `security find-generic-password -s <svc> -w`.
      (executable! (fs/path stubdir "security") "#!/bin/bash\nsvc=\"\"\nwhile [ $# -gt 0 ]; do case \"$1\" in -s) svc=\"$2\"; shift;; esac; shift; done\necho \"tok-from-test:$svc\"\n")
      (spit claude-json (json/generate-string {"projects" {"/somewhere/else" {"hasTrustDialogAccepted" true "allowedTools" []}}
                                               "numStartups" 7}))
      (f {:sandbox (str sandbox) :home home :src src :env env :claude-json claude-json :stubdir stubdir})
      (finally
        (fs/delete-tree sandbox)))))

(deftest roles-tsv-model-column-is-the-sixth-the-shim-reads
  (load-file (str (fs/path repo-root "scripts" "task_lib.bb")))
  (is (= 5 (.indexOf @(resolve 'task-lib/roles-tsv-columns) :model)) "shim.sh reads $6 for the vendor")
  (is (= #{"anthropic" "glm" "kimi" "deepseek" "qwen"} @(resolve 'task-lib/known-vendors)) "vendors.tsv rows plus anthropic")
  (is (= "moonshotai/kimi-k3:exacto" (:model-main (get ((resolve 'task-lib/read-vendors)) "kimi"))))
  (is (= "" (:ctx-tokens (get ((resolve 'task-lib/read-vendors)) "qwen"))) "an empty last column survives"))

(deftest a-wrapper-shim-on-path-is-skipped-not-pinned
  (load-file (str (fs/path repo-root "scripts" "swarm_lib.bb")))
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-wrapper."})
        ;; The shape cmux puts first on PATH: $TMPDIR/cmux-cli-shims/<uuid>/claude.
        ;; It forwards our argv after rewriting it, and `--settings <path>` comes
        ;; back inline — the exec dies with "Argument list too long" and the role
        ;; relaunches forever, so pinning it is worse than not resolving at all.
        shimdir (fs/path sandbox "cmux-cli-shims" "abc-123")
        realdir (fs/path sandbox "bin")]
    (try
      (fs/create-dirs shimdir)
      (fs/create-dirs realdir)
      (executable! (fs/path shimdir "claude") "#!/bin/bash\necho wrapper\n")
      (executable! (fs/path realdir "claude") "#!/bin/bash\necho real\n")
      (let [wrapper? (resolve 'swarm-lib/wrapper-shim?)]
        (is (@wrapper? (str (fs/path shimdir "claude"))) "a cmux-cli-shims path is a wrapper")
        (is (not (@wrapper? (str (fs/path realdir "claude")))) "an ordinary bin directory is not")
        (is (not (@wrapper? (str (fs/path sandbox "stubbin" "claude"))))
            "and neither is a stub in a temp dir — the first version of this test rejected those and sent the smoke suite at the live vendors")
        (let [path (str shimdir ":" realdir)]
          ;; harness-candidates reads PATH from the environment, so drive it in a
          ;; child bb with the PATH under test rather than mutating this process.
          (let [out (:out (process/sh {:continue true
                                       :extra-env {"PATH" path}
                                       :dir repo-root}
                                      "bb" "-e"
                                      (str "(load-file \"scripts/swarm_lib.bb\")"
                                           "(prn (swarm-lib/harness-candidates \"claude\"))"
                                           "(prn (:path (swarm-lib/resolve-harness \"claude\")))")))]
            (is (str/includes? out "cmux-cli-shims") "the wrapper is a candidate")
            (is (str/includes? out (str (fs/path realdir "claude"))) "and so is the real binary")
            (is (str/ends-with? (str/trim out) (str "\"" (fs/path realdir "claude") "\""))
                "but the one chosen is the real binary, not the wrapper that came first")))
        (let [out (:out (process/sh {:continue true
                                     :extra-env {"PATH" (str shimdir)
                                                 "SWARMKHAZAD_HARNESS_CLAUDE" (str (fs/path realdir "claude"))}
                                     :dir repo-root}
                                    "bb" "-e"
                                    (str "(load-file \"scripts/swarm_lib.bb\")"
                                         "(prn (swarm-lib/resolve-harness \"claude\"))")))]
          (is (str/includes? out ":pinned true") "SWARMKHAZAD_HARNESS_<NAME> wins outright")
          (is (str/includes? out (str (fs/path realdir "claude")))))
        (let [r (process/sh {:continue true
                             :extra-env {"PATH" (str shimdir)}
                             :dir repo-root}
                            "bb" "-e"
                            (str "(load-file \"scripts/swarm_lib.bb\")"
                                 "(swarm-lib/resolve-harnesses! (task-lib/task-ctx \"t-none\") [{:harness \"claude\"}])"))]
          (is (not (zero? (:exit r))) "nothing but wrappers is a failure at open, not a swarm that loops")
          (is (str/includes? (str (:err r)) "resolves only to wrapper shims"))
          (is (str/includes? (str (:err r)) "SWARMKHAZAD_HARNESS_CLAUDE")
              "and the message says how to pin the real one")))
      (finally
        (fs/delete-tree sandbox)))))

(deftest smoke-runs-every-role-through-its-shim-with-its-own-model-env
  (with-sandbox
    (fn [{:keys [env src home claude-json]}]
      (let [id "t-smoke"
            _ (run {:env env} cli "new" id "--repo" src)
            dir (fs/path home "tasks" id)]
        (spit (str (fs/path dir "roles"))
              (str "plain claude " src " task\n"
                   "fast claude " src " task model=kimi\n"
                   "deep claude " src " task model=deepseek --model sonnet\n"))
        (let [result (run {:env env} cli "smoke" id)
              out (:out result)]
          (testing "every role reports OK and sent its note to itself"
            (doseq [role ["plain" "fast" "deep"]]
              (is (re-find (re-pattern (str "(?m)^OK +" role " ")) out) (str role ": " out))
              (is (str/includes? (slurp (str (fs/path dir "tmp" (str "smoke-" role ".out")))) "HANDOFF QUEUED") "the stub ran swarm_handoff.bb"))
            (is (empty? (fs/glob (fs/path dir "mail") "**/outbox/*.handoff")) "smoke notes are removed so a later open does not deliver them"))
          (testing "the shims and the vendor table exist; harnesses.tsv points at the recorded real binary"
            (doseq [h ["claude" "codex" "grok" "copilot"]]
              (is (fs/executable? (fs/path dir "bin" h)) h))
            (is (fs/regular-file? (fs/path dir "state" "vendors.tsv")))
            (is (str/includes? (slurp (str (fs/path dir "state" "harnesses.tsv"))) "stubbin/claude")))
          (testing "an anthropic role gets no vendor env, but does get telemetry tags"
            (let [e (env-map (fs/path dir "tmp" "launch-plain.env"))]
              (is (nil? (get e "ANTHROPIC_BASE_URL")) "the parent shell's routing was dropped")
              (is (nil? (get e "ANTHROPIC_DEFAULT_OPUS_MODEL")) "the parent shell's model remap was dropped")
              (is (nil? (get e "ANTHROPIC_AUTH_TOKEN")))
              (is (= "1" (get e "CLAUDE_CODE_ENABLE_TELEMETRY")))
              (is (= "otlp" (get e "OTEL_METRICS_EXPORTER")))
              (is (= "http/protobuf" (get e "OTEL_EXPORTER_OTLP_PROTOCOL")))
              (is (= "http://127.0.0.1:8428/opentelemetry" (get e "OTEL_EXPORTER_OTLP_ENDPOINT")))
              (is (= (str "task_id=" id ",role=plain") (get e "OTEL_RESOURCE_ATTRIBUTES")))
              (is (not (some #{"--model"} (str/split-lines (slurp (str (fs/path dir "tmp" "launch-plain.argv")))))) "no --model pin for anthropic")))
          (testing "a kimi role gets the cc_alt env and a --model pin, and the run reports that model"
            (let [e (env-map (fs/path dir "tmp" "launch-fast.env"))
                  argv (str/split-lines (slurp (str (fs/path dir "tmp" "launch-fast.argv"))))]
              (is (= "https://openrouter.ai/api" (get e "ANTHROPIC_BASE_URL")))
              (is (= "tok-from-test:openrouter-token" (get e "ANTHROPIC_AUTH_TOKEN")) "looked up by the vendor's keychain service name")
              (is (= "" (get e "ANTHROPIC_API_KEY")) "empty-but-set, never unset")
              (is (= "moonshotai/kimi-k3:exacto" (get e "ANTHROPIC_DEFAULT_OPUS_MODEL")))
              (is (= "moonshotai/kimi-k2.5" (get e "ANTHROPIC_DEFAULT_HAIKU_MODEL")))
              (is (= "1048576" (get e "CLAUDE_CODE_MAX_CONTEXT_TOKENS")))
              (is (= "1" (get e "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC")))
              (is (= (str "task_id=" id ",role=fast") (get e "OTEL_RESOURCE_ATTRIBUTES")))
              (is (= ["--model" "moonshotai/kimi-k3:exacto"] (take 2 argv)) "the pin comes first so declared args can still override")
              (is (str/includes? out "used=moonshotai/kimi-k3:exacto"))))
          (testing "a deepseek role with declared extra args keeps both the pin and the args"
            (let [e (env-map (fs/path dir "tmp" "launch-deep.env"))
                  argv (str/split-lines (slurp (str (fs/path dir "tmp" "launch-deep.argv"))))]
              (is (= "deepseek/deepseek-v4-flash-vision-exp" (get e "ANTHROPIC_DEFAULT_OPUS_MODEL")))
              (is (= "deepseek/deepseek-v4-flash" (get e "ANTHROPIC_DEFAULT_HAIKU_MODEL")))
              (is (= "1048576" (get e "CLAUDE_CODE_MAX_CONTEXT_TOKENS")))
              (is (= ["--model" "deepseek/deepseek-v4-flash-vision-exp"] (take 2 argv)))
              (is (str/includes? out "used=deepseek/deepseek-v4-flash-vision-exp"))))
          (testing "smoke does not seed trust or start tmux; that is open's job"
            (is (= 1 (count (get (json/parse-string (slurp claude-json)) "projects"))))
            (is (not (fs/exists? (fs/path dir "state" "tmux-socket"))))))))))

(deftest smoke-reports-a-role-whose-shim-cannot-launch-or-answers-with-another-model
  (with-sandbox
    (fn [{:keys [env src home stubdir]}]
      (let [id "t-smoke-bad"
            _ (run {:env env} cli "new" id "--repo" src)
            dir (fs/path home "tasks" id)]
        (spit (str (fs/path dir "roles")) (str "a claude " src " task model=kimi\n"))
        (testing "a run that answers with some other model is a collision, not a pass"
          (let [result (run {:env (assoc env "SWARMKHAZAD_STUB_MODEL" "claude-opus-5") :ok? false} cli "smoke" id)]
            (is (not= 0 (:exit result)))
            (is (re-find #"(?m)^FAIL +a .*note=sent.*used=claude-opus-5.*expected=moonshotai/kimi-k3:exacto" (:out result)) (:out result))))
        (testing "no token in the keychain: the shim refuses and smoke reports it, not hangs or passes"
          (executable! (fs/path stubdir "security") "#!/bin/bash\nexit 44\n")
          (let [result (run {:env env :ok? false} cli "smoke" id)]
            (is (not= 0 (:exit result)))
            (is (re-find #"(?m)^FAIL +a .*note=none" (:out result)) (:out result))
            (is (str/includes? (:out result) "no keychain token for service 'openrouter-token'") (:out result))))))))

(deftest open-seeds-folder-trust-for-claude-worktrees-and-close-removes-it
  (with-sandbox
    (fn [{:keys [env src home claude-json]}]
      (let [id "t-trust"
            _ (run {:env env} cli "new" id "--repo" src)
            dir (fs/path home "tasks" id)
            socket (str "/tmp/swarmkhazad-" (System/getProperty "user.name") "/" id ".sock")]
        (spit (str (fs/path dir "roles")) (str "a claude " src " task\nb claude none\n"))
        (try
          (run {:env env} cli "open" id)
          (let [cfg (json/parse-string (slurp claude-json))
                projects (get cfg "projects")
                a-real (str (fs/canonicalize (fs/path dir "worktrees" "a")))
                task-real (str (fs/canonicalize dir))]
            (is (= true (get-in projects [a-real "hasTrustDialogAccepted"])) "role a's worktree is trusted")
            (is (= true (get-in projects [task-real "hasTrustDialogAccepted"])) "a repo-less role works in the task folder, which is trusted too")
            (is (= true (get-in projects ["/somewhere/else" "hasTrustDialogAccepted"])) "existing entries untouched")
            (is (= [] (get-in projects ["/somewhere/else" "allowedTools"])) "existing entry fields untouched")
            (is (= 7 (get cfg "numStartups")) "other top-level keys untouched")
            (is (= 3 (count projects)))
            (testing "a second open adds nothing"
              (run {:env env} cli "close" id)
              (run {:env env} cli "open" id)
              (is (= 3 (count (get (json/parse-string (slurp claude-json)) "projects")))))
            (testing "close removes exactly the entries open added"
              (run {:env env} cli "close" id)
              (let [after (get (json/parse-string (slurp claude-json)) "projects")]
                (is (= #{"/somewhere/else"} (set (keys after))))
                (is (= true (get-in after ["/somewhere/else" "hasTrustDialogAccepted"]))))))
          (finally
            (run {:env env :ok? false} cli "close" id)
            (process/sh {:continue true} "tmux" "-S" socket "kill-server")))))))
