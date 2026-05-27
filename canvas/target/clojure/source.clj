(ns canvas.target.clojure.source
  "Canvas port of target/clojure/source.allium + source.boundary.

   Coverage:
     - value SourceSymbol     → construction/record (4 String fields)
     - fn find_clj_files      → construction/function
     - fn read_forms          → construction/function
     - fn extract_symbols     → construction/function (cross-module ref)
     - 4 invariants           → vocab.behavioral/invariant each

   Notes:
     - SourceSymbol.kind is a String discriminator (not a sum type);
       documented in the record docstring.
     - fn extract_symbols returns List<SourceSymbol> — expressed as
       (list-of :source/SourceSymbol).
     - fn read_forms returns List<Any> (opaque EDN forms)."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "target.clojure.source"

      ;; ── Value Types ──────────────────────────────────────────────────────

      (record "SourceSymbol"
        "One top-level definition discovered in a Clojure source file.
         The Analyzer keys these by (ns, name, kind) when looking up whether
         a spec primitive has a realising code artifact at its canonical
         address.

         kind is one of:
           \"function\"          — top-level public callable
           \"function_private\"  — top-level private callable
           \"data_structure\"    — top-level value binding (def-shaped schema)"
        (field ns   :String)
        (field name :String)
        (field kind :String)
        (field file :String))

      ;; ── Invariants ───────────────────────────────────────────────────────

      (invariant "ReadOnly"
        "Source walking never mutates the source tree, never writes caches,
         and never evaluates the forms it reads. The walk is a pure read
         over the filesystem at the time of invocation."
        (holds-that "source-walk-is-read-only"))

      (invariant "DeterministicWalk"
        "find_clj_files returns paths in a stable order across invocations
         on the same tree. Downstream symbol-extraction order — and therefore
         duplicate-address-violation reporting order — is reproducible."
        (holds-that "find-clj-files-stable-order"))

      (invariant "TopLevelOnly"
        "extract_symbols only emits records for top-level definitions whose
         first element names a recognised definition form. Macros that expand
         to definitions but appear as other forms at the textual top level are
         out of scope: they surface as absent projections rather than
         mis-attributed code, per DESIGN.md 'Couplings'."
        (holds-that "extract-symbols-top-level-forms-only"))

      (invariant "NoEval"
        "Forms are read as data only. The reader is configured with read-eval
         disabled, so a malicious or buggy source file can never cause
         execution during analysis."
        (holds-that "source-reader-no-eval"))

      ;; ── Public Functions ─────────────────────────────────────────────────

      (function "find_clj_files"
        "Walk a root directory and return absolute paths to all discoverable
         Clojure source files in a deterministic order."
        (takes [root :String])
        (gives (list-of :String)))

      (function "read_forms"
        "Read every top-level form from a Clojure source file as data,
         without evaluating any of them."
        (takes [path :String])
        (gives (list-of :Any)))

      (function "extract_symbols"
        "Read a Clojure source file and return one SourceSymbol record for
         every top-level public-function, private-function, or
         data-structure definition discovered."
        (takes [path :String])
        (gives (list-of :source/SourceSymbol))))))
