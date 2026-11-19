(ns fixtures.lexicon-classpath.library.desk
  "A LIBRARY namespace holding TWO vocabularies, only one of which the project using it names.
   Every vocabulary and view declared here enters the model with the file. Read as forms;
   never loaded."
  (:require [lexicon.core :as lexicon]))

(def Seat
  [:map
   [:library.seat/id :string]])

(def Clerk
  [:map
   [:library.clerk/id :string]
   [:library.clerk/name :string]])

(def seating
  (lexicon/vocabulary [Seat] {:name :seating}))

(def staff
  (lexicon/vocabulary [Clerk] {:name :staff}))

(def staff-views
  (lexicon/views staff {:staff/badge [:library.clerk/name]}))
