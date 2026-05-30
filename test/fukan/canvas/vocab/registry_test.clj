(ns fukan.canvas.vocab.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [datascript.core :as d]
            [fukan.canvas.construction]
            [fukan.canvas.vocab.behavioral]
            [fukan.canvas.vocab.lifecycle]
            [fukan.canvas.vocab.validation]
            [fukan.canvas.vocab.event]
            [fukan.canvas.projection.canvas-source :as cs]
            [fukan.canvas.vocab.registry :as registry]))

(deftest registry-is-well-formed
  (testing "registered tag-definitions carry distinct tags and known families"
    (let [defs (registry/all)
          tags (mapv :tag defs)]
      (is (seq defs) "vocabularies registered their terms")
      (is (= (count tags) (count (distinct tags))) "tags are distinct")
      (is (every? (fn [{:keys [family]}]
                    (contains? #{:Module :Affordance :Type :State nil} family))
                  defs)
          "every family is Module/Affordance/Type/State or nil (marker)"))))

(deftest every-in-use-tag-is-defined
  (testing "every tag-application in the built substrate has a projected :tagdef"
    (let [db      (cs/build-substrate)
          in-use  (into #{} (map first) (d/q '[:find ?tag :where [_ :tagapp/tag ?tag]] db))
          defined (into #{} (map first) (d/q '[:find ?tag :where [_ :tagdef/tag ?tag]] db))]
      (is (seq in-use)  "there are tag-applications to check")
      (is (seq defined) "tag-definitions were projected into the db")
      (is (empty? (set/difference in-use defined))
          (str "tags used without a definition: " (set/difference in-use defined))))))
