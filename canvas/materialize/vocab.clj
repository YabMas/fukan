(ns canvas.materialize.vocab
  "The materialize layer's own VOCABULARY — the grammar for describing CODE, which is what
   this layer corresponds the model to. Owned here, not by the extractor: `extract` produces
   instances of these by tag, the self-models author instances of them, and they meet at
   merge. (Moved out of `language/`: `op` and `shape` were never domain-agnostic — they
   describe code.)

   Vocab-only canvas spec (no build-canvas)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

;; ── data types ───────────────────────────────────────────────────────────────

(defstructure Kind
  "A named atomic type — a leaf in a Shape (e.g. Db, NsSymbol, File).")

(defn ^:export read-shape
  "Expand a data-LITERAL shape into Shape construction clauses — one level; nested
   literals are re-expanded by the interpreter:
     Foo        (symbol)        → a \"type\" leaf naming the Kind Foo
     [X]        (1-elem vector) → a \"list\" of shape X
     {:f X, …}  (map)           → a \"record\" with fields f: X, …"
  [data]
  (cond
    (symbol? data) [(list 'kind "type") (list 'type data)]
    (vector? data) [(list 'kind "list") (list 'of (first data))]
    (map?    data) (into [(list 'kind "record")]
                         (map (fn [[k v]] (list 'of [(symbol (name k)) v])) data))
    :else (throw (ex-info (str "not a shape literal: " (pr-str data)) {:data data}))))

(defstructure ^:value Shape
  "A structural type-shape, value-identified. `:kind` is the combinator — \"type\"
   (a leaf naming a Kind), \"list\" (a homogeneous sequence of its child shape), or
   \"record\" (labelled child shapes); `:of` are child shapes (recursive); `:type`
   names the referenced Kind for a \"type\" leaf. Author as data: `Db`, `[Db]`,
   `{:entity-maps [EntityMap] :ref-txs [RefTx]}`."
  (slot :kind (one :String))
  (slot :of   (many Shape))
  (slot :type (optional Kind))
  (reader read-shape)
  (law "a \"type\" shape must name a Kind"
    :offenders '[?s]
    :where '[[?s :val/kind "type"]
             (not-join [?s] [?r :rel/from ?s] [?r :rel/kind :type])]))

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

(defstructure Operation
  "A named unit of computation — the UNIFIED computational unit. An `Operation` is either
   AUTHORED (a self-model's intent: input/output Shapes, Effects, intended calls) or
   EXTRACTED from code (`:extracted true`, stamped by the plug-point — name + privacy, and
   actual calls). A modelled Operation corresponds 1-on-1 (by name + corresponding Subsystem)
   to its extracted twin; the two stay distinct nodes so spec and actual remain checkable.

   (Replaces the old `Stage` and the extractor's private `Operation` — one vocab, owned here,
   the extractor produces into it by tag. Slots are mostly optional so one grammar fits both
   the rich authored side and the thin extracted side.)"
  (includes Connected)
  (slot :in        (many Shape))      ; input shapes
  (slot :out       (optional Shape))  ; output shape (authored ops declare one; extracted may not)
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
