(ns cae.high-fidelity
  "Portable high-fidelity reference kernels and parallelization contracts."
  (:require [cae.solver :as solver]))

(def ^:private gamma 1.4)
(defn- pressure [rho mom energy] (* (- gamma 1.0) (- energy (/ (* mom mom) (* 2.0 rho)))))
(defn- flux [u]
  (let [[rho mom energy] u p (pressure rho mom energy) vel (/ mom rho)]
    [mom (+ (* mom vel) p) (* (+ energy p) vel)]))
(defn- rusanov [l r dt dx]
  (let [[rl ml el] l [rr mr er] r pl (pressure rl ml el) pr (pressure rr mr er)
        vl (/ ml rl) vr (/ mr rr) al (Math/sqrt (* gamma (/ pl rl))) ar (Math/sqrt (* gamma (/ pr rr))) s (max (+ (Math/abs vl) al) (+ (Math/abs vr) ar)) fl (flux l) fr (flux r)]
    (mapv - (mapv + l (mapv #(* (/ dt dx) %) (mapv - fl fr))) (mapv #(* (/ dt dx) %) (mapv #(* s %) (mapv - r l))))))

(defmethod solver/solve :fvm-compressible [{:keys [cells dx-m dt-s steps initial-state]}]
  (let [n (long cells) dx (double dx-m) dt (double dt-s) init (vec (or initial-state (repeat n [1.0 0.0 250000.0])))
        advance (fn [u] (vec (for [i (range n)] (let [l (if (zero? i) (u i) (u (dec i))) r (if (= i (dec n)) (u i) (u (inc i)))] (rusanov l r dt dx)))))]
    {:solver :fvm-compressible :state (nth (iterate advance init) (long steps)) :cells n :flux :rusanov :equation :euler-1d :gamma gamma :fidelity :finite-volume-reference :status :screening-only}))

(defmethod solver/solve :turbulence-model [{:keys [velocity-m-s length-scale-m kinematic-viscosity-m2-s]}]
  (let [u (double velocity-m-s) l (double length-scale-m) nu (double kinematic-viscosity-m2-s) re (/ (* u l) nu) k (* 0.5 u u) eps (/ (Math/pow 0.09 0.75) l (Math/pow (max k 1e-12) 0.25))]
    {:solver :turbulence-model :reynolds-number re :turbulent-kinetic-energy-m2-s2 k :dissipation-m2-s3 eps :model :mixing-length :fidelity :closure-reference :status :screening-only}))

(defmethod solver/solve :adaptive-mesh [{:keys [coordinates field tolerance max-level]}]
  (let [tol (double (or tolerance 1e-3)) max-level (long (or max-level 1)) pairs (map vector coordinates (rest coordinates) field (rest field))
        marked (vec (keep-indexed (fn [i [[x0 x1] f0 f1]] (when (> (Math/abs (- f1 f0)) tol) i)) pairs))
        refined (vec (mapcat (fn [i] (let [x0 (nth coordinates i) x1 (nth coordinates (inc i)) xm (* 0.5 (+ x0 x1))] [x0 xm])) marked))]
    {:solver :adaptive-mesh :coordinates (if (pos? max-level) (vec (sort (distinct (concat coordinates refined)))) (vec coordinates)) :marked-elements marked :error-indicator :gradient :tolerance tol :fidelity :reference-adaptation :status :screening-only}))

(defmethod solver/solve :mpi-domain [{:keys [cells ranks rank ghost-width]}]
  (let [n (long cells) p (long ranks) r (long (or rank 0)) g (long (or ghost-width 1)) base (quot n p) rem (mod n p) start (+ (* r base) (min r rem)) count (+ base (if (< r rem) 1 0)) owned (vec (range start (+ start count))) ghosts (vec (concat (range (max 0 (- start g)) start) (range (+ start count) (min n (+ start count g)))))]
    {:solver :mpi-domain :rank r :ranks p :owned-indices owned :ghost-indices ghosts :neighbors (vec (concat (when (pos? r) [(dec r)]) (when (< r (dec p)) [(inc r)]))) :communication :halo-exchange :fidelity :parallel-contract :status :screening-only}))

(defmethod solver/solve :fem-elastoplastic [{:keys [strain youngs-modulus-Pa yield-stress-Pa hardening-Pa]}]
  (let [e (double youngs-modulus-Pa) sy (double yield-stress-Pa) h (double (or hardening-Pa 0.0)) eps (double strain) trial (* e eps) plastic? (> (Math/abs trial) sy) sign (if (neg? trial) -1.0 1.0) plastic-strain (if plastic? (* sign (/ (- (Math/abs trial) sy) (+ e h))) 0.0) stress (if plastic? (* sign (+ sy (* h (Math/abs plastic-strain)))) trial)]
    {:solver :fem-elastoplastic :stress-Pa stress :plastic-strain plastic-strain :yielded? plastic? :tangent-modulus-Pa (if plastic? (/ (* e h) (+ e h 1e-30)) e) :algorithm :return-mapping :fidelity :nonlinear-reference :status :screening-only}))
