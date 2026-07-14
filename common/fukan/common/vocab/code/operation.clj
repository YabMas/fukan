(ns fukan.common.vocab.code.operation
  "Code vocab — the `Operation` element: a named unit of computation.

   An Operation is PURE IDENTITY: the authored intent alone — its input/output types, the effects it
   performs, and its designed dependencies. Everything else ABOUT an Operation is hooked in from OUTSIDE
   so the concept stays clean: model↔code correspondence (the fact-side slots, the twin, the drift
   demands) lives in `fukan.common.vocab.code.module`; implementer-directed prose in the kernel
   `:guidance` annotation. This namespace holds only the identity `defstructure` and its authoring sugar."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.common.typing.malli :as ct :refer [Schema]]
            [fukan.common.vocab.code.effect :refer [Effect]]))

(defstructure Operation
  "A named unit of computation, either AUTHORED (a self-model's intent) or EXTRACTED from code (the fact
   stratum, stamped by the build at the merge). The two are DISTINCT nodes: a design Operation
   corresponds 1-on-1 to its extracted twin by name within twinned Modules (`:by-name`, nested), so
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

;; The correspondence demands (declared in `fukan.common.vocab.code.module`, generated as laws) are read at
;; their stable keys — e.g. `(violation-names db :corresponds/Operation.realized)` for unrealized intent,
;; `.covered` for uncovered code. Adherence (`.adheres`) is STRUCTURAL: the `:signature` comparator
;; compares a design op's and its twin's decomposed :in/:out node identities — types content-dedup across
;; strata — so type, argument ORDER, and ARITY are all checked by sequence identity.
