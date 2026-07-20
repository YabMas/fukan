(ns fukan.canvas.core.declarations-test
  "The declaration registry + sdef->declarations adapter — the means-of-growth seam (Stage A)."
  (:require [clojure.test :refer [deftest is]]
            [fukan.canvas.core.structure :as s]))

(deftest registry-dispatches
  (s/register-declaration! ::probe (fn [_decl _sdef] {:terms [[(list 'probe '?x)]] :laws []}))
  (is (contains? (s/declaration-kinds) ::probe)))

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
