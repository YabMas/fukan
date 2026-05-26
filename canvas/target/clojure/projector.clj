(ns canvas.target.clojure.projector
  "Canvas port of target/clojure/projector.allium + projector.boundary.

   Coverage:
     - fn project     → construction/function (cross-module refs)
     - 5 invariants   → vocab.behavioral/invariant each

   Notes:
     - projector.allium contains only invariants (no value types).
     - Cross-module type refs: :model/Model, :registry/Registry,
       :blueprint/Blueprint.
     - fn project raises if no such primitive exists in the Model
       (documented in docstring)."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "target.clojure.projector"

      ;; ── Invariants ───────────────────────────────────────────────────────

      (invariant "OnDemand"
        "project is invoked per request — never as part of model
         construction. The Projector has no place in the build pipeline; it
         consumes a built Model alongside a project registry to assemble an
         ephemeral Blueprint."
        (holds-that "projector-invoked-per-request-not-in-pipeline"))

      (invariant "SixComponentAssembly"
        "Every assembled Blueprint carries all six components of the
         universal projection mechanic per MODEL.md §7.7:
           1. canonical address
           2. artifact kind
           3. expected signature
           4. type renderings (folded into the signature)
           5. surrounding model context (description, intent, related edges,
              host surface if any)
           6. selected idioms (from the project registry)
         A missing component is a Projector bug, not a Blueprint variant —
         unrenderable types fall back to the any-schema rather than dropping
         the signature."
        (holds-that "blueprint-carries-all-six-components"))

      (invariant "ReadOnly"
        "project is a pure function of (model, registry, primitive_id,
         projection_kind). It never mutates the Model, never writes to disk,
         and never registers anything with the project registry. Blueprints
         are returned, not stored."
        (holds-that "projector-is-pure-and-read-only"))

      (invariant "AddressMatchesAnalyzer"
        "The canonical address the Projector embeds in a Blueprint is the
         same address the Analyzer derives when emitting the corresponding
         projects edge. Generation (LLM reads the Blueprint) and verification
         (Analyzer compares the source index against its computed address)
         agree by construction: both routes call the same address resolver
         with the same inputs."
        (holds-that "projector-and-analyzer-address-agree"))

      (invariant "IdiomSelectionRoute"
        "An idiom from the project registry contributes to a Blueprint when
         every populated key on its route matches the target primitive:
         primitive_kind, projection_kind, and the regex-matched canonical
         address name. Unpopulated route keys match anything. Idioms are
         evaluated in registry order; the Blueprint carries the bodies of
         every matching entry."
        (holds-that "idiom-selection-by-route-matching"))

      ;; ── Public Functions ─────────────────────────────────────────────────

      (function "project"
        "Assemble an Implementation Blueprint for the named primitive at the
         given projection kind. Raises if no such primitive exists in the
         Model."
        (takes [model           :model/Model
                registry        :registry/Registry
                primitive_id    :String
                projection_kind :String])
        (gives :blueprint/Blueprint)))))
