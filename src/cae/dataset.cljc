(ns cae.dataset
  "License-aware, solver-neutral dataset manifests and sample normalization."
  (:require [clojure.string :as str]))

(defn manifest [{:keys [id revision license domain files source]}]
  {:dataset/id (str id) :revision revision :license license :domain (keyword domain) :files (vec files) :source source :status :unverified})

(defn verify-manifest [m]
  (when-not (and (:dataset/id m) (:revision m) (:license m) (:domain m))
    (throw (ex-info "dataset manifest requires id, revision, license and domain" {:manifest m})))
  (assoc m :status :metadata-verified))

(defn sample [{:keys [dataset-id split index coordinates fields target]}]
  {:dataset/id (str dataset-id) :split (keyword (or split :unknown)) :index (long (or index 0)) :coordinates (vec coordinates) :fields (into {} fields) :target target :format :kotoba-physics-sample-v1})

(defn scalar-field [sample field]
  (let [values (get-in sample [:fields field])]
    {:dataset/id (:dataset/id sample) :field field :count (count values) :values (vec (map double values)) :units nil :status :unverified}))

(defn file-kind [path]
  (let [p (str/lower-case (str path))]
    (cond (str/ends-with? p ".h5") :hdf5 (str/ends-with? p ".vtu") :vtu (str/ends-with? p ".vtk") :vtk (str/ends-with? p ".msh") :gmsh (str/ends-with? p ".inp") :calculix :else :unknown)))
