(ns fukan.canvas.vocab.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.construction]
            [fukan.canvas.vocab.behavioral]
            [fukan.canvas.vocab.lifecycle]
            [fukan.canvas.vocab.validation]
            [fukan.canvas.vocab.event]
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

;; (every-in-use-tag-is-defined removed — it validated tag usage across the
;;  full canvas, which is pruned during the lean-kernel rebuild. The in-use
;;  vs defined coverage check returns with the new examples in Tier 2.)
