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

(defn- build-source-index
  "Walk `code-root` and produce a map from [ns name kind] → source record."
  [code-root]
  (if (and code-root (.exists (io/file code-root)))
    (let [files (source/find-clj-files code-root)
          syms (mapcat source/extract-symbols files)]
      (into {} (map (fn [s] [[(:ns s) (:name s) (:kind s)] s]) syms)))
    {}))

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
   'module::sub::Name' → 'module'.
   'module' (no :: separator, id IS the module coord) → 'module'."
  [primitive-id]
  (when (string? primitive-id)
    (if (str/includes? primitive-id "::")
      (first (str/split primitive-id #"::" 2))
      primitive-id)))

(defn- emit-function-projection
  "Emit one projects edge from a primitive (as :endpoint/primitive) to a
   Code.Function artifact at the canonical address.
   ALWAYS materializes the artifact so add-edge never throws for :absent edges."
  [model source-index reg primitive-id primitive-kind projection-kind primitive-label]
  (let [module-coord (module-coord-of-primitive primitive-id)
        {:keys [ns name]} (addr/canonical reg primitive-kind projection-kind
                                          module-coord primitive-label)
        artifact (a/make-code-function "clojure" (str ns "/" name))
        aid (a/artifact-identity artifact)
        found (find-symbol source-index ns name :function)
        validity (if found :valid :absent)
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
  ;; A Container that carries an Allium::Entity OR Value OR Variant tag.
  (let [kind-tag? (fn [ta]
                    (and (= "Allium" (-> ta :tag :namespace))
                         (#{"Entity" "Value" "Variant"} (-> ta :tag :name))
                         (= :target/primitive (-> ta :target :case))))
        ids (set (map (comp :id :target)
                      (filter kind-tag? (:tag-apps model))))]
    (filter (fn [[id _]] (contains? ids id)) (:primitives model))))

(defn- events [model]
  (filter (fn [[_ p]] (= :primitive/event (:kind p))) (:primitives model)))

(defn- emit-data-structure-projection
  [model source-index reg primitive-id primitive-kind primitive-label]
  (let [module-coord (module-coord-of-primitive primitive-id)
        {:keys [ns name]} (addr/canonical reg primitive-kind :projection-kind/schema
                                          module-coord primitive-label)
        artifact (a/make-code-data-structure "clojure" (str ns "/" name))
        aid (a/artifact-identity artifact)
        found (find-symbol source-index ns name :data-structure)
        validity (if found :valid :absent)
        m1 (ensure-artifact model artifact)]  ;; ALWAYS materialize
    (emit-projects-edge m1
                        (r/primitive-ref primitive-id)
                        aid
                        :projection-kind/schema
                        validity)))

(defn- invariants
  "Walk every Container's intent.assertions. Return seq of
     {:container-id <id> :index <int> :label <string>}
   for assertions whose source-clause tag includes Allium::Invariant
   and whose :label is non-nil."
  [model]
  (let [inv-targets
        (->> (:tag-apps model)
             (filter (fn [ta]
                       (and (= "Allium" (-> ta :tag :namespace))
                            (= "Invariant" (-> ta :tag :name))
                            (= :target/substrate (-> ta :target :case)))))
             (map (fn [ta]
                    (let [t (:target ta)
                          path (:path t)
                          idx-step (last path)]
                      {:container-id (:container t)
                       :index (when (string? (:key idx-step))
                                (Long/parseLong (:key idx-step)))})))
             (filter :index))]
    (for [{:keys [container-id index]} inv-targets
          :let [c (get-in model [:primitives container-id])
                assertion (get-in c [:intent :assertions index])
                label (:label assertion)]
          :when (some? label)]
      {:container-id container-id :index index :label label})))

(defn- emit-invariant-projection
  [model source-index reg inv]
  (let [module-coord (module-coord-of-primitive (:container-id inv))
        {:keys [ns name]} (addr/canonical reg :primitive/expression
                                          :projection-kind/invariant
                                          module-coord (:label inv))
        artifact (a/make-code-function "clojure" (str ns "/" name))
        aid (a/artifact-identity artifact)
        found (find-symbol source-index ns name :function)
        validity (if found :valid :absent)
        m1 (ensure-artifact model artifact)  ;; ALWAYS materialize
        from-endpoint (r/substrate-address
                        (:container-id inv)
                        [{:slot "intent"} {:slot "assertions" :key (str (:index inv))}])]
    (-> m1
        (emit-projects-edge from-endpoint aid :projection-kind/invariant validity)
        (emit-projects-edge from-endpoint aid :projection-kind/test validity))))

(defn run
  "Run the Clojure Analyzer on the model. Emits Code.Function and
   Code.DataStructure artifacts and :relation/projects edges with
   per-edge :validity.

   `code-root` is the source root (typically \"src\"). If nil or
   non-existent, source-index is empty and all edges land with
   :validity :absent.

   Plan 5 Task 6 covers function-shaped analyzers (Operation, Rule).
   Plan 5 Task 7 covers DataStructure (Entity/Value/Variant/Event) and
   Invariant analyzers. Phase 6 is non-gating."
  [model registry code-root]
  (let [source-index (build-source-index code-root)
        m1 (reduce (fn [m [op-id op]]
                     (-> m
                         (emit-function-projection
                           source-index registry op-id :primitive/operation
                           :projection-kind/operation (:label op))
                         (emit-function-projection
                           source-index registry op-id :primitive/operation
                           :projection-kind/test (:label op))))
                   model
                   (operations model))
        m2 (reduce (fn [m [rule-id rule]]
                     (-> m
                         (emit-function-projection
                           source-index registry rule-id :primitive/rule
                           :projection-kind/rule (:label rule))
                         (emit-function-projection
                           source-index registry rule-id :primitive/rule
                           :projection-kind/test (:label rule))))
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
        m5 (reduce (fn [m inv] (emit-invariant-projection m source-index registry inv))
                   m4 (invariants m4))]
    m5))
