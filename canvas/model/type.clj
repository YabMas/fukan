(ns canvas.model.type
  "Canvas port of model/type.allium + type.boundary.

   Coverage:
     - 2 invariants from type.allium → vocab.behavioral/invariant each:
         ConstructorsProduceType, TargetLanguageNeutral
     - Type vocabulary constructors:
         make_field_spec, make_scalar, make_enum, make_composite_named,
         make_composite_inline, keyed, make_collection, make_union,
         make_ref_kernel_primitive, make_ref_substrate

   Notes:
     - The Type vocabulary value types themselves (six cases plus FieldSpec,
       CollectionSemantics, RefTarget) live in canvas.model.spec.
     - Per-target-language type translation is a project-layer concern.
     - Scalar name is methodology-supplied and opaque to the substrate."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "model.type"

      ;; Invariants from type.allium

      (invariant "ConstructorsProduceType"
        "Each constructor yields a Type value matching its corresponding variant
         in model/spec.allium: make_scalar -> ScalarType, make_enum -> EnumType,
         make_composite_named / make_composite_inline -> CompositeType,
         make_collection -> CollectionType, make_union -> UnionType,
         make_ref_kernel_primitive / make_ref_substrate -> RefType."
        (holds-that "type-constructors-produce-matching-spec-variant"))

      (invariant "TargetLanguageNeutral"
        "Scalar name is methodology-supplied and opaque to the substrate.
         Constructors commit to structural shape only — they never decide which
         target-language type a Scalar maps to. That translation lives in the
         project layer."
        (holds-that "scalar-name-opaque-to-substrate-no-target-lang-decision"))

      ;; Public functions from type.boundary

      (function "make_field_spec"
        "FieldSpec for a Composite-inline shape. Optionality is at slot level
         (not a Type case)."
        (takes [name       :String
                type_value :Any
                optional   :Boolean])
        (gives :Any))

      (function "make_scalar"
        "Atomic Scalar with methodology-supplied opaque name."
        (takes [name :String])
        (gives :Any))

      (function "make_enum"
        "Closed Enum of literal string values."
        (takes [values (list-of :String)])
        (gives :Any))

      (function "make_composite_named"
        "Composite whose shape comes from a value-typed Container's fields."
        (takes [container_id :String])
        (gives :Any))

      (function "make_composite_inline"
        "Composite with anonymous inline fields."
        (takes [field_specs (list-of :Any)])
        (gives :Any))

      (function "keyed"
        "CollectionSemantics constructor for Keyed(K)."
        (takes [key_type :Any])
        (gives :Any))

      (function "make_collection"
        "Collection of `of` with Sequential | Unique | Keyed(K) semantics.
         Pass the enum keyword for Sequential / Unique; pass (keyed K) for
         Keyed."
        (takes [of        :Any
                semantics :Any])
        (gives :Any))

      (function "make_union"
        "Sum of distinct Types. Cases stay structurally distinct."
        (takes [types (list-of :Any)])
        (gives :Any))

      (function "make_ref_kernel_primitive"
        "Reference to a kernel primitive of one of kinds. opts may carry a
         :where set of TagRefs filtering admissible targets."
        (takes [kinds (set-of :String)
                opts  (optional :Any)])
        (gives :Any))

      (function "make_ref_substrate"
        "Reference to a sub-substrate slot on a primitive of within_kind.
         v0 populates :field; :parameter is shape-committed but deferred."
        (takes [within_kind :String
                slot_kinds  (set-of :String)])
        (gives :Any)))))
