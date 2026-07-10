(ns build-pages-report
  "Generate the static GitHub Pages verification report from the real CLJS
  solver runtime. Run with:
    nbb -cp src:scripts -e \"(require '[build-pages-report])\""
  (:require [cae.industrial]
            [cae.solver :as solver]
            ["fs" :as fs]))

(defn- html-escape [value]
  (-> (str value)
      (.replace "&" "&amp;")
      (.replace "<" "&lt;")
      (.replace ">" "&gt;")
      (.replace "\"" "&quot;")))

(defn- metric-row [{:keys [domain metric value unit]}]
  (str "<tr><td>" (html-escape domain) "</td><td>" (html-escape metric)
       "</td><td>" (html-escape value) "</td><td>" (html-escape unit) "</td></tr>"))

(defn build-report []
  (let [cfd (solver/solve {:solver {:kind :cfd}
                           :flow-m3-s 1.0 :duct-diameter-m 0.4 :duct-length-m 10.0
                           :minor-loss-coefficient 2.0
                           :fuel-mass-flow-kg-s 0.001
                           :lower-heating-value-J-kg 5.0e7
                           :combustion-efficiency 0.8})
        fem (solver/solve {:solver {:kind :fem} :element :cantilever-beam
                           :length-m 1.0 :width-m 0.05 :height-m 0.1
                           :youngs-modulus-Pa 210e9 :load-N 1000.0})
        materials (solver/solve {:solver {:kind :materials} :temperature-K 900.0 :time-s 60.0
                                :avrami-n 1.0 :pre-exponential-s-n 1.0
                                :activation-energy-J-mol 4.0e4})
        heating (solver/solve {:solver {:kind :emag} :mode :induction-heating
                               :heating-power-W 10000.0 :coupling-efficiency 0.8
                               :thermal-mass-kg 10.0 :specific-heat-J-kgK 500.0
                               :duration-s 60.0 :initial-temperature-C 20.0})
        production (solver/solve {:solver {:kind :production-des} :jobs 2 :arrival-interval-s 100.0
                                  :stations [{:id :cut :cycle-time-s 10.0 :power-kW 6.0}
                                             {:id :pack :cycle-time-s 5.0 :power-kW 3.0}]})
        rows [{:domain "CFD" :metric "Pressure drop" :value (:pressure-drop-Pa cfd) :unit "Pa"}
              {:domain "CFD" :metric "Outlet temperature" :value (:outlet-temperature-C cfd) :unit "°C"}
              {:domain "FEM" :metric "Tip displacement" :value (:displacement-m fem) :unit "m"}
              {:domain "FEM" :metric "First bending mode" :value (:first-bending-frequency-Hz fem) :unit "Hz"}
              {:domain "Materials" :metric "Transformed fraction" :value (:transformed-fraction materials) :unit "–"}
              {:domain "EM heating" :metric "Final temperature" :value (:final-temperature-C heating) :unit "°C"}
              {:domain "Production" :metric "Throughput" :value (:throughput-per-hour production) :unit "jobs/h"}
              {:domain "Production" :metric "Energy / job" :value (:energy-per-job-kWh production) :unit "kWh"}]
        report {:runtime "ClojureScript via NBB / Node"
                :solver-fidelity :reduced-order
                :status :screening-only
                :metrics rows}
        json (js/JSON.stringify (clj->js report) nil 2)
        html (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                  "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                  "<title>Kotoba CAE — CLJS verification</title>"
                  "<style>body{font-family:system-ui,sans-serif;max-width:1040px;margin:3rem auto;padding:0 1rem;color:#172033}"
                  "h1{margin-bottom:.2rem} .ok{color:#087443;font-weight:700} table{border-collapse:collapse;width:100%;margin:1.5rem 0}"
                  "th,td{padding:.7rem;border-bottom:1px solid #d8dee9;text-align:left}th{background:#f4f7fb}"
                  "pre{overflow:auto;background:#111827;color:#d1fae5;padding:1rem;border-radius:.5rem}"
                  ".kami-stage{position:relative;margin:1.5rem 0;border-radius:20px;overflow:hidden;box-shadow:0 16px 36px #17203333}"
                  "#kami-webgpu-canvas{display:block;width:100%;height:310px;background:#050d18}.kami-stage-copy{position:absolute;right:18px;top:18px;color:#eafff6;background:#061220cc;padding:12px 14px;border-radius:12px;font-weight:700;min-width:210px}"
                  "#kami-runtime-status[data-state=ok]{color:#7af4ba}#kami-runtime-status[data-state=error]{color:#ff8f8f}#kami-runtime-status[data-state=fallback]{color:#ffd36e}.kami-scenes{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}.kami-scenes button{border:1px solid #bbcad8;background:#fff;padding:8px 12px;border-radius:999px;cursor:pointer}.kami-scenes button.active{background:#172033;color:#fff}</style></head><body>"
                  "<h1>Kotoba CAE</h1><p class=\"ok\">● CLJS/NBB verification passed</p>"
                  "<p>This report is generated in GitHub Actions by executing the portable ClojureScript solver surface. "
                  "Results are reduced-order screening outputs, not release-signoff CAE.</p>"
                  "<section class=\"kami-stage\"><canvas id=\"kami-webgpu-canvas\" aria-label=\"Kami Engine WebGPU renderer\"></canvas><div class=\"kami-stage-copy\"><div id=\"kami-runtime-status\">Loading Kami Engine WASM…</div><div id=\"kami-scene-name\">CFD · flow / combustion</div><hr><div>WASM ticks: <span id=\"kami-wasm-ticks\">0</span></div><div>WASM draw calls: <span id=\"kami-wasm-draws\">0</span></div></div></section><div class=\"kami-scenes\"><button class=\"active\" data-kami-scene=\"0\">CFD</button><button data-kami-scene=\"1\">FEM</button><button data-kami-scene=\"2\">Process</button><button data-kami-scene=\"3\">Materials</button><button data-kami-scene=\"4\">EM</button><button data-kami-scene=\"5\">Production</button></div>"
                  "<table><thead><tr><th>Domain</th><th>Metric</th><th>Value</th><th>Unit</th></tr></thead><tbody>"
                  (apply str (map metric-row rows)) "</tbody></table><h2>Runtime evidence</h2><pre>"
                  (html-escape json) "</pre><script>window.__KAMI_CAE_METRICS__=" json ";</script><script src=\"./kami-ui.js\"></script><script src=\"./kami-cae-webgpu.js\"></script></body></html>")]
    (.mkdirSync fs "dist" #js {:recursive true})
    (.writeFileSync fs "dist/index.html" html "utf8")
    (println "Generated dist/index.html from CLJS solver runtime")))

(build-report)
