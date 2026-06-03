(ns fukan.canvas.projection.probe-code-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.structure :as s]
            [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.probe :refer [Finding]]))

(deftest finding-carries-shape-and-holds
  (testing "a Finding can declare a contract: a shape and a holds invariant"
    (let [db (s/with-structures
               (s/within-module "t"
                 (Kind "Str")
                 (Finding "F" (gating true)
                   (shape [Str])
                   (holds "the list is empty for a clean model"))))
          fid (ffirst (d/q '[:find ?f :where [?f :entity/name "F"] [?f :structure/of :Finding]] db))]
      (is (= "the list is empty for a clean model"
             (:val/holds (d/entity db fid)))
          "holds is stored as a scalar on the finding")
      (is (seq (d/q '[:find ?sh :in $ ?f
                      :where [?r :rel/from ?f] [?r :rel/kind :shape] [?r :rel/to ?sh]
                             [?sh :structure/of :Shape]] db fid))
          "shape is a Shape value node linked from the finding"))))
