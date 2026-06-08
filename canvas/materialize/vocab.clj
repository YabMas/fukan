(ns canvas.materialize.vocab
  "The materialize layer's own VOCABULARY — the grammar for describing CODE, which is what
   this layer corresponds the model to. Owned here, not by the extractor: `extract` produces
   instances of these by tag, the self-models author instances of them, and they meet at
   merge. (Moved out of `language/`: `op` and `shape` were never domain-agnostic — they
   describe code.)

   Vocab-only canvas spec (no build-canvas)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [canvas.dialects.malli :refer [Schema]]))

;; ── data types ───────────────────────────────────────────────────────────────

(defstructure Kind
  "A named atomic type — a leaf in a Schema (e.g. Db, NsSymbol, File).")

;; ── computation ──────────────────────────────────────────────────────────────

(defn ^:export read-effect
  "Expand an effect literal — a keyword like `:io` — into Effect clauses, so
   effects author as `(performs :io :require)`."
  [kw]
  [(list 'name (name kw))])

(defstructure ^:value Effect
  "A named side effect an Operation performs (e.g. :io, :require, :stderr, :throws).
   Value-identified — `:io` is one node shared across every Operation that performs it."
  (slot :name (one :String))
  (reader read-effect))

(defn ^:export op-arrow
  "Operation's authoring sugar (the `(syntax …)` hook): a signature `[in-binding]… -> Out` mixed
   among the clauses becomes `(in …)`/`(out …)`. Vectors before the `->` are input bindings (each
   `[name Type]`); the token after `->` is the output shape. Plain clauses (`(doc …)`, `(calls …)`,
   `(performs …)`) pass through unchanged — as do explicit `(in …)`/`(out …)` clauses (they're seqs),
   so the arrow and the clause form coexist. Lives here, in the vocab — `->` never touches core."
  [body]
  (let [clauses     (filter seq? body)               ; (doc …) (calls …) (performs …) [(in …)/(out …)]
        sig         (remove seq? body)               ; [in…] -> Out  (an empty [] = no inputs)
        [ins after] (split-with #(not= '-> %) sig)]
    (concat clauses
            (map #(list 'in %) (filter seq ins))     ; non-empty input vectors → :in ([] = none)
            (when (= '-> (first after)) [(list 'out (second after))]))))

(defstructure Operation
  "A named unit of computation — the UNIFIED computational unit. An `Operation` is either
   AUTHORED (a self-model's intent: input/output Shapes, Effects, intended calls) or
   EXTRACTED from code (`:extracted true`, stamped by the plug-point — name + privacy, and
   actual calls). A modelled Operation corresponds 1-on-1 (by name + corresponding Subsystem)
   to its extracted twin; the two stay distinct nodes so spec and actual remain checkable.

   Authored with an arrow signature: `(Operation (doc …) [in-binding]… -> Out (calls …))` — the
   `(syntax op-arrow)` hook rewrites it to `:in`/`:out` clauses. (Replaces the old `Stage` and the
   extractor's private `Operation` — one vocab, owned here, the extractor produces into it by tag.)"
  (includes Connected)
  (syntax op-arrow)                   ; [in…] -> Out authoring sugar (vocab-owned)
  (slot :in        (many Schema))     ; input schemas
  (slot :out       (optional Schema)) ; output schema (authored ops declare one; extracted may not)
  (slot :performs  (many Effect))     ; side effects
  (slot :calls     (many Operation))  ; downstream operations it invokes
  (slot :private   (optional :Bool))  ; public/internal — the module's surface (from extraction)
  (slot :extracted (optional :Bool))) ; provenance: true ⇒ from code; absent/false ⇒ authored

;; ── code subsystem (boundary + ownership) ────────────────────────────────────

(defstructure Subsystem
  "A subsystem of code — a cohesion boundary (a namespace/module). Unlike the generic
   grouping `Module` (which groups CONCEPTS), a Subsystem describes CODE: it has an explicit
   API boundary and owns the data-shapes it is the source of truth for.

   `:exposes` is the public surface (the Operations callers depend on); `:owns` are the Kinds
   this subsystem DECIDES — others adopt them, they don't redefine them; `:child` is the full
   membership / ownership backbone (`in-module` resolves over it)."
  (slot :exposes (many Operation))   ; the public API surface
  (slot :owns    (many Kind))        ; the Kinds it is the source of truth for
  (slot :child   (many Any))         ; internal members + ownership backbone
  (law "a Kind is owned by at most one Subsystem"
    :offenders '[?k]
    :where '[[?k :structure/of :Kind]
             [?r1 :rel/from ?s1] [?r1 :rel/kind :owns] [?r1 :rel/to ?k]
             [?r2 :rel/from ?s2] [?r2 :rel/kind :owns] [?r2 :rel/to ?k]
             [(not= ?s1 ?s2)]]))
