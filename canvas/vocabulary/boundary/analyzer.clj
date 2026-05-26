(ns canvas.vocabulary.boundary.analyzer
  "Canvas port of vocabulary/boundary/analyzer.allium.

   Scope: The per-file .boundary analyzer — file-shape discipline, the
   three fn forms, attach resolution, subsystem composition, and the
   Phase 4 prep state it accumulates for downstream sub-phases.

   Coverage:
     - record BindingIssue        (10 fields, several optional)
     - 8 invariants: FileShapeDiscipline, ThreeFnForms,
       DeclareNewIntroducesOperation, AttachReusesOperation,
       ExportsClosesModule, SubsystemComposesChildren,
       BindingEdgeIsBestEffort, Phase4StateAccumulation

   TODO: rule AnalyzeFile — no rule lift (deferred).
     Structural intent:
       when: AnalyzeFile(model: model.Model, ast: parser.ParsedBoundary,
                         coord: String, use_aliases: Map<String, String>)
     Detects file shape (module-bound vs subsystem-bound); processes fn
     forms (declare-new / local-attach / foreign-attach); applies exports:;
     handles subsystem declarations. Returns enriched Model."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [record]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "vocabulary.boundary.analyzer"

      ;; BindingIssue — diagnostic record stashed in :phase4-state.binding-issues
      ;; when an attach-form fn cannot resolve its target Operation or triggers: Rule.
      (record "BindingIssue"
        "Diagnostic record stashed in :phase4-state.binding-issues when an
         attach-form fn cannot resolve its target Operation or its
         triggers: Rule. Phase 4 (rules_4c) reads this slot and surfaces
         each record as a Violation."
        (field kind         :String)
        (field coord        (optional :String))
        (field op           (optional :String))
        (field rule         (optional :String))
        (field trigger      (optional :Any))
        (field form         (optional :String))
        (field contract     (optional :String))
        (field alias        (optional :String))
        (field reason       (optional :String))
        (field ex           (optional :String))
        ;; use_aliases: List<String>?
        (field use_aliases  (optional (list-of :String))))

      ;; TODO: rule AnalyzeFile — no rule lift (deferred to Sprint 2).
      ;; Structural intent:
      ;;   when: AnalyzeFile(model: model.Model, ast: parser.ParsedBoundary,
      ;;                     coord: String, use_aliases: Map<String, String>)
      ;; Detects file shape from declaration kinds; processes fn forms
      ;; (declare-new / local-attach / foreign-attach); applies exports:;
      ;; handles subsystem declarations with contains: canonicalisation.

      (invariant "FileShapeDiscipline"
        "A .boundary file is module-bound or subsystem-bound, never both.
         A mixed file raises :boundary-shape-error and the build halts.
         More than one exports: declaration in a single module-bound file
         is also a shape error."
        (holds-that "boundary-file-is-module-or-subsystem-not-both"))

      (invariant "ThreeFnForms"
        "The fn declaration recognises three forms, distinguished by the parser:
           :declare-new    — introduces a fresh Operation primitive.
           :local-attach   — binds an existing same-module Operation.
           :foreign-attach — binds an existing imported Operation.
         Both attach forms require a non-empty body (triggers: and/or
         returns:); an empty-body attach raises :boundary-shape-error."
        (holds-that "three-fn-forms-declare-local-foreign"))

      (invariant "DeclareNewIntroducesOperation"
        "A declare-new fn creates exactly one Operation primitive at
         <coord>::<fn-name>, registers it on the module-Container's
         boundary.operations, and applies a Boundary::Function tag. If
         the body has triggers:, a Boundary::Binding-tagged triggers:
         Operation → Rule edge is emitted with optional
         :returns_expression payload."
        (holds-that "declare-new-creates-one-operation"))

      (invariant "AttachReusesOperation"
        "An attach-form fn never creates a new Operation. The target id
         is <owner-coord>::<Contract>.<op> — for local-attach, owner
         is coord; for foreign-attach, it is resolved through
         use-aliases. The binding edge is the same as for declare-new
         with triggers:, but no Operation primitive is added."
        (holds-that "attach-form-reuses-existing-operation"))

      (invariant "ExportsClosesModule"
        "An exports: clause applies the Boundary::ModuleApi tag to the
         module-Container with the :exported payload listing the
         closed-surface entries. Presence of the tag flips the module
         to closed; absence leaves it open. Phase 4d consumes the
         payload to enforce visibility."
        (holds-that "exports-clause-closes-module"))

      (invariant "SubsystemComposesChildren"
        "A subsystem declaration creates a composite Container at the
         file's coord with the :children set populated from the
         contains: list. Each contains: path is canonicalised against
         the host file's coord before storage. The composite carries
         Boundary::Subsystem (with :name payload) and Boundary::Exports
         (with the subsystem's :exported list)."
        (holds-that "subsystem-declaration-creates-composite-container"))

      (invariant "BindingEdgeIsBestEffort"
        "triggers: Operation → Rule edge emission catches all kernel
         errors and records them as :phase4-state binding-issues rather
         than halting. An unresolved Rule reference, an unknown alias,
         or a kernel validation failure becomes a downstream Phase 4
         Violation, not an analyzer crash."
        (holds-that "binding-edge-emission-best-effort"))

      (invariant "Phase4StateAccumulation"
        "All resolution failures (unresolved trigger rule, attach without
         triggers, unresolved Operation target) accumulate into
         :phase4-state.binding-issues. Phase 4 (rules_4c) reads the slot
         and surfaces each entry as a Violation with structured context."
        (holds-that "phase4-state-accumulation-not-throw")))))
