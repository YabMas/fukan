(ns fukan.canvas.core.coverage-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.law]                       ; registers the cozo check engine
            [fukan.model.pipeline :as pipeline]
            [fukan.canvas.core.coverage :as coverage]))

(deftest lens-coverage-fires-on-an-orphan-reader
  (testing "an extracted reader (prefix+X) with no declared Lens X is an offender; a covered reader, a
            non-matching op, and a reader-less Lens are not — the prefix is read from ReaderConvention"
    (let [db (build/tx-maps->cozo
              [{:db/id -1 :structure/of :fukan.canvas.core.coverage/ReaderConvention :val/prefix "probe-"}
               {:db/id -2 :structure/of :fukan.canvas.core.lens/Lens :entity/name "survey"}                                  ; declared focus
               {:db/id -3 :structure/of :fukan.canvas.core.lens/Lens :entity/name "purity"}                                  ; Lens with no reader — allowed
               {:db/id -4 :structure/of :canvas.vocab.code.operation/Operation :entity/name "probe-survey" :val/extracted true} ; covered
               {:db/id -5 :structure/of :canvas.vocab.code.operation/Operation :entity/name "probe-orphan" :val/extracted true} ; no "orphan" Lens → offender
               {:db/id -6 :structure/of :canvas.vocab.code.operation/Operation :entity/name "run"          :val/extracted true}])] ; non-matching → ignored
      (is (= #{"probe-orphan"} (coverage/uncovered-readers db))
          "only the matching reader with no declared Lens is flagged"))))

(deftest the-prefix-is-read-from-config-not-hardcoded
  (testing "a different ReaderConvention prefix changes which readers are checked — proves the prefix
            is a parameter, not the old hardcoded 'probe-'"
    (let [db (build/tx-maps->cozo
              [{:db/id -1 :structure/of :fukan.canvas.core.coverage/ReaderConvention :val/prefix "read-"}
               {:db/id -2 :structure/of :canvas.vocab.code.operation/Operation :entity/name "read-orphan" :val/extracted true}  ; matches "read-" → offender
               {:db/id -3 :structure/of :canvas.vocab.code.operation/Operation :entity/name "probe-orphan" :val/extracted true}])] ; not matched under "read-"
      (is (= #{"read-orphan"} (coverage/uncovered-readers db))
          "only readers matching the declared prefix are checked"))))

(deftest lens-coverage-green-on-the-self-model
  (testing "every bespoke probe reader has a declared Lens twin on the live build-model \"src\""
    (is (empty? (coverage/uncovered-readers (pipeline/build-model "src")))
        "0 uncovered readers — every probe-X leaf's focus is declared as a Lens instrument")))
