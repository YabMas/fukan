(ns fukan.cozo.vocab-index-test
  "The compiled vocab-rule index is a pure function of the vocabulary and the db's bucket index,
   and it was being rebuilt on every query — 86% of a trivial query's cost. These pin the two
   halves of the cache key, because a memo that never invalidates answers a moved vocabulary out
   of the old one."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.common]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]))

(defstructure VIThing "Fixture: a node the index has a kind rule for." {:size :int})

(VIThing vi-one {:size 1})

(deftest an-unchanged-vocabulary-compiles-once
  (is (identical? (cq/vocab-index) (cq/vocab-index))
      "two calls on the same vocabulary answer with the same compiled index"))

(deftest a-registration-retires-the-index
  (testing "the generation is what makes the memo safe: a vocabulary that gained a sort has gained
            a kind rule, and serving the previous compile would answer a query out of a grammar
            that no longer exists."
    (let [before (s/vocabulary-generation)
          idx    (cq/vocab-index)]
      (eval '(fukan.canvas.core.structure/defstructure VIProbe "Fixture: registered mid-test."))
      (is (> (s/vocabulary-generation) before) "registering bumps the generation")
      (is (not (identical? idx (cq/vocab-index))) "…and the index is recompiled"))))

(deftest a-different-bucket-index-retires-it-too
  (testing "the rules are the vocabulary's, but the stored relation each clause READS is the db's:
            an attribute that lives in one bucket compiles to direct access and one that spans
            several to a union helper, so the bucket index belongs in the key."
    (let [d (build/vars->cozo [#'vi-one])]
      (is (not (identical? (binding [cq/*attr-buckets* nil] (cq/vocab-index))
                           (binding [cq/*attr-buckets* (cq/buckets-of d)] (cq/vocab-index))))
          "an index compiled with no buckets in force is not the one the db's laws want"))))
