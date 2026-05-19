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
