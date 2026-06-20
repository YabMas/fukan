(ns fukan.canvas.projection.finding-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.projection.finding :as f]))

(deftest observation-and-finding-constructors
  (testing "an observation pairs a focus sub-graph with a tag and a note"
    (let [o (f/observation #{1 2} :pattern "3× Foo -[bar]-> Baz")]
      (is (= #{1 2} (:focus o)))
      (is (= :pattern (:as o)))
      (is (= "3× Foo -[bar]-> Baz" (:note o)))))
  (testing "a finding bundles lens + observations"
    (let [fdg (f/finding "patterns" [(f/observation #{1} :pattern "p")])]
      (is (= "patterns" (:lens fdg)))
      (is (= 1 (count (:observations fdg)))))))

(deftest finding->text-is-the-trivial-text-projection
  (testing "rendering a finding to text is just its observations' notes"
    (let [fdg (f/finding "tar-pit"
                [(f/observation #{1} :hotspot "9 edges: Foo")
                 (f/observation #{2} :hotspot "7 edges: Bar")])]
      (is (= ["9 edges: Foo" "7 edges: Bar"] (f/finding->text fdg))))))
