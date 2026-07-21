(ns fukan.canvas.core.declarations-test
  "The closed declaration algebra + sdef->declarations adapter."
  (:require [clojure.test :refer [deftest is]]
            [fukan.canvas.core.structure :as s]))

(deftest declaration-lowering-fails-closed
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown declaration kind"
                        (#'s/lower-declaration {:kind ::probe} {:tag ::Subject}))
      "vocabulary extends through kernel forms, not by installing evaluator semantics"))

(deftest adapter-covers-a-slot-and-a-law
  (let [sdef  {:tag :fukan.canvas.core.declarations-test/T
               :slots [{:rel :calls :card :many :target :fukan.canvas.core.declarations-test/T}]
               :laws  [{:desc "d" :offenders '[?x] :where '[]}]}
        kinds (set (map :kind (s/sdef->declarations sdef)))]
    (is (contains? kinds :kind))
    (is (contains? kinds :slot))
    (is (contains? kinds :free-law))))

(deftest closures-are-the-compilers
  (let [sdef  {:tag :fukan.canvas.core.declarations-test/T
               :slots [{:rel :tcalls :card :many :target :fukan.canvas.core.declarations-test/T}]}
        terms (s/terms-of [sdef])]
    (is (some #(= (first %) '(tcalls+ ?a ?b)) terms)
        "every binary relation's closure is emitted unconditionally — no :transitive declaration;
         a query pays for it only when its reachability closure references it")))

(deftest adapter-omits-node-kind-for-derived-concepts
  (is (not (contains? (set (map :kind (s/sdef->declarations {:tag ::R :realized-as '[[?e :x]]})))
                      :kind))))

(deftest closed-relation-heads-reject-other-contributors
  (let [closed {:tag :closed-view :slots [] :laws [] :relation-element true
                :relation-incl {:incl :eq :expr :base}}
        feeder {:tag ::Feeder :slots [{:rel :closed-view :card :many :target ::Feeder}] :laws []}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed relation :closed-view"
                          (s/terms-of [closed feeder]))
        "an :eq view is exact: an extensional slot may not also feed its head")))

(deftest derived-relations-are-closed-views
  (let [closed {:tag :closed-derived :slots [] :laws [] :relation-element true
                :derived-rule {:head '[?a ?b] :bodies ['[(base ?a ?b)]]}}
        feeder {:tag :species :slots [] :laws [] :relation-element true
                :relation-incl {:incl :sub :expr :closed-derived}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed relation :closed-derived"
                          (s/terms-of [closed feeder]))
        "a downstream :sub inclusion may feed only an open head")))

(deftest sup-relation-heads-remain-open
  (let [open   {:tag :open-view :slots [] :laws [] :relation-element true
                :relation-incl {:incl :sup :expr :base}}
        feeder {:tag ::Feeder :slots [{:rel :open-view :card :many :target ::Feeder}] :laws []}
        terms  (s/terms-of [open feeder])]
    (is (some #(= '(open-view ?a ?b) (first %)) terms))
    (is (some #(and (= '(open-view ?a ?b) (first %))
                    (= '(base ?a ?b) (second %)))
              terms)
        ":sup contributes its definition without closing the head")))
