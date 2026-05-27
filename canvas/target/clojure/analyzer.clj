(ns canvas.target.clojure.analyzer
  "Canvas port of target/clojure/analyzer.allium + analyzer.boundary.

   Coverage:
     - rule RunClojureAnalyzer          → vocab.behavioral/rule
     - fn run                           → construction/function (triggers RunClojureAnalyzer)
                                          (returns post.model) + cross-module refs
     - 6 invariants                     → vocab.behavioral/invariant each

   Notes:
     - Cross-module type refs: :model/Model, :registry/Registry."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant rule]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "target.clojure.analyzer"

      (rule "RunClojureAnalyzer"
        "Phase 6 of the build pipeline. Walk the configured code root,
         project every Operation, Rule, Entity/Value/Variant Container,
         and Event to a canonical Code.* artifact, and emit a
         :relation/projects edge with per-edge :validity. Source symbols
         with no matching primitive become standalone unprojected artifacts.
         Non-gating: failures accumulate as violations, the build continues."
        (when RunClojureAnalyzer
          (model     :model/Model)
          (code_root (optional :String))))

      ;; ── Invariants ───────────────────────────────────────────────────────

      (invariant "NonGating"
        "Phase 6 never raises. A missing or unreadable code root produces an
         empty source index and a Model with every projects edge at :absent
         validity. Duplicate-address conditions surface as Model violations
         rather than halting the build."
        (holds-that "phase6-never-raises"))

      (invariant "PurelyAdditive"
        "The analyzer never removes or mutates an existing primitive, existing
         kernel edge, existing artifact, or existing violation. Its output
         Model equals the input Model plus new Code.* artifacts, new
         :relation/projects edges, and any new duplicate-address violations."
        (holds-that "analyzer-is-purely-additive"))

      (invariant "ProjectsEdgeValidity"
        "Every projects edge the analyzer emits carries a :validity of :valid
         or :absent. :valid indicates a source symbol was found at the
         canonical address; :absent indicates the artifact identity was
         synthesised at the canonical address with no realising source.
         :stale is reserved for future signature-mismatch detection."
        (holds-that "projects-edge-validity-valid-or-absent"))

      (invariant "MaterialisesUnprojected"
        "Every source symbol the walker discovers becomes a Code.* artifact
         in the Model, even when no spec primitive projects onto its canonical
         address. Unprojected artifacts carry no inbound projects edge and
         are the drift surface for unbound implementation code."
        (holds-that "materialises-every-source-symbol"))

      (invariant "DuplicateAddressViolations"
        "When more than one source symbol shares the same canonical address
         (ns, name, kind), the analyzer emits exactly one violation per
         offending group, listing every contributing file. The violations are
         warnings on Phase 6, not errors: the analyzer continues processing
         the remaining primitives."
        (holds-that "duplicate-address-one-violation-per-group"))

      (invariant "FunctionPrivacyDerivation"
        "For function-shaped projections, the resulting Code.* artifact
         records publicity derived from the source symbol's kind: public when
         the symbol was a public top-level function, private when it was a
         private top-level function, and unknown when no matching source
         symbol was found."
        (holds-that "function-privacy-derived-from-symbol-kind"))

      ;; ── Public Functions ─────────────────────────────────────────────────

      (function "run"
        "Run the Clojure target analyzer over the model. Emits Code.*
         artifacts and :relation/projects edges; appends any
         duplicate-canonical-address violations; materialises any
         otherwise-unprojected source artifacts. Returns the updated model.
         A nil or non-existent code_root produces an empty source index, so
         every projects edge lands with :absent validity."
        (takes [model     :model/Model
                registry  :registry/Registry
                code_root (optional :String)])
        (gives :model/Model)
        (triggers RunClojureAnalyzer)
        (returns "post.model")))))

