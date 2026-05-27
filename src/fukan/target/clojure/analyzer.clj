(ns fukan.target.clojure.analyzer
  "Clojure Target Analyzer — Phase 6 of the build pipeline.

   Walks Clojure source files under a configured root, identifies
   function and def-shaped data-structure top-level forms, emits
   Code.* Artifacts plus projects edges with per-edge :validity from
   every spec primitive that should have a Clojure realisation.

   Per MODEL.md §7.6 and DESIGN.md 'Implementation linkage'."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [fukan.model.artifact :as a]
            [fukan.model.build :as build]
            [fukan.model.relations :as r]
            [fukan.target.clojure.address :as addr]
            [fukan.target.clojure.source :as source]))

;; ---------------------------------------------------------------------------
;; Source index
;; ---------------------------------------------------------------------------

(defn- walk-symbols
  "Walk `code-root` and return a flat vector of all source symbol records."
  [code-root]
  (if (and code-root (.exists (io/file code-root)))
    (let [files (source/find-clj-files code-root)]
      (vec (mapcat source/extract-symbols files)))
    []))

(defn- index-from-symbols
  "Build a map from [ns name kind] → source record from a flat symbol list."
  [symbols]
  (into {} (map (fn [s] [[(:ns s) (:name s) (:kind s)] s]) symbols)))

(defn- detect-duplicate-addresses
  "Group source-index entries by [ns name kind]; emit one violation per
   group of size > 1."
  [files-symbols]
  (let [grouped (group-by (fn [s] [(:ns s) (:name s) (:kind s)])
                          files-symbols)]
    (for [[[ns nm kind] group] grouped
          :when (> (count group) 1)]
      {:severity :error :phase :phase6
       :kind :phase6/duplicate-canonical-address
       :location {:ns ns :name nm :kind kind :files (mapv :file group)}
       :message (str "multiple " (name kind) " at " ns "/" nm
                     " across " (count group) " files: "
                     (str/join ", " (mapv :file group)))})))

(defn- find-symbol
  "Look up a symbol by ns + name + kind. Returns the source record or nil."
  [source-index ns-name local-name kind]
  (get source-index [ns-name local-name kind]))

;; ---------------------------------------------------------------------------
;; Artifact + projects-edge emission
;; ---------------------------------------------------------------------------

(defn- ensure-artifact
  "Ensure a Code.* artifact exists in :artifacts. Idempotent."
  [model artifact]
  (let [aid (a/artifact-identity artifact)]
    (if (get-in model [:artifacts aid])
      model
      (assoc-in model [:artifacts aid] artifact))))

(defn- emit-projects-edge
  "Emit a :relation/projects edge from from-endpoint to artifact-id, with
   :projection-kind metadata + :validity."
  [model from-endpoint artifact-id projection-kind validity]
  (let [edge (-> (r/make-edge :relation/projects
                              from-endpoint
                              (r/artifact-ref artifact-id)
                              {:projection-kind projection-kind})
                 (assoc :validity validity))]
    (build/add-edge model edge)))

;; ---------------------------------------------------------------------------
;; Per-primitive emission
;; ---------------------------------------------------------------------------

