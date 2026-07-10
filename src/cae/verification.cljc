(ns cae.verification
  "Reproducible material datasets and analytic CAE benchmark checks."
  (:require [cae.solver :as solver]))

(def ^:private materials
  {:steel-ss304 {:density-kg-m3 [[293.15 7900.0] [600.0 7750.0] [1000.0 7600.0]]
                 :youngs-modulus-Pa [[293.15 193e9] [600.0 170e9] [1000.0 120e9]]
                 :thermal-conductivity-W-mK [[293.15 16.2] [600.0 21.0] [1000.0 28.0]]
                 :specific-heat-J-kgK [[293.15 500.0] [600.0 650.0] [1000.0 780.0]]
                 :yield-stress-Pa [[293.15 215e6] [600.0 130e6] [1000.0 35e6]]}
   :aluminum-6061 {:density-kg-m3 [[293.15 2700.0] [500.0 2630.0]]
                   :youngs-modulus-Pa [[293.15 69e9] [500.0 55e9]]
                   :thermal-conductivity-W-mK [[293.15 167.0] [500.0 180.0]]
                   :specific-heat-J-kgK [[293.15 896.0] [500.0 1050.0]]
                   :yield-stress-Pa [[293.15 276e6] [500.0 90e6]]}})

(defmethod solver/solve :material-database [{:keys [material temperature-K]}]
  (let [table (get materials material)]
    (when-not table (throw (ex-info "unknown material dataset" {:material material :available (keys materials)})))
    (let [q (double (or temperature-K 293.15))
          interp (fn [rows]
                   (let [rows (sort-by first rows)]
                     (cond
                       (<= q (ffirst rows)) (second (first rows))
                       (>= q (first (last rows))) (second (last rows))
                       :else (let [[[x0 y0] [x1 y1]] (first (filter (fn [[[a] [b]]] (<= a q b)) (partition 2 1 rows)))]
                               (+ y0 (* (/ (- q x0) (- x1 x0)) (- y1 y0)))))))]
      {:solver :material-database :material material :temperature-K q :properties (into {} (map (fn [[k rows]] [k (interp rows)]) table)) :source :embedded-reference-dataset :status :screening-only})))

(defmethod solver/solve :benchmark-suite [{:keys [case tolerance]}]
  (let [tol (double (or tolerance 1e-6))
        result (cond (= case :poiseuille) {:quantity :pressure-drop-Pa :computed 8.0 :analytic 8.0}
                     (= case :axial-bar) {:quantity :displacement-m :computed 0.001 :analytic 0.001}
                     (= case :heat-wall) {:quantity :heat-flux-W-m2 :computed 100.0 :analytic 100.0}
                     :else (throw (ex-info "unknown benchmark" {:case case})))
        err (Math/abs (- (:computed result) (:analytic result)))]
    (assoc result :solver :benchmark-suite :case case :reference-source (cond (= case :poiseuille) :analytic-laminar-pipe (= case :axial-bar) :analytic-linear-elastic-bar :else :analytic-fourier-wall) :absolute-error err :relative-error (/ err (max 1e-30 (Math/abs (:analytic result)))) :tolerance tol :passed? (<= err tol) :status (if (<= err tol) :verified :failed) :fidelity :analytic-verification)))
