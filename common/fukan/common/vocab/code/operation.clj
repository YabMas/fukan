(ns fukan.common.vocab.code.operation
  "Code vocab — the `Operation` element: a named unit of computation. PURE DESIGN, language-neutral.

   An Operation's IDENTITY is the authored intent alone — its input/output types, the effects it
   performs, and its designed dependencies. Nothing here knows what language the code is written in.

   Its CORRESPONDENCE — how an authored Operation pairs with an extracted code twin, and what the
   code must realize/cover/adhere-to — is a map into a SPECIFIC language's constructs, so it is NOT
   here: `fukan.common.extraction.clojure.operation` declares it from outside via the external
   `(correspond Operation …)` hook, and every drift check is GENERATED from that declaration.
   Implementer-directed prose likewise isn't a slot — it rides the kernel `:guidance` annotation."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.common.typing.malli :as ct :refer [Schema]]
            [fukan.common.vocab.code.effect :refer [Effect]]))

(defstructure Operation
  "A named unit of computation, either AUTHORED (a self-model's intent) or EXTRACTED from code (the fact
   stratum, stamped by the build at the merge). The two are DISTINCT nodes: a design Operation
   corresponds 1-on-1 to its extracted twin by name within twinned Modules (nested), so
   intended and actual structure stay checkable against each other.

   Authored with a malli signature — `(Operation f \"doc\" {:signature [:=> [:catn [:name Type] …] Out]
   :delegates […]})` — which the `signature->slots` sugar rewrites into the `:in`/`:out` slots. A design
   op authors `:delegates` (the cross-module surfaces it relies on), never `:calls`: internal wiring is
   extraction's job, so the actual call graph rides a fact-side slot the extractor fills."
  {:in        [:* Schema]                          ; input types — positional, ordered, each labelled with its param name
   :out       [:? Schema]                          ; output type
   :performs  [:* Effect]                          ; the effects it performs
   :delegates [:* {:transitive true} Operation]})  ; designed dependencies — direct callees; :transitive ⇒ the delegates+ closure

;; `:guidance` (implementer-directed intent) is deliberately NOT a slot — it rides the kernel's
;; per-instance annotation (a `:val/guidance` leaf on any instance, the read-dual of a docstring).

;; Authoring sugar — machinery, not identity, so it lives off the defstructure and registers against the
;; tag from outside; the kernel applies it (map → map) at instance-expansion, per the Syntax plug-point.
(defn ^:export signature->slots
  "Rewrite an Operation's `:signature` (a malli function-schema) into the `:in`/`:out` slots via the type
   dialect's `ct/arrow->in-out`: the input's named params become the ordered `:in` vector, the output
   becomes `:out`. A slots map without a `:signature` passes through unchanged."
  [m]
  (if-not (contains? m :signature)
    m
    (let [{:keys [in out]} (ct/arrow->in-out (:signature m))]
      (cond-> (-> m (dissoc :signature) (assoc :out out))
        (seq in) (assoc :in in)))))

(s/register-syntax! ::Operation signature->slots)

;; An Operation's PROVENANCE is not vocab: `(design ?o)` (authored) and `(fact ?o)` (extracted) are the
;; kernel's universal substrate rules (`fukan.canvas.core.rules`), ambient in every law and query — pair
;; them with the op-kind rule `(Operation ?o)` where op-ness matters.

;; ── correspondence: NOT here ─────────────────────────────────────────────────
;; Operation is pure DESIGN — language-neutral, the same whatever the code is written in. Its
;; correspondence to code is a map into a SPECIFIC language's constructs, so it belongs to whoever
;; extracts that language: `fukan.common.extraction.clojure.operation` declares it, from outside, via
;; the external `(correspond Operation …)` registry hook.
;;
;; It lived here until 2026-07-17, which meant this shipped, language-neutral vocabulary exported
;; `:export`/`:test-support`/`:private` — i.e. `^:export`, `^:test-support` and `defn-`, Clojure
;; METADATA CONVENTIONS — as though they were design vocabulary. A consuming project in another
;; language inherited them. Correspondence is contributed by the extractor now, so a project loading
;; this vocab with a different extractor correctly gets no `:calls` at all.
