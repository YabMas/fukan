(ns fukan.target.correspondence
  "The model↔code CORRESPONDENCE concern — deliberately separate from BOTH the
   abstract modelling domain (canvas/, e.g. `Operation`) and the code-structure domain
   (`Operation`). A domain definition's laws should concern only that domain's own
   behaviour and constraints; HOW the model is realized in code is an orthogonal
   question. Keeping it here means each can be reasoned about — and evolved — in
   isolation: focus on the domain without implementation noise, and focus on the
   implementation/correspondence question without touching the domains.

   It holds fukan's self-correspondence — THE model↔code drift-check (distinct from
   the system overview's faculty→module map (derived from each Module's `:realizes` role), which
   checks nothing against extracted reality). fukan's projection convention is that an
   op-layer `Operation` named X in a canvas module is realized by a function named X in
   the CORRESPONDING code module — so the law matches on name AND module placement
   across the altitude gap between authored Operations (canvas/) and extracted Operations
   (src/), no `realizes` relation needed. Authored and extracted operations are both the
   one `:Operation` tag now (provenance `:extracted`, not tag, distinguishes them); the law
   references it only as data (a structure tag resolved at check-time over the merged graph), so this
   concern takes no code dependency on either domain."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datascript.core :as d]
            [fukan.canvas.core.rules :as rules]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.canvas.core.typing :as typing]))

(defn ^:export module-corresponds?
  "True when code namespace `km` realizes canvas module `cm`. Deterministic, separator-agnostic:
   split both on `[-.]` into segments; the canvas name's segments must be a SUFFIX of the code
   namespace's. So `infra-model` ← `fukan.infra.model`, `canvas-source` ←
   `fukan.canvas.projection.canvas-source`, `core-structure` ← `fukan.canvas.core.structure`.
   (Canvas module names are hyphenated and equal their vars; the code path is dotted — this rule
   bridges the two without the model authoring a second name string.)"
  [cm km]
  (let [segs #(str/split % #"[-.]")
        c    (segs cm)]
    (= c (take-last (count c) (segs km)))))

(defstructure Realization
  "A law-holder for the model↔code correspondence — it has no instances of its own;
   it exists to carry the cross-layer assertion in its own concern.

   `:scope :global` opts out of the default self-scoping (its offenders are Operations,
   not Realizations). The leading extracted-Operation clause is the real guard: the law
   is vacuous when no code is extracted — correspondence is only assertable when both
   layers share the graph — so registering it never disturbs a model-only `check`.

   AUTHORED and EXTRACTED operations are both `Operation`s now — provenance (`:extracted`),
   not tag, distinguishes them. The match is on name AND module: an authored Operation in
   canvas module C is realized only by an extracted Operation of the same name whose code
   module corresponds to C (module-corresponds?)."
  ;; Reads over the vocab-derived rules (check injects them) — domain altitude:
  ;; `(Operation …)`, `(named …)`, `(in-module …)`; `:val/extracted` splits the two sides.
  (law "every authored operation is realized by an extracted operation of the same name in the corresponding module"
    :scope :global
    :offenders '[?s]
    :where '[(Operation ?x) [?x :val/extracted true]                  ; guard: some code is extracted
             (Operation ?s) (not [?s :val/extracted true])            ; an authored operation
             (named ?s ?n) (in-module ?s ?cmn)
             (not-join [?n ?cmn]
               (Operation ?o) [?o :val/extracted true]
               (named ?o ?n) (in-module ?o ?kmn)
               [(fukan.target.correspondence/module-corresponds? ?cmn ?kmn)])]))

