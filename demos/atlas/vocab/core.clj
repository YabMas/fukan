(ns demos.atlas.vocab.core
  "An architecture-semantics vocabulary re-expressing the Semantic Namespace Atlas
   ontology (tangrammer, 'on the clojure move') directly on defstructure.

   Atlas registers each system entity with a COMPOUND SEMANTIC IDENTITY:

     (registry/register!
       :fn/validate-token              ; dev-id
       :atlas/execution-function       ; entity TYPE
       #{:domain/auth                  ; an open SET of orthogonal aspects
         :operation/validate
         :tier/service}
       {:execution-function/deps #{:component/oauth-provider}})

   and validates it with executable INVARIANTS (tier boundaries, dependency
   legality). The three Atlas parts map onto fukan as: entity-type → a structure
   tag; deps → a relation slot; invariants → laws. The aspect SET is the only
   part that genuinely pushes on the core, and this vocab models it BOTH ways so
   the trade is a proven artifact rather than a claim.

   ── style A — AXIS-AS-SLOT (ExecutionFunction) ─────────────────────────────────
   Each aspect axis becomes a named, typed slot. Atlas's flagship invariant —
   dependency legality across tiers — is then just a recursive law, the same
   shape as the RBAC demo's inheritance closure. Each axis is cardinality-bounded
   for free (one tier per function is a slot cardinality, not a law). The price:
   the schema is CLOSED — a new axis (integration-style, effect) edits the struct.

   ── style B — ASPECT-AS-DATA (Faceted) ─────────────────────────────────────────
   The aspect itself is reified, carrying its axis, and an entity holds one flat
   open :aspects set — faithful to Atlas, and extensible with zero schema change.
   The price is the mirror image: 'one aspect per axis', which style A got from a
   slot cardinality, must be RE-DERIVED as a law.

   FINDING. Faceting is not a fukan gap — it is a clunky-but-expressible ergonomics
   choice, and the core strictly dominates Atlas here: it can pick either style and
   the trade-off is visible in the model instead of baked into the framework (Atlas
   only offers the flat set, and so cannot state 'exactly one tier' structurally at
   all). What fukan lacks is a first-class FACET marker distinguishing orthogonal
   classification axes from structural composition — style A scatters axes across
   ordinary slots with nothing saying they're a different KIND of slot. That is the
   itch the parked theory-composition `(includes Facet)` idea reaches for; Atlas is
   independent evidence the concept earns its keep, but as a vocabulary convenience,
   not a core primitive."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

;; ── style A: axis-as-slot ────────────────────────────────────────────────────

(defstructure Domain
  "A concern axis — auth, users, storage.")

(defstructure Operation
  "An operation axis — validate, fetch, handle.")

(defstructure Tier
  "A layering axis. `:over` names the tier directly beneath this one, so the
   chain foundation ← service ← api expresses the legal direction of dependency."
  (slot :over (optional Tier)))

(defstructure ExecutionFunction
  "Atlas's :atlas/execution-function. Its compound identity is its (domain, tier,
   operation) facets; `:deps` are the other functions it calls."
  (slot :domain    (one  Domain))
  (slot :tier      (one  Tier))
  (slot :operation (one  Operation))
  (slot :deps      (many ExecutionFunction))

  ;; Dependency legality across tiers — Atlas's flagship invariant. A function may
  ;; not depend UPWARD: a service function must not call an api function. `over*`
  ;; is the transitive closure of the DIRECT :over relation, step INLINED (a
  ;; recursive rule may not call a helper rule — datascript diverges otherwise).
  ;; A dep f → g is illegal exactly when g's tier sits over f's tier.
  (law "no execution-function depends on a higher tier"
    :rules '[[(over* ?hi ?lo)
              [?r :rel/from ?hi] [?r :rel/kind :over] [?r :rel/to ?lo]]
             [(over* ?hi ?lo)
              [?r :rel/from ?hi] [?r :rel/kind :over] [?r :rel/to ?m]
              (over* ?m ?lo)]]
    :scope :ExecutionFunction
    :offenders '[?f ?g]
    :where '[[?d  :rel/from ?f] [?d  :rel/kind :deps] [?d  :rel/to ?g]
             [?tf :rel/from ?f] [?tf :rel/kind :tier] [?tf :rel/to ?ft]
             [?tg :rel/from ?g] [?tg :rel/kind :tier] [?tg :rel/to ?gt]
             (over* ?gt ?ft)]))

;; ── style B: aspect-as-data (the open compound identity, faithfully) ──────────

(defstructure Axis
  "A dimension of meaning — domain, tier, operation, integration-style, ….")

(defstructure Aspect
  "A single point on one axis — `auth` on the domain axis, `service` on tier."
  (slot :axis (one Axis)))

(defstructure Faceted
  "Atlas's open compound identity verbatim: one flat `:aspects` set instead of
   named axis slots, extensible with no schema change. The cost is the law below —
   'one aspect per axis', which style A got for free from a slot cardinality."
  (slot :aspects (many Aspect))
  (slot :deps    (many Faceted))

  (law "an entity carries at most one aspect per axis"
    :scope :Faceted
    :offenders '[?e ?a1 ?a2]
    :where '[[?r1 :rel/from ?e]  [?r1 :rel/kind :aspects] [?r1 :rel/to ?a1]
             [?r2 :rel/from ?e]  [?r2 :rel/kind :aspects] [?r2 :rel/to ?a2]
             [?x1 :rel/from ?a1] [?x1 :rel/kind :axis] [?x1 :rel/to ?ax]
             [?x2 :rel/from ?a2] [?x2 :rel/kind :axis] [?x2 :rel/to ?ax]
             [(not= ?a1 ?a2)]]))
