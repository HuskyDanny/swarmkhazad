(ns swarmkhazad.schema-test
  "One definition per file format. Every TSV in this repo is written by one
   function and parsed by another, and the column order is the contract between
   them — so it is a named def, never a literal in both places.

   `board_lib` had the same five keys spelled out twice, six lines apart: a
   vector in the reader's `zipmap` and a second vector in the writer's
   `str/join`. Nothing tied them, and the way that fails is silent. `zipmap`
   drops a column the writer added and pads a column it dropped with nil, so a
   card reads back with the wrong lane and nothing anywhere errors — the swarm
   moves a task to a stage nobody sent it to.

   This is the whole brittleness gate: not a coverage number, a count of
   schemas written down more than once. It is at zero, and this test is what
   keeps it there."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def scripts (fs/path (fs/cwd) "scripts"))

(defn script-files []
  ;; `**.bb`, not `**/*.bb`. Java's glob requires the separator, so
  ;; `**/*.bb` matches nothing at all in a directory whose .bb files are
  ;; direct children — which is every file in scripts/. The first version of
  ;; this check used it, scanned zero files, and passed: restoring the exact
  ;; inline literal it exists to forbid did not fail it. The regex was right;
  ;; the file list was empty. RAN — `**/*.bb` 0 files, `**.bb` 21.
  (sort (map str (fs/glob scripts "**.bb"))))

(defn offenders
  "Every line in `scripts/` that spells a column list inline rather than
   naming one. Two shapes, because a TSV contract has two ends:

     (zipmap [:a :b :c] ...)              a reader mapping columns to keys
     (str/join \"\\t\" [(:a r) (:b r)])     a writer laying them back out

   Both are matched on a literal `[` followed by a keyword or a keyword
   accessor, so prose that merely mentions `zipmap` does not count."
  []
  (for [f (script-files)
        [n line] (map-indexed (fn [i l] [(inc i) l]) (str/split-lines (slurp f)))
        :let [hit (cond
                    (re-find #"zipmap\s+\[:" line) "zipmap with an inline column vector"
                    (re-find #"join\s+\"\\t\"\s+\[[:(]" line) "tab-join of an inline column vector"
                    :else nil)]
        :when hit]
    {:file f :line n :why hit :text (str/trim line)}))

(deftest a-file-format-is-defined-once
  (testing "no column list is written inline — each names a *-columns def"
    (let [bad (offenders)]
      (is (empty? bad)
          (str "column lists spelled inline instead of named:\n"
               (str/join "\n" (for [{:keys [file line why text]} bad]
                                (str "  " file ":" line "  " why "\n      " text)))
               "\n\nDefine the order once, as with `sessions-tsv-columns`"
               " (scripts/task_lib.bb) or `tasks-tsv-columns` (scripts/board_lib.bb),"
               " and have both the reader and the writer map over it.")))))

(deftest the-check-can-see-an-offender
  ;; The file list first, because that is the half that was broken. A gate
  ;; reading zero files reports zero offenders and looks identical to a clean
  ;; repo.
  (testing "there are files to check, and board_lib is among them"
    (let [files (script-files)]
      (is (> (count files) 15) "scripts/ holds ~21 .bb files; a short list means the glob is wrong")
      (is (some #(str/ends-with? % "board_lib.bb") files))
      (is (some #(str/ends-with? % "task_lib.bb") files))))
  ;; A gate at zero that would also read zero on a repo full of duplicated
  ;; schemas is not a gate. The pattern above is asserted against the exact
  ;; shape board_lib had before it was fixed, so a regex that stops matching
  ;; fails here rather than passing quietly forever.
  (testing "the reader shape board_lib actually had"
    (is (re-find #"zipmap\s+\[:"
                 "(zipmap [:name :lane :created-at :updated-at :handed] (str/split line #\"\\t\" -1))")))
  (testing "and the writer shape beside it"
    (is (re-find #"join\s+\"\\t\"\s+\[[:(]"
                 "(str/join \"\\t\" [(:name r) (:lane r) (:created-at r)])")))
  (testing "but not the named form that replaced them"
    (is (not (re-find #"zipmap\s+\[:" "(zipmap tasks-tsv-columns (str/split line #\"\\t\" -1))")))
    (is (not (re-find #"join\s+\"\\t\"\s+\[[:(]"
                      "(str/join \"\\t\" (map #(str (or (get r %) \"\")) tasks-tsv-columns))")))
    (is (not (re-find #"zipmap\s+\[:" "   vector in the reader's `zipmap` and a second literal in the writer's")))))
