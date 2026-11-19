(ns app.domain
  "A project's vocabulary drawing on a library's, which lives outside the project's source paths.
   Read as forms; never loaded."
  (:require [lexicon.core :as lexicon]
            [fixtures.lexicon-classpath.library.desk :as desk]
            [fixtures.lexicon-classpath.library.vocabulary :as library]))

(def Visit
  [:map
   [:visit/id :string]
   [:visit/ticket {:lexicon/ref :library.ticket} :string]])

(def vocabulary
  (lexicon/vocabulary [Visit] {:name :app :uses [library/vocabulary desk/seating]}))
