(ns fukan.common.vocab.patterns.fulfilment
  "Pattern vocab — `Fulfilment`: the SUPPLY half of a call.

   Every call splits in two. DEMAND is what a call site does — it names a surface — and it is
   UNIFORM: `Operation :delegates` and `Fn :calls` stay polymorphism-blind, so nothing that reads
   either learns what a dispatch point is. SUPPLY is where all of polymorphism lives, and this is
   its one word: a satisfier FULFILS a surface. A plain `defn` is the degenerate case — one
   fulfilment, selected by identity — which is why non-polymorphic code needs none of this and
   models exactly as it did before the word existed.

   The satisfier is a MODULE, not an Operation. A method is not a public surface of the module
   that writes it — nobody calls it by name — so modelling one as an Operation would contradict
   surface-only modelling (a modelled Operation must have a public code correspondent). What the
   design has to say is WHO supplies WHAT, and a module is the smallest thing that can say it.

   The declaration says that and stops. It cannot name a discriminator, deliberately: the surface's
   signature is the contract, and enumerating dispatch values as intent would restate the dispatch
   table at vocabulary altitude, where it would immediately start to drift. The discriminator rides
   the EXTRACTED edge (as `:rel/label`), where it is a fact rather than a claim.

   WHAT A `fulfils` EDGE ASSERTS — that the satisfier supplies the surface, and nothing more. It
   does NOT assert adherence: no law compares a satisfier's shape to the surface's signature,
   because for the one grounding that exists there is no shape to compare. clj-kondo emits neither
   a var-definition nor an arglist for a `defmethod`, and a method has no contract of its own —
   the surface's signature is what every method is presumed to meet, which is what makes it ONE
   surface. Adherence becomes a real question only for registration-style satisfiers, where the
   registered value is a var that can carry an annotation, and it belongs with that grounding.

   ITS GROUNDINGS — one, DISPATCH: a `defmethod` supplies the multimethod it implements
   (`fukan.common.extraction.clojure.method`). The word is shaped so a second grounding feeds this
   same relation rather than redefining it, but registration-style satisfaction of a `PlugPoint`
   is NOT recognised: nothing marks a function as a registration function, so the linkage cannot
   be found. Nothing here or downstream therefore reads a PlugPoint as satisfied or unsatisfied —
   with one grounding shipped, such a verdict would be a claim the extractor cannot support.

   THE PATTERN TIER (`vocab/patterns/`) — one rung above the core code grammar. The dependency
   points strictly UPWARD: this pattern names its participants (`:satisfier`, `:surface`), and the
   core never names the pattern — `Module` carries no `:satisfies` slot and does not require this
   namespace. That is also why a Fulfilment is a STANDALONE node rather than a slot anywhere: on
   Module it would break that closure, and on the surface's owner it would be the owner naming its
   satisfiers, which is exactly the dependency this word exists to invert."
  (:require [fukan.canvas.core.structure :refer [defstructure defrelation]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]))

(defstructure Fulfilment
  "A declaration that a Module supplies a surface it does not own — its `:satisfier` is the module
   that implements, its `:surface` the Operation implemented. Both are constitutive (cardinality
   one): a fulfilment naming only one end states nothing, so the generated presence laws are the
   teeth. No discriminator is authorable — see the namespace docstring."
  {:satisfier Module
   :surface   Operation})

;; `fulfils` is ONE relation across BOTH strata, the way `:child` already is. Here it is DEFINED
;; from the two slots (`:sup` — an OPEN head), so the design side reads Module → Operation; the
;; fact side contributes its own edges (a satisfier node → the surface's function) through a slot
;; of the same name, and a law or graph clause that says `fulfils` sees whichever stratum it has
;; pinned by sort. A DERIVED declaration would close the head and lock the fact side out.
(defrelation :fulfils
  "Satisfier ?a fulfils surface ?b — the domain-altitude reading of a Fulfilment's two ends."
  (:sup [:cat [:inv :satisfier] :surface]))
