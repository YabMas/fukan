(ns fukan.vocabulary.allium.analyzer
  "Per-file Allium AST → kernel content (with Allium::* tag applications).
   The entry point `analyze-file` takes a Model, an AST, and a coordinate;
   returns the Model extended with this file's content."
  (:require [clojure.set]
            [fukan.model.primitives :as p]
            [fukan.model.relations :as r]
            [fukan.model.type :as t]
            [fukan.model.vocabulary :as v]
            [fukan.model.build :as build]))

;; ---------------------------------------------------------------------------
;; Type translation
;; ---------------------------------------------------------------------------

(def ^:private builtin-scalars
  #{"String" "Integer" "Boolean" "DateTime" "Text"})

(defn- qualify [module-coord local-name]
  (str module-coord "::" local-name))

(defn- translate-type-ref
  "Convert Plan-2a type-ref shape into a fukan.model.type Type value.
   `name-registry` is a map of local-name → declaration-type (#{:entity :value :variant}),
   used to resolve same-module type references. Optional wrappers are unwrapped
   at the FieldSpec level — this function returns just the inner Type."
  [tr module-coord name-registry]
  (case (:kind tr)
    :simple
    (let [n (:name tr)]
      (if (or (contains? builtin-scalars n)
              (not (contains? name-registry n)))
        (t/make-scalar n)
        (t/make-composite-named (qualify module-coord n))))

    :optional
    (translate-type-ref (:inner tr) module-coord name-registry)

    :generic
    (case (:name tr)
      "List"
      (t/make-collection
        (translate-type-ref (first (:params tr)) module-coord name-registry)
        :sequential)
      "Set"
      (t/make-collection
        (translate-type-ref (first (:params tr)) module-coord name-registry)
        :unique)
      "Map"
      (let [[k v] (:params tr)]
        (t/make-collection
          (translate-type-ref v module-coord name-registry)
          (t/keyed (translate-type-ref k module-coord name-registry))))
      ;; unknown generic → scalar placeholder
      (t/make-scalar (:name tr)))

    :union
    (t/make-union (mapv #(translate-type-ref % module-coord name-registry) (:members tr)))

    :qualified
    ;; Cross-module ref — Task 13. Placeholder:
    (t/make-scalar (str (:ns tr) "/" (:name tr)))

    :inline-obj
    (t/make-composite-inline
      (mapv (fn [f]
              (t/make-field-spec
                (:name f)
                (translate-type-ref (:type-ref f) module-coord name-registry)
                (= :optional (-> f :type-ref :kind))))
            (:fields tr)))))

(defn- field->kernel
  "Convert a Plan-2a field-item to a kernel Field value record. Returns nil
   for non-:typed field-items (annotations, invariants, etc. — handled by
   later tasks)."
  [field-item module-coord name-registry]
  (when (= :typed (:field-kind field-item))
    (let [tr       (:type-ref field-item)
          optional? (= :optional (:kind tr))
          inner-type (translate-type-ref tr module-coord name-registry)]
      (p/make-field (:name field-item) inner-type optional?))))

;; ---------------------------------------------------------------------------
;; Name registry
;; ---------------------------------------------------------------------------

(defn- collect-name-registry
  "First pass: build a map of local-name → declaration-type ({:entity :value :variant})
   for all entity/value/variant declarations in this AST. Used to resolve
   same-module simple-name type references."
  [ast]
  (into {}
        (keep (fn [d]
                (when (#{:entity :value :variant} (:type d))
                  [(:name d) (:type d)]))
              (:declarations ast))))

;; ---------------------------------------------------------------------------
;; Declaration handlers
;; ---------------------------------------------------------------------------

(defn- analyze-entity-like
  "Handle entity/value/variant declarations. `decl-type` is :entity/:value/:variant."
  [model decl module-coord decl-type name-registry]
  (let [container-id (qualify module-coord (:name decl))
        all-fields   (vec (keep #(field->kernel % module-coord name-registry)
                                (:fields decl)))
        container    (p/make-container
                       (cond-> {:id container-id
                                :label (:name decl)
                                :fields all-fields}
                         (:description decl) (assoc :description (:description decl))))
        tag-name     (case decl-type
                       :entity  "Entity"
                       :value   "Value"
                       :variant "Variant")]

    ;; Variant field-collision check (only when parent is in this module)
    (when (= :variant decl-type)
      (let [parent-name   (-> decl :base :name)
            parent-id     (qualify module-coord parent-name)
            parent-c      (build/get-primitive model parent-id)]
        (when parent-c
          (let [parent-field-names  (set (map :name (:fields parent-c)))
                variant-field-names (set (map :name all-fields))
                colliding           (clojure.set/intersection parent-field-names variant-field-names)]
            (when (seq colliding)
              (throw (ex-info (str "Variant field collision: fields "
                                   colliding
                                   " appear in both variant "
                                   container-id
                                   " and parent "
                                   parent-id)
                              {:variant  container-id
                               :parent   parent-id
                               :colliding colliding})))))))

    (let [model' (-> model
                     (build/add-primitive container)
                     (build/add-tag-application
                       (v/make-tag-application
                         {:tag    {:namespace "Allium" :name tag-name}
                          :target {:case :target/primitive :id container-id}})))]
      ;; Variant emits specialises edge to parent
      (if (= :variant decl-type)
        (let [parent-id (qualify module-coord (-> decl :base :name))]
          (build/add-edge
            model'
            (r/make-edge :relation/specialises
                         (r/primitive-ref container-id)
                         (r/primitive-ref parent-id))))
        model'))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn analyze-file
  "Add this file's kernel content + Allium::* tag applications to `model`.
   The coordinate becomes the module-Container's id; Allium::Module tag
   is applied. Entity/value/variant declarations in (:declarations ast) are
   walked and converted to kernel Containers with appropriate Allium tags,
   fields, and edges."
  [model ast coordinate]
  (let [module-c (p/make-container
                   {:id coordinate
                    :label coordinate})
        model-with-module
        (-> model
            (build/add-primitive module-c)
            (build/add-tag-application
              (v/make-tag-application
                {:tag    {:namespace "Allium" :name "Module"}
                 :target {:case :target/primitive :id coordinate}})))
        name-registry (collect-name-registry ast)
        ;; Process entity-like declarations in order
        model-with-decls
        (reduce (fn [m decl]
                  (case (:type decl)
                    :entity  (analyze-entity-like m decl coordinate :entity  name-registry)
                    :value   (analyze-entity-like m decl coordinate :value   name-registry)
                    :variant (analyze-entity-like m decl coordinate :variant name-registry)
                    ;; Other declaration types: passthrough (Tasks 6–13)
                    m))
                model-with-module
                (:declarations ast))
        ;; Collect child ids from all entity-like declarations
        child-ids (->> (:declarations ast)
                       (filter #(#{:entity :value :variant} (:type %)))
                       (map #(qualify coordinate (:name %)))
                       set)
        ;; Update module-Container's :children (direct assoc-in bypasses
        ;; add-primitive's duplicate-id check)
        updated-module (-> (build/get-primitive model-with-decls coordinate)
                           (update :children (fnil into #{}) child-ids))]
    (assoc-in model-with-decls [:primitives coordinate] updated-module)))
