(ns fukan.constraint.sort-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.constraint.sort :as sort]))

(deftest is-string-guard
  (is (sort/is-string? "foo"))
  (is (not (sort/is-string? 42))))

(deftest is-number-guard
  (is (sort/is-number? 42))
  (is (sort/is-number? 3.14))
  (is (not (sort/is-number? "42"))))

(deftest is-primitive-id-guard
  (is (sort/is-primitive-id? "m::Order"))
  (is (sort/is-primitive-id? "fukan/web/views::events::Pick"))
  (is (not (sort/is-primitive-id? "no-double-colon-here")))
  (is (not (sort/is-primitive-id? 42))))
