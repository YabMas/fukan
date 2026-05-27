(ns canvas.distributed.election
  "Canvas — the leader-election protocol.

   Describes how a cluster transitions from a leaderless state (or a stale
   leadership) into a new term with a single elected leader. State and
   identities live in distributed.cluster; this module owns the reactive
   protocol — the events exchanged and the handlers that drive role
   transitions.

   The protocol, in one sentence: when a Follower's heartbeat expires it
   becomes a Candidate for a new Term, requests votes from the cluster, and
   — on receiving a strict majority of grants — becomes Leader and begins
   issuing heartbeats. A higher Term observed at any moment demotes the
   observer to Follower."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [record exports]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]
            [fukan.canvas.vocab.event :refer [event handler]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "distributed.election"

      ;; -------------------------------------------------------------------
      ;; Protocol-level records
      ;; -------------------------------------------------------------------

      (record "Vote"
        "A single vote granted by one node to one candidate for one term.
         Votes are per-term and per-voter: a node grants at most one Vote
         in any given term."
        (field term      :cluster/Term)
        (field voter     :cluster/NodeId)
        (field candidate :cluster/NodeId))

      (record "ElectionRound"
        "A single attempt by one candidate to win one term. Carries the
         candidate's identity, the contested term, and the tally of grants
         observed so far. An election round is local to the candidate."
        (field term       :cluster/Term)
        (field candidate  :cluster/NodeId)
        (field grants     (set-of :cluster/NodeId)))

      ;; -------------------------------------------------------------------
      ;; Protocol events — the wire vocabulary of leader election
      ;; -------------------------------------------------------------------

      (event "HeartbeatExpired"
        "The follower has not received a heartbeat from the current leader
         within the election timeout. Marker event — no payload; the local
         node knows its own context."
        (payload [node :cluster/NodeId]))

      (event "ElectionStarted"
        "A Follower has transitioned to Candidate for a new term and begun
         soliciting votes."
        (payload [term      :cluster/Term]
                 [candidate :cluster/NodeId]))

      (event "VoteRequested"
        "A candidate is asking one peer to grant its vote for a term."
        (payload [term      :cluster/Term]
                 [candidate :cluster/NodeId]
                 [from      :cluster/NodeId]))

      (event "VoteGranted"
        "A peer has granted its vote to the candidate for the term."
        (payload [term      :cluster/Term]
                 [candidate :cluster/NodeId]
                 [voter     :cluster/NodeId]))

      (event "VoteDenied"
        "A peer has refused its vote — either it has already voted this term,
         or it has observed a term higher than the candidate's."
        (payload [term      :cluster/Term]
                 [candidate :cluster/NodeId]
                 [voter     :cluster/NodeId]
                 [reason    :String]))

      (event "LeaderElected"
        "A candidate has received a strict majority of grants and has
         transitioned to Leader for the term."
        (payload [term   :cluster/Term]
                 [leader :cluster/NodeId]))

      (event "HeartbeatReceived"
        "A node has observed a heartbeat from a leader for some term.
         If the term is at-or-above the observer's current term, the
         observer adopts the term and remains Follower."
        (payload [term   :cluster/Term]
                 [leader :cluster/NodeId]))

      ;; -------------------------------------------------------------------
      ;; Handlers — the reactive protocol
      ;; -------------------------------------------------------------------

      (handler "on_heartbeat_expired"
        "A follower whose heartbeat has expired increments its term, votes
         for itself, and begins soliciting votes from peers."
        (on :election/HeartbeatExpired)
        (emits :election/ElectionStarted)
        (emits :election/VoteRequested))

      (handler "on_vote_requested"
        "A peer evaluates an incoming vote request and replies with a grant
         or a denial. A grant is issued only if the requester's term is at
         least as high as the observer's, and the observer has not already
         voted in that term."
        (on :election/VoteRequested)
        (emits :election/VoteGranted)
        (emits :election/VoteDenied))

      (handler "on_vote_granted"
        "A candidate accumulates grants and — on reaching a strict majority
         of cluster members — transitions to Leader."
        (on :election/VoteGranted)
        (emits :election/LeaderElected))

      (handler "on_heartbeat_received"
        "Any node receiving a valid heartbeat resets its election timeout
         and (if the heartbeat carries a higher term) demotes itself to
         Follower of that leader."
        (on :election/HeartbeatReceived))

      ;; -------------------------------------------------------------------
      ;; Safety invariants
      ;; -------------------------------------------------------------------

      (invariant "OneVotePerVoterPerTerm"
        "In any given Term, any voter casts at most one Vote. (Necessary
         condition for AtMostOneLeaderPerTerm in distributed.cluster.)"
        (holds-that "at-most-one vote per voter per term"))

      (invariant "VoteImpliesTermAcknowledgement"
        "Casting a vote for Term T implies the voter has observed Term T
         and updated its current_term to T (or higher)."
        (holds-that "voting acknowledges the contested term"))

      (invariant "ElectionRequiresStrictMajority"
        "A candidate transitions to Leader for Term T only after observing
         grants from strictly more than half of cluster members for T.
         A simple majority — i.e. exactly half — is insufficient."
        (holds-that "strict-majority required for leadership"))

      (exports Vote ElectionRound))))
