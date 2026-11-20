(ns web.vocabulary
  "How a visit to a site is handled: a request, and the session it belongs to. Nothing here
   says what a site is about, or who a session is for — that is the using system's to say."
  (:require [lexicon.core :as lexicon]
            [lexicon.install :as install]))

(def Session
  "One visitor's ongoing visit."
  [:map
   [:web.session/id :string]
   [:web.session/started :int]])

(def Request
  "One thing asked of the site."
  [:map
   [:web.request/id :string]
   [:web.request/method [:enum :get :post]]
   [:web.request/path :string]
   [:web.request/session {:lexicon/ref :web.session} [:maybe :string]]])

(def vocabulary
  (install/vocabulary! (lexicon/vocabulary [Session Request] {:name :web})))

;; The views it publishes: what a step in handling a request needs of it.
(def views
  (install/views!
   (lexicon/views vocabulary
                  {:web/route  [:web.request/method :web.request/path]
                   :web/caller [:web.request/id :web.request/session]})))
