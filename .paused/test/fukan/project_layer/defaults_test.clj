(ns fukan.project-layer.defaults-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.project-layer.defaults :as defaults]
            [fukan.target.clojure.address :as addr]))

(deftest fukan-on-fukan-root-prefix-is-fukan
  (testing "fukan's own source lives under fukan.* — registry must reflect that"
    ;; Gap 2: fukan-on-fukan previously returned :root-prefix "" which broke
    ;; canonical-address derivation for fukan analysing itself (the actual
    ;; code is at fukan.infra.server, not infra.server).
    (let [reg (defaults/fukan-on-fukan)]
      (is (= "fukan" (:root-prefix reg))))))

(deftest fukan-on-fukan-derives-canonical-fukan-ns
  (testing "module-coord 'infra.server' → ns 'fukan.infra.server'"
    (let [reg (defaults/fukan-on-fukan)]
      (is (= "fukan.infra.server" (addr/module-ns reg "infra.server")))
      (is (= {:ns "fukan.infra.server" :name "start-server"}
             (addr/canonical reg :primitive/operation :projection-kind/operation
                             "infra.server" "start_server"))))))
