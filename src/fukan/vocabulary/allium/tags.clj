(ns fukan.vocabulary.allium.tags
  "All Allium::* TagDefinitions registered as a single vector for the
   analyzer to apply onto kernel content. Per MODEL.md §8.1."
  (:require [fukan.model.vocabulary :as v]
            [fukan.model.type :as t]))

;; -- Private payload schemas --------------------------------------------------

(def ^:private trigger-kind-enum
  (t/make-enum ["external_stimulus" "chained"
                "creation" "state_transition" "becomes" "temporal" "derived"]))

(def ^:private actor-payload-schema
  (t/make-composite-inline
    [(t/make-field-spec "identified_by" (t/make-scalar "Text") true)
     (t/make-field-spec "within" (t/make-scalar "Text") true)]))

(def ^:private surface-payload-schema
  (t/make-composite-inline
    [(t/make-field-spec "facing"
       (t/make-union [(t/make-ref-kernel-primitive #{:actor})
                      (t/make-ref-kernel-primitive #{:container})])
       true)
     (t/make-field-spec "context"
       (t/make-ref-kernel-primitive #{:container} {:where #{"Allium::Entity"}})
       true)
     (t/make-field-spec "related"
       (t/make-collection
         (t/make-ref-kernel-primitive #{:container} {:where #{"Allium::Surface"}})
         :sequential)
       false)
     (t/make-field-spec "timeout" (t/make-scalar "String") true)]))

(def ^:private trigger-payload-schema
  (t/make-composite-inline
    [(t/make-field-spec "kind" trigger-kind-enum false)]))

;; -- Tag registry -------------------------------------------------------------

(def allium-tag-definitions
  [;; Container classifications
   (v/make-tag-definition
     {:namespace "Allium" :name "Module"
      :applies-to :target/container})
   (v/make-tag-definition
     {:namespace "Allium" :name "Entity"
      :applies-to :target/container})
   (v/make-tag-definition
     {:namespace "Allium" :name "Value"
      :applies-to :target/container})
   (v/make-tag-definition
     {:namespace "Allium" :name "Variant"
      :applies-to :target/container})
   (v/make-tag-definition
     {:namespace "Allium" :name "ExternalEntity"
      :applies-to :target/container})

   ;; Primitive classifications with payload
   (v/make-tag-definition
     {:namespace "Allium" :name "Actor"
      :applies-to :target/actor
      :payload-schema actor-payload-schema})

   ;; Rule primitive
   (v/make-tag-definition
     {:namespace "Allium" :name "Rule"
      :applies-to :target/rule})

   ;; Edge tags
   (v/make-tag-definition
     {:namespace "Allium" :name "Trigger"
      :applies-to :target/edge
      :payload-schema trigger-payload-schema})

   ;; Boundary-only Container patterns with payload
   (v/make-tag-definition
     {:namespace "Allium" :name "Surface"
      :applies-to :target/container
      :payload-schema surface-payload-schema})
   (v/make-tag-definition
     {:namespace "Allium" :name "Contract"
      :applies-to :target/container})

   ;; Edge shading (optional methodology metadata)
   (v/make-tag-definition
     {:namespace "Allium" :name "Provides"
      :applies-to :target/edge})
   (v/make-tag-definition
     {:namespace "Allium" :name "Exposes"
      :applies-to :target/edge})
   (v/make-tag-definition
     {:namespace "Allium" :name "Fulfils"
      :applies-to :target/edge})
   (v/make-tag-definition
     {:namespace "Allium" :name "Demands"
      :applies-to :target/edge})

   ;; Typed call Operation
   (v/make-tag-definition
     {:namespace "Allium" :name "Call"
      :applies-to :target/operation})

   ;; Event primitive
   (v/make-tag-definition
     {:namespace "Allium" :name "Event"
      :applies-to :target/event})

   ;; Source-clause tags on Expressions (substrate addresses inside Intent.assertions)
   (v/make-tag-definition
     {:namespace "Allium" :name "Invariant"
      :applies-to :target/substrate})
   (v/make-tag-definition
     {:namespace "Allium" :name "Requires"
      :applies-to :target/substrate})
   (v/make-tag-definition
     {:namespace "Allium" :name "Let"
      :applies-to :target/substrate})
   (v/make-tag-definition
     {:namespace "Allium" :name "Ensures"
      :applies-to :target/substrate})

   ;; Source-clause tags on Clause primitives (annotation prose)
   (v/make-tag-definition
     {:namespace "Allium" :name "ContractInvariant"
      :applies-to :target/clause})
   (v/make-tag-definition
     {:namespace "Allium" :name "SurfaceGuarantee"
      :applies-to :target/clause})
   (v/make-tag-definition
     {:namespace "Allium" :name "Guidance"
      :applies-to :target/clause})])
