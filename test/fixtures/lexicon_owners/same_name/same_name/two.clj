(ns same-name.two
  "One of two vocabularies in an ownership fixture. Read as forms; never loaded."
  (:require [lexicon.core :as lexicon]
            [same-name.shapes :as shapes]))

(def vocabulary
  (lexicon/vocabulary [shapes/Other] {:name :same}))
