(ns canvas.vocab.code.effect
  "Code vocab — `Effect`: a named side effect an Operation performs, plus the transitive
   effect-reachability readings (`reaches-effect` / `throw-spread`) over the reified call graph.
   (The EffectCorrespondence law + the effect-drift readers live here too — added with the
   correspondence layer; the Clojure effect-grounding extraction is added with the extractor.)"
  (:require [clojure.set :as set]
            [datascript.core :as d]
            [fukan.canvas.core.structure :refer [defstructure]]))

(defn ^:export read-effect
  "Expand an effect literal — a keyword like `:io` — into Effect clauses, so
   effects author as `:performs [:io :require]`."
  [kw]
  [(list 'name (name kw))])

(defstructure ^:value Effect
  "A named side effect an Operation performs (e.g. :io, :require, :stderr, :throws).
   Value-identified — `:io` is one node shared across every Operation that performs it."
  {:name :string}
  (reader read-effect))

;; ── transitive effect reachability (the effect-language correspondence instrument) ──

(def reaches-effect-rules
  "Transitive effect reachability over the reified code graph: an op REACHES effect E if it directly
   performs E, or it calls some op that reaches E. Purely self-recursive (`reaches-effect` calls only
   itself + datoms — passes `check-law-recursion!`), so it is safe as a one-shot query; it saturates
   to a fixpoint over the cyclic call graph (no divergence). Used by `reached-effects`/`throw-spread`.
   NB: the `EffectCorrespondence` law INLINES an identical copy in its `:rules` (a law's `:rules` is
   macro-time literal data — it cannot reference this var); keep the two copies in sync (the same
   convention as `module/module-depends-rules` and the `lib.arch` DAG law)."
  '[[(reaches-effect ?op ?en) [?pr :rel/from ?op] [?pr :rel/kind :performs] [?pr :rel/to ?e] [?e :val/name ?en]]
    [(reaches-effect ?op ?en) [?cr :rel/from ?op] [?cr :rel/kind :calls] [?cr :rel/to ?mid] (reaches-effect ?mid ?en)]])

(defn reached-effects
  "The transitive effect profile of the op at `op-eid`: the set of effect-name strings it reaches over
   `:calls` ∪ `:performs` (direct effects included). A pure read; the depth-of-the-call-graph truth of
   what this op touches. Empty ⇔ the op is effect-free transitively."
  [db op-eid]
  (set (d/q '[:find [?en ...] :in $ % ?op :where (reaches-effect ?op ?en)]
            db reaches-effect-rules op-eid)))

(defn direct-throwers
  "Extracted ops that DIRECTLY perform `:throws` (their own body throws) — the partiality leaves.
   Most are ① parse-edge input-validators (legitimate); some are ② internal-invariant assertions
   (validation past the parse line). A pure read."
  [db]
  (set (d/q '[:find [?on ...]
              :where [?o :structure/of :canvas.vocab.code.operation/Operation] [?o :val/extracted true] [?o :entity/name ?on]
                     [?pr :rel/from ?o] [?pr :rel/kind :performs] [?pr :rel/to ?e] [?e :val/name "throws"]]
            db)))

(defn throw-spread
  "How partiality spreads: `{:direct #{ops that throw themselves} :transitive-only #{ops that reach
   throws only via a call}}`. The transitive-only set is the propagation surface that ②-containment
   (making internal-invariant throwers total) would collapse. A pure read over the reified code graph."
  [db]
  (let [direct   (direct-throwers db)
        reachers (set (d/q '[:find [?on ...] :in $ %
                             :where [?o :structure/of :canvas.vocab.code.operation/Operation] [?o :val/extracted true] [?o :entity/name ?on]
                                    (reaches-effect ?o "throws")]
                           db reaches-effect-rules))]
    {:direct direct :transitive-only (set/difference reachers direct)}))
