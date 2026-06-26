(ns canvas.vocab.code.operation
  "Code vocab — `Operation`: the unified computational unit, AUTHORED (a self-model's intent)
   or EXTRACTED from code (`:extracted true`), plus its model↔code correspondence: the
   Realization/Encapsulation laws + the drift/coverage/type-drift readers. (The op pairing
   `op-twin` itself lives in `module` — it is built on the Module name bridge — and is referenced
   here via datalog injection; the `defn→Operation`+`:calls` extraction is added with the extractor.)"
  (:require [clojure.edn :as edn]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.canvas.core.substrate :as sub]
            [fukan.canvas.core.typing :as typing]
            [fukan.cozo.query :as cq]
            [canvas.vocab.type :as ct :refer [Schema]]
            [canvas.vocab.code.effect :as effect :refer [Effect]]
            [canvas.vocab.grouping :refer [Connected]]
            [canvas.vocab.code.kind :refer [Kind]]))

(defn ^:export signature->slots
  "Operation's authoring syntax (the `(syntax …)` hook, map → map): a `:signature`
   pseudo-key is rewritten into the ordered+labelled `:in` vector and the `:out` entry.
   The value is a malli function schema `[:=> INPUT OUTPUT]`; INPUT is `[:catn [:name Type] …]`
   (named params → ordered + labelled `[name Type]` pairs) or `[:cat]` (nullary). A
   `[:cat Type …]` (positional, unnamed) is REJECTED — name your parameters. The Types are
   ordinary malli, expanded by the Schema reader (a bare symbol is a var-ref to a Kind)."
  [m]
  (if-not (contains? m :signature)
    m
    (let [form (:signature m)]
      (when-not (and (vector? form) (= :=> (first form)) (= 3 (count form)))
        (throw (ex-info (str "signature must be a malli function schema [:=> INPUT OUTPUT]: " (pr-str form)) {:form form})))
      (let [[_ input output] form]
        ;; keep this guard ahead of catn->pairs: it reports against the whole [:=> …] form
        (when-not (vector? input)
          (throw (ex-info (str "signature input must be [:catn …] or [:cat]: " (pr-str input)) {:form form})))
        (let [in (ct/catn->pairs input)]
          (cond-> (-> m (dissoc :signature) (assoc :out output))
            (seq in) (assoc :in in)))))))

(defstructure Operation
  "A named unit of computation — the UNIFIED computational unit. An `Operation` is either
   AUTHORED (a self-model's intent: input/output Shapes, Effects, intended calls) or
   EXTRACTED from code (`:extracted true`, stamped by the plug-point — name + privacy, and
   actual calls). A modelled Operation corresponds 1-on-1 (by name + corresponding Module)
   to its extracted twin; the two stay distinct nodes so spec and actual remain checkable.

   Authored with a malli signature: `(Operation f \"doc\" {:signature [:=> [:catn [:name Type] …] Out] :delegates […]})`
   — the `(syntax signature->slots)` hook rewrites `:signature` to the `:in`/`:out` entries.

   A boundary sketch authors `:delegates` (the cross-module surfaces it relies on — designed
   dependencies) and `:guidance` (implementer-directed intent); it does NOT author `:calls` —
   internal wiring is extraction's job. `:calls` is therefore the EXTRACTED actual-call graph."
  (includes Connected)
  (syntax signature->slots)          ; {:signature [:=> [:catn …] Out]} authoring entry (vocab-owned)
  {:in        [:* Schema]            ; input shapes — positional, each labelled with its param name
   :out       [:? Schema]            ; output schema (authored ops declare one; extracted may not)
   :performs  [:* Effect]            ; side effects
   :delegates [:* Operation]         ; cross-boundary dependencies it relies on (authored, designed)
   :dispatches-to [:* Operation]     ; indirection: handler Operations this dispatch point routes to (authored intent — a design statement, not an extracted fact)
   :guidance  [:? :string]           ; implementer-directed design intent (algorithm/perf/library) — rendered by the projection
   :calls     [:* Operation]         ; the ACTUAL call graph (extraction's actuals; not authored)
   :private   [:? :boolean]          ; public/internal — the module's surface (from extraction)
   :export    [:? :boolean]          ; intentionally public for MECHANISM (macro emission / dynamic dispatch); settled, not a coverage gap (from ^:export)
   :test-support [:? :boolean]       ; intentionally public for TEST-SUPPORT (test isolation / setup, never called from production); settled (from ^:test-support)
   :extracted [:? :boolean]          ; provenance: true ⇒ from code; absent/false ⇒ authored
   ;; the code's REALIZED malli signature (a pr-str'd `[:=> …]` form), stamped by extraction
   ;; from `:malli/schema` metadata; authored Operations leave it empty and use :in/:out.
   :sig       [:? :string]})

;; ── model↔code correspondence (op altitude) ──────────────────────────────────

(defstructure Realization
  "A law-holder for the model↔code correspondence — it has no instances of its own; it exists to
   carry the cross-layer assertion. `:scope :global` (its offenders are Operations). The leading
   extracted-Operation clause is the real guard: the law is vacuous when no code is extracted. The
   twin's existence is the injected `op-twin` rule (registered in `module`): an authored op with no
   `(op-twin ?s ?e)` has no realizing code."
  (law "every authored operation is realized by an extracted operation of the same name in the corresponding module"
    :scope :global
    :offenders '[?s]
    :where '[(Operation ?x) [?x :val/extracted true]                  ; guard: some code is extracted
             (Operation ?s) (not [?s :val/extracted true])            ; an authored operation …
             (not-join [?s] (op-twin ?s ?e))]))                       ; … with no extracted twin

