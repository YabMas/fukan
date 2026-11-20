(ns fixtures.lexicon-classpath.library.vocabulary
  "A LIBRARY's vocabulary, as a project using it finds it: on the classpath, not under the
   project's source paths. Its namespace is its path below `test/`, which the test classpath
   holds, so it resolves as a resource wherever the checkout lies. Read as forms; never loaded."
  (:require [lexicon.core :as lexicon]))

(def Ticket
  [:map
   [:library.ticket/id :string]
   [:library.ticket/issued :int]])

(def vocabulary
  (lexicon/vocabulary [Ticket] {:name :library}))

(def views
  (lexicon/views vocabulary {:library/stub [:library.ticket/id]}))

(defn not-extracted
  "Code beside the library's words: the project using the library does not extract it."
  [x]
  x)
