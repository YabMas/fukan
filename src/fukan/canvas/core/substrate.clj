(ns fukan.canvas.core.substrate
  "Six substrate primitives: Module, Affordance, State, Type, Relation, Tag.
   Architecture-neutral; ships zero role/relation/tag vocabulary.")

(defn- gen-id [] (random-uuid))

(defrecord Module       [id name children tags])
(defrecord Affordance   [id name module shape role formal-expression tags])
(defrecord State        [id name module shape tags])
(defrecord Type         [id name kind fields tags])
(defrecord Relation     [from kind to tags])

(defn module [name]
  (->Module (gen-id) name #{} #{}))

(defn affordance [name & {:keys [module shape role formal-expression]}]
  (->Affordance (gen-id) name module shape role formal-expression #{}))

(defn state [name & {:keys [module shape]}]
  (when-not shape
    (throw (ex-info "State requires :shape" {:name name})))
  (->State (gen-id) name module shape #{}))

(defn type-primitive [name]
  (->Type (gen-id) name :atomic nil #{}))

(defn type-record [name fields]
  (->Type (gen-id) name :record fields #{}))

(defn relation [from kind to]
  (when-not (keyword? kind)
    (throw (ex-info "Relation kind must be a keyword" {:kind kind})))
  (->Relation from kind to #{}))

(defn apply-tag [entity tag]
  (update entity :tags conj tag))

;; Accessors
(defn id-of [e] (or (:id e) (when (instance? Relation e) [(:from e) (:kind e) (:to e)])))
(defn name-of [e] (:name e))
(defn module-of [e] (:module e))
(defn role-of [e] (:role e))
(defn shape-of [e] (:shape e))
(defn formal-expression-of [e] (:formal-expression e))
(defn from-of [r] (:from r))
(defn kind-of [r] (:kind r))
(defn to-of [r] (:to r))
(defn tags-of [e] (:tags e))

(defn primitive-kind [e]
  (cond
    (instance? Module e) :Module
    (instance? Affordance e) :Affordance
    (instance? State e) :State
    (instance? Type e) :Type
    (instance? Relation e) :Relation))
