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

(deftest committed-calculix-contact-run-is-converged-and-balanced
  (let [fixture (fixture "cae/evidence/calculix-2.21-nlgeom-contact-blocks.edn")]
    (is (= :external-nonlinear-contact-verified (:status fixture)))
    (is (true? (get-in fixture [:result :nlgeom?])))
    (is (= 22 (get-in fixture [:result :increments])))
    (is (= 22 (get-in fixture [:result :converged-increments])))
    (is (= 28 (get-in fixture [:result :maximum-contact-elements])))
    (is (= -0.2 (get-in fixture [:result :displacement :top-node-16 2])))
    (is (neg? (get-in fixture [:result :contact :normal-force])))
    (is (zero? (get-in fixture [:checks :force-balance-relative-error])))
    (is (every? #(re-matches #"[0-9a-f]{64}" (:sha256 %))
                (concat (:input-files fixture) (:result-files fixture))))))

(deftest committed-calculix-plastic-cycle-retains-residual-state
  (let [fixture (fixture "cae/evidence/calculix-2.21-elastoplastic-load-unload.edn")]
    (is (= :external-plastic-cycle-verified (:status fixture)))
    (is (= 42 (get-in fixture [:result :converged-increments])))
    (is (pos? (get-in fixture [:result :peak :peeq])))
    (is (< (abs (get-in fixture [:result :final :top-force-z])) 1.0e-9))
    (is (< (abs (get-in fixture [:result :final :stress-z])) 1.0e-9))
    (is (pos? (get-in fixture [:result :final :top-displacement-z])))
    (is (= (get-in fixture [:result :maximum-peeq])
           (get-in fixture [:result :final :peeq])))
    (is (every? #(re-matches #"[0-9a-f]{64}" (:sha256 %))
                (concat (:input-files fixture) (:result-files fixture))))))

(deftest committed-calculix-three-grid-study-quantifies-discretization-error
  (let [fixture (fixture "cae/evidence/calculix-2.21-nlgeom-cantilever-three-grid-gci.edn")
        study (:study fixture)]
    (is (= :external-mesh-convergence-verified (:status fixture)))
    (is (= [10 80 640] (mapv :elements (:levels study))))
    (is (apply < (map #(abs (:value %)) (:levels study))))
    (is (< 1.0 (:observed-order study) 2.0))
    (is (< 0.05 (:fine-relative-error-estimate study) 0.06))
    (is (< 0.06 (:fine-gci study) 0.07))
    (is (= 18 (count (mapcat :files (:levels study)))))
    (is (every? #(re-matches #"[0-9a-f]{64}" (:sha256 %))
                (mapcat :files (:levels study))))))

(deftest committed-ultrafine-run-reduces-gci-below-declared-target
  (let [fixture (fixture "cae/evidence/calculix-2.21-nlgeom-cantilever-four-level-gci.edn")
        study (:study fixture) ultrafine (last (get-in study [:refined :levels]))]
    (is (= :external-mesh-refinement-verified (:status fixture)))
    (is (= 5120 (:elements ultrafine)))
    (is (= 6561 (:nodes ultrafine)))
    (is (< (get-in study [:refined :fine-gci]) (:target-gci study)))
    (is (> (:gci-reduction-factor study) 5.0))
    (is (> (get-in study [:refined :observed-order])
           (get-in study [:baseline :observed-order])))
    (is (= 6 (count (:files ultrafine))))
    (is (every? #(re-matches #"[0-9a-f]{64}" (:sha256 %)) (:files ultrafine)))))

(deftest committed-contact-study-rejects-nonmonotonic-local-pressure
  (let [fixture (fixture "cae/evidence/calculix-2.21-contact-pressure-mesh-sensitivity.edn")
        study (:study fixture)]
    (is (= :external-contact-sensitivity-verified (:status fixture)))
    (is (true? (:evidence-passed? study)))
    (is (false? (:local-qualified? study)))
    (is (= :local-pressure-not-qualified (:qualification-status study)))
    (is (= [1850.362 1648.054 1651.934] (mapv :maximum-pressure (:levels study))))
    (is (every? zero? (map :force-balance-relative-error (:levels study))))
    (is (= 18 (count (mapcat :files (:levels study)))))
    (is (every? #(re-matches #"[0-9a-f]{64}" (:sha256 %))
                (mapcat :files (:levels study))))))
