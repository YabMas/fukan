(ns fukan.canvas.pilot.vocabulary-analyzer
  "Canvas port of vocabulary/allium/analyzer.allium + analyzer.boundary.

   Slice chosen: analyzer (per-file Allium AST → kernel content).
   Why: largest and most meta-interesting vocabulary module. Has a `rule`
   (not just invariants), two value types with actual fields including
   List<T> and optional types, and two cross-module-typed functions.

   Coverage:
     - value ExposesIssue       → (record ...) — 7 String fields, ports cleanly
     - value EventShapeMismatch → (record ...) — partially; List<T> and optional
                                                  fields approximated. Gap 8, 10.
     - fn analyze_file          → (function ...) with cross-module type approximation
     - fn extract_use_aliases   → (function ...)

   Gaps (see doc/plans/2026-05-25-pilot-port-findings.md):
     - rule AnalyzeFile with when: clause — no rule lift. Gap 9.
     - 8 invariants — no invariant lift. Gap 11.
     - EventShapeMismatch.shapes: List<Any> — record field body is keyword-only,
       no (field name (h/list-of :Any)) syntax. Gap 8.
     - arities: List<Integer>?, type_seqs: List<Any>? — optional list fields. Gap 10.
     - analyze_file takes model.Model, parser.ParsedAllium — cross-module refs. Gap 5."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "vocabulary.allium.analyzer"
      ;; ExposesIssue — all String fields, ports cleanly.
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

      ;; EventShapeMismatch — partially portedable. The List<Any>, List<Integer>?,
      ;; and List<Any>? fields are not expressible with keyword-only field syntax.
      ;; Approximated as opaque :List keywords (losing element type and
      ;; optionality). Gap 8, Gap 10.
      (record "EventShapeMismatch"
        "Diagnostic record for event declaration sites that disagree on
         arity or parameter types. Consumed by rules_4b."
        (field event_id     :String)
        (field event_name   :String)
        (field module_coord :String)
        (field reason       :String)
        ;; shapes: List<Any> — element type not expressible. Gap 8.
        (field shapes       :List)
        ;; arities: List<Integer>? — optional list, not expressible. Gap 10.
        ;; Dropped entirely rather than misrepresenting as required :Integer.
        ;; type_seqs: List<Any>? — same. Dropped.
        )

      ;; Functions from analyzer.boundary.
      ;;
      ;; analyze_file(model: model.Model, ast: parser.ParsedAllium,
      ;;              coordinate: String, use_aliases: Map<String, String>?)
      ;;   -> model.Model
      ;; Cross-module types approximated as opaque keywords. Gap 5.
      ;; use_aliases is optional (Map<String, String>?) — approximated as :Map.
      (function "analyze_file"
        "Add this file's kernel content and Allium::* tag applications to model."
        (takes [model      :Model
                ast        :ParsedAllium
                coordinate :String
                use_aliases :Map])
        (gives :Model))

      ;; extract_use_aliases(ast: parser.ParsedAllium) -> Map<String, String>
      (function "extract_use_aliases"
        "Collect use declarations from an AST into alias → imported-coord map."
        (takes [ast :ParsedAllium])
        (gives :Map)))))
