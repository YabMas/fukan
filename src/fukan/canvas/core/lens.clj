(ns fukan.canvas.core.lens
  "Lens evaluation — a focus resolved to a genuine sub-graph.

   A lens carries ONE selection query (its `:val/select`: datalog `:where` clauses
   binding `?n` as the focused node). `evaluate-lens` runs it with the vocab-derived
   rules — so it reads at domain altitude — and returns the focus node-set; the
   induced relations among those nodes are the rest of the sub-graph. Transitive
   scope (closure) is just recursion within that single query, not a separate knob.
   `projection-focus` resolves a Projection's focus the same way — its own inline
   `:select`, a named `:through` Lens, or (absent both) the whole model.

   No cycle: it depends on the kernel for `vocab-rules`, the kernel does not depend back.

   This module also OWNS the act grammar — the `Lens`/`Projection`/`Check` structures below.
   Being opinionated about these acts is deliberate: they are fukan-NATIVE apparatus, not domain
   vocab (core is unopinionated about the ELEMENTS a project models, opinionated about the ACTS it
   performs on them)."
  (:require [clojure.set :as set]
            [clojure.edn :as edn]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.structure :as s :refer [defstructure]]))

;; ── THE FOCUS: a Lens names a slice and carries its runnable selection ─────────────────────────

(defstructure Lens
  "A focus over the model — what it brings into view / weighs as salient. Its description is the
   instance docstring; `:select` is the focus's own executable form — the datalog selection (binding
   `?n`, evaluated by `evaluate-lens`) that resolves the description to a genuine sub-graph. The
   selection lives HERE, not in a realization shim: it is model-native datalog — it references no
   code, only the graph's own vocabulary, exactly like a law's `:where` or a `realized-as`
   derivation. It is the focus stated runnably, not a second thing that could drift from it. A lens
   with no `:select` is prose-only (not evaluable).
   A Lens is the OPTIONAL naming act for a focus — the `defrelation` of selections: minted when
   a selection is genuinely shared (≥2 consumers) or wants independent addressability. A
   single-consumer focus belongs INLINE on its Projection (`:select` there)."
  {:select [:? {:form true} :any]})         ; the datalog selection (binding ?n) — an opaque code-form leaf

;; ── THE SYNTHESIS: a Projection re-presents the model from a focus ─────────────────────────────

(defstructure Projection
  "A projected representation of the model — a target we render it into. Two flavours, composing:
     a BASE projection renders source kinds directly into its target artifact (Blueprint →
     implementation specs; Docs → documentation) — the kind→artifact mapping lives in the
     REGISTERED RENDERERS, not on the node.
     a CONTEXTUALIZATION renders THROUGH a base it `:contextualizes`, wrapping that base's output in
     a framing `:context` (DriftClose = Blueprint framed as drift to close) — the base's rendering,
     told differently.
   A projection node carries the FOCUS and the INTERPRETATION (its docstring); the focus is
   declared one of three ways: an inline `:select` (its OWN selection — the default home for a
   single-consumer focus), a `:through` Lens (a NAMED focus, minted only when a selection is
   genuinely shared), or NEITHER — no narrowing is the maximal focus, the whole model (Blueprint).
   Never both; `projection-focus` is the one resolution."
  {:select         [:? {:form true} :any] ; the projection's own inline selection (binding ?n)
   :through        [:? Lens]              ; …or a NAMED shared focus
   :contextualizes [:? Projection]        ; a CONTEXTUALIZATION's base projection
   :context        [:? :string]}          ; the framing prose wrapped around the base render
  (law "a projection focuses inline (:select) or through a named lens (:through), never both"
    :offenders '[?p]
    :where '[[?p :val/select ?s]
             [?tr :rel/from ?p] [?tr :rel/kind :through]]))

;; ── THE GATE: a Check turns a Lens's focus into a verdict ──────────────────────────────────────

(defstructure Check
  "A GATE over a Lens — the use-side dual of the law substrate, and the third native act beside
   `Lens` (read) and `Projection` (synthesize). A Check names a Lens it `:gates` and a `:verdict`:
   when that lens's focus is NON-EMPTY, it is a violation and the focused nodes are the offenders.
   Reading and checking are different acts — a Check turns a focus into a gate, and the focus itself
   doesn't know it is gated. `run-checks` evaluates every Check, parallel to how `structure/check`
   runs the laws.

   A Check is the LIGHTWEIGHT use-side gate a project authors over its own lenses (compose a focus,
   declare 'non-empty is a violation'). fukan's own rigorous model↔code correspondence gates stay
   bespoke law-holders in `target.correspondence` — guards, transitive rules, module-correspondence
   — so fukan authors no Check of its own; this is the surface a CONSUMER project gates with."
  {:gates   Lens       ; the focus this check gates on — non-empty ⇒ violation
   :verdict :string})  ; what a non-empty focus means — the violation description

