(ns cae.external-evidence-test
  (:require [cae.external-evidence :as evidence]
            [clojure.test :refer [deftest is]]))

(def log-sample
  "Time = 0.5\nCourant Number mean: 0.22 max: 0.85\nsmoothSolver:  Solving for Ux, Initial residual = 2.3e-07, Final residual = 2.3e-07, No Iterations 0\ntime step continuity errors : sum local = 8.8e-09, global = -1.4e-19, cumulative = -4.1e-18\nEnd\n")

(deftest parses-real-openfoam-diagnostics
  (let [parsed (evidence/openfoam-log log-sample)]
    (is (:complete? parsed))
    (is (= 0.5 (:final-time parsed)))
    (is (= 0.85 (:maximum-courant parsed)))
    (is (= -4.1e-18 (get-in parsed [:final-continuity :cumulative])))))

(deftest external-process-evidence-fails-closed
  (let [file {:path "x" :sha256 (apply str (repeat 64 "a")) :bytes 10}
        base {:case-id "cavity" :solver :openfoam :solver-version "v2506"
              :image-digest (str "sha256:" (apply str (repeat 64 "b")))
              :command ["blockMesh" "icoFoam"] :exit-code 0 :input-files [file]
              :result-files [file] :log-text log-sample :platform "linux/arm64" :executed-at "now"}]
    (is (= :external-process-verified (:status (evidence/process-evidence base))))
    (is (= :external-process-rejected (:status (evidence/process-evidence (assoc base :exit-code 1)))))
    (is (= :external-process-rejected (:status (evidence/process-evidence (assoc base :image-digest "latest")))))))

(deftest parses-calculix-log-and-fixed-width-frd
  (let [log (evidence/calculix-log "CalculiX Version 2.21\nJob finished\nTotal CalculiX Time: 0.012880\n")
        frd (evidence/calculix-frd-displacements
             " -4  DISP        4    1\n -1         5 4.0E-19-9.0E-19 1.00000E-03\n -3\n")]
    (is (:complete? log))
    (is (= "2.21" (:version log)))
    (is (= 1 (:sample-count frd)))
    (is (= 0.001 (:maximum-absolute-uz frd)))
    (is (= 0.001 (:maximum-displacement frd)))))
