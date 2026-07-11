(ns cae.dataset-test
  (:require [cae.dataset :as dataset]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(def catalog (-> "cae/datasets.edn" io/resource slurp edn/read-string))
(defn entry [id] (first (filter #(= id (:dataset/id %)) catalog)))

(deftest immutable-manifest-and-content-verification
  (let [m (dataset/audit-manifest (entry "aethron-cfd-pinn"))
        observed (into {} (map (fn [{:keys [path sha256 bytes]}]
                                 [path {:sha256 sha256 :bytes bytes}]) (:files m)))
        verified (dataset/verify-content m observed)]
    (is (= :metadata-audited (:status m)))
    (is (every? #(.contains (:url %) (:revision m)) (dataset/immutable-download-urls m)))
    (is (= :content-verified (:status verified)))
    (is (every? :passed? (:content-checks verified)))
    (is (= :content-rejected (:status (dataset/verify-content m (assoc-in observed ["Flow.csv" :bytes] 1)))))))

(deftest training-and-noncommercial-data-cannot-qualify-industrial-accuracy
  (let [synthetic (dataset/verify-content
                   (entry "aethron-cfd-pinn")
                   (into {} (map (fn [{:keys [path sha256 bytes]}] [path {:sha256 sha256 :bytes bytes}])
                                 (:files (entry "aethron-cfd-pinn")))))
        real-noncommercial (assoc (entry "realpdebench") :status :content-verified)]
    (is (= [:not-validation-use :not-independent-experiment :not-independent-from-solver
            :measurement-uncertainty-missing :held-out-split-missing]
           (:reasons (dataset/qualification-eligibility synthetic))))
    (is (some #{:commercial-use-not-permitted}
              (:reasons (dataset/qualification-eligibility real-noncommercial))))))

(deftest malformed-or-floating-revision-is-rejected
  (testing "main is never an acceptable evidence revision"
    (is (thrown? Exception (dataset/audit-manifest (assoc (entry "aethron-cfd-pinn") :revision "main")))))
  (testing "missing hashes are rejected"
    (is (thrown? Exception
                 (dataset/audit-manifest (assoc-in (entry "aethron-cfd-pinn") [:files 0 :sha256] nil))))))
