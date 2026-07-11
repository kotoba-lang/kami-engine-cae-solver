(ns cae.external-evidence-fixture-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(def fixture (-> "cae/evidence/openfoam-v2506-icofoam-cavity.edn" io/resource slurp edn/read-string))

(deftest committed-openfoam-run-is-hash-complete
  (is (= :external-process-verified (:status fixture)))
  (is (re-matches #"sha256:[0-9a-f]{64}" (:image-digest fixture)))
  (is (every? #(re-matches #"[0-9a-f]{64}" (:sha256 %))
              (concat (:input-files fixture) (:result-files fixture))))
  (is (= 0.5 (get-in fixture [:log :final-time])))
  (is (< (abs (get-in fixture [:log :final-continuity :cumulative])) 1.0e-15))
  (is (< (get-in fixture [:log :maximum-courant]) 1.0))
  (is (= 400 (get-in fixture [:log :residual-records]))))
