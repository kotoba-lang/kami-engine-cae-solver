(ns cae.cljs-smoke-test
  "NBB/Node execution smoke test for the portable `.cljc` CAE surface.

  Run with:
    nbb -cp src:test -e \"(require '[cae.cljs-smoke-test])\""
  (:require [cae.assessment :as assessment]
            [cae.industrial]
            [cae.interchange :as interchange]
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
    (check! (= :openusd (get-in with-provenance [:case/provenance :source]))
            "OpenUSD provenance invalid" {:case with-provenance})
    (println "CLJS/NBB CAE smoke test passed")))

(run!)
