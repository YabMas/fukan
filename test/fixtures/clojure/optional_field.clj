(ns fukan.test.fixture.optional-field)

(def Thing
  [:map
   [:a :int]
   [:b {:optional true} :string]
   [:c {:optional true :description "carry an options map and stay optional"} :keyword]
   [:d :boolean]])
