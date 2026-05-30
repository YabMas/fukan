(ns fukan.canvas.vocab.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [datascript.core :as d]
            [fukan.canvas.projection.canvas-source :as cs]
            [fukan.canvas.vocab.registry :as registry]))

(deftest registry-is-well-formed
  (testing "tag-definitions carry distinct tags and known families"
    (let [tags (mapv :tag registry/tag-definitions)]
      (is (= (count tags) (count (distinct tags))) "tags are distinct")
      (is (every? (fn [{:keys [family]}]
                    (contains? #{:Module :Affordance :Type nil} family))
                  registry/tag-definitions)
          "every family is Module/Affordance/Type or nil (marker)"))))

(deftest every-in-use-tag-is-defined
  (testing "every tag-application in the built substrate has a projected :tagdef"
    (let [db      (cs/build-substrate)
          in-use  (into #{} (map first) (d/q '[:find ?tag :where [_ :tagapp/tag ?tag]] db))
          defined (into #{} (map first) (d/q '[:find ?tag :where [_ :tagdef/tag ?tag]] db))]
      (is (seq in-use)  "there are tag-applications to check")
      (is (seq defined) "tag-definitions were projected into the db")
      (is (empty? (set/difference in-use defined))
          (str "tags used without a definition: " (set/difference in-use defined))))))
