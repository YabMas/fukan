(ns fukan.vocabulary.allium.analyzer
  "Per-file Allium AST → kernel content (with Allium::* tag applications).
   The entry point `analyze-file` takes a Model, an AST, and a coordinate;
   returns the Model extended with this file's content."
  (:require [clojure.set]
            [clojure.string :as str]
            [fukan.model.primitives :as p]
            [fukan.model.relations :as r]
            [fukan.model.type :as t]
            [fukan.model.vocabulary :as v]
            [fukan.model.build :as build]
            [fukan.vocabulary.allium.expression :as ae]))

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

(defn- analyze-external-entity
  [model decl module-coord _name-registry]
  (let [container-id (qualify module-coord (:name decl))
        container (p/make-container
                    {:id container-id
                     :label (:name decl)})]
    (-> model
        (build/add-primitive container)
        (build/add-tag-application
          (v/make-tag-application
            {:tag {:namespace "Allium" :name "ExternalEntity"}
             :target {:case :target/primitive :id container-id}})))))

(defn- actor-payload [decl]
  (let [identified-by-text
        (when-let [tr (:identified-by decl)]
          (str (:name tr)
               (when-let [w (:identified-by-where decl)]
                 (str " where " w))))
        within-text
        (when-let [tr (:within decl)]
          (:name tr))]
    (cond-> {}
      identified-by-text (assoc :identified_by identified-by-text)
      within-text        (assoc :within within-text))))

(defn- analyze-actor
  [model decl module-coord _name-registry]
  (let [actor-id (qualify module-coord (:name decl))
        actor (p/make-actor
                {:id actor-id
                 :label (:name decl)})]
    (-> model
        (build/add-primitive actor)
        (build/add-tag-application
          (v/make-tag-application
            {:tag {:namespace "Allium" :name "Actor"}
             :target {:case :target/primitive :id actor-id}
             :payload (actor-payload decl)})))))

;; ---------------------------------------------------------------------------
;; Surface analysis helpers
;; ---------------------------------------------------------------------------

