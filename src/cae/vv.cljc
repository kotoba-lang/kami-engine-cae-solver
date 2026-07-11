(ns cae.vv
  "Numerical verification gates for declared-scope CAE qualification.

  These checks do not certify a solver. They produce auditable evidence for a
  specifically declared model, version, benchmark, mesh family and tolerance."
  (:require [cae.solver :as solver]))

(defn- finite? [x]
  (and (number? x) #?(:clj (Double/isFinite (double x)) :cljs (js/isFinite x))))

(defn relative-error [computed reference]
  (when-not (every? finite? [computed reference])
    (throw (ex-info "relative error requires finite values" {:computed computed :reference reference})))
  (/ (abs (- (double computed) (double reference)))
     (max 1.0e-30 (abs (double reference)))))

(defn conservation-check
  "Check integral balance: inputs + sources = outputs + sinks + accumulation."
  [{:keys [inputs outputs sources sinks accumulation tolerance quantity]
    :or {sources [] sinks [] accumulation 0.0 tolerance 1.0e-8}}]
  (let [values (concat inputs outputs sources sinks [accumulation tolerance])]
    (when-not (every? finite? values)
      (throw (ex-info "conservation check requires finite values" {:values values})))
    (let [incoming (+ (reduce + 0.0 inputs) (reduce + 0.0 sources))
          outgoing (+ (reduce + 0.0 outputs) (reduce + 0.0 sinks) accumulation)
          imbalance (- incoming outgoing)
          scale (max 1.0e-30 (abs incoming) (abs outgoing))
          relative (/ (abs imbalance) scale)]
      {:check :conservation :quantity quantity :incoming incoming :outgoing outgoing
       :imbalance imbalance :relative-imbalance relative :tolerance tolerance
       :passed? (<= relative tolerance)})))

(defn residual-check
  "Require a finite residual history, final tolerance and actual reduction."
  [{:keys [history absolute-tolerance minimum-reduction]
    :or {absolute-tolerance 1.0e-8 minimum-reduction 1.0e3}}]
  (let [history (vec history)]
    (when (or (< (count history) 2) (not-every? finite? history) (some neg? history))
      (throw (ex-info "residual history needs at least two finite non-negative samples" {:history history})))
    (let [initial (first history) final (peek history)
          reduction (/ (max initial 1.0e-300) (max final 1.0e-300))]
      {:check :iterative-convergence :initial-residual initial :final-residual final
       :iterations (dec (count history)) :reduction reduction
       :absolute-tolerance absolute-tolerance :minimum-reduction minimum-reduction
       :passed? (and (<= final absolute-tolerance) (>= reduction minimum-reduction))})))

(defn grid-convergence-check
  "Three-grid Richardson verification and fine-grid GCI (constant refinement ratio)."
  [{:keys [coarse medium fine refinement-ratio gci-tolerance safety-factor]
    :or {safety-factor 1.25}}]
  (when-not (and (every? finite? [coarse medium fine refinement-ratio gci-tolerance safety-factor])
                 (> refinement-ratio 1.0) (pos? gci-tolerance))
    (throw (ex-info "invalid three-grid convergence input" {:coarse coarse :medium medium :fine fine
                                                              :refinement-ratio refinement-ratio})))
  (let [d21 (- medium fine) d32 (- coarse medium)
        monotonic? (pos? (* d21 d32))
        ratio (when (and monotonic? (not (zero? d21))) (/ (abs d32) (abs d21)))
        order (when (and ratio (pos? ratio)) (/ (Math/log ratio) (Math/log refinement-ratio)))
        denominator (when order (- (Math/pow refinement-ratio order) 1.0))
        extrapolated (when (and denominator (not (zero? denominator))) (+ fine (/ (- fine medium) denominator)))
        gci (when (and denominator (not (zero? denominator)))
              (/ (* safety-factor (abs (- fine medium)))
                 (* (max (abs fine) 1.0e-30) (abs denominator))))]
    {:check :grid-convergence :coarse coarse :medium medium :fine fine
     :refinement-ratio refinement-ratio :monotonic? monotonic? :observed-order order
     :richardson-extrapolated extrapolated :fine-grid-gci gci :gci-tolerance gci-tolerance
     :passed? (boolean (and monotonic? order (pos? order) gci (<= gci gci-tolerance)))}))

(def required-evidence-keys
  [:case-id :solver :solver-version :model-revision :input-id :mesh-id :executed-at :platform])

(defn evidence-check [evidence]
  (let [missing (vec (remove #(let [v (get evidence %)] (and (some? v) (not= "" v))) required-evidence-keys))]
    {:check :traceability :required required-evidence-keys :missing missing :evidence evidence
     :passed? (empty? missing)}))

(defn qualification-gate
  "Fail closed unless every required V&V category has passing evidence."
  [{:keys [scope checks evidence]}]
  (let [checks (conj (vec checks) (evidence-check evidence))
        required #{:analytic-benchmark :conservation :iterative-convergence :grid-convergence :traceability}
        present (set (map :check checks)) missing (vec (sort (remove present required)))
        failed (vec (remove :passed? checks)) passed? (and (map? scope) (seq scope) (empty? missing) (empty? failed))]
    {:solver :qualification-gate :scope scope :checks checks :required-checks required
     :missing-checks missing :failed-checks failed :passed? passed?
     :status (if passed? :verified-for-declared-scope :not-qualified)
     :claim (if passed? :declared-scope-only :no-industrial-accuracy-claim)}))

(defmethod solver/solve :qualification-gate [case]
  (qualification-gate case))

(defn- tridiagonal-solve [a b c d]
  (let [n (count d)
        forward (loop [i 0 cp [] dp []]
                  (if (= i n) [cp dp]
                      (let [denom (- (nth b i) (if (zero? i) 0.0 (* (nth a (dec i)) (peek cp))))
                            ci (if (= i (dec n)) 0.0 (/ (nth c i) denom))
                            di (/ (- (nth d i) (if (zero? i) 0.0 (* (nth a (dec i)) (peek dp)))) denom)]
                        (recur (inc i) (conj cp ci) (conj dp di)))))
        [cp dp] forward]
    (loop [i (dec n) x (vec (repeat n 0.0))]
      (if (neg? i) x
          (recur (dec i) (assoc x i (- (nth dp i) (* (nth cp i) (if (= i (dec n)) 0.0 (nth x (inc i)))))))))))

(defmethod solver/solve :axial-bar-fe
  [{:keys [elements length-m area-m2 youngs-modulus-Pa distributed-load-N-m]}]
  (let [n (long elements) l (double length-m) area (double area-m2) e (double youngs-modulus-Pa)
        q0 (double distributed-load-N-m)]
    (when-not (and (>= n 4) (even? n) (every? pos? [l area e q0]))
      (throw (ex-info "axial-bar FE requires even elements >=4 and positive properties" {:elements elements})))
    (let [h (/ l n) stiffness (/ (* e area) h) unknowns (dec n)
          loads (mapv (fn [i] (* h q0 (Math/sin (/ (* Math/PI i) n)))) (range 1 n))
          interior (tridiagonal-solve (vec (repeat (dec unknowns) (- stiffness)))
                                      (vec (repeat unknowns (* 2 stiffness)))
                                      (vec (repeat (dec unknowns) (- stiffness))) loads)
          u (vec (concat [0.0] interior [0.0]))
          exact-scale (/ (* q0 l l) (* e area Math/PI Math/PI))
          exact (mapv (fn [i] (* exact-scale (Math/sin (/ (* Math/PI i) n)))) (range (inc n)))
          error (Math/sqrt (/ (reduce + (map (fn [a b] (let [d (- a b)] (* d d))) u exact)) (inc n)))
          residuals (mapv (fn [i] (- (* stiffness (+ (* 2 (u i)) (- (u (dec i))) (- (u (inc i))))) (loads (dec i)))) (range 1 n))
          rhs-norm (Math/sqrt (reduce + (map #(* % %) loads)))
          residual-norm (Math/sqrt (reduce + (map #(* % %) residuals)))
          reactions [(* (- stiffness) (u 1)) (* (- stiffness) (u (dec n)))]
          applied (reduce + loads)]
      {:solver :axial-bar-fe :model :linear-fe-sinusoidal-body-load :elements n :nodes (inc n)
       :displacement-m u :midpoint-displacement-m (u (quot n 2)) :exact-midpoint-m exact-scale
       :l2-error-m error :reaction-forces-N reactions :applied-load-N applied
       :algebraic-residual-norm residual-norm :residual-history [rhs-norm residual-norm]
       :fidelity :verification-benchmark :status :computed})))

(defmethod solver/solve :axial-bar-vv-study
  [{:keys [element-counts length-m area-m2 youngs-modulus-Pa distributed-load-N-m
           gci-tolerance benchmark-tolerance evidence]}]
  (let [counts (vec (or element-counts [8 16 32]))
        base {:length-m (or length-m 1.0) :area-m2 (or area-m2 0.01)
              :youngs-modulus-Pa (or youngs-modulus-Pa 2.0e11)
              :distributed-load-N-m (or distributed-load-N-m 1.0e6)}
        runs (mapv #(solver/solve (assoc base :solver {:kind :axial-bar-fe} :elements %)) counts)
        [coarse medium fine] (mapv :midpoint-displacement-m runs) fine-run (peek runs)
        reference (:exact-midpoint-m fine-run)
        analytic {:check :analytic-benchmark :computed fine :reference reference
                  :relative-error (relative-error fine reference)
                  :tolerance (or benchmark-tolerance 0.01)
                  :passed? (<= (relative-error fine reference) (or benchmark-tolerance 0.01))}
        conservation (conservation-check {:quantity :force-equilibrium :inputs [(:applied-load-N fine-run)]
                                          :outputs [(- (reduce + (:reaction-forces-N fine-run)))] :tolerance 1.0e-10})
        residual (residual-check {:history (:residual-history fine-run) :absolute-tolerance 1.0e-6
                                  :minimum-reduction 1.0e8})
        grid (grid-convergence-check {:coarse coarse :medium medium :fine fine
                                      :refinement-ratio 2.0 :gci-tolerance (or gci-tolerance 0.01)})
        checks [analytic conservation residual grid]
        gate (qualification-gate {:scope {:physics :linear-elasticity :dimension :1d
                                          :element :linear-axial :loading :sinusoidal-body-force}
                                  :checks checks :evidence evidence})]
    (assoc gate :runs runs :study {:element-counts counts :quantity :midpoint-displacement-m})))
