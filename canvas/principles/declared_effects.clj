(ns canvas.principles.declared-effects
  "PRINCIPLE — declared, bounded effects (Out of the Tarpit; Wlaschin, DDD Made Functional).

   Accidental complexity concentrates where state and IO hide. Every effect an operation's
   code TRANSITIVELY reaches must be declared in its authored `:performs` — design and
   extraction speak one effect language, to call-graph depth.

   The enforced direction (under-declaration) rides Operation's
   `:performs {:covered-from [:calls* :performs]}` slot option
   (key `:corresponds/Operation.performs-covered`); this ns holds the judgment surface
   (`effect-drift`, `undeclared-effects` — the fast by-name reading, deliberately NOT the
   law's identity semantics). Over-declaration is a soft reading (the classifier is
   necessarily incomplete), surfaced by `effect-drift`."
  (:require [clojure.set :as set]
            [fukan.cozo.query :as cq]))

(defn effect-drift
  "The effect-language correspondence reading: per MODELLED operation, the disagreement between its
   authored `:performs` intent and its extracted twin's TRANSITIVE effect profile (the truth, to the
   depth of the call graph: `(path ?op [:calls* :performs] ?effect)`). Twin via the shared
   `op-twin` rule. Returns `{op-name {:undeclared #{…} :phantom #{…}}}` for every op with a disagreement:
     :undeclared = reached ∖ declared — code reaches an effect the design never declared (HARD: the
                   enforced law direction).
     :phantom    = declared ∖ reached — the design declares an effect the code does not reach (SOFT:
                   a taxonomy gap, OR stale intent like a leftover `:throws`).
   A QUERY, not a law — the soft (phantom) half is advisory; the hard half is enforced by the
   generated `:corresponds/Operation.performs-covered` law (surfaced by `undeclared-effects`)."
  [db]
  ;; Bind the twin (?e) through the SAME `op-twin` rule the law uses, so the reading agrees with the
  ;; law by construction — a module-BLIND `[?e :entity/name ?on]` twin lookup would grab a same-named op
  ;; in the wrong module on a name collision, fabricating a drift the precise law never sees.
  (let [pairs    (cq/q '[:find ?on ?o ?e :in $
                        :where (op-twin ?o ?e) [?o :entity/name ?on]]
                       db)
        declared (fn [oeid] (set (cq/q '[:find [?en ...] :in $ ?o
                                         :where [?pr :rel/from ?o] [?pr :rel/kind :performs]
                                                [?pr :rel/to ?e] [?e :val/name ?en]]
                                       db oeid)))
        reached  (fn [teid] (set (cq/q '[:find [?en ...] :in $ ?op
                                         :where (path ?op [:calls* :performs] ?e)
                                                [?e :val/name ?en]]
                                       db teid)))]
    (reduce (fn [acc [on oeid teid]]
              (let [dec        (declared oeid)
                    rea        (reached teid)
                    undeclared (set/difference rea dec)
                    phantom    (set/difference dec rea)]
                (if (or (seq undeclared) (seq phantom))
                  (assoc acc on {:undeclared undeclared :phantom phantom})
                  acc)))
            {} pairs)))

(defn undeclared-effects
  "The EFFECT-CORRESPONDENCE offenders — modelled ops whose extracted twin TRANSITIVELY reaches an
   effect the op does not declare in its `:performs`, as a set of op names (the under-declaration
   direction). Empty ⇔ design declares every effect the code reaches, to the depth of the call graph.
   The enforced invariant is the `:corresponds/Operation.performs-covered` generated law; this reader
   is the FAST surface (derived from `effect-drift`, not a full `check`). Law and reader agree by
   construction (both follow the same op-twin pairing and composed effect reach)."
  [db]
  (set (for [[on m] (effect-drift db) :when (seq (:undeclared m))] on)))
