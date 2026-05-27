(ns fukan.target.clojure.source-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.target.clojure.source :as source]))

(deftest walk-finds-clj-files
  (testing "walks a directory and finds .clj files"
    (let [files (source/find-clj-files "test/fixtures/clojure")]
      (is (>= (count files) 1))
      (is (every? #(.endsWith % ".clj") files)))))

(deftest read-top-level-forms-from-sample
  (testing "reads defs and defns from a sample file"
    (let [forms (source/read-forms "test/fixtures/clojure/sample.clj")]
      ;; ns + 1 def + 2 defns
      (is (= 4 (count forms))))))

(deftest extract-defs-and-defns
  (testing "extract-symbols pulls Code.* candidates"
    (let [syms (source/extract-symbols "test/fixtures/clojure/sample.clj")]
      ;; Expect 3: Order (def), process-order (defn), helper-fn (defn)
      (is (= 3 (count syms)))
      (let [by-kind (group-by :kind syms)]
        (is (= 1 (count (:data-structure by-kind))))
        (is (= 2 (count (:function by-kind))))
        (is (= "fukan.test.fixture.sample"
               (-> by-kind :data-structure first :ns)))
        (is (= "Order"
               (-> by-kind :data-structure first :name)))))))

(deftest extract-fields-from-malli-map-def
  (testing "a (def Order [:map [:id :string] [:total :int]]) yields :fields"
    (let [syms    (source/extract-symbols "test/fixtures/clojure/sample.clj")
          by-kind (group-by :kind syms)
          order   (-> by-kind :data-structure first)]
      (is (= [[:id :string] [:total :int]] (:fields order))
          "Malli :map entries flatten to [field-name type] pairs"))))

(deftest extract-fields-from-malli-map-with-options
  (testing "a Malli :map with an options map after :map is parsed (skips options)"
    ;; (def ServerOpts [:map {:description "..."} [:port :int]])
    ;; → fields [[:port :int]]
    (let [syms    (source/extract-symbols "test/fixtures/clojure/malli_options.clj")
          ds     (->> syms (filter #(= :data-structure (:kind %))) first)]
      (is (= [[:port :int] [:host :string]] (:fields ds))
          "options map after :map is skipped; field options-maps inside an entry are also skipped"))))

(deftest extract-fields-from-defrecord
  (testing "a defrecord yields :fields with :any types"
    (let [syms (source/extract-symbols "test/fixtures/clojure/defrecord_sample.clj")
          ds   (->> syms (filter #(= :data-structure (:kind %))) first)]
      (is (= "Order" (:name ds)))
      (is (= :data-structure (:kind ds)))
      (is (= [[:id :any] [:customer :any] [:items :any] [:total :any]]
             (:fields ds))
          "defrecord field names land as keywords; types default to :any"))))

(deftest extract-fields-from-m-schema-wrapped-malli-map
  (testing "(def X (m/schema [:map …])) unwraps the schema wrapper to find fields"
    (let [syms (source/extract-symbols "test/fixtures/clojure/m_schema_wrapped.clj")
          ds   (->> syms (filter #(= :data-structure (:kind %))) first)]
      (is (= [[:id :string] [:label :string]] (:fields ds))
          "the (m/schema …) wrapper is unwrapped before parsing :map entries"))))

(deftest extract-fields-honours-docstring-position
  (testing "(def Name \"docstring\" body) skips the docstring when locating the body"
    (let [syms (source/extract-symbols "test/fixtures/clojure/def_with_doc.clj")
          ds   (->> syms (filter #(= :data-structure (:kind %))) first)]
      (is (= [[:id :string]] (:fields ds))
          "the docstring is not mistaken for the body"))))

(deftest extract-no-fields-for-non-record-defs
  (testing "def of a fn/value/keyword has no :fields attached"
    (let [syms (source/extract-symbols "test/fixtures/clojure/non_record_defs.clj")
          by-name (into {} (map (juxt :name identity) syms))]
      (is (nil? (:fields (get by-name "the-number"))))
      (is (nil? (:fields (get by-name "the-fn")))))))
