(ns fukan.canvas.core.typing-test
  "The type-dialect registry: merge-per-key registration, lenient absent bridges,
   and the strict `value-valid?` refined-slot bridge. The fixture saves/clears/
   restores the (global) registry so these tests neither see nor leave foreign
   registrations."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [fukan.canvas.core.typing :as typing]))

(use-fixtures :each
  (fn [t]
    (let [saved (typing/registered-dialect)]
      (typing/clear-type-dialect!)
      (try (t)
           (finally
             (typing/clear-type-dialect!)
             (when saved (typing/register-type-dialect! saved)))))))

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
  (is (nil? (typing/type-adheres? '[:=> [:cat] :int] '[:=> [:cat] :int]))))

(deftest registrations-merge-per-key
  ;; capabilities compose across registrars (a grammar brings :valid?, a
  ;; composition root :render); re-registering a key replaces it
  (typing/register-type-dialect! {:render (fn [_ _] :first)})
  (typing/register-type-dialect! {:valid? (fn [_ _] true)})
  (is (= :first (typing/render-type :db 1)) "earlier key survives a later merge")
  (is (true? (typing/value-valid? [:enum "a"] "a")))
  (typing/register-type-dialect! {:render (fn [_ _] :second)})
  (is (= :second (typing/render-type :db 1)) "re-registering a key replaces it"))

(deftest value-valid-throws-without-a-dialect
  ;; unlike the lenient bridges, an unverifiable refined-slot law must be LOUD
  (is (thrown-with-msg? Exception #"registered.*type dialect"
        (typing/value-valid? [:enum "a"] "a"))))