(defstructure Encapsulation
  "Law-holder for code-up ENCAPSULATION — the ENFORCED dual of the `uncovered-public-operations`
   query, at OPERATION altitude (the op-level peer of the relation-level `Fidelity`). Every PUBLIC
   extracted operation must be COVERED by the model (an `op-twin`) OR deliberately exempt:
     - `:val/private`      — an internal (encapsulation working as intended)
     - `:val/export`       — public for MECHANISM (macro-emitted substrate / a var reached only via
                             dynamic dispatch: a datalog predicate, a `(syntax …)`/reader hook)
     - `:val/test-support` — public only for TEST isolation/setup (never called from production)
   A public, non-exempt, unmodelled function is an UNDECLARED PUBLIC SURFACE: the model must name it
   (intent), or it must be hidden. `:scope :global`; vacuous on a model-only build (the leading
   `:val/extracted true` clause)."
  (law "every public extracted operation is covered by the model or deliberately exempt"
    :scope :global
    :offenders '[?o]
    :where '[[?o :structure/of :canvas.vocab.code.operation/Operation] [?o :val/extracted true]
             (not [?o :val/private true])
             (not [?o :val/export true])
             (not [?o :val/test-support true])
             (not-join [?o] (op-twin ?s ?o))]))                       ; … with no authored twin

(defstructure TrustBoundary
  "Designates a Kind as a parse-don't-validate TRUST BOUNDARY: an Operation that takes this Kind as
   input operates on already-trusted data, so it must be total. `:kind` points at the TRUSTED ARTIFACT
   itself (the parsed, trusted representation — e.g. fukan's StructureDb, the Model), not a boundary
   line; the name reads 'this Kind marks where trust holds'. The Totality law reads the designation."
  {:kind Kind})

(defstructure Totality
  "Law-holder for code-up TOTALITY (parse-don't-validate, at the trust line): a trusted-core reader —
   a modelled Operation whose :in references a declared TrustBoundary Kind — operates on already-trusted
   data, so it must be TOTAL. An offender is such a reader whose extracted twin (op-twin) performs
   :throws. `:scope :global`; vacuous when no TrustBoundary is declared. Generic: the trust boundary is
   a parameter (a project declares its own), not a hardcoded StructureDb."
  (law "every trusted-core reader (its :in is a declared TrustBoundary) is total — its code performs no :throws"
    :scope :global
    :offenders '[?o]
    :where '[[?tb :structure/of ::TrustBoundary] [?tbr :rel/from ?tb] [?tbr :rel/kind :kind] [?tbr :rel/to ?k]
             [?o :structure/of :canvas.vocab.code.operation/Operation] (not [?o :val/extracted true])
             [?ir :rel/from ?o] [?ir :rel/kind :in] [?ir :rel/to ?sch]
             [?sch :val/kind "ref"] [?nr :rel/from ?sch] [?nr :rel/kind :names] [?nr :rel/to ?k]
             (op-twin ?o ?e)
             [?pr :rel/from ?e] [?pr :rel/kind :performs] [?pr :rel/to ?eff] [?eff :val/name "throws"]]))

(defn drifted-operations
  "The AUTHORED operations in `db` with no same-named extracted operation, as a set of
   names. Empty ⇔ the model is fully realized in code. The focusable surface of the
   correspondence concern; reads the single source of truth (the registered Realization law)."
  [db]
  (let [desc (-> (s/structure-by-tag ::Realization) :laws first :desc)]
    (->> (s/check db)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (cq/entity db %)))
         set)))

(defn uncovered-operations
  "The DUAL of drifted-operations — EXTRACTED operations in `db` with no authored operation
   of the same name in a corresponding module, as a set of names: code not covered by the
   model. A query, not a law — unmodelled code is a coverage signal, not a violation (you
   don't model every function). Twin lookup is the injected `op-twin` rule."
  [db]
  (->> (cq/q '[:find ?on :in $ %
               :where [?o :structure/of :canvas.vocab.code.operation/Operation] [?o :val/extracted true] [?o :entity/name ?on]
                      (not-join [?o] (op-twin ?s ?o))]
             db (s/vocab-rules))
       (map first) set))

