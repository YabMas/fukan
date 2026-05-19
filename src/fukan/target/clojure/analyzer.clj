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
  "Extract the module coord from a primitive id like 'm::events::E' → 'm'."
  [primitive-id]
  (when (and (string? primitive-id) (str/includes? primitive-id "::"))
    (first (str/split primitive-id #"::" 2))))

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

(defn run
  "Run the Clojure Analyzer on the model. Emits Code.Function artifacts
   and :relation/projects edges with per-edge :validity.

   `code-root` is the source root (typically \"src\"). If nil or
   non-existent, source-index is empty and all edges land with
   :validity :absent.

   Plan 5 Task 6 covers function-shaped analyzers (Operation, Rule).
   Invariant + Entity/Event analyzers land in Task 7.
   Phase 6 is non-gating."
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
                   (rules model))]
    m2))
