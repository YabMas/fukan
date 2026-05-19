(ns fukan.target.clojure.blueprint-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [fukan.target.clojure.blueprint :as bp]))

(deftest make-blueprint-with-required-fields
  (let [b (bp/make
            {:primitive-id    "m::Foo"
             :projection-kind :projection-kind/rule
             :address         {:ns "m" :name "foo"}
             :artifact-kind   :code/function})]
    (is (= :blueprint/v1 (:case b)))
    (is (= "m::Foo" (:primitive-id b)))
    (is (= :projection-kind/rule (:projection-kind b)))
    (is (= {:ns "m" :name "foo"} (:address b)))
    (is (= :code/function (:artifact-kind b)))))

(deftest make-blueprint-defaults-empty-optional-fields
  (let [b (bp/make {:primitive-id "m::F" :projection-kind :projection-kind/rule
                    :address {:ns "m" :name "f"} :artifact-kind :code/function})]
    (is (nil? (:signature b)))
    (is (= {} (:context b)))
    (is (= [] (:idioms b)))))

(deftest blueprint-identity
  (let [b (bp/make {:primitive-id "m::F" :projection-kind :projection-kind/rule
                    :address {:ns "m" :name "f"} :artifact-kind :code/function})]
    (is (= ["m::F" :projection-kind/rule] (bp/identity b)))))

(deftest blueprint-to-edn-roundtrip
  (let [b (bp/make {:primitive-id "m::F" :projection-kind :projection-kind/rule
                    :address {:ns "m" :name "f"} :artifact-kind :code/function
                    :signature [:=> [:cat] :any]
                    :context {:description "X" :intent nil :related-edges []}
                    :idioms []})
        s (bp/to-edn b)
        parsed (edn/read-string s)]
    (is (string? s))
    (is (= b parsed) "blueprint EDN roundtrip is identity")))

(deftest blueprint-to-markdown-includes-key-sections
  (let [b (bp/make {:primitive-id "m::Foo"
                    :projection-kind :projection-kind/rule
                    :address {:ns "m" :name "foo"}
                    :artifact-kind :code/function
                    :signature {:arglist [] :return-malli nil}
                    :context {:description "checks foo"
                              :intent nil
                              :related-edges []}
                    :idioms ["use threading macros"]})
        md (bp/to-markdown b)]
    (is (string? md))
    (is (re-find #"(?i)projecting `m::Foo`" md))
    (is (re-find #"(?i)canonical address" md))
    (is (re-find #"`m/foo`" md))
    (is (re-find #"(?i)artifact kind" md))
    (is (re-find #":code/function" md))
    (is (re-find #"(?i)expected signature" md))
    (is (re-find #"(?i)context" md))
    (is (re-find #"checks foo" md))
    (is (re-find #"(?i)idioms" md))
    (is (re-find #"use threading macros" md))))
