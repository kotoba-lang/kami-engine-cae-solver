(ns cae.qualification-matrix-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(def matrix (-> "cae/qualification-matrix.edn" io/resource slurp edn/read-string))

(deftest matrix-is-explicit-and-never-overclaims
  (is (= (count matrix) (count (set (map :scope/id matrix)))))
  (doseq [entry matrix]
    (is (seq (get-in entry [:scope])))
    (is (seq (:included entry)))
    (is (seq (:excluded entry)))
    (when (= :release-qualified (:release-status entry))
      (is (= :passed (:numerical-verification entry)))
      (is (= :passed (:experimental-validation entry)))
      (is (= :passed (:software-quality entry)))))
  (is (= :not-release-qualified
         (:release-status (first (filter #(= :package/general-industrial (:scope/id %)) matrix))))))
