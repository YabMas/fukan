(ns canvas.target.clojure.address
  "Canvas port of target/clojure/address.allium + address.boundary.

   Coverage:
     - value CanonicalAddress       → construction/record (2 String fields)
     - fn module_ns                 → construction/function
     - fn local_name                → construction/function
     - fn canonical                 → construction/function (cross-module refs)
     - 4 invariants                 → vocab.behavioral/invariant each

   Notes:
     - Cross-module type refs: :registry/Registry, :address/CanonicalAddress.
     - No rule declarations in address.allium — this module has only functions
       and invariants.
     - canonical returns :address/CanonicalAddress; the module emits a
       :references relation automatically for that cross-module type."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "target.clojure.address"

      ;; ── Value Types ──────────────────────────────────────────────────────

      (record "CanonicalAddress"
        "A resolved target-language address: a module namespace plus the
         local name within that namespace. Consumed by the Analyzer when
         emitting Code.* artifact identities and by the Projector when
         assembling a Blueprint."
        (field ns   :String)
        (field name :String))

      ;; ── Invariants ───────────────────────────────────────────────────────

      (invariant "DeterministicResolution"
        "Address resolution is a pure function of (project registry,
         primitive kind, projection kind, module coord, primitive label).
         The same inputs always yield the same address. No file I/O, no
         model lookups, no global state."
        (holds-that "address-resolution-is-pure"))

      (invariant "RootPrefixHonoured"
        "module_ns prefixes the dotted module coord with the project
         registry's root_prefix when non-empty, and uses the dotted coord
         verbatim when the prefix is empty (the fukan-on-fukan
         self-referential case)."
        (holds-that "root-prefix-honoured-in-module-ns"))

      (invariant "ProjectionKindPartition"
        "local_name partitions projection kinds into two families.
         DataStructure-shaped projections (schema) preserve the primitive
         label verbatim. Function-shaped projections (rule, operation,
         invariant, test) apply the project's mechanical identifier
         transliteration. An unhandled projection kind raises rather than
         guessing."
        (holds-that "projection-kind-partition-in-local-name"))

      (invariant "TestProjectionSuffix"
        "Test projections append the conventional test suffix to both the
         namespace and the local name, so a primitive's test artifact lands
         at a sibling test namespace rather than alongside its production
         projection."
        (holds-that "test-projection-appends-suffix"))

      ;; ── Public Functions ─────────────────────────────────────────────────

      (function "module_ns"
        "Compute the target-language module namespace from a module coord
         and the project registry's root_prefix. Mechanical concatenation."
        (takes [registry     :registry/Registry
                module_coord :String])
        (gives :String))

      (function "local_name"
        "Compute the local in-namespace name for a primitive given its kind
         and the requested projection kind. DataStructure-shaped projections
         preserve the primitive label; function-shaped projections apply the
         project's identifier transliteration."
        (takes [primitive_kind  :String
                projection_kind :String
                primitive_label :String])
        (gives :String))

      (function "canonical"
        "Build the full canonical address. Test projections append the
         conventional test suffix to both the namespace and the name."
        (takes [registry        :registry/Registry
                primitive_kind  :String
                projection_kind :String
                module_coord    :String
                primitive_label :String])
        (gives :address/CanonicalAddress)))))
