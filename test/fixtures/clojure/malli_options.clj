(ns fukan.test.fixture.malli-options)

(def ServerOpts
  [:map {:description "HTTP server configuration."}
   [:port {:optional true :description "TCP port to bind."} :int]
   [:host :string]])
