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
            [fukan.model.expression :as e]
            [fukan.vocabulary.allium.expression :as ae]
            [fukan.vocabulary.allium.effect-canonicalise :as ec]))

;; ---------------------------------------------------------------------------
;; Type translation
;; ---------------------------------------------------------------------------

(def ^:private builtin-scalars
  #{"String" "Integer" "Boolean" "DateTime" "Text"})

(defn- qualify [module-coord local-name]
  (str module-coord "::" local-name))

(defn- event-id
  "Construct the slot-aware kernel id for an Event. Per K16/K16a, Event
   identity is `(qualifying Container, local-name)`; this is encoded in the
   id-string as `<container-id>::events::<local-name>`. Rules and Containers
   use the flat `<container-id>::<local-name>`; the `::events::` segment
   prevents rule/event name collisions within the same module."
  [module-coord event-name]
  (str module-coord "::events::" event-name))

(defn- translate-type-ref
  "Convert Plan-2a type-ref shape into a fukan.model.type Type value.
   `name-registry` is a map of local-name → declaration-type (#{:entity :value :variant}),
   used to resolve same-module type references. `use-aliases` is a map of
   alias → imported-coord for the host file; used to resolve `:qualified` refs
   (`alias/Foo` → `Composite-named(<imported-coord>::Foo)`). Optional wrappers
   are unwrapped at the FieldSpec level — this function returns just the inner
   Type."
  [tr module-coord name-registry use-aliases]
  (case (:kind tr)
    :simple
    (let [n (:name tr)]
      (if (or (contains? builtin-scalars n)
              (not (contains? name-registry n)))
        (t/make-scalar n)
        (t/make-composite-named (qualify module-coord n))))

    :optional
    (translate-type-ref (:inner tr) module-coord name-registry use-aliases)

    :generic
    (case (:name tr)
      "List"
      (t/make-collection
        (translate-type-ref (first (:params tr)) module-coord name-registry use-aliases)
        :sequential)
      "Set"
      (t/make-collection
        (translate-type-ref (first (:params tr)) module-coord name-registry use-aliases)
        :unique)
      "Map"
      (let [[k v] (:params tr)]
        (t/make-collection
          (translate-type-ref v module-coord name-registry use-aliases)
          (t/keyed (translate-type-ref k module-coord name-registry use-aliases))))
      ;; unknown generic → scalar placeholder
      (t/make-scalar (:name tr)))

    :union
    (t/make-union (mapv #(translate-type-ref % module-coord name-registry use-aliases) (:members tr)))

    :qualified
    ;; Cross-module ref. If the alias is known, resolve to the imported coord
    ;; and emit a Composite-named pointing at <coord>::<name>. The target
    ;; Container may or may not exist yet; pipeline stub-unification (§3.6)
    ;; reconciles after all files have been analyzed. If the alias is
    ;; unknown, leave a Scalar placeholder carrying the original text so
    ;; the projection can still surface the unresolved reference.
    (if-let [imported-coord (get use-aliases (:ns tr))]
      (t/make-composite-named (qualify imported-coord (:name tr)))
      (t/make-scalar (str (:ns tr) "/" (:name tr))))

    :inline-obj
    (t/make-composite-inline
      (mapv (fn [f]
              (t/make-field-spec
                (:name f)
                (translate-type-ref (:type-ref f) module-coord name-registry use-aliases)
                (= :optional (-> f :type-ref :kind))))
            (:fields tr)))))

(defn- field->kernel
  "Convert a Plan-2a field-item to a kernel Field value record. Returns nil
   for non-:typed field-items (annotations, invariants, etc. — handled by
   later tasks)."
  [field-item module-coord name-registry use-aliases]
  (when (= :typed (:field-kind field-item))
    (let [tr       (:type-ref field-item)
          optional? (= :optional (:kind tr))
          inner-type (translate-type-ref tr module-coord name-registry use-aliases)]
      (p/make-field (:name field-item) inner-type optional?))))

;; ---------------------------------------------------------------------------
;; Invariant body → kernel Expression
;; ---------------------------------------------------------------------------

(defn- invariant-body->expression
  "Convert a Plan-2a invariant body shape to a kernel Expression.
   For :for-quantification, wraps the assertion text in make-forall.
   Guard is ignored for Plan 2b MVP — Plan 4 will restore it."
  [body]
  (case (:kind body)
    :expression
    (ae/parse (:text body))

    :for-quantification
    ;; Simplification: parse the assertion text only; guard ignored for now.
    ;; Plan 4 expression engine will reconstruct the full forall with guard.
    (let [assertion-expr (ae/parse (:assertion body))]
      (e/make-forall (:var body)
                     (e/make-var (:source body))
                     assertion-expr))

    ;; fallback: treat entire body as opaque text
    (ae/parse (str body))))

(defn- get-or-make-intent
  "Return the existing intent on container, or create a fresh one with empty
   clauses and assertions."
  [container]
  (or (:intent container)
      (p/make-intent {:id         (str (:id container) "::intent")
                      :clauses    []
                      :assertions []})))

(defn- add-expression-to-intent
  "Add a Bool Expression to a Container's :intent.assertions.
   Returns the updated container."
  [container expr]
  (let [intent  (get-or-make-intent container)
        intent' (update intent :assertions (fnil conj []) expr)]
    (assoc container :intent intent')))

(defn- add-clause-to-container
  "Add a Clause to a container at `intent-path` (e.g. [:intent :clauses] or
   [:boundary :intent :clauses]). Initialises missing intent along the path.
   Returns the updated container."
  [container clause intent-path]
  (let [;; Ensure the intent exists at the path
        container' (if (= [:boundary :intent :clauses] intent-path)
                     ;; Surface-style: need to initialise boundary.intent if absent
                     (let [boundary (or (:boundary container)
                                        (p/make-boundary {:id         (str (:id container) "::boundary")
                                                          :label      (:label container)
                                                          :operations []}))
                           b-intent  (or (:intent boundary)
                                         (p/make-intent {:id         (str (:id container) "::boundary::intent")
                                                          :clauses    []
                                                          :assertions []}))
                           b-intent' (update b-intent :clauses (fnil conj []) clause)
                           boundary' (assoc boundary :intent b-intent')]
                       (assoc container :boundary boundary'))
                     ;; Container-style: add to container's own intent.clauses
                     (let [intent  (get-or-make-intent container)
                           intent' (update intent :clauses (fnil conj []) clause)]
                       (assoc container :intent intent')))]
    container'))

;; ---------------------------------------------------------------------------
;; Name registry
;; ---------------------------------------------------------------------------

(defn- collect-name-registry
  "First pass: build a map of local-name → declaration-type
   ({:entity :value :variant :external-entity}) for all type-declaring
   declarations in this AST. Used to resolve same-module simple-name type
   references; external-entity stubs participate so that field refs to a
   stub resolve as Composite-named instead of degrading to Scalar."
  [ast]
  (into {}
        (keep (fn [d]
                (when (#{:entity :value :variant :external-entity} (:type d))
                  [(:name d) (:type d)]))
              (:declarations ast))))

(defn- collect-facing-roles
  "First pass: build a set of facing-role names declared on any surface in
   this AST. Surface `facing <role>: <Type>` clauses introduce a role that
   `rule when:`/`emits` sites use to address the surface's provided events:
   `viewer.SelectNode` means \"the SelectNode event on the surface I face\".
   Since the surface lives in this module, the event lives in this module
   too — `<module-coord>::events::SelectNode`.

   Returns a set of role-name strings. (A single role-name maps unambiguously
   within a module because facing roles are typically named for the actor
   they address; collisions would be ambiguous spec authoring and currently
   surface a warning when a trigger uses them.)"
  [ast]
  (into #{}
        (keep (fn [d]
                (when (= :surface (:type d))
                  (some (fn [field]
                          (when (= :facing (:field-kind field))
                            (:role field)))
                        (:fields d))))
              (:declarations ast))))

;; ---------------------------------------------------------------------------
;; Declaration handlers
;; ---------------------------------------------------------------------------

(defn- analyze-top-level-invariant
  "Handle a top-level :invariant declaration. Adds a Bool Expression to the
   module-Container's intent.assertions and applies an Allium::Invariant tag."
  [model decl module-coord]
  (let [expr         (invariant-body->expression (:body decl))
        labeled-expr (assoc expr :label (:name decl))
        module-c     (build/get-primitive model module-coord)
        updated-c    (add-expression-to-intent module-c labeled-expr)
        idx          (count (-> module-c :intent :assertions (or [])))]
    (-> model
        (assoc-in [:primitives module-coord] updated-c)
        (build/add-tag-application
          (v/make-tag-application
            {:tag    {:namespace "Allium" :name "Invariant"}
             :target {:case      :target/substrate
                      :container module-coord
                      :path      [{:slot "intent"}
                                  {:slot "assertions" :key (str idx)}]}})))))

(defn- analyze-entity-like
  "Handle entity/value/variant declarations. `decl-type` is :entity/:value/:variant."
  [model decl module-coord decl-type name-registry use-aliases]
  (let [container-id (qualify module-coord (:name decl))
        all-fields   (vec (keep #(field->kernel % module-coord name-registry use-aliases)
                                (:fields decl)))
        ;; Collect entity-level invariant field-items
        entity-invariants (filter #(= :invariant (:field-kind %)) (:fields decl))
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

    (let [model-base (-> model
                         (build/add-primitive container)
                         (build/add-tag-application
                           (v/make-tag-application
                             {:tag    {:namespace "Allium" :name tag-name}
                              :target {:case :target/primitive :id container-id}})))
          ;; Process entity-level invariants: add Bool Expressions to the
          ;; entity Container's intent.assertions + Allium::Invariant tag
          model' (reduce (fn [m inv-field]
                           (let [expr         (invariant-body->expression (:body inv-field))
                                 labeled-expr (assoc expr :label (:name inv-field))
                                 current-c    (build/get-primitive m container-id)
                                 idx          (count (-> current-c :intent :assertions (or [])))
                                 updated-c    (add-expression-to-intent current-c labeled-expr)]
                             (-> m
                                 (assoc-in [:primitives container-id] updated-c)
                                 (build/add-tag-application
                                   (v/make-tag-application
                                     {:tag    {:namespace "Allium" :name "Invariant"}
                                      :target {:case      :target/substrate
                                               :container container-id
                                               :path      [{:slot "intent"}
                                                           {:slot "assertions" :key (str idx)}]}})))))
                         model-base
                         entity-invariants)]
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
  [model decl module-coord _name-registry _use-aliases]
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
  [model decl module-coord _name-registry _use-aliases]
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
   Qualified names (`{:kind :qualified :ns A :name X}`) resolve via the
   host file's use-alias map; an unknown alias leaves an `<ns>/<name>`
   placeholder that survives into the projection."
  [contract-ref module-coord use-aliases]
  (if (= :qualified (:kind contract-ref))
    (if-let [imported-coord (get use-aliases (:ns contract-ref))]
      (qualify imported-coord (:name contract-ref))
      (str (:ns contract-ref) "/" (:name contract-ref)))
    (qualify module-coord (:name contract-ref))))

(defn- analyze-surface
  [model decl module-coord _name-registry use-aliases]
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
                           ev-id      (event-id module-coord event-name)
                           ;; Only add the event stub if it doesn't already exist
                           m' (if (build/get-primitive m ev-id)
                                m
                                (build/add-primitive m (p/make-event {:id    ev-id
                                                                       :label event-name
                                                                       :parameters []})))
                           edge    (r/make-edge :relation/provides
                                                (r/primitive-ref container-id)
                                                (r/primitive-ref ev-id))
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

        ;; exposes: — emit exposes Container -> Field(substrate) edges + Allium::Exposes tag.
        ;; Path may be `Container.field` (same-module) or `alias.Container.field`
        ;; (cross-module via `use` alias). When the first segment is a known
        ;; alias for the host file, retarget to <imported-coord>::Container.
        ;; If the target Container is not yet in the Model, create a minimal
        ;; stub (zero fields) so the edge can land; the field-key segment
        ;; would still fail endpoint resolution under add-edge, so this
        ;; remains best-effort with the existing try/catch — Plan 4 will
        ;; tighten exposes endpoint resolution. The retargeting at least
        ;; preserves the container coordinate for the projection.
        m2 (reduce (fn [m exposed-path]
                     (let [segs (str/split exposed-path #"\.")
                           cross-module?
                           (and (>= (count segs) 3)
                                (contains? use-aliases (first segs)))
                           [target-coord container-name field-name]
                           (if cross-module?
                             [(get use-aliases (first segs)) (second segs) (last segs)]
                             [module-coord (first segs) (last segs)])
                           target-container-id (qualify target-coord container-name)
                           ;; For cross-module references, create a minimal
                           ;; stub Container at the target coordinate so the
                           ;; edge survives add-edge's endpoint validation.
                           ;; Stub-unification (§3.6) merges the stub with the
                           ;; real Container if it is defined elsewhere.
                           m' (if (or (not cross-module?)
                                      (build/get-primitive m target-container-id))
                                m
                                (try
                                  (build/add-primitive
                                    m
                                    (p/make-container
                                      {:id     target-container-id
                                       :label  container-name
                                       :fields [(p/make-field field-name
                                                              (t/make-scalar "AlliumText")
                                                              false)]}))
                                  (catch clojure.lang.ExceptionInfo e
                                    (if (= "Duplicate primitive id" (.getMessage e))
                                      m
                                      (throw e)))))
                           edge    (r/make-edge :relation/exposes
                                                (r/primitive-ref container-id)
                                                (r/substrate-address target-container-id
                                                                      [{:slot "field" :key field-name}]))
                           edge-id (r/edge-identity edge)]
                       ;; Best-effort: skip silently if endpoint resolution
                       ;; still fails (e.g. same-module reference to a
                       ;; non-existent field).
                       (try (-> m'
                                (build/add-edge edge)
                                (build/add-tag-application
                                  (v/make-tag-application
                                    {:tag    {:namespace "Allium" :name "Exposes"}
                                     :target {:case :target/edge :edge-identity edge-id}})))
                            (catch Exception _ m'))))
                   m1
                   (->> fields
                        (filter #(= :exposes (:field-kind %)))
                        (mapcat :entries)))

        ;; contracts: fulfils / demands + Allium::Fulfils / Allium::Demands tags.
        ;; Cross-module contract refs (`alias/Contract`) resolved via the
        ;; host's use-alias map; a minimal stub Container is pre-created at
        ;; the imported coordinate when the target is not yet known, so the
        ;; edge can land. Stub-unification reconciles after all files load.
        m3 (reduce (fn [m entry]
                     (let [verb        (:verb entry)
                           contract-id (resolve-contract-ref-id (:contract entry) module-coord use-aliases)
                           m           (if (and (= :qualified (-> entry :contract :kind))
                                                (contains? use-aliases (-> entry :contract :ns))
                                                (not (build/get-primitive m contract-id)))
                                         (try
                                           (build/add-primitive
                                             m
                                             (p/make-container
                                               {:id    contract-id
                                                :label (-> entry :contract :name)}))
                                           (catch clojure.lang.ExceptionInfo e
                                             (if (= "Duplicate primitive id" (.getMessage e))
                                               m
                                               (throw e))))
                                         m)
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
                        (mapcat :entries)))

        ;; annotations: @guarantee → Clause in boundary.intent.clauses (Allium::SurfaceGuarantee)
        ;;              @guidance  → Clause in boundary.intent.clauses (Allium::Guidance)
        annotation-items (->> fields (filter #(= :annotation (:field-kind %))))
        m4 (reduce (fn [m ann]
                     (let [ann-kind    (:kind ann)   ;; "guarantee", "guidance", etc.
                           ann-name    (:name ann)   ;; optional
                           ann-body    (or (:body ann) "")
                           tag-name    (case ann-kind
                                         "guarantee" "SurfaceGuarantee"
                                         "guidance"  "Guidance"
                                         ;; fallback for unrecognised annotation kinds on surface
                                         (str "Surface" (str/capitalize ann-kind)))
                           clause-id   (str container-id "::annotations::" (or ann-name ann-kind))
                           clause      (p/make-clause (cond-> {:id clause-id :body ann-body}
                                                        ann-name (assoc :label ann-name)))
                           current-c   (build/get-primitive m container-id)
                           updated-c   (add-clause-to-container current-c clause [:boundary :intent :clauses])]
                       (-> m
                           (assoc-in [:primitives container-id] updated-c)
                           (build/add-tag-application
                             (v/make-tag-application
                               {:tag    {:namespace "Allium" :name tag-name}
                                :target {:case :target/primitive :id container-id}})))))
                   m3
                   annotation-items)]
    m4))

;; ---------------------------------------------------------------------------
;; Rule analysis
;; ---------------------------------------------------------------------------

(defn- effect-edge-kind
  "Map Effect kind to the corresponding kernel relation kind."
  [effect-kind]
  (case effect-kind
    :effect/write    :relation/writes
    :effect/create   :relation/creates
    :effect/destroy  :relation/destroys
    :effect/emit     :relation/emits))

(defn- analyze-rule-trigger
  "Process one :when clause and emit the appropriate kernel edge + Trigger tag.
   Returns updated model. Best-effort endpoint resolution via try/catch.

   AST trigger shapes (from parser):
     :call          — {:kind :call, :name \"EventName\", :params [...]}
     :binding       — {:kind :binding, :var ..., :source ..., :operator op, :operand ...}
                       operator=\"created\" => creation
                       operator=\"transitions_to\" => state_transition
                       operator=\"becomes\" => becomes
                       comparison ops against \"now\" operand => temporal
     :binding-derived — {:kind :binding-derived, :var ..., :source \"Entity.field\"}

   Dotted call names (`X.EventName`) are resolved in order:
   1. If `X` is a known use-alias → event-id is `<imported-coord>::events::EventName`.
   2. If `X` is a facing-role name declared on a surface in this module →
      the trigger is for the event the surface `provides:` — event-id is
      `<this-module>::events::EventName`.
   3. Otherwise → fall through to the legacy treat-as-local path, which
      embeds the dot in the event-id; a warning is emitted to stderr so
      this latent garbage doesn't accumulate silently."
  [model when-clause module-coord rule-id use-aliases facing-roles]
  (let [trigger (:trigger when-clause)]
    (case (:kind trigger)
      :call
      ;; Event-shaped: triggers: Event -> Rule
      ;; Event-id uses the slot-aware form <module>::events::<EventName>
      ;; (per K16a).
      (let [raw-name   (:name trigger)
            dotted?    (.contains ^String raw-name ".")
            [alias-part name-part] (if dotted?
                                     (str/split raw-name #"\." 2)
                                     [nil raw-name])
            aliased?     (and alias-part (contains? use-aliases alias-part))
            facing?      (and (not aliased?)
                              alias-part
                              (contains? facing-roles alias-part))
            target-coord (cond
                           aliased? (get use-aliases alias-part)
                           facing?  module-coord
                           :else    module-coord)
            event-name   (cond
                           aliased? name-part
                           facing?  name-part
                           :else    raw-name)
            ;; Warn on dotted trigger names whose leading segment is neither
            ;; a known alias nor a facing-role — legacy treat-as-local path.
            _ (when (and dotted? (not aliased?) (not facing?))
                (binding [*out* *err*]
                  (println (str "[allium-analyzer] dotted trigger name has no matching"
                                " use-alias or facing-role; treating as local: "
                                (pr-str {:trigger raw-name
                                         :rule    rule-id})))))
            ev-id      (event-id target-coord event-name)
            ;; Create a stub Event primitive if one doesn't exist yet
            m'         (if (build/get-primitive model ev-id)
                         model
                         (build/add-primitive model (p/make-event {:id         ev-id
                                                                    :label      event-name
                                                                    :parameters []})))
            edge       (r/make-edge :relation/triggers
                                    (r/primitive-ref ev-id)
                                    (r/primitive-ref rule-id))
            edge-id    (r/edge-identity edge)]
        (try (-> m'
                 (build/add-edge edge)
                 (build/add-tag-application
                   (v/make-tag-application
                     {:tag     {:namespace "Allium" :name "Trigger"}
                      :target  {:case :target/edge :edge-identity edge-id}
                      :payload {:kind "external_stimulus"}})))
             (catch Exception _ model)))

      :binding
      ;; operator "created" => observes: Rule -> Container (creation)
      ;; other operators    => observes: Rule -> Field (various sub-kinds)
      (let [operator (:operator trigger)
            source   (:source trigger)]
        (if (= operator "created")
          ;; T.created — source is the entity name (no dot notation)
          (let [entity-id (qualify module-coord source)
                m'        (if (build/get-primitive model entity-id)
                            model
                            (build/add-primitive model (p/make-container {:id     entity-id
                                                                           :label  source
                                                                           :fields []})))
                edge      (r/make-edge :relation/observes
                                       (r/primitive-ref rule-id)
                                       (r/primitive-ref entity-id))
                edge-id   (r/edge-identity edge)]
            (try (-> m'
                     (build/add-edge edge)
                     (build/add-tag-application
                       (v/make-tag-application
                         {:tag     {:namespace "Allium" :name "Trigger"}
                          :target  {:case :target/edge :edge-identity edge-id}
                          :payload {:kind "creation"}})))
                 (catch Exception _ model)))
          ;; operator-based: source is "Entity.field", determine kind from operator
          (let [[entity-name field-name] (str/split source #"\." 2)
                entity-id (qualify module-coord entity-name)
                edge      (r/make-edge :relation/observes
                                       (r/primitive-ref rule-id)
                                       (r/substrate-address entity-id
                                                            [{:slot "field"
                                                              :key  (or field-name "")}]))
                edge-id   (r/edge-identity edge)
                kind      (case operator
                            "transitions_to" "state_transition"
                            "becomes"        "becomes"
                            ;; comparison ops: temporal if operand is "now", else derived
                            ("<=" "<" ">=" ">" "=" "!=")
                            (if (= "now" (some-> (:operand trigger) str/trim))
                              "temporal"
                              "derived")
                            ;; fallback
                            "derived")]
            (try (-> model
                     (build/add-edge edge)
                     (build/add-tag-application
                       (v/make-tag-application
                         {:tag     {:namespace "Allium" :name "Trigger"}
                          :target  {:case :target/edge :edge-identity edge-id}
                          :payload {:kind kind}})))
                 (catch Exception _ model)))))

      :binding-derived
      ;; Derived-condition: observes: Rule -> Container (entity)
      ;; Derived fields are computed/virtual and may not appear in the kernel
      ;; field list, so we target the entity container (primitive-ref) and
      ;; create a stub container if it doesn't exist yet.
      (let [source       (:source trigger)
            [entity-name _field-name] (str/split source #"\." 2)
            entity-id    (qualify module-coord entity-name)
            m'           (if (build/get-primitive model entity-id)
                           model
                           (build/add-primitive model (p/make-container {:id     entity-id
                                                                          :label  entity-name
                                                                          :fields []})))
            edge         (r/make-edge :relation/observes
                                      (r/primitive-ref rule-id)
                                      (r/primitive-ref entity-id))
            edge-id      (r/edge-identity edge)]
        (try (-> m'
                 (build/add-edge edge)
                 (build/add-tag-application
                   (v/make-tag-application
                     {:tag     {:namespace "Allium" :name "Trigger"}
                      :target  {:case :target/edge :edge-identity edge-id}
                      :payload {:kind "derived"}})))
             (catch Exception _ model)))

      ;; Unknown trigger kind: skip
      model)))

(defn- analyze-rule
  [model decl module-coord _name-registry use-aliases facing-roles]
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

        ;; Canonicalise each ensures Expression into an Effect (if pattern matches)
        effects (->> ensures-exprs
                     (map-indexed (fn [i expr]
                                    (ec/canonicalise expr (str rule-id "::ensures::" i))))
                     (filterv some?))

        intent (p/make-intent
                 {:id (str rule-id "::intent")
                  :clauses []
                  :assertions all-assertions})
        body   (p/make-rule-body definitions effects)
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
                   (range (count definitions)))

        ;; Emit writes/creates/destroys/emits kernel edges from effects (best-effort).
        ;; For :effect/emit, the canonicaliser produces a primitive-ref by bare
        ;; event-name; encode it as the slot-aware Event id
        ;; <module>::events::<EventName> (per K16a) and ensure a stub Event
        ;; primitive exists (the synthesis post-pass will canonicalise its
        ;; parameters at end of file).
        m4 (reduce (fn [m effect]
                     (let [edge-kind (effect-edge-kind (:kind effect))
                           target    (:target effect)
                           ;; Resolve emit targets. The canonicaliser stores
                           ;; either a bare local name ("OrderShipped") or a
                           ;; dotted name ("alias.X" or "facingRole.X"). Order
                           ;; of precedence:
                           ;;   1. dotted + leading segment is a use-alias
                           ;;      → event in imported coord
                           ;;   2. dotted + leading segment is a facing-role
                           ;;      → event in this module, name = right side
                           ;;   3. anything else → event in this module, name = raw
                           emit-spec (when (and (= :effect/emit (:kind effect))
                                                (= :endpoint/primitive (:case target)))
                                       (let [raw    (:id target)
                                             dot?   (.contains ^String raw ".")
                                             [a n]  (if dot? (str/split raw #"\." 2) [nil raw])
                                             use?   (and a (contains? use-aliases a))
                                             facing? (and (not use?) a (contains? facing-roles a))]
                                         {:coord       (if use? (get use-aliases a) module-coord)
                                          :event-local (cond
                                                         use?    n
                                                         facing? n
                                                         :else   raw)}))
                           target'   (if emit-spec
                                       (r/primitive-ref (event-id (:coord emit-spec)
                                                                  (:event-local emit-spec)))
                                       target)
                           m'        (if (and (= :effect/emit (:kind effect))
                                              (= :endpoint/primitive (:case target'))
                                              (not (build/get-primitive m (:id target'))))
                                       (build/add-primitive m (p/make-event {:id         (:id target')
                                                                              :label      (or (:event-local emit-spec)
                                                                                              (:id target))
                                                                              :parameters []}))
                                       m)
                           edge (r/make-edge edge-kind
                                             (r/primitive-ref rule-id)
                                             target'
                                             (cond-> {}
                                               (:value effect) (assoc :condition (:value effect))))]
                       (try (build/add-edge m' edge)
                            (catch Exception _ m'))))
                   m3
                   effects)
        ;; Emit triggers/observes kernel edges from when: clauses
        m5 (reduce (fn [m when-clause]
                     (analyze-rule-trigger m when-clause module-coord rule-id use-aliases facing-roles))
                   m4
                   (filter #(= :when (:clause-type %)) clauses))]
    m5))

;; ---------------------------------------------------------------------------
;; Contract analysis helpers
;; ---------------------------------------------------------------------------

(defn- analyze-operation
  "Convert a provides-entry (from a contract's provides-block entries) into an
   Operation primitive plus an Allium::Call tag application.
   Returns [updated-model operation-id]."
  [model op-entry module-coord contract-name name-registry use-aliases]
  (let [op-name (:name op-entry)
        op-id (qualify module-coord (str contract-name "." op-name))
        params (mapv (fn [i param]
                       (p/make-parameter
                         (:name param)
                         (translate-type-ref (or (:type-ref param) (:type param)) module-coord name-registry use-aliases)
                         false
                         i))
                     (range)
                     (or (:params op-entry) []))
        return-type (when-let [tr (:return op-entry)]
                      (translate-type-ref tr module-coord name-registry use-aliases))
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
  [model decl module-coord name-registry use-aliases]
  (let [contract-id (qualify module-coord (:name decl))
        ;; Contract operations come through provides-block entries
        op-entries (->> (:fields decl)
                        (filter #(= :provides-block (:field-kind %)))
                        (mapcat :entries))
        [model-with-ops op-ids]
        (reduce (fn [[m ids] op-entry]
                  (let [[m' op-id] (analyze-operation m op-entry module-coord (:name decl) name-registry use-aliases)]
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
                      (:description decl) (assoc :description (:description decl))))

        ;; Collect annotation field-items from the contract body
        annotation-items (->> (:fields decl) (filter #(= :annotation (:field-kind %))))

        ;; Build model with the contract container + Allium::Contract tag
        m0 (-> model-with-ops
               (build/add-primitive container)
               (build/add-tag-application
                 (v/make-tag-application
                   {:tag {:namespace "Allium" :name "Contract"}
                    :target {:case :target/primitive :id contract-id}})))

        ;; Process annotations: @invariant → Clause in intent.clauses (Allium::ContractInvariant)
        ;;                      @guidance  → Clause in intent.clauses (Allium::Guidance)
        m1 (reduce (fn [m ann]
                     (let [ann-kind  (:kind ann)   ;; "invariant", "guidance", etc.
                           ann-name  (:name ann)   ;; optional
                           ann-body  (or (:body ann) "")
                           tag-name  (case ann-kind
                                       "invariant" "ContractInvariant"
                                       "guidance"  "Guidance"
                                       ;; fallback
                                       (str "Contract" (str/capitalize ann-kind)))
                           clause-id (str contract-id "::annotations::" (or ann-name ann-kind))
                           clause    (p/make-clause (cond-> {:id clause-id :body ann-body}
                                                      ann-name (assoc :label ann-name)))
                           current-c (build/get-primitive m contract-id)
                           updated-c (add-clause-to-container current-c clause [:intent :clauses])]
                       (-> m
                           (assoc-in [:primitives contract-id] updated-c)
                           (build/add-tag-application
                             (v/make-tag-application
                               {:tag    {:namespace "Allium" :name tag-name}
                                :target {:case :target/primitive :id contract-id}})))))
                   m0
                   annotation-items)]
    m1))

;; ---------------------------------------------------------------------------
;; Event synthesis (Task 13)
;; ---------------------------------------------------------------------------

(defn- event-param-shape
  "Reduce a list of parameter maps to an arity-only summary used for cross-site
   agreement: a vector of param names (positions). Typed sites add type info
   under :types — only meaningful when all sites agree. Untyped sites omit it."
  [params typed?]
  (let [names (mapv :name params)]
    (cond-> {:arity (count params), :names names}
      typed? (assoc :types
                    (mapv (fn [p] (or (:type-ref p) (:type p))) params)))))

(defn- provides-param-shape
  "Convert a provides-block entry's params (which use :type key) to the shape
   used for cross-site comparison. provides params are always typed at the
   grammar level."
  [entry]
  (event-param-shape (or (:params entry) []) true))

(defn- trigger-param-shape
  "Convert a when-call trigger's params (which use :type-ref key, optional)
   to the shape used for cross-site comparison. Trigger params may or may not
   carry types; treat the whole site as typed only if every param has a type."
  [trigger]
  (let [params (or (:params trigger) [])
        all-typed? (and (seq params) (every? :type-ref params))]
    (event-param-shape params all-typed?)))

(defn- emit-param-shape
  "Synthesize a shape for an emits site. The canonicaliser captures emitted
   args as an Expression (tuple Apply for multi-arg, single expr for 1-arg, or
   nil for zero-arg). We only know arity at this stage, with anonymous names.
   Returns nil if we cannot determine arity (e.g. opaque value)."
  [emit-value]
  (cond
    (nil? emit-value)
    {:arity 0 :names []}

    (and (map? emit-value)
         (= :expr/apply (:case emit-value))
         (= "tuple" (:op emit-value)))
    (let [args (:args emit-value)]
      {:arity (count args)
       :names (mapv (fn [i] (str "arg" i)) (range (count args)))})

    :else
    ;; Single expression argument
    {:arity 1 :names ["arg0"]}))

(defn- collect-event-sites
  "Walk the AST and the model's effects list to collect declaration sites for
   each event-name. Returns a map of event-name → vector of {:kind, :shape, :params, :raw}
   where :kind ∈ #{:provides :when :emits}.

   Cross-module event references (alias.EventName in when:/emits) are
   filtered out — they belong to the imported module's synthesis pass.

   Facing-role-prefixed names (`viewer.SelectNode` where `viewer` is a
   facing role declared on a surface in this module) refer to events the
   surface `provides:` — the leading segment is stripped so the trigger
   site merges with the matching provides site."
  [ast model module-coord use-aliases facing-roles]
  (let [sites (atom {})
        add!  (fn [event-name site]
                (swap! sites update event-name (fnil conj []) site))
        split-dotted (fn [name]
                       (let [dotted? (.contains ^String name ".")]
                         (when dotted? (str/split name #"\." 2))))
        cross-module? (fn [dotted-name]
                        (when-let [[a _] (split-dotted dotted-name)]
                          (contains? use-aliases a)))
        local-event-name (fn [raw]
                           ;; If raw is `<facing-role>.<EventName>` and the
                           ;; leading segment is a facing-role on a surface
                           ;; in this module, strip it; otherwise return raw.
                           (if-let [[a n] (split-dotted raw)]
                             (if (contains? facing-roles a) n raw)
                             raw))]
    ;; provides: blocks on Surfaces and Contracts. Per K16, Event identity
    ;; comes from `provides:` on Surfaces (Surface entries are Events).
    ;; Contract provides entries are Operations — handled separately. We only
    ;; harvest provides entries from Surface declarations here.
    (doseq [decl (:declarations ast)
            :when (= :surface (:type decl))
            field (:fields decl)
            :when (= :provides-block (:field-kind field))
            entry (:entries field)]
      (add! (:name entry)
            {:kind :provides
             :params (or (:params entry) [])
             :shape (provides-param-shape entry)}))

    ;; when:call triggers and emits effects from Rules
    (doseq [decl (:declarations ast)
            :when (= :rule (:type decl))]
      (let [rule-id (qualify module-coord (:name decl))
            rule    (build/get-primitive model rule-id)]
        ;; when:call sites — from AST. Skip cross-module triggers; they
        ;; synthesise in the imported module's pass. Strip facing-role
        ;; prefixes so triggers merge with the surface's provides site.
        (doseq [clause (:clauses decl)
                :when (= :when (:clause-type clause))
                :let [trigger (:trigger clause)]
                :when (and (= :call (:kind trigger))
                           (not (cross-module? (:name trigger))))]
          (add! (local-event-name (:name trigger))
                {:kind :when
                 :params (or (:params trigger) [])
                 :shape (trigger-param-shape trigger)}))
        ;; emit effects — from canonicalised effects on the synthesized Rule.
        ;; The canonicaliser stores the bare local name (e.g. "OrderShipped")
        ;; or a dotted name ("alias.X") for cross-module emits. Cross-module
        ;; emits are skipped here — they belong to the imported module's
        ;; synthesis pass.
        (doseq [effect (-> rule :body :effects)
                :when (= :effect/emit (:kind effect))
                :let [target (:target effect)]
                :when (= :endpoint/primitive (:case target))
                :let [raw (:id target)]
                :when (not (cross-module? raw))]
          (add! (local-event-name raw)
                {:kind :emits
                 :params []
                 :shape (or (emit-param-shape (:value effect))
                            {:arity 0 :names []})}))))
    @sites))

(defn- pick-canonical-shape
  "Among the declaration sites for one event-name, pick canonical parameters.
   Returns a vector of Parameter records (kernel substrate values).

   Preference order:
     1. First typed `provides:` site (carries full type info).
     2. First typed `when:` site.
     3. Any site — names from the site, types are Scalar('AlliumText')
        placeholders (mirroring the expression-parser fallback).
   Untyped sites only constrain arity."
  [event-sites module-coord name-registry use-aliases]
  (let [provides-site (first (filter #(= :provides (:kind %)) event-sites))
        typed-when    (first (filter #(and (= :when (:kind %))
                                           (every? :type-ref (:params %)))
                                     event-sites))
        chosen        (or provides-site typed-when (first event-sites))
        params        (:params chosen)]
    (mapv (fn [i p]
            (let [tr (or (:type-ref p) (:type p))
                  type-val (if tr
                             (translate-type-ref tr module-coord name-registry use-aliases)
                             (t/make-scalar "AlliumText"))]
              (p/make-parameter (or (:name p) (str "arg" i))
                                type-val
                                false
                                i)))
          (range)
          (or params []))))

(defn- verify-event-shape-agreement
  "Throw ex-info {:type :event-shape-mismatch ...} if any two sites for the
   same event-name disagree on arity, or two typed sites disagree on type
   sequences."
  [event-name event-sites]
  (let [arities (set (map (comp :arity :shape) event-sites))]
    (when (> (count arities) 1)
      (throw (ex-info (str "Event shape mismatch: event '" event-name
                           "' declared with inconsistent arity across sites")
                      {:type        :event-shape-mismatch
                       :event       event-name
                       :arities     arities
                       :sites       (mapv (fn [s]
                                            {:kind  (:kind s)
                                             :shape (:shape s)})
                                          event-sites)}))))
  ;; Cross-site type agreement: any two typed sites must agree on the type
  ;; sequence. Compare on :types vectors (only typed sites have :types).
  (let [typed-shapes (->> event-sites
                          (map :shape)
                          (filter :types))
        type-seqs    (set (map :types typed-shapes))]
    (when (> (count type-seqs) 1)
      (throw (ex-info (str "Event shape mismatch: event '" event-name
                           "' declared with inconsistent parameter types across sites")
                      {:type     :event-shape-mismatch
                       :event    event-name
                       :sites    (mapv (fn [s]
                                         {:kind  (:kind s)
                                          :shape (:shape s)})
                                       event-sites)})))))

(defn- synthesize-events
  "Task 13 post-pass. After per-decl analysis on a single file, walk
   declaration sites for events (provides, when:call, emits) and:
     - validate parameter-shape agreement across sites
     - synthesize/overwrite the Event primitive with canonical params
     - apply Allium::Event tag with :declaration-sites payload
     - add event-id to module-Container's :events set
   Cross-file Event references via aliases land in Task 14.

   `facing-roles` is the set of facing-role names declared on surfaces in
   this module; used to strip facing-role prefixes from trigger/emit
   names so they merge with the surface's provides site."
  [model ast module-coord name-registry use-aliases facing-roles]
  (let [site-map (collect-event-sites ast model module-coord use-aliases facing-roles)]
    (reduce (fn [m [event-name event-sites]]
              ;; Validate shape agreement first — throws on mismatch
              (verify-event-shape-agreement event-name event-sites)
              (let [ev-id        (event-id module-coord event-name)
                    params       (pick-canonical-shape event-sites module-coord name-registry use-aliases)
                    sites-set    (into (sorted-set) (map (comp name :kind)) event-sites)
                    event-prim   (p/make-event {:id         ev-id
                                                :label      event-name
                                                :parameters params})
                    ;; Defensive: under K16a the slot-aware id-string keeps
                    ;; Events in their own namespace, but if anything ever
                    ;; collides at this id with a non-Event primitive, fail
                    ;; loudly rather than silently obliterate.
                    existing     (build/get-primitive m ev-id)
                    _            (when (and existing
                                            (not= :primitive/event (:kind existing)))
                                   (throw (ex-info "event-id collision: id is occupied by a non-Event primitive"
                                                   {:type          :event-id-collision
                                                    :event-id      ev-id
                                                    :existing-kind (:kind existing)})))
                    ;; Safe overwrite of any stub Event primitive at this id.
                    m'           (assoc-in m [:primitives ev-id] event-prim)
                    ;; Add Allium::Event tag with declaration-sites payload.
                    m''          (build/add-tag-application
                                   m'
                                   (v/make-tag-application
                                     {:tag     {:namespace "Allium" :name "Event"}
                                      :target  {:case :target/primitive :id ev-id}
                                      :payload {:declaration-sites (vec sites-set)}}))
                    ;; Add the event-id to the module-Container's :events set.
                    module-c     (build/get-primitive m'' module-coord)
                    module-c'    (update module-c :events (fnil conj #{}) ev-id)]
                (assoc-in m'' [:primitives module-coord] module-c')))
            model
            site-map)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn extract-use-aliases
  "Collect the `use \"<path>\" as <alias>` declarations from an AST into a
   map of alias-string → imported-module-coord. Empty map when no use-decls."
  [ast]
  (->> (:declarations ast)
       (filter #(= :use (:type %)))
       (map (juxt :alias :path))
       (into {})))

(defn analyze-file
  "Add this file's kernel content + Allium::* tag applications to `model`.
   The coordinate becomes the module-Container's id; Allium::Module tag
   is applied. Entity/value/variant declarations in (:declarations ast) are
   walked and converted to kernel Containers with appropriate Allium tags,
   fields, and edges.

   The 4-arity overload threads a `use-aliases` map (alias → imported coord)
   for cross-module reference resolution; the pipeline computes this from
   each file's `use` declarations. The 3-arity overload (kept for backwards
   compatibility with the analyzer's own unit tests) extracts the alias map
   from the AST itself."
  ([model ast coordinate]
   (analyze-file model ast coordinate (extract-use-aliases ast)))
  ([model ast coordinate use-aliases]
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
        facing-roles  (collect-facing-roles ast)
        ;; Pre-sort declarations so entity/value/variant/contract come before
        ;; surface (ensuring same-module contract endpoints are registered first).
        declaration-order {:entity 0 :value 0 :variant 0
                           :external-entity 1 :actor 1
                           :contract 2
                           :surface 3
                           :rule 4
                           :invariant 5}
        sorted-decls (sort-by #(get declaration-order (:type %) 99)
                               (:declarations ast))
        ;; Process declarations in dependency order
        model-with-decls
        (reduce (fn [m decl]
                  (case (:type decl)
                    :entity          (analyze-entity-like m decl coordinate :entity  name-registry use-aliases)
                    :value           (analyze-entity-like m decl coordinate :value   name-registry use-aliases)
                    :variant         (analyze-entity-like m decl coordinate :variant name-registry use-aliases)
                    :external-entity (analyze-external-entity m decl coordinate name-registry use-aliases)
                    :actor           (analyze-actor m decl coordinate name-registry use-aliases)
                    :contract        (analyze-contract m decl coordinate name-registry use-aliases)
                    :surface         (analyze-surface m decl coordinate name-registry use-aliases)
                    :rule            (analyze-rule m decl coordinate name-registry use-aliases facing-roles)
                    :invariant       (analyze-top-level-invariant m decl coordinate)
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
                           (update :children (fnil into #{}) child-ids))
        model-with-children (assoc-in model-with-decls [:primitives coordinate] updated-module)]
    ;; Task 13 post-pass: synthesize Events from declaration sites
    ;; (provides:, when:call, emits ensures). Verifies parameter-shape
    ;; agreement, overwrites stub Event primitives, applies Allium::Event tag,
    ;; and adds the event ids to the module-Container's :events set.
    (synthesize-events model-with-children ast coordinate name-registry use-aliases facing-roles))))
