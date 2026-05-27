(ns canvas.target.clojure.types
  "Canvas port of target/clojure/types.allium + types.boundary.

   Coverage:
     - fn render      → construction/function (cross-module ref)
     - 4 invariants   → vocab.behavioral/invariant each

   Notes:
     - types.allium contains only invariants (no value types).
     - fn render takes a project registry and a kernel Type expression;
       returns a target-language schema value (opaque :Any).
     - Cross-module type ref: :registry/Registry."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "target.clojure.types"

      ;; ── Invariants ───────────────────────────────────────────────────────

      (invariant "OverrideWinsOverBuiltin"
        "For Scalar types, the renderer consults the project registry's
         type_overrides first. A registered override for a Scalar name
         replaces the built-in default; an unregistered Scalar falls back
         to the built-in mapping."
        (holds-that "type-override-wins-over-builtin"))

      (invariant "BuiltinScalarMap"
        "The renderer ships a small built-in mapping from common Scalar names
         (String, Integer, Boolean, Number, Text) to target-language
         primitives, so a fresh project registry already renders ordinary
         types without requiring overrides."
        (holds-that "builtin-scalar-map-covers-common-types"))

      (invariant "CompositeRefSentinel"
        "A named Composite type renders as a sentinel that carries the
         referenced Container's identity. Resolution of that sentinel to a
         concrete schema reference is the Projector's responsibility — the
         type renderer never reads the Model."
        (holds-that "composite-ref-renders-as-sentinel"))

      (invariant "UnknownIsAny"
        "When the renderer encounters a Type shape it does not recognise —
         an unmapped Scalar, an unhandled Type case — it yields the
         target-language any-schema rather than raising. Type translation is
         best-effort; an unrenderable type does not block Blueprint assembly."
        (holds-that "unknown-type-yields-any-schema"))

      ;; ── Public Functions ─────────────────────────────────────────────────

      (function "render"
        "Render a kernel substrate Type expression as a target-language
         schema value. The project registry's type_overrides win over the
         built-in substrate-to-Malli defaults."
        (takes [registry :registry/Registry
                type     :Any])
        (gives :Any)))))
