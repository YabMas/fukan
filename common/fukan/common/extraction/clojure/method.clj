(ns fukan.common.extraction.clojure.method
  "Clojure grounding for `Fulfilment` — the FACT vocabulary (`Method`) and the design↔Clojure
   CORRESPONDENCE (`Fulfilment ↦ Method`).

   `Method` is the codomain: the Clojure realization of a fulfilment — an extracted `defmethod`,
   owning the calls its body makes and carrying one `:fulfils` edge to the multimethod it
   implements, labelled with the dispatch value as clj-kondo spells it.

   IT IS DELIBERATELY NOT AN `Fn`, and each of the three reasons is a law that would need an
   exemption otherwise. A natural key is the assembler's dedup key, and a co-owned method would
   key as `ns/name` — the same key as its own `defmulti` — so the two would merge into one node.
   `public-unaccounted` would fire on every method in an adopted namespace, demanding an authored
   Operation per dispatch value. And correspondence matching is name-only, so a method would pair
   with a same-named authored Operation. Three exemptions in the laws that define what a surface
   IS means the thing is not a surface — which is the true statement about a method: nobody calls
   it by name.

   Its identity is the TRIPLE (implementing namespace, surface, dispatch value), which is what
   makes two namespaces supplying the same value two nodes rather than one.

   The generic `Fulfilment` structure — pure, language-neutral intent — lives in
   `fukan.common.vocab.patterns.fulfilment`, which also owns the `fulfils` relation this
   contributes its fact-side edges to."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.common.extraction.clojure.operation :refer [Fn]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.patterns.fulfilment :refer [Fulfilment]]))

;; ── the FACT vocabulary: a Clojure defmethod ─────────────────────────────────
(defstructure Method
  "The Clojure realization of a Fulfilment — an EXTRACTED `defmethod`, stamped by the build. It
   `:fulfils` exactly one surface (the multimethod's `Fn`) and owns the `:calls` its body makes,
   which is the whole of what a satisfier contributes: the surface it supplies, and what supplying
   it costs. A method is a member of the namespace that WRITES it, not of the one that owns the
   surface — that asymmetry is the entire point of the word."
  {:fulfils Fn                  ; the surface, labelled on the edge with the dispatch value
   :calls   [:* Fn]}            ; the ACTUAL calls its body makes (the same relation `Fn` feeds)

  ;; ── the correspondence's TEETH, at the FULFILMENT altitude ──────────────────────────────────
  ;; Rides `Method` for the reason the other five ride `Ns`/`Fn`: `correspond` lowers exclusively
  ;; to rules, so a denial ABOUT a correspondence rides the codomain declared beside it.
  ;;
  ;; Both laws range over CROSS-MODULE fulfilment, and that is what the declaration MEANS rather
  ;; than a scope bolted onto the law. An authored fulfilment says a module depends on another
  ;; because it supplies it; when satisfier and surface share a module there is no dependency to
  ;; state, which is why both dependency relations already drop self-edges. A co-owned method is
  ;; therefore degenerate in precisely the way a plain `defn` is — supply inside one module is
  ;; implementation, not intent — and demanding a declaration for it would be demanding that a
  ;; module declare it depends on itself.
  ;;
  ;; Both are also gated on PAIRED CARRIERS, which is the discipline the neighbouring
  ;; correspondence laws already keep. A fulfilment rides two carriers — its satisfier and its
  ;; surface — and can have a counterpart on the other stratum only where BOTH have paired.
  ;; `operation-unrealized` is gated on an already-paired owning Module for exactly this reason:
  ;; one absent carrier must yield ONE finding, at the carrier's own altitude, not one more per
  ;; claim it carries. Outside these gates the absence is already named where it is caused —
  ;; module-unrealized, operation-unrealized, public-unaccounted, or the adoption frontier — and a
  ;; fulfilment finding there would diagnose the consequence with its cause stated beside it.
  ;; Nothing true is lost: where a carrier has not paired, there is no counterpart to find.
  (law "every cross-module declared fulfilment is realized by a method"
    ;; The design claims a module supplies a surface it does not own, and no code does. This is
    ;; the rename case for the supply side: the satisfier moved, or the dispatch was deleted, and
    ;; the declaration goes on asserting an inversion that no longer exists.
    ;;
    ;; The offender row carries all three names because the declaration's own name is an authored
    ;; symbol — legible to whoever wrote it, opaque to a reader meeting the finding cold.
    {:scope     :global
     :key       :correspondence/fulfilment-unrealized
     :offenders [?ful ?m ?op]
     :where     [(is ?ful Fulfilment) (design ?ful)
                 (satisfier ?ful ?m) (surface ?ful ?op)
                 (is ?owner Module) (contains ?owner ?op) [(not= ?m ?owner)]
                 (corresponds ?m ?_ns) (corresponds ?op ?_fn)
                 (not-join [?ful] (corresponds ?ful ?meth))]})

  (law "every cross-module method realizes a declared fulfilment"
    ;; The other direction: the code supplies across a module boundary and the design never said
    ;; so. Left unsaid, that dependency is invisible to `:may-depend` conformance — a module could
    ;; reach into any subsystem it liked as long as it did so through a dispatch table.
    ;;
    ;; RELATIVIZED to `adopted` namespaces, exactly as public-unaccounted is: unrelativized it
    ;; would assert that every cross-module method in the project is declared, which is true only
    ;; of a fully adopted codebase. `adopted` is reached BY NAME through datalog injection — it is
    ;; declared in the Ns fragment, whose namespace requires THIS one and so cannot be required
    ;; back.
    ;;
    ;; `contains` needs no sort guard at either end: it reaches a Method or an `Fn` only from its
    ;; namespace, and `adopted` pins `?ns` the rest of the way.
    {:scope     :global
     :key       :correspondence/fulfilment-undeclared
     :offenders [?meth ?ns]
     :where     [(is ?meth Method) (contains ?ns ?meth) (adopted ?ns)
                 (fulfils ?meth ?fn) (contains ?owner ?fn) [(not= ?ns ?owner)]
                 (corresponds ?_op ?fn)
                 (not-join [?meth] (corresponds ?ful ?meth))]}))

;; ── the correspondence: the ENTIRE Fulfilment ↔ Method bridge ────────────────
;; Pairing = the declaration's two ends both reach this method: the satisfier Module twins with
;; the namespace that CONTAINS it, and the surface Operation twins with the function it FULFILS.
;;
;; ONE-TO-MANY on purpose, and the one place this pairing differs in kind from `Module ↦ Ns`
;; (which has two laws demanding it be one-to-one). A declaration says "this module supplies that
;; surface"; three dispatch values in that namespace are three methods realizing the one claim,
;; not an ambiguity to resolve. Multiplicity here is the intent, which is also why no dispatch
;; value is authorable — see the Fulfilment namespace.
(s/correspond [Fulfilment ?ful Method ?meth]
  [(satisfier ?ful ?m) (corresponds ?m ?ns) (contains ?ns ?meth)
   (surface ?ful ?op) (corresponds ?op ?fn) (fulfils ?meth ?fn)]
  {;; each design edge, realized by the fact path between the two witnesses: the satisfier is the
   ;; Module of the namespace that owns the method (containment, read backwards), the surface the
   ;; Operation of the function it fulfils.
   :satisfier [:inv :child]
   :surface   :fulfils})
