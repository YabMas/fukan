(ns fukan.canvas.core.reflect
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
       a form, not decomposed — like a `Lens`'s `:select` code-form leaf).
     - a VOCABULARY is one grammar namespace: `:child` edges to its Structures.
     - a MORPHISM is a node per registered `(correspond …)`: `:from`/`:to` edges to its domain
       and codomain Structures, `RelationMap` children carrying the relation maps.

   Scope: the namespace closure of the tags in use — every namespace that defines
   a tag instantiated in the db, expanded through slot targets, plus
   this namespace itself (the reflection self-reifies: `Structure` gets a
   Structure node). Reflection is PURE (`with-grammar`: db → db′) and re-runs on
   every build, so the reified grammar can never drift from the registry.

   Kernel-native MACHINERY — this is CORE, not the reusable `fukan.common` vocab: reflection is
   grammar-AGNOSTIC (it reifies whatever registry exists, knowing no specific vocabulary), and the
   native build ALWAYS runs it (via `build/with-grammar`). Its meta-grammar (`Structure`/`Law`/
   `Vocabulary`/`Relation`/`Morphism`/`RelationMap`) is the tool's vocabulary for describing
   grammars AND the morphisms between them — the same category as
   the act grammar in `fukan.canvas.core.lens`, so it sits beside it in core. The runtime
   (`check`/`assemble`/`evaluate-lens`) never consults the reflected nodes — they exist only so the
   grammar is viewable as data (the print-dual primer, the `unused-structures` grammar-drift reading).
   The type dialect is reached only through the neutral SPI (`fukan.canvas.core.typing/reflect-type`,
   which returns nil when no dialect is registered), so reflection depends on NO concrete dialect; the
   composition root wires the dialect. Schema's grammar is seeded into the reflection closure by NAME
   (the `fukan.common.typing.malli` string below) so its `^:value` structures reflect even with zero
   Schema instances — a data reference, not a code dependency."
  (:require [clojure.string :as str]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.canvas.core.typing :as typing]))

(def ^:private this-ns (str (ns-name *ns*)))

;; ── the meta-grammar ─────────────────────────────────────────────────────────

(defstructure Law
  "A registered law, reified. `:query` recaps the :where; the full datalog
   ({:offenders :where :rules}) rides as the `:form` payload."
  {:desc  :string
   :scope [:? :string]
   :query [{:payload :form} :string]}
  ;; OWNERSHIP — the reflector's self-check. A Law has no independent existence: it is asserted BY a
  ;; Structure (ownership-on-owner), so every reified Law must be the target of some `:law` edge. An
  ;; orphan Law is a defect of THIS reflection, not a modelling mistake — which is why it lives here on
  ;; the reified type (self-scoped to `:fukan.canvas.core.reflect/Law`), not as a design law on a portrait.
  (law "every reified Law is owned by an asserting Structure"
    (matched-by :law)))

(defstructure Structure
  "A registered defstructure, reified into the graph it defines. Slots are
   `:slot/<card>` edges (see the ns doc), not declared here; `:tag` is the
   instance-join key (an instance's mirror-stringified `:structure/of` names the
   Structure whose `:val/tag` is its colon-prefixed form); a realized concept
   carries its membership datalog as the `:form` payload of `:realizes`."
  {:tag      :string
   :value    [:? :boolean]
   :law      [:* Law]
   :realizes [:? {:payload :form} :string]}
  ;; TOTALITY — the reflector's self-check. A Structure's identity IS its defining namespace, so every
  ;; reified Structure is the target of a `:child` edge from its `Vocabulary`. The synthetic `:Any`
  ;; wildcard is not an authored Structure, so it is exempt (:unless its tag is ":Any"). A missing
  ;; Vocabulary is a defect of THIS reflection — hence here, self-scoped to `:fukan.canvas.core.reflect/Structure`.
  (law "every reified Structure is defined in a Vocabulary"
    (matched-by :child :from Vocabulary :unless {:tag ":Any"})))

(defstructure Vocabulary
  "One grammar namespace reified as a SIGNATURE: the Structures (sorts) it defines, the Relation
   elements it declares (`:relation`, from the element's recorded `:ns` — an unqualified relation
   tag cannot carry its namespace, so ownership rides the declaration), and the vocabularies it
   references (`:imports` — DERIVED, never authored: slot targets crossing namespaces, law bodies
   calling another vocabulary's rules, a correspondence's codomain. Entail, don't store — the
   namespace IS the signature, its inclusions are computed from actual use). Slot-declared
   relations that are not elements yet (`:calls`, `:delegates`) belong to no signature — that
   asymmetry is the relations-first-class residual, visible here. (Named Vocabulary, not Grammar:
   the BNF demo owns the `Grammar` tag.)"
  {:child    [:* Structure]
   :relation [:* Relation]
   :imports  [:* Vocabulary]})

