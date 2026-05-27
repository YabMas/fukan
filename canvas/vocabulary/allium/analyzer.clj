(ns canvas.vocabulary.allium.analyzer
  "Canvas port of vocabulary/allium/analyzer.allium + analyzer.boundary.

   Phase 2 migration: escape hatches replaced by vocab lifts.

   Coverage:
     - value ExposesIssue        → construction/record (7 String fields)
     - value EventShapeMismatch  → construction/record using shape grammar
                                   (list-of :Any), (optional (list-of :Integer))
     - rule AnalyzeFile          → vocab.behavioral/rule
     - fn analyze_file           → construction/function
     - fn extract_use_aliases    → construction/function
     - 8 invariants              → vocab.behavioral/invariant each

   Notes:
     - EventShapeMismatch.arities: List<Integer>? and type_seqs: List<Any>?
       are now expressible via shape grammar (optional (list-of :Integer)) etc."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record]]
            [fukan.canvas.vocab.behavioral :refer [invariant rule]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "vocabulary.allium.analyzer"

      ;; Value types from analyzer.allium

      ;; ExposesIssue — 7 String fields, ports cleanly.
      (record "ExposesIssue"
        "Diagnostic record for exposes: path validation failures.
         Stashed in :phase4-state.exposes-issues; surfaced by rules_4b."
        (field surface_id  :String)
        (field module      :String)
        (field path        :String)
        (field target_id   :String)
        (field field       :String)
        (field resolution  :String)
        (field message     :String))

      ;; EventShapeMismatch — Phase 2 shape grammar handles List<T> and
      ;; Optional<List<T>> fields natively.
      (record "EventShapeMismatch"
        "Diagnostic record for event declaration sites that disagree on
         arity or parameter types. Consumed by rules_4b."
        (field event_id     :String)
        (field event_name   :String)
        (field module_coord :String)
        (field reason       :String)
        ;; shapes: List<Any>
        (field shapes       (list-of :Any))
        ;; arities: List<Integer>?
        (field arities      (optional (list-of :Integer)))
        ;; type_seqs: List<Any>?
        (field type_seqs    (optional (list-of :Any))))

      (rule "AnalyzeFile"
        "Walk one .allium file's declarations and fold kernel content (plus
         Allium::* tag applications) into the input Model. Processes
         declarations in dependency order: entities/values/variants →
         external-entities/actors → contracts → surfaces → rules →
         top-level invariants. Stashes diagnostics in :phase4-state rather
         than throwing."
        (when AnalyzeFile
          (model      :model/Model)
          (ast        :parser/ParsedAllium)
          (coordinate :String)
          (use_aliases (optional (map-of :String :String)))))

      ;; Invariants from analyzer.allium
      (invariant "DeclarationOrder"
        "Declarations within one file are processed in a fixed dependency
         order: entities/values/variants → external-entities/actors →
         contracts → surfaces → rules → top-level invariants."
        (holds-that "declaration-order-discipline"))

      (invariant "CrossModuleResolution"
        "Cross-module references resolve through the file's use-aliases map.
         An unknown alias falls through to an opaque placeholder — never
         silently dropped."
        (holds-that "cross-module-ref-via-use-aliases"))

      (invariant "StubCreationOnUnknownRef"
        "When a cross-module reference targets a coordinate not yet analyzed,
         the analyzer creates a minimal stub primitive. Pipeline
         stub-unification merges the stub once the imported module loads."
        (holds-that "stub-on-unknown-ref"))

      (invariant "FacingRoleStripping"
        "When a rule's when: or emits clause uses a dotted reference whose
         leading segment is a facing role on a local surface, the leading
         segment is stripped; the event lives in this module."
        (holds-that "facing-role-stripping-on-dotted-ref"))

      (invariant "EventSynthesis"
        "After per-declaration analysis, a synthesize-events post-pass
         produces one canonical Event primitive per event-name from all
         declaration sites in this file."
        (holds-that "event-synthesis-post-pass"))

      (invariant "Phase4StateAccumulation"
        "The analyzer never throws on unresolved or ambiguous references.
         Diagnostic records are appended to the Model's :phase4-state slot
         for Phase 4 rules to surface as Violations."
        (holds-that "phase4-state-accumulation-not-throw"))

      (invariant "BestEffortEmission"
        "Edge emission is best-effort: failures are caught and either silently
         dropped or recorded into :phase4-state. The analyzer continues rather
         than halting on the first defect."
        (holds-that "best-effort-edge-emission"))

      (invariant "PurelyAdditive"
        "analyze_file only adds to the input Model — primitives, edges, tag
         applications, :phase4-state entries. It never removes a primitive or
         rewrites a previously-registered substrate."
        (holds-that "purely-additive-to-model"))

      ;; Public functions from analyzer.boundary.
      ;; Cross-module types approximated as namespaced keywords.
      (function "analyze_file"
        "Add this file's kernel content and Allium::* tag applications to model."
        (takes [model       :model/Model
                ast         :parser/ParsedAllium
                coordinate  :String
                use_aliases (optional (map-of :String :String))])
        (gives :model/Model))

      (function "extract_use_aliases"
        "Collect use declarations from an AST into alias → imported-coord map."
        (takes [ast :parser/ParsedAllium])
        (gives (map-of :String :String))))))
