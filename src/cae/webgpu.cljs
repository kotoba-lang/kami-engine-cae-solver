(ns cae.webgpu
  "Browser renderer: ClojureScript drives WebGPU directly through JS interop.")

(def scene-labels
  ["CFD · flow / combustion" "FEM · cantilever beam" "Process · weld / cast / roll"
   "Materials · microstructure" "EM · motor / induction" "Production · flow line"
   "FVM · Sod shock tube" "RANS · k–ε turbulence" "AMR · adaptive mesh"
   "Thermo-mechanical · coupled bar" "EM FEM · field mesh" "Contact · 3D friction"
   "Fracture · crack criterion" "MPI · partitions / halo" "FEM mesh · TRI3 / TET4 quality"])

(def colors
  {:cyan [0.04 0.85 0.68] :blue [0.10 0.42 0.95] :orange [1.0 0.35 0.12]
   :gold [1.0 0.75 0.12] :purple [0.62 0.28 0.95] :white [0.75 0.88 0.95]})

(defn- push-vertex! [out [x y z] [r g b]]
  (swap! out into [x y z r g b]))

(defn- triangle! [out a b c color]
  (doseq [p [a b c c b a]] (push-vertex! out p color)))

(defn- cube! [out x y z sx sy sz color]
  (let [x0 (- x (/ sx 2)) x1 (+ x (/ sx 2)) y0 (- y (/ sy 2)) y1 (+ y (/ sy 2))
        z0 (- z (/ sz 2)) z1 (+ z (/ sz 2))
        faces [[[x0 y0 z1] [x1 y0 z1] [x1 y1 z1] [x0 y1 z1]]
               [[x1 y0 z0] [x0 y0 z0] [x0 y1 z0] [x1 y1 z0]]
               [[x0 y1 z1] [x1 y1 z1] [x1 y1 z0] [x0 y1 z0]]
               [[x0 y0 z0] [x1 y0 z0] [x1 y0 z1] [x0 y0 z1]]
               [[x1 y0 z1] [x1 y0 z0] [x1 y1 z0] [x1 y1 z1]]
               [[x0 y0 z0] [x0 y0 z1] [x0 y1 z1] [x0 y1 z0]]]]
    (doseq [[a b c d] faces] (triangle! out a b c color) (triangle! out a c d color))))

(defn- tetra! [out x y z size color]
  (let [a [x (+ y size) z] b [(- x size) (- y size) (+ z size)]
        c [(+ x size) (- y size) (+ z size)] d [x (- y size) (- z size)]]
    (doseq [[p q r] [[a b c] [a c d] [a d b] [b d c]]] (triangle! out p q r color))))

