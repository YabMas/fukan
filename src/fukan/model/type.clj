(ns fukan.model.type
  "Type vocabulary (MODEL.md §3.3). Six cases:

     Scalar    — atomic leaf with methodology-supplied name (opaque to substrate)
     Enum      — closed set of literal values
     Composite — Named(container) | Inline(fields)
     Collection — of: Type, semantics: Sequential | Unique | Keyed(K)
     Union     — sum of distinct Types
     Ref       — KernelPrimitive(kinds, where) | Substrate(within-kind, slot-kinds)

   Substrate stays generic at the target-language level. Per-target-language
   translation lives in the project layer (Plan 5)."
  )

;; -- FieldSpec ----------------------------------------------------------------

(defn make-field-spec
  "FieldSpec lives on Composite Inline shapes. Optionality is at slot level
   (not a Type case)."
  [name type-value optional?]
  {:name name, :type type-value, :optional (boolean optional?)})

;; -- Constructors -------------------------------------------------------------

(defn make-scalar
  "Atomic leaf. `name` is methodology-supplied and opaque to substrate."
  [name]
  {:case :type/scalar, :name name})

(defn make-enum
  "Closed set of literal string values."
  [values]
  {:case :type/enum, :values (vec values)})

(defn make-composite-named
  "Composite whose shape comes from a value-typed Container's fields."
  [container-id]
  {:case :type/composite, :shape {:case :shape/named, :container container-id}})

(defn make-composite-inline
  "Composite with anonymous inline fields (List<FieldSpec>)."
  [field-specs]
  {:case :type/composite, :shape {:case :shape/inline, :fields (vec field-specs)}})

(defn keyed
  "CollectionSemantics constructor for Keyed(K)."
  [key-type]
  {:case :semantics/keyed, :key-type key-type})

(defn make-collection
  "Collection of T with one of Sequential | Unique | Keyed(K) semantics.
   For Sequential / Unique pass the keyword; for Keyed pass (keyed K)."
  [of semantics]
  {:case :type/collection, :of of, :semantics semantics})

(defn make-union
  "Sum of distinct Types. Cases stay structurally distinct (no coercion)."
  [types]
  {:case :type/union, :types (vec types)})

(defn make-ref-kernel-primitive
  "Reference to a kernel primitive. `kinds` is a set of kernel-kind keywords:
   #{:container :event :actor :rule :operation :behaviour :boundary :intent :clause}.
   `opts` may include {:where #{TagRef…}} — admissible targets must carry all tags."
  ([kinds] (make-ref-kernel-primitive kinds {}))
  ([kinds {:keys [where]}]
   (cond-> {:case   :type/ref
            :target {:case :ref-target/kernel-primitive, :kinds (set kinds)}}
     (seq where) (assoc :where (set where)))))

(defn make-ref-substrate
  "Reference to a sub-substrate slot on a primitive of `within-kind`. `slot-kinds`
   is a set of substrate slot keywords (#{:field} in v0; :parameter deferred)."
  [within-kind slot-kinds]
  {:case   :type/ref
   :target {:case        :ref-target/substrate
            :within-kind within-kind
            :slot-kinds  (set slot-kinds)}})

;; -- Malli schemas ------------------------------------------------------------

(def ^:private kernel-kinds
  #{:container :event :actor :rule :operation :behaviour :boundary :intent :clause})

(def ^:private substrate-slot-kinds #{:field :parameter})

(def ^:private collection-semantics
  [:or {:description "Sequential, Unique, or Keyed(K)"}
   [:enum :sequential :unique]
   [:map [:case [:= :semantics/keyed]] [:key-type [:ref ::Type]]]])

(def ^:private ref-target
  [:multi {:dispatch :case}
   [:ref-target/kernel-primitive
    [:map [:case [:= :ref-target/kernel-primitive]]
     [:kinds [:set (into [:enum] kernel-kinds)]]]]
   [:ref-target/substrate
    [:map [:case [:= :ref-target/substrate]]
     [:within-kind (into [:enum] kernel-kinds)]
     [:slot-kinds  [:set (into [:enum] substrate-slot-kinds)]]]]])

(def ^:private composite-shape
  [:multi {:dispatch :case}
   [:shape/named  [:map [:case [:= :shape/named]]  [:container :string]]]
   [:shape/inline [:map [:case [:= :shape/inline]] [:fields [:vector [:ref ::FieldSpec]]]]]])

(def Type
  [:schema {:registry
            {::Type
             [:multi {:dispatch :case}
              [:type/scalar      [:map [:case [:= :type/scalar]]      [:name :string]]]
              [:type/enum        [:map [:case [:= :type/enum]]        [:values [:vector :string]]]]
              [:type/composite   [:map [:case [:= :type/composite]]   [:shape composite-shape]]]
              [:type/collection  [:map [:case [:= :type/collection]]  [:of [:ref ::Type]] [:semantics collection-semantics]]]
              [:type/union       [:map [:case [:= :type/union]]       [:types [:vector [:ref ::Type]]]]]
              [:type/ref         [:map [:case [:= :type/ref]]         [:target ref-target] [:where {:optional true} [:set :string]]]]]
             ::FieldSpec
             [:map
              [:name :string]
              [:type [:ref ::Type]]
              [:optional :boolean]]}}
   [:ref ::Type]])

(def FieldSpec [:schema {:registry (-> Type second :registry)} [:ref ::FieldSpec]])
