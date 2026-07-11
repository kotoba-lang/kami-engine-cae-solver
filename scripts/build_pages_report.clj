(ns build-pages-report
  "Generate the GitHub Pages report with Hiccup and zero-runtime Shadow CSS."
  (:require [cae.industrial]
            [cae.advanced]
            [cae.high-fidelity]
            [cae.solver :as solver]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [hiccup2.core :as h]
            [shadow.css :refer [css]]
            [shadow.css.build :as css-build]))

(def $body (css {:font-family "system-ui,sans-serif" :max-width "1040px" :margin "3rem auto"
                 :padding "0 1rem" :color "#172033"}))
(def $ok (css {:color "#087443" :font-weight "700"}))
(def $table (css {:border-collapse "collapse" :width "100%" :margin "1.5rem 0"}))
(def $cell (css {:padding ".7rem" :border-bottom "1px solid #d8dee9" :text-align "left"}))
(def $head (css {:padding ".7rem" :border-bottom "1px solid #d8dee9" :text-align "left"
                 :background "#f4f7fb"}))
(def $evidence (css {:overflow "auto" :background "#111827" :color "#d1fae5"
                     :padding "1rem" :border-radius ".5rem"}))
(def $stage (css {:position "relative" :margin "1.5rem 0" :border-radius "20px"
                  :overflow "hidden" :box-shadow "0 16px 36px #17203333"}))
(def $canvas (css {:display "block" :width "100%" :height "310px" :background "#050d18"}))
(def $overlay (css {:position "absolute" :right "18px" :top "18px" :color "#eafff6"
                    :background "#061220cc" :padding "12px 14px" :border-radius "12px"
                    :font-weight "700" :min-width "240px"}))
(def $metric (css {:color "#7af4ba" :min-height "1.25em"}))
(def $scene-list (css :flex :flex-wrap {:gap "8px" :margin "12px 0"}))
(def $controls (css :flex {:gap "10px" :align-items "center" :margin "0 0 18px"}))
(def $button (css {:border "1px solid #bbcad8" :background "#fff" :padding "8px 12px"
                   :border-radius "999px" :cursor "pointer"}
                  ["&.active" {:background "#172033" :color "#fff"}]))

(def scenes ["CFD" "FEM" "Process" "Materials" "EM" "Production" "Sod FVM"
             "RANS k–ε" "Adaptive Mesh" "Thermo-Mechanical" "EM FEM" "3D Contact"
             "Fracture" "MPI Halo" "Mesh Quality"])

(defn- metric-row [{:keys [domain metric value unit]}]
  [:tr [:td {:class $cell} domain] [:td {:class $cell} metric]
   [:td {:class $cell} (str value)] [:td {:class $cell} unit]])

