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
   ;; reified slot relations — the seam carrying :rel/label / :rel/order
   :rel/id       {:db/unique :db.unique/identity}
   :rel/from     {:db/valueType :db.type/ref}
   :rel/kind     {:db/index true}
   :rel/to       {:db/valueType :db.type/ref}
   :rel/label    {}
   :rel/order    {}})                                         ; position in an (ordered ...) slot

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

(defn var-simple-name
  "The simple (unqualified) name of an instance-bearing var, as a string — the
   default `:entity/name` for an entity authored without an explicit name."
  [v]
  (name (:name (meta v))))

;; ── instantiation (the interpreter: instance → Node + reified slot Relations) ─

(defn- slot-for [sdef rel] (first (filter #(= rel (:rel %)) (:slots sdef))))

;; Universal built-in clauses are not slots — they set scalar attributes on the
;; instance node rather than emitting relations.
(def ^:private builtin-clauses #{'doc})


;; ── value-authoring: instance-form / value-form ──────────────────────────────

(defn- unquote-lit [v] (if (and (seq? v) (= 'quote (first v))) (second v) v))

(defn- ref-arg->form
  "Code for one relation-slot target: a symbol → (var sym); an inline (Tag ...) form
   → left to evaluate (it yields an InstanceValue)."
  [arg]
  (if (symbol? arg) (list 'var arg) arg))

(defn- reader-literal?
  "True when `arg` is a data literal that a reader-slot should expand (rather than
   var-capture). A reader takes a native literal — a symbol (Shape `Foo`), a keyword
   (Effect `:io`), a number (Wrapped `5`), a string, a map (Shape record), or a vector
   (Shape `[X]`). The only non-literals are an inline `(Tag …)` constructor seq and a
   2-element symbol-headed `[label target]` form (parsed upstream as a labelled target)."
  [arg]
  (and (not (seq? arg))
       (not (and (vector? arg) (= 2 (count arg)) (symbol? (first arg))))))

(declare value-form)

(defn- reader-arg->form
  "Like `ref-arg->form` but for a reader-slot: a data literal (symbol/vector/map,
   NOT an inline `(Tag …)` seq) is expanded via the reader at macroexpansion time
   and the resulting clauses are built into an inline value-form; an inline seq
   form is left as-is (normal inline construction)."
  [target-tag reader-fn arg]
  (if (and (reader-literal? arg) (not (seq? arg)))
    ;; data literal → expand via reader → build value-form at macroexpansion time
    (value-form target-tag (reader-fn arg))
    ;; inline (Tag …) form → leave to normal evaluation
    (ref-arg->form arg)))

(defn- rel-map-form
  "Emits a form for a single relation-clause map.  `:targets` is a vector of
   *code forms* (e.g. `(var User)`) so they evaluate to vars / InstanceValues
   when the surrounding `->InstanceValue` call is evaluated.
   `labels` is a vector parallel to `:targets` (nil entries for unlabelled
   targets) — a `:labels` key is added only when some target is labelled, so a
   clause like `(takes [x Int] [y Str])` carries a per-target label."
  ([rk card targets]
   `{:rk ~rk :card ~card :targets [~@targets]})
  ([rk card targets labels]
   (if (some some? labels)
     `{:rk ~rk :card ~card :targets [~@targets] :labels [~@labels]}
     `{:rk ~rk :card ~card :targets [~@targets]})))

(defn- parse-clause-arg-forms
  "Given a slot's `:card`, its raw args (from the authored clause), and optionally
   the target structure's sdef (when it has a `:reader`), return
   `[labels target-forms]` where `target-forms` is a seq of code forms to splice
   into `:targets` and `labels` is a parallel vector (nil per unlabelled target),
   or nil when there are no labels.

   - `:ordered` slot: the single arg is expected to be a vector; splice its
     elements (each via `ref-arg->form` or `reader-arg->form`), in order. No labels.
   - Other slots: parse each arg — a 2-element, symbol-headed vector `[label t]`
     contributes a labelled single target; a bare arg contributes one unlabelled
     target. (So `(takes [x Int] [y Str])` carries per-target labels.)

   When `target-sdef` has a `:reader`, data literals are expanded via the reader
   (§2.1 reader-slot exception). A `[label literal]` form for a reader-slot
   extracts the label and reader-expands the target part."
  ([card args] (parse-clause-arg-forms card args nil))
  ([card args target-sdef]
   (let [arg->form (if (and target-sdef (:reader target-sdef))
                     (partial reader-arg->form (:tag target-sdef) (:reader target-sdef))
                     ref-arg->form)]
     (if (= :ordered card)
       ;; ordered: the arg vector is spliced — mirror clause->rels behaviour
       (let [elems (mapcat #(if (vector? %) % [%]) args)]
         [nil (mapv arg->form elems)])
       ;; non-ordered: parse each arg as [label target] or bare target
       (let [parsed (mapv (fn [a]
                            (if (and (vector? a) (= 2 (count a)) (symbol? (first a)))
                              ;; [label target] form — for reader-slots, reader-expand the target
                              {:label (str (first a)) :target (arg->form (second a))}
                              {:label nil :target (arg->form a)}))
                          args)
             labels (mapv :label parsed)
             forms  (mapv :target parsed)]
         [labels forms])))))

(defn- build-instance-form
  "Shared clause-walker for `instance-form` and `value-form`. Builds the
   `->InstanceValue` call with `name-expr` (a string form or nil-literal) and
   `value?-expr` (true/false literal). Validates slot names; separates scalar
   clauses from relation clauses; emits target-capture forms via `ref-arg->form`.

   §2.1 reader-slot exception: when a slot's target structure declares a `:reader`,
   data literals (symbol/vector/map, not inline `(Tag …)` seqs) in that slot are
   expanded via the reader at macroexpansion time and inlined as value-forms."
  [tag name-expr value?-expr body]
  (let [sdef    (structure-by-tag tag)
        _       (when-not sdef
                  (throw (ex-info (str "defstructure: unknown structure " tag) {:tag tag})))
        doc     (some (fn [c] (when (and (seq? c) (= 'doc (first c))) (second c))) body)
        clauses (remove #(contains? builtin-clauses (first %)) body)
        scalar? (fn [c] (let [s (slot-for sdef (keyword (first c)))] (and s (scalar-slot? s))))
        scalars (into {} (for [c clauses :when (scalar? c)
                               :let [slot (slot-for sdef (keyword (first c)))]
                               pair (cond-> [[(keyword "val" (clojure.core/name (first c))) (second c)]]
                                      (and (:payload slot) (>= (count c) 3))
                                      ;; A payload carries a companion CODE-FORM (a datalog
                                      ;; query, a predicate `(fn …)`) — stored as DATA, not
                                      ;; evaluated. Strip any authoring quote then re-quote so
                                      ;; the leaf holds the form verbatim (symbols like ?n
                                      ;; survive instead of resolving at runtime).
                                      (conj [(keyword "val" (clojure.core/name (:payload slot)))
                                             (list 'quote (unquote-lit (nth c 2)))]))]
                           pair))
        rels    (mapv (fn [c]
                        (let [rk         (keyword (first c))
                              slot       (slot-for sdef rk)
                              target-sdef (when slot (structure-by-tag (:target slot)))]
                          (when-not slot
                            (throw (ex-info (str (clojure.core/name tag) ": `"
                                                 (clojure.core/name rk) "` is not a slot")
                                            {:tag tag :rel rk})))
                          (let [[labels target-forms] (parse-clause-arg-forms (:card slot) (rest c) target-sdef)]
                            (rel-map-form rk (:card slot) target-forms labels))))
                      (remove scalar? clauses))]
    `(->InstanceValue ~tag ~name-expr ~doc ~scalars ~rels ~value?-expr)))

(defn instance-form
  "Macroexpansion-time: build the (->InstanceValue ...) form for an entity instance.
   An optional leading string literal is the entity's name. When it is absent the
   name is nil and the assembler derives `:entity/name` from the binding var's simple
   name — so `(def survey (Lens (focus …)))` names the node \"survey\" with no
   redundant string. Pass a name only to override (e.g. a dotted module name, or a
   var renamed to dodge a collision)."
  [tag args]
  (let [named?    (string? (first args))
        name-expr (when named? (first args))
        body      (if named? (rest args) args)
        ;; a structure may own instance-level authoring sugar via (syntax f): f rewrites the
        ;; body before clause-parsing (e.g. Operation's `[in] -> out` → (in …)/(out …)). The
        ;; transform lives in the vocab; core just invokes it.
        body      (if-let [syn (:syntax (structure-by-tag tag))] (syn body) body)]
    (build-instance-form tag name-expr false body)))

(defn value-form
  "Macroexpansion-time: build the (->InstanceValue ...) form for a ^:value instance.
   Anonymous (name=nil) and content-identified (value?=true)."
  [tag body]
  (build-instance-form tag nil true body))

;; ── nesting: a container instance lifts nested named instances to sibling defs ──
;; `(Subsystem infra-model "doc" (Operation load-model …) …)` is a TOP-LEVEL def-emitting form:
;; the leading symbol is the name AND the var; nested `(Tag sym …)` instances become sibling
;; `def`s (so cross-refs stay VAR-refs) and route by target-type into the container's slots.

(defn- nested-instance?
  "A body form `(Tag sym …)` where Tag is a registered structure and sym a symbol — a nested
   named instance to lift (vs a slot/law clause, or an inline ^:value form)."
  [f]
  (and (seq? f) (symbol? (first f)) (>= (count f) 2) (symbol? (second f))
       (structure-by-tag (keyword (name (first f))))))

(defn- route-slot
  "Which slot a nested instance of `kid-tag` routes to in `sdef`: the slot whose target IS that
   tag (the role slot — an Operation → :exposes, a Kind → :owns), unless `private?`, then the
   `Any`-targeting fallback (the internal :child slot)."
  [sdef kid-tag private?]
  (or (when-not private?
        (some #(when (= (:target %) kid-tag) (:rel %)) (:slots sdef)))
      (some #(when (= (:target %) :Any) (:rel %)) (:slots sdef))))

(declare expand-instance)

(defn expand-instance
  "Def-emitting + nesting expansion of `(sym \"doc\"? body…)` for structure `tag`. Returns
   {:defs [forms] :sym :tag}: nested named instances are lifted to sibling `def`s (cross-refs stay
   var-refs) and routed by target-type into the container's slots; this instance's `def` is last.
   The leading symbol is the name AND the var; a bare string after it is the doc."
  [tag args]
  (let [sym   (first args)
        more  (rest args)
        doc   (when (string? (first more)) (first more))
        body  (if doc (rest more) more)
        sdef  (structure-by-tag tag)
        body  (if-let [syn (:syntax sdef)] (syn body) body)
        nests (filter nested-instance? body)
        cls   (remove nested-instance? body)
        kids  (mapv (fn [nf] (assoc (expand-instance (keyword (name (first nf))) (rest nf))
                                    :private? (boolean (:private (meta (second nf)))))) nests)
        routed (->> kids
                    (group-by #(route-slot sdef (:tag %) (:private? %)))
                    (map (fn [[rel ks]] (cons (symbol (name rel)) (map :sym ks)))))
        clauses (concat (when doc [(list 'doc doc)]) cls routed)
        value   (build-instance-form tag (name sym) false clauses)]
    {:defs (concat (mapcat :defs kids) [(list 'def sym value)])
     :sym sym :tag tag}))

(defn value-content-key
  "A deterministic, purely structural identity for a ^:value InstanceValue.
   Returns a pr-str over [tag-name scalars-map rel-key-seq] where each rel entry
   is [rk-name [target-id...]] with targets resolved recursively:
     - a Var → (var-id v)
     - an InstanceValue → (value-content-key iv) (recurse)
   Order within an ordered clause is preserved (so [A B] ≠ [B A])."
  [^InstanceValue iv]
  (let [tag-name (clojure.core/name (:tag iv))
        scalars  (into (sorted-map) (:scalars iv))
        resolve-target (fn resolve-target [t]
                         (cond
                           (var? t)             (var-id t)
                           (instance? InstanceValue t) (value-content-key t)
                           :else                (pr-str t)))
        rel-keys (mapv (fn [{:keys [rk targets labels]}]
                         [(clojure.core/name rk)
                          labels
                          (mapv resolve-target targets)])
                       (:clauses iv))]
    (pr-str [tag-name scalars rel-keys])))

;; ── defstructure (the one form) ──────────────────────────────────────────────

(defn- parse-slot
  "(slot :rel (card Target) & opts) → {:rel :card :target & opts}."
  [form]
  (let [[_ rel card-form & opts] form]
    (merge {:rel rel
            :card (keyword (first card-form))
            :target (keyword (name (second card-form)))}
           (apply hash-map opts))))

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
  "Define a structure: its slots (relations-with-laws) and free laws. Registers the
   structure-definition and defines a VALUE-RETURNING instantiation macro named `sname`.
   The generated macro returns an `InstanceValue` record: scalar slots go into `:scalars`,
   relation slots into `:clauses` as `{:rk :card :targets [...]}` where each symbol target
   is captured as `(var sym)` (a deferred var reference, safe for forward/cyclic refs).

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
    (when-not (and (seq? form) (#{'slot 'law 'reader 'syntax 'includes 'realized-as} (first form)))
      (throw (ex-info (str "defstructure " sname ": unknown body form " (pr-str form)
                           " — expected (slot ...), (law ...), (reader ...), (syntax ...), (includes ...) or (realized-as ...)")
                      {:structure sname :form form}))))
  (let [value? (boolean (:value (meta sname)))
        tag    (keyword (name sname))
        slots  (mapv parse-slot (filter #(= 'slot (first %)) body))
        _      (doseq [s slots]
                 (when (and (scalar-slot? s) (#{:some :many :ordered} (:card s)))
                   (throw (ex-info
                           (str "defstructure " sname ": scalar slot " (:rel s)
                                " must be (one ...) or (optional ...), not ("
                                (name (:card s)) " ...)")
                           {:structure sname :slot (:rel s) :card (:card s)}))))
        laws   (mapv #(assoc (parse-law %) :owner tag) (filter #(= 'law (first %)) body))
        _      (doseq [law laws] (check-law-recursion! sname law))
        reader-form (some (fn [f] (when (= 'reader (first f)) (second f)))
                          (filter #(= 'reader (first %)) body))
        ;; an instance-level authoring-syntax fn (the reader's analogue, raised from a single
        ;; slot's literal to the whole instance arg-tail): applied to the body before clause
        ;; parsing, so a structure owns its surface sugar (e.g. Operation's `->`) — NOT core.
        syntax-form (some (fn [f] (when (= 'syntax (first f)) (second f)))
                          (filter #(= 'syntax (first %)) body))
        includes (->> body (filter #(= 'includes (first %)))
                      (mapcat rest) (mapv (comp keyword name)))
        realized (some (fn [f] (when (= 'realized-as (first f)) (unquote-lit (second f))))
                       (filter #(= 'realized-as (first %)) body))
        _      (when realized
                 (when (or (seq slots) (seq laws) value? (seq includes) reader-form)
                   (throw (ex-info (str "defstructure " sname
                                        ": a realized concept (realized-as) is pure derived membership —"
                                        " it may not also declare slots, laws, includes, a reader, or ^:value")
                                   {:structure sname})))
                 (when (> (count (filter #(and (seq? %) (= 'realized-as (first %))) body)) 1)
                   (throw (ex-info (str "defstructure " sname ": multiple (realized-as …) forms")
                                   {:structure sname}))))
        sdef   {:tag tag :doc docstring :slots slots :laws laws :value? value?
                :includes includes :realized-as realized}]
    `(do
       (register-structure! (cond-> '~sdef
                              ~reader-form (assoc :reader ~reader-form)
                              ~syntax-form (assoc :syntax ~syntax-form)))
       ~(cond
          realized nil                                   ; realized concept: no constructor
          value?   `(defmacro ~sname ~docstring [& body#]
                      (fukan.canvas.core.structure/value-form ~tag body#))
          :else    `(defmacro ~sname ~docstring [& args#]
                      (if (symbol? (first args#))
                        ;; def-emitting + nesting: `(Tag sym …)` interns the var, lifts nested
                        (cons 'do (:defs (fukan.canvas.core.structure/expand-instance ~tag args#)))
                        ;; value form: `(def x (Tag …))` / `(Tag "name" …)`
                        (fukan.canvas.core.structure/instance-form ~tag args#)))))))

(defmacro defrelation-coproduct
  "Declare a relation as the COPRODUCT (union) of existing relation kinds:
   `(V ?a ?b) ⇐ (kᵢ ?a ?b)` for each member kᵢ. Registers a vocab entry carrying
   `:relation-coproduct`; `derive-rules` emits the union rules so laws/lenses can read
   the umbrella relation `V` at domain altitude. It is the relation-level analogue of a
   `realized-as` coproduct (one level up, over `:rel/kind` instead of node kinds): it has
   no slots, laws, constructor, or instances. Members must be live relation kinds — i.e.
   relation slots present somewhere in the loaded vocab — else the union rule references an
   undefined rule.

     (defrelation-coproduct :view-map \"cross-view mapping\" :via :contextualizes)"
  [rtag docstring & members]
  (let [tag        (keyword (name rtag))
        member-kws (mapv (comp keyword name) members)]
    `(register-structure! {:tag ~tag :doc ~docstring :slots [] :laws [] :includes []
                           :relation-coproduct ~member-kws})))

;; ── laws: slot-derived + free, run over a db ─────────────────────────────────

(defn- relation-slot-laws
  "Cardinality + target-type laws for a RELATION slot (target is a structure).
   When `target` is `:Any` (the wildcard), the target-type law is skipped —
   any node is accepted; only cardinality laws are emitted."
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
    (cond-> (if (= target :Any) [] [target-law])
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
   at domain altitude: `(Operation ?s) (in-module ?s \"…\") (calls ?s ?c)`."
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
                    (vec (cons (list (symbol (name scope-tag)) (first offenders)) where))
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
