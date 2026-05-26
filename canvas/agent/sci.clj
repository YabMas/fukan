(ns canvas.agent.sci
  "Canvas port of agent/sci.allium + sci.boundary.

   Coverage:
     - value EvalResult   → construction/record (7 fields; all but ok are optional)
     - value EvalOpts     → construction/record (1 optional field)
     - fn eval_string     → construction/function
     - fn eval_string_as_view → construction/function
     - fn reset_ctx       → construction/function
     - 4 invariants       → vocab.behavioral/invariant each
     - exports: EvalResult EvalOpts"
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record exports]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "agent.sci"

      ;; ── Value Types ──────────────────────────────────────────────────────

      (record "EvalResult"
        "The envelope every eval call returns. Success carries :result;
         failure carries :error/kind and :error/message. :elapsed-ms is
         always present on eval_string returns; eval_string_as_view omits
         timing because it runs as part of the synchronous load pass."
        (field ok              :Boolean)
        (field result          (optional :Value))
        (field error_kind      (optional :String))
        (field error_message   (optional :String))
        (field error_elapsed_ms (optional :Integer))
        (field elapsed_ms      (optional :Integer)))

      (record "EvalOpts"
        "Caller-supplied evaluation knobs."
        (field timeout_ms (optional :Integer)))

      ;; ── Invariants ────────────────────────────────────────────────────────

      (invariant "SandboxSafety"
        "The sandbox exposes only the public bindings of api and system
         under the 'user' namespace. alter-var-root, intern, binding,
         and var are explicitly denied. No Java class is reachable. No
         IO, no thread spawning, no namespace creation beyond the def/defn
         needed by view loading. Agent-supplied code cannot escape the
         query/view surface."
        (holds-that "sandbox-exposes-only-api-and-system"))

      (invariant "SharedContextLifetime"
        "A single SCI context backs both eval_string and eval_string_as_view.
         Defs added during view loading remain reachable from subsequent
         eval calls within the same daemon process. reset_ctx is the only
         supported way to drop accumulated defs; views_loader.reset invokes
         it before each reload."
        (holds-that "shared-sci-context-across-eval-calls"))

      (invariant "EvalTimeout"
        "eval_string enforces a hard wall-clock timeout (default 5000ms).
         On timeout the underlying future is cancelled and an EvalResult
         with :error/kind :timeout is returned. eval_string_as_view does
         not enforce a timeout — view forms are trusted to complete during
         the load pass."
        (holds-that "eval-string-enforces-timeout"))

      (invariant "SandboxFailureModes"
        "Eval failures surface as structured EvalResult values, never as
         thrown exceptions:
           :runtime  -- caught Throwable inside the sandbox
           :timeout  -- :timeout-ms exceeded; future cancelled
         ex-info instances thrown inside the sandbox propagate their
         :type key through :error/kind, so callers can match against the
         Datalog DSL parser's typed errors and the api filter errors."
        (holds-that "eval-failures-as-eval-result-not-exception"))

      ;; ── Functions ─────────────────────────────────────────────────────────

      (function "eval_string"
        "Evaluate an expression string in the agent sandbox. The result
         carries :ok? and either :result or :error/kind+:error/message,
         plus :elapsed-ms. Times out after :timeout-ms (default 5000)."
        (takes [s    :String
                opts (optional :EvalOpts)])
        (gives :EvalResult))

      (function "eval_string_as_view"
        "Evaluate a form-string in the view-loading context. Defs land
         in the shared SCI context and become reachable from subsequent
         eval_string calls."
        (takes [s :String])
        (gives :EvalResult))

      (function "reset_ctx"
        "Discard the shared SCI context. The next eval call rebuilds it
         with a fresh empty namespace. Used by views_loader before each
         reload to drop stale view defs."
        (takes [])
        (gives :Unit))

      ;; ── Exports ───────────────────────────────────────────────────────────

      (exports EvalResult EvalOpts))))
