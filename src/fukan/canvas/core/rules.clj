(ns fukan.canvas.core.rules
  "Vocab-derived datalog rules — the model made queryable in its own vocabulary.

   `derive-rules` is PURE: given the registered structure defs (+ a `scalar?`
   predicate that tells relation slots from value slots), it returns a datascript
   rules vector so queries refer to domain abstractions (`(Operation ?s)`, `(calls ?a ?b)`,
   `(in-module ?s \"…\")`) instead of substrate datoms. It takes no dependency on the
   kernel — it receives the registry data — so the kernel can consume the rules
   (in `check`) without a `structure ↔ rules` cycle.")

(def substrate-rules
  "Fixed rules for substrate relations that are not structure slots.
   `in-module` is generic: 'e is in module named mname' means some node m named mname
   contains e — via `:child` (generic membership), or, for a `Subsystem`, via `:exposes`
   (a public Operation) or `:owns` (an owned Kind). No :Module tag / :module/child needed."
  '[[(in-module ?e ?mname) [?r :rel/kind :child]   [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]
    [(in-module ?e ?mname) [?r :rel/kind :exposes] [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]
    [(in-module ?e ?mname) [?r :rel/kind :owns]    [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]
    [(named ?e ?n) [?e :entity/name ?n]]])

(defn- rule-sym [kw] (symbol (name kw)))

(defn derive-rules
  "Datascript rules derived from `structures` (a seq of structure defs):
     kind  K        → (K ?e)     ⇐ [?e :structure/of K]      (concrete structures only)
     incl  C⊇F      → (F ?e)     ⇐ (C ?e)                    (one per (includes F))
     real  R≔where  → (R ?e)     ⇐ <where…>                  (one per (realized-as …))
     copr  V=k₁|k₂  → (V ?a ?b)  ⇐ (kᵢ ?a ?b)                (one per :relation-coproduct member)
     rel   slot R   → (R ?a ?b)  ⇐ [?r :rel/from ?a] …
   plus the fixed substrate rules. `scalar?` splits relation slots from value slots.
   Realized concepts and relation-coproducts carry no instances, so they get no kind-rule."
  [structures scalar?]
  (let [concrete   (remove #(or (:realized-as %) (:relation-coproduct %)) structures)
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
        rel-kinds  (->> (mapcat :slots structures)
                        (remove scalar?)
                        (map :rel)
                        distinct)
        rel-rules  (for [r rel-kinds]
                     [(list (rule-sym r) '?a '?b)
                      ['?r :rel/from '?a] ['?r :rel/kind r] ['?r :rel/to '?b]])]
    (vec (concat kind-rules incl-rules real-rules copr-rules rel-rules substrate-rules))))
