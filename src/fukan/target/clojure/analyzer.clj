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

(defn- safe-extract-symbols
  "Extract symbols from `path`, returning [] on read errors. The
   analyzer must not crash when a single source file is unreadable —
   fixtures and demos under `test/` sometimes contain intentionally
   malformed snippets. Per-file failures are skipped silently so the
   rest of the walk completes."
  [path]
  (try
    (source/extract-symbols path)
    (catch Exception _
      [])))

(defn- walk-symbols
  "Walk `code-root` and return a flat vector of all source symbol records.
   Per-file read errors are skipped rather than crashing the walk —
   fixture files (under `test/fixtures` etc.) sometimes carry
   intentionally malformed snippets that aren't legal Clojure but live
   on the test-side classpath."
  [code-root]
  (if (and code-root (.exists (io/file code-root)))
    (let [files (source/find-clj-files code-root)]
      (vec (mapcat safe-extract-symbols files)))
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

(defn- emit-property-test-projection
  "Emit one projects edge from a `:canvas/invariant` primitive to a
   Code.Function artifact at the property-test canonical address (under
   `test/`). Phase 8 Sprint 5 — the migrate path: invariants project to
   `clojure.test.check` `defspec` symbols in `test/<module>_test.clj`,
   not to predicate stubs in `src/`. The validity flips :valid when the
   test source-index carries a `:property-test` symbol at the canonical
   address."
  [model test-source-index reg primitive-id primitive-label]
  (let [module-coord (module-coord-of-primitive primitive-id)
        {:keys [ns name]} (addr/canonical reg :primitive/rule
                                          :projection-kind/property-test
                                          module-coord primitive-label)
        property-match (find-symbol test-source-index ns name :property-test)
        artifact (a/make-code-function "clojure" (str ns "/" name) nil nil)
        aid (a/artifact-identity artifact)
        validity (if property-match :valid :absent)
        m1 (ensure-artifact model artifact)]
    (emit-projects-edge m1
                        (r/primitive-ref primitive-id)
                        aid
                        :projection-kind/property-test
                        validity)))

;; ---------------------------------------------------------------------------
;; Top-level run
;; ---------------------------------------------------------------------------

(defn- operations [model]
  (filter (fn [[_ p]] (= :primitive/operation (:kind p)))
          (:primitives model)))

(defn- rules
  "Reactive rules — canvas `:canvas/rule` primitives. Excludes invariants,
   which canvas-source maps to the same `:primitive/rule` kernel kind but
   carry `:canvas-role :canvas/invariant` and (Phase 8 Sprint 5) project
   to test-side property-test artifacts instead of src-side predicate
   stubs."
  [model]
  (filter (fn [[_ p]]
            (and (= :primitive/rule (:kind p))
                 (not= :canvas/invariant (:canvas-role p))))
          (:primitives model)))

(defn- invariants
  "Timeless commitments — canvas `:canvas/invariant` primitives. Phase 8
   Sprint 5 migrates these from `src/`-side predicate stubs (the Phase 7
   default) to `test/`-side `clojure.test.check` property tests; the
   `:projection-kind/property-test` discriminator drives drift comparator
   and Layer A's `invariant-to-property-test` projection alike."
  [model]
  (filter (fn [[_ p]]
            (and (= :primitive/rule (:kind p))
                 (= :canvas/invariant (:canvas-role p))))
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

(defn- src-root->test-root
  "Conventional sibling of a src/ root: a peer `test/` directory.
   `code-root` of `src` (or any directory ending in `/src`) yields the
   matching `test` directory; otherwise returns nil (no test-side walk).

   The convention keeps invariant property-test artifacts colocated with
   the src/ implementation tree the analyzer already walks. Callers that
   pass an irregular code-root simply skip the test-side walk; analyzer
   semantics for src/-side artifacts are unaffected."
  [code-root]
  (when (string? code-root)
    (cond
      (= "src" code-root) "test"
      (str/ends-with? code-root "/src") (str (subs code-root 0 (- (count code-root) 3)) "test")
      :else nil)))

(defn run
  "Run the Clojure Analyzer on the model. Emits Code.Function and
   Code.DataStructure artifacts and :relation/projects edges with
   per-edge :validity.

   `code-root` is the source root (typically \"src\"). If nil or
   non-existent, source-index is empty and all edges land with
   :validity :absent.

   The analyzer additionally walks the peer `test/` directory (Phase 8
   Sprint 5) to recognise `defspec` property-test artifacts as the
   code-side counterpart of canvas invariants. Test-side artifacts use
   the address convention ns = `<module>-test`, symbol =
   `<kebab(label)>-property`; canvas invariants now project there
   instead of to `src/`-side predicate stubs.

   Plan 5 Task 6 covers function-shaped analyzers (Operation, Rule).
   Plan 5 Task 7 covers DataStructure (Entity/Value/Variant/Event) and
   Invariant analyzers. Phase 6 is non-gating.
   Plan 6 Task 13 materialises unprojected Code.* artifacts for defns
   not bound to any spec primitive."
  [model registry code-root]
  (let [symbols           (walk-symbols code-root)
        test-root         (src-root->test-root code-root)
        test-symbols      (walk-symbols test-root)
        source-index      (index-from-symbols symbols)
        test-source-index (index-from-symbols test-symbols)
        dup-violations    (detect-duplicate-addresses symbols)
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
        m2-inv (reduce (fn [m [inv-id inv]]
                         (emit-property-test-projection
                           m test-source-index registry inv-id (:label inv)))
                       m2
                       (invariants m2))
        m3 (reduce (fn [m [c-id c]]
                     (emit-data-structure-projection
                       m source-index registry c-id :primitive/container (:label c)))
                   m2-inv (entities-values-variants m2-inv))
        m4 (reduce (fn [m [ev-id ev]]
                     (emit-data-structure-projection
                       m source-index registry ev-id :primitive/event (:label ev)))
                   m3 (events m3))
        m-final (-> m4
                    (update :violations (fnil into []) dup-violations)
                    (materialize-unprojected symbols))]
    m-final))
