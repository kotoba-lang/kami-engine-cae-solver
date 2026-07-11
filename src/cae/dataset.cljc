(ns cae.dataset
  "License-aware, solver-neutral dataset manifests and sample normalization."
  (:require [clojure.string :as str]))

(defn manifest [{:keys [id revision license domain files source]}]
  {:dataset/id (str id) :revision revision :license license :domain (keyword domain) :files (vec files) :source source :status :unverified})

(defn verify-manifest [m]
  (when-not (and (:dataset/id m) (:revision m) (:license m) (:domain m))
    (throw (ex-info "dataset manifest requires id, revision, license and domain" {:manifest m})))
  (assoc m :status :metadata-verified))

(def ^:private sha-pattern #"^[0-9a-f]{64}$")
(def ^:private revision-pattern #"^[0-9a-f]{40}$")
(def ^:private validation-usages #{:verification-reference :experimental-validation})

(defn audit-manifest
  "Strict v2 audit for immutable, license-aware external CAE evidence.
  This verifies metadata declarations only; use `verify-content` with hashes
  calculated from downloaded bytes before consuming any file."
  [m]
  (let [required [:dataset/id :provider :repository :revision :license :license-uri
                  :domain :data-origin :intended-use :commercial-use? :citation :files]
        missing (vec (remove #(some? (get m %)) required))
        files (:files m)
        file-errors (vec
                     (mapcat (fn [{:keys [path sha256 bytes split]}]
                               (cond-> []
                                 (not (seq path)) (conj {:path path :reason :missing-path})
                                 (not (re-matches sha-pattern (or sha256 ""))) (conj {:path path :reason :invalid-sha256})
                                 (not (and (integer? bytes) (pos? bytes))) (conj {:path path :reason :invalid-size})
                                 (nil? split) (conj {:path path :reason :missing-split}))) files))]
    (when (or (seq missing) (not (re-matches revision-pattern (or (:revision m) "")))
              (not (vector? files)) (seq file-errors))
      (throw (ex-info "dataset v2 manifest audit failed"
                      {:missing missing :revision (:revision m) :file-errors file-errors})))
    (assoc m :manifest-version 2 :status :metadata-audited)))

(defn immutable-download-urls [m]
  (let [m (audit-manifest m)
        base (case (:provider m)
               :hugging-face (str "https://huggingface.co/datasets/" (:repository m) "/resolve/" (:revision m))
               :github-raw (str "https://raw.githubusercontent.com/" (:repository m) "/" (:revision m))
               (throw (ex-info "unsupported immutable dataset provider" {:provider (:provider m)})))]
    (mapv (fn [{:keys [path]}] {:path path :url (str base "/" path)}) (:files m))))

(defn parse-nasa-ofi
  "Parse NASA TMR smooth-body-separation Oil Film Interferometry text.
  Returns the measured Cf and its per-sample absolute uncertainty e_Cf."
  [text]
  (let [number-pattern #"[-+]?\d+(?:\.\d+)?(?:[Ee][-+]?\d+)?"
        rows (->> (str/split-lines text)
                  (map str/trim)
                  (filter #(re-matches (re-pattern (str "(?:" number-pattern "\\s+){6}" number-pattern)) %))
                  (mapv (fn [line]
                          (let [[x y z dx dz cf uncertainty] (mapv #(double #?(:clj (Double/parseDouble %) :cljs (js/parseFloat %)))
                                                                   (re-seq number-pattern line))]
                            {:x-mm x :y-mm y :z-mm z :dx-mm dx :dz-mm dz
                             :skin-friction-coefficient cf :absolute-uncertainty uncertainty}))))]
    {:format :nasa-tmr-ofi-v1 :quantity :skin-friction-coefficient
     :uncertainty :per-sample-absolute :samples rows :sample-count (count rows)}))

(defn verify-content
  "Compare independently calculated `{path {:sha256 ... :bytes ...}}` facts."
  [manifest observed]
  (let [m (audit-manifest manifest)
        checks (mapv (fn [{:keys [path sha256 bytes]}]
                       (let [actual (get observed path)]
                         {:path path :expected-sha256 sha256 :actual-sha256 (:sha256 actual)
                          :expected-bytes bytes :actual-bytes (:bytes actual)
                          :passed? (and (= sha256 (:sha256 actual)) (= bytes (:bytes actual)))})) (:files m))
        passed? (and (seq checks) (every? :passed? checks))]
    (assoc m :content-checks checks :content-verified? (boolean passed?)
           :status (if passed? :content-verified :content-rejected))))

(defn qualification-eligibility
  "Determine whether a dataset may support an industrial validation claim.
  Synthetic/simulation data can verify regression but cannot independently
  validate physical accuracy. Non-commercial licenses are rejected here."
  [m]
  (let [reasons (cond-> []
                  (not= :content-verified (:status m)) (conj :content-not-verified)
                  (not (validation-usages (:intended-use m))) (conj :not-validation-use)
                  (not= :experiment (:data-origin m)) (conj :not-independent-experiment)
                  (not (true? (:commercial-use? m))) (conj :commercial-use-not-permitted)
                  (not= :independent (:validation-role m)) (conj :not-independent-from-solver)
                  (not (map? (:uncertainty m))) (conj :measurement-uncertainty-missing)
                  (not (some #{:validation :test} (map :split (:files m)))) (conj :held-out-split-missing))]
    {:dataset/id (:dataset/id m) :eligible? (empty? reasons) :reasons reasons
     :scope (:validation-scope m)}))

(defn sample [{:keys [dataset-id split index coordinates fields target]}]
  {:dataset/id (str dataset-id) :split (keyword (or split :unknown)) :index (long (or index 0)) :coordinates (vec coordinates) :fields (into {} fields) :target target :format :kotoba-physics-sample-v1})

(defn scalar-field [sample field]
  (let [values (get-in sample [:fields field])]
    {:dataset/id (:dataset/id sample) :field field :count (count values) :values (vec (map double values)) :units nil :status :unverified}))

(defn file-kind [path]
  (let [p (str/lower-case (str path))]
    (cond (str/ends-with? p ".h5") :hdf5 (str/ends-with? p ".vtu") :vtu (str/ends-with? p ".vtk") :vtk (str/ends-with? p ".msh") :gmsh (str/ends-with? p ".inp") :calculix :else :unknown)))
