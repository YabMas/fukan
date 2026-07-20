(ns fukan.common.extraction.clojure.operation
  "Clojure grounding for the code `Operation` vocabulary — the FACT theory (`Fn`), the extraction that
   builds it from clj-kondo var-definitions, and the design↔Clojure CORRESPONDENCE (`Operation ↦ Fn`).

   `Fn` is the codomain: the Clojure realization of an Operation — an extracted `defn`/`defn-`/`defmulti`
   with its call graph and its metadata conventions (`defn-`, `^:export`, `^:test-support`). It is a
   SPECIFIC language's construct, so it lives here, not in the language-neutral vocabulary. Before
   2026-07-17 there was no `Fn`: the fact-side slots were grafted onto the design `Operation` tag, so
   design Operation and Clojure function were one structure told apart by a provenance flag — and the
   drift demands had to be six bespoke forms because a morphism had no codomain to map INTO.

   The generic `Operation` structure — pure, language-neutral identity — lives in
   `fukan.common.vocab.code.operation`."
  (:require [fukan.canvas.core.substrate :as sub]
            [fukan.canvas.core.structure :as s :refer [defstructure defrelation]]
            [fukan.common.typing.malli :refer [Schema]]
            [fukan.common.vocab.code.effect :refer [Effect]]
            [fukan.common.vocab.code.operation :as operation :refer [Operation]]))

;; ── the FACT theory: a Clojure function ──────────────────────────────────────
(defstructure Fn
  "The Clojure realization of an Operation — an EXTRACTED function (`defn`/`defn-`/`defmulti`), stamped
   by the build. Carries the same input/output/effect shape as the design `Operation` (so the two agree
   by structure) PLUS Clojure's own constructs: the actual `:calls` graph (extraction's actuals — the
   design side authors `:delegates`, never this), and the metadata conventions that mark a public
   surface (`:private` ← `defn-`, `:export` ← `^:export`, `:test-support` ← `^:test-support`)."
  {:in           [:* Schema]                     ; input types — positional, ordered
   :out          [:? Schema]                     ; output type
   :performs     [:* Effect]                     ; the effects it performs (extracted)
   :calls        [:* {:transitive true} Fn]      ; the ACTUAL call graph; :transitive ⇒ calls+
   :private      [:? :boolean]                   ; public/internal — the module's surface
   :export       [:? :boolean]                   ; intentionally public for MECHANISM (^:export)
   :test-support [:? :boolean]})                 ; intentionally public for TEST-SUPPORT (^:test-support)

;; Fn OWNS its public surface — the sub-sort the design↦fact object map is surjective ONTO (the codomain
;; restriction `[Fn :public]`). A public Fn is an extracted function that is none of private (`defn-`) /
;; `^:export` / `^:test-support`. The codomain decides which of its instances count, rather than the
;; correspondence reaching into Fn's raw `:val/*` triples from the design side. This is the SAME
;; public/private line the delegates roll-up quotients over as interior — drawn once, here.
(defrelation :public
  "Fn's public surface: an extracted function that is not private, not ^:export, not ^:test-support."
  '[?x]
  '[[?x :structure/of :fukan.common.extraction.clojure.operation/Fn]
    (not [?x :val/private true]) (not [?x :val/export true]) (not [?x :val/test-support true])])

