(ns canvas.model.artifact
  "Canvas port of model/artifact.allium + artifact.boundary.

   Coverage:
     - 1 invariant: ArtifactIdentityIsTriple → vocab.behavioral/invariant
     - fn make_code_function         → construction/function
     - fn make_code_data_structure   → construction/function
     - fn artifact_identity          → construction/function

   Notes:
     - Artifact ontology and ArtifactSubCase enum live in canvas.model.spec.
     - SourceLocation value type lives in canvas.model.spec.
     - Constructor signatures match artifact.boundary; behavioural invariant
       from artifact.allium."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "model.artifact"

      ;; Invariants from artifact.allium

      (invariant "ArtifactIdentityIsTriple"
        "artifact_identity returns the three-tuple (sub_case, language,
         qualified_name) per §7.3. SourceLocation and the public? flag are
         non-identifying: artifacts with equal identity tuples are the same
         artifact regardless of where they live on disk or whether they are
         public."
        (holds-that "artifact-identity-is-sub-case-language-qualified-name-triple"))

      ;; Public functions from artifact.boundary

      (function "make_code_function"
        "Construct a code/function artifact in the given target language.
         source_location and public are optional (pass nil)."
        (takes [language        :String
                qualified_name  :String
                source_location (optional :Any)
                public          (optional :Boolean)])
        (gives :Any))

      (function "make_code_data_structure"
        "Construct a code/data_structure artifact in the given target language.
         source_location is optional (pass nil)."
        (takes [language        :String
                qualified_name  :String
                source_location (optional :Any)])
        (gives :Any))

      (function "artifact_identity"
        "Returns the (sub_case, language, qualified_name) triple per §7.3.
         source_location is non-identifying."
        (takes [artifact :Any])
        (gives :Any)))))
