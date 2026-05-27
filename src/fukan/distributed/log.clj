(ns fukan.distributed.log
  "Implementation surface for canvas.distributed.log — deliberately minimal.

   Phase 6 Sprint 4 trial-run output, round 3. The trial's purpose is
   to exercise drift detection; this module is the 'mostly absent'
   case — only enough code to test that drift signal scales sanely
   when the implementation is far behind the canvas.

   Canvas coverage in this file:
     Types     — LogIndex, LogEntry             (2 of 4;
                 Command, Log intentionally absent)
     Events    — ClientCommandReceived          (1 of 4;
                 AppendEntriesRequested,
                 AppendEntriesAcknowledged,
                 EntryCommitted absent)
     Handlers  — on-client-command              (1 of 3;
                 on-append-entries-requested,
                 on-append-entries-acknowledged absent)
     Getters/Functions — none                   (deliberate;
                 leaves get-commit-index, get-entry as drift)
     Invariants — none                          (consistent with rounds 1-2)

   Drift expectation: distributed.log finding count drops 17 → ~13 —
   modest progress, large remaining gap. That is the *honest*
   appearance of canvas-ahead-of-code work, which is the normal
   condition in LLM-driven development."
  (:require [fukan.distributed.cluster :as cluster]))

;; --------------------------------------------------------------------------
;; Atomic / record types implemented (2 of 4)
;; --------------------------------------------------------------------------

(def LogIndex
  "A 1-based position in the replicated log. Monotonically assigned;
   never reused, never reordered."
  [:int {:min 1}])

(def LogEntry
  "A single replicated log entry."
  [:map
   [:index LogIndex]
   [:term cluster/Term]
   [:command :any]])  ; Command is intentionally absent — :any stand-in.

;; Command (the opaque value type) and Log (the record) are intentionally
;; absent. The drift signal should surface both.

;; --------------------------------------------------------------------------
;; The one event we implement — the leader's input edge
;; --------------------------------------------------------------------------

(def ClientCommandReceived
  "The leader has received a command from a client."
  [:map
   [:command :any]])

;; --------------------------------------------------------------------------
;; The one handler we implement — leader appending to its local log
;; --------------------------------------------------------------------------

(defn on-client-command
  "The leader appends the command to its local log and dispatches an
   AppendEntries to every follower."
  [event-payload log-state]
  (throw (ex-info "Not implemented"
                  {:context :phase6-trial
                   :handler 'on-client-command
                   :event :log/ClientCommandReceived
                   :received {:event-payload event-payload
                              :log-state log-state}})))

;; Intentionally absent (drift evidence):
;;   - Type Command, Log
;;   - Event AppendEntriesRequested, AppendEntriesAcknowledged, EntryCommitted
;;   - Handler on-append-entries-requested, on-append-entries-acknowledged
;;   - Getter get-commit-index
;;   - All 4 invariants

(defn get-entry
  "Retrieve a log entry by index, if one has been appended at that
   position."
  {:malli/schema [:=> [:cat :LogIndex] [:maybe :LogEntry]]}
  [_index]
  (throw (ex-info "get-entry: not yet implemented"
                  {:canvas-id "distributed.log/get_entry"})))
