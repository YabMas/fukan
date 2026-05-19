(ns fukan.vocabulary.boundary.tags
  "All Boundary::* TagDefinitions registered as a single vector for the
   analyzer to apply onto kernel content. Per MODEL.md §8.2."
  (:require [fukan.model.vocabulary :as v]
            [fukan.model.type :as t]))

;; -- Payload schemas ----------------------------------------------------------

(def ^:private exported-list-schema
  (t/make-collection (t/make-scalar "String") :sequential))

(def ^:private binding-payload-schema
  (t/make-composite-inline
    [(t/make-field-spec "returns_expression" (t/make-scalar "Text") true)]))

(def ^:private module-api-payload-schema
  (t/make-composite-inline
    [(t/make-field-spec "exported" exported-list-schema false)]))

(def ^:private subsystem-payload-schema
  (t/make-composite-inline
    [(t/make-field-spec "name" (t/make-scalar "String") false)]))

(def ^:private exports-payload-schema
  (t/make-composite-inline
    [(t/make-field-spec "exported" exported-list-schema false)]))

;; -- Registry -----------------------------------------------------------------

(def boundary-tag-definitions
  [(v/make-tag-definition
     {:namespace "Boundary" :name "Function"
      :applies-to :target/operation})

   (v/make-tag-definition
     {:namespace "Boundary" :name "Binding"
      :applies-to :target/edge
      :payload-schema binding-payload-schema})

   (v/make-tag-definition
     {:namespace "Boundary" :name "ModuleApi"
      :applies-to :target/container
      :payload-schema module-api-payload-schema})

   (v/make-tag-definition
     {:namespace "Boundary" :name "Subsystem"
      :applies-to :target/container
      :payload-schema subsystem-payload-schema})

   (v/make-tag-definition
     {:namespace "Boundary" :name "Exports"
      :applies-to :target/container
      :payload-schema exports-payload-schema})])
