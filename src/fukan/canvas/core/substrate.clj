(ns fukan.canvas.core.substrate
  "Two substrate primitives: Node and Relation.
   Architecture-neutral; ships zero kind/role/relation/tag vocabulary.

   A Node carries its kind as the :node-kind data attribute
   (:Module/:Affordance/:State/:Type) rather than as a record class — that
   attribute is the seam the vocabulary/tag layer plugs into. The kind-specific
   fields (shape/role/type-kind/fields/…) are a union held on the one record;
   most are nil for any given kind.")

(defn- gen-id [] (random-uuid))

(defrecord Node [id name node-kind tag tags
                 shape role formal-expression returns-label doc
                 type-kind fields])
(defrecord Relation [from kind to tags])

(defn node
  "Generic, kind-agnostic Node constructor — the substrate primitive. `attrs` is
   a map supplying :node-kind and whatever payload fields the kind carries
   (:shape / :role / :formal-expression / :type-kind / :fields / :doc / …). The
   kind-specific constructors below are thin sugar over this."
  [attrs]
  (map->Node (merge {:id (gen-id) :tags #{}} attrs)))

;; Kind-specific sugar over `node`. These encode vocab-flavoured kinds
;; (Module/Affordance/State/Type); they remain only as a convenience for the
;; bespoke lifts and the test fixtures. The registry-driven `declare-node`
;; (followup (b)) supersedes them — at which point they retire with the lifts.
(defn module [name]
  (node {:name name :node-kind :Module :tag :canvas/module}))

(defn affordance [name & {:keys [shape role formal-expression doc returns-label]}]
  (node {:name name :node-kind :Affordance :tag role
         :shape shape :role role :formal-expression formal-expression
         :doc doc :returns-label returns-label}))

(defn state [name & {:keys [shape]}]
  (when-not shape
    (throw (ex-info "State requires :shape" {:name name})))
  (node {:name name :node-kind :State :tag :canvas/state :shape shape}))

(defn type-primitive [name & {:keys [doc]}]
  (node {:name name :node-kind :Type :tag :canvas/value :type-kind :atomic :doc doc}))

(defn type-record [name fields & {:keys [doc]}]
  (node {:name name :node-kind :Type :tag :canvas/record :type-kind :record :fields fields :doc doc}))

(defn relation [from kind to]
  (when-not (keyword? kind)
    (throw (ex-info "Relation kind must be a keyword" {:kind kind})))
  (->Relation from kind to #{}))

(defn apply-tag [entity tag]
  (update entity :tags conj tag))

;; Accessors
(defn id-of [e] (or (:id e) (when (instance? Relation e) [(:from e) (:kind e) (:to e)])))
(defn name-of [e] (:name e))
(defn role-of [e] (:role e))
(defn tag-of [e] (:tag e))
(defn shape-of [e] (:shape e))
(defn formal-expression-of [e] (:formal-expression e))
(defn doc-of [e] (:doc e))
(defn returns-label-of [e] (:returns-label e))
(defn from-of [r] (:from r))
(defn kind-of [r] (:kind r))
(defn to-of [r] (:to r))
(defn tags-of [e] (:tags e))

(defn primitive-kind [e]
  (cond
    (instance? Node e)     (:node-kind e)
    (instance? Relation e) :Relation))
