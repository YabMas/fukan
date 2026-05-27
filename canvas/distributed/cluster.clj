(ns canvas.distributed.cluster
  "Canvas — the cluster's essential state.

   A cluster is a known set of node identities that may collectively elect a
   leader for a given term. This module defines only what *must* be remembered:
   node identity, the current term, and (when one exists) the current leader's
   id. Everything else — vote tallies, election rounds, log replication — is
   derived structure expressed in sibling modules."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record value exports]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]
            [fukan.canvas.vocab.lifecycle :refer [getter]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "distributed.cluster"

      ;; -------------------------------------------------------------------
      ;; Essential state — what every node must remember about the cluster
      ;; -------------------------------------------------------------------

      (value "NodeId"
        "An opaque, stable identity for a cluster member. Distinct from the
         transport-layer address; survives restarts and rebinds.")

      (value "Term"
        "A monotonically increasing logical clock. Every leadership epoch
         carries a unique term. Comparable; never reused.")

      (record "Node"
        "A known member of the cluster."
        (field id   :NodeId)
        (field role :NodeRole))

      ;; A node is always exactly one of three roles. The sum is the carrier
      ;; of the role state-machine; transitions live in distributed.election.
      (value "NodeRole"
        "One of: Follower, Candidate, Leader. Each node holds exactly one role
         at any given moment in any given term.")

      (record "Cluster"
        "The cluster's view from one node. The set of members it knows about,
         the current term it has observed, and — when known — the id of the
         leader for that term."
        (field self          :NodeId)
        (field members       (set-of :NodeId))
        (field current_term  :Term)
        (field current_leader (optional :NodeId)))

      ;; -------------------------------------------------------------------
      ;; Behavioral commitments
      ;; -------------------------------------------------------------------

      (invariant "AtMostOneLeaderPerTerm"
        "Across the cluster, at most one node may hold the Leader role for
         any given Term. (Safety property — the heart of leader election.)"
        (holds-that "at-most-one leader per term"))

      (invariant "TermMonotonicity"
        "The current_term observed by any node never decreases. A node that
         learns of a higher term adopts it; a node that observes a lower
         term ignores or rejects the message."
        (holds-that "current_term is monotonically non-decreasing per node"))

      (invariant "MajorityRequiredForLeadership"
        "A node may not become Leader for a Term unless it has received vote
         grants from a strict majority of cluster members for that Term."
        (holds-that "leader holds majority for its term"))

      ;; -------------------------------------------------------------------
      ;; Read accessors over cluster state
      ;; -------------------------------------------------------------------

      (getter "get_current_term"
        "The most recent Term this node has observed."
        :Term)

      (getter "get_current_leader"
        "The id of the leader for the current term, if one is known."
        :NodeId)

      (getter "get_self_role"
        "This node's current role within the cluster."
        :NodeRole)

      ;; Membership lookup — parameterised, so it is a function rather than
      ;; a getter (getters are zero-arg by definition).
      (function "get_node"
        "Look up a known cluster member by id."
        (takes [id :NodeId])
        (gives (optional :Node)))

      (function "members"
        "Return all known cluster members."
        (takes [])
        (gives (set-of :Node)))

      (exports NodeId Term Node NodeRole Cluster))))