(defstructure CallRealization
  "Law-holder for the model↔code CALL realization — no instances of its own; the relation-level dual
   of the op-level `Realization`. The INTERPRETATION seam between INTENT (`:delegates`, authored) and
   FACT (`:calls`, extracted), at MODULE-DEPENDENCY altitude: every authored CROSS-MODULE delegation
   must be realized by SOME actual cross-module call between the corresponding modules
   (`module-corresponds?`). Module-level, not exact op-pair: real dependencies are often indirect
   (dispatch, internal leaves) — the author sketches the module dependency on an exposed op.
   `:scope :global` (offenders are authored delegation source ops). The leading `:calls` clause guards
   it vacuous on a model-only build; negation via an inline `not-join` with the corresponding-module
   names bound on entry (mirroring the op-level `Realization` law) — keeping `?cm1`/`?cm2` bound
   avoids a free-variable blow-up; the leading `:calls` clause guards vacuity on a model-only build.
   The `:rules` inline `in-module` (self-contained, the `lib.arch` convention)."
  (law "every intended cross-module delegation is realized by an actual call between the corresponding modules"
    :scope :global
    :offenders '[?o1]
    :rules '[[(in-module ?e ?mname) [?r :rel/kind :child]   [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]
             [(in-module ?e ?mname) [?r :rel/kind :exposes] [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]
             [(in-module ?e ?mname) [?r :rel/kind :owns]    [?r :rel/from ?m] [?r :rel/to ?e] [?m :entity/name ?mname]]]
    :where '[[?anycall :rel/kind :calls]
             [?dr :rel/kind :delegates] [?dr :rel/from ?o1] [?dr :rel/to ?o2]
             (not [?o1 :val/extracted true])
             (in-module ?o1 ?cm1) (in-module ?o2 ?cm2) [(not= ?cm1 ?cm2)]
             (not-join [?cm1 ?cm2]
               [?cr :rel/kind :calls] [?cr :rel/from ?e1] [?cr :rel/to ?e2]
               [?e1 :val/extracted true] [?e2 :val/extracted true]
               (in-module ?e1 ?km1) (in-module ?e2 ?km2)
               [(fukan.target.correspondence/module-corresponds? ?cm1 ?km1)]
               [(fukan.target.correspondence/module-corresponds? ?cm2 ?km2)])]))

(defn unrealized-delegates
  "The authored source Operations whose cross-module delegation is NOT realized by any actual call
   between the corresponding modules, as a set of op names. Empty ⇔ every intended module dependency
   is backed by real code. Reads the single source of truth (the registered CallRealization law)."
  [db]
  (let [desc (-> (s/structure-by-tag ::CallRealization) :laws first :desc)]
    (->> (s/check db)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (d/entity db %)))
         set)))

(defn uncovered-calls
  "Fidelity worklist — the dual of `unrealized-delegates` (a QUERY, not a law, like
   `uncovered-operations`): actual cross-module module-calls (over `:calls`) with no corresponding
   intended cross-module delegation (over `:delegates`, bridged by `module-corresponds?`), as a set
   of [caller-module callee-module] code-module-name pairs. The couplings the design has not yet
   declared. Computed by set-difference in Clojure (not `not-join`) to sidestep the empty-relation
   gotcha. A signal, not a violation: you do not model every call."
  [db]
  (let [intent (d/q '[:find ?cm1 ?cm2 :in $ %
                      :where [?dr :rel/kind :delegates] [?dr :rel/from ?o1] [?dr :rel/to ?o2]
                             (not [?o1 :val/extracted true])
                             (in-module ?o1 ?cm1) (in-module ?o2 ?cm2) [(not= ?cm1 ?cm2)]]
                    db rules/substrate-rules)
        actual (d/q '[:find ?km1 ?km2 :in $ %
                      :where [?cr :rel/kind :calls] [?cr :rel/from ?e1] [?cr :rel/to ?e2]
                             [?e1 :val/extracted true] [?e2 :val/extracted true]
                             (in-module ?e1 ?km1) (in-module ?e2 ?km2) [(not= ?km1 ?km2)]]
                    db rules/substrate-rules)]
    (->> actual
         (remove (fn [[km1 km2]]
                   (some (fn [[cm1 cm2]]
                           (and (module-corresponds? cm1 km1) (module-corresponds? cm2 km2)))
                         intent)))
         set)))

