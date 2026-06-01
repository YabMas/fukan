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
  "Declare a module and run body with it as the enclosing container; children
   declared inside register under it via :module/child."
  [mname & body]
  `(let [mid# (random-uuid)]
     (transact! [{:entity/id mid# :entity/name ~mname :structure/of :Module}])
     (binding [*enclosing-module* mid#]
       ~@body
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

(defn instantiate!
  "Emit an instance of structure `tag` named `name` from `clauses` (a seq of
   (slot-rel arg*) forms, plus the universal (doc \"...\") clause). Returns the
   instance :entity/id."
  [tag name clauses]
  (let [sdef    (structure-by-tag tag)
        node-id (random-uuid)
        doc     (some (fn [c] (when (and (seq? c) (= 'doc (first c))) (second c))) clauses)]
    (when-not sdef
      (throw (ex-info (str "unknown structure " tag) {:tag tag})))
    (transact! [(cond-> {:entity/id node-id :entity/name (str name) :structure/of tag}
                  doc (assoc :entity/doc doc))])
    (register-child! node-id)
    (doseq [clause clauses
            :when (not (contains? builtin-clauses (first clause)))]
      (let [rel  (keyword (first clause))
            slot (slot-for sdef rel)
            args (rest clause)]
        (when-not slot
          (throw (ex-info (str name ": `" (clojure.core/name rel)
                               "` is not a slot of " tag)
                          {:tag tag :rel rel})))
        (doseq [arg args]
          (let [{:keys [label target]} (parse-clause-arg arg)
                target-id (resolve-in-module @*store* target)]
            (if target-id
              (transact! [(cond-> {:rel/id   (str node-id "|" (clojure.core/name rel)
                                               "|" target "|" (random-uuid))
                                   :rel/from [:entity/id node-id]
                                   :rel/kind rel
                                   :rel/to   [:entity/id target-id]}
                            label (assoc :rel/label label))])
              (binding [*out* *err*]
                (println (str name ": " (clojure.core/name rel) " target '" target
                              "' not found in enclosing module — skipping relation"))))))))
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
  "(law \"desc\" :offenders '[?vars] :where '[clauses] :rules '[rules]?)."
  [form]
  (let [[_ desc & kvs] form
        m (apply hash-map kvs)]
    {:desc desc
     :offenders (unquote-lit (:offenders m))
     :where     (unquote-lit (:where m))
     :rules     (unquote-lit (:rules m))}))

(defmacro defstructure
  "Define a structure: its slots (relations-with-laws) and free laws. Registers
   the structure-definition and defines an instantiation macro named `sname`.

     (defstructure Function \"...\"
       (slot :takes (many Type) :label-as :param)
       (slot :gives (one  Type))
       (law \"...\" :offenders '[?f] :where '[...] :rules '[...]?))

   Instantiate with the generated macro, slot names as clause heads:
     (Function \"load-model\" (takes [src String]) (gives Model))"
  [sname docstring & body]
  (let [tag   (keyword (name sname))
        slots (mapv parse-slot (filter #(and (seq? %) (= 'slot (first %))) body))
        laws  (mapv parse-law  (filter #(and (seq? %) (= 'law (first %))) body))
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
           none-law {:desc (str tn "." rn " requires exactly one (found none)")
                     :offenders '[?x]
                     :where [['?x :structure/of tag]
                             (list 'not-join ['?x]
                                   ['?r :rel/from '?x] ['?r :rel/kind rel])]}
           several-law (fn [verb]
                         {:desc (str tn "." rn " " verb " (found several)")
                          :offenders '[?x]
                          :where [['?x :structure/of tag]
                                  ['?r1 :rel/from '?x] ['?r1 :rel/kind rel]
                                  ['?r2 :rel/from '?x] ['?r2 :rel/kind rel]
                                  [(list 'not= '?r1 '?r2)]]})]
       (cond-> [target-law]
         (= card :one)      (conj none-law (several-law "requires exactly one"))
         (= card :optional) (conj (several-law "allows at most one")))))
   slots))

(defn- run-law [db {:keys [offenders where rules]}]
  (let [q       (vec (concat [:find] offenders [:in '$ '%] [:where] where))
        results (d/q q db (or rules []))]
    (when (seq results) (mapv vec results))))

(defn check
  "Run every registered structure's laws (slot-cardinality + free) over `db`.
   Returns a vector of {:structure :law :offenders} violation maps."
  [db]
  (vec
   (for [{:keys [tag laws] :as sdef} (all-structures)
         {:keys [desc] :as law} (concat (slot-laws sdef) laws)
         :let [offenders (run-law db law)]
         :when offenders]
     {:structure tag :law desc :offenders offenders})))
