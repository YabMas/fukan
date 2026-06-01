(ns fukan.canvas.core.structure
  "The lean-kernel structure primitive (Tier 2 of the rebuild).

   `defstructure` fuses the three pruned half-languages (defconstructor +
   registry/construct + check) into one form, on the insight that *a slot is a
   relation with a law*: one slot declaration yields composition (a Relation),
   authoring grammar (an instantiation clause), and a datalog constraint at once.

   A structure instance is a Node tagged `:structure/of <Tag>`; a slot value is a
   reified Relation (`:rel/from` → `:rel/to`, `:rel/kind`, optional `:rel/label` /
   `:rel/wrap`) — so type combinators ride as a compact `:rel/wrap` annotation
   (design decision 1b) and every cross-reference stays queryable. `check` runs
   every structure's laws (slot-cardinality laws + free `law`s, recursive datalog
   rules supported) over a db.

   Standalone for now: its own minimal, classification-free schema. The Tier-2
   build path rewires canvas_source/store onto this and deletes the old machinery.
   See doc/specs/2026-05-31-defstructure-design.md."
  (:require [datascript.core :as d]))

;; ── substrate (minimal, lean — no node-kind / role / family / classification) ─

(def schema
  {:entity/id    {:db/unique :db.unique/identity}
   :entity/name  {:db/index true}
   :entity/doc   {}                                            ; instance documentation (the (doc ...) clause)
   :structure/of {:db/index true}                              ; the structure tag of an instance
   :module/child {:db/cardinality :db.cardinality/many :db/valueType :db.type/ref}
   ;; reified slot relations — the seam that carries :rel/label and :rel/wrap
   :rel/id       {:db/unique :db.unique/identity}
   :rel/from     {:db/valueType :db.type/ref}
   :rel/kind     {:db/index true}
   :rel/to       {:db/valueType :db.type/ref}
   :rel/label    {}
   :rel/wrap     {}})

(defn create [] (d/empty-db schema))

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

