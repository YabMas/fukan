(ns same-var.one
  "One of two vocabularies in an ownership fixture. Read as forms; never loaded."
  (:require [lexicon.core :as lexicon]
            [same-var.shapes :as shapes]))

(def vocabulary
  (lexicon/vocabulary [shapes/Thing] {:name :one}))
