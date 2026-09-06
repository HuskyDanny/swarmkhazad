(ns run-tests
  "Test runner: every test/**/*_test.clj namespace, optionally filtered by a substring."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]))

(defn test-namespaces [wanted]
  (->> (fs/glob "test" "**/*_test.clj")
       (map (fn [p]
              (-> (str (fs/relativize "test" p))
                  (str/replace #"\.clj$" "")
                  (str/replace "/" ".")
                  (str/replace "_" "-")
                  symbol)))
       (filter (fn [n] (or (nil? wanted) (str/includes? (str n) wanted))))
       sort))

(defn -main [args]
  (let [namespaces (test-namespaces (first args))]
    (doseq [n namespaces] (require n))
    (let [{:keys [fail error]} (apply t/run-tests namespaces)]
      (System/exit (min 1 (+ fail error))))))
