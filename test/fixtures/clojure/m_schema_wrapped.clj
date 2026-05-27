(ns fukan.test.fixture.m-schema-wrapped
  (:require [malli.core :as m]))

(def Node
  (m/schema
   [:map
    [:id    :string]
    [:label :string]]))
