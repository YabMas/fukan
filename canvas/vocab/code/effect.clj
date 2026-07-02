(ns canvas.vocab.code.effect
  "Code vocab — `Effect`: a named side effect an Operation performs, the transitive
   effect-reachability readings (`reaches-effect` / `throw-spread`), and the effect extraction.
   The EffectCorrespondence law + its effect-drift readers live in `canvas.principles.declared-effects`
   (the adopted-principle home); this file keeps the Effect element itself — the value structure,
   the `effectful` property, extraction, and the analysis helpers. (The op pairing `op-twin` lives
   in `module`, referenced here via datalog injection.)"
  (:require [clojure.set :as set]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.canvas.core.substrate :as sub]
            [fukan.cozo.query :as cq]))

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

(s/defrelation :effectful
  "an Operation that DIRECTLY performs a consequential effect (io/state/require) — NOT :throws, which
   is partiality (read by (totality)), not a consequential world-effect. The leaf PROPERTY that the
   composition operator transports along a transitive relation, e.g. (via :delegates Operation effectful)."
  '[?o]
  '[(Operation ?o) (performs ?o ?e) [?e :val/name ?en] [(not= ?en "throws")]])

;; ── transitive effect reachability (the effect-language correspondence instrument) ──

(def reaches-effect-rules
  "Transitive effect reachability over the reified code graph: an op REACHES effect E if it directly
   performs E, or it calls some op that reaches E. Purely self-recursive (`reaches-effect` calls only
   itself + datoms), so it is safe as a one-shot query; it saturates
   to a fixpoint over the cyclic call graph (no divergence). Used by `reached-effects`/`throw-spread`.
   NB: the `EffectCorrespondence` law (in `canvas.principles.declared-effects`) INLINES an identical
   copy in its `:rules` (a law's `:rules` is macro-time literal data — it cannot reference this var);
   keep the two copies in sync."
  '[[(reaches-effect ?op ?en) [?pr :rel/from ?op] [?pr :rel/kind :performs] [?pr :rel/to ?e] [?e :val/name ?en]]
    [(reaches-effect ?op ?en) [?cr :rel/from ?op] [?cr :rel/kind :calls] [?cr :rel/to ?mid] (reaches-effect ?mid ?en)]])

(defn reached-effects
  "The transitive effect profile of the op at `op-eid`: the set of effect-name strings it reaches over
   `:calls` ∪ `:performs` (direct effects included). A pure read; the depth-of-the-call-graph truth of
   what this op touches. Empty ⇔ the op is effect-free transitively."
  [db op-eid]
  (set (cq/q '[:find [?en ...] :in $ % ?op :where (reaches-effect ?op ?en)]
            db reaches-effect-rules op-eid)))

(defn direct-throwers
  "Extracted ops that DIRECTLY perform `:throws` (their own body throws) — the partiality leaves.
   Most are ① parse-edge input-validators (legitimate); some are ② internal-invariant assertions
   (validation past the parse line). A pure read."
  [db]
  (set (cq/q '[:find [?on ...]
              :where [?o :structure/of :canvas.vocab.code.operation/Operation] [?o :val/extracted true] [?o :entity/name ?on]
                     [?pr :rel/from ?o] [?pr :rel/kind :performs] [?pr :rel/to ?e] [?e :val/name "throws"]]
            db)))

(defn throw-spread
  "How partiality spreads: `{:direct #{ops that throw themselves} :transitive-only #{ops that reach
   throws only via a call}}`. The transitive-only set is the propagation surface that ②-containment
   (making internal-invariant throwers total) would collapse. A pure read over the reified code graph."
  [db]
  (let [direct   (direct-throwers db)
        reachers (set (cq/q '[:find [?on ...] :in $ %
                             :where [?o :structure/of :canvas.vocab.code.operation/Operation] [?o :val/extracted true] [?o :entity/name ?on]
                                    (reaches-effect ?o "throws")]
                           db reaches-effect-rules))]
    {:direct direct :transitive-only (set/difference reachers direct)}))

;; ── Clojure effect-grounding (this element's extraction facts) ────────────────
;; The FACTS layer for effects: classify a callee, attribute its effect to the calling op (direct
;; effects only; transitive reach is the reading's job). CONSEQUENTIAL effects (:io/:state/:require)
;; are the `(purity)` surface; logging/monitoring is deliberately NOT an effect (observational, not a
;; hazard). `throw` is classified as PARTIALITY (:throws) — kept OUT of the consequential surface, read
;; by the `(totality)` trust-line worklist.

(def ^:private effect-by-callee
  "Fully-qualified callee var → the effect it performs — CONSEQUENTIAL (:io/:state/:require) or
   PARTIALITY (:throws, kept out of the consequential `(purity)` surface; read by `(totality)`).
   Logging/monitoring (println/print/prn/pr/printf/flush, clojure.tools.logging, tap>) is
   deliberately ABSENT — observational, not a hazard, per the purity carve-out."
  (merge
   (zipmap '[clojure.core/slurp clojure.core/spit clojure.core/line-seq clojure.core/file-seq
             clj-kondo.core/run!]                  ; the analyzer's file I/O (reads source, writes its cache)
           (repeat :io))
   (zipmap '[clojure.core/swap! clojure.core/reset! clojure.core/swap-vals! clojure.core/reset-vals!
             clojure.core/alter clojure.core/alter-var-root clojure.core/ref-set clojure.core/vreset!
             clojure.core/commute clojure.core/send clojure.core/send-off]
           (repeat :state))
   (zipmap '[clojure.core/require clojure.core/use clojure.core/load clojure.core/load-file
             clojure.core/load-string clojure.core/requiring-resolve clojure.core/resolve
             clojure.core/ns-resolve clojure.core/find-ns clojure.core/the-ns]
           (repeat :require))
   ;; partiality — `throw` is a special form, but clj-kondo resolves it as clojure.core/throw.
   ;; An op that throws is partial; classified so its partiality is queryable by the `(totality)`
   ;; trust-line worklist. NOT a consequential world-effect → excluded from the `(purity)` surface.
   (zipmap '[clojure.core/throw] (repeat :throws))))

(def ^:private effect-by-ns
  "Callee NAMESPACE → effect, for whole namespaces that are effectful regardless of the var."
  {"clojure.java.io"    :io
   "clojure.java.shell" :io})

(defn- callee-effect
  "The effect a callee — namespace symbol `to`, name symbol `nm` — performs, or nil.
   A specific-var classification wins over the namespace-wide one."
  [to nm]
  (or (effect-by-callee (symbol (str to) (str nm)))
      (effect-by-ns (str to))))

(defn effect-iv
  "A value-identified Effect InstanceValue for effect keyword `kw` — content-identical to an
   authored `(Effect :kw)`, so extracted and authored effects collapse to one node."
  [kw]
  (sub/->InstanceValue ::Effect nil nil {:val/name (name kw)} [] true))

(defn op-effects
  "Map {[caller-ns-str caller-fn-str] #{effect-kw …}} from clj-kondo var-usages — every resolvable
   call to a classified-effectful callee attributes that effect to the CALLING op (direct effects
   only; transitive reach is the reading's job, and is deferred)."
  [var-usages]
  (reduce (fn [acc {:keys [from from-var to name]}]
            (if-let [eff (and from from-var to name (callee-effect to name))]
              (update acc [(str from) (str from-var)] (fnil conj #{}) eff)
              acc))
          {} var-usages))
