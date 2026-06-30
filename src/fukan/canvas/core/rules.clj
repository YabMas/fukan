(ns fukan.canvas.core.rules
  "Vocab-derived datalog rules — the model made queryable in its own vocabulary.

   `derive-rules` is PURE: given the registered structure defs (+ a `scalar?`
   predicate that tells relation slots from value slots), it returns a datalog
   rules vector so queries refer to domain abstractions (`(Operation ?s)`, `(calls ?a ?b)`,
   `(in-module ?s \"…\")`) instead of substrate datoms. It takes no dependency on the
   kernel — it receives the registry data — so the kernel can consume the rules
   (in `check`) without a `structure ↔ rules` cycle.")

(def substrate-rules
  "Fixed rules for substrate relations — vocab-agnostic: only `named` (over the substrate
   `:entity/name` attribute). `in-module` is NOT here — it is derived from the vocab-declared
   `member` relation in `derive-rules`, so the kernel names no code-vocab relation kind."
  '[[(named ?e ?n) [?e :entity/name ?n]]])

(defn- rule-sym [kw] (symbol (name kw)))

(def ^:private character-implies
  "Relation-character ⟹ the characters it confers (closed transitively by `close-chars`). Generic —
   extend by adding entries. Containment entails transitivity (rollup up the containment ladder)."
  {:contains #{:transitive}})

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
     cont  slot R:cont → (contains c m) ⇐ (R c m)   (union of :contains slots)
     trans  R:trans    → (R+ a b) ⇐ (R a b) ∪ (R a m)(R+ m b)   (per :transitive name; :contains ⟹ contains+)
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
        slots*         (remove scalar? (mapcat :slots structures))
        contains-kinds (->> slots* (filter :contains) (map :rel) distinct)
        trans-kinds    (->> slots* (filter :transitive) (map :rel) distinct)
        ;; the transitive-closure generator — applies to a relation NAME (a slot kind OR a generated union)
        closure        (fn [rname] (let [r+ (symbol (str (name rname) "+"))]
                                     [[(list r+ '?a '?b) (list rname '?a '?b)]
                                      [(list r+ '?a '?b) (list rname '?a '?mid) (list r+ '?mid '?b)]]))
        ;; the :contains union — one same-head disjunct per marked relation kind
        contains-rules (for [k contains-kinds] [(list 'contains '?c '?m) (list (rule-sym k) '?c '?m)])
        ;; relation names that carry :transitive — directly, or by implication (contains ⟹ transitive)
        trans-names    (cond-> (set (map rule-sym trans-kinds))
                         (and (seq contains-kinds) (contains? (close-chars #{:contains}) :transitive)) (conj 'contains))
        closure-rules  (mapcat closure trans-names)
        ;; in-module (name-keyed) is DERIVED from `contains` — the kernel no longer hardcodes child/exposes/owns
        in-module-rules (when (seq contains-kinds)
                          [[(list 'in-module '?e '?mname) (list 'contains '?m '?e) ['?m :entity/name '?mname]]])]
    (vec (concat kind-rules incl-rules real-rules copr-rules drv-rules rel-rules contains-rules closure-rules in-module-rules substrate-rules))))
