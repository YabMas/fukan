(ns canvas.vocab.code.operation
  "Code vocab — `Operation`: the unified computational unit, the AUTHORED intent (a self-model's
   input/output Shapes, Effects, designed dependencies). PURE IDENTITY — model↔code correspondence
   (the fact-side slots :calls/:private/…, the twin, and the realized/covered/adheres demands) is NOT
   here: it hooks in from OUTSIDE via `(correspond Operation …)` in `canvas.vocab.code.module`."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]
            [canvas.vocab.type :as ct :refer [Schema]]
            [canvas.vocab.code.effect :refer [Effect]]))

(defstructure Operation
  "A named unit of computation — the UNIFIED computational unit. An `Operation` is either
   AUTHORED (a self-model's intent: input/output Shapes, Effects, intended calls) or
   EXTRACTED from code (fact-stratum, stamped by the BUILD at the merge — name + privacy from
   the plug-point, and actual calls). A modelled Operation corresponds 1-on-1 (by name + corresponding Module)
   to its extracted twin; the two stay distinct nodes so spec and actual remain checkable.

   Authored with a malli signature: `(Operation f \"doc\" {:signature [:=> [:catn [:name Type] …] Out] :delegates […]})`
   — the `(syntax signature->slots)` hook rewrites `:signature` to the `:in`/`:out` entries.

   A boundary sketch authors `:delegates` (the cross-module surfaces it relies on — designed
   dependencies); it does NOT author `:calls` — internal wiring is extraction's job. `:calls` is
   therefore the EXTRACTED actual-call graph. (Implementer intent rides the kernel `:guidance`
   annotation — available on any instance, not an Operation slot.)

   Corresponds NESTED (:by-name): a design Operation twins the same-named extracted one within twinned Modules."
  ;; PURE IDENTITY — the authored intent of an Operation, and nothing else. Correspondence (the fact-side
  ;; slots, twin, demands) hooks in via `(correspond Operation …)` in `canvas.vocab.code.module`; the
  ;; authoring sugar via `register-syntax!` below; the DEMANDS on the operation surface (declare an output
  ;; type; no isolated op) live in `canvas.principles.operation-surface`. None of that is on the concept.
  {:in        [:* Schema]            ; input shapes — positional, each labelled with its param name
   :out       [:? Schema]            ; output schema (authored ops declare one)
   :performs  [:* Effect]            ; side effects it performs
   :delegates [:* {:transitive true} Operation]})  ; designed dependencies (known, direct callees); :transitive ⇒ delegates+
;; `:guidance` (implementer-directed design intent) is NOT an Operation slot — it's a kernel-level
;; per-instance annotation (the read dual of the docstring) available on ANY instance; see
;; `reserved-annotation-keys` in the kernel. Authored inline in the slots map, stored as `:val/guidance`.

;; Operation's authoring SUGAR — off the identity defstructure (it's machinery, not identity).
;; Registered against the tag from outside; the kernel applies it (map → map) at instance-expansion
;; (`sdef-syntax`), honoring the Syntax plug-point's contract.
(defn ^:export signature->slots
  "Operation's authoring syntax: decompose the `:signature` malli function-schema in a slots map
   into the `:in`/`:out` slots (via the type dialect's `ct/arrow->in-out` — INPUT's named params
   become the ordered+labelled `:in` vector, OUTPUT becomes `:out`). Slots without a `:signature`
   pass through untouched."
  [m]
  (if-not (contains? m :signature)
    m
    (let [{:keys [in out]} (ct/arrow->in-out (:signature m))]
      (cond-> (-> m (dissoc :signature) (assoc :out out))
        (seq in) (assoc :in in)))))

(s/register-syntax! ::Operation signature->slots)

;; The provenance split an Operation quantifies over is NOT vocab: `(design ?o)` (authored, not
;; extracted) and `(fact ?o)` (extracted from code) are the kernel's universal substrate rules
;; (`fukan.canvas.core.rules`), ambient in every law and `cq/q`. Combine with the op-kind rule
;; `(Operation ?o)` — or lean on an op-specific edge already in the clause — where op-ness matters.

;; ── model↔code correspondence (op altitude) ──────────────────────────────────
;; The demands (realized / type-coverage / covered / adheres) are NOT on Operation's identity: they
;; hook in from outside via `(correspond Operation …)` in `canvas.vocab.code.module`. No separate
;; law-holder defstructures — and no per-demand reader wrapper: a demand's worklist is just
;; `(cq/violation-names db <demand-key>)` at its stable key, read directly by the consumer
;; (`:corresponds/Operation.realized` = drift, `.covered` = the encapsulation worklist).
;;
;; Type-drift is the GATED `:corresponds/Operation.adheres` demand — STRUCTURAL: the `:signature`
;; comparator (in `canvas.vocab.code.module`) compares the design op's and its twin's decomposed
;; :in/:out node identities (both strata store types as content-deduped Schema nodes), so argument
;; ORDER and ARITY are checked by sequence identity. Surfaces in `(check)` / `(cq/violation-names db
;; :corresponds/Operation.adheres)`. (The `undertyped` precision reading is retired — under exact
;; adherence a modelled `:any` must match the code exactly, so imprecision is not a separate signal.)