(defmacro with-structures
  "Bind *store* to a fresh structure db, run body, return the db."
  [& body]
  `(binding [*store* (atom (create))]
     ~@body
     @*store*))

(defmacro within-module
  "Declare a module and run `body` with it as the enclosing container; children
   register under it via :module/child. Two-pass: `body` declares the instances
   (queuing their relations); on exit the queued relations are resolved and
   emitted — so forward references and cycles between instances resolve."
  [mname & body]
  `(let [mid# (random-uuid)]
     (transact! [{:entity/id mid# :entity/name ~mname :structure/of :Module}])
     (binding [*enclosing-module*  mid#
               *pending-relations* (atom [])]
       ~@body
       (flush-pending-relations!)
       mid#)))

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

;; ── instantiation (the interpreter: instance → Node + reified slot Relations) ─

(defn- slot-for [sdef rel] (first (filter #(= rel (:rel %)) (:slots sdef))))

(defn- parse-clause-arg
  "A slot clause arg is either `target` (a name) or `[label target]`."
  [arg]
  (if (vector? arg)
    {:label (str (first arg)) :target (second arg)}
    {:label nil :target arg}))

;; Universal built-in clauses are not slots — they set scalar attributes on the
;; instance node rather than emitting relations.
(def ^:private builtin-clauses #{'doc})

(defn- emit-relations!
  "Resolve a queued instance's slot clauses against the now-declared module
   entities and transact its reified relations. Resolution runs after the whole
   module is declared (two-pass), so a target that still does not resolve is a
   genuine error — a typo, or a forward/cross-module reference the by-name,
   module-scoped resolver does not reach — and is thrown, not skipped."
  [{:keys [tag name node-id clauses]}]
  (let [sdef (structure-by-tag tag)
        db   @*store*]
    (doseq [clause clauses]
      (let [rel  (keyword (first clause))
            slot (slot-for sdef rel)
            args (rest clause)]
        (when-not slot
          (throw (ex-info (str name ": `" (clojure.core/name rel)
                               "` is not a slot of " tag)
                          {:tag tag :rel rel})))
        (doseq [arg args]
          (let [{:keys [label target]} (parse-clause-arg arg)
                target-id (resolve-in-module db target)]
            (when-not target-id
              (throw (ex-info (str name ": " (clojure.core/name rel) " references '" target
                                   "', which is not an entity in the enclosing module")
                              {:structure tag :instance name :rel rel :target target})))
            (transact! [(cond-> {:rel/id   (str node-id "|" (clojure.core/name rel)
                                             "|" target "|" (random-uuid))
                                 :rel/from [:entity/id node-id]
                                 :rel/kind rel
                                 :rel/to   [:entity/id target-id]}
                          label (assoc :rel/label label))])))))))

(defn flush-pending-relations!
  "Pass 2 (on `within-module` exit): emit the relations queued during the body,
   now that every instance is declared — so forward references and cycles resolve."
  []
  (when *pending-relations*
    (doseq [pending @*pending-relations*]
      (emit-relations! pending))))

(defn instantiate!
  "Emit an instance of structure `tag` named `name` from `clauses` (slot-rel
   forms + the universal (doc \"...\") clause). Pass 1: declare the node now and
   queue its relations for `flush-pending-relations!` (or emit immediately when
   not inside a `within-module` two-pass scope). Returns the instance :entity/id."
  [tag name clauses]
  (let [sdef        (structure-by-tag tag)
        node-id     (random-uuid)
        doc         (some (fn [c] (when (and (seq? c) (= 'doc (first c))) (second c))) clauses)
        rel-clauses (remove #(contains? builtin-clauses (first %)) clauses)
        pending     {:tag tag :name name :node-id node-id :clauses rel-clauses}]
    (when-not sdef
      (throw (ex-info (str "unknown structure " tag) {:tag tag})))
    (transact! [(cond-> {:entity/id node-id :entity/name (str name) :structure/of tag}
                  doc (assoc :entity/doc doc))])
    (register-child! node-id)
    (if *pending-relations*
      (swap! *pending-relations* conj pending)
      (emit-relations! pending))
    node-id))

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
       (slot :takes (many Type) :label-as :param)
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
    (when-not (and (seq? form) (#{'slot 'law} (first form)))
      (throw (ex-info (str "defstructure " sname ": unknown body form " (pr-str form)
                           " — expected (slot ...) or (law ...)")
                      {:structure sname :form form}))))
  (let [tag   (keyword (name sname))
        slots (mapv parse-slot (filter #(= 'slot (first %)) body))
        ;; stamp the owning structure tag onto each law so check can auto-scope it
        laws  (mapv #(assoc (parse-law %) :owner tag) (filter #(= 'law (first %)) body))
        _     (doseq [law laws] (check-law-recursion! sname law))
        sdef  {:tag tag :doc docstring :slots slots :laws laws}
        impl  `instantiate!]   ; fully-qualified so the generated macro works when :refer-ed
    `(do
       (register-structure! '~sdef)
       (defmacro ~sname
         ~docstring
         [name# & body#]
         (list '~impl ~tag name# (list 'quote body#))))))

;; ── laws: slot-derived + free, run over a db ─────────────────────────────────

(defn- slot-laws
  "Derive cardinality + target-type laws for a structure's slots. Each compiles
   to a datalog offender query over the reified relations."
  [{:keys [tag slots]}]
  (mapcat
   (fn [{:keys [rel card target]}]
     (let [tn (name tag) rn (name rel)
           ;; every target of this slot must be tagged the slot's target structure
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
       ;; card → which laws: one = exactly 1, some = >=1, optional = <=1, many = any
       (cond-> [target-law]
         (= card :one)      (conj (none-law "requires exactly one")
                                  (several-law "requires exactly one"))
         (= card :some)     (conj (none-law "requires at least one"))
         (= card :optional) (conj (several-law "allows at most one")))))
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

(defn- run-query
  "Run a law's offender query, returning the offender rows (or nil if none).
   Only laws with recursive `:rules` can diverge, so only they are run under the
   *law-timeout-ms* guard (via a future); non-recursive laws run inline. Returns
   ::timeout if a guarded query exceeds the budget."
  [db q rules]
  (if (seq rules)
    (let [fut     (future (d/q q db rules))
          results (deref fut *law-timeout-ms* ::timeout)]
      (if (= results ::timeout)
        (do (future-cancel fut) ::timeout)
        results))
    (d/q q db [])))

(defn- run-law [db {:keys [offenders where rules] :as law}]
  (let [scope-tag (law-scope-tag law)
        where*    (if scope-tag
                    (vec (cons [(first offenders) :structure/of scope-tag] where))
                    where)
        q         (vec (concat [:find] offenders [:in '$ '%] [:where] where*))
        results   (run-query db q rules)]
    (cond
      (= results ::timeout) ::timeout
      (seq results)         (mapv vec results)
      :else                 nil)))

(defn check
  "Run every registered structure's laws (slot-cardinality + free) over `db`.
   Returns a vector of result maps:
     {:structure :law :offenders [...]}        — a violation
     {:structure :law :timed-out? true :message …} — a (recursive) law that
        exceeded *law-timeout-ms* instead of completing."
  [db]
  (vec
   (for [{:keys [tag laws] :as sdef} (all-structures)
         {:keys [desc] :as law} (concat (slot-laws sdef) laws)
         :let [r (run-law db law)]
         :when r]
     (if (= r ::timeout)
       {:structure tag :law desc :timed-out? true
        :message (str "law exceeded *law-timeout-ms* (" *law-timeout-ms* "ms) "
                      "without completing — likely unbounded recursion over a "
                      "cyclic/indirect graph (recursion must run over a direct "
                      "binary relation, not a rule-derived one)")}
       {:structure tag :law desc :offenders r}))))
