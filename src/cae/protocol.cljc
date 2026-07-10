(ns cae.protocol
  "Clean-room, serializable protocols for MPI jobs, mesh exchange and coupling."
  (:require [clojure.string :as str]))

(defn mpi-message [{:keys [source target tag payload]}]
  {:source (long source) :target (long target) :tag (keyword tag) :payload payload :protocol :mpi-like-v1})

(defn collective [{:keys [operation ranks values]}]
  {:operation (keyword operation) :ranks (vec (map long ranks)) :values (vec values) :protocol :collective-v1})

(defn halo-message [{:keys [rank neighbor field indices values]}]
  {:rank (long rank) :neighbor (long neighbor) :field (keyword field) :indices (vec indices) :values (vec values) :protocol :halo-v1})

(defn job-spec [{:keys [executable arguments environment ranks threads cwd]}]
  {:executable (str executable) :arguments (vec (map str arguments)) :environment (or environment {}) :ranks (long (or ranks 1)) :threads (long (or threads 1)) :cwd cwd :protocol :host-job-v1})

(defn coupling-message [{:keys [participant mesh field time values]}]
  {:participant (keyword participant) :mesh mesh :field (keyword field) :time (double time) :values (vec values) :protocol :partitioned-coupling-v1})

(defn openfoam-case-path [root relative]
  (let [root (str/replace (str root) #"/+$" "") relative (str/replace (str relative) #"^/+" "")]
    (str root "/" relative)))