(defn- expand-via
  "Composition sugar: expand each `(via R Scope P)` clause into the property P transported along
   relation R's generated closure R+ at altitude Scope —
     (via R Scope P)  ⇒  (Scope ?n) (R+ ?n ?o) (P ?o)
   — ?n is in focus if it reaches a P-node along R by ≥1 hop. R is a keyword (its `R+` closure is
   compiler-minted for every binary relation); Scope and P are symbols (a kind-rule and a property rule). Non-`via` clauses
   pass through unchanged; each occurrence gets a fresh intermediate var. TOP-LEVEL only: a `via` nested
   inside a `not-join`/`or-join` — or inside a `(measure …)` body — is NOT expanded (negated/aggregated transport is not yet supported)."
  [clauses]
  (vec (apply concat
              (map-indexed
               (fn [i clause]
                 (if (and (seq? clause) (= 'via (first clause)))
                   (let [[_ r scope p] clause
                         r+ (symbol (str (name r) "+"))
                         o  (symbol (str "?_via" i))]
                     [(list scope '?n) (list r+ '?n o) (list p o)])
                   [clause]))
              clauses))))

(defn- expand-selection
  "Expand the lens-facing composition sugar into ordinary datalog clauses."
  [clauses]
  (s/expand-clauses (expand-via clauses)))

(defn ^{:malli/schema [:=> [:cat :StructureDb [:vector :Clause]] [:vector :Eid]]}
  focus-nodes
  "Run datalog `:where` `clauses` (binding `?n` as the focused node) with the
   vocab-derived rules, returning the focus node-set (a set of eids). The shared
   evaluation engine behind both a stored lens and any ad-hoc focus.
   A `(path ?from [:r :s* :t+] ?to)` clause composes relation paths, and `(via R Scope P)`
   composes a property `P` along relation `R`'s transitive closure at altitude `Scope`
   (see `expand-via`)."
  [db clauses]
  (set (cq/q (vec (concat '[:find [?n ...] :in $ %] [:where] (expand-selection clauses)))
             db (s/vocab-rules))))

(defn ^{:malli/schema [:=> [:cat :StructureDb :Eid] [:vector :Eid]]}
  evaluate-lens
  "Run lens `lens-eid`'s own selection query — the `:val/select` form it carries (its
   `:select` slot) — with the vocab-derived rules, returning the focus node-set (a set of
   eids). The selection is the focus stated runnably (model-native datalog), so it lives ON
   the lens; no `:realizes` indirection. TOTAL: a prose-only lens (no `:select`) is not
   evaluable, so it yields `nil` — a Maybe (`nil` = not evaluable, distinct from `#{}` =
   evaluated to no nodes), never a throw. This is a trusted-core reader over the Model, so it
   stays total (parse-don't-validate); deciding a prose-only lens is unevaluable is the
   caller's concern, not an exception in the core."
  [db lens-eid]
  (when-let [clauses (:val/select (cq/entity db lens-eid))]
    ;; the :select form round-trips through pr-str in the Cozo substrate (arrives as a
    ;; string) — read it back when it came as a string.
    (focus-nodes db (cond-> clauses (string? clauses) edn/read-string))))

(defn ^{:malli/schema [:=> [:cat :StructureDb :Eid] [:vector :Eid]]}
  projection-focus
  "Resolve Projection node `proj-eid`'s FOCUS — the node-set it renders. Three-way:
   an inline `:select` (its own `:val/select` form) → `focus-nodes`; else a `:through`
   Lens → `evaluate-lens` (a prose-only lens still yields nil = not evaluable, the
   caller's concern); else NO focus declared → the WHOLE MODEL (absence of narrowing is
   the maximal focus). The single focus-resolution a Projection renderer uses — dormant since
   the downward projection was cut, kept for the deferred projection side."
  [db proj-eid]
  (let [clauses (:val/select (cq/entity db proj-eid))]
    (if clauses
      (focus-nodes db (cond-> clauses (string? clauses) edn/read-string))
      (if-let [l (ffirst (cq/q '[:find ?l :in $ ?p
                                 :where [?r :rel/from ?p] [?r :rel/kind :through] [?r :rel/to ?l]]
                               db proj-eid))]
        (evaluate-lens db l)
        (focus-nodes db '[[?n :structure/of _]])))))

(defn ^{:malli/schema [:=> [:cat :StructureDb [:vector :Eid] [:vector :Clause]] [:vector :Eid]]}
  refine
  "Narrow a `focus` (a node-set) to its members that ALSO match `clauses` (binding `?n`,
   evaluated with the vocab-derived rules) — lens-within-lens. The composable step: a
   focus refined by a further query, so acts CHAIN by passing a refined focus forward
   (e.g. focus-nodes → refine → a scoped reading or projection)."
  [db focus clauses]
  (set/intersection (set focus) (focus-nodes db clauses)))

(defn ^{:malli/schema [:=> [:cat :StructureDb] :any]}
  run-checks
  "Evaluate every `Check` in `db`: a Check gates a Lens, so its violation is the gated lens's focus
   being NON-EMPTY — each focused node an offender. Returns a seq of
   `{:check <name> :verdict <str> :offenders <node-set>}`, the use-side dual of `structure/check`'s
   law violations: empty ⇔ every gated focus is empty (a prose-only gated lens is unevaluable, so it
   never fires). Laws gate the substrate; Checks gate a use-side focus."
  [db]
  (for [[c verdict lens] (cq/q '[:find ?c ?verdict ?lens
                                 :where [?c :structure/of :fukan.canvas.core.lens/Check]
                                        [?c :val/verdict ?verdict]
                                        [?g :rel/kind :gates] [?g :rel/from ?c] [?g :rel/to ?lens]]
                               db)
        :let  [focus (evaluate-lens db lens)]
        :when (seq focus)]
    {:check     (:entity/name (cq/entity db c))
     :verdict   verdict
     :offenders (set focus)}))
