(ns fukan.canvas.core.rules
  "Vocab-derived datalog rules — the model made queryable in its own vocabulary.

   `derive-rules` is PURE: given the registered structure defs (+ a `scalar?`
   predicate that tells relation slots from value slots), it returns a datalog
   rules vector so queries refer to domain abstractions (`(Operation ?s)`, `(calls ?a ?b)`,
   `(in-module ?s \"…\")`) instead of substrate datoms. It takes no dependency on the
   kernel — it receives the registry data — so the kernel can consume the rules
   (in `check`) without a `structure ↔ rules` cycle.")

(def substrate-rules
  "Fixed rules for substrate relations that are not structure slots.
   `in-module` is generic: 'e is in module named mname' means some node m named mname
   contains e — via `:child` (generic membership), or, for a `Module`, via `:exposes`
   (a public Operation) or `:owns` (an owned Kind). No :Grouping tag / :module/child needed."
  '[[(in-module ?e ?mname) [?r :rel/kind :child]   [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]
    [(in-module ?e ?mname) [?r :rel/kind :exposes] [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]
    [(in-module ?e ?mname) [?r :rel/kind :owns]    [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]
    [(named ?e ?n) [?e :entity/name ?n]]])

(defn- rule-sym [kw] (symbol (name kw)))

(def ^:private character-implies
  "Relation-character ⟹ the characters it confers (closed transitively by `close-chars`). Generic —
   extend by adding entries. Membership entails transitivity (rollup up the containment ladder)."
  {:member #{:transitive}})

(defn- close-chars
  "A relation's full character set: `chars` closed under `character-implies` to a fixpoint."
  [chars]
  (loop [cs (set chars)]
    (let [nxt (into cs (mapcat character-implies cs))]
      (if (= nxt cs) cs (recur nxt)))))

(defn ^{:malli/schema [:=> [:cat [:vector :StructureDef] :Pred] [:vector :Rule]]}
  derive-rules
  "Datascript rules derived from `structures` (a seq of structure defs):
     kind  K        → (K ?e)     ⇐ [?e :structure/of K]      (concrete structures only)
     incl  C⊇F      → (F ?e)     ⇐ (C ?e)                    (one per (includes F))
     real  R≔where  → (R ?e)     ⇐ <where…>                  (one per (realized-as …))
     copr  V=k₁|k₂  → (V ?a ?b)  ⇐ (kᵢ ?a ?b)                (one per :relation-coproduct member)
     drv   D≔head·w → (D head…)  ⇐ <where…>                  (one per (defrelation …))
     rel   slot R   → (R ?a ?b)  ⇐ [?r :rel/from ?a] …
     mem   slot R:mem  → (member c m) ⇐ (R c m)   (union of :member slots)
     trans  R:trans    → (R+ a b) ⇐ (R a b) ∪ (R a m)(R+ m b)   (per :transitive name; :member ⟹ member+)
   plus the fixed substrate rules. `scalar?` splits relation slots from value slots.
   Realized concepts, relation-coproducts, and derived relations carry no instances, so they
   get no kind-rule."
  [structures scalar?]
  (let [concrete   (remove #(or (:realized-as %) (:relation-coproduct %) (:derived-rule %)) structures)
        kind-rules (for [{:keys [tag]} concrete]
                     [(list (rule-sym tag) '?e) ['?e :structure/of tag]])
        incl-rules (for [{:keys [tag includes]} structures
                         f includes]
                     [(list (rule-sym f) '?e) (list (rule-sym tag) '?e)])
        real-rules (for [{:keys [tag realized-as]} structures :when realized-as]
                     (into [(list (rule-sym tag) '?e)] realized-as))
        copr-rules (for [{:keys [tag relation-coproduct]} structures
                         m relation-coproduct]
                     [(list (rule-sym tag) '?a '?b) (list (rule-sym m) '?a '?b)])
        drv-rules  (for [{:keys [tag derived-rule]} structures :when derived-rule]
                     (into [(apply list (rule-sym tag) (:head derived-rule))]
                           (:where derived-rule)))
        rel-kinds  (->> (mapcat :slots structures)
                        (remove scalar?)
                        (map :rel)
                        distinct)
        rel-rules  (for [r rel-kinds]
                     [(list (rule-sym r) '?a '?b)
                      ['?r :rel/from '?a] ['?r :rel/kind r] ['?r :rel/to '?b]])
        slots*       (remove scalar? (mapcat :slots structures))
        member-kinds (->> slots* (filter :member) (map :rel) distinct)
        trans-kinds  (->> slots* (filter :transitive) (map :rel) distinct)
        ;; the transitive-closure generator — applies to a relation NAME (a slot kind OR a generated union)
        closure      (fn [rname] (let [r+ (symbol (str (name rname) "+"))]
                                   [[(list r+ '?a '?b) (list rname '?a '?b)]
                                    [(list r+ '?a '?b) (list rname '?a '?mid) (list r+ '?mid '?b)]]))
        ;; the :member union — one same-head disjunct per marked relation kind
        member-rules (for [k member-kinds] [(list 'member '?c '?m) (list (rule-sym k) '?c '?m)])
        ;; relation names that carry :transitive — directly, or by implication (member ⟹ transitive)
        trans-names  (cond-> (set (map rule-sym trans-kinds))
                       (and (seq member-kinds) (contains? (close-chars #{:member}) :transitive)) (conj 'member))
        closure-rules (mapcat closure trans-names)]
    (vec (concat kind-rules incl-rules real-rules copr-rules drv-rules rel-rules member-rules closure-rules substrate-rules))))
