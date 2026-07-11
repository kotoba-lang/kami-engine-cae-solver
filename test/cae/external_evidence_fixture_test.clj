(ns cae.external-evidence-fixture-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn fixture [path] (-> path io/resource slurp edn/read-string))

(deftest committed-openfoam-run-is-hash-complete
  (let [fixture (fixture "cae/evidence/openfoam-v2506-icofoam-cavity.edn")]
    (is (= :external-process-verified (:status fixture)))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:image-digest fixture)))
    (is (every? #(re-matches #"[0-9a-f]{64}" (:sha256 %))
                (concat (:input-files fixture) (:result-files fixture))))
    (is (= 0.5 (get-in fixture [:log :final-time])))
    (is (< (abs (get-in fixture [:log :final-continuity :cumulative])) 1.0e-15))
    (is (< (get-in fixture [:log :maximum-courant]) 1.0))
    (is (= 400 (get-in fixture [:log :residual-records])))))

(deftest committed-calculix-run-is-hash-complete-and-analytic
  (let [fixture (fixture "cae/evidence/calculix-2.21-axial-unit-cube.edn")]
    (is (= :external-process-verified (:status fixture)))
    (is (= "2.21" (:solver-version fixture)))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:image-digest fixture)))
    (is (every? #(and (re-matches #"[0-9a-f]{64}" (:sha256 %))
                      (pos? (:bytes %)))
                (concat (:input-files fixture) (:result-files fixture))))
    (is (true? (get-in fixture [:log :complete?])))
    (is (= 8 (get-in fixture [:result :sample-count])))
    (is (= 0.001 (get-in fixture [:result :maximum-absolute-uz])))
    (is (zero? (get-in fixture [:analytic-check :relative-error])))
    (is (true? (get-in fixture [:analytic-check :passed?])))))

(deftest committed-openmpi-run-is-rank-complete-and-repeatable
  (let [fixture (fixture "cae/evidence/openmpi-4.1.6-four-rank-pi.edn")
        run (first (:runs fixture))]
    (is (= :external-mpi-verified (:status fixture)))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:image-digest fixture)))
    (is (= 4 (:rank-count run)))
    (is (= [0 1 2 3] (mapv :rank (:ranks run))))
    (is (= 1000000 (reduce + (map :samples (:ranks run)))))
    (is (= 1000000 (get-in run [:result :samples])))
    (is (< (get-in run [:result :absolute-error]) 1.0e-12))
    (is (true? (:deterministic? fixture)))
    (is (apply = (map :sha256 (:output-files fixture))))))
