(ns canvas.agent.views-loader
  "Canvas port of agent/views_loader.allium + views_loader.boundary.

   Coverage:
     - value LoadReport  → construction/record (2 fields)
     - value LoadError   → construction/record (4 fields)
     - fn load_file      → construction/function
     - fn auto_load      → construction/function
     - fn discover       → construction/function
     - fn last_report    → construction/function
     - fn reset          → construction/function
     - 5 invariants      → vocab.behavioral/invariant each
     - exports: LoadReport LoadError

   Notes:
     - File path: views_loader.clj → namespace: canvas.agent.views-loader (hyphen).
     - LoadError.error_form is optional Value (arbitrary form shape)."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record exports]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "agent.views_loader"

      ;; ── Value Types ──────────────────────────────────────────────────────

      (record "LoadReport"
        "Summary of the most recent load attempt. loaded carries the
         symbols of successful defns; errors carries one entry per failure
         (parse error or per-form runtime error)."
        (field loaded (list-of :Symbol))
        (field errors (list-of :LoadError)))

      (record "LoadError"
        "One failure entry — either a top-level syntax error from reading
         the file or a per-form runtime error from the sandbox."
        (field error_kind    :String)
        (field error_message :String)
        (field error_path    (optional :String))
        (field error_form    (optional :Value)))

      ;; ── Invariants ────────────────────────────────────────────────────────

      (invariant "LoadFileReplacesViews"
        "Each load_file invocation resets the shared SCI context (dropping
         every previously-loaded view def) and then evaluates the new file
         form by form. Per-form failures do not stop the load — the loader
         continues with the next form and captures the failure into the
         report. After load_file, only the defns that succeeded in the
         current load are reachable."
        (holds-that "load-file-resets-then-evaluates"))

      (invariant "ReportShape"
        "The load report always carries :loaded (a vector of symbols) and
         :errors (a vector of LoadError maps), both possibly empty. status
         includes this report under :views so callers can spot misbehaving
         view files without a separate query."
        (holds-that "load-report-always-has-loaded-and-errors"))

      (invariant "PerFormIsolation"
        "A failing form leaves the loader in a usable state: subsequent
         forms in the same file still attempt to load, and the failure is
         reported in :errors with the offending form attached. The shared
         SCI context is not torn down by a per-form failure."
        (holds-that "per-form-isolation-on-failure"))

      (invariant "DiscoveryConvention"
        "Auto-discovery looks for exactly one path:
         <target_src>/.fukan/agent-views.clj. A missing file is not an
         error — auto_load resets the report and returns it empty."
        (holds-that "discovery-convention-single-path"))

      (invariant "ResetClearsBoth"
        "reset empties the load report and discards the shared SCI context
         via sci.reset_ctx. Either operation in isolation would leave the
         two stores inconsistent; the loader always pairs them."
        (holds-that "reset-clears-report-and-context"))

      ;; ── Functions ─────────────────────────────────────────────────────────

      (function "load_file"
        "Reset the shared context and load forms from the given file.
         Each form is evaluated independently; per-form failures land in
         :errors and successful defns land in :loaded."
        (takes [path :String])
        (gives :LoadReport))

      (function "auto_load"
        "Discover .fukan/agent-views.clj under target_src and load it,
         or reset to an empty report when the file is absent."
        (takes [target_src (optional :String)])
        (gives :LoadReport))

      (function "discover"
        "Resolve the canonical path of .fukan/agent-views.clj under the
         given source root, or nil when the file does not exist."
        (takes [target_src (optional :String)])
        (gives (optional :String)))

      (function "last_report"
        "Return the most recent load report without reloading."
        (takes [])
        (gives :LoadReport))

      (function "reset"
        "Empty the load report and discard the shared SCI context."
        (takes [])
        (gives :Unit))

      ;; ── Exports ───────────────────────────────────────────────────────────

      (exports LoadReport LoadError))))
