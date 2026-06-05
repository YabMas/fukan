(ns fukan.canvas.core.structure
  "The lean-kernel structure primitive — the heart of the kernel.

   `defstructure` fuses composition, authoring grammar, and constraint into one
   form, on the insight that *a slot is a relation with a law*: one slot declaration
   yields composition (a Relation), authoring grammar (an instantiation clause), and
   a datalog constraint at once. The structure substrate IS the model — no separate
   model-map, no privileged kinds.

   A structure instance is a Node tagged `:structure/of <Tag>`. A slot whose target
   is another structure reifies a Relation (`:rel/from` → `:rel/to`, `:rel/kind`,
   optional `:rel/label` from an authored `[label target]` clause, `:rel/order` for
   ordered slots) so every cross-reference stays queryable; a slot whose target is a
   scalar stores a `:val/<slot>` leaf with an auto-generated type-check law. `check`
   runs every structure's laws (slot-cardinality laws + free `law`s, recursive
   datalog rules supported) over a db, injecting the vocab-derived rules so laws read
   at domain altitude. The schema is minimal and classification-free."
  (:require [datascript.core :as d]
            [fukan.canvas.core.rules :as rules]))

;; ── substrate (minimal, lean — no node-kind / role / family / classification) ─

(def schema
  {:entity/id    {:db/unique :db.unique/identity}
   :entity/name  {:db/index true}
   :entity/doc   {}                                            ; instance documentation (the (doc ...) clause)
   :structure/of {:db/index true}                              ; the structure tag of an instance
   :module/child {:db/cardinality :db.cardinality/many :db/valueType :db.type/ref}
   ;; reified slot relations — the seam carrying :rel/label / :rel/order / cross-module :rel/to-ref
   :rel/id       {:db/unique :db.unique/identity}
   :rel/from     {:db/valueType :db.type/ref}
   :rel/kind     {:db/index true}
   :rel/to       {:db/valueType :db.type/ref}
   :rel/label    {}
   :rel/order    {}                                           ; position in an (ordered ...) slot
   :rel/to-ref   {}})                                         ; deferred cross-module target [module] / [module name]

(defn create [] (d/empty-db schema))

;; ── scalar value types (the leaf-value vocabulary) ───────────────────────────
;; A slot whose target is one of these is a VALUE slot (stores a leaf datum),
;; not a RELATION slot (an edge to a node). Predicate held as a symbol so it
;; splices into a datalog clause. Extend by adding a type → predicate-symbol pair.