(defn uncovered-public-operations
  "The ENCAPSULATION worklist — the PUBLIC subset of `uncovered-operations`: extracted operations
   that are PUBLIC (not `:val/private true`) AND have no authored twin. The principle (the dual at
   op-granularity of `uncovered-calls`): a function the model does not name should be an internal
   detail — so a PUBLIC uncovered function is an UNDECLARED PUBLIC SURFACE, demanding a decision
   (model it as intent, or make it private). Reads the single source of truth (the registered
   `Encapsulation` law). `:val/private`/`:val/export`/`:val/test-support` are settled exclusions."
  [db]
  (let [desc (-> (s/structure-by-tag ::Encapsulation) :laws first :desc)]
    (->> (s/check db)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (cq/entity db %)))
         set)))

(defn operation-sig
  "Render the AUTHORED Operation at `op-eid` to a malli function-schema
   `[:=> [:cat <each :in schema>] <:out schema, or :nil if none>]`, each `:in`/`:out`
   Schema rendered via the type dialect (`typing/render-type`). The `:in` targets are
   ordered/positional — rendered in `:rel/order` order — so the adherence comparison
   checks argument order and arity."
  [db op-eid]
  (let [ins  (->> (cq/q '[:find ?ord ?to :in $ ?from
                          :where [?r :rel/from ?from] [?r :rel/kind :in] [?r :rel/to ?to] [?r :rel/order ?ord]]
                        db op-eid)
                  ;; ?ord arrives a native number (typed-q) — sort by true numeric order
                  (sort-by (fn [[ord _]] (long ord)))
                  (mapv (fn [[_ to]] (typing/render-type db to))))
        out  (ffirst (cq/q '[:find ?to :in $ ?from
                             :where [?r :rel/from ?from] [?r :rel/kind :out] [?r :rel/to ?to]]
                           db op-eid))]
    [:=> (into [:cat] ins) (if out (typing/render-type db out) :nil)]))

(defn type-drifted-operations
  "AUTHORED operations whose modelled type disagrees with the realizing function's declared
   `:malli/schema` — a type-drift signal (only checked where the code carries an annotation).
   Pairs each authored op with its extracted twin through the shared `op-twin` rule (same name,
   corresponding module via `in-module` — the SAME membership the laws use, so public ops attached
   via `:exposes` are seen, not just `:child`-attached ones), additionally requiring the twin
   carries a `:val/sig`; collects the authored Operation's name when its rendered type does NOT
   adhere to the twin's realized signature."
  [db]
  (->> (cq/q '[:find ?s ?sn ?o :in $ %
               :where (op-twin ?s ?o) [?s :entity/name ?sn] [?o :val/sig ?sig]]
             db (s/vocab-rules))
       (filter (fn [[s _ o]]
                 (not (typing/type-adheres?
                        (operation-sig db s)
                        (edn/read-string (:val/sig (cq/entity db o)))))))
       (map second) set))

(defn totality-violations
  "The ENFORCED TOTALITY offenders — trusted-core reader Operations (their :in references a declared
   TrustBoundary) whose realizing code is PARTIAL, as a set of op names. Empty ⇔ the modelled trusted
   core is total. Reads the single source of truth (the registered `Totality` law)."
  [db]
  (let [desc (-> (s/structure-by-tag ::Totality) :laws first :desc)]
    (->> (s/check db)
         (filter #(= desc (:law %)))
         (mapcat :offenders) (map first)
         (map #(:entity/name (cq/entity db %)))
         set)))

;; ── Clojure extraction (defn → Operation) ────────────────────────────────────

(def fn-defining
  "clj-kondo `:defined-by` values that denote a computation unit (an Operation). `defn`/`defn-`
   are functions; `defmulti` is a DISPATCH POINT — also an Operation (callers depend on it; its
   handler fan-out is authored intent, see `:dispatches-to`). `def`, `defmacro`, `defmethod`, …
   stay excluded — `defmethod` defines no var."
  #{'clojure.core/defn 'clojure.core/defn- 'clojure.core/defmulti})

(defn extract-operation
  "Build an extracted Operation InstanceValue from a clj-kondo var-definition `v` and the set of
   effect keywords `effs` directly attributed to it. Stamps `:val/extracted`, privacy, the `^:export`/
   `^:test-support` mechanism flags, the realized `:malli/schema` signature (`:val/sig`), and the
   direct effects as `:performs` (each a content-deduped Effect value, via `effect/effect-iv`)."
  [v effs]
  (sub/->InstanceValue ::Operation (str (:name v)) nil
                       (cond-> {:val/private (boolean (:private v))
                                :val/extracted true}
                         (:export (:meta v))       (assoc :val/export true)
                         (:test-support (:meta v)) (assoc :val/test-support true)
                         (:malli/schema (:meta v)) (assoc :val/sig (pr-str (:malli/schema (:meta v)))))
                       (cond-> []
                         (seq effs) (conj {:rk :performs :card :many
                                           :targets (mapv effect/effect-iv (sort effs))}))
                       false))
