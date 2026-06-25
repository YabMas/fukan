(ns fukan.canvas.core.lens
  "Lens evaluation — a focus resolved to a genuine sub-graph.

   A lens carries ONE selection query (its `:val/query`: datalog `:where` clauses
   binding `?n` as the focused node). `evaluate-lens` runs it with the vocab-derived
   rules — so it reads at domain altitude — and returns the focus node-set; the
   induced relations among those nodes are the rest of the sub-graph. Transitive
   scope (closure) is just recursion within that single query, not a separate knob.

   No cycle: it depends on the kernel for `vocab-rules`, the kernel does not depend back.

   This module also OWNS the act grammar — the `Lens`/`Projection`/`Mapping` structures below.
   Being opinionated about these acts is deliberate: they are fukan-NATIVE apparatus, not domain
   vocab (core is unopinionated about the ELEMENTS a project models, opinionated about the ACTS it
   performs on them)."
  (:require [clojure.set :as set]
            [clojure.edn :as edn]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.structure :as s :refer [defstructure]]))

;; ── THE FOCUS: a Lens names a slice and carries its runnable selection ─────────────────────────

(defstructure Lens
  "A focus over the model — what it brings into view / weighs as salient. `:focus` is the prose
   description of the slice; `:select` is the focus's own executable form — the datalog selection
   (binding `?n`, evaluated by `evaluate-lens`) that resolves the prose to a genuine sub-graph. The
   selection lives HERE, not in a realization shim: it is model-native datalog — it references no
   code, only the graph's own vocabulary, exactly like a law's `:where` or a `realized-as`
   derivation. It is the focus stated runnably, not a second thing that could drift from it. A lens
   with no `:select` is prose-only (not evaluable)."
  {:focus  :string                          ; the prose description of the slice
   :select [:? {:payload :query} :string]}) ; recap + the datalog selection (the :query payload)

;; ── THE SYNTHESIS: a Projection re-presents the model through a Lens ───────────────────────────

(defstructure ^:value Mapping
  "One source-kind → target-artifact rule within a projection — value-identified by its (from, to)."
  {:from :string     ; the source structure kind
   :to   :string})   ; the target artifact it becomes

(defstructure Projection
  "A projected representation of the model — a target we render it into. Two flavours, composing:
     a BASE projection renders source kinds directly — it `:maps` each focused kind to a target
     artifact (Blueprint → implementation specs; Docs → documentation).
     a CONTEXTUALIZATION renders THROUGH a base it `:contextualizes`, wrapping that base's output in
     a framing `:context` (DriftClose = Blueprint framed as drift to close; the same composes
     Blueprint with a 'new feature' or 'refactor' context). It adds no mappings of its own — it
     reuses the base's, told differently.
   Either flavour renders THROUGH a `Lens` (the WHAT). The same lens can feed a read and a
   projection (the drift lens feeds drift readings AND DriftClose)."
  {:through        Lens              ; the focus it renders through (the WHAT)
   :maps           [:* Mapping]      ; a BASE's source→artifact mappings (the HOW)
   :contextualizes [:? Projection]   ; a CONTEXTUALIZATION's base projection
   :context        [:? :string]}     ; the framing prose wrapped around the base render
  ;; a projection is one flavour or the other — it declares mappings (base) or frames
  ;; another (contextualization); neither would render nothing.
  (law "a projection is a base (declares mappings) or a contextualization (frames another)"
    (has-any :maps :contextualizes)))

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

(defn focus-nodes
  "Run datalog `:where` `clauses` (binding `?n` as the focused node) with the
   vocab-derived rules, returning the focus node-set (a set of eids). The shared
   evaluation engine behind both a stored lens and any ad-hoc focus."
  [db clauses]
  (set (cq/q (vec (concat '[:find [?n ...] :in $ %] [:where] clauses))
             db (s/vocab-rules))))

(defn evaluate-lens
  "Run lens `lens-eid`'s own selection query — the `:val/query` payload it carries (its
   `:select` slot) — with the vocab-derived rules, returning the focus node-set (a set of
   eids). The selection is the focus stated runnably (model-native datalog), so it lives ON
   the lens; no `:realizes` indirection. TOTAL: a prose-only lens (no `:select`) is not
   evaluable, so it yields `nil` — a Maybe (`nil` = not evaluable, distinct from `#{}` =
   evaluated to no nodes), never a throw. This is a trusted-core reader over the Model, so it
   stays total (parse-don't-validate); deciding a prose-only lens is unevaluable is the
   caller's concern, not an exception in the core."
  [db lens-eid]
  (when-let [clauses (:val/query (cq/entity db lens-eid))]
    ;; the :query payload round-trips through pr-str in the Cozo substrate (arrives as a
    ;; string) — read it back when it came as a string.
    (focus-nodes db (cond-> clauses (string? clauses) edn/read-string))))

(defn refine
  "Narrow a `focus` (a node-set) to its members that ALSO match `clauses` (binding `?n`,
   evaluated with the vocab-derived rules) — lens-within-lens. The composable step: a
   focus refined by a further query, so acts CHAIN by passing a refined focus forward
   (e.g. focus-nodes → refine → materialize-over / a scoped probe)."
  [db focus clauses]
  (set/intersection (set focus) (focus-nodes db clauses)))

(defn run-checks
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
