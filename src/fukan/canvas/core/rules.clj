(ns fukan.canvas.core.rules
  "Vocab-derived datalog rules — the model made queryable in its own vocabulary.

   `derive-rules` is PURE: given the registered structure defs (+ a `scalar?`
   predicate that tells relation slots from value slots), it returns a datascript
   rules vector so queries refer to domain abstractions (`(Stage ?s)`, `(calls ?a ?b)`,
   `(in-module ?s \"…\")`) instead of substrate datoms. It takes no dependency on the
   kernel — it receives the registry data — so the kernel can consume the rules
   (in `check`) without a `structure ↔ rules` cycle.")

(def substrate-rules
  "Fixed rules for substrate relations that are not structure slots."
  '[[(in-module ?e ?mname) [?m :structure/of :Module] [?m :entity/name ?mname] [?m :module/child ?e]]
    [(named ?e ?n) [?e :entity/name ?n]]])

(defn- rule-sym [kw] (symbol (name kw)))

(defn derive-rules
  "Datascript rules derived from `structures` (a seq of structure defs):
     kind  K      → (K ?e)     ⇐ [?e :structure/of K]
     rel   slot R → (R ?a ?b)  ⇐ [?r :rel/from ?a] [?r :rel/kind R] [?r :rel/to ?b]
   (relation rules deduped by rel across structures — the body is structure-agnostic),
   plus the fixed substrate rules. `scalar?` splits relation slots from value slots;
   value-slot rules are deferred."
  [structures scalar?]
  (let [kind-rules (for [{:keys [tag]} structures]
                     [(list (rule-sym tag) '?e) ['?e :structure/of tag]])
        rel-kinds  (->> (mapcat :slots structures)
                        (remove scalar?)
                        (map :rel)
                        distinct)
        rel-rules  (for [r rel-kinds]
                     [(list (rule-sym r) '?a '?b)
                      ['?r :rel/from '?a] ['?r :rel/kind r] ['?r :rel/to '?b]])]
    (vec (concat kind-rules rel-rules substrate-rules))))
