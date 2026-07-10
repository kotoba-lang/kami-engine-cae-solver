(ns cae.high-fidelity-test
  (:require [clojure.test :refer [deftest is]]
            [cae.solver :as s]
            [cae.high-fidelity]
            [cae.verification]))

(deftest cfd-reference-kernels
  (let [r (s/solve {:solver {:kind :combustion-reaction} :temperature-K 1000 :fuel-mass-fraction 1.0 :dt-s 0.01 :steps 2 :pre-exponential-1-s 10 :activation-energy-J-mol 1000 :heat-release-J-kg 100})
        b (s/solve {:solver {:kind :boundary-layer} :velocity-m-s 10 :density-kg-m3 1 :viscosity-pa-s 1e-5 :distance-m 1})]
    (is (> (:temperature-K r) 1000))
    (is (pos? (:reynolds-number b)))))

(deftest nonlinear-and-parallel-contracts
  (let [f (s/solve {:solver {:kind :friction-contact} :normal-force-N 100 :tangential-force-N 80 :friction-coefficient 0.5 :penalty-stiffness-N-m 1000})
        p (s/solve {:solver {:kind :fem-elastoplastic} :strain 0.01 :youngs-modulus-Pa 2e5 :yield-stress-Pa 100 :hardening-Pa 1000})
        h (s/solve {:solver {:kind :mpi-halo-exchange} :partitions [{:owned [1 2]} {:owned [3 4]}]})]
    (is (:sliding? f))
    (is (:yielded? p))
    (is (= [3] (get-in h [:partitions 0 :ghost-values])))))

(deftest material-and-benchmark-verification
  (let [m (s/solve {:solver {:kind :material-database} :material :steel-ss304 :temperature-K 450})
        b (s/solve {:solver {:kind :benchmark-suite} :case :poiseuille})]
    (is (> (get-in m [:properties :youngs-modulus-Pa]) 1e11))
    (is (:passed? b))))