(defn drifted-operations
  "The AUTHORED operations in `db` with no same-named extracted operation, as a set of
   names. Empty ⇔ the model is fully realized in code. The focusable surface of the
   correspondence concern; reads the single source of truth (the registered law)."
  [db]
  (let [desc (-> (s/structure-by-tag ::Realization) :laws first :desc)]
    (->> (s/check db)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (d/entity db %)))
         set)))

(defn operation-sig
  "Render the AUTHORED Operation at `op-eid` to a malli function-schema
   `[:=> [:cat <each :in schema>] <:out schema, or :nil if none>]`, each `:in`/`:out`
   Schema rendered via the type dialect (`typing/render-type`). The `:in` targets are
   ordered/positional — rendered in `:rel/order` order — so the adherence comparison
   checks argument order and arity."
  [db op-eid]
  (let [ins  (->> (d/q '[:find ?ord ?to :in $ ?from
                         :where [?r :rel/from ?from] [?r :rel/kind :in] [?r :rel/to ?to] [?r :rel/order ?ord]]
                       db op-eid)
                  (sort-by first)
                  (mapv (fn [[_ to]] (typing/render-type db to))))
        out  (ffirst (d/q '[:find ?to :in $ ?from
                            :where [?r :rel/from ?from] [?r :rel/kind :out] [?r :rel/to ?to]]
                          db op-eid))]
    [:=> (into [:cat] ins) (if out (typing/render-type db out) :nil)]))

(defn type-drifted-operations
  "AUTHORED operations whose modelled type disagrees with the realizing function's declared
   `:malli/schema` — a type-drift signal (only checked where the code carries an annotation).
   Mirrors `uncovered-operations`' authored↔extracted pairing (same name, corresponding
   module via `module-corresponds?`), additionally requiring the extracted twin carries a
   `:val/sig`; collects the authored Operation's name when its rendered type does NOT adhere
   to the twin's realized signature."
  [db]
  (->> (d/q '[:find ?s ?sn ?o
              :where [?s :structure/of :lib.code/Operation] (not [?s :val/extracted true]) [?s :entity/name ?sn]
                     [?cr :rel/kind :child] [?cr :rel/from ?cm] [?cr :rel/to ?s]
                     [?cm :entity/name ?cmn]
                     [?o :structure/of :lib.code/Operation] [?o :val/extracted true] [?o :entity/name ?sn]
                     [?o :val/sig _]
                     [?kr :rel/kind :child] [?kr :rel/from ?km] [?kr :rel/to ?o]
                     [?km :entity/name ?kmn]
                     [(fukan.target.correspondence/module-corresponds? ?cmn ?kmn)]]
            db)
       (filter (fn [[s _ o]]
                 (not (typing/type-adheres?
                        (operation-sig db s)
                        (edn/read-string (:val/sig (d/entity db o)))))))
       (map second) set))

(defn uncovered-operations
  "The DUAL of drifted-operations — EXTRACTED operations in `db` with no authored operation
   of the same name in a corresponding module, as a set of names: code not covered by the
   model. A query, not a law — unmodelled code is a coverage signal, not a violation (you
   don't model every function)."
  [db]
  (->> (d/q '[:find ?on
              :where [?o :structure/of :lib.code/Operation] [?o :val/extracted true] [?o :entity/name ?on]
                     [?kr :rel/kind :child] [?kr :rel/from ?km] [?kr :rel/to ?o]
                     [?km :entity/name ?kmn]
                     (not-join [?on ?kmn]
                       [?s :structure/of :lib.code/Operation] (not [?s :val/extracted true]) [?s :entity/name ?on]
                       [?cr :rel/kind :child] [?cr :rel/from ?cm] [?cr :rel/to ?s]
                       [?cm :entity/name ?cmn]
                       [(fukan.target.correspondence/module-corresponds? ?cmn ?kmn)])]
            db)
       (map first) set))
