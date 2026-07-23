(ns fukan.common.extraction.clojure.operation
  "Clojure grounding for the code `Operation` vocabulary — the FACT vocabulary (`Fn`), the extraction that
   builds it from clj-kondo var-definitions, and the design↔Clojure CORRESPONDENCE (`Operation ↦ Fn`).

   `Fn` is the codomain: the Clojure realization of an Operation — an extracted `defn`/`defn-`/`defmulti`
   with its call graph and its metadata conventions (`defn-`, `^:export`, `^:test-support`). It is a
   SPECIFIC language's construct, so it lives here, not in the language-neutral vocabulary.

   The correspondence is the essential `(correspond [Operation ?op Fn ?fn] match realization-map)`: the
   MATCH is a flat identity query (same name inside corresponding containers — publicness is a future
   LAW, not match logic), and the REALIZATION MAP names, per design slot, the pure fact-graph path that
   realizes it (`:in↦:in`, `:out↦:out`, delegation through non-public interior, effects to call-graph
   depth). Coverage — unrealized/unaccounted/drifted — is a READING of the pairing join (`drift`/
   `encapsulation`/`type-drift` in dev/user.clj), no longer a set of generated carrier/coverage/relation-map
   demand laws.

   The generic `Operation` structure — pure, language-neutral identity — lives in
   `fukan.common.vocab.code.operation`."
  (:require [fukan.canvas.core.substrate :as sub]
            [fukan.canvas.core.structure :as s :refer [defstructure defrelation]]
            [fukan.common.typing.malli :refer [Schema]]
            [fukan.common.vocab.code.effect :refer [Effect]]
            [fukan.common.vocab.code.operation :as operation :refer [Operation]]))

;; ── the FACT vocabulary: a Clojure function ──────────────────────────────────
(defstructure Fn
  "The Clojure realization of an Operation — an EXTRACTED function (`defn`/`defn-`/`defmulti`), stamped
   by the build. Carries the same input/output/effect shape as the design `Operation` (so the two agree
   by structure) PLUS Clojure's own constructs: the actual `:calls` graph (extraction's actuals — the
   design side authors `:delegates`, never this), and the metadata conventions that mark a public
   surface (`:private` ← `defn-`, `:export` ← `^:export`, `:test-support` ← `^:test-support`)."
  {:in           [:* Schema]                     ; input types — positional, ordered
   :out          [:? Schema]                     ; output type
   :performs     [:* Effect]                     ; the effects it performs (extracted)
   :calls        [:* Fn]                         ; the ACTUAL call graph (calls+ is the compiler's)
   :private      [:? :boolean]                   ; public/internal — the module's surface
   :export       [:? :boolean]                   ; intentionally public for MECHANISM (^:export)
   :test-support [:? :boolean]})                 ; intentionally public for TEST-SUPPORT (^:test-support)

;; Fn OWNS its public surface — the sub-sort onto which the design↔fact carrier is right-total (the codomain
;; restriction `[Fn :public]`). A public Fn is an extracted function that is none of private (`defn-`) /
;; `^:export` / `^:test-support`. The codomain decides which of its instances count, rather than the
;; correspondence reaching into Fn's raw `:val/*` triples from the design side. This is the SAME
;; public/private line the delegates roll-up quotients over as interior — drawn once, here.
(defrelation :public
  "Fn's public surface: an extracted function that is not private, not ^:export, not ^:test-support."
  [?x]
  [(is ?x Fn)
   (not [?x :val/private true]) (not [?x :val/export true]) (not [?x :val/test-support true])])

;; ── the correspondence: the ENTIRE Operation ↔ Fn bridge ─────────────────────
;; Pairing = same name inside corresponding containers (pure identity logic; publicness is
;; POLICY — a future law — not match logic: an op realized by a private fn PAIRS, and
;; realized-but-private is that law's precise finding). Entries are pure code-graph paths:
;; identity = the same-named atom; delegation = a call reaching through non-public interior;
;; performing = every effect the code reaches. Coverage (unrealized/unaccounted/ambiguous)
;; is a READING of the pairing join — `(drift)` reports it; no law fires until authored.
(s/correspond [Operation ?op Fn ?fn]
  [(named ?op ?n) (named ?fn ?n)
   (contains ?m ?op) (contains ?ns ?fn)
   (corresponds ?m ?ns)]
  {:in        :in
   :out       :out
   :performs  [:cat [:* :calls] :performs]
   :delegates [:cat :calls [:* [:cat [:not public] :calls]]]})

(def ^:private schema-tag
  "The type dialect's ^:value structure tag — the fact-side fragment builds its :in/:out
   Schema subgraphs through it (via `s/value-literal->iv`, the one value-construction path)."
  :fukan.common.typing.malli/Schema)

(def ^:private effect-tag
  "The Effect value tag, used through the same canonical value-construction path as Schema."
  :fukan.common.vocab.code.effect/Effect)

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
                                                 :targets (mapv #(s/value-literal->iv effect-tag %)
                                                                (sort effs))})
                           (seq in)       (conj {:rk :in :card :many
                                                 :targets (mapv #(s/value-literal->iv schema-tag %) in)})
                           arrow?         (conj {:rk :out :card :optional
                                                 :targets [(s/value-literal->iv schema-tag out)]})
                           (seq call-ids) (conj {:rk :calls :card :many
                                                 :targets (mapv sub/->Ref call-ids)}))
                         false)))
