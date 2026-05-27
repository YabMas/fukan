(ns canvas.vocabulary.allium.pipeline
  "Canvas port of vocabulary/allium/pipeline.allium.

   Scope: Phase 1+2 of the build pipeline — Allium source-root walk,
   per-file analysis, cross-file stub-unification (§3.6), and the
   K15a inline-primitive lift.

   Coverage:
     - rule LoadSource → vocab.behavioral/rule
     - 7 invariants: DeterministicFileOrder, PathCanonicalisation,
       DefaultsRegisteredBeforeAnalysis, StubUnification,
       AmbiguousStubsLeftAlone, InlineLiftIdempotence, PipelinePurity"
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.vocab.behavioral :refer [invariant rule]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "vocabulary.allium.pipeline"

      (rule "LoadSource"
        "Phase 1+2 of the build pipeline. Seed a fresh Model with the Allium
         TagDefinition catalogue and default RendererRegistrations; discover
         every .allium file under source_root in sorted order; per-file:
         derive coordinate, parse AST, extract use-alias map, run analyzer;
         stub-unification (§3.6); inline-primitive lift. Returns a validated
         Model. No Violations produced."
        (when LoadSource (source_root :String)))

      (invariant "DeterministicFileOrder"
        "Files are loaded in sorted absolute-path order. Same source-root
         contents always produce the same load sequence; the Model is a
         pure function of the source tree."
        (holds-that "deterministic-file-order-sorted"))

      (invariant "PathCanonicalisation"
        "Each file's raw use-alias paths (e.g. ./views/spec.allium,
         ../model/spec.allium) are canonicalised relative to the host
         file's coordinate before reaching the analyzer. The canonical
         form is the root-relative path with the .allium extension
         stripped, lining up with coordinate-of's output for the same file."
        (holds-that "use-alias-paths-canonicalised-before-analyzer"))

      (invariant "DefaultsRegisteredBeforeAnalysis"
        "The TagDefinition catalogue and RendererRegistration defaults
         are seeded onto the Model before any file is analyzed. The
         analyzer relies on every Allium::* tag being registered when
         it constructs TagApplications; an unregistered tag would fail
         payload-schema validation at application time."
        (holds-that "defaults-registered-before-analysis"))

      (invariant "StubUnification"
        "After per-file analysis, every Container tagged Allium::
         ExternalEntity is a candidate for merging into a real Container
         with the same local-name. When exactly one real Container
         matches across the analyzed corpus, the stub is removed and all
         edges, tag applications, and inline Type references are
         retargeted to the canonical id. The Allium::ExternalEntity tag
         is stripped from the canonical."
        (holds-that "stub-unification-after-per-file-analysis"))

      (invariant "AmbiguousStubsLeftAlone"
        "A stub with two or more candidate real Containers is left
         unresolved with a warning to stderr — the methodology layer
         must disambiguate. A stub with no candidates is left in place
         as a legitimate opaque foreign reference."
        (holds-that "ambiguous-stubs-left-unresolved"))

      (invariant "InlineLiftIdempotence"
        "The inline-primitive lift is safe to re-run: every host
         primitive's inline Intent / Clause / Boundary value is
         registered (or re-registered) at top-level :primitives under its
         own id. A repeat invocation overwrites with the same value. The
         lift runs after stub-unification so lifted ids reflect post-merge
         host coordinates."
        (holds-that "inline-lift-idempotent"))

      (invariant "PipelinePurity"
        "The pipeline is a pure function of the source tree (apart from
         stderr warnings for ambiguous stubs and dotted-trigger fall-
         throughs). No global state, no caches, no I/O outside the file
         walk and parse calls."
        (holds-that "pipeline-pure-of-source-tree")))))
