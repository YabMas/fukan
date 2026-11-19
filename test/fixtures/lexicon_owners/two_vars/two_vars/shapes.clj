(ns two-vars.shapes
  "Shapes for an ownership fixture. Read as forms by the Lexicon producer's tests; never loaded.")

(def Thing
  [:map [:thing/id :string]])

(def Other
  [:map [:other/id :string]])

(def AlsoThing
  "A second var whose keys share the namespace `thing`: the same shape name, declared again."
  [:map [:thing/id :string] [:thing/label :string]])