(def scalar-types {:Int 'clojure.core/integer?, :String 'clojure.core/string?, :Bool 'clojure.core/boolean?})

(defn- scalar-slot?
  "True when a parsed slot's target is a registered scalar type."
  [slot]
  (contains? scalar-types (:target slot)))

(def ^:dynamic *store* nil)
(def ^:dynamic *enclosing-module* nil)

(def ^:dynamic *pending-relations*
  "When bound (inside `within-module`), an atom collecting each instance's
   relation clauses for a SECOND resolution pass — so forward references and
   cycles between instances resolve once every instance in the module is declared."
  nil)

(defn transact! [tx] (swap! *store* d/db-with tx))

(defn register-child! [child-id]
  (when *enclosing-module*
    (transact! [[:db/add [:entity/id *enclosing-module*] :module/child [:entity/id child-id]]])))

(declare flush-pending-relations!)

(defn with-structures*
  "Fn form of `with-structures`: bind *store* to a fresh db, run `thunk`, return the db."
  [thunk]
  (binding [*store* (atom (create))]
    (thunk)
    @*store*))

(defmacro with-structures
  "Bind *store* to a fresh structure db, run body, return the db."
  [& body]
  `(with-structures* (fn [] ~@body)))

(defn within-module*
  "Fn form of `within-module`: declare module `mname`, run `thunk` with it enclosing,
   flush queued relations on exit, return the module id."
  [mname thunk]
  (let [mid (random-uuid)]
    (transact! [{:entity/id mid :entity/name mname :structure/of :Module}])
    (binding [*enclosing-module*  mid
              *pending-relations* (atom [])]
      (thunk)
      (flush-pending-relations!)
      mid)))

(defmacro within-module
  "Declare a module and run `body` with it as the enclosing container; children
   register under it via :module/child. Two-pass (see within-module*)."
  [mname & body]
  `(within-module* ~mname (fn [] ~@body)))

(defn resolve-in-module
  "The :entity/id of the enclosing module's child named `nm`, or nil."
  [db nm]
  (when *enclosing-module*
    (ffirst
     (d/q '[:find ?id :in $ ?mid ?n
            :where [?m :entity/id ?mid] [?m :module/child ?e]
                   [?e :entity/name ?n] [?e :entity/id ?id]]
          db *enclosing-module* (str nm)))))

;; ── structure registry (vocabulary as data: slots + laws, no family/payload) ──

(defonce ^:private structures (atom {}))

(defn register-structure! [sdef] (swap! structures assoc (:tag sdef) sdef) (:tag sdef))
(defn all-structures [] (vals @structures))
(defn structure-by-tag [tag] (get @structures tag))

;; ── value-authoring: instances as values, references as vars ──────────────────

(defrecord InstanceValue [tag name doc scalars clauses value?])

(defn instance-value? [x] (instance? InstanceValue x))

(defn var-id
  "The fully-qualified-var-name id of an instance-bearing var."
  [v]
  (let [m (meta v)] (str (ns-name (:ns m)) "/" (:name m))))

;; ── instantiation (the interpreter: instance → Node + reified slot Relations) ─

(defn- slot-for [sdef rel] (first (filter #(= rel (:rel %)) (:slots sdef))))

(defn- parse-clause-arg
  "A slot clause arg is `[label target]` (a 2-element, symbol-headed vector) or a
   bare `target`. A 1-element vector / map is NOT a label form — it is a data
   literal target (e.g. a `[X]` list-shape or `{:k v}` record-shape) handed to the
   target structure's :reader by `resolve-target`."
  [arg]
  (if (and (vector? arg) (= 2 (count arg)) (symbol? (first arg)))
    {:label (str (first arg)) :target (second arg)}
    {:label nil :target arg}))

;; Universal built-in clauses are not slots — they set scalar attributes on the
;; instance node rather than emitting relations.
(def ^:private builtin-clauses #{'doc})

(declare resolve-target clause->rels unquote-lit)

(defn construct-value!
  "Construct (and dedup) a value-typed instance of `tag` from `clauses`. Identity
   is a deterministic function of composition (tag + scalar slot values + resolved
   relation targets), so structurally-equal values collapse to one node via
   datascript :entity/id uniqueness. Value nodes are anonymous (no :entity/name)
   and ownerless (no :module/child) — a value exists only as a component. Runs in
   the resolution pass; nested inline values recurse, entity targets resolve by
   name. Returns the value node's content :entity/id."
  [tag clauses]
  (let [sdef    (structure-by-tag tag)
        db      @*store*
        scalar? (fn [c] (scalar-slot? (slot-for sdef (keyword (first c)))))
        rels    (vec (mapcat #(clause->rels db sdef %)
                             (for [c clauses :when (not (scalar? c))] c)))
        scalars (into (sorted-map)
                      (mapcat (fn [c]
                                (let [slot (slot-for sdef (keyword (first c)))]
                                  (cond-> [[(keyword "val" (clojure.core/name (first c))) (second c)]]
                                    ;; payload may be a quoted code form → unquote-lit; the primary
                                    ;; scalar is a typed leaf stored raw
                                    (and (:payload slot) (> (count c) 2))
                                    (conj [(keyword "val" (clojure.core/name (:payload slot)))
                                           (unquote-lit (nth c 2))]))))
                              (for [c clauses :when (scalar? c)] c)))
        ;; :order enters the content key, so [A B] and [B A] are distinct values
        ckey    (pr-str [(clojure.core/name tag)
                         scalars
                         (sort (map (fn [{:keys [rk label order tid ref]}]
                                      [(clojure.core/name rk) (str order) (str label) (str (or tid ref))])
                                    rels))])
        node-id (str "#val" ckey)]
    (transact! [(into {:entity/id node-id :structure/of tag} scalars)])
    (doseq [{:keys [rk label order tid ref]} rels]
      (transact! [(cond-> {:rel/id   (str node-id "|" (clojure.core/name rk) "|" order "|" (or tid ref))
                           :rel/from [:entity/id node-id]
                           :rel/kind rk}
                    tid   (assoc :rel/to [:entity/id tid])
                    ref   (assoc :rel/to-ref ref)
                    label (assoc :rel/label label)
                    order (assoc :rel/order order))]))
    node-id))

(defn- across-form?
  "An `(across <module>)` / `(across <module> <name>)` clause arg — a deferred
   CROSS-MODULE reference, resolved post-merge rather than in the local module."
  [target]
  (and (seq? target) (= 'across (first target))))

(defn- clause->rels
  "Resolve one relation clause of an instance of `sdef` into relation specs
   {:rk :label :order (:tid | :ref)}. A local target resolves to a node (:tid); an
   `(across …)` target is recorded symbolically (:ref [module] / [module name]) for
   post-merge resolution. An ORDERED slot splices its vector arg(s) and
   position-indexes them (no label); any other slot parses each arg as `target` or
   `[label target]`. Throws on an unresolved local target."
  [db sdef clause]
  (let [rk   (keyword (first clause))
        slot (slot-for sdef rk)
        args (rest clause)]
    (when-not slot
      (throw (ex-info (str (clojure.core/name (:tag sdef)) ": `" (clojure.core/name rk)
                           "` is not a slot")
                      {:tag (:tag sdef) :rel rk})))
    (let [resolve* (fn [target]
                     (or (resolve-target db (:target slot) target)
                         (throw (ex-info (str (clojure.core/name (:tag sdef)) ": "
                                              (clojure.core/name rk) " references '" target
                                              "', which is not an entity in the enclosing module")
                                         {:structure (:tag sdef) :rel rk :target target}))))
          spec     (fn [target order label]
                     (if (across-form? target)
                       {:rk rk :label label :order order :ref (mapv str (rest target))}
                       {:rk rk :label label :order order :tid (resolve* target)}))]
      (if (= :ordered (:card slot))
        (map-indexed (fn [i t] (spec t i nil)) (mapcat #(if (vector? %) % [%]) args))
        (for [a args
              :let [{:keys [label target]} (parse-clause-arg a)]]
          (spec target nil label))))))

(defn- resolve-target
  "Resolve a relation clause target (for a slot whose declared target structure is
   `target-tag`) to a node id:
   - an explicit value form `(ValueTag clause...)` → construct (and dedup) it;
   - a data LITERAL (symbol / vector / map) when target-tag's structure declares a
     `:reader` → expand the literal to clauses via the reader, then construct;
   - otherwise a name (symbol) → the enclosing module's entity of that name (nil
     if unresolved)."
  [db target-tag target]
  (let [sdef (structure-by-tag target-tag)]
    (cond
      (and (seq? target) (symbol? (first target)))
      (let [vtag  (keyword (first target))
            vsdef (structure-by-tag vtag)]
        (when-not (:value? vsdef)
          (throw (ex-info (str "inline construction of " vtag " — only ^:value structures"
                               " may be authored inline")
                          {:tag vtag :form target})))
        (construct-value! vtag (rest target)))

      (and (:value? sdef) (:reader sdef))
      (construct-value! target-tag ((:reader sdef) target))

      (symbol? target)
      (resolve-in-module db target)

      :else
      (throw (ex-info (str "cannot resolve " (pr-str target) " as a " target-tag
                           " — not a name, and " target-tag " declares no data-literal reader")
                      {:target target :tag target-tag})))))

(defn- emit-relations!
  "Resolve a queued instance's slot clauses against the now-declared module
   entities (or inline value forms) and transact its reified relations — carrying
   :rel/order for ordered slots. Resolution runs after the whole module is declared
   (two-pass), so a target that still does not resolve is a genuine error (thrown by
   `clause->rels`), not skipped."
  [{:keys [tag node-id clauses]}]
  (let [sdef (structure-by-tag tag)
        db   @*store*]
    (doseq [clause clauses
            {:keys [rk label order tid ref]} (clause->rels db sdef clause)]
      (transact! [(cond-> {:rel/id   (str node-id "|" (clojure.core/name rk)
                                          "|" (or tid ref) "|" (random-uuid))
                           :rel/from [:entity/id node-id]
                           :rel/kind rk}
                    tid   (assoc :rel/to [:entity/id tid])
                    ref   (assoc :rel/to-ref ref)        ; deferred cross-module target
                    label (assoc :rel/label label)
                    order (assoc :rel/order order))]))))

(defn flush-pending-relations!
  "Pass 2 (on `within-module` exit): emit the relations queued during the body,
   now that every instance is declared — so forward references and cycles resolve."
  []
  (when *pending-relations*
    (doseq [pending @*pending-relations*]
      (emit-relations! pending))))

(defn instantiate!
  "Emit an instance of structure `tag` named `name` from `clauses` (slot-rel and
   slot-value forms + the universal (doc \"...\") clause). VALUE clauses (scalar
   target) are applied now — they need no name resolution. RELATION clauses are
   queued for `flush-pending-relations!` (or emitted immediately when not inside a
   `within-module` two-pass scope). Returns the instance :entity/id."
  [tag name clauses]
  (let [sdef        (structure-by-tag tag)
        node-id     (random-uuid)
        doc         (some (fn [c] (when (and (seq? c) (= 'doc (first c))) (second c))) clauses)
        non-builtin (remove #(contains? builtin-clauses (first %)) clauses)
        value?      (fn [c] (let [slot (slot-for sdef (keyword (first c)))]
                              (and slot (scalar-slot? slot))))
        val-clauses (filter value? non-builtin)
        rel-clauses (remove value? non-builtin)
        pending     {:tag tag :name name :node-id node-id :clauses rel-clauses}]
    (when-not sdef
      (throw (ex-info (str "unknown structure " tag) {:tag tag})))
    (transact! [(cond-> {:entity/id node-id :entity/name (str name) :structure/of tag}
                  doc (assoc :entity/doc doc))])
    (register-child! node-id)
    (doseq [c val-clauses]
      (let [slot (slot-for sdef (keyword (first c)))]
        (transact! [(cond-> {:entity/id node-id
                             (keyword "val" (clojure.core/name (first c))) (second c)}
                      ;; payload may be a quoted code form → unquote-lit; the primary
                      ;; scalar is a typed leaf stored raw
                      (and (:payload slot) (> (count c) 2))
                      (assoc (keyword "val" (clojure.core/name (:payload slot)))
                             (unquote-lit (nth c 2))))])))
    (if *pending-relations*
      (swap! *pending-relations* conj pending)
      (emit-relations! pending))
    node-id))

;; ── value-authoring: instance-form / defstructure* ──────────────────────────

(defn- ref-arg->form
  "Code for one relation-slot target: a symbol → (var sym); an inline (Tag ...) form
   → left to evaluate (it yields an InstanceValue)."
  [arg]
  (if (symbol? arg) (list 'var arg) arg))

(defn- rel-map-form
  "Emits a form for a single relation-clause map.  `:targets` is a vector of
   *code forms* (e.g. `(var User)`) so they evaluate to vars / InstanceValues
   when the surrounding `->InstanceValue` call is evaluated."
  [rk card targets]
  ;; ~@targets splices the code forms into the vector literal — each form
  ;; (e.g. (var User)) is real code, not quoted data.
  `{:rk ~rk :card ~card :targets [~@targets]})

(defn instance-form
  "Macroexpansion-time: build the (->InstanceValue ...) form for an entity instance.
   `name-form` is `(quote <name>)` so `(second name-form)` yields the raw name value."
  [tag name-form body]
  (let [sdef    (structure-by-tag tag)
        _       (when-not sdef
                  (throw (ex-info (str "defstructure*: unknown structure " tag) {:tag tag})))
        doc     (some (fn [c] (when (and (seq? c) (= 'doc (first c))) (second c))) body)
        clauses (remove #(contains? builtin-clauses (first %)) body)
        scalar? (fn [c] (let [s (slot-for sdef (keyword (first c)))] (and s (scalar-slot? s))))
        scalars (into {} (for [c clauses :when (scalar? c)]
                           [(keyword "val" (clojure.core/name (first c))) (second c)]))
        rels    (mapv (fn [c]
                        (let [rk   (keyword (first c))
                              slot (slot-for sdef rk)]
                          (when-not slot
                            (throw (ex-info (str (clojure.core/name tag) ": `"
                                                 (clojure.core/name rk) "` is not a slot")
                                            {:tag tag :rel rk})))
                          (rel-map-form rk (:card slot)
                                        (mapv ref-arg->form (rest c)))))
                      (remove scalar? clauses))]
    `(->InstanceValue ~tag ~(str (second name-form)) ~doc ~scalars ~rels false)))

(defn- value-form
  "Minimal placeholder for ^:value structures (completed in Task 4)."
  [tag body]
  `(construct-value! ~tag '~body))

;; ── defstructure (the one form) ──────────────────────────────────────────────

(defn- parse-slot
  "(slot :rel (card Target) & opts) → {:rel :card :target & opts}."
  [form]
  (let [[_ rel card-form & opts] form]
    (merge {:rel rel
            :card (keyword (first card-form))
            :target (keyword (name (second card-form)))}
           (apply hash-map opts))))

(defn- unquote-lit [v] (if (and (seq? v) (= 'quote (first v))) (second v) v))

(defn- parse-law
  "(law \"desc\" :offenders '[?vars] :where '[clauses] :rules '[rules]? :scope <tag|:global>?).

   :scope controls auto-scoping of the first offender var to a structure:
   absent → the owning structure (the common case: a law about my own
   instances); a tag → that structure (a law whose subject is a related
   structure); :global → no auto-scope (the law is fully explicit)."
  [form]
  (let [[_ desc & kvs] form
        m (apply hash-map kvs)]
    {:desc desc
     :offenders (unquote-lit (:offenders m))
     :where     (unquote-lit (:where m))
     :rules     (unquote-lit (:rules m))
     :scope     (:scope m)}))

(defn- defined-rule-names
  "The set of rule-head names defined in a datalog rule vector."
  [rules]
  (set (map (comp first first) rules)))

(defn- rule-refs
  "Defined rule-names invoked as clause heads within `clauses`, recursing into
   not / not-join / or / and wrappers. Datom vectors `[?e :a ?v]` and predicate
   clauses `[(pred …)]` are not rule calls, so they're skipped."
  [clauses defined]
  (mapcat
   (fn [c]
     (if (and (seq? c) (symbol? (first c)))
       (let [h (first c)]
         (cond
           (#{'not 'or 'and} h)  (rule-refs (rest c) defined)
           (= 'not-join h)       (rule-refs (drop 2 c) defined)
           (contains? defined h) [h]
           :else                 []))
       []))
   clauses))

(defn- check-law-recursion!
  "Reject a law whose :rules contains a self-recursive rule that ALSO calls
   another rule. datascript diverges on cyclic data for that rule-calls-rule
   shape (see the no-cycle / reachability finding) — the helper rule's clauses
   must be inlined into the recursive rule. The *law-timeout-ms* guard is the
   runtime backstop for divergent shapes this static check misses (e.g. mutual
   recursion)."
  [sname {:keys [desc rules]}]
  (when (seq rules)
    (let [defined (defined-rule-names rules)]
      (doseq [rule rules]
        (let [hname (-> rule first first)
              refs  (set (rule-refs (rest rule) defined))]
          (when (and (contains? refs hname) (seq (disj refs hname)))
            (throw (ex-info
                    (str "defstructure " sname ", law " (pr-str desc)
                         ": recursive rule (" hname ") calls another rule "
                         (vec (disj refs hname)) " — datascript diverges on cyclic data "
                         "for rule-calls-rule recursion. Inline that rule's clauses "
                         "directly into (" hname ").")
                    {:structure sname :law desc :rule hname}))))))))

(defmacro defstructure
  "Define a structure: its slots (relations-with-laws) and free laws. Registers
   the structure-definition and defines an instantiation macro named `sname`.

     (defstructure Function \"...\"
       (slot :takes (many Type))
       (slot :gives (one  Type))
       (law \"...\" :offenders '[?f] :where '[...] :rules '[...]?))

   Instantiate with the generated macro, slot names as clause heads:
     (Function \"load-model\" (takes [src String]) (gives Model))

   Body forms must be (slot ...) or (law ...); anything else is rejected at
   macro-expansion time (a silently-dropped form is a footgun).

   A law's recursive :rules must INLINE their step — a self-recursive rule may
   not call another rule, because datascript diverges on cyclic data for that
   shape (rejected here; the *law-timeout-ms* guard backstops the rest)."
  [sname docstring & body]
  (doseq [form body]
    (when-not (and (seq? form) (#{'slot 'law 'reader} (first form)))
      (throw (ex-info (str "defstructure " sname ": unknown body form " (pr-str form)
                           " — expected (slot ...), (law ...) or (reader ...)")
                      {:structure sname :form form}))))
  (let [value? (boolean (:value (meta sname)))   ; ^:value → content-deduped value structure
        tag   (keyword (name sname))
        slots (mapv parse-slot (filter #(= 'slot (first %)) body))
        _     (doseq [s slots]
                (when (and (scalar-slot? s) (#{:some :many :ordered} (:card s)))
                  (throw (ex-info
                          (str "defstructure " sname ": scalar slot " (:rel s)
                               " must be (one ...) or (optional ...), not ("
                               (name (:card s)) " ...)")
                          {:structure sname :slot (:rel s) :card (:card s)}))))
        ;; stamp the owning structure tag onto each law so check can auto-scope it
        laws  (mapv #(assoc (parse-law %) :owner tag) (filter #(= 'law (first %)) body))
        _     (doseq [law laws] (check-law-recursion! sname law))
        reader-form (some (fn [f] (when (= 'reader (first f)) (second f)))
                          (filter #(= 'reader (first %)) body))
        sdef  {:tag tag :doc docstring :slots slots :laws laws :value? value?}]
    `(do
       ;; a (reader fn) form injects a data-literal expander (fn) into the sdef
       (register-structure! ~(if reader-form `(assoc '~sdef :reader ~reader-form) `'~sdef))
       ;; A value structure is authored inline (anonymous, deduped) → its macro
       ;; takes no name and constructs-and-dedups; an entity structure is named.
       ~(if value?
          `(defmacro ~sname ~docstring [& body#]
             (list 'fukan.canvas.core.structure/construct-value! ~tag (list 'quote body#)))
          `(defmacro ~sname ~docstring [name# & body#]
             (list 'fukan.canvas.core.structure/instantiate! ~tag name# (list 'quote body#)))))))

(defmacro defstructure*
  "Like `defstructure` but generates a VALUE-RETURNING constructor (no db, no side-effects).
   The generated macro returns an `InstanceValue` record: scalar slots go into `:scalars`,
   relation slots into `:clauses` as `{:rk :card :targets [...]}` where each symbol target
   is captured as `(var sym)` (a deferred var reference, safe for forward/cyclic refs).

   `defstructure*` and `defstructure` share the same structure registry, so a structure
   defined with one can be referenced in the other."
  [sname docstring & body]
  (doseq [form body]
    (when-not (and (seq? form) (#{'slot 'law 'reader} (first form)))
      (throw (ex-info (str "defstructure* " sname ": unknown body form " (pr-str form)
                           " — expected (slot ...), (law ...) or (reader ...)")
                      {:structure sname :form form}))))
  (let [value? (boolean (:value (meta sname)))
        tag    (keyword (name sname))
        slots  (mapv parse-slot (filter #(= 'slot (first %)) body))
        _      (doseq [s slots]
                 (when (and (scalar-slot? s) (#{:some :many :ordered} (:card s)))
                   (throw (ex-info
                           (str "defstructure* " sname ": scalar slot " (:rel s)
                                " must be (one ...) or (optional ...), not ("
                                (name (:card s)) " ...)")
                           {:structure sname :slot (:rel s) :card (:card s)}))))
        laws   (mapv #(assoc (parse-law %) :owner tag) (filter #(= 'law (first %)) body))
        _      (doseq [law laws] (check-law-recursion! sname law))
        reader-form (some (fn [f] (when (= 'reader (first f)) (second f)))
                          (filter #(= 'reader (first %)) body))
        sdef   {:tag tag :doc docstring :slots slots :laws laws :value? value?}]
    `(do
       (register-structure! ~(if reader-form `(assoc '~sdef :reader ~reader-form) `'~sdef))
       ~(if value?
          `(defmacro ~sname ~docstring [& body#]
             (fukan.canvas.core.structure/value-form ~tag body#))
          `(defmacro ~sname ~docstring [name# & body#]
             (fukan.canvas.core.structure/instance-form ~tag (list 'quote name#) body#))))))

;; ── laws: slot-derived + free, run over a db ─────────────────────────────────

(defn- relation-slot-laws
  "Cardinality + target-type laws for a RELATION slot (target is a structure)."
  [tag {:keys [rel card target]}]
  (let [tn (name tag) rn (name rel)
        target-law {:desc (str tn "." rn " target must be a " (name target))
                    :offenders '[?x ?t]
                    :where [['?r :rel/from '?x] ['?r :rel/kind rel] ['?r :rel/to '?t]
                            ['?x :structure/of tag]
                            (list 'not ['?t :structure/of target])]}
        none-law (fn [verb]
                   {:desc (str tn "." rn " " verb " (found none)")
                    :offenders '[?x]
                    :where [['?x :structure/of tag]
                            (list 'not-join ['?x]
                                  ['?r :rel/from '?x] ['?r :rel/kind rel])]})
        several-law (fn [verb]
                      {:desc (str tn "." rn " " verb " (found several)")
                       :offenders '[?x]
                       :where [['?x :structure/of tag]
                               ['?r1 :rel/from '?x] ['?r1 :rel/kind rel]
                               ['?r2 :rel/from '?x] ['?r2 :rel/kind rel]
                               [(list 'not= '?r1 '?r2)]]})]
    (cond-> [target-law]
      (= card :one)      (conj (none-law "requires exactly one")
                               (several-law "requires exactly one"))
      (= card :some)     (conj (none-law "requires at least one"))
      (= card :optional) (conj (several-law "allows at most one")))))

(defn- value-slot-laws
  "Type-check (+ none for `one`) laws for a VALUE slot (target is a scalar type).
   No 'found several' law: plain :val/<key> storage is cardinality-one."
  [tag {:keys [rel card target]}]
  (let [tn (name tag) rn (name rel)
        val-attr (keyword "val" (name rel))
        pred     (scalar-types target)
        type-law {:desc (str tn "." rn " value must be a " (name target))
                  :offenders '[?x]
                  :where [['?x :structure/of tag]
                          ['?x val-attr '?v]
                          [(list pred '?v) '?ok]
                          [(list 'false? '?ok)]]}
        none-law {:desc (str tn "." rn " requires exactly one (found none)")
                  :offenders '[?x]
                  :where [['?x :structure/of tag]
                          (list 'not-join ['?x] ['?x val-attr '?_v])]}]
    (cond-> [type-law]
      (= card :one) (conj none-law))))

(defn- slot-laws
  "Derive laws for a structure's slots, dispatching value vs relation slots."
  [{:keys [tag slots]}]
  (mapcat #(if (scalar-slot? %)
             (value-slot-laws tag %)
             (relation-slot-laws tag %))
          slots))

(defn- law-scope-tag
  "The structure tag a free law's first offender var is scoped to: its :scope
   when set (nil when :scope is :global), else its owning structure. Slot-derived
   laws self-scope and carry no :owner, so they get no injection."
  [{:keys [scope owner]}]
  (case scope
    :global nil
    nil     owner
    scope))

(def ^:dynamic *law-timeout-ms*
  "Per-law wall-clock budget for `check`. A recursive law that exceeds it —
   e.g. unbounded recursion over a cyclic/indirect graph — is reported as
   timed-out instead of hanging the whole check. Best-effort: the underlying
   datascript query thread is abandoned (datascript queries aren't cleanly
   interruptible), so it runs on until it finishes or the JVM exits."
  5000)

(defn vocab-rules
  "The datascript rules derived from the live vocabulary (one per kind + per relation
   slot, plus the fixed substrate rules). Lets queries — and laws (via `check`) — read
   at domain altitude: `(Stage ?s) (in-module ?s \"…\") (calls ?s ?c)`."
  []
  (rules/derive-rules (all-structures) scalar-slot?))

(defn- run-query
  "Run a law's offender query, returning the offender rows (or nil if none).
   Only laws with their OWN recursive `:rules` can diverge, so only they are run under
   the *law-timeout-ms* guard (via a future); non-recursive laws run inline. Either
   way the query gets `rules` (the vocab-derived rules merged with the law's own).
   Returns ::timeout if a guarded query exceeds the budget."
  [db q rules recursive?]
  (if recursive?
    (let [fut     (future (d/q q db rules))
          results (deref fut *law-timeout-ms* ::timeout)]
      (if (= results ::timeout)
        (do (future-cancel fut) ::timeout)
        results))
    (d/q q db rules)))

(defn- run-law [db base-rules {:keys [offenders where rules] :as law}]
  (let [scope-tag (law-scope-tag law)
        where*    (if scope-tag
                    (vec (cons [(first offenders) :structure/of scope-tag] where))
                    where)
        q         (vec (concat [:find] offenders [:in '$ '%] [:where] where*))
        ;; vocab-derived rules are always available; a law's OWN rules signal recursion
        results   (run-query db q (into (vec base-rules) rules) (boolean (seq rules)))]
    (cond
      (= results ::timeout) ::timeout
      (seq results)         (mapv vec results)
      :else                 nil)))

(defn check
  "Run every registered structure's laws (slot-cardinality + free) over `db`, with the
   vocab-derived rules injected so law `:where`s can read at domain altitude.
   Returns a vector of result maps:
     {:structure :law :offenders [...]}        — a violation
     {:structure :law :timed-out? true :message …} — a (recursive) law that
        exceeded *law-timeout-ms* instead of completing."
  [db]
  (let [base (vocab-rules)]
   (vec
    (for [{:keys [tag laws] :as sdef} (all-structures)
          {:keys [desc] :as law} (concat (slot-laws sdef) laws)
          :let [r (run-law db base law)]
          :when r]
     (if (= r ::timeout)
       {:structure tag :law desc :timed-out? true
        :message (str "law exceeded *law-timeout-ms* (" *law-timeout-ms* "ms) "
                      "without completing — likely unbounded recursion over a "
                      "cyclic/indirect graph (recursion must run over a direct "
                      "binary relation, not a rule-derived one)")}
       {:structure tag :law desc :offenders r})))))
