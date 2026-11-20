(ns one-def-same-name.vocabularies
  "Two vocabularies of ONE name declared by two calls inside one def — a name has one owner. An
   ownership fixture; read as forms, never loaded."
  (:require [lexicon.core :as lexicon]))

(def Thing
  [:map [:thing/id :string]])

(def Other
  [:map [:other/id :string]])

(def vocabularies
  [(lexicon/vocabulary [Thing] {:name :same})
   (lexicon/vocabulary [Other] {:name :same})])
