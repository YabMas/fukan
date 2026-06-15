(ns lib.grammar
  "GRAMMAR REFLECTION — the registry projected into the model. The structure
   registry is the one piece of fukan that lives OFF the graph; opting in here
   reifies it: every defstructure in the model's namespace closure becomes a
   `Structure` node, so vocab and domain become altitudes on one graph — the
   grammar is queryable, law-checkable, and renderable like everything else.

   The lean shape (no Slot wrapper nodes):
     - a SLOT is an EDGE from its Structure: `:rel/kind :slot/<card>` carries the
       cardinality, `:rel/label` the slot name, `:rel/order` the declaration
       position. Its target is the reified target `Structure` — or, for scalar
       and refined slots, the type dialect's own `^:value Schema` node (`:string`
       reifies as ⟨Schema :string⟩, `[:enum …]` as its Schema subgraph), content-
       deduped with every other use of that type anywhere in the model.
     - a LAW is a node: desc + the datalog as a `:val/form` payload (queryable as
       a form, not decomposed — like a `Lens`'s `:select` query payload).
     - a VOCABULARY is one grammar namespace: `:child` edges to its Structures.

   Scope: the namespace closure of the tags in use — every namespace that defines
   a tag instantiated in the db, expanded through slot targets and includes, plus
   this namespace itself (the reflection self-reifies: `Structure` gets a
   Structure node). Reflection is PURE (`with-grammar`: db → db′) and re-runs on
   every build, so the reified grammar can never drift from the registry.

   Opt-in (required, not auto-discovered); fukan's pipeline opts in for the
   self-model; demos compose `with-grammar` onto their own assembly."
  (:require [clojure.string :as str]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            ;; Schema must be registered (and its :valid? bridge wired) before
            ;; reflection can build Schema value targets from slot type forms.
            [lib.type.malli]))

(def ^:private this-ns (str (ns-name *ns*)))

;; ── the meta-grammar ─────────────────────────────────────────────────────────

(defstructure Law
  "A registered law, reified. `:query` recaps the :where; the full datalog
   ({:offenders :where :rules}) rides as the `:form` payload."
  {:desc  :string
   :scope [:? :string]
   :query [{:payload :form} :string]})

(defstructure Structure
  "A registered defstructure, reified into the graph it defines. Slots are
   `:slot/<card>` edges (see the ns doc), not declared here; `:tag` is the
   instance-join key (`(of-structure ?i ?s)` in `rules`); a realized concept
   carries its membership datalog as the `:form` payload of `:realizes`."
  {:tag      :string
   :value    [:? :boolean]
   :includes [:* Structure]
   :law      [:* Law]
   :realizes [:? {:payload :form} :string]})

(defstructure Vocabulary
  "One grammar namespace, reified — the Structures it defines. (Named Vocabulary,
   not Grammar: the BNF demo owns the `Grammar` tag.)"
  {:child [:* Structure]})

(def rules
  "Extra datalog rules over the reified grammar, for queries that join the two
   altitudes: `(of-structure ?i ?s)` binds an instance to its Structure node."
  '[[(of-structure ?i ?s)
     [?i :structure/of ?t]
     [?s :structure/of :lib.grammar/Structure]
     [?s :val/tag ?ts]
     [(clojure.core/str ?t) ?ts]]])

;; ── runtime value construction (the reader expansion, mirrored off-macro) ────

(def ^:private scalar->malli {:int :int, :string :string, :boolean :boolean})

(defn- scalar-slot? [slot]
  (or (contains? scalar->malli (:target slot)) (vector? (:target slot))))

