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
            [fukan.cozo.query :as cq]
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
   :test-support [:? :boolean]}                  ; intentionally public for TEST-SUPPORT (^:test-support)

  ;; ── the correspondence's TEETH, at the OPERATION altitude ───────────────────────────────────
  ;; Rides `Fn` for the reason the root law rides `Ns`: `correspond` lowers exclusively to rules,
  ;; so a denial about a correspondence rides the codomain declared beside it. See the note on
  ;; `Ns` in fukan.common.extraction.clojure.module.
  (law "every modelled Operation in a realized Module is realized by a function"
    ;; This is `drift` — until now a READING nothing enforced. The model could claim an Operation
    ;; that no function realized and every gate stayed green, which made an adopted module's
    ;; specification decorative: rename the function and nothing anywhere said so.
    ;;
    ;; SCOPED to Operations whose owning Module pairs, which is both the gate and the altitude
    ;; rule. As a gate it is exact — a build with no code root pairs no Module, so the law is
    ;; vacuous without naming extraction at all. As an altitude rule it keeps one cause to one
    ;; finding: when a whole module fails to pair, the module-level law names the module rather
    ;; than this one naming every operation inside it.
    ;;
    ;; The owning Module needs no sort guard. `contains` reaches an Operation only from its
    ;; Module (a Subsystem holds Modules, not Operations), and `corresponds` pins it the rest of
    ;; the way — which also spares this namespace from naming `Module`, whose namespace requires
    ;; THIS one and so cannot be required back.
    {:scope     :global
     :key       :correspondence/operation-unrealized
     :offenders [?op ?m]
     :where     [(is ?op Operation) (design ?op)
                 (contains ?m ?op) (corresponds ?m ?_ns)
                 (not-join [?op] (corresponds ?op ?fn))]})

  (law "every public function in an adopted namespace is modelled by an Operation"
    ;; The law above says the design claims nothing the code lacks; this one says the code
    ;; exposes nothing the design has not claimed. Together they are what makes a module's
    ;; specification its surface rather than a subset of it — without this half, adopting a
    ;; namespace and modelling three of its ten public functions reads as a complete claim.
    ;;
    ;; RELATIVIZED to `adopted` namespaces — the Ns half of a live pairing. Unrelativized this
    ;; asserts total coverage of the project's public surface, which is true only of a fully
    ;; adopted codebase and is the premise incremental adoption denies. Scoped, it says: within
    ;; the region the model has claimed, this much surface is unaccounted for. The UNCLAIMED half
    ;; is not a gap at all — it is `adoption-frontier`'s business.
    ;;
    ;; `public` is POLICY and appears only here and in the delegation path, never in match logic:
    ;; an Operation realized by a PRIVATE function still pairs, so it is not an offender of this
    ;; law, and the two ways out of a finding are the two honest ones — model the function as
    ;; intent, or make it `defn-`.
    {:scope     :global
     :key       :correspondence/public-unaccounted
     :offenders [?fn ?ns]
     :where     [(public ?fn) (contains ?ns ?fn) (adopted ?ns)
                 (not-join [?fn] (corresponds ?op ?fn))]}))

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

(defn unaccounted-public
  "The ENCAPSULATION coverage gap: PUBLIC extracted functions inside an ADOPTED namespace that no
   authored Operation models. Each is an undeclared public surface demanding a decision — model it as
   intent, or make it `defn-`.

   Relativized to `adopted` namespaces (the Ns half of a live `Module ↦ Ns` pairing, declared in
   fukan.common.extraction.clojure.module and reached here by NAME through datalog injection, so the
   Module fragment need not be required back). Un-relativized, this reading asserts TOTAL coverage —
   every public function in the project should be modelled — which is true only of a fully-adopted
   codebase and is the premise incremental adoption denies. Scoped, it means: within the region the
   model has claimed, this much of the public surface is unaccounted for. Empty ⇔ every unmodelled
   function in the claimed region is genuinely private. The private half of the gap is settled by
   definition; the UNCLAIMED half is `adoption-frontier`'s business, not a gap at all.

   A READING of the `corresponds` join via the `public` predicate — `public` is policy, never match
   logic, so an Operation realized by a private fn still pairs."
  [db]
  (set (cq/q '[:find [?n ...] :in $ %
               :where (public ?fn) (contains ?ns ?fn) (adopted ?ns)
                      (not-join [?fn] (corresponds ?_op ?fn))
                      [?fn :entity/name ?n]]
             db (s/vocab-rules))))

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
