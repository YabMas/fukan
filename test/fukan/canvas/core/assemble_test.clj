(ns fukan.canvas.core.assemble-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.core.assemble :as a]))

(s/defstructure* Thing "t")
(def t5-foo (Thing "foo"))     ; an instance-bearing var
(def t5-plain 42)              ; not an instance

(deftest collect-finds-instance-vars
  (let [found (a/collect ['fukan.canvas.core.assemble-test])
        names (set (map (fn [[v _]] (:name (meta v))) found))]
    (is (contains? names 't5-foo))
    (is (not (contains? names 't5-plain)))))
