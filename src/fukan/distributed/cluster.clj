(ns fukan.distributed.cluster
  "Implementation surface for canvas.distributed.cluster — partial.

   Phase 6 Sprint 4 trial-run scope. Stubs throw; the loop's purpose is
   to exercise drift detection, not to ship Raft. Canvas declares the
   shape and intent; this file projects the essentials and deliberately
   leaves one declaration absent so drift has something to catch.

   Canvas coverage in this file:
     Types     — NodeId, Term, NodeRole, Node, Cluster   (all 5)
     Getters   — get-current-term, get-current-leader    (2 of 3;
                 get-self-role intentionally omitted)
     Functions — get-node, members                       (both stubs)

   Invariants (AtMostOneLeaderPerTerm, TermMonotonicity,
   MajorityRequiredForLeadership) have no code-side counterpart in
   this trial — they are behavioural commitments better realised in
   property-tests than in callable code. Drift will surface them and
   the loop's job is to *weigh* whether that is canvas-side over-
   declaration or code-side under-realisation. (See findings doc.)")

;; --------------------------------------------------------------------------
;; Atomic value types — canvas declares as opaque. Represent as Malli specs
;; over their carrier types so the analyzer can pick up a code-side shape.
;; --------------------------------------------------------------------------

(def NodeId
  "An opaque, stable identity for a cluster member."
  [:string {:min 1}])

(def Term
  "A monotonically increasing logical clock — natural number."
  [:int {:min 0}])

(def NodeRole
  "One of three role tags. The carrier of the role state-machine."
  [:enum :follower :candidate :leader])

;; --------------------------------------------------------------------------
;; Record types — Malli :map schemas. Field shape matches canvas verbatim:
;; cluster/Node has fields {id, role}; cluster/Cluster has fields
;; {self, members, current_term, current_leader}. The shape-drift check
;; reconciles these against canvas/distributed/cluster.clj.
;; --------------------------------------------------------------------------

(def Node
  "A known member of the cluster."
  [:map
   [:id NodeId]
   [:role NodeRole]])

(def ^:schema Cluster
  "One node's view of the cluster."
  [:map {:description "The cluster's view from one node. The set of members it knows about, the current term it has observed, and — when known — the id of the leader for that term."}
   [:self :NodeId]
   [:current_leader {:optional true} :NodeId]
   [:current_term :Term]
   [:members [:set :NodeId]]])

;; --------------------------------------------------------------------------
;; Getters — zero-arg accessors over an externally-held Cluster value.
;; In a real implementation these would close over node state; here they
;; take a Cluster value explicitly to keep the trial pure.
;;
;; Naming: canvas getter "get_current_term" → code symbol get-current-term.
;; Drift's expected-symbol field encodes the kebab-case convention; the
;; analyzer's address registry matches on that path.
;; --------------------------------------------------------------------------

(defn get-current-term
  "The most recent Term this node has observed."
  [^{:doc "Cluster value"} cluster]
  (:current_term cluster))

(defn get-current-leader
  "The id of the leader for the current term, if one is known."
  [cluster]
  (:current_leader cluster))

;; get-self-role intentionally omitted. The trial leaves this canvas
;; declaration absent so the drift loop has something to catch on Reflect.

;; --------------------------------------------------------------------------
;; Lookup functions — stubs that throw. The drift signal cares about
;; presence, not correctness; a throwing body still satisfies the
;; projection edge's :valid claim.
;; --------------------------------------------------------------------------

(defn get-node
  "Look up a known cluster member by id."
  [cluster id]
  (throw (ex-info "Not implemented"
                  {:context :phase6-trial
                   :fn 'get-node
                   :received {:cluster cluster :id id}})))

(defn members
  "Return all known cluster members as Node values."
  [cluster]
  (throw (ex-info "Not implemented"
                  {:context :phase6-trial
                   :fn 'members
                   :received {:cluster cluster}})))

;; --------------------------------------------------------------------------
;; Invariants project to property tests at test/fukan/distributed/cluster_test.clj
;; per Phase 8 Sprint 5 (the migrate path). No predicate stubs live in src/
;; for the cluster invariants — their code-side counterpart is a
;; `clojure.test.check` defspec.
;; --------------------------------------------------------------------------
