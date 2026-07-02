(ns canvas.principles.declared-effects
  "PRINCIPLE — declared, bounded effects (Out of the Tarpit; Wlaschin, DDD Made Functional).

   Accidental complexity concentrates where state and IO hide. Every effect an operation's
   code TRANSITIVELY reaches must be declared in its authored `:performs` — design and
   extraction speak one effect language, to call-graph depth. Under-declaration is a
   violation (`EffectCorrespondence`); over-declaration is a soft reading (the classifier is
   necessarily incomplete), surfaced by `effect-drift`/`undeclared-effects`."
  (:require [clojure.set :as set]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.cozo.query :as cq]
            [canvas.vocab.code.effect :as effect]))

(defstructure EffectCorrespondence
  "Law-holder for the model↔code EFFECT correspondence — the effect dimension of the self-correspondence
   (sibling of `Realization`/`Fidelity`/`Encapsulation`/`Totality`). Every effect a modelled op's
   extracted twin TRANSITIVELY reaches (over `:calls` ∪ `:performs`) must appear in the op's authored
   `:performs`: design and extraction speak one effect language, to the depth of the call graph. Twin
   via the injected `op-twin` rule.

   Direction: UNDER-declaration only (`reached ∖ declared`). Over-declaration is NOT a violation — the
   classifier is necessarily incomplete (taxonomy + dynamic-dispatch gaps), so a phantom is a soft
   `effect-phantom` reading, never enforced. `:scope :global`; naturally vacuous on a model-only build.
   The `reaches-effect` rule is purely self-recursive (cheap — ~0.2s).
   The `:rules` inline a copy of `reaches-effect-rules` (a law's `:rules` is macro-time literal data —
   it cannot reference that var); keep the two in sync."
  (law "every effect a modelled op's twin transitively reaches is declared in the op's :performs"
    :scope :global
    :offenders '[?o]
    :rules '[[(reaches-effect ?op ?en) [?pr :rel/from ?op] [?pr :rel/kind :performs] [?pr :rel/to ?e] [?e :val/name ?en]]
             [(reaches-effect ?op ?en) [?cr :rel/from ?op] [?cr :rel/kind :calls] [?cr :rel/to ?mid] (reaches-effect ?mid ?en)]]
    :where '[(authored ?o)
             (op-twin ?o ?e)
             (reaches-effect ?e ?en)
             (not-join [?o ?en]
               [?dpr :rel/from ?o] [?dpr :rel/kind :performs] [?dpr :rel/to ?deff] [?deff :val/name ?en])]))

(defn effect-drift
  "The effect-language correspondence reading: per MODELLED operation, the disagreement between its
   authored `:performs` intent and its extracted twin's TRANSITIVE effect profile (the truth, to the
   depth of the call graph; `reached-effects`). Twin via the shared `op-twin` rule. Returns
   `{op-name {:undeclared #{…} :phantom #{…}}}` for every op with a disagreement:
     :undeclared = reached ∖ declared — code reaches an effect the design never declared (HARD: the
                   enforced law direction).
     :phantom    = declared ∖ reached — the design declares an effect the code does not reach (SOFT:
                   a taxonomy gap, OR stale intent like a leftover `:throws`).
   A QUERY, not a law — the soft (phantom) half is advisory; the hard half is enforced by
   `EffectCorrespondence` (surfaced by `undeclared-effects`)."
  [db]
  ;; Bind the twin (?e) through the SAME `op-twin` rule the law uses, so the reading agrees with the
  ;; law by construction — a module-BLIND `[?e :entity/name ?on]` twin lookup would grab a same-named op
  ;; in the wrong module on a name collision, fabricating a drift the precise law never sees.
  (let [pairs    (cq/q '[:find ?on ?o ?e :in $ %
                        :where (op-twin ?o ?e) [?o :entity/name ?on]]
                       db (s/vocab-rules))
        declared (fn [oeid] (set (cq/q '[:find [?en ...] :in $ ?o :where [?pr :rel/from ?o] [?pr :rel/kind :performs] [?pr :rel/to ?e] [?e :val/name ?en]] db oeid)))]
    (reduce (fn [acc [on oeid teid]]
              (let [dec        (declared oeid)
                    rea        (effect/reached-effects db teid)
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
   The enforced invariant is the `EffectCorrespondence` law; this reader is the FAST surface (derived
   from `effect-drift`, not a full `check`). Law and reader agree by construction."
  [db]
  (set (for [[on m] (effect-drift db) :when (seq (:undeclared m))] on)))