(defn- scene-geometry [kind]
  (let [v (atom []) c colors]
    (case kind
      0 (do (cube! v 0 0 0 5.8 1.5 1.5 (:blue c))
            (doseq [i (range 6)] (cube! v (+ -2.3 (* i 0.9)) 0.1 0 0.25 0.25 0.25 (:cyan c)))
            (cube! v -3.5 0 0 0.3 2.6 2.6 (:white c)))
      1 (do (cube! v -2 -1 0 0.8 2.5 1.2 (:white c)) (cube! v 0.3 0.1 0 4.8 0.35 0.65 (:cyan c))
            (cube! v 2.5 -0.8 0 0.3 1.6 0.3 (:orange c)) (cube! v 2.5 -1.7 0 0.8 0.2 0.8 (:orange c)))
      2 (do (doseq [x [-2 0 2]] (cube! v x -0.7 0 0.7 1.4 1.4 (:orange c)))
            (cube! v 0 0.35 0 5.4 0.25 1.2 (:gold c)) (cube! v 0 0.8 0 2 0.12 0.8 (:cyan c)))
      3 (doseq [x (range 4) y (range 3) z (range 3)]
          (cube! v (* (- x 1.5) 0.65) (* (- y 1) 0.65) (* (- z 1) 0.65) 0.3 0.3 0.3
                 [(+ 0.2 (* x 0.12)) (+ 0.35 (* y 0.15)) (+ 0.8 (* z 0.05))]))
      4 (do (cube! v 0 0 0 1.3 2.3 1.3 (:purple c))
            (doseq [i (range 12) :let [a (/ (* i js/Math.PI) 6)]]
              (cube! v (* (js/Math.cos a) 1.5) 0 (* (js/Math.sin a) 1.5) 0.25 1 0.25 (:cyan c)))
            (cube! v 0 0 0 3.8 0.12 0.12 (:gold c)))
      5 (do (doseq [i (range 4)] (cube! v (+ -2.4 (* i 1.6)) -0.5 0 1 1.2 1.3
                                        [(+ 0.1 (* i 0.12)) 0.45 (- 0.8 (* i 0.08))])
                                  (cube! v (+ -2.4 (* i 1.6)) 0.35 0 0.35 0.35 0.35 (:gold c)))
            (cube! v 0 -1.2 0 6 0.12 1.5 (:white c)))
      6 (do (doseq [i (range 18) :let [left? (< i 9) h (if left? 1.8 0.55)]]
              (cube! v (+ -3.4 (* i 0.4)) (+ -1 (/ h 2)) 0 0.34 h 1.4 (if left? (:blue c) (:orange c))))
            (cube! v 0.15 0.25 0 0.12 2.7 1.7 (:gold c)))
      7 (do (cube! v 0 -1.35 0 7 0.15 3 (:white c)) (cube! v 0 1.35 0 7 0.15 3 (:white c))
            (doseq [i (range 18) :let [a (* i 0.72) r (+ 0.45 (* i 0.04))]]
              (cube! v (+ -3 (* i 0.35)) (* (js/Math.sin a) r) (* (js/Math.cos a) r) 0.18 0.18 0.18
                     (if (odd? i) (:cyan c) (:purple c)))))
      8 (do (doseq [x (range 4) z (range 3)] (cube! v (+ -2.7 (* x 0.7)) -0.65 (- (* z 0.7) 0.7) 0.58 0.58 0.58 (:blue c)))
            (doseq [x (range 8) z (range 5)] (cube! v (+ 0.1 (* x 0.38)) -0.65 (- (* z 0.38) 0.76) 0.28 0.28 0.28 (if (< x 3) (:gold c) (:cyan c))))
            (tetra! v 0 0.65 0 0.55 (:orange c)))
      9 (do (doseq [i (range 15) :let [x (+ -3.2 (* i 0.46)) heat (- 1 (/ (js/Math.abs (- i 7)) 7)) y (* 0.28 (js/Math.sin (/ (* i js/Math.PI) 14)))]]
              (cube! v x y 0 0.42 0.5 0.8 [(+ 0.08 (* heat 0.92)) (- 0.72 (* heat 0.42)) (- 0.78 (* heat 0.66))]))
            (cube! v -3.65 0 0 0.35 2.4 1.5 (:white c)))
      10 (do (doseq [x (range 7) z (range 7)] (tetra! v (* (- x 3) 0.55) -0.9 (* (- z 3) 0.55) 0.18 [(+ 0.1 (* x 0.08)) (+ 0.25 (* z 0.07)) 0.85]))
             (doseq [i (range 16) :let [a (/ (* i js/Math.PI) 8)]] (cube! v (* (js/Math.cos a) 1.45) 0.55 (* (js/Math.sin a) 1.45) 0.18 0.65 0.18 (:gold c)))
             (cube! v 0 0.55 0 0.55 1.4 0.55 (:purple c)))
      11 (do (cube! v -0.35 -0.85 0 4.8 1.1 2.3 (:blue c)) (cube! v 0.35 0.45 0 4.8 1.1 2.3 (:orange c))
             (doseq [i (range 7)] (cube! v (+ -1.6 (* i 0.55)) -0.18 0 0.18 0.18 1.7 (:gold c))) (cube! v 2.9 0.45 0 1.3 0.16 0.16 (:cyan c)))
      12 (do (cube! v -1.75 0 0 3.1 2.8 0.7 (:blue c)) (cube! v 1.75 0 0 3.1 2.8 0.7 (:blue c))
             (cube! v 0 1.05 0 0.18 0.7 0.82 (:orange c)) (tetra! v 0 0.55 0 0.45 (:gold c)) (cube! v 0 -1.05 0 0.18 0.7 0.82 (:orange c)))
      13 (doseq [r (range 4) :let [cs [(:blue c) (:cyan c) (:purple c) (:orange c)]]]
           (cube! v (+ -2.7 (* r 1.8)) 0 0 1.45 1.7 2.2 (nth cs r))
           (when (< r 3) (cube! v (+ -1.8 (* r 1.8)) 0 0 0.18 2.15 2.55 (:gold c))))
      14 (do (doseq [i (range 10) :let [cs [(:blue c) (:cyan c) (:purple c) (:orange c) (:gold c)]]]
               (tetra! v (+ -2.7 (* (mod i 5) 1.35)) (+ -0.5 (* (quot i 5) 1.35)) (- (* (mod i 2) 0.45) 0.2) (+ 0.42 (* (mod i 3) 0.08)) (nth cs (mod i 5))))
             (triangle! v [-3 -1.45 -1.1] [3 -1.45 -1.1] [0 -1.45 2.2] (:white c)))
      nil)
    (js/Float32Array. (clj->js @v))))

