(ns fukan.common.vocab.code.operation
  "Code vocab — the `Operation` element: a named unit of computation. PURE DESIGN, language-neutral.

   An Operation's IDENTITY is the authored intent alone — its input/output types, the effects it
   performs, and its designed dependencies. Nothing here knows what language the code is written in.

   Its CORRESPONDENCE — how an authored Operation pairs with an extracted code twin, and what the
   code must realize/cover/adhere-to — is a map into a SPECIFIC language's constructs, so it is NOT
   here: `fukan.common.extraction.clojure.operation` declares it from outside via the external
   `(correspond Operation …)` hook, and every drift check is GENERATED from that declaration."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [fukan.common.typing.malli :refer [Schema]]
            [fukan.common.vocab.code.effect :refer [Effect]]))

(defstructure Operation
  "A named unit of computation, either AUTHORED (a self-model's intent) or EXTRACTED from code (the fact
   stratum, stamped by the build at the merge). The two are DISTINCT nodes: a design Operation
   corresponds 1-on-1 to its extracted twin by name within twinned Modules (nested), so
   intended and actual structure stay checkable against each other.

   Authored with a malli signature — `(Operation f \"doc\" {:signature [:=> [:catn [:name Type] …] Out]
   :delegates […]})`. `:signature` is an ORDINARY slot holding one `Schema`, the type dialect's own
   node, so the authored malli form is stored as authored and renders back unchanged.

   Until 2026-08-29 it was sugar: a `(syntax …)` hook decomposed the arrow into `:in`/`:out` slots
   of this structure. That was fukan restating, at the vocabulary altitude, what the dialect
   already modelled — `Schema` has had an arrow kind all along — and the duplication is what made
   MULTI-ARITY look like it needed an arity vocabulary of its own. It does not: `[:function [:=> …]
   [:=> …]]` is malli's spelling, the dialect reads it, and this slot holds it. The print-dual also
   stops lying — you authored `:signature` and `(show …)` used to answer `:in`/`:out`.

   A design op authors `:delegates` (the cross-module surfaces it relies on), never `:calls`:
   internal wiring is extraction's job, so the actual call graph rides a fact-side slot the
   extractor fills."
  {:signature [:? Schema]                          ; the whole function type — the DIALECT's, not decomposed here
   :performs  [:* Effect]                          ; the effects it performs
   :delegates [:* Operation]})                     ; designed dependencies — direct callees

;; An Operation's PROVENANCE is not vocab: `(design ?o)` (authored) and `(fact ?o)` (extracted) are the
;; kernel's universal substrate rules (`fukan.canvas.core.rules`), ambient in every law and query — pair
;; them with the op-kind rule `(Operation ?o)` where op-ness matters.

;; ── correspondence: NOT here ─────────────────────────────────────────────────
;; Operation is pure DESIGN — language-neutral, the same whatever the code is written in. Its
;; correspondence to code is a map into a SPECIFIC language's constructs, so it belongs to whoever
;; extracts that language: `fukan.common.extraction.clojure.operation` declares it, from outside, via
;; the external `(correspond Operation …)` declaration.
;;
;; It lived here until 2026-07-17, which meant this shipped, language-neutral vocabulary exported
;; `:export`/`:test-support`/`:private` — i.e. `^:export`, `^:test-support` and `defn-`, Clojure
;; METADATA CONVENTIONS — as though they were design vocabulary. A consuming project in another
;; language inherited them. Correspondence is contributed by the extractor now, so a project loading
;; this vocab with a different extractor correctly gets no `:calls` at all.
