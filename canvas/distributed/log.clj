(ns canvas.distributed.log
  "Canvas — the replicated log.

   Once a leader is elected (see distributed.election), it accepts client
   commands and replicates them as ordered log entries to followers. A log
   entry that has been acknowledged by a strict majority of nodes is
   committed; committed entries are applied to the local state machine in
   index order.

   This module declares the entry shape, the replication events the leader
   exchanges with followers, and the invariants that protect log safety.
   The state machine that consumes committed entries is outside the scope
   of this canvas — it is application-specific."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record value exports]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]
            [fukan.canvas.vocab.event :refer [event handler]]
            [fukan.canvas.vocab.lifecycle :refer [getter]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "distributed.log"

      ;; -------------------------------------------------------------------
      ;; Essential state — the log and its commit horizon
      ;; -------------------------------------------------------------------

      (value "LogIndex"
        "A 1-based position in the replicated log. Monotonically assigned
         by the leader; never reused, never reordered. Comparable.")

      (value "Command"
        "An opaque application-level command — the payload of a log entry.
         The log treats commands as values; their interpretation is the
         state machine's concern, not the log's.")

      (record "LogEntry"
        "A single replicated log entry. Carries the term in which it was
         issued (so followers can reject stale leaders' entries) and the
         command itself."
        (field index   :LogIndex)
        (field term    :cluster/Term)
        (field command :Command))

      (record "Log"
        "One node's view of the replicated log. The entries it has
         accepted (in index order) and the highest index it knows to be
         committed cluster-wide."
        (field entries      (list-of :LogEntry))
        (field commit_index :LogIndex))

      ;; -------------------------------------------------------------------
      ;; Replication events
      ;; -------------------------------------------------------------------

      (event "ClientCommandReceived"
        "The leader has received a command from a client and is about to
         attempt to replicate it."
        (payload [command :Command]))

      (event "AppendEntriesRequested"
        "The leader is asking a follower to append a batch of entries
         beginning after the follower's last-known index. The leader_term
         lets the follower reject stale leaders; prev_index/prev_term let
         the follower check log-matching before accepting the append."
        (payload [leader_term  :cluster/Term]
                 [leader       :cluster/NodeId]
                 [prev_index   :LogIndex]
                 [prev_term    :cluster/Term]
                 [entries      (list-of :LogEntry)]
                 [leader_commit :LogIndex]))

      (event "AppendEntriesAcknowledged"
        "A follower has accepted (success=true) or rejected (success=false)
         an AppendEntries request. On rejection the leader retries with an
         earlier prev_index until the logs match."
        (payload [follower_term :cluster/Term]
                 [follower      :cluster/NodeId]
                 [success       :Boolean]
                 [match_index   :LogIndex]))

      (event "EntryCommitted"
        "An entry at the given index has been replicated to a strict
         majority of cluster members and is now committed. The state
         machine may apply this entry."
        (payload [index :LogIndex]
                 [term  :cluster/Term]))

      ;; -------------------------------------------------------------------
      ;; Handlers — leader-side and follower-side reactions
      ;; -------------------------------------------------------------------

      (handler "on_client_command"
        "The leader appends the command to its local log and dispatches an
         AppendEntries to every follower."
        (on :log/ClientCommandReceived)
        (emits :log/AppendEntriesRequested))

      (handler "on_append_entries_requested"
        "A follower validates the request (leader_term not stale,
         prev_index/prev_term match), then appends the entries or
         rejects the request."
        (on :log/AppendEntriesRequested)
        (emits :log/AppendEntriesAcknowledged))

      (handler "on_append_entries_acknowledged"
        "The leader tallies acknowledgements. When an entry's index has
         been matched by a strict majority of followers, the leader
         advances its commit_index and emits EntryCommitted."
        (on :log/AppendEntriesAcknowledged)
        (emits :log/EntryCommitted))

      ;; -------------------------------------------------------------------
      ;; Read accessors
      ;; -------------------------------------------------------------------

      (getter "get_commit_index"
        "The highest log index this node knows to be committed."
        :LogIndex)

      (function "get_entry"
        "Retrieve a log entry by index, if one has been appended at that
         position."
        (takes [index :LogIndex])
        (gives (optional :LogEntry)))

      ;; -------------------------------------------------------------------
      ;; Safety invariants
      ;; -------------------------------------------------------------------

      (invariant "LogAppendOnly"
        "Once an entry has been appended at an index, the entry at that
         index never changes — entries are immutable once placed. (A
         follower may overwrite *uncommitted* trailing entries to reconcile
         with a leader, but committed entries are inviolate.)"
        (holds-that "committed entries are append-only and immutable"))

      (invariant "LogMatching"
        "If two logs contain an entry with the same index and term, then
         the logs are identical in all entries up through that index.
         This is Raft's log-matching property; it is the structural reason
         leader election + AppendEntries together preserve log safety."
        (holds-that "log-matching property"))

      (invariant "CommitIndexMonotonic"
        "A node's commit_index never decreases over time. Newly observed
         commits move it forward; nothing moves it backward."
        (holds-that "commit_index is monotonically non-decreasing"))

      (invariant "LeaderCompleteness"
        "If an entry is committed in Term T, then every leader of every
         Term T' > T has that entry in its log. (Election rules guarantee
         this: a candidate cannot win an election unless its log is
         at-least-as-up-to-date as a majority of the cluster.)"
        (holds-that "committed entries survive leadership changes"))

      (exports LogIndex LogEntry Log Command))))
