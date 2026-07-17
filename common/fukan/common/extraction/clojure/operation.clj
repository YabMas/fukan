(ns fukan.common.extraction.clojure.operation
  "Clojure grounding for the generic code `Operation` vocabulary — BOTH halves of the seam: the
   extraction that builds Operation facts from clj-kondo var-definitions, and the design↔Clojure
   CORRESPONDENCE those facts are checked against.

   The correspondence lives here, not with the vocabulary, because it is a map into Clojure's
   constructs: `:calls` is Clojure's call graph, and `:private`/`:export`/`:test-support` are `defn-`,
   `^:export` and `^:test-support` — metadata CONVENTIONS of this language, not design vocabulary. It
   sat in `vocab/code/operation.clj` until 2026-07-17, which made the shipped language-neutral tier
   export them to every consumer. A plugin owns its specialized vocabulary together with its
   mechanism; this is that vocabulary.

   The generic `Operation` structure — pure, language-neutral identity — lives in
   `fukan.common.vocab.code.operation`."
  (:require [fukan.canvas.core.substrate :as sub]
            [fukan.canvas.core.structure :as s]
            [fukan.common.vocab.code.effect :refer [Effect]]
            [fukan.common.vocab.code.operation :as operation :refer [Operation]]))

;; ── the design↔Clojure correspondence: fact-side slots + the drift demands ────
;; Declared against the Operation TAG from outside (the external `(correspond …)` registry hook), so
;; the vocabulary keeps pure identity and this plugin keeps the Clojure knowledge. Contributes the
;; extracted fact-slots + the twin (by name, nested within twinned Modules) + the drift demands,
;; generated as laws at the stable keys :corresponds/Operation.*. Each demand's `:desc` is its
;; human-facing name in check/drift output; a `:when`/`:require` guard reads at domain altitude
;; through the auto-generated `out` slot-rule (`(out ?t ?_o)` ⇔ the twin declares an :out type — i.e.
;; carries a fn-schema), not raw reified triples.
;;
;; NOTE this is still a map from `Operation` to ITSELF-with-extra-slots: the fact side is grafted onto
;; the design tag, so design Operation and Clojure function are one structure told apart by a
;; provenance flag. That graft is why the demands below have to be six bespoke forms rather than a
;; map's components — a morphism needs a codomain. See
;; docs/superpowers/specs/2026-07-17-correspondence-as-morphism-design.md (step 2: give it an `Fn`).

(s/correspond Operation
  {:calls        [:* {:transitive true} Operation]  ; the ACTUAL call graph (extraction's actuals); :transitive ⇒ calls+
   :private      [:? :boolean]      ; public/internal — the module's surface (from extraction)
   :export       [:? :boolean]      ; intentionally public for MECHANISM (^:export)
   :test-support [:? :boolean]}     ; intentionally public for TEST-SUPPORT (^:test-support)
  (realized {:desc "every authored operation is realized by an extracted operation of the same name in the corresponding module"})
  (realized {:key :type-coverage :require '[(out ?t ?_o)]
             :desc "every modelled operation's realizing code carries a type signature (:malli/schema)"})
  (covered  {:unless '[[?x :val/private true] [?x :val/export true] [?x :val/test-support true]]
             :desc "every public extracted operation is covered by the model or deliberately exempt"})
  (agrees   {:key :adheres :by :structural :over [:in :out] :when '[(out ?t ?_o)]  ; only where the twin declares a sig (else type-coverage's offence)
             :desc "every modelled operation's realizing code signature exactly adheres to its modelled type"})
  (delegates {:realized-by :calls :faithful true})  ; cross-module :delegates realized by a :calls+ path; :faithful ⇒ the module-level reverse
  (performs  {:covered-from [:calls* :performs]}))   ; every effect the twin reaches over :calls*·:performs is declared

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
  "Build an extracted Operation InstanceValue from a clj-kondo var-definition `v`, the set of effect
   keywords `effs` directly attributed to it, and `call-ids` — the natural-key ids of the Operations it
   calls (a `:calls` clause of `substrate/Ref`s, resolved by the assembler like an authored ref). When
   `v` carries a `:malli/schema` function-type, the signature is DECOMPOSED into `:in`/`:out` Schema
   subgraphs (the fact-side symmetric with the design side), built through the type dialect via
   `s/value-literal->iv` — the queryable form the adherence comparator reads (there is no `:val/sig`
   blob; both strata render through `operation-sig`). The Operation's own natural-key id (`\"ns/op\"`) is
   the root id the caller assigns."
  [v effs call-ids]
  (let [sig    (:malli/schema (:meta v))
        arrow? (and (vector? sig) (= :=> (first sig)) (= 3 (count sig)))
        {:keys [in out]} (when arrow? (code-arrow->in-out sig))]
    (sub/->InstanceValue ::operation/Operation (str (:name v)) nil
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
