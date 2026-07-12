(ns cae.sbse-block-mesh-test
  (:require [cae.sbse-block-mesh :as mesh]
            [clojure.test :refer [deftest is]]))

(deftest structured-volume-mesh-has-consistent-counts-and-patches
  (let [generated (mesh/block-mesh-dict {:nx 4 :ny 5 :nz 2})
        dict (:dict generated)]
    (is (= 40 (:cells generated)))
    (is (= 8 (:blocks generated)))
    (is (= 30 (:vertices generated)))
    (is (= 8 (count (re-seq #"(?m)^    hex " dict))))
    (is (= 6 (count (re-seq #"(?m)^    (?:inlet|outlet|floorBump|ceiling|sideA|sideB)$" dict))))
    (is (.contains dict "simpleGrading (1 1 20.00000000)"))))
