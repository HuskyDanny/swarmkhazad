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

(deftest sessions-tsv-columns-are-the-ones-the-shim-reads
  (load-file (str (fs/path repo-root "scripts" "task_lib.bb")))
  (is (= 0 (.indexOf @(resolve 'task-lib/sessions-tsv-columns) :session)) "shim.sh matches $1 against the session")
  (is (= 6 (.indexOf @(resolve 'task-lib/sessions-tsv-columns) :model)) "shim.sh reads $7 for the vendor")
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
      (let [wrapper? (resolve 'task-lib/wrapper-shim?)]
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
                                           "(prn (task-lib/harness-candidates \"claude\"))"
                                           "(prn (:path (task-lib/resolve-harness \"claude\")))")))]
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
                                         "(prn (task-lib/resolve-harness \"claude\"))")))]
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
              (str "plain claude task\n"
                   "fast claude task model=kimi\n"
                   "deep claude task model=deepseek --model sonnet\n"
                   ;; `<vendor>:<model-id>`: the vendor half picks the endpoint
                   ;; and the credential, the suffix names the exact model. A
                   ;; vendors.tsv row pins one pair for everyone who picks that
                   ;; vendor, so without this a role can ask for kimi and not
                   ;; for a particular kimi.
                   "exact claude task model=anthropic:claude-opus-5[1m]\n"
                   "pinned claude task model=kimi:moonshotai/kimi-k2.5:exacto\n"))
        (spit (str (fs/path dir "repos")) (str src "\n"))
        (let [result (run {:env env} cli "smoke" id)
              out (:out result)]
          (testing "every role reports OK and sent its note to itself"
            (doseq [role ["plain" "fast" "deep" "exact" "pinned"]]
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
              (is (= (str "task_id=" id ",role=plain,session=plain,repo=fixture") (get e "OTEL_RESOURCE_ATTRIBUTES")))
              (is (not (some #{"--model"} (str/split-lines (slurp (str (fs/path dir "tmp" "launch-plain.argv")))))) "no --model pin for anthropic")))
          (testing "an exact model on the operator's own login: pinned, and still no vendor routing"
            (let [e (env-map (fs/path dir "tmp" "launch-exact.env"))
                  argv (str/split-lines (slurp (str (fs/path dir "tmp" "launch-exact.argv"))))]
              (is (nil? (get e "ANTHROPIC_BASE_URL"))
                  "`anthropic:` is still anthropic — naming a model does not route it anywhere")
              (is (nil? (get e "ANTHROPIC_AUTH_TOKEN")))
              (is (= ["--model" "claude-opus-5[1m]"]
                     (->> argv (drop-while #(not= "--model" %)) (take 2)))
                  "and the model reaches the CLI, which is the whole point of the suffix")))
          (testing "an exact model on a vendor's endpoint: that vendor's URL and token, this role's model"
            (let [e (env-map (fs/path dir "tmp" "launch-pinned.env"))
                  argv (str/split-lines (slurp (str (fs/path dir "tmp" "launch-pinned.argv"))))]
              (is (= "https://openrouter.ai/api" (get e "ANTHROPIC_BASE_URL"))
                  "the vendor half still selects the endpoint")
              (is (= "tok-from-test:openrouter-token" (get e "ANTHROPIC_AUTH_TOKEN"))
                  "and the credential")
              (is (= "moonshotai/kimi-k2.5:exacto" (get e "ANTHROPIC_DEFAULT_OPUS_MODEL"))
                  "but the model is this role's, not the row's `moonshotai/kimi-k3:exacto` — and the id carries a colon of its own, which is why the split is on the FIRST one")
              (is (= "moonshotai/kimi-k2.5:exacto" (get e "ANTHROPIC_DEFAULT_SONNET_MODEL")))
              (is (= "moonshotai/kimi-k2.5" (get e "ANTHROPIC_DEFAULT_HAIKU_MODEL"))
                  "the row's small model is not overridden; nothing else in the row moves either")
              (is (= "1048576" (get e "CLAUDE_CODE_MAX_CONTEXT_TOKENS"))
                  "the context window still comes from the vendor row")
              (is (= ["--model" "moonshotai/kimi-k2.5:exacto"]
                     (->> argv (drop-while #(not= "--model" %)) (take 2))))))
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
              (is (= (str "task_id=" id ",role=fast,session=fast,repo=fixture") (get e "OTEL_RESOURCE_ATTRIBUTES")))
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
        (spit (str (fs/path dir "roles")) "a claude task model=kimi\n")
        (spit (str (fs/path dir "repos")) (str src "\n"))
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
        (spit (str (fs/path dir "roles")) "a claude task\nb claude\n")
        (spit (str (fs/path dir "repos")) (str src "\n"))
        (try
          (run {:env env} cli "open" id)
          (let [cfg (json/parse-string (slurp claude-json))
                projects (get cfg "projects")
                wt-real (str (fs/canonicalize (fs/path dir "worktrees" "fixture")))]
            (is (= true (get-in projects [wt-real "hasTrustDialogAccepted"])) "the repo's worktree is trusted")
            (is (= true (get-in projects ["/somewhere/else" "hasTrustDialogAccepted"])) "existing entries untouched")
            (is (= [] (get-in projects ["/somewhere/else" "allowedTools"])) "existing entry fields untouched")
            (is (= 7 (get cfg "numStartups")) "other top-level keys untouched")
            (is (= 2 (count projects))
                "both roles share the repo's worktree, so trust is seeded once")
            (testing "a second open adds nothing"
              (run {:env env} cli "close" id)
              (run {:env env} cli "open" id)
              (is (= 2 (count (get (json/parse-string (slurp claude-json)) "projects")))))
            (testing "close removes exactly the entries open added"
              (run {:env env} cli "close" id)
              (let [after (get (json/parse-string (slurp claude-json)) "projects")]
                (is (= #{"/somewhere/else"} (set (keys after))))
                (is (= true (get-in after ["/somewhere/else" "hasTrustDialogAccepted"]))))))
          (finally
            (run {:env env :ok? false} cli "close" id)
            (process/sh {:continue true} "tmux" "-S" socket "kill-server")))))))

(deftest a-lane-script-is-a-harness-and-the-task-layers-over-it-without-restating-it
  ;; Base home -> lane -> task. Each layer adds; none restates the one below.
  ;;
  ;; This works because of one measured fact about the CLI (RAN, real binary):
  ;;
  ;;   claude --model claude-haiku-4-5-20251001 --model bogus-model-xyz  -> error
  ;;   claude --model bogus-model-xyz --model claude-haiku-4-5-20251001  -> ok
  ;;
  ;; The LAST occurrence wins. `lane_exec` is
  ;; `exec aws-vault exec dev --duration=8h --server -- claude "$@"`, so a lane
  ;; puts its flags first and appends ours — which is why the task layer can
  ;; override the one flag it must own and inherit everything else.
  ;;
  ;; The same fact is why `--settings` had to stop being passed twice: ours
  ;; REPLACED the lane's file, and every hook the lane installs stopped running.
  (let [sandbox (fs/create-temp-dir {:prefix "swarmkhazad-lane."})
        cc-home (str (fs/path sandbox "cc"))
        home (str (fs/path sandbox "home"))
        src (str (fs/path sandbox "src" "fixture"))
        env {"SWARMKHAZAD_HOME" home "CLAUDE_CONFIG_DIR" cc-home}
        id "t-lane"]
    (try
      (make-source-repo! src)
      ;; the operator's own lane: a launcher script and the settings it loads
      (fs/create-dirs (fs/path cc-home "scripts"))
      (fs/create-dirs (fs/path cc-home "auto"))
      (write! (fs/path cc-home "scripts" "cc-auto.sh") "#!/bin/bash\nexec claude \"$@\"\n")
      (fs/set-posix-file-permissions (fs/path cc-home "scripts" "cc-auto.sh") "rwxr-xr-x")
      (write! (fs/path cc-home "auto" "settings.json")
              (json/generate-string
               {:hooks {:SessionStart [{:hooks [{:type "command" :command "/lane/persona.sh"}]}]
                        :PreCompact [{:hooks [{:type "command" :command "/lane/compact.sh"}]}]}
                :env {:LANE_ONLY "1"}}))
      (run {:env env} cli "new" id "--repo" src)
      (let [dir (fs/path home "tasks" id)]
        (spit (str (fs/path dir "roles"))
              (str "implement cc_auto task model=anthropic:claude-opus-5[1m]\n"
                   "review claude task model=deepseek\n"))
        (spit (str (fs/path dir "repos")) (str src "\n"))
        (run {:env env} cli "prepare" id)
        ;; `open` writes these; this test only needs argv, so it stands them in.
        (doseq [sess ["implement" "review"]]
          (write! (fs/path dir "prompts" (str sess ".md")) "role prompt\n"))
        (testing "a lane name validates as a harness and survives into sessions.tsv"
          (let [rows (str/split-lines (slurp (str (fs/path dir "state" "sessions.tsv"))))
                cols (fn [n] (str/split (some #(when (str/starts-with? % n) %) rows) #"\t" -1))]
            (is (= "cc_auto" (nth (cols "implement") 4)) (str rows))
            (is (= "anthropic:claude-opus-5[1m]" (nth (cols "implement") 6)))
            (is (= "claude" (nth (cols "review") 4)) "and a bare CLI still resolves as itself")))
        (testing "the task contributes only its own settings — the CLI merges the lane's"
          (let [argv (run {:env env} "bb" "-e"
                          (str "(load-file \"" (str (fs/path repo-root "scripts")) "/swarm_lib.bb\") "
                               "(let [ctx (task-lib/task-ctx \"" id "\") "
                               "      rows (task-lib/read-sessions-tsv ctx) "
                               "      row (first (filter #(= \"implement\" (:session %)) rows))] "
                               "  (prn (swarm-lib/harness-argv ctx row \"/bin/cc-auto.sh\" "
                               "        (str (:prompts-dir ctx) \"/implement.md\") :interactive nil)))"))
                argv (read-string (str/trim (:out argv)))
                settings-path (second (drop-while #(not= "--settings" %) argv))
                merged (json/parse-string (slurp settings-path) true)]
            ;; RAN against the real CLI, which is why this file holds only our
            ;; hooks rather than a copy of the lane's:
            ;;
            ;;   claude --settings A --settings B   BOTH files' SessionStart
            ;;                                      hooks fire, either order
            ;;
            ;; and the base layer is not replaced either — with `--settings`
            ;; passed, ~/.claude/settings.json's own PreToolUse guard still
            ;; refused `sudo`. Settings LAYER. Copying the lane's hooks in here
            ;; would add nothing and would make this file claim hooks that are
            ;; not its own.
            (testing "our own hooks are here"
              (is (str/includes? (str (mapv :command (mapcat :hooks (:SessionStart (:hooks merged)))))
                                 "run-contract.sh")
                  "the truth lock")
              (is (seq (:Stop (:hooks merged))) "and the goal judge"))
            (testing "and the lane's are NOT copied in — the CLI merges its file, this one does not restate it"
              (is (= 1 (count (:SessionStart (:hooks merged))))
                  "one entry, ours")
              (is (not (str/includes? (str merged) "/lane/persona.sh")))
              (is (nil? (:PreCompact (:hooks merged)))
                  "an event only the lane has stays only the lane's")
              (is (nil? (:env merged))
                  "and nothing else of the lane's is duplicated here either"))
            (testing "the permission mode is the lane's, not restated"
              ;; cc_auto bypasses and cc_control screens. Passing ours would
              ;; make picking between them meaningless.
              (is (not (some #{"--permission-mode"} argv)) (str argv)))
            (testing "but the role's own prompt still wins — a lane's prompt describes its own delivery arc, not this task's"
              (is (some #{"--append-system-prompt-file"} argv)))))
        (testing "a bare claude role is unchanged: its own settings, and the permission mode stated"
          (let [argv (run {:env env} "bb" "-e"
                          (str "(load-file \"" (str (fs/path repo-root "scripts")) "/swarm_lib.bb\") "
                               "(let [ctx (task-lib/task-ctx \"" id "\") "
                               "      rows (task-lib/read-sessions-tsv ctx) "
                               "      row (first (filter #(= \"review\" (:session %)) rows))] "
                               "  (prn (swarm-lib/harness-argv ctx row \"/bin/claude\" "
                               "        (str (:prompts-dir ctx) \"/review.md\") :interactive nil)))"))
                argv (read-string (str/trim (:out argv)))
                settings (json/parse-string (slurp (second (drop-while #(not= "--settings" %) argv))) true)]
            (is (= ["--permission-mode" "bypassPermissions"]
                   (->> argv (drop-while #(not= "--permission-mode" %)) (take 2))))
            (is (nil? (:PreCompact (:hooks settings)))
                "and a bare claude role's file is the same file — nothing lane-shaped in it"))))
      (finally (fs/delete-tree sandbox)))))
