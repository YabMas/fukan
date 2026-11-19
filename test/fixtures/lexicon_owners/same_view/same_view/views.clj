(ns same-view.views
  "Two `views` calls declaring one view keyword — a name has one owner. An ownership fixture;
   read as forms, never loaded."
  (:require [lexicon.core :as lexicon]))

(def Thing
  [:map [:thing/id :string]])

(def vocabulary
  (lexicon/vocabulary [Thing] {:name :things}))

(def first-views
  (lexicon/views vocabulary {:use/summary [:thing/id]}))

(def second-views
  (lexicon/views vocabulary {:use/summary [:thing/id]}))
