(ns cae.vv-test
  (:require [clojure.test :refer [deftest is]]
            [cae.vv :as vv]))

(def evidence {:case-id "bar-001" :solver "kotoba-fem" :solver-version "0.1.0"
               :model-revision "git:abc" :input-id "sha256:input" :mesh-id "sha256:mesh"
               :executed-at "2026-07-11T00:00:00Z" :platform "cljs/webgpu"})

(deftest conservation-is-scale-aware-and-fails-closed
  (is (:passed? (vv/conservation-check {:quantity :mass :inputs [1000.0] :outputs [999.999999]
                                         :accumulation 0.000001 :tolerance 1.0e-10})))
  (is (false? (:passed? (vv/conservation-check {:inputs [10.0] :outputs [9.0] :tolerance 1.0e-3})))))

(deftest residual-needs-tolerance-and-reduction
  (is (:passed? (vv/residual-check {:history [1.0 1e-3 1e-7 1e-10]})))
  (is (false? (:passed? (vv/residual-check {:history [1.0 0.1 0.01] :absolute-tolerance 1e-6})))))

(deftest three-grid-gci-recovers-second-order-sequence
  (let [check (vv/grid-convergence-check {:coarse 1.16 :medium 1.04 :fine 1.01
                                           :refinement-ratio 2.0 :gci-tolerance 0.02})]
    (is (:passed? check))
    (is (< (abs (- 2.0 (:observed-order check))) 1e-12))
    (is (< (abs (- 1.0 (:richardson-extrapolated check))) 1e-12))))

(deftest qualification-requires-all-categories-and-provenance
  (let [checks [{:check :analytic-benchmark :passed? true}
                (vv/conservation-check {:inputs [1.0] :outputs [1.0]})
                (vv/residual-check {:history [1.0 1e-4 1e-10]})
                (vv/grid-convergence-check {:coarse 1.16 :medium 1.04 :fine 1.01
                                             :refinement-ratio 2.0 :gci-tolerance 0.02})]
        passed (vv/qualification-gate {:scope {:physics :linear-elasticity :element :axial-bar}
                                       :checks checks :evidence evidence})
        missing (vv/qualification-gate {:scope {:physics :linear-elasticity}
                                        :checks (butlast checks) :evidence evidence})]
    (is (:passed? passed))
    (is (= :verified-for-declared-scope (:status passed)))
    (is (false? (:passed? missing)))
    (is (= [:grid-convergence] (:missing-checks missing)))))

(deftest missing-traceability-never-passes
  (is (= [:mesh-id :executed-at :platform]
         (:missing (vv/evidence-check (dissoc evidence :mesh-id :executed-at :platform))))))
