(ns one-def.vocabularies
  "Two vocabularies declared by two calls inside ONE def — each is its own vocabulary. An
   ownership fixture; read as forms, never loaded."
  (:require [lexicon.core :as lexicon]))

(def Thing
  [:map [:thing/id :string]])

(def Other
  [:map [:other/id :string]])

(def vocabularies
  [(lexicon/vocabulary [Thing] {:name :things})
   (lexicon/vocabulary [Other] {:name :others})])