(defstructure Relation
  "A reflected relation KIND (`:child`/`:calls`/`:delegates`/…), reified so the grammar's EDGE
   vocabulary is queryable like its node vocabulary (`Structure`). An ELEMENT carries its
   declaration's right-hand side in the SAME (direction, expression) shape a `RelationMap` uses:
   `:incl` (`:sub`/`:sup`/`:eq`) + `:expr` (the inclusion expression, as edn) for an inclusion
   element, or `:incl` `:eq` + the defining datalog as the `:rule` payload for a DERIVED element —
   a definitional extension is definitionally exact. A BARE element (a genus) and a slot-only
   relation (not an element yet — `:calls`, `:delegates`) carry neither. (`:transitive` is gone:
   closures belong to the compiler, not to the relation.) A reflection TOOL, not authored; the
   runtime never reads it."
  {:incl [:? :string]
   :expr [:? :string]
   :rule [:? {:payload :form} :string]})

(defstructure RelationMap
  "One relation map of a reflected `Morphism`: a design relation carried to a fact-side relation
   expression, with an inclusion direction — `(:delegates :sub :public-call)` reified. `:rel` is the
   design relation, `:incl` the direction (`:sub` ⊑ preserve / `:sup` ⊒ reflect / `:eq` ≡ both),
   `:expr` the fact-side expression (a named relation atom or an inline regular path), stored as
   edn — NOT a `:form` payload, because a bare keyword atom (`:public-call`) is lossy through the
   mirror's keyword-leaf stringification; the pr-str scalar round-trips faithfully."
  {:rel  :string
   :incl :string
   :expr :string})

(defstructure Morphism
  "A reflected THEORY MORPHISM — one external `(correspond Design Fact …)` seam, reified as a node
   so the morphism is data like the presentations it connects (a `Law` reflects with its datalog
   queryable; the sort map + relation maps deserve no less). `:from`/`:to` are the domain and
   codomain `Structure`s; `:incl` the object map's inclusion (`:eq`/`:sub`/`:sup`); `:restrict`
   the codomain sub-sort it maps onto (`[Fn :public]`'s `:public`); `:bridge` the carrier
   correlation strategy; `:map` the relation maps in authoring order. `:agrees` carries any
   AUTHORED comparator escape-hatch demands as a form payload — the object-map and derived-identity
   demands are NOT stored, they are consequences the registry recomputes
   (`structure/effective-node-demands`). A reflection TOOL, not authored; the runtime never reads it."
  {:incl     :string
   :from     Structure
   :to       Structure
   :restrict [:? :string]
   :bridge   [:? :string]
   :map      [:* RelationMap]
   :agrees   [:? {:payload :form} :string]})

;; ── the reflector ─────────────────────────────────────────────────────────────

(defn- structure-id [tag] (str tag))   ; ":ns/Name" — the colon keeps it clear of var-ids

(defn- target-ns [t] (when (and (keyword? t) (namespace t)) (namespace t)))

(defn- rule-calls
  "The rule NAMES called by a seq of datalog clauses — every list clause `(rule …)`, recursing
   through the negation/disjunction/measure wrappers. Conservative: vector clauses (datom
   patterns and `[(pred …)]` predicate bindings) call no rules — a predicate is a Clojure var,
   a different category."
  [clauses]
  (letfn [(walk [c]
            (when (and (seq? c) (symbol? (first c)))
              (let [[op & args] c]
                (case op
                  (not or and)       (mapcat walk args)
                  (not-join or-join) (mapcat walk (rest args))    ; drop the leading var vector
                  measure            (mapcat walk (drop 2 args))  ; (measure ?out (agg ?v) body…)
                  [(name op)]))))]
    (set (mapcat walk clauses))))

(defn- sdef-clauses
  "Every datalog clause an sdef's own declarations carry: its laws' `:where` (+ the bodies of any
   inline `:rules`) and its `realized-as` membership body. The clause surface the signature
   derivations (closure + imports) walk for cross-vocabulary rule calls."
  [sd]
  (concat (mapcat (fn [l] (concat (:where l) (mapcat rest (:rules l)))) (:laws sd))
          (:realized-as sd)))

(defn- expr-atoms
  "The relation names an inclusion expression references — the atoms of the regular term
   (`[:alt :via :contextualizes]` → via, contextualizes)."
  [e]
  (cond (keyword? e) [(name e)]
        (vector? e)  (mapcat expr-atoms (rest e))
        :else        nil))

