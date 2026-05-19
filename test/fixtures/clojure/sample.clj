(ns fukan.test.fixture.sample)

(def Order
  [:map
   [:id :string]
   [:total :int]])

(defn process-order [order]
  {:id (:id order)
   :status "received"})

(defn helper-fn []
  ;; A helper that isn't a canonical spec realisation.
  42)
