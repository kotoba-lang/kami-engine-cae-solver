(ns cae.external-evidence-test
  (:require [cae.external-evidence :as evidence]
            [clojure.string :as str]
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

(def contact-log-sample
  "Nonlinear geometric effects are taken into account\n increment 1 attempt 1 \n iteration 1\n Number of contact spring elements=28\n convergence\n Job finished\n")

(def contact-dat-sample
  (str "displacements (vx,vy,vz) for set TOP and time  1.0\n"
       " 16 0.0 0.0 -0.2\n"
       "total force (fx,fy,fz) for set TOP and time  1.0\n 0.0 0.0 -100.0\n"
       "statistics for slave set UPPER_BOTTOM, master set LOWER_TOP and time  1.0\n"
       "total surface force (fx,fy,fz) and moment about the origin (mx,my,mz)\n 0.0 0.0 100.0 0 0 0\n"
       "area,  normal force (+ = tension) and shear force (size)\n 1.0 -100.0 0.0\n"))

(deftest parses-and-fail-closes-nonlinear-contact
  (let [result (evidence/calculix-contact-result
                {:log-text contact-log-sample :dat-text contact-dat-sample
                 :sta-text " 1 1 1 1 1.0 1.0 1.0\n"
                 :cvg-text " 1 22 1 2 28 0.0 0.005\n"})]
    (is (:nlgeom? result))
    (is (= 28 (:maximum-contact-elements result)))
    (is (= -100.0 (get-in result [:contact :normal-force])))
    (is (:passed? (evidence/calculix-contact-checks result)))
    (is (false? (:passed? (evidence/calculix-contact-checks (assoc result :nlgeom? false)))))))

(defn plastic-snapshot [time displacement force stress peeq]
  (str "displacements (vx,vy,vz) for set TOP and time " time "\n 8 0 0 " displacement "\n"
       "total force (fx,fy,fz) for set TOP and time " time "\n 0 0 " force "\n"
       "stresses (elem, integ.pnt.,sxx,syy,szz,sxy,sxz,syz) for set SPECIMEN and time " time
       "\n 1 1 0 0 " stress " 0 0 0\n"
       "equivalent plastic strain (elem, integ.pnt.,pe)for set SPECIMEN and time " time
       "\n 1 1 " peeq "\n"))

(deftest parses-and-fail-closes-plastic-cycle
  (let [log (str "Nonlinear material laws are taken into account\n"
                 " increment 1 attempt 1 \n convergence\n"
                 " increment 2 attempt 1 \n convergence\nJob finished\n")
        dat (str (plastic-snapshot "0.5" "0.002" "300" "300" "0.001")
                 (plastic-snapshot "1.0" "0.0005" "0" "0" "0.001"))
        result (evidence/calculix-plastic-result
                {:log-text log :dat-text dat :sta-text " 1 2 1 2 1.0 1.0 0.5\n"})]
    (is (= 2 (:history-count result)))
    (is (= 0.001 (:maximum-peeq result)))
    (is (:passed? (evidence/calculix-plastic-checks (assoc result :history-count 3))))
    (is (false? (:passed? (evidence/calculix-plastic-checks
                           (assoc-in (assoc result :history-count 3) [:final :peeq] 0.0)))))))

(def mpi-sample
  (str "KOTOBA_MPI_RANK rank=0 size=2 samples=5 partial=15.0\n"
       "KOTOBA_MPI_RANK rank=1 size=2 samples=5 partial=16.0\n"
       "KOTOBA_MPI_RESULT size=2 samples=10 pi=3.1 error=0.04159\n"))

(deftest parses-and-fail-closes-real-mpi-audit
  (let [parsed (evidence/mpi-log mpi-sample)
        file {:path "x" :sha256 (apply str (repeat 64 "a")) :bytes 10}
        base {:case-id "mpi" :solver-version "4.1.6"
              :image-digest (str "sha256:" (apply str (repeat 64 "b")))
              :command ["mpirun" "-np" "2"] :exit-codes [0 0] :worker-source file
              :output-files [file file] :run-texts [mpi-sample mpi-sample]
              :error-tolerance 0.05}]
    (is (:complete? parsed))
    (is (= [0 1] (mapv :rank (:ranks parsed))))
    (is (= :external-mpi-verified (:status (evidence/mpi-process-evidence base))))
    (is (= :external-mpi-rejected
           (:status (evidence/mpi-process-evidence
                     (assoc base :run-texts [mpi-sample (str mpi-sample "x")])))))
    (is (false? (:complete? (evidence/mpi-log
                             (str/replace mpi-sample "rank=1" "rank=2")))))))
