(ns cae.cljs-smoke-test
  "NBB/Node execution smoke test for the portable `.cljc` CAE surface.

  Run with:
    nbb -cp src:test -e \"(require '[cae.cljs-smoke-test])\""
  (:require [cae.assessment :as assessment]
            [cae.industrial]
            [cae.interchange :as interchange]
            [cae.high-fidelity]
            [cae.adapter]
            [cae.protocol :as protocol]
            [cae.verification]
            [cae.orchestration :as orchestration]
            [cae.solver :as solver]
            [cae.study :as study]))

(defn- check! [condition message data]
  (when-not condition
    (throw (ex-info message data))))

(defn- approx= [expected actual tolerance]
  (<= (js/Math.abs (- expected actual)) tolerance))

(defn run! []
  (let [cfd (solver/solve {:solver {:kind :cfd}
                           :flow-m3-s 1.0 :duct-diameter-m 0.4 :duct-length-m 10.0
                           :minor-loss-coefficient 2.0
                           :fuel-mass-flow-kg-s 0.001
                           :lower-heating-value-J-kg 5.0e7
                           :combustion-efficiency 0.8})
        beam (solver/solve {:solver {:kind :fem} :element :cantilever-beam
                            :length-m 1.0 :width-m 0.05 :height-m 0.1
                            :youngs-modulus-Pa 210e9 :load-N 1000.0})
        material (solver/solve {:solver {:kind :materials} :temperature-K 900.0 :time-s 60.0
                                :avrami-n 1.0 :pre-exponential-s-n 1.0
                                :activation-energy-J-mol 4.0e4})
        heating (solver/solve {:solver {:kind :emag} :mode :induction-heating
                               :heating-power-W 10000.0 :coupling-efficiency 0.8
                               :thermal-mass-kg 10.0 :specific-heat-J-kgK 500.0
                               :duration-s 60.0 :initial-temperature-C 20.0})
        production (solver/solve {:solver {:kind :production-des} :jobs 2 :arrival-interval-s 100.0
                                  :stations [{:id :cut :cycle-time-s 10.0 :power-kW 6.0}
                                             {:id :pack :cycle-time-s 5.0 :power-kW 3.0}]})
        with-provenance (interchange/attach-provenance
                         {:case/id "cljs-usd" :solver {:kind :cfd}
                          :flow-m3-s 1.0 :duct-diameter-m 0.4 :duct-length-m 10.0}
                         {:source :openusd :asset-uri "file:///factory.usda"
                          :prim-path "/Factory/HVAC/Duct" :up-axis :z-up
                          :meters-per-unit 1.0})
        assessment (assessment/assess cfd {:pressure-drop-Pa {:max 200.0}})
        batch (orchestration/run-cases [with-provenance {:solver {:kind :cfd}
                                                          :flow-m3-s 0.0 :duct-diameter-m 0.4 :duct-length-m 10.0}])
        fvm (solver/solve {:solver {:kind :fvm-compressible} :cells 8 :dx-m 0.01 :dt-s 1.0e-5 :steps 1 :initial-condition :sod-shock-tube})
        rans (solver/solve {:solver {:kind :rans-k-epsilon} :cells 4 :dx-m 1.0 :dt-s 0.01 :steps 1 :velocity-m-s 2.0 :density-kg-m3 1.0 :viscosity-pa-s 1.0e-3})
        matdb (solver/solve {:solver {:kind :material-database} :material :air :temperature-K 300.0})
        benchmark (solver/solve {:solver {:kind :benchmark-suite} :case :axial-bar :force-N 10.0 :youngs-modulus-Pa 1000.0 :area-m2 2.0 :length-m 4.0})
        external (solver/solve {:solver {:kind :external-backend} :backend :openfoam :domain :cfd :version "v11" :input-format :openfoam-case})
        validation (solver/solve {:solver {:kind :validation-report} :report-id "cljs-nightly" :checks [benchmark {:passed? true}]})
        balance (solver/solve {:solver {:kind :mpi-load-balance} :weights [1.0 1.0 8.0 1.0 1.0] :ranks 2})
        experiment (solver/solve {:solver {:kind :experimental-comparison} :dataset :wind-tunnel :predicted [1.0 2.0] :measured [1.0 2.0] :tolerance 1.0e-6})
        mpi-message (protocol/mpi-message {:source 0 :target 1 :tag :halo :payload [1 2]})
        sensitivity (study/central-sensitivity {:solver {:kind :cfd}
                                                 :flow-m3-s 1.0 :duct-diameter-m 0.4 :duct-length-m 10.0}
                                                [:flow-m3-s] :pressure-drop-Pa 0.05)]
    (check! (= :cfd (:solver cfd)) "CFD did not dispatch in CLJS" {:result cfd})
    (check! (pos? (:minor-pressure-drop-Pa cfd)) "CFD fitting loss absent" {:result cfd})
    (check! (= 40000.0 (:combustion-heat-W cfd)) "CFD combustion heat mismatch" {:result cfd})
    (check! (= :linear-cantilever-beam (:model beam)) "FEM beam did not dispatch" {:result beam})
    (check! (pos? (:first-bending-frequency-Hz beam)) "FEM frequency invalid" {:result beam})
    (check! (= :Arrhenius (:kinetics material)) "materials kinetics invalid" {:result material})
    (check! (approx= 116.0 (:final-temperature-C heating) 1.0e-9)
            "induction heating energy balance invalid" {:result heating})
    (check! (pos? (:energy-kWh production)) "production energy invalid" {:result production})
    (check! (= :passed (:status assessment)) "assessment did not pass" {:assessment assessment})
    (check! (= [:succeeded :failed] (mapv :status batch)) "batch isolation invalid" {:batch batch})
    (check! (pos? (:derivative sensitivity)) "CFD sensitivity direction invalid" {:sensitivity sensitivity})
    (check! (= 8 (:cells fvm)) "FVM Sod dispatch invalid" {:result fvm})
    (check! (= 4 (:cells rans)) "RANS dispatch invalid" {:result rans})
    (check! (pos? (get-in matdb [:properties :dynamic-viscosity-Pa-s])) "fluid material invalid" {:result matdb})
    (check! (:passed? benchmark) "benchmark verification invalid" {:result benchmark})
    (check! (= :adapter-pending (:status external)) "external adapter contract invalid" {:result external})
    (check! (= :verified (:status validation)) "validation report gate invalid" {:result validation})
    (check! (= 5 (count (:assignment balance))) "MPI load balance invalid" {:result balance})
    (check! (= :verified (:status experiment)) "experimental comparison invalid" {:result experiment})
    (check! (= :halo (:tag mpi-message)) "MPI protocol invalid" {:result mpi-message})
    (check! (= :openusd (get-in with-provenance [:case/provenance :source]))
            "OpenUSD provenance invalid" {:case with-provenance})
    (println "CLJS/NBB CAE smoke test passed")))

(run!)
