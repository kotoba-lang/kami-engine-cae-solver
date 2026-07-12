(ns verify-external-dataset
  "Download immutable external CAE evidence and verify every byte."
  (:require [cae.dataset :as dataset]
            [cae.sbse-geometry :as sbse]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]))

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bytes)))

(defn- sha256 [file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream file)]
      (let [buffer (byte-array 65536)]
        (loop []
          (let [n (.read in buffer)]
            (when (pos? n) (.update digest buffer 0 n) (recur))))))
    (hex (.digest digest))))

(defn- download! [client url target]
  (.mkdirs (.getParentFile target))
  (let [temporary (io/file (str (.getPath target) ".part"))
        request (-> (HttpRequest/newBuilder (URI/create url)) (.GET) (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofFile (.toPath temporary)))]
    (when-not (= 200 (.statusCode response))
      (throw (ex-info "external dataset download failed" {:url url :status (.statusCode response)})))
    (Files/move (.toPath temporary) (.toPath target)
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
    target))

(defn -main [& [dataset-id]]
  (let [catalog (-> "cae/datasets.edn" io/resource slurp edn/read-string)
        quarantine (-> "cae/dataset-quarantine.edn" io/resource slurp edn/read-string)
        requested-id (or dataset-id "aethron-cfd-pinn")
        manifest (or (first (filter #(= requested-id (:dataset/id %)) catalog))
                     (first (filter #(= requested-id (:dataset/id %)) quarantine))
                     (throw (ex-info "unknown dataset id" {:id dataset-id :available (mapv :dataset/id catalog)})))
        _ (dataset/require-standard-download! manifest)
        audited (dataset/audit-manifest manifest)
        root (io/file (or (System/getenv "HF_DATASETS_DIR") ".cache/huggingface-datasets") (:dataset/id audited))
        client (-> (HttpClient/newBuilder) (.followRedirects HttpClient$Redirect/ALWAYS) (.build))
        urls (dataset/immutable-download-urls audited)
        observed (into {}
                       (for [{:keys [path url]} urls
                             :let [target (download! client url (io/file root path))]]
                         [path {:sha256 (sha256 target) :bytes (.length target)}]))
        verified (dataset/verify-content audited observed)]
    (when-not (:content-verified? verified)
      (throw (ex-info "dataset content verification failed" {:checks (:content-checks verified)})))
    (when (= :nasa-ofi (:parser verified))
      (doseq [{:keys [path split]} (:files verified) :when (#{:validation :test} split)
              :let [parsed (dataset/parse-nasa-ofi (slurp (io/file root path)))]]
        (when-not (and (pos? (:sample-count parsed))
                       (every? #(pos? (:absolute-uncertainty %)) (:samples parsed)))
          (throw (ex-info "NASA OFI semantic verification failed" {:path path :parsed parsed}))))
      (doseq [{:keys [path split]} (:files verified) :when (= :geometry split)
              :let [summary (sbse/iges-summary (slurp (io/file root path)))] ]
        (when-not (and (:madcap? summary) (:inch-unit-declared? summary)
                       (pos? (:rational-b-spline-surface-records summary)))
          (throw (ex-info "NASA SBSE IGES semantic verification failed"
                          {:path path :summary summary})))))
    (let [semantic
          (when (= :nist-midas-1045 (:parser verified))
            (let [path (get-in verified [:files 0 :path])
                  parsed (dataset/parse-nist-midas-1045 (slurp (io/file root path)))
                  report (dataset/calibration-report parsed 50.0)]
              (when-not (and (pos? (:experiment-count parsed))
                             (> (:sample-count parsed) 1000)
                             (:passed? report))
                (throw (ex-info "NIST MIDAS semantic verification failed"
                                {:parsed (select-keys parsed [:experiment-count :sample-count])
                                 :calibration report})))
              {:parsed (select-keys parsed [:format :experiment-count :sample-count
                                            :measurement-uncertainty])
               :calibration report
               :qualification (dataset/qualification-eligibility verified)}))]
      (prn (cond-> (select-keys verified [:dataset/id :revision :license :status :content-checks])
             semantic (assoc :semantic-verification semantic))))))
