(ns canvas.architecture.cozo.reading
  "Self-spec: `fukan.cozo.reading` — read-side queries ported onto the Cozo mirror
   (the datascript→Cozo migration's parity targets). Each is the Cozo twin of a
   datascript reader; the oracle asserts the two agree. TRANSITIONAL framing —
   becomes the read surface once datascript is gone (P5)."
  (:require [canvas.vocab.code.operation :refer [Operation]]
            [canvas.vocab.code.module :refer [Module]]
            [canvas.architecture.cozo.db :as db]))

(Module cozo-reading
  "Read-side queries over the Cozo mirror — the cozo-side twins the oracle checks
   against the datascript readers."
  (Operation module-dependencies
    "The complete module→module dependency graph (calls ∪ data-adoption) as a set of [caller callee] name pairs, computed in CozoScript over the mirror."
    {:signature [:=> [:catn [:db db/CozoDb]] :any]
     :delegates [db/q]})
  (Operation latent-boundaries
    "Code modules whose public surface has split into ≥2 consumer-disjoint clienteles (Parnas/ISP boundary discovery), as {module [{:ops :clientele}…]}. Composes the surface building blocks through ConnectedComponents + a cohesion/proper gate."
    {:signature [:=> [:catn [:db db/CozoDb]] :any]
     :delegates [db/q]})
  (Operation throw-spread
    "How partiality spreads: {:direct #{ops that throw directly} :transitive-only #{ops that reach :throws only via a call}}. Composes performs and reaches_effect over the mirror."
    {:signature [:=> [:catn [:db db/CozoDb]] :any]
     :delegates [db/q]})
  (Operation effect-drift
    "Per modelled op, the disagreement between authored :performs and the extracted twin's transitive effect profile: {op-name {:undeclared #{…} :phantom #{…}}}. Composes op_twin, performs and reaches_effect over the mirror."
    {:signature [:=> [:catn [:db db/CozoDb]] :any]
     :delegates [db/q]}))
