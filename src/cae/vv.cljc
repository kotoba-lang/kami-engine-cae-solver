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
