(ns cae.host
  "Host-process execution boundary for clean-room solver adapters."
  (:require [cae.protocol :as protocol]))

(defn run-job
  "Run a validated argv job on the JVM host; CLJS returns a pending descriptor."
  [job]
  (let [job (protocol/validate-job job) argv (protocol/launch-vector job)]
    #?(:clj
       (let [pb (ProcessBuilder. ^java.util.List argv)
             _ (when (:cwd job) (.directory pb (java.io.File. ^String (:cwd job))))
             env (.environment pb)]
         (doseq [[k v] (:environment job)] (.put env (str k) (str v)))
         (let [p (.start pb) out (slurp (.getInputStream p)) err (slurp (.getErrorStream p)) exit (.waitFor p)]
           {:status (if (zero? exit) :completed :failed) :exit-code exit :stdout out :stderr err :argv argv :protocol :host-execution-v1}))
       :cljs {:status :host-pending :argv argv :environment (protocol/rank-environment job 0) :protocol :host-execution-v1})))
