(ns fukan.canvas.core.classification
  "The classification stratum: a query-time, derived-classification surface over
   the Node+Relation+tagapp substrate.

   A node's kind/role is reified as a tag-application (`:tagapp/node` →
   `:tagapp/tag`). This namespace exposes that classification as a reusable
   query surface so consumers stop hand-reading the denormalised `:entity/type`
   / `:affordance/role` index. It is **tag-agnostic** — it hardcodes no kinds,
   reading only substrate schema — which is why it sits in `core/` and is usable
   by every tier above it (construct-kit, vocab, projection, lens, inspect).

   Surface (all Datalog rules; the depth-0 case is also a fn):

     `direct-kind` — a node's immediate classification tag (depth-0): the value
                     `:affordance/role` carried for affordances, generalised to
                     every node (a Type's `:canvas/record`, a Module's
                     `:canvas/module`, …).
     `refines*`    — reflexive-transitive closure over `:tagdef/refines` (the
                     engine; rarely called directly).
     `kind-of`     — \"is-a, transitively\": a node is of kind ?k when its direct
                     tag refines* ?k. Replaces `[?e :entity/type :Affordance]`.
     `family-of`   — the node's `:family/*` super-tag ancestor (single-valued
                     given the partition invariant). Replaces `:entity/type`
                     enumeration/bucketing.

   The refinement lattice is data: each tag-definition's `:refines` parent (or,
   when unset, the super-tag derived from its construction family) is projected
   as a `:tagdef/refines` datom by canvas-source. A second classification costs
   one `:refines` line and zero consumer changes — `(kind-of ?e :ddd/entity)`."
  (:require [datascript.core :as d]))

;; ── Family super-tags (the lattice roots) ────────────────────────────────────

(def family-super-tags
  "Construction family → its super-tag (the refinement-lattice root). A node's
   family is the `:family/*` ancestor its kind-tag refines, transitively."
  {:Affordance :family/affordance
   :Type       :family/type
   :Module     :family/module})

(defn family->super-tag
  "The `:family/*` super-tag for a construction family keyword, or nil."
  [family]
  (get family-super-tags family))

(defn family-root?
  "True when `tag` is a family super-tag (namespace `family`)."
  [tag]
  (= "family" (some-> tag namespace)))

;; ── Datalog rules ──────────────────────────────────────────────────────────
;; Pass `rules` as the `%` input to d/q and reference the operators in :where:
;;   (d/q '[:find ?e :in $ % :where (kind-of ?e :family/affordance)] db rules)

(def rules
  "Datascript rule set for the classification stratum. Thread as the `%` input."
  '[[(direct-kind ?e ?tag)
     [?ta :tagapp/node ?e]
     [?ta :tagapp/tag ?tag]]
    ;; reflexive base: every registered tag (incl. family super-tags) refines* itself
    ;; (head vars must be distinct, so bind ?anc to ?t explicitly)
    [(refines* ?t ?anc)
     [?td :tagdef/tag ?t]
     [(identity ?t) ?anc]]
    ;; transitive step: ?t refines* ?anc when ?t's parent refines* ?anc
    [(refines* ?t ?anc)
     [?td :tagdef/tag ?t]
     [?td :tagdef/refines ?p]
     (refines* ?p ?anc)]
    ;; a node is of kind ?k when its direct tag refines* ?k
    [(kind-of ?e ?k)
     (direct-kind ?e ?t)
     (refines* ?t ?k)]
    ;; family-of: the node's :family/* ancestor
    [(family-of ?e ?fam)
     (kind-of ?e ?fam)
     [(namespace ?fam) ?ns]
     [(= ?ns "family")]]])

;; ── Per-entity lookup ────────────────────────────────────────────────────────

(defn direct-kind
  "The immediate classification tag of node `eid` (its tag-application tag), or
   nil when the node carries no tag-application. Single-valued: each node has
   one primary tag-application. Use the `direct-kind` rule (above) to embed the
   same relation inside a larger Datalog query."
  [db eid]
  (ffirst
   (d/q '[:find ?tag
          :in $ ?e
          :where [?ta :tagapp/node ?e]
                 [?ta :tagapp/tag ?tag]]
        db eid)))