(defn- literal->iv
  "Runtime mirror of the authoring-time reader expansion: a data literal for
   value structure `tag` → an InstanceValue, recursing into relation targets via
   THEIR readers. Lets the reflector build Schema subgraphs from slot type forms
   with the same content keys authored Schemas get — so they dedup."
  [tag literal]
  (let [sdef    (s/structure-by-tag tag)
        clauses ((:reader sdef) literal)
        slot-of (fn [k] (some #(when (= k (:rel %)) %) (:slots sdef)))]
    (reduce
     (fn [iv [head & args]]
       (let [sl (slot-of (keyword head))]
         (cond
           (nil? sl)
           (throw (ex-info (str "reader for " tag " emitted unknown clause " head) {:literal literal}))
           (scalar-slot? sl)
           (assoc-in iv [:scalars (keyword "val" (name head))] (first args))
           :else
           (let [ttag (:target sl)]
             (when-not (:reader (s/structure-by-tag ttag))
               (throw (ex-info (str "cannot reify type form " (pr-str literal) " — slot target "
                                    ttag " has no reader (named-Kind refs are not reflectable)")
                               {:tag tag :literal literal})))
             (update iv :clauses conj {:rk (:rel sl) :card (:card sl)
                                       :targets (mapv #(literal->iv ttag %) args)})))))
     (s/->InstanceValue tag nil nil {} [] true)
     clauses)))

(defn- schema-target
  "A scalar/refined slot target → [content-key {:nodes … :rels …}] of its Schema
   value subgraph (content-deduped with any equal Schema already in the db)."
  [target]
  (let [form (if (vector? target) target (scalar->malli target))
        iv   (literal->iv :lib.type.malli/Schema form)
        key  (s/value-content-key iv)]
    [key (a/emit-instances [[key iv]])]))

;; ── the reflector ─────────────────────────────────────────────────────────────

(defn- structure-id [tag] (str tag))   ; ":ns/Name" — the colon keeps it clear of var-ids

(defn- target-ns [t] (when (and (keyword? t) (namespace t)) (namespace t)))

(defn- ns-closure
  "Expand seed namespaces through their structures' slot targets and includes,
   to a fixpoint — so a reified slot's target Structure is always present."
  [seed]
  (loop [nss (set seed)]
    (let [nxt (into nss
                    (for [sd (s/all-structures)
                          :when (contains? nss (some-> (:tag sd) namespace))
                          t    (concat (map :target (:slots sd)) (:includes sd))
                          :let [n (target-ns t)] :when n]
                      n))]
      (if (= nxt nss) nss (recur nxt)))))

(defn- reflect-structure
  "One sdef → {:nodes … :rels …} for its Structure node, Law children, slot and
   includes edges, and any Schema value targets."
  [{:keys [tag doc slots laws includes value? realized-as]}]
  (let [sid  (structure-id tag)
        node (cond-> {:entity/id sid :structure/of ::Structure
                      :entity/name (name tag) :val/tag (str tag)}
               doc         (assoc :entity/doc doc)
               value?      (assoc :val/value true)
               realized-as (assoc :val/realizes (pr-str realized-as) :val/form realized-as))
        slot-bits
        (map-indexed
         (fn [i sl]
           (let [label (name (:rel sl))
                 kind  (keyword "slot" (name (:card sl)))
                 [tid emitted] (if (scalar-slot? sl)
                                 (schema-target (:target sl))
                                 [(structure-id (:target sl)) nil])]
             {:emitted emitted
              :any?    (= :Any (:target sl))
              :rel     (cond-> {:rel/id   (str sid "|" (name kind) "|" label)
                                :rel/from [:entity/id sid] :rel/kind kind
                                :rel/to   [:entity/id tid]
                                :rel/label label :rel/order i}
                         (:payload sl) (assoc :rel/payload (:payload sl)))}))
         slots)
        law-bits
        (map-indexed
         (fn [i law]
           {:node (cond-> {:entity/id (str sid "#law/" i) :structure/of ::Law
                           :val/desc  (:desc law)
                           :val/query (pr-str (:where law))
                           ;; :src = the authored combinator form, when the law was
                           ;; authored through one (the print-dual renders it back)
                           :val/form  (select-keys law [:offenders :where :rules :src])}
                    (:scope law) (assoc :val/scope (str (:scope law))))
            :rel  {:rel/id   (str sid "|law|" i)
                   :rel/from [:entity/id sid] :rel/kind :law
                   :rel/to   [:entity/id (str sid "#law/" i)]}})
         laws)
        inc-rels
        (for [itag includes]
          {:rel/id   (str sid "|includes|" itag)
           :rel/from [:entity/id sid] :rel/kind :includes
           :rel/to   [:entity/id (structure-id itag)]})]
    {:any? (boolean (some :any? slot-bits))
     :nodes (into [node] (concat (mapcat (comp :nodes :emitted) slot-bits)
                                 (map :node law-bits)))
     :rels  (vec (concat (mapcat (comp :rels :emitted) slot-bits)
                         (map :rel slot-bits)
                         (map :rel law-bits)
                         inc-rels))}))

(defn with-grammar
  "PURE: db → db′ with the model's grammar reified in (see the ns doc for shape
   and scope). Idempotent per build — ids are deterministic, so re-reflection
   upserts rather than duplicates. `extra-seeds` (ns-name strings) are added to the
   reflection closure, so a namespace with NO instantiated tags still reflects — the
   case for a pure-grammar stratum like `canvas.subject` (portraits, no instances)."
  ([db] (with-grammar db nil))
  ([db extra-seeds]
   (let [tags   (map first (d/q '[:find ?t :where [_ :structure/of ?t]] db))
         ;; seed with this ns (the reflection self-reifies), the Schema dialect's
         ;; (reflection emits Schema value targets, so their grammar must be present),
         ;; and any caller-supplied seeds (zero-instance grammar strata)
         nss    (ns-closure (into (conj (set (keep target-ns tags)) this-ns "lib.type.malli")
                                  (map str (or extra-seeds []))))
         sds    (->> (s/all-structures)
                     (filter #(contains? nss (some-> (:tag %) namespace)))
                     (sort-by (comp str :tag)))
         bits   (map reflect-structure sds)
         vocabs (for [[vns members] (group-by (comp namespace :tag) sds)]
                  {:node (let [vid (str "vocabulary:" vns)]
                           {:entity/id vid :structure/of ::Vocabulary :entity/name vns})
                   :rels (for [m members]
                           {:rel/id   (str "vocabulary:" vns "|child|" (:tag m))
                            :rel/from [:entity/id (str "vocabulary:" vns)]
                            :rel/kind :child
                            :rel/to   [:entity/id (structure-id (:tag m))]})})
         any    (when (some :any? bits)
                  [{:entity/id ":Any" :structure/of ::Structure
                    :entity/name "Any" :val/tag ":Any"
                    :entity/doc "The wildcard target — any node."}])
         nodes  (concat (mapcat :nodes bits) (map :node vocabs) any)
         rels   (concat (mapcat :rels bits) (mapcat :rels vocabs))
         ;; a slot/includes referencing a tag NOBODY registered is a dangling
         ;; grammar ref (e.g. a misresolved (includes Foo)) — fail with the tag,
         ;; not datascript's cryptic missing-entity error at transact.
         known  (into #{} (map :entity/id) nodes)
         _      (doseq [r rels
                        :let [[_ tid] (:rel/to r)]
                        :when (and (str/starts-with? tid ":")
                                   (not (contains? known tid)))]
                  (throw (ex-info (str "grammar reflection: " tid " is referenced by a slot or "
                                       "includes but no such structure is registered — dangling "
                                       "grammar reference (check the defining ns is required)")
                                  {:rel r})))]
     (-> db (d/db-with (vec nodes)) (d/db-with (vec rels))))))
