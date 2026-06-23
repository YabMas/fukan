(ns canvas.vocab.code.operation
  "Code vocab — `Operation`: the unified computational unit, AUTHORED (a self-model's intent)
   or EXTRACTED from code (`:extracted true`). (The Realization/Encapsulation correspondence
   + drift/coverage readers live here too — added with the correspondence layer; the
   `defn→Operation` + `:calls` extraction is added with the extractor.)"
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [canvas.vocab.type :as ct :refer [Schema]]
            [canvas.vocab.code.effect :refer [Effect]]
            [canvas.vocab.grouping :refer [Connected]]))

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