(defn- surface-payload
  "Extract the Allium::Surface payload from the surface declaration's
   structured field-items."
  [decl _module-coord]
  (let [fields (:fields decl)
        facing  (->> fields (filter #(= :facing  (:field-kind %))) first)
        context (->> fields (filter #(= :context (:field-kind %))) first)
        timeout (->> fields (filter #(= :timeout (:field-kind %))) first)
        related (->> fields (filter #(= :related (:field-kind %))) first)]
    (cond-> {}
      facing
      (assoc :facing {:role          (:role facing)
                      :type-ref-name (-> facing :type-ref :name)
                      :type-ref-ns   (-> facing :type-ref :ns)})
      context
      (assoc :context {:role          (:role context)
                       :type-ref-name (-> context :type-ref :name)
                       :type-ref-ns   (-> context :type-ref :ns)})
      timeout
      (assoc :timeout (:rule-name timeout))
      related
      (assoc :related (mapv :name (:entries related))))))

(defn- resolve-contract-ref-id
  "Convert a contract-ref shape into a primitive id.
   Simple names (`{:name \"X\"}`) get qualified to the current module.
   Qualified names (`{:kind :qualified :ns A :name X}`) become `<ns>/<name>`
   placeholders for Task 13 to retarget."
  [contract-ref module-coord]
  (if (= :qualified (:kind contract-ref))
    (str (:ns contract-ref) "/" (:name contract-ref))
    (qualify module-coord (:name contract-ref))))

(defn- analyze-surface
  [model decl module-coord _name-registry]
  (let [container-id (qualify module-coord (:name decl))
        boundary     (p/make-boundary
                       {:id         (str container-id "::boundary")
                        :label      (:name decl)
                        :operations []})
        container    (p/make-container
                       (cond-> {:id       container-id
                                :label    (:name decl)
                                :boundary boundary}
                         (:description decl) (assoc :description (:description decl))))
        fields       (:fields decl)

        ;; Add the Surface container + Allium::Surface tag
        m0 (-> model
               (build/add-primitive container)
               (build/add-tag-application
                 (v/make-tag-application
                   {:tag     {:namespace "Allium" :name "Surface"}
                    :target  {:case :target/primitive :id container-id}
                    :payload (surface-payload decl module-coord)})))

        ;; provides: — create stub Event primitive then emit provides edge + Allium::Provides tag
        m1 (reduce (fn [m entry]
                     (let [event-name (:name entry)
                           event-id   (qualify module-coord event-name)
                           ;; Only add the event stub if it doesn't already exist
                           m' (if (build/get-primitive m event-id)
                                m
                                (build/add-primitive m (p/make-event {:id    event-id
                                                                       :label event-name
                                                                       :parameters []})))
                           edge    (r/make-edge :relation/provides
                                                (r/primitive-ref container-id)
                                                (r/primitive-ref event-id))
                           edge-id (r/edge-identity edge)]
                       (try (-> m'
                                (build/add-edge edge)
                                (build/add-tag-application
                                  (v/make-tag-application
                                    {:tag    {:namespace "Allium" :name "Provides"}
                                     :target {:case :target/edge :edge-identity edge-id}})))
                            (catch Exception _ m'))))
                   m0
                   (->> fields
                        (filter #(= :provides-block (:field-kind %)))
                        (mapcat :entries)))

        ;; exposes: — emit exposes Container -> Field(substrate) edges + Allium::Exposes tag
        m2 (reduce (fn [m exposed-path]
                     (let [segs                (str/split exposed-path #"\.")
                           container-name      (first segs)
                           field-name          (last segs)
                           target-container-id (qualify module-coord container-name)
                           edge    (r/make-edge :relation/exposes
                                                (r/primitive-ref container-id)
                                                (r/substrate-address target-container-id
                                                                      [{:slot "field" :key field-name}]))
                           edge-id (r/edge-identity edge)]
                       ;; Skip if endpoint resolution fails (cross-module or missing field)
                       (try (-> m
                                (build/add-edge edge)
                                (build/add-tag-application
                                  (v/make-tag-application
                                    {:tag    {:namespace "Allium" :name "Exposes"}
                                     :target {:case :target/edge :edge-identity edge-id}})))
                            (catch Exception _ m))))
                   m1
                   (->> fields
                        (filter #(= :exposes (:field-kind %)))
                        (mapcat :entries)))

        ;; contracts: fulfils / demands + Allium::Fulfils / Allium::Demands tags
        m3 (reduce (fn [m entry]
                     (let [verb        (:verb entry)
                           contract-id (resolve-contract-ref-id (:contract entry) module-coord)
                           relation    (case verb
                                         "fulfils" :relation/realises
                                         "demands" :relation/uses)
                           tag-name    (case verb
                                         "fulfils" "Fulfils"
                                         "demands" "Demands")
                           edge    (r/make-edge relation
                                                (r/primitive-ref container-id)
                                                (r/primitive-ref contract-id))
                           edge-id (r/edge-identity edge)]
                       (try (-> m
                                (build/add-edge edge)
                                (build/add-tag-application
                                  (v/make-tag-application
                                    {:tag    {:namespace "Allium" :name tag-name}
                                     :target {:case :target/edge :edge-identity edge-id}})))
                            (catch Exception _ m))))
                   m2
                   (->> fields
                        (filter #(= :contracts (:field-kind %)))
                        (mapcat :entries)))]
    m3))

;; ---------------------------------------------------------------------------
;; Rule analysis
;; ---------------------------------------------------------------------------

(defn- analyze-rule
  [model decl module-coord _name-registry]
  (let [rule-id (qualify module-coord (:name decl))
        clauses (:clauses decl)
        requires-clauses (filter #(= :requires (:clause-type %)) clauses)
        let-clauses      (filter #(= :let (:clause-type %)) clauses)
        ensures-clauses  (filter #(= :ensures (:clause-type %)) clauses)
        ;; Build assertions list — requires first, then ensures
        requires-exprs (mapv #(ae/parse (:body %)) requires-clauses)
        ensures-exprs  (mapv #(ae/parse (:body %)) ensures-clauses)
        all-assertions (vec (concat requires-exprs ensures-exprs))
        ;; Build definitions from let clauses. Parse `name = rhs` from body text.
        definitions (mapv (fn [let-clause]
                            (let [body (:body let-clause)
                                  [name rhs] (str/split body #"\s*=\s*" 2)]
                              (p/make-definition (str/trim name)
                                                 (ae/parse rhs))))
                          let-clauses)
        intent (p/make-intent
                 {:id (str rule-id "::intent")
                  :clauses []
                  :assertions all-assertions})
        body   (p/make-rule-body definitions [])
        rule   (p/make-rule
                 (cond-> {:id rule-id
                          :label (:name decl)
                          :intent intent
                          :body body}
                   (:description decl) (assoc :description (:description decl))))
        ;; Add Rule primitive and Allium::Rule tag
        m0 (-> model
               (build/add-primitive rule)
               (build/add-tag-application
                 (v/make-tag-application
                   {:tag    {:namespace "Allium" :name "Rule"}
                    :target {:case :target/primitive :id rule-id}})))
        ;; Apply Allium::Requires source-clause tags (substrate path by index)
        m1 (reduce (fn [m i]
                     (build/add-tag-application
                       m
                       (v/make-tag-application
                         {:tag    {:namespace "Allium" :name "Requires"}
                          :target {:case      :target/substrate
                                   :container rule-id
                                   :path      [{:slot "intent"}
                                               {:slot "assertions" :key (str i)}]}})))
                   m0
                   (range (count requires-exprs)))
        ;; Apply Allium::Ensures source-clause tags (offset by requires count)
        m2 (reduce (fn [m i]
                     (let [idx (+ i (count requires-exprs))]
                       (build/add-tag-application
                         m
                         (v/make-tag-application
                           {:tag    {:namespace "Allium" :name "Ensures"}
                            :target {:case      :target/substrate
                                     :container rule-id
                                     :path      [{:slot "intent"}
                                                 {:slot "assertions" :key (str idx)}]}}))))
                   m1
                   (range (count ensures-exprs)))
        ;; Apply Allium::Let source-clause tags
        m3 (reduce (fn [m i]
                     (build/add-tag-application
                       m
                       (v/make-tag-application
                         {:tag    {:namespace "Allium" :name "Let"}
                          :target {:case      :target/substrate
                                   :container rule-id
                                   :path      [{:slot "body"}
                                               {:slot "definitions" :key (str i)}]}})))
                   m2
                   (range (count definitions)))]
    m3))

;; ---------------------------------------------------------------------------
;; Contract analysis helpers
;; ---------------------------------------------------------------------------

(defn- analyze-operation
  "Convert a provides-entry (from a contract's provides-block entries) into an
   Operation primitive plus an Allium::Call tag application.
   Returns [updated-model operation-id]."
  [model op-entry module-coord contract-name name-registry]
  (let [op-name (:name op-entry)
        op-id (qualify module-coord (str contract-name "." op-name))
        params (mapv (fn [i param]
                       (p/make-parameter
                         (:name param)
                         (translate-type-ref (or (:type-ref param) (:type param)) module-coord name-registry)
                         false
                         i))
                     (range)
                     (or (:params op-entry) []))
        return-type (when-let [tr (:return op-entry)]
                      (translate-type-ref tr module-coord name-registry))
        op (p/make-operation
             (cond-> {:id op-id
                      :label op-name
                      :parameters params}
               return-type (assoc :return-type return-type)))]
    [(-> model
         (build/add-primitive op)
         (build/add-tag-application
           (v/make-tag-application
             {:tag {:namespace "Allium" :name "Call"}
              :target {:case :target/primitive :id op-id}})))
     op-id]))

(defn- analyze-contract
  [model decl module-coord name-registry]
  (let [contract-id (qualify module-coord (:name decl))
        ;; Contract operations come through provides-block entries
        op-entries (->> (:fields decl)
                        (filter #(= :provides-block (:field-kind %)))
                        (mapcat :entries))
        [model-with-ops op-ids]
        (reduce (fn [[m ids] op-entry]
                  (let [[m' op-id] (analyze-operation m op-entry module-coord (:name decl) name-registry)]
                    [m' (conj ids op-id)]))
                [model []]
                op-entries)
        boundary (p/make-boundary
                   {:id (str contract-id "::boundary")
                    :label (:name decl)
                    :operations op-ids})
        container (p/make-container
                    (cond-> {:id contract-id
                             :label (:name decl)
                             :boundary boundary}
                      (:description decl) (assoc :description (:description decl))))]
    (-> model-with-ops
        (build/add-primitive container)
        (build/add-tag-application
          (v/make-tag-application
            {:tag {:namespace "Allium" :name "Contract"}
             :target {:case :target/primitive :id contract-id}})))))

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
        ;; Pre-sort declarations so entity/value/variant/contract come before
        ;; surface (ensuring same-module contract endpoints are registered first).
        declaration-order {:entity 0 :value 0 :variant 0
                           :external-entity 1 :actor 1
                           :contract 2
                           :surface 3
                           :rule 4}
        sorted-decls (sort-by #(get declaration-order (:type %) 99)
                               (:declarations ast))
        ;; Process declarations in dependency order
        model-with-decls
        (reduce (fn [m decl]
                  (case (:type decl)
                    :entity          (analyze-entity-like m decl coordinate :entity  name-registry)
                    :value           (analyze-entity-like m decl coordinate :value   name-registry)
                    :variant         (analyze-entity-like m decl coordinate :variant name-registry)
                    :external-entity (analyze-external-entity m decl coordinate name-registry)
                    :actor           (analyze-actor m decl coordinate name-registry)
                    :contract        (analyze-contract m decl coordinate name-registry)
                    :surface         (analyze-surface m decl coordinate name-registry)
                    :rule            (analyze-rule m decl coordinate name-registry)
                    ;; Other declaration types: passthrough (Tasks 10–13)
                    m))
                model-with-module
                sorted-decls)
        ;; Collect child ids from Container declarations (entity, value, variant, external-entity, surface)
        ;; Actors are Actor primitives — not Containers — so they are NOT added to :children
        child-ids (->> (:declarations ast)
                       (filter #(#{:entity :value :variant :external-entity :surface :contract} (:type %)))
                       (map #(qualify coordinate (:name %)))
                       set)
        ;; Update module-Container's :children (direct assoc-in bypasses
        ;; add-primitive's duplicate-id check)
        updated-module (-> (build/get-primitive model-with-decls coordinate)
                           (update :children (fnil into #{}) child-ids))]
    (assoc-in model-with-decls [:primitives coordinate] updated-module)))