(defn- element-refs
  "The names a relation ELEMENT's own declaration references: its derived bodies' rule calls
   and its inclusion expression's atoms (an inclusion is a cross-signature reference like any
   other — `:child (:sub :contains)` reaches grouping)."
  [el]
  (concat (rule-calls (apply concat (:bodies (:derived-rule el))))
          (expr-atoms (:expr (:relation-incl el)))))

(defn- ns-closure
  "Expand seed namespaces to a fixpoint through everything a signature in scope REACHES: its
   structures' slot targets, its correspondences' fact tags, and — resolved through
   `resolve-call` (rule name → declaring ns) — the rules its laws and owned relation elements
   call. So a reified slot's target Structure, a Morphism's codomain, and every imported
   vocabulary (even one contributing only relations, like a genus-declaring primitive vocab)
   are always present."
  [seed resolve-call]
  (loop [nss (set seed)]
    (let [nxt (into nss
                    (concat
                     (for [sd (s/all-structures)
                           :when (contains? nss (some-> (:tag sd) namespace))
                           n    (concat (keep target-ns (mapcat #(or (:alts %) [(:target %)]) (:slots sd)))
                                        (some-> (s/correspondence-of (:tag sd)) :fact-tag target-ns list)
                                        (keep resolve-call (rule-calls (sdef-clauses sd))))
                           :when n]
                       n)
                     (for [sd (s/all-structures)
                           :when (and (nil? (namespace (:tag sd))) (contains? nss (:ns sd)))
                           n    (keep resolve-call (element-refs sd))
                           :when n]
                       n)))]
      (if (= nxt nss) nss (recur nxt)))))

(defn- reflect-structure
  "One sdef → {:nodes … :rels …} for its Structure node, Law children, slot edges,
   and any Schema value targets."
  [{:keys [tag doc slots laws value? realized-as]}]
  (let [sid  (structure-id tag)
        ;; a correspondence is NOT stamped here: the morphism reflects as its own `Morphism` node
        ;; (see `reflect-morphism`), decomposed — not a payload blob on the design Structure.
        node (cond-> {:entity/id sid :structure/of ::Structure
                      :entity/name (name tag) :val/tag (str tag)}
               doc         (assoc :entity/doc doc)
               value?      (assoc :val/value true)
               realized-as (assoc :val/realizes (pr-str realized-as) :val/form realized-as))
        slot-bits
        (->> slots
             (map-indexed
              (fn [i sl]
                (let [label  (name (:rel sl))
                      kind   (keyword "slot" (name (:card sl)))
                      props* (not-empty (dissoc sl :rel :card :target :type-form? :payload :alts))
                      mk     (fn [suffix tid emitted any?]
                               {:emitted emitted
                                :any?    any?
                                :rel     (cond-> {:rel/id   (str sid "|" (name kind) "|" label suffix)
                                                  :rel/from [:entity/id sid] :rel/kind kind
                                                  :rel/to   [:entity/id tid]
                                                  :rel/label label :rel/order i}
                                           (:payload sl) (assoc :rel/payload (:payload sl))
                                           props*        (assoc :rel/props (pr-str props*)))})]
                  ;; a UNION slot reflects as one edge per alternative (same label + order; the
                  ;; alt position rides the id suffix — the print-dual regroups by label)
                  (if-let [alts (:alts sl)]
                    (map-indexed (fn [j a] (mk (str "|" j) (structure-id a) nil false)) alts)
                    [(if (s/scalar-slot? sl)
                       (let [sub (typing/reflect-type (:target sl))]
                         (mk "" (:id sub) sub false))
                       (mk "" (structure-id (:target sl)) nil (= :Any (:target sl))))]))))
             (mapcat identity))
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
         laws)]
    {:any? (boolean (some :any? slot-bits))
     :nodes (into [node] (concat (mapcat (comp :nodes :emitted) slot-bits)
                                 (map :node law-bits)))
     :rels  (vec (concat (mapcat (comp :rels :emitted) slot-bits)
                         (map :rel slot-bits)
                         (map :rel law-bits)))}))

(defn- reflect-morphism
  "One design sdef's registered correspondence → its `Morphism` node (+ `RelationMap` children,
   Law-style positional ids), or nil. `:from`/`:to` edges target the two reified Structures (the
   codomain is guaranteed present by `ns-closure`'s fact-tag expansion); scalar fields hold the
   keyword pr-str'd (the print-dual reads them back); each relation map is a child node in
   authoring order carrying its expression as edn; authored `agrees` demands (and only
   those — the object-map/identity demands are recomputed consequences) ride the `:agrees`
   payload."
  [{:keys [tag]}]
  (when-let [{:keys [fact-tag incl restrict bridge demands rel-demands]} (s/correspondence-of tag)]
    (let [mid   (str "morphism:" tag)
          mnode (cond-> {:entity/id mid :structure/of ::Morphism
                         :entity/name (str (name tag) "↦" (name fact-tag))
                         :val/incl (pr-str incl)}
                  restrict      (assoc :val/restrict (pr-str restrict))
                  bridge        (assoc :val/bridge (pr-str bridge))
                  (seq demands) (assoc :val/agrees (pr-str (vec demands)) :val/form (vec demands)))
          maps  (map-indexed
                 (fn [i {:keys [rel incl expr]}]
                   {:node {:entity/id (str mid "#map/" i) :structure/of ::RelationMap
                           :entity/name (name rel)
                           :val/rel (pr-str rel) :val/incl (pr-str incl)
                           :val/expr (pr-str expr)}
                    :rel  {:rel/id   (str mid "|map|" i)
                           :rel/from [:entity/id mid] :rel/kind :map
                           :rel/to   [:entity/id (str mid "#map/" i)] :rel/order i}})
                 rel-demands)]
      {:nodes (into [mnode] (map :node maps))
       :rels  (into [{:rel/id (str mid "|from") :rel/from [:entity/id mid]
                      :rel/kind :from :rel/to [:entity/id (structure-id tag)]}
                     {:rel/id (str mid "|to") :rel/from [:entity/id mid]
                      :rel/kind :to :rel/to [:entity/id (structure-id fact-tag)]}]
                    (map :rel maps))})))

(defn ^{:malli/schema [:=> [:catn [:tags [:vector :any]] [:extra-seeds [:vector :any]]] :map]}
  reflect
  "PURE, db-agnostic: the model's reified-grammar `{:nodes :rels}` for the structure `tags` in use
   (the `:structure/of` values, as keywords) + `extra-seeds` (ns-name strings added to the reflection
   closure, so a zero-instance grammar stratum still reflects). The caller inserts the datoms onto
   its Cozo substrate (the native build's upsert insert)."
  [tags extra-seeds]
  ;; assemble the seam for its VALIDATION side-effect: a cross-family duplicate law key
  ;; throws here, so the guard fires on every build (reflection runs on every build)
  (s/correspondence)
  (let [;; relation ELEMENTS — read from the FULL registry: an element's tag is unqualified
        ;; (`:contains`), so it belongs to no vocabulary namespace and an ns-filter would drop it.
        ;; (That global name is also why a cross-namespace re-declaration is a collision — caught
        ;; loudly at registration.)
        rel-elems  (into {} (for [sd (s/all-structures) :when (:relation-element sd)]
                              [(:tag sd) sd]))
        ;; rule-call → declaring signature, for the closure and the derived `:imports`: relation
        ;; elements first, then structure short names (kind rules) when globally unambiguous; a
        ;; trailing `+` resolves to its base relation (the closure rides the declaration).
        ;; Unresolvable names (substrate rules, slot-only relations — no signature owns those
        ;; yet) contribute nothing.
        elem-ns    (into {} (for [[rk el] rel-elems :when (:ns el)] [(name rk) (:ns el)]))
        short-ns   (let [by-short (group-by (comp name :tag)
                                            (filter (comp namespace :tag) (s/all-structures)))]
                     (into {} (for [[n gs] by-short
                                    :let [gnss (distinct (map (comp namespace :tag) gs))]
                                    :when (= 1 (count gnss))]
                                [n (first gnss)])))
        resolve-call (fn [nm]
                       (let [nm (cond-> nm (str/ends-with? nm "+") (subs 0 (dec (count nm))))]
                         (or (elem-ns nm) (short-ns nm))))
        ;; seed with this ns (the reflection self-reifies), the Schema dialect's (reflection emits
        ;; Schema value targets, so their grammar must be present), and any caller-supplied seeds
        nss    (ns-closure (into (conj (set (keep target-ns tags)) this-ns "fukan.common.typing.malli")
                                 (map str (or extra-seeds [])))
                           resolve-call)
        sds    (->> (s/all-structures)
                    (filter #(contains? nss (some-> (:tag %) namespace)))
                    (sort-by (comp str :tag)))
        bits   (map reflect-structure sds)
        grouped (group-by (comp namespace :tag) sds)
        ;; SIGNATURE ownership: an element belongs to the Vocabulary of its declaring `:ns` (the
        ;; declaration records it precisely because the unqualified tag cannot) — reflected when
        ;; that vocabulary is in scope
        rel-owned  (for [[rk el] rel-elems :when (contains? nss (:ns el))] [(:ns el) rk])
        vocab-nss  (into (set (keys grouped)) (map first rel-owned))
        ;; the DERIVED imports of one vocabulary: the signatures it actually reaches — slot
        ;; targets crossing namespaces, its correspondences' codomains, and every name its laws /
        ;; owned relation elements reference that another signature declares
        imports-of (fn [vns]
                     (let [called (into (set (mapcat (comp rule-calls sdef-clauses) (grouped vns)))
                                        (mapcat (fn [[_ el]] (when (= vns (:ns el)) (element-refs el)))
                                                rel-elems))
                           slot-t (for [sd (grouped vns), sl (:slots sd)
                                        :when (not (s/scalar-slot? sl))
                                        t (or (:alts sl) [(:target sl)])
                                        :let [n (target-ns t)] :when n] n)
                           fact-t (keep #(some-> (s/correspondence-of (:tag %)) :fact-tag namespace)
                                        (grouped vns))]
                       (->> (concat (keep resolve-call called) slot-t fact-t)
                            (filter #(and (not= % vns) (contains? vocab-nss %)))
                            set)))
        vocabs (for [vns (sort vocab-nss)
                     :let [vid (str "vocabulary:" vns)]]
                 {:node {:entity/id vid :structure/of ::Vocabulary :entity/name vns}
                  :rels (concat
                         (for [m (grouped vns)]
                           {:rel/id   (str vid "|child|" (:tag m))
                            :rel/from [:entity/id vid] :rel/kind :child
                            :rel/to   [:entity/id (structure-id (:tag m))]})
                         (for [[ens rk] rel-owned :when (= ens vns)]
                           {:rel/id   (str vid "|relation|" (name rk))
                            :rel/from [:entity/id vid] :rel/kind :relation
                            :rel/to   [:entity/id (str "relation:" (name rk))]})
                         (for [b (sort (imports-of vns))]
                           {:rel/id   (str vid "|imports|" b)
                            :rel/from [:entity/id vid] :rel/kind :imports
                            :rel/to   [:entity/id (str "vocabulary:" b)]}))})
        any    (when (some :any? bits)
                 [{:entity/id ":Any" :structure/of ::Structure
                   :entity/name "Any" :val/tag ":Any"
                   :entity/doc "The wildcard target — any node."}])
        ;; reflect relation KINDS — one Relation node per relation ELEMENT (`defrelation`: bare,
        ;; inclusion, or derived) UNIONed with every distinct (non-scalar) slot kind. Completes
        ;; grammar reflection: edge-kinds reified, not just node-types. A genus (`contains`) has no
        ;; slot of its own and is reflected purely from its element; an element's right-hand side
        ;; reflects in the same (direction, expression) shape a RelationMap carries — `:incl`+
        ;; `:expr` for an inclusion, `:incl :eq` + the `:rule` payload for a derived definition.
        rel-slots  (remove s/scalar-slot? (mapcat :slots sds))
        relation-nodes
        (for [rk    (sort-by name (into (set (keys rel-elems)) (map :rel rel-slots)))
              :let  [el   (get rel-elems rk)
                     incl (:relation-incl el)
                     dr   (:derived-rule el)]]
          (cond-> {:entity/id (str "relation:" (name rk)) :structure/of ::Relation :entity/name (name rk)}
            (:doc el) (assoc :entity/doc (:doc el))
            incl      (assoc :val/incl (pr-str (:incl incl)) :val/expr (pr-str (:expr incl)))
            ;; a derived element is definitionally exact — :eq to its own rule
            dr        (assoc :val/incl ":eq" :val/rule (pr-str dr) :val/form dr)))
        morphisms (keep reflect-morphism sds)
        nodes  (concat (mapcat :nodes bits) (map :node vocabs) any relation-nodes
                       (mapcat :nodes morphisms))
        rels   (concat (mapcat :rels bits) (mapcat :rels vocabs) (mapcat :rels morphisms))
        ;; a slot referencing a tag NOBODY registered is a dangling grammar ref — fail with the tag,
        ;; not a cryptic missing-entity error.
        known  (into #{} (map :entity/id) nodes)]
    (doseq [r rels
            :let [[_ tid] (:rel/to r)]
            :when (and (str/starts-with? tid ":") (not (contains? known tid)))]
      (throw (ex-info (str "grammar reflection: " tid " is referenced by a slot but no such "
                           "structure is registered — dangling grammar reference "
                           "(check the defining ns is required)")
                      {:rel r})))
    {:nodes (vec nodes) :rels (vec rels)}))
