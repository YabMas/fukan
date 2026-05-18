(ns fukan.vocabulary.allium.tags-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.vocabulary.allium.tags :as tags]
            [fukan.model.vocabulary :as v]
            [malli.core :as m]))

(deftest all-allium-tags-registered
  (testing "the tags namespace exports all 23 Allium::* TagDefinitions"
    (is (= 23 (count tags/allium-tag-definitions)))
    (is (every? #(m/validate v/TagDefinition %) tags/allium-tag-definitions))))

(deftest tag-namespaces-are-allium
  (is (every? #(= "Allium" (:namespace %)) tags/allium-tag-definitions)))

(deftest required-tag-names-present
  (testing "every §8.1 tag is in the registry"
    (let [names (set (map :name tags/allium-tag-definitions))]
      (doseq [expected ["Module" "Entity" "Value" "Variant" "ExternalEntity"
                        "Actor" "Rule" "Trigger" "Surface" "Contract"
                        "Provides" "Exposes" "Fulfils" "Demands" "Call"
                        "Event" "Invariant" "Requires" "Let" "Ensures"
                        "ContractInvariant" "SurfaceGuarantee" "Guidance"]]
        (is (contains? names expected)
            (str "missing tag: Allium::" expected))))))

(deftest actor-tag-has-payload
  (testing "Allium::Actor declares identified_by/within payload"
    (let [actor-td (->> tags/allium-tag-definitions
                        (filter #(= "Actor" (:name %)))
                        first)]
      (is (some? (:payload-schema actor-td))
          "Allium::Actor must have payload-schema"))))

(deftest surface-tag-has-payload
  (testing "Allium::Surface declares facing/context/related/timeout payload"
    (let [surf-td (->> tags/allium-tag-definitions
                       (filter #(= "Surface" (:name %)))
                       first)]
      (is (some? (:payload-schema surf-td))))))

(deftest trigger-tag-has-payload
  (testing "Allium::Trigger declares kind payload"
    (let [trig-td (->> tags/allium-tag-definitions
                       (filter #(= "Trigger" (:name %)))
                       first)]
      (is (some? (:payload-schema trig-td))))))

(deftest tag-applies-to-distribution
  (testing "tags are spread across :target/container, :target/edge, :target/substrate, etc."
    (let [applies-tos (set (map :applies-to tags/allium-tag-definitions))]
      (is (contains? applies-tos :target/container))
      (is (contains? applies-tos :target/edge))
      (is (contains? applies-tos :target/substrate))
      (is (contains? applies-tos :target/actor))
      (is (contains? applies-tos :target/clause))
      (is (contains? applies-tos :target/event))
      (is (contains? applies-tos :target/rule))
      (is (contains? applies-tos :target/operation)))))
