(ns fukan.model.typing-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.model.typing :as typing]))

(deftest dispatches-to-registered-dialect
  (typing/register-type-dialect!
    {:render (fn [_db eid] (str "rendered:" eid))})
  (is (= "rendered:42" (typing/render-type :db 42))))

(deftest missing-op-is-nil
  ;; a dialect may register only some bridges; absent ops return nil, not throw
  (typing/register-type-dialect! {:render (fn [_ _] :ok)})
  (is (nil? (typing/parse-type [:int]))))

(deftest adheres-absent-is-nil
  ;; the :adheres? bridge is part of the dialect contract but unregistered here;
  ;; type-adheres? must no-op to nil, not throw
  (typing/register-type-dialect! {:render (fn [_ _] :ok)})
  (is (nil? (typing/type-adheres? :db 1 :extracted))))