(defn- module-coord-of-primitive
  "Extract the module coord from a primitive id.
   Canvas stable-id format uses '/' as separator:
     'module.sub/name'         (Affordance)         → 'module.sub'
     'module.sub/type/Name'    (Type)               → 'module.sub'
     'module.sub/state/name'   (State)              → 'module.sub'
     'module.sub'              (Module — no '/')    → 'module.sub'."
  [primitive-id]
  (when (string? primitive-id)
    (if (str/includes? primitive-id "/")
      (first (str/split primitive-id #"/" 2))
      primitive-id)))

(defn- emit-function-projection
  "Emit one projects edge from a primitive (as :endpoint/primitive) to a
   Code.Function artifact at the canonical address.
   ALWAYS materializes the artifact so add-edge never throws for :absent edges.

   :public? is derived from the source-index lookup:
   - `:function` (defn)        → :public? true
   - `:function-private` (defn-) → :public? false
   - not found                 → :public? nil (the artifact is a spec claim with
                                  no realising code yet, so publicity is unknown)"
  [model source-index reg primitive-id primitive-kind projection-kind primitive-label]
  (let [module-coord (module-coord-of-primitive primitive-id)
        {:keys [ns name]} (addr/canonical reg primitive-kind projection-kind
                                          module-coord primitive-label)
        public-match  (find-symbol source-index ns name :function)
        private-match (find-symbol source-index ns name :function-private)
        public?  (cond public-match true private-match false :else nil)
        artifact (a/make-code-function "clojure" (str ns "/" name) nil public?)
        aid (a/artifact-identity artifact)
        validity (if public-match :valid :absent)
        m1 (ensure-artifact model artifact)]  ;; ALWAYS materialize
    (emit-projects-edge m1
                        (r/primitive-ref primitive-id)
                        aid
                        projection-kind
                        validity)))

;; ---------------------------------------------------------------------------
;; Top-level run
;; ---------------------------------------------------------------------------

(defn- operations [model]
  (filter (fn [[_ p]] (= :primitive/operation (:kind p)))
          (:primitives model)))

(defn- rules [model]
  (filter (fn [[_ p]] (= :primitive/rule (:kind p)))
          (:primitives model)))

(defn- entities-values-variants [model]
  ;; Two recognition paths so both legacy Allium-tagged Containers AND modern
  ;; canvas-projected Type primitives are seen:
  ;;   (a) Legacy: a :primitive/container that carries an Allium::Entity|Value|Variant
  ;;       tag-application (Allium analyzer output).
  ;;   (b) Canvas: a :primitive/container whose stable-id includes the canvas
  ;;       '/type/' segment (canvas-source's stable-id format for Type entities).
  ;;
  ;; The id-shape probe is what unblocks canvas records/values reaching schema
  ;; projection without depending on the retired Allium tag vocabulary.
  (let [kind-tag? (fn [ta]
                    (and (= "Allium" (-> ta :tag :namespace))
                         (#{"Entity" "Value" "Variant"} (-> ta :tag :name))
                         (= :target/primitive (-> ta :target :case))))
        tagged-ids (set (map (comp :id :target)
                             (filter kind-tag? (:tag-apps model))))
        canvas-type-id? (fn [id]
                          (and (string? id) (str/includes? id "/type/")))]
    (filter (fn [[id p]]
              (and (= :primitive/container (:kind p))
                   (or (contains? tagged-ids id)
                       (canvas-type-id? id))))
            (:primitives model))))

(defn- events [model]
  (filter (fn [[_ p]] (= :primitive/event (:kind p))) (:primitives model)))

(defn- emit-data-structure-projection
  "Emit one projects edge from a canvas Type primitive to a Code.DataStructure
   artifact. When a matching source-index symbol carries `:fields` (parsed
   from a Malli `[:map …]` schema or a defrecord field list), attach those
   fields to the artifact's `:sub` map so downstream drift checks can compare
   canvas field shape against code field shape."
  [model source-index reg primitive-id primitive-kind primitive-label]
  (let [module-coord (module-coord-of-primitive primitive-id)
        {:keys [ns name]} (addr/canonical reg primitive-kind :projection-kind/schema
                                          module-coord primitive-label)
        found       (find-symbol source-index ns name :data-structure)
        base-art    (a/make-code-data-structure "clojure" (str ns "/" name))
        artifact    (if-let [fs (:fields found)]
                      (assoc-in base-art [:sub :fields] fs)
                      base-art)
        aid (a/artifact-identity artifact)
        validity (if found :valid :absent)
        m1 (ensure-artifact model artifact)]  ;; ALWAYS materialize
    (emit-projects-edge m1
                        (r/primitive-ref primitive-id)
                        aid
                        :projection-kind/schema
                        validity)))

;; ---------------------------------------------------------------------------
;; Unprojected artifact materialisation (Plan 6 Task 13)
;; ---------------------------------------------------------------------------

(defn- symbol->artifact
  [{:keys [ns name kind file fields]}]
  (let [qname (str ns "/" name)
        loc   {:file file}]
    (case kind
      :function         (a/make-code-function "clojure" qname loc true)
      :data-structure   (cond-> (a/make-code-data-structure "clojure" qname loc)
                          (some? fields) (assoc-in [:sub :fields] fields))
      :function-private (a/make-code-function "clojure" qname loc false)
      nil)))

(defn- materialize-unprojected
  "For every symbol whose canonical artifact-id isn't already in :artifacts,
   add the artifact (no projects edge — these are unbound code per
   DESIGN.md 'Couplings')."
  [model symbols]
  (reduce
    (fn [m sym]
      (if-let [art (symbol->artifact sym)]
        (let [aid (a/artifact-identity art)]
          (if (get-in m [:artifacts aid])
            m
            (assoc-in m [:artifacts aid] art)))
        m))
    model
    symbols))

(defn run
  "Run the Clojure Analyzer on the model. Emits Code.Function and
   Code.DataStructure artifacts and :relation/projects edges with
   per-edge :validity.

   `code-root` is the source root (typically \"src\"). If nil or
   non-existent, source-index is empty and all edges land with
   :validity :absent.

   Plan 5 Task 6 covers function-shaped analyzers (Operation, Rule).
   Plan 5 Task 7 covers DataStructure (Entity/Value/Variant/Event) and
   Invariant analyzers. Phase 6 is non-gating.
   Plan 6 Task 13 materialises unprojected Code.* artifacts for defns
   not bound to any spec primitive."
  [model registry code-root]
  (let [symbols       (walk-symbols code-root)
        source-index  (index-from-symbols symbols)
        dup-violations (detect-duplicate-addresses symbols)
        m1 (reduce (fn [m [op-id op]]
                     (emit-function-projection
                       m source-index registry op-id :primitive/operation
                       :projection-kind/operation (:label op)))
                   model
                   (operations model))
        m2 (reduce (fn [m [rule-id rule]]
                     (emit-function-projection
                       m source-index registry rule-id :primitive/rule
                       :projection-kind/rule (:label rule)))
                   m1
                   (rules model))
        m3 (reduce (fn [m [c-id c]]
                     (emit-data-structure-projection
                       m source-index registry c-id :primitive/container (:label c)))
                   m2 (entities-values-variants m2))
        m4 (reduce (fn [m [ev-id ev]]
                     (emit-data-structure-projection
                       m source-index registry ev-id :primitive/event (:label ev)))
                   m3 (events m3))
        m-final (-> m4
                    (update :violations (fnil into []) dup-violations)
                    (materialize-unprojected symbols))]
    m-final))
