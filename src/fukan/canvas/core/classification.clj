(ns fukan.canvas.core.classification
  "The classification stratum: a query-time, derived-classification surface over
   the Node+Relation+tagapp substrate.

   A node's kind/role is reified as a tag-application (`:tagapp/node` →
   `:tagapp/tag`). This namespace exposes that classification as a reusable
   query surface so consumers stop hand-reading the denormalised `:entity/type`
   / `:affordance/role` index. It is **tag-agnostic** — it hardcodes no kinds,
   reading only substrate schema — which is why it sits in `core/` and is usable
   by every tier above it (construct-kit, vocab, projection, lens, inspect).

   Phase 1 surface:

     `direct-kind` — a node's immediate classification tag (depth-0): the value
                     `:affordance/role` carried for affordances, generalised to
                     every node (a Type's `:canvas/record`, a Module's
                     `:canvas/module`, …). Available as a Datalog rule (for
                     embedding in `:where` clauses) and as a fn (for per-entity
                     lookup).

   Later strata (kind-of / family-of via `refines`) layer transitive
   classification on top; they read `:tagdef/*` reference datoms and live here
   too — still tag-agnostic, still query-time."
  (:require [datascript.core :as d]))

;; ── Datalog rules ──────────────────────────────────────────────────────────
;; Pass `rules` as the `%` input to d/q and reference the operators in :where:
;;   (d/q '[:find ?e :in $ % :where (direct-kind ?e :canvas/getter)] db rules)

(def rules
  "Datascript rule set for the classification stratum. Thread as the `%` input."
  '[[(direct-kind ?e ?tag)
     [?ta :tagapp/node ?e]
     [?ta :tagapp/tag ?tag]]])

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
