(ns cae.result-reader
  "Small, format-oriented readers for host solver result interchange."
  (:require [clojure.string :as str]))

(defn- number [s]
  #?(:clj (Double/parseDouble s) :cljs (js/parseFloat s)))

(defn scalar-values
  "Extract scalar tokens from an OpenFOAM uniform/nonuniform field body."
  [text]
  (->> (re-seq #"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?" (str text))
       (map number)
       vec))

(defn openfoam-field [{:keys [name location dimensions text]}]
  {:field (keyword name) :location (keyword (or location :cell)) :dimensions dimensions :values (scalar-values text) :format :openfoam-scalar-v1})

(defn calculix-table [{:keys [name columns text]}]
  (let [rows (->> (str/split-lines (str text))
                  (keep (fn [line]
                          (let [xs (->> (re-seq #"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?" line) (map number) vec)]
                            (when (seq xs) xs))))) ]
    {:table (keyword name) :columns (vec columns) :rows (vec rows) :format :calculix-table-v1}))

(defn result-manifest [{:keys [backend case-id files]}]
  {:backend (keyword backend) :case/id case-id :files (vec files) :format :cae-result-manifest-v1})
