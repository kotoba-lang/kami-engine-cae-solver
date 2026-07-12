(ns cae.thermoplastic-sensitivity-test
  (:require [cae.external-evidence :as evidence]
            [clojure.test :refer [deftest is]]))

(deftest independent-response-gates-do-not-hide-local-nonconvergence
  (let [levels [{:id :coarse :h-relative 4.0 :passed? true
                 :midpoint-temperature 400.0 :maximum-heat-flux 20.0 :maximum-peeq 0.004}
                {:id :medium :h-relative 2.0 :passed? true
                 :midpoint-temperature 410.0 :maximum-heat-flux 22.0 :maximum-peeq 0.003}
                {:id :fine :h-relative 1.0 :passed? true
                 :midpoint-temperature 415.0 :maximum-heat-flux 23.0 :maximum-peeq 0.0035}]
        result (evidence/thermoplastic-field-sensitivity
                {:levels levels :targets {:midpoint-temperature 0.1
                                          :maximum-heat-flux 0.1 :maximum-peeq 0.1}})]
    (is (:evidence-passed? result))
    (is (true? (get-in result [:qualified :midpoint-temperature])))
    (is (true? (get-in result [:qualified :maximum-heat-flux])))
    (is (false? (get-in result [:qualified :maximum-peeq])))
    (is (= :one-or-more-responses-not-qualified (:qualification-status result)))))
