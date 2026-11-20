(ns web.routing
  "Deciding what a request is for. It reads the part of a request that says so, and no more."
  (:require [clojure.string :as str]
            [malli.experimental :as mx]
            [web.vocabulary]))

(mx/defn route-key :- :string
  "What a request asks for, as one word a table of handlers can be keyed by."
  [request :- :web/route]
  (str (name (:web.request/method request)) " " (str/replace (:web.request/path request) #"/\d+" "/:id")))
