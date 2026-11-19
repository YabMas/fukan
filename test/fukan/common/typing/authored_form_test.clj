(ns fukan.common.typing.authored-form-test
  "An authored value of a slot the dialect reads is the dialect's, whole. The kernel has a
   shorthand of its own — a two-element, symbol-headed vector is a `[label target]` pair — and
   malli has forms of exactly that shape (`[int? {:min 1}]`, a predicate carrying properties).
   The kernel used to take those apart before the dialect saw them."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.common]
            [fukan.common.typing.malli :as malli]
            [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.cozo.query :as cq]))

(Kind Count "A predicate carrying properties, as the whole of a shape." [int? {:min 1}])

(Operation tally "The same form as the whole of a signature slot's value is read, not split."
  {:signature [:=> [:cat [int? {:min 1}]] Count]})

(deftest a-symbol-headed-malli-form-is-read-whole
  (let [db    (build/model->cozo ['fukan.common.typing.authored-form-test] {:roots []})
        shape (ffirst (cq/q '[:find ?s :where [?k :entity/name "Count"] [?r :rel/from ?k]
                              [?r :rel/kind :shape] [?r :rel/to ?s]] db))]
    (is (= [] (law/check db)))
    (testing "and comes back as written"
      (is (= '[int? {:min 1}] (malli/render db shape))))))

(deftest what-is-no-malli-form-is-refused-rather-than-read-as-a-label
  (is (re-find #"keyword head"
               (try (str (macroexpand '(fukan.common.vocab.code.operation/Operation nope
                                         {:signature [Foo Bar]})))
                    (catch Exception e (ex-message (or (ex-cause e) e)))))))
