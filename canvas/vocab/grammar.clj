(ns canvas.vocab.grammar
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

   A modelling TOOL, not core: the runtime (`check`/`assemble`/`evaluate-lens`) never
   consults the reflected nodes — they exist only so the grammar is viewable as data (the
   print-dual primer, the `unused-structures` grammar-drift reading). So it lives in
   canvas/vocab, and the native build runs `reflect` (via `build/with-grammar-cozo`) on every build."
  (:require [clojure.string :as str]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.canvas.core.typing :as typing]
            ;; Schema must be registered (and its :valid?/:reflect bridges wired) before
            ;; reflection can build Schema value targets from slot type forms.
            [canvas.vocab.type]))

(def ^:private this-ns (str (ns-name *ns*)))

;; ── the meta-grammar ─────────────────────────────────────────────────────────

(defstructure Law
  "A registered law, reified. `:query` recaps the :where; the full datalog
   ({:offenders :where :rules}) rides as the `:form` payload."
  {:desc  :string
   :scope [:? :string]
   :query [{:payload :form} :string]}
  ;; OWNERSHIP — the reflector's self-check. A Law has no independent existence: it is asserted BY a
  ;; Structure (ownership-on-owner), so every reified Law must have an incoming `:law` edge. An orphan
  ;; Law is a defect of THIS reflection, not a modelling mistake — which is why it lives here on the
  ;; reified type (self-scoped to `:canvas.vocab.grammar/Law`), not as a design law on a modelled portrait.
  (law "every reified Law is owned by an asserting Structure"
    :offenders '[?l]
    :rules '[[(asserted ?l) [?r :rel/kind :law] [?r :rel/to ?l]]]
    :where '[(not (asserted ?l))]))

(defstructure Structure
  "A registered defstructure, reified into the graph it defines. Slots are
   `:slot/<card>` edges (see the ns doc), not declared here; `:tag` is the
   instance-join key (`(of-structure ?i ?s)` in `rules`); a realized concept
   carries its membership datalog as the `:form` payload of `:realizes`."
  {:tag      :string
   :value    [:? :boolean]
   :includes [:* Structure]
   :law      [:* Law]
   :realizes [:? {:payload :form} :string]}
  ;; TOTALITY — the reflector's self-check. A Structure's identity IS its defining namespace, so every
  ;; reified Structure belongs to a Vocabulary (an incoming `:child` edge from a `:canvas.vocab.grammar/Vocabulary`
  ;; node). The synthetic `:Any` wildcard is not an authored Structure, so it is exempt. A missing
  ;; Vocabulary is a defect of THIS reflection — hence here, self-scoped to `:canvas.vocab.grammar/Structure`.
  (law "every reified Structure is defined in a Vocabulary"
    :offenders '[?s]
    :rules '[[(in-vocabulary ?s)
              [?v :structure/of :canvas.vocab.grammar/Vocabulary]
              [?r :rel/kind :child] [?r :rel/from ?v] [?r :rel/to ?s]]]
    :where '[[?s :val/tag ?tag]
             [(clojure.core/not= ?tag ":Any")]
             (not (in-vocabulary ?s))]))

(defstructure Vocabulary
  "One grammar namespace, reified — the Structures it defines. (Named Vocabulary,
   not Grammar: the BNF demo owns the `Grammar` tag.)"
  {:child [:* Structure]})

(def rules
  "Extra datalog rules over the reified grammar, for queries that join the two
   altitudes: `(of-structure ?i ?s)` binds an instance to its Structure node."
  '[[(of-structure ?i ?s)
     [?i :structure/of ?t]
     [?s :structure/of :canvas.vocab.grammar/Structure]
     [?s :val/tag ?ts]
     [(clojure.core/str ?t) ?ts]]])

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
                 [tid emitted] (if (s/scalar-slot? sl)
                                 (let [sub (typing/reflect-type (:target sl))]
                                   [(:id sub) sub])
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

(defn reflect
  "PURE, db-agnostic: the model's reified-grammar `{:nodes :rels}` for the structure `tags` in use
   (the `:structure/of` values, as keywords) + `extra-seeds` (ns-name strings added to the reflection
   closure, so a zero-instance grammar stratum still reflects). The caller transacts/inserts the datoms
   onto its substrate (datascript via `with-grammar`, Cozo via the native build's upsert insert)."
  [tags extra-seeds]
  (let [;; seed with this ns (the reflection self-reifies), the Schema dialect's (reflection emits
        ;; Schema value targets, so their grammar must be present), and any caller-supplied seeds
        nss    (ns-closure (into (conj (set (keep target-ns tags)) this-ns "canvas.vocab.type")
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
        ;; a slot/includes referencing a tag NOBODY registered is a dangling grammar ref (e.g. a
        ;; misresolved (includes Foo)) — fail with the tag, not a cryptic missing-entity error.
        known  (into #{} (map :entity/id) nodes)]
    (doseq [r rels
            :let [[_ tid] (:rel/to r)]
            :when (and (str/starts-with? tid ":") (not (contains? known tid)))]
      (throw (ex-info (str "grammar reflection: " tid " is referenced by a slot or "
                           "includes but no such structure is registered — dangling "
                           "grammar reference (check the defining ns is required)")
                      {:rel r})))
    {:nodes (vec nodes) :rels (vec rels)}))
