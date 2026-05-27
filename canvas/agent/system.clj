(ns canvas.agent.system
  "Canvas port of agent/system.allium + system.boundary.

   Coverage:
     - value AgentStatus  → construction/record (7 fields; cross-module LoadReport)
     - value HelpEntry    → construction/record (5 fields)
     - value SourceEntry  → construction/record (3 fields)
     - fn status          → construction/function
     - fn refresh         → construction/function
     - fn help            → construction/function
     - fn source          → construction/function
     - 5 invariants       → vocab.behavioral/invariant each

   Notes:
     - AgentStatus.views uses :views_loader/LoadReport cross-module ref.
     - help(fn_sym: Symbol?) → approximated as (optional :Symbol)."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "agent.system"

      ;; ── Value Types ──────────────────────────────────────────────────────

      (record "AgentStatus"
        "Snapshot of the daemon and the currently loaded Model. The views
         field carries the latest views-loader report so callers can see
         which agent-defined views succeeded and which failed."
        (field model_loaded    :Boolean)
        (field target          (optional :String))
        (field primitive_count :Integer)
        (field relation_count  :Integer)
        (field artifact_count  :Integer)
        (field violation_count :Integer)
        (field views           :views_loader/LoadReport))

      (record "HelpEntry"
        "One catalogued entry of the agent surface. When help is called
         without args, the response is a nested map of these grouped by
         namespace and (for the api ns) by layer."
        (field name    :String)
        (field layer   (optional :String))
        (field doc     :String)
        (field example :String)
        (field origin  (optional :String)))

      (record "SourceEntry"
        "Source text of a single L1 or L2 fn, with its declaring namespace.
         The top-level metadata map is stripped to leave a clean template."
        (field name   :String)
        (field ns     :String)
        (field source :String))

      ;; ── Invariants ────────────────────────────────────────────────────────

      (invariant "EditRefreshQueryLoop"
        "Canonical agent workflow. Spec and code on disk are the source
         of truth; the loaded Model is a cached projection. An agent
         edits a .clj / .allium / .boundary file, then calls refresh to
         rebuild the Model, then queries via L0/L1/L2 to verify the change
         landed. Skipping the refresh step leaves the Model stale."
        (holds-that "edit-refresh-query-loop"))

      (invariant "RefreshRebuildsViews"
        "Each refresh invocation also reloads the .fukan/agent-views.clj
         file (when present), replacing the prior view set atomically.
         Agents that change view definitions see them surface on the next
         refresh without restarting the daemon."
        (holds-that "refresh-reloads-agent-views"))

      (invariant "RefreshIdempotence"
        "Calling refresh twice in a row without intervening disk edits
         produces the same loaded Model and the same status snapshot."
        (holds-that "refresh-idempotent-without-disk-changes"))

      (invariant "SelfDocumenting"
        "help and source draw exclusively from the live surface and its
         :agent/doc / :agent/example / :agent/layer metadata. There is no
         separate documentation registry that can drift from the code."
        (holds-that "help-source-from-live-metadata"))

      (invariant "StatusReflectsLoadedModel"
        "status fields (:model-loaded? :primitive-count :relation-count
         :artifact-count :violation-count :views) describe the currently
         loaded Model snapshot, not the on-disk source. To learn what
         disk says, call refresh first."
        (holds-that "status-reflects-loaded-model-not-disk"))

      ;; ── Functions ─────────────────────────────────────────────────────────

      (function "status"
        "Snapshot of the daemon and the loaded Model."
        (takes [])
        (gives :AgentStatus))

      (function "refresh"
        "Rebuild the loaded Model from disk and reload agent-views.
         Blocks until the rebuild completes; returns the new status."
        (takes [])
        (gives :AgentStatus))

      (function "help"
        "Without args: list the surface grouped by namespace and layer.
         With a symbol: return doc, arglists, and example for that fn."
        (takes [fn_sym (optional :Symbol)])
        (gives :HelpEntry))

      (function "source"
        "Return the source text of an L1 or L2 fn so the agent can read
         built-in views as templates for its own definitions."
        (takes [fn_sym :Symbol])
        (gives (optional :SourceEntry))))))
