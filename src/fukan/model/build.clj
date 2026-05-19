(ns fukan.model.build
  "Model record + fixture-only construction API.

   The Model is the substrate's top-level container. Plan 1 ships an in-process
   API for building Models from primitive constructors directly — analyzers
   land in Plans 2/3/5.

   Plan-1 invariants enforced at construction time:
     - Primitive ids are unique
     - Edge endpoint primitives exist in the Model
     - Field substrate addresses point at real Fields on the named Container
     - Tag application targets exist
     - Artifact identities are unique

   Cross-altitude reference rules (K23/K24) and pipeline validation
   (Phase 4 sub-phases) land in Plan 3.

   Storage convention: primitives are stored top-level by id in :primitives;
   sub-substrate values (Field, Parameter, Definition, Effect) live inline on
   their host primitive. Artifacts are stored separately in :artifacts keyed
   by artifact-identity. Whether Clause and Intent (themselves kernel
   primitives per K15a) are stored top-level by id or referenced inline on
   their host is a Plan-2 decision driven by the Allium analyzer's needs."
  (:require [fukan.model.primitives :as p]
            [fukan.model.relations :as r]
            [fukan.model.artifact :as a]
            [fukan.model.vocabulary :as v]))

(defn empty-model
  "An empty Model — no primitives, no edges, no vocabulary, no artifacts."
  []
  {:primitives {}
   :edges      []
   :tag-defs   []
   :tag-apps   []
   :predicates []
   :renderers  []
   :artifacts  {}})

;; -- Primitive registry ------------------------------------------------------

(defn get-primitive [model id] (get-in model [:primitives id]))

(defn add-primitive
  "Add a primitive to the Model. Throws if its id collides with an existing
   primitive."
  [model primitive]
  (let [id (:id primitive)]
    (when (get-primitive model id)
      (throw (ex-info "Duplicate primitive id" {:id id})))
    (assoc-in model [:primitives id] primitive)))

;; -- Edge registry -----------------------------------------------------------

(defn- endpoint-resolves?
  "True iff the endpoint's target is present in the model. Primitives are
   looked up in :primitives; Artifacts in :artifacts; substrate addresses
   validate their container is in :primitives."
  [model endpoint]
  (case (:case endpoint)
    :endpoint/primitive (some? (get-primitive model (:id endpoint)))
    :endpoint/artifact  (contains? (:artifacts model) (:id endpoint))
    :endpoint/substrate
    (let [{:keys [container path]} endpoint
          c (get-primitive model container)
          [seg] path]
      (and (some? c)
           ;; V0 validates only the first (field) segment; deeper path steps
           ;; (parameter, etc.) are kernel-shaped but are not resolved in v0.
           (= (:slot seg) "field")
           (some #(= (:name %) (:key seg)) (:fields c))))))

(defn add-edge
  "Add an edge to the Model. Validates endpoints resolve to real primitives /
   substrate addresses. Multi-edges allowed iff identity differs (per §4.4);
   identity collisions are no-ops (preserve the existing edge)."
  [model edge]
  (when-not (endpoint-resolves? model (:from edge))
    (throw (ex-info "Unknown :from endpoint" {:edge edge})))
  (when-not (endpoint-resolves? model (:to edge))
    (throw (ex-info "Unknown :to endpoint" {:edge edge})))
  (let [existing-id-set (into #{} (map r/edge-identity (:edges model)))]
    (if (existing-id-set (r/edge-identity edge))
      model
      (update model :edges conj edge))))

(defn edges-by-kind [model relation-kind]
  (filter #(= relation-kind (:kind %)) (:edges model)))

(defn edges-from [model endpoint]
  (filter #(= endpoint (:from %)) (:edges model)))

(defn edges-to [model endpoint]
  (filter #(= endpoint (:to %)) (:edges model)))

;; -- Vocabulary --------------------------------------------------------------

(defn add-tag-definition [model td]
  (update model :tag-defs conj td))

(defn add-tag-application
  "Append a TagApplication. Validates the target resolves to a real primitive
   when the target is :target/primitive. Edge / substrate target resolution
   stays Plan-2-or-later concern (the analyzer is the only realistic source)."
  [model ta]
  (when (= :target/primitive (get-in ta [:target :case]))
    (when-not (get-primitive model (get-in ta [:target :id]))
      (throw (ex-info "TagApplication target not found"
                      {:target (:target ta)}))))
  (update model :tag-apps conj ta))

(defn add-predicate [model pr] (update model :predicates conj pr))

(defn add-renderer [model rr] (update model :renderers conj rr))

;; -- Artifacts ---------------------------------------------------------------

(defn get-artifact [model identity-tuple]
  (get-in model [:artifacts identity-tuple]))

(defn add-artifact [model artifact]
  (let [id (a/artifact-identity artifact)]
    (when (get-artifact model id)
      (throw (ex-info "Duplicate artifact identity" {:identity id})))
    (assoc-in model [:artifacts id] artifact)))

;; -- Malli schema ------------------------------------------------------------

(def Model
  [:map
   [:primitives [:map-of :string p/Primitive]]
   [:edges      [:vector r/Edge]]
   [:tag-defs   [:vector v/TagDefinition]]
   [:tag-apps   [:vector v/TagApplication]]
   [:predicates [:vector v/PredicateRegistration]]
   [:renderers  [:vector v/RendererRegistration]]
   [:artifacts  [:map-of :any a/Artifact]]])
