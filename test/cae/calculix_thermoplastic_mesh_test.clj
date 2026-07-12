(ns cae.calculix-thermoplastic-mesh-test
  (:require [cae.calculix-thermoplastic-mesh :as mesh]
            [clojure.test :refer [deftest is]]))

(deftest coupled-mesh-refines-only-the-through-thickness-direction
  (let [case (mesh/coupled-input {:layers 4})]
    (is (= 20 (:nodes case)))
    (is (= 4 (:elements case)))
    (is (= [9 10 11 12] (:midpoint-nodes case)))
    (is (re-find #"\*COUPLED TEMPERATURE-DISPLACEMENT" (:input case)))
    (is (re-find #"\*CONDUCTIVITY" (:input case)))
    (is (re-find #"\*PLASTIC" (:input case))))
  (is (thrown? Exception (mesh/coupled-input {:layers 3}))))