(defn- vsub [a b] (mapv - a b))
(defn- dot [a b] (reduce + (map * a b)))
(defn- cross [[a b c] [x y z]] [(- (* b z) (* c y)) (- (* c x) (* a z)) (- (* a y) (* b x))])
(defn- norm [a] (let [l (js/Math.hypot.apply nil (clj->js a))] (mapv #(/ % l) a)))
(defn- perspective [fov aspect near far]
  (let [f (/ 1 (js/Math.tan (/ fov 2))) nf (/ 1 (- near far))]
    (js/Float32Array. #js [(/ f aspect) 0 0 0 0 f 0 0 0 0 (* (+ far near) nf) -1 0 0 (* 2 far near nf) 0])))
(defn- look-at [eye center up]
  (let [z (norm (vsub eye center)) x (norm (cross up z)) y (cross z x)]
    (js/Float32Array. #js [(x 0) (y 0) (z 0) 0 (x 1) (y 1) (z 1) 0 (x 2) (y 2) (z 2) 0 (- (dot x eye)) (- (dot y eye)) (- (dot z eye)) 1])))
(defn- mat-mul [a b]
  (let [o (js/Float32Array. 16)]
    (doseq [c (range 4) r (range 4)]
      (aset o (+ (* c 4) r) (+ (* (aget a r) (aget b (* c 4))) (* (aget a (+ 4 r)) (aget b (+ (* c 4) 1))) (* (aget a (+ 8 r)) (aget b (+ (* c 4) 2))) (* (aget a (+ 12 r)) (aget b (+ (* c 4) 3))))))
    o))

(def shader-code
  "struct U { mvp: mat4x4f, params: vec4f }
   @group(0) @binding(0) var<uniform> u: U;
   struct V { @location(0) position: vec3f, @location(1) color: vec3f }
   struct O { @builtin(position) position: vec4f, @location(0) color: vec3f }
   @vertex fn vs(v: V) -> O { var o: O; o.position = u.mvp * vec4f(v.position, 1.0); o.color = v.color; return o; }
   @fragment fn fs(o: O) -> @location(0) vec4f { return vec4f(o.color, 1.0); }")

(defn- init-renderer! [adapter]
  (-> (.requestDevice adapter)
      (.then
       (fn [device]
         (let [canvas (.getElementById js/document "kami-webgpu-canvas")
               status (.getElementById js/document "kami-runtime-status")
               frames-el (.getElementById js/document "kami-cljs-frames")
               draws-el (.getElementById js/document "kami-webgpu-draws")
               context (.getContext canvas "webgpu")
               format (.getPreferredCanvasFormat (.-gpu js/navigator))
               shader (.createShaderModule device #js {:code shader-code})
               pipeline (.createRenderPipeline device #js {:layout "auto"
                 :vertex #js {:module shader :entryPoint "vs" :buffers #js [#js {:arrayStride 24 :attributes #js [#js {:shaderLocation 0 :offset 0 :format "float32x3"} #js {:shaderLocation 1 :offset 12 :format "float32x3"}]}]}
                 :fragment #js {:module shader :entryPoint "fs" :targets #js [#js {:format format}]}
                 :primitive #js {:topology "triangle-list" :cullMode "back"}
                 :depthStencil #js {:format "depth24plus" :depthWriteEnabled true :depthCompare "less"}})
               usage js/GPUBufferUsage
               tex-usage js/GPUTextureUsage
               uniform (.createBuffer device #js {:size 80 :usage (bit-or (.-UNIFORM usage) (.-COPY_DST usage))})
               bind-group (.createBindGroup device #js {:layout (.getBindGroupLayout pipeline 0) :entries #js [#js {:binding 0 :resource #js {:buffer uniform}}]})
               scene (atom 0) geometry (atom nil) geometry-count (atom 0) depth-view (atom nil) frames (atom 0) draws (atom 0)]
           (doseq [button (array-seq (.querySelectorAll js/document "[data-kami-scene]"))]
             (.addEventListener button "click"
               (fn [_]
                 (reset! scene (js/Number (.. button -dataset -kamiScene)))
                 (doseq [b (array-seq (.querySelectorAll js/document "[data-kami-scene]"))] (.toggle (.-classList b) "active" (= b button)))
                 (set! (.-textContent (.getElementById js/document "kami-scene-name")) (nth scene-labels @scene))
                 (reset! geometry nil))))
           (letfn [(render [time]
                     (let [rect (.getBoundingClientRect canvas) scale (min (or js/devicePixelRatio 1) 2)
                           width (max 1 (js/Math.floor (* (.-width rect) scale))) height (max 1 (js/Math.floor (* (.-height rect) scale)))]
                       (when (or (not= (.-width canvas) width) (not= (.-height canvas) height))
                         (set! (.-width canvas) width) (set! (.-height canvas) height)
                         (.configure context #js {:device device :format format :alphaMode "opaque"})
                         (reset! depth-view (.createView (.createTexture device #js {:size #js [width height 1] :format "depth24plus" :usage (.-RENDER_ATTACHMENT tex-usage)}))))
                       (when-not @geometry
                         (let [data (scene-geometry @scene) buffer (.createBuffer device #js {:size (max 4 (.-byteLength data)) :usage (bit-or (.-VERTEX usage) (.-COPY_DST usage))})]
                           (.writeBuffer (.-queue device) buffer 0 data) (reset! geometry buffer) (reset! geometry-count (/ (.-length data) 6))))
                       (swap! frames inc) (swap! draws inc)
                       (let [angle (* time 0.00025) eye [(* (js/Math.sin angle) 8) 5 (* (js/Math.cos angle) 8)]
                             mvp (mat-mul (perspective (/ js/Math.PI 4) (/ width height) 0.1 100) (look-at eye [0 0 0] [0 1 0]))
                             u (js/Float32Array. 20) encoder (.createCommandEncoder device)]
                         (.set u mvp) (aset u 16 time) (aset u 17 @draws) (aset u 18 @scene) (.writeBuffer (.-queue device) uniform 0 u)
                         (let [pass (.beginRenderPass encoder #js {:colorAttachments #js [#js {:view (.createView (.getCurrentTexture context)) :loadOp "clear" :storeOp "store" :clearValue #js {:r 0.015 :g 0.03 :b 0.06 :a 1}}]
                                                                        :depthStencilAttachment #js {:view @depth-view :depthClearValue 1 :depthLoadOp "clear" :depthStoreOp "store"}})]
                           (.setPipeline pass pipeline) (.setBindGroup pass 0 bind-group) (.setVertexBuffer pass 0 @geometry) (.draw pass @geometry-count) (.end pass)
                           (.submit (.-queue device) #js [(.finish encoder)]))
                         (set! (.-textContent frames-el) @frames) (set! (.-textContent draws-el) @draws)
                         (js/requestAnimationFrame render))))]
             (set! (.-textContent status) "ClojureScript direct WebGPU rendering active")
             (set! (.. status -dataset -state) "ok")
             (js/requestAnimationFrame render)))))))

(defn init! []
  (let [status (.getElementById js/document "kami-runtime-status") gpu (.-gpu js/navigator)]
    (if-not gpu
      (do (set! (.-textContent status) "WebGPU unavailable; use a WebGPU-capable browser.") (set! (.. status -dataset -state) "fallback"))
      (-> (.requestAdapter gpu)
          (.then (fn [adapter] (if adapter (init-renderer! adapter) (throw (js/Error. "No WebGPU adapter available")))))
          (.catch (fn [error] (js/console.error error) (set! (.-textContent status) (str "WebGPU initialization failed: " (.-message error))) (set! (.. status -dataset -state) "error")))))))

(init!)
