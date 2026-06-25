(ns canvas.vocab.code.effect
  "Code vocab — `Effect`: a named side effect an Operation performs, the transitive
   effect-reachability readings (`reaches-effect` / `throw-spread`), AND the effect dimension of
   the model↔code correspondence (the EffectCorrespondence law + the effect-drift readers). (The
   op pairing `op-twin` lives in `module`, referenced here via datalog injection; the Clojure
   effect-grounding extraction is added with the extractor.)"
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

;; ── transitive effect reachability (the effect-language correspondence instrument) ──

(def reaches-effect-rules
  "Transitive effect reachability over the reified code graph: an op REACHES effect E if it directly
   performs E, or it calls some op that reaches E. Purely self-recursive (`reaches-effect` calls only
   itself + datoms — passes `check-law-recursion!`), so it is safe as a one-shot query; it saturates
   to a fixpoint over the cyclic call graph (no divergence). Used by `reached-effects`/`throw-spread`.
   NB: the `EffectCorrespondence` law INLINES an identical copy in its `:rules` (a law's `:rules` is
   macro-time literal data — it cannot reference this var); keep the two copies in sync (the same
   convention as `module/module-depends-rules` and the `subsystem` DAG law)."
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

;; ── model↔code correspondence (effect dimension) ─────────────────────────────

(defstructure EffectCorrespondence
  "Law-holder for the model↔code EFFECT correspondence — the effect dimension of the self-correspondence
   (sibling of `Realization`/`Fidelity`/`Encapsulation`/`Totality`). Every effect a modelled op's
   extracted twin TRANSITIVELY reaches (over `:calls` ∪ `:performs`) must appear in the op's authored
   `:performs`: design and extraction speak one effect language, to the depth of the call graph. Twin
   via the injected `op-twin` rule.

   Direction: UNDER-declaration only (`reached ∖ declared`). Over-declaration is NOT a violation — the
   classifier is necessarily incomplete (taxonomy + dynamic-dispatch gaps), so a phantom is a soft
   `effect-phantom` reading, never enforced. `:scope :global`; naturally vacuous on a model-only build.
   The `reaches-effect` rule is purely self-recursive (passes `check-law-recursion!`, cheap — ~0.2s).
   The `:rules` inline a copy of `reaches-effect-rules` (a law's `:rules` is macro-time literal data —
   it cannot reference that var); keep the two in sync."
  (law "every effect a modelled op's twin transitively reaches is declared in the op's :performs"
    :scope :global
    :offenders '[?o]
    :rules '[[(reaches-effect ?op ?en) [?pr :rel/from ?op] [?pr :rel/kind :performs] [?pr :rel/to ?e] [?e :val/name ?en]]
             [(reaches-effect ?op ?en) [?cr :rel/from ?op] [?cr :rel/kind :calls] [?cr :rel/to ?mid] (reaches-effect ?mid ?en)]]
    :where '[[?o :structure/of :canvas.vocab.code.operation/Operation] (not [?o :val/extracted true])
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
                    rea        (reached-effects db teid)
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
