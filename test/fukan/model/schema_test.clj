(ns fukan.model.schema-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.model.schema :as schema]))

(deftest dispatches-to-registered-dialect
  (schema/register-schema-dialect!
    {:render (fn [_db eid] (str "rendered:" eid))})
  (is (= "rendered:42" (schema/render-schema :db 42))))

(deftest missing-op-is-nil
  ;; a dialect may register only some bridges; absent ops return nil, not throw
  (schema/register-schema-dialect! {:render (fn [_ _] :ok)})
  (is (nil? (schema/parse-schema [:int]))))

(deftest adheres-absent-is-nil
  ;; the :adheres? bridge is part of the dialect contract but unregistered here;
  ;; schema-adheres? must no-op to nil, not throw
  (schema/register-schema-dialect! {:render (fn [_ _] :ok)})
  (is (nil? (schema/schema-adheres? :db 1 :extracted))))
