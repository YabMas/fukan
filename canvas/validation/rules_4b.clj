(ns canvas.validation.rules-4b
  "Canvas port of validation/rules_4b.allium + rules_4b.boundary.

   Scope: Phase 4b — event-related rules. Four structural rules over
   Events, their declaration sites, the parameter-shape agreement across
   sites, the provides/triggers wiring, and the exposes: substrate-endpoint
   resolution.

   Coverage:
     - fn check  → vocab.validation/checker (Model) -> [Violation]
     - 5 invariants → vocab.behavioral/invariant each

   Notes:
     - rule EveryEventHasDeclarationSite, DeclarationSitesShareShape,
       ProvidesNeedsExternalStimulus, ExposesPathsResolve, CheckIsPure:
       no rule lift (deferred). Left as TODO comments below."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.vocab.behavioral :refer [invariant]]
            [fukan.canvas.vocab.validation :refer [checker]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "validation.rules-4b"

      ;; TODO: rule EveryEventHasDeclarationSite — no rule lift (deferred).
      ;; Structural intent: every Event primitive carries a non-empty
      ;; :declaration-sites payload. Violation kind
      ;; :4b/event-no-declaration-site (error).

      ;; TODO: rule DeclarationSitesShareShape — no rule lift (deferred).
      ;; Structural intent: all declaration sites for the same Event within
      ;; a module agree on parameter shape. Violation kind
      ;; :4b/event-shape-mismatch (error).

      ;; TODO: rule ProvidesNeedsExternalStimulus — no rule lift (deferred).
      ;; Structural intent: every :relation/provides edge from a Surface to
      ;; an Event requires at least one external_stimulus Trigger.
      ;; Violation kind :4b/provides-no-external-stimulus (error).

      ;; TODO: rule ExposesPathsResolve — no rule lift (deferred).
      ;; Structural intent: every exposes: path on a Surface resolves to a
      ;; known substrate endpoint. Violation kind :4b/exposes-unresolved (error).

      (invariant "EveryEventHasDeclarationSite"
        "Every Event primitive carries an Allium::Event tag whose payload
         :declaration-sites is non-empty. Events with no declaration site
         emit :4b/event-no-declaration-site (error)."
        (holds-that "every-event-has-at-least-one-declaration-site"))

      (invariant "DeclarationSitesShareShape"
        "All declaration sites for the same Event within a single module
         agree on parameter shape (arity plus typed-parameter sequence).
         Disagreement emits :4b/event-shape-mismatch (error)."
        (holds-that "event-declaration-sites-share-parameter-shape"))

      (invariant "ProvidesNeedsExternalStimulus"
        "For every :relation/provides edge from a Surface to an Event,
         the Event must have at least one :relation/triggers edge to a
         Rule with Trigger :kind 'external_stimulus'. Provides without
         such a consumer emit :4b/provides-no-external-stimulus (error)."
        (holds-that "provides-edge-requires-external-stimulus-trigger"))

      (invariant "ExposesPathsResolve"
        "Every exposes: path declared on a Surface resolves to a known
         substrate endpoint. Unresolved paths emit
         :4b/exposes-unresolved (error)."
        (holds-that "exposes-paths-resolve-to-known-endpoints"))

      (invariant "CheckIsPure"
        "The 4b check function is a pure derivation from the input
         Model — it reads only :primitives, :edges, :tag-apps, and
         :phase4-state."
        (holds-that "check-is-a-pure-function-of-model"))

      ;; Public entry point from rules_4b.boundary.
      (checker "check"
        "Run all four 4b event rules against the Model and return the
         aggregated Violation sequence."))))
