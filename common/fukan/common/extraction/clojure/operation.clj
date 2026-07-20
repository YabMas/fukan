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

;; Fn OWNS its public surface — the subtheory the design↦fact correspondence must be surjective ONTO.
;; A public Fn is an extracted function that is none of private (`defn-`) / `^:export` / `^:test-support`.
;; The correspondence names this predicate (`:surjective-onto :fn-public`) instead of reaching into Fn's
;; raw `:val/*` triples from the design side — the codomain owns which of its instances count.
(defrelation :fn-public
  "Fn's public surface: an extracted function that is not private, not ^:export, not ^:test-support."
  '[?x]
  '[[?x :structure/of :fukan.common.extraction.clojure.operation/Fn]
    (not [?x :val/private true]) (not [?x :val/export true]) (not [?x :val/test-support true])])

;; ── the correspondence: Operation ↦ Fn, as a theory morphism ─────────────────
;; Declared against the tags from outside (the external `(correspond …)` hook), so the vocabulary keeps
;; pure identity and this plugin keeps the Clojure knowledge. Every law is generated at
;; :corresponds/Operation.*. The block is exactly the morphism's components:
;;   · the OBJECT MAP — `:total` (every design Operation has a twin) + `:surjective-onto :fn-public`
;;     (every public extracted Fn has a design preimage; the subtheory is Fn's own public surface).
;;   · the RELATION MAPS — `(:delegates :sub …)` and `(:performs :sup …)` below.
;;   · the IDENTITY component (`in↦in`, `out↦out` over the shared `Schema` sort) is DERIVED, not authored
;;     (the `:corresponds/Operation.agrees` demand) — a morphism states only its non-identity maps, and
;;     the shared-sort slots agree for free (types content-dedup across strata). It also SUBSUMES the old
;;     `type-coverage`: `out↦out` FORWARD fails both on a differing out (old `adheres`) and a missing one.
(s/correspond Operation Fn
  :total
  :surjective-onto :fn-public
  ;; the relation-map primitive. delegates ⊑ the PUBLIC call graph — the roll-up `calls·(private·calls)*`
  ;; (Kleene-with-tests): a call path a→b through only PRIVATE interior (routing through another PUBLIC
  ;; op is TWO delegations, not one). PRESERVE only (:sub): every declared op-level delegation must be
  ;; realized by such a path. The REVERSE (fidelity — is every code coupling declared?) is an
  ;; ARCHITECTURAL question, already enforced one altitude up by Subsystem `:may-depend` conformance;
  ;; re-checking it op-level would redundantly re-flag every sanctioned kernel dependency.
  (:delegates :sub [:cat :calls [:* [:cat [:test :private] :calls]]])
  ;; performs ⊒ calls*·performs (reflect) — every effect the twin reaches over the code call graph
  ;; must be a declared design effect.
  (:performs :sup [:cat [:* :calls] :performs]))

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
