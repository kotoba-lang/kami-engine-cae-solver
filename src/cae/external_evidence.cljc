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

(defn calculix-log [text]
  (let [version (second (re-find #"CalculiX Version ([0-9.]+)" text))
        elapsed (some-> (re-find #"Total CalculiX Time: ([0-9.Ee+-]+)" text) second parse-number)
        finished? (boolean (re-find #"Job finished" text))
        fatal? (boolean (re-find #"(?i)\*ERROR|fatal error" text))]
    {:format :calculix-log-v1 :version version :elapsed-seconds elapsed
     :finished? finished? :fatal-error? fatal?
     :complete? (boolean (and version elapsed finished? (not fatal?)))}))

(defn calculix-frd-displacements [text]
  (let [section (second (re-find #"(?ms)-4\s+DISP.*?\n(.*?)^\s*-3\s*$" text))
        number-pattern #"[-+]?\d+(?:\.\d+)?(?:[Ee][-+]?\d+)?"
        rows (if-not section []
                 (->> (re-seq #"(?m)^\s*-1\s+.*$" section)
                      (mapv (fn [line]
                              (let [[_ node ux uy uz] (mapv parse-number (re-seq number-pattern line))]
                                {:node (long node) :ux ux :uy uy :uz uz})))))]
    {:format :calculix-frd-displacement-v1 :samples rows :sample-count (count rows)
     :maximum-absolute-uz (when (seq rows) (apply max (map #(abs (:uz %)) rows)))
     :maximum-displacement (when (seq rows)
                             (apply max (map (fn [{:keys [ux uy uz]}]
                                               (Math/sqrt (+ (* ux ux) (* uy uy) (* uz uz)))) rows)))}))

(defn process-evidence
  [{:keys [solver solver-version image-digest command exit-code input-files result-files log-text result-text
           platform executed-at case-id]}]
  (let [log (case solver :openfoam (openfoam-log log-text) :calculix (calculix-log log-text) nil)
        result (when (= solver :calculix) (calculix-frd-displacements result-text))
        digest? (boolean (re-matches #"sha256:[0-9a-f]{64}" (or image-digest "")))
        hashed? (fn [files] (and (seq files) (every? #(and (re-matches #"[0-9a-f]{64}" (:sha256 %))
                                                            (pos? (:bytes %))) files)))
        complete? (and (zero? exit-code) digest? (seq command) (hashed? input-files)
                       (hashed? result-files) (:complete? log)
                       (or (not= solver :calculix) (pos? (:sample-count result))))]
    {:case-id case-id :solver solver :solver-version solver-version :image-digest image-digest
     :command (vec command) :exit-code exit-code :input-files (vec input-files)
     :result-files (vec result-files) :platform platform :executed-at executed-at
     :log log :result result :passed? (boolean complete?)
     :status (if complete? :external-process-verified :external-process-rejected)}))

(defn mpi-log
  "Parse rank audit output. Completeness requires one ordered record per rank,
  a common world size, and agreement between local and reduced sample counts."
  [text]
  (let [ranks (mapv (fn [[_ rank size samples partial]]
                      {:rank (long (parse-number rank)) :size (long (parse-number size))
                       :samples (long (parse-number samples)) :partial-sum (parse-number partial)})
                    (re-seq #"(?m)^KOTOBA_MPI_RANK rank=(\d+) size=(\d+) samples=(\d+) partial=([-+0-9.Ee]+)$" text))
        match (re-find #"(?m)^KOTOBA_MPI_RESULT size=(\d+) samples=(\d+) pi=([-+0-9.Ee]+) error=([-+0-9.Ee]+)$" text)
        result (when match
                 (let [[_ size samples pi error] match]
                   {:size (long (parse-number size)) :samples (long (parse-number samples))
                    :pi (parse-number pi) :absolute-error (parse-number error)}))
        size (:size result)
        complete? (and result (= size (count ranks))
                       (= (mapv :rank ranks) (vec (range size)))
                       (every? #(= size (:size %)) ranks)
                       (= (:samples result) (reduce + 0 (map :samples ranks))))]
    {:format :kotoba-mpi-log-v1 :ranks ranks :rank-count (count ranks) :result result
     :allreduce :sum :gather? (boolean (seq ranks)) :complete? (boolean complete?)}))

(defn mpi-process-evidence
  [{:keys [case-id solver-version image-digest command exit-codes worker-source output-files
           run-texts platform executed-at error-tolerance]}]
  (let [runs (mapv mpi-log run-texts)
        digest? (boolean (re-matches #"sha256:[0-9a-f]{64}" (or image-digest "")))
        hash-record? #(and (re-matches #"[0-9a-f]{64}" (:sha256 %)) (pos? (:bytes %)))
        deterministic? (and (= 2 (count run-texts)) (apply = run-texts))
        error (get-in (first runs) [:result :absolute-error])
        complete? (and (= [0 0] (vec exit-codes)) digest? (seq command)
                       (hash-record? worker-source) (seq output-files)
                       (every? hash-record? output-files) (every? :complete? runs)
                       deterministic? (number? error) (<= error error-tolerance))]
    {:case-id case-id :solver :openmpi :solver-version solver-version
     :image-digest image-digest :command (vec command) :exit-codes (vec exit-codes)
     :worker-source worker-source :output-files (vec output-files) :runs runs
     :deterministic? deterministic? :error-tolerance error-tolerance
     :platform platform :executed-at executed-at :passed? (boolean complete?)
     :status (if complete? :external-mpi-verified :external-mpi-rejected)}))
