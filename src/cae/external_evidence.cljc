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

(defn calculix-contact-result
  "Parse the final contact increment from CalculiX DAT/STA/CVG and solver log."
  [{:keys [log-text dat-text sta-text cvg-text]}]
  (let [numbers #"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][-+]?\d+)?"
        final-time (some->> (re-seq (re-pattern (str "(?m)^\\s*1\\s+\\d+\\s+\\d+\\s+\\d+\\s+(" numbers ")\\s+(" numbers ")")) sta-text)
                            last second parse-number)
        top-blocks (re-seq (re-pattern (str "(?ms)total force \\(fx,fy,fz\\) for set TOP and time\\s+(" numbers ")\\s*\\n\\s*("
                                             numbers ")\\s+(" numbers ")\\s+(" numbers ")")) dat-text)
        contact-blocks (re-seq (re-pattern (str "(?ms)statistics for slave set UPPER_BOTTOM, master set LOWER_TOP and time\\s+(" numbers ")"
                                                 ".*?total surface force.*?\\n\\s*(" numbers ")\\s+(" numbers ")\\s+(" numbers ")"
                                                 ".*?area,\\s+normal force.*?\\n\\s*(" numbers ")\\s+(" numbers ")\\s+(" numbers ")")) dat-text)
        displacement-blocks (re-seq (re-pattern (str "(?ms)displacements \\(vx,vy,vz\\) for set TOP and time\\s+(" numbers ")"
                                                      ".*?\\n\\s*16\\s+(" numbers ")\\s+(" numbers ")\\s+(" numbers ")")) dat-text)
        top (when-let [[_ time fx fy fz] (last top-blocks)]
              {:time (parse-number time) :force [(parse-number fx) (parse-number fy) (parse-number fz)]})
        contact (when-let [[_ time fx fy fz area normal shear] (last contact-blocks)]
                  {:time (parse-number time) :surface-force [(parse-number fx) (parse-number fy) (parse-number fz)]
                   :area (parse-number area) :normal-force (parse-number normal) :shear-force (parse-number shear)})
        displacement (when-let [[_ time ux uy uz] (last displacement-blocks)]
                       {:time (parse-number time) :top-node-16 [(parse-number ux) (parse-number uy) (parse-number uz)]})
        contact-counts (mapv (comp long parse-number second)
                             (re-seq #"Number of contact spring elements=(\d+)" log-text))
        increments (count (re-seq #"(?m)^ increment \d+ attempt \d+" log-text))
        converged (count (re-seq #"(?m)^ convergence(?:;|$)" log-text))
        iterations (count (re-seq #"(?m)^ iteration \d+" log-text))
        final-cvg (last (re-seq (re-pattern (str "(?m)^\\s*1\\s+22\\s+1\\s+2\\s+(\\d+)\\s+(" numbers ")\\s+(" numbers ")")) cvg-text))]
    {:format :calculix-contact-result-v1
     :nlgeom? (boolean (re-find #"(?i)nonlinear geometric effects (?:are turned on|are taken into account)" log-text))
     :job-finished? (boolean (re-find #"Job finished" log-text)) :final-time final-time
     :increments increments :converged-increments converged :iterations iterations
     :maximum-contact-elements (when (seq contact-counts) (apply max contact-counts))
     :top top :contact contact :displacement displacement
     :final-convergence (when final-cvg {:contact-elements (long (parse-number (nth final-cvg 1)))
                                        :residual-force-percent (parse-number (nth final-cvg 2))
                                        :correction-displacement-percent (parse-number (nth final-cvg 3))})}))

(defn calculix-contact-checks [result]
  (let [top-fz (get-in result [:top :force 2])
        contact-fz (get-in result [:contact :surface-force 2])
        force-error (when (and (number? top-fz) (number? contact-fz)
                               (pos? (max (abs top-fz) (abs contact-fz))))
                      (/ (abs (- (abs top-fz) (abs contact-fz)))
                         (max (abs top-fz) (abs contact-fz))))
        checks {:job-finished? (:job-finished? result) :nlgeom? (:nlgeom? result)
                :final-time? (= 1.0 (:final-time result))
                :contact-active? (pos? (or (:maximum-contact-elements result) 0))
                :compression? (neg? (or (get-in result [:contact :normal-force]) 0.0))
                :prescribed-displacement? (= -0.2 (get-in result [:displacement :top-node-16 2]))
                :all-increments-converged? (= (:increments result) (:converged-increments result))
                :final-contact-elements? (pos? (or (get-in result [:final-convergence :contact-elements]) 0))
                :final-residual-force? (zero? (or (get-in result [:final-convergence :residual-force-percent]) ##Inf))
                :force-balance-relative-error force-error :force-balance-tolerance 1.0e-6
                :force-balance? (and (number? force-error) (<= force-error 1.0e-6))}
        booleans (dissoc checks :force-balance-relative-error :force-balance-tolerance)]
    {:checks checks :passed? (every? true? (vals booleans))}))

(defn calculix-plastic-result
  "Parse the load history and material state from a one-element CalculiX
  elastoplastic load/unload DAT, STA and solver log."
  [{:keys [log-text dat-text sta-text]}]
  (let [n #"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][-+]?\d+)?"
        pattern (fn [s] (re-pattern s))
        indexed (fn [matches value-fn]
                  (reduce (fn [m row] (assoc m (parse-number (second row)) (value-fn row))) {} matches))
        displacements (indexed
                       (re-seq (pattern (str "(?ms)displacements \\(vx,vy,vz\\) for set TOP and time\\s+(" n ")"
                                                ".*?\\n\\s*8\\s+(" n ")\\s+(" n ")\\s+(" n ")")) dat-text)
                       #(parse-number (nth % 4)))
        forces (indexed
                (re-seq (pattern (str "(?ms)total force \\(fx,fy,fz\\) for set TOP and time\\s+(" n ")"
                                         "\\s*\\n\\s*(" n ")\\s+(" n ")\\s+(" n ")")) dat-text)
                #(parse-number (nth % 4)))
        stresses (indexed
                  (re-seq (pattern (str "(?ms)stresses .*? for set SPECIMEN and time\\s+(" n ")"
                                           ".*?\\n\\s*1\\s+1\\s+(" n ")\\s+(" n ")\\s+(" n ")")) dat-text)
                  #(parse-number (nth % 4)))
        peeq (indexed
              (re-seq (pattern (str "(?ms)equivalent plastic strain .*?for set SPECIMEN and time\\s+(" n ")"
                                     ".*?\\n\\s*1\\s+1\\s+(" n ")")) dat-text)
              #(parse-number (nth % 2)))
        times (sort (keys forces))
        history (mapv (fn [time] {:time time :top-force-z (forces time)
                                  :top-displacement-z (displacements time)
                                  :stress-z (stresses time) :peeq (peeq time)}) times)
        peak (when (seq history) (apply max-key #(abs (:top-force-z %)) history))
        final (peek history)
        increments (count (re-seq #"(?m)^ increment \d+ attempt \d+" log-text))
        converged (count (re-seq #"(?m)^ convergence(?:;|$)" log-text))
        final-time (some->> (re-seq (pattern (str "(?m)^\\s*1\\s+\\d+\\s+\\d+\\s+\\d+\\s+(" n ")\\s+(" n ")")) sta-text)
                            last second parse-number)]
    {:format :calculix-plastic-cycle-v1
     :material-nonlinear? (boolean (re-find #"Nonlinear material laws are taken into account" log-text))
     :job-finished? (boolean (re-find #"Job finished" log-text))
     :increments increments :converged-increments converged :final-time final-time
     :history-count (count history) :peak peak :final final
     :maximum-peeq (when (seq history) (apply max (map :peeq history)))}))

(defn calculix-plastic-checks [result]
  (let [peak (:peak result) final (:final result)
        checks {:job-finished? (:job-finished? result)
                :material-nonlinear? (:material-nonlinear? result)
                :final-time? (= 1.0 (:final-time result))
                :all-increments-converged? (= (:increments result) (:converged-increments result))
                :load-cycle-sampled? (> (:history-count result) 2)
                :peak-near-midcycle? (and peak (< 0.45 (:time peak) 0.55))
                :yield-exceeded? (and peak (> (:top-force-z peak) 250.0) (pos? (:peeq peak)))
                :unloaded? (and final (< (abs (:top-force-z final)) 1.0e-9)
                                (< (abs (:stress-z final)) 1.0e-9))
                :residual-deformation? (and final (> (:top-displacement-z final) 1.0e-5))
                :plastic-strain-retained? (and final (pos? (:peeq final))
                                                (= (:maximum-peeq result) (:peeq final)))}]
    {:checks checks :passed? (every? true? (vals checks))}))

(defn calculix-tip-displacement [dat-text]
  (let [section (some->> (re-seq #"(?ms)displacements \(vx,vy,vz\) for set TIP and time\s+([0-9.Ee+-]+)\s*\n(.*?)(?=\n\s*INCREMENT|\z)" dat-text)
                         last)
        time (some-> section second parse-number)
        rows (if-not section []
                 (mapv (fn [[_ node ux uy uz]]
                         {:node (long (parse-number node)) :ux (parse-number ux)
                          :uy (parse-number uy) :uz (parse-number uz)})
                       (re-seq #"(?m)^\s*(\d+)\s+([-+0-9.Ee]+)\s+([-+0-9.Ee]+)\s+([-+0-9.Ee]+)$"
                               (nth section 2))))
        values (mapv :uz rows)]
    {:time time :nodes rows :node-count (count rows)
     :mean-uz (when (seq values) (/ (reduce + values) (count values)))
     :spread-uz (when (seq values) (- (apply max values) (apply min values)))}))

(defn mesh-convergence-evidence
  "Compute the observed order, Richardson extrapolation and fine-grid GCI for
  three consistently refined scalar solutions ordered coarse to fine."
  [{:keys [levels refinement-ratio safety-factor]
    :or {refinement-ratio 2.0 safety-factor 1.25}}]
  (let [[f1 f2 f3] (map :value levels)
        d12 (- f1 f2) d23 (- f2 f3)
        monotonic? (pos? (* d12 d23))
        p (when (and monotonic? (not (zero? d23)))
            (/ (Math/log (abs (/ d12 d23))) (Math/log refinement-ratio)))
        denominator (when p (- (Math/pow refinement-ratio p) 1.0))
        extrapolated (when (and denominator (not (zero? denominator)))
                       (+ f3 (/ (- f3 f2) denominator)))
        relative-error (when (and denominator (not (zero? denominator)) (not (zero? f3)))
                         (/ (abs (/ (- f3 f2) f3)) denominator))
        gci (when relative-error (* safety-factor relative-error))
        checks {:three-levels? (= 3 (count levels))
                :all-runs-passed? (every? :passed? levels)
                :consistent-refinement? (= [4.0 2.0 1.0] (mapv :h-relative levels))
                :monotonic? monotonic? :positive-order? (and p (pos? p))
                :finite-gci? (and gci (not #?(:clj (Double/isNaN gci) :cljs (js/isNaN gci)))
                                  (pos? gci) (< gci 0.1))}]
    {:format :three-grid-gci-v1 :levels levels :refinement-ratio refinement-ratio
     :safety-factor safety-factor :observed-order p :richardson-extrapolated extrapolated
     :fine-relative-error-estimate relative-error :fine-gci gci
     :checks checks :passed? (every? true? (vals checks))}))

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
