(ns lib.code
  "Shared-lib vocabulary — the grammar for describing CODE: `Kind` (atomic type),
   `Effect`, `Operation` (a unit of computation) and `Module` (a code boundary).
   These are standard code-structures, NOT unique to fukan — so they live in the
   reusable `lib/` stdlib, not in fukan's own canvas vocab. A consumer (a self-model
   or a demo) opts in by requiring this namespace; the extractor `extract` produces
   instances of these by tag, the model authors instances of them, and they meet at
   merge.

   Opt-in (required, not auto-discovered like `canvas/**`); ingests no instances."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [lib.grouping :refer [Connected]]
            [lib.type.malli :refer [Schema]]))

;; ── data types ───────────────────────────────────────────────────────────────

(defstructure Kind
  "A named atomic type — a leaf in a Schema (e.g. Db, NsSymbol, File). Owned by at
   most one Module (`:owns` — others adopt it, they don't redefine it)."
  (law "a Kind is owned by at most one Module"
    (at-most-one :owns)))

;; ── computation ──────────────────────────────────────────────────────────────

(defn ^:export read-effect
  "Expand an effect literal — a keyword like `:io` — into Effect clauses, so
   effects author as `(performs :io :require)`."
  [kw]
  [(list 'name (name kw))])

(defstructure ^:value Effect
  "A named side effect an Operation performs (e.g. :io, :require, :stderr, :throws).
   Value-identified — `:io` is one node shared across every Operation that performs it."
  {:name :String}
  (reader read-effect))

(defn ^:export signature->clauses
  "Operation's authoring syntax (the `(syntax …)` hook): a `(signature <malli-fn-schema>)`
   clause is rewritten into the ordered+labelled `:in` and the `:out` clauses. The schema is a
   malli function schema `[:=> INPUT OUTPUT]`; INPUT is `[:catn [:name Type] …]` (named params →
   ordered + labelled) or `[:cat]` (nullary). A `[:cat Type …]` (positional, unnamed) is REJECTED —
   name your parameters. The Types are ordinary malli, expanded by the Schema reader (a bare symbol
   is a var-ref to a Kind). Lives in the vocab — malli never touches core."
  [body]
  (let [sig (some #(when (and (seq? %) (= 'signature (first %))) %) body)]
    (if-not sig
      body
      (let [form (second sig)]
        (when-not (and (vector? form) (= :=> (first form)) (= 3 (count form)))
          (throw (ex-info (str "signature must be a malli function schema [:=> INPUT OUTPUT]: " (pr-str form)) {:form form})))
        (let [[_ input output] form]
          (when-not (vector? input)
            (throw (ex-info (str "signature input must be [:catn …] or [:cat]: " (pr-str input)) {:form form})))
          (let [[in-op & in-args] input
                in-clause (case in-op
                            :catn (when (seq in-args)
                                    ;; one (in [name Type] …) clause — VARARGS of labelled
                                    ;; pairs; authoring order is the :in sequence order
                                    [(cons 'in (map (fn [pair]
                                                      (when-not (and (vector? pair) (= 2 (count pair)) (keyword? (first pair)))
                                                        (throw (ex-info (str ":catn entry must be [:name Type]: " (pr-str pair)) {:form form})))
                                                      [(symbol (name (first pair))) (second pair)])
                                                    in-args))])
                            :cat  (when (seq in-args)
                                    (throw (ex-info (str "name your parameters — use [:catn [:name Type] …], not [:cat …]: " (pr-str input)) {:form form})))
                            (throw (ex-info (str "signature input must be [:catn …] or [:cat]: " (pr-str input)) {:form form})))]
            (concat (remove #(= % sig) body) in-clause [(list 'out output)])))))))

(defstructure Operation
  "A named unit of computation — the UNIFIED computational unit. An `Operation` is either
   AUTHORED (a self-model's intent: input/output Shapes, Effects, intended calls) or
   EXTRACTED from code (`:extracted true`, stamped by the plug-point — name + privacy, and
   actual calls). A modelled Operation corresponds 1-on-1 (by name + corresponding Module)
   to its extracted twin; the two stay distinct nodes so spec and actual remain checkable.

   Authored with a malli signature: `(Operation (doc …) (signature [:=> [:catn [:name Type] …] Out]) (calls …))`
   — the `(syntax signature->clauses)` hook rewrites it to `:in`/`:out` clauses. (Replaces the old
   `Stage` and the extractor's private `Operation` — one vocab, owned here, the extractor produces
   into it by tag.)"
  (includes Connected)
  (syntax signature->clauses)        ; (signature [:=> [:catn …] Out]) authoring form (vocab-owned)
  {:in        [:* Schema]            ; input shapes — positional, each labelled with its param name
   :out       [:? Schema]            ; output schema (authored ops declare one; extracted may not)
   :performs  [:* Effect]            ; side effects
   :calls     [:* Operation]         ; downstream operations it invokes
   :private   [:? :Bool]             ; public/internal — the module's surface (from extraction)
   :extracted [:? :Bool]             ; provenance: true ⇒ from code; absent/false ⇒ authored
   ;; the code's REALIZED malli signature (a pr-str'd `[:=> …]` form), stamped by extraction
   ;; from `:malli/schema` metadata; authored Operations leave it empty and use :in/:out.
   :sig       [:? :String]})

;; ── code module (boundary + ownership) ───────────────────────────────────────

(defstructure Module
  "A code module — one cohesion boundary (a namespace). Like a `Grouping` it collects members
   (`:child`), but it ALSO carries code semantics: an explicit API surface (`:exposes`) and the
   data-shapes it is the source of truth for (`:owns`). Conceptually a Module IS-A Grouping;
   making that an explicit `(includes Grouping)` waits until slot type-checks honor
   `includes`-satisfaction (so `FacultyRealization.realizer` can tighten from `Any` to
   `Grouping`) — deferred theory-composition work. (A Module is NOT a `Subsystem`: that level —
   a cluster of modules realizing a capability — is reserved and undefined for now; today every
   code namespace is a Module.)

   `:exposes` is the public surface (the Operations callers depend on); `:owns` are the Kinds
   this module DECIDES — others adopt them, they don't redefine them; `:child` is the full
   membership / ownership backbone (`in-module` resolves over it)."
  {:exposes [:* Operation]           ; the public API surface
   :owns    [:* Kind]                ; the Kinds it is the source of truth for
   :child   [:* Any]})               ; internal members + ownership backbone