;; ── the correspondence: Operation ↦ Fn, one sort of the design→Clojure morphism ──
;; Rides Operation from OUTSIDE (its `defstructure` stays pure identity). Every law generates at
;; :corresponds/Operation.*.
;;   · OBJECT MAP: `Operation :eq [Fn :public]` — a bijection onto Fn's PUBLIC sub-sort (`:eq` ⇒ total,
;;     every design Operation twinned + surjective, every public Fn has a preimage). Private/export/
;;     test-support fns are NOT public, so neither sort images nor delegation boundaries.
;;   · RELATION MAPS: `:delegates ⊑` the public call graph — the roll-up `calls·(¬public·calls)*`: a call
;;     path a→b whose interior is all ¬PUBLIC (routing through another public boundary is two delegations,
;;     not one). `¬public` — the SAME `public` line the codomain restricts to, complemented — so an
;;     unmodelled `^:export`/`^:test-support` helper is interior, not a boundary. `:sub` only (fidelity is
;;     Subsystem `:may-depend`'s concern). `:performs ⊒` `calls*·performs` — every reached effect declared.
;;   · the IDENTITY component (`in↦in`, `out↦out` over the shared `Schema` sort) is DERIVED.
(s/correspond Operation :eq [Fn :public]
  (:delegates :sub [:cat :calls [:* [:cat [:test [:not :public]] :calls]]])
  (:performs  :sup [:cat [:* :calls] :performs]))

(def ^:private schema-tag
  "The type dialect's ^:value structure tag — the fact-side signature builds its :in/:out
   Schema subgraphs through it (via `s/value-literal->iv`, the one value-construction path)."
  :fukan.common.typing.malli/Schema)

(defn- code-arrow->in-out
  "Decompose an EXTRACTED code function-schema `[:=> INPUT OUTPUT]` into `{:in [type…] :out type}`.
   Unlike the authoring `arrow->in-out`, INPUT may be POSITIONAL `[:cat T…]` (code's convention —
   no param names) as well as named `[:catn [:n T]…]`; param names are dropped, since adherence
   compares argument TYPES and ORDER, not names."
  [form]
  (let [[_ input output] form
        in (case (first input)
             :cat  (vec (rest input))
             :catn (mapv second (rest input))
             [])]
    {:in in :out output}))

(def fn-defining
  "clj-kondo `:defined-by` values that denote a computation unit.
   `defn`/`defn-` are functions; `defmulti` is a POLYMORPHIC operation (a dispatch fn with a
   uniform signature its co-owned methods implement) — a concrete surface, not a split-ownership
   plug-point. `def`, `defmacro`, `defmethod`, etc. stay excluded."
  #{'clojure.core/defn 'clojure.core/defn- 'clojure.core/defmulti})

(defn extract-operation
  "Build an extracted `Fn` InstanceValue (the fact twin of a design Operation) from a clj-kondo
   var-definition `v`, the set of effect keywords `effs` directly attributed to it, and `call-ids` — the
   natural-key ids of the functions it calls (a `:calls` clause of `substrate/Ref`s, resolved by the
   assembler like an authored ref). When `v` carries a `:malli/schema` function-type, the signature is
   DECOMPOSED into `:in`/`:out` Schema subgraphs (symmetric with the design side), built through the type
   dialect via `s/value-literal->iv` — the queryable form the adherence comparator reads. The function's
   own natural-key id (`\"ns/op\"`) is the root id the caller assigns."
  [v effs call-ids]
  (let [sig    (:malli/schema (:meta v))
        arrow? (and (vector? sig) (= :=> (first sig)) (= 3 (count sig)))
        {:keys [in out]} (when arrow? (code-arrow->in-out sig))]
    (sub/->InstanceValue ::Fn (str (:name v)) nil
                         (cond-> {:val/private (boolean (:private v))}
                           (:export (:meta v))       (assoc :val/export true)
                           (:test-support (:meta v)) (assoc :val/test-support true))
                         (cond-> []
                           (seq effs)     (conj {:rk :performs :card :many
                                                 :targets (mapv (fn [eff] (Effect eff)) (sort effs))})
                           (seq in)       (conj {:rk :in :card :many
                                                 :targets (mapv #(s/value-literal->iv schema-tag %) in)})
                           arrow?         (conj {:rk :out :card :optional
                                                 :targets [(s/value-literal->iv schema-tag out)]})
                           (seq call-ids) (conj {:rk :calls :card :many
                                                 :targets (mapv sub/->Ref call-ids)}))
                         false)))
