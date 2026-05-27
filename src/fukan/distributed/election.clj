(ns fukan.distributed.election
  "Implementation surface for canvas.distributed.election — partial.

   Phase 6 Sprint 4 trial-run scope. Stubs throw; payloads are Malli
   schemas. Canvas declares the reactive protocol; this file projects
   a representative slice and deliberately leaves four declarations
   absent (3 events, 2 handlers) for drift evidence.

   Canvas coverage in this file:
     Records  — Vote, ElectionRound                      (both)
     Events   — HeartbeatExpired, ElectionStarted,
                VoteRequested, VoteGranted               (4 of 7;
                VoteDenied, LeaderElected,
                HeartbeatReceived intentionally absent)
     Handlers — on-heartbeat-expired,
                on-vote-requested                        (2 of 4;
                on-vote-granted, on-heartbeat-received
                intentionally absent)
     Invariants — none implemented; property-test material."
  (:require [fukan.distributed.cluster :as cluster]))

;; --------------------------------------------------------------------------
;; Protocol-level records
;; --------------------------------------------------------------------------

(def Vote
  "A single vote granted by one node to one candidate for one term."
  [:map
   [:term cluster/Term]
   [:voter cluster/NodeId]
   [:candidate cluster/NodeId]])

(def ElectionRound
  "A single attempt by one candidate to win one term."
  [:map
   [:term cluster/Term]
   [:candidate cluster/NodeId]
   [:grants [:set cluster/NodeId]]])

;; --------------------------------------------------------------------------
;; Events — Malli schemas for the payloads. Events are messages on the
;; wire; the schema is the contract for the payload's shape.
;;
;; Trial scope: 4 of 7 events. The omitted three (VoteDenied,
;; LeaderElected, HeartbeatReceived) are deliberate drift evidence.
;; --------------------------------------------------------------------------

(def HeartbeatExpired
  "The follower has not received a heartbeat within the election timeout."
  [:map
   [:node cluster/NodeId]])

(def ElectionStarted
  "A Follower has transitioned to Candidate for a new term."
  [:map
   [:term cluster/Term]
   [:candidate cluster/NodeId]])

(def VoteRequested
  "A candidate is asking one peer to grant its vote for a term."
  [:map
   [:term cluster/Term]
   [:candidate cluster/NodeId]
   [:from cluster/NodeId]])

(def VoteGranted
  "A peer has granted its vote to the candidate for the term."
  [:map
   [:term cluster/Term]
   [:candidate cluster/NodeId]
   [:voter cluster/NodeId]])

;; --------------------------------------------------------------------------
;; Handlers — reactive entry points. Stubs throw; only signature and
;; presence matter for the drift signal.
;; --------------------------------------------------------------------------

(defn on-heartbeat-expired
  "A follower whose heartbeat has expired increments its term, votes
   for itself, and begins soliciting votes from peers."
  [event-payload cluster-state]
  (throw (ex-info "Not implemented"
                  {:context :phase6-trial
                   :handler 'on-heartbeat-expired
                   :event :election/HeartbeatExpired
                   :received {:event-payload event-payload
                              :cluster-state cluster-state}})))

(defn on-vote-requested
  "A peer evaluates an incoming vote request and replies with a grant
   or a denial."
  [event-payload cluster-state]
  (throw (ex-info "Not implemented"
                  {:context :phase6-trial
                   :handler 'on-vote-requested
                   :event :election/VoteRequested
                   :received {:event-payload event-payload
                              :cluster-state cluster-state}})))

;; on-vote-granted and on-heartbeat-received intentionally omitted —
;; drift evidence. Together with the three absent events, drift should
;; surface 5 missing-implementation findings post-reset.
