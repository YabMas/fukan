(ns fukan.common.vocab.code.operation
  "Code vocab — the `Operation` element: a named unit of computation, AND its own model↔code
   correspondence (the fact-side slots, the twin, and the drift demands — all generated).

   An Operation's IDENTITY is the authored intent alone — its input/output types, the effects it
   performs, and its designed dependencies. Its CORRESPONDENCE — how an authored Operation is paired
   with the extracted code twin, and what the code must realize/cover/adhere-to — is declared here
   too (via `(s/correspond Operation …)`), the complete story of the one element in one file. Every
   drift check is GENERATED — Operation hand-writes no correspondence mechanism. The only thing NOT
   here is implementer-directed prose, which rides the kernel `:guidance` annotation.

   The generated realization law reaches through the `:calls+` closure and the kernel `twin` rule
   (which pairs an authored Operation with its extracted code twin by name WITHIN twinned Modules,
   bridged by Module's `:qualified-suffix` name-match) — so this namespace needs no dependency on
   Module (Module requires Operation, not the reverse)."
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

;; ── the correspondence: the fact-side slots + the model↔code demands ──────────
;; Declared from OUTSIDE the defstructure so the identity above stays clean, but IN this file — the one
;; element, its whole story. `(s/correspond Operation …)` contributes the extracted fact-slots (:calls,
;; :private, …), the twin (`:by-name`, nested within twinned Modules), and the drift demands (generated
;; as laws at the stable keys :corresponds/Operation.*). The concept's defstructure mentions none of it.

(s/correspond Operation :by-name
  {:calls     [:* {:transitive true} Operation]  ; the ACTUAL call graph (extraction's actuals); :transitive ⇒ calls+
   :private   [:? :boolean]          ; public/internal — the module's surface (from extraction)
   :export    [:? :boolean]          ; intentionally public for MECHANISM (^:export)
   :test-support [:? :boolean]}      ; intentionally public for TEST-SUPPORT (^:test-support)
  ;; ex-Realization — vacuity-guarded: ∃ extracted Operation
  (realized {:desc "every authored operation is realized by an extracted operation of the same name in the corresponding module"})
  ;; ex-TypeCoverage — a modelled op's realizing code carries a :malli/schema
  (realized {:key :type-coverage
             :desc "every modelled operation's realizing code carries a type signature (:malli/schema)"
             :require '[[?tr :rel/from ?t] [?tr :rel/kind :out]]})   ; the twin declares an :out ⇔ it carries a fn-schema
  ;; ex-Encapsulation — the exemption flags are VOCAB's (the kernel never names them)
  (covered  {:desc "every public extracted operation is covered by the model or deliberately exempt"
             :unless '[[?x :val/private true] [?x :val/export true] [?x :val/test-support true]]})
  ;; GATED signature adherence (exact match): wherever the twin declares a signature (an :out), the
  ;; modelled signature must EXACTLY adhere — the design op and its twin have IDENTICAL :in/:out targets
  ;; (kernel `:structural` agreement: types content-dedup across strata, so eid identity = same types,
  ;; order, and arity). A missing sig is type-coverage's offence — hence the :when guard.
  (agrees   {:key :adheres :by :structural :over [:in :out]
             :desc "every modelled operation's realizing code signature exactly adheres to its modelled type"
             :when '[[?tr :rel/from ?t] [?tr :rel/kind :out]]})
  ;; relation demands ABOUT Operation's own identity relations (:delegates / :performs):
  ;; :delegates — every cross-module design delegation is REALIZED op-level: the design endpoints'
  ;; twins reach each other through the actual `:calls+` graph (transitive; `:calls` is a :transitive
  ;; fact-slot). :faithful adds the reverse at module altitude (a code coupling between twinned modules
  ;; must be declared). :performs — every effect the twin reaches over :calls*·:performs is declared.
  (delegates {:realized-by :calls :faithful true})
  (performs  {:covered-from [:calls* :performs]}))

;; Operation hand-writes NO correspondence code: the fact-slots, twin, and every drift check
;; (realized / type-coverage / covered / adheres / delegates-realized / delegates-faithful /
;; performs-covered) are GENERATED from the `(correspond Operation …)` declaration above. A caller
;; wanting any of them as a worklist names the stable law key through `law/violation-names` (see
;; `dev/user.clj`).
