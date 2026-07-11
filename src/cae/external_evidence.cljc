(ns cae.external-evidence
  "Parse and qualify evidence emitted by real external CAE processes.")

(defn- parse-number [s]
  #?(:clj (Double/parseDouble s) :cljs (js/parseFloat s)))

(defn openfoam-log
  "Parse icoFoam/OpenFOAM execution diagnostics without treating log presence
  as success. A terminal `End`, time steps, residuals and continuity records
  are all required."
  [text]
  (let [times (mapv (comp parse-number second) (re-seq #"(?m)^Time = ([0-9.Ee+-]+)$" text))
        courant (mapv (fn [[_ mean maximum]] {:mean (parse-number mean) :maximum (parse-number maximum)})
                      (re-seq #"Courant Number mean: ([0-9.Ee+-]+) max: ([0-9.Ee+-]+)" text))
        residuals (mapv (fn [[_ field initial final iterations]]
                          {:field field :initial (parse-number initial) :final (parse-number final)
                           :iterations (long (parse-number iterations))})
                        (re-seq #"Solving for ([^,]+), Initial residual = ([0-9.Ee+-]+), Final residual = ([0-9.Ee+-]+), No Iterations ([0-9]+)" text))
        continuity (mapv (fn [[_ local global cumulative]]
                           {:local (parse-number local) :global (parse-number global)
                            :cumulative (parse-number cumulative)})
                         (re-seq #"time step continuity errors : sum local = ([0-9.Ee+-]+), global = ([0-9.Ee+-]+), cumulative = ([0-9.Ee+-]+)" text))
        ended? (boolean (re-find #"(?m)^End\s*$" text))
        complete? (and ended? (seq times) (seq courant) (seq residuals) (seq continuity))]
    {:format :openfoam-log-v1 :ended? ended? :complete? (boolean complete?)
     :time-steps (count times) :final-time (peek times)
     :maximum-courant (when (seq courant) (apply max (map :maximum courant)))
     :maximum-initial-residual (when (seq residuals) (apply max (map :initial residuals)))
     :final-continuity (peek continuity) :residual-records (count residuals)
     :final-residual-by-field (reduce (fn [m r] (assoc m (:field r) (dissoc r :field))) {} residuals)}))

(defn process-evidence
  [{:keys [solver solver-version image-digest command exit-code input-files result-files log-text
           platform executed-at case-id]}]
  (let [log (when (= solver :openfoam) (openfoam-log log-text))
        digest? (boolean (re-matches #"sha256:[0-9a-f]{64}" (or image-digest "")))
        hashed? (fn [files] (and (seq files) (every? #(and (re-matches #"[0-9a-f]{64}" (:sha256 %))
                                                            (pos? (:bytes %))) files)))
        complete? (and (zero? exit-code) digest? (seq command) (hashed? input-files)
                       (hashed? result-files) (:complete? log))]
    {:case-id case-id :solver solver :solver-version solver-version :image-digest image-digest
     :command (vec command) :exit-code exit-code :input-files (vec input-files)
     :result-files (vec result-files) :platform platform :executed-at executed-at
     :log log :passed? (boolean complete?)
     :status (if complete? :external-process-verified :external-process-rejected)}))
