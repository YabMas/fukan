(ns fukan.canvas.core.substrate-test
  "The kernel provenance stamp — fact-stratum on extraction InstanceValue trees."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.substrate :as sub]))

(deftest stamp-stratum-walks-non-value-instances
  (testing "stamp-stratum marks the node and every nested non-value instance; ^:value stays stratum-free"
    (let [eff (sub/->InstanceValue :E "io" nil nil nil true)      ; a ^:value instance (content-deduped)
          op  (sub/->InstanceValue :Op "f" nil {:val/private false}
                                   [{:rk :performs :card :many :targets [eff]}] false)
          m   (sub/->InstanceValue :M "mod" nil nil
                                   [{:rk :child :card :many :targets [op]}] false)
          s   (sub/stamp-stratum m)]
      (is (true? (get-in s [:scalars :val/extracted])) "the root module is stamped")
      (is (true? (get-in s [:clauses 0 :targets 0 :scalars :val/extracted])) "the nested op is stamped")
      (is (not (get-in s [:clauses 0 :targets 0 :clauses 0 :targets 0 :scalars :val/extracted]))
          "the ^:value effect is NOT stamped — values are stratum-free (stamping would fork content keys)")
      (is (= :val/extracted sub/stratum-attr)))))