(defn- write-css! []
  (-> (css-build/start)
      (css-build/index-path (io/file "scripts") {})
      (css-build/generate '{:main {:entries [build-pages-report]}})
      (css-build/minify)
      (css-build/write-outputs-to (io/file "dist" "css"))))

(defn build-report []
  (let [cfd (solver/solve {:solver {:kind :cfd} :flow-m3-s 1.0 :duct-diameter-m 0.4 :duct-length-m 10.0
                           :minor-loss-coefficient 2.0 :fuel-mass-flow-kg-s 0.001
                           :lower-heating-value-J-kg 5.0e7 :combustion-efficiency 0.8})
        fem (solver/solve {:solver {:kind :fem} :element :cantilever-beam :length-m 1.0
                           :width-m 0.05 :height-m 0.1 :youngs-modulus-Pa 210e9 :load-N 1000.0})
        materials (solver/solve {:solver {:kind :materials} :temperature-K 900.0 :time-s 60.0
                                :avrami-n 1.0 :pre-exponential-s-n 1.0 :activation-energy-J-mol 4.0e4})
        heating (solver/solve {:solver {:kind :emag} :mode :induction-heating :heating-power-W 10000.0
                               :coupling-efficiency 0.8 :thermal-mass-kg 10.0 :specific-heat-J-kgK 500.0
                               :duration-s 60.0 :initial-temperature-C 20.0})
        production (solver/solve {:solver {:kind :production-des} :jobs 2 :arrival-interval-s 100.0
                                  :stations [{:id :cut :cycle-time-s 10.0 :power-kW 6.0}
                                             {:id :pack :cycle-time-s 5.0 :power-kW 3.0}]})
        fvm (solver/solve {:solver {:kind :fvm-compressible} :cells 18 :dx-m 0.05 :dt-s 1.0e-4
                           :steps 4 :initial-condition :sod-shock-tube})
        rans (solver/solve {:solver {:kind :rans-k-epsilon} :cells 12 :dx-m 0.1 :dt-s 1.0e-4
                            :steps 8 :velocity-m-s 12.0 :density-kg-m3 1.2 :viscosity-pa-s 1.8e-5})
        mesh (solver/solve {:solver {:kind :fem-mesh} :dimension :3d :nx 2 :ny 2 :nz 2
                            :length-x-m 1.0 :length-y-m 1.0 :length-z-m 1.0})
        quality (solver/solve (assoc mesh :solver {:kind :mesh-quality}))
        thermo (solver/solve {:solver {:kind :thermo-mech} :elements 12 :length-m 1.0 :dt-s 1.0e-4
                              :steps 20 :thermal-diffusivity-m2-s 1.0e-5 :heat-source-K-s 100.0
                              :reference-temperature-K 293.15 :youngs-modulus-Pa 210e9 :fully-constrained? true})
        emag-fem (solver/solve {:solver {:kind :emag-fem} :nx 8 :ny 8 :width-m 1.0 :height-m 1.0
                                :iterations 24 :current-density-A-m2 2000.0})
        contact (solver/solve {:solver {:kind :friction-contact-3d} :normal-vector-N [0 0 100]
                               :tangential-vector-N [60 0 0] :friction-coefficient 0.5
                               :penalty-stiffness-N-m 1.0e6})
        fracture (solver/solve {:solver {:kind :fracture-criterion} :stress-Pa 180e6
                                :fracture-toughness-Pa-m 50e6 :stress-intensity-factor-Pa-sqrt-m 42e6})
        mpi (solver/solve {:solver {:kind :mpi-halo-exchange}
                           :partitions [{:owned [0 1 2]} {:owned [3 4 5]} {:owned [6 7 8]} {:owned [9 10 11]}]})
        rows [{:domain "CFD" :metric "Pressure drop" :value (:pressure-drop-Pa cfd) :unit "Pa"}
              {:domain "CFD" :metric "Outlet temperature" :value (:outlet-temperature-C cfd) :unit "°C"}
              {:domain "FEM" :metric "Tip displacement" :value (:displacement-m fem) :unit "m"}
              {:domain "FEM" :metric "First bending mode" :value (:first-bending-frequency-Hz fem) :unit "Hz"}
              {:domain "Materials" :metric "Transformed fraction" :value (:transformed-fraction materials) :unit "–"}
              {:domain "EM heating" :metric "Final temperature" :value (:final-temperature-C heating) :unit "°C"}
              {:domain "Production" :metric "Throughput" :value (:throughput-per-hour production) :unit "jobs/h"}
              {:domain "Production" :metric "Energy / job" :value (:energy-per-job-kWh production) :unit "kWh"}
              {:domain "Sod FVM" :metric "Density contrast" :value (- (first (:density-profile fvm)) (last (:density-profile fvm))) :unit "kg/m³"}
              {:domain "RANS k–ε" :metric "Turbulent kinetic energy" :value (ffirst (:k-epsilon rans)) :unit "m²/s²"}
              {:domain "3D FEM mesh" :metric "TET4 elements" :value (:element-count mesh) :unit "elements"}
              {:domain "Mesh quality" :metric "Minimum tetra volume" :value (:minimum quality) :unit "m³"}
              {:domain "Thermo-mechanical" :metric "Thermal stress" :value (:thermal-stress-Pa thermo) :unit "Pa"}
              {:domain "EM FEM" :metric "Maximum flux density" :value (:max-flux-density-T emag-fem) :unit "T"}
              {:domain "3D contact" :metric "Sliding" :value (:sliding? contact) :unit "boolean"}
              {:domain "Fracture" :metric "K/Kc utilization" :value (:utilization fracture) :unit "ratio"}
              {:domain "MPI" :metric "Halo messages" :value (:messages mpi) :unit "messages"}]
        report {:runtime "ClojureScript / WebGPU" :solver-fidelity :realtime-reference
                :status :screening-only :metrics rows}
        report-json (json/write-str report)]
    (.mkdirs (io/file "dist"))
    (write-css!)
    (spit "dist/index.html"
          (str "<!doctype html>"
               (h/html
                [:html {:lang "en"}
                 [:head [:meta {:charset "utf-8"}]
                  [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
                  [:title "Kotoba CAE — realtime CLJS WebGPU"]
                  [:link {:rel "stylesheet" :href "./css/main.css"}]]
                 [:body {:class $body}
                  [:h1 "Kotoba CAE"] [:p {:class $ok} "● CLJS realtime simulation ready"]
                  [:p "Stateful reference physics is stepped and rendered directly from ClojureScript with WebGPU."]
                  [:section {:class $stage}
                   [:canvas {:id "kami-webgpu-canvas" :class $canvas
                             :aria-label "ClojureScript direct WebGPU realtime simulation"}]
                   [:div {:class $overlay}
                    [:div {:id "kami-runtime-status"} "Loading ClojureScript WebGPU…"]
                    [:div {:id "kami-scene-name"} "CFD · flow / combustion"] [:hr]
                    [:div "Sim time: " [:span {:id "kami-sim-time"} "0.00"] " s · steps: " [:span {:id "kami-sim-steps"} "0"]]
                    [:div {:id "kami-sim-metric" :class $metric} "initializing simulation"]
                    [:div "CLJS frames: " [:span {:id "kami-cljs-frames"} "0"] " · WebGPU draws: " [:span {:id "kami-webgpu-draws"} "0"]]]]
                  [:div {:class $scene-list}
                   (map-indexed (fn [i label] [:button {:class (str $button (when (zero? i) " active"))
                                                          :data-kami-scene i} label]) scenes)]
                  [:div {:class $controls}
                   [:button {:id "kami-sim-toggle" :class $button} "Pause"]
                   [:button {:id "kami-sim-reset" :class $button} "Reset"]
                   [:label "Speed " [:select {:id "kami-sim-speed" :class $button}
                                      (for [speed [0.25 0.5 1 2 4]]
                                        [:option (cond-> {:value speed} (= speed 1) (assoc :selected true)) (str speed "×")])]]]
                  [:table {:class $table} [:thead [:tr (for [x ["Domain" "Metric" "Value" "Unit"]] [:th {:class $head} x])]]
                   [:tbody (map metric-row rows)]]
                  [:h2 "Runtime evidence"] [:pre {:class $evidence} (pr-str report)]
                  [:script (h/raw (str "window.__KAMI_CAE_METRICS__=" report-json ";"))]
                  [:script {:src "./kami-cae-webgpu.js"}]]])))
    (println "Generated dist/index.html with Hiccup + Shadow CSS")))

(defn -main [& _] (build-report))
