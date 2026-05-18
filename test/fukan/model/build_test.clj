(ns fukan.model.build-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.build :as b]
            [fukan.model.primitives :as p]
            [fukan.model.relations :as r]
            [fukan.model.type :as t]
            [fukan.model.vocabulary :as v]
            [fukan.model.artifact :as a]
            [malli.core :as m]))

(deftest empty-model
  (let [m (b/empty-model)]
    (is (m/validate b/Model m))
    (is (zero? (count (:primitives m))))
    (is (zero? (count (:edges m))))))

(deftest add-primitive
  (let [m (-> (b/empty-model)
              (b/add-primitive (p/make-container {:id "order" :label "Order"})))]
    (is (= 1 (count (:primitives m))))
    (is (= :primitive/container (:kind (b/get-primitive m "order"))))))

(deftest duplicate-primitive-id-rejected
  (let [c1 (p/make-container {:id "order" :label "Order"})
        c2 (p/make-container {:id "order" :label "OrderTwo"})
        m  (b/add-primitive (b/empty-model) c1)]
    (is (thrown? Exception (b/add-primitive m c2)))))

(deftest add-edge-with-valid-endpoints
  (let [order  (p/make-container {:id "order" :label "Order"
                                  :fields [(p/make-field "total"
                                                         (t/make-scalar "Integer")
                                                         false)]})
        rule   (p/make-rule {:id "rule-1" :label "AddOne"})
        edge   (r/make-edge :relation/writes
                            (r/primitive-ref "rule-1")
                            (r/substrate-address "order" [{:slot "field" :key "total"}]))
        m (-> (b/empty-model)
              (b/add-primitive order)
              (b/add-primitive rule)
              (b/add-edge edge))]
    (is (= 1 (count (:edges m))))
    (is (= [edge] (b/edges-from m (r/primitive-ref "rule-1"))))
    (is (= [edge] (b/edges-by-kind m :relation/writes)))))

(deftest add-edge-rejects-unknown-endpoint
  (let [edge (r/make-edge :relation/uses
                          (r/primitive-ref "ghost-a")
                          (r/primitive-ref "ghost-b"))]
    (is (thrown? Exception (b/add-edge (b/empty-model) edge)))))

(deftest multi-edge-allowed-with-distinct-identifying-metadata
  (let [a (p/make-container {:id "a" :label "A"})
        b (p/make-container {:id "b" :label "B"})
        from (r/primitive-ref "a")
        to   (r/primitive-ref "b")
        e1 (r/make-edge :relation/projects from to
              {:projection-kind :projection-kind/rule})
        e2 (r/make-edge :relation/projects from to
              {:projection-kind :projection-kind/test})
        m (-> (b/empty-model)
              (b/add-primitive a) (b/add-primitive b)
              (b/add-edge e1) (b/add-edge e2))]
    (is (= 2 (count (:edges m)))
        "Different :projection-kind on `projects` => two distinct edges")))

(deftest duplicate-edge-collapses
  (testing "Same identity => one edge regardless of non-identifying metadata"
    (let [a (p/make-container {:id "a" :label "A"})
          b (p/make-container {:id "b" :label "B"})
          e1 (r/make-edge :relation/uses (r/primitive-ref "a") (r/primitive-ref "b")
                          {:source-file "x.allium"})
          e2 (r/make-edge :relation/uses (r/primitive-ref "a") (r/primitive-ref "b")
                          {:source-file "y.allium"})
          m (-> (b/empty-model)
                (b/add-primitive a) (b/add-primitive b)
                (b/add-edge e1) (b/add-edge e2))]
      (is (= 1 (count (:edges m)))))))

(deftest tag-definition-application
  (let [td (v/make-tag-definition
             {:namespace "Allium" :name "Module" :applies-to :target/container})
        m  (-> (b/empty-model)
               (b/add-primitive (p/make-container {:id "auth" :label "Auth"}))
               (b/add-tag-definition td)
               (b/add-tag-application
                 (v/make-tag-application
                   {:tag {:namespace "Allium" :name "Module"}
                    :target {:case :target/primitive :id "auth"}})))]
    (is (= 1 (count (:tag-defs m))))
    (is (= 1 (count (:tag-apps m))))))

(deftest tag-application-rejects-missing-target
  (let [td (v/make-tag-definition
             {:namespace "Allium" :name "Module" :applies-to :target/container})
        m (-> (b/empty-model) (b/add-tag-definition td))]
    (is (thrown? Exception
                 (b/add-tag-application m
                   (v/make-tag-application
                     {:tag {:namespace "Allium" :name "Module"}
                      :target {:case :target/primitive :id "ghost"}}))))))

(deftest artifact-registry-by-identity
  (let [m (-> (b/empty-model)
              (b/add-artifact (a/make-code-function "clojure" "ns/foo")))]
    (is (= 1 (count (:artifacts m))))
    (is (some? (b/get-artifact m [:code/function "clojure" "ns/foo"])))))

(deftest artifact-duplicate-identity-rejected
  (let [m (b/add-artifact (b/empty-model) (a/make-code-function "clojure" "ns/foo"))]
    (is (thrown? Exception
                 (b/add-artifact m (a/make-code-function "clojure" "ns/foo"))))))

(deftest validates-against-Model-schema
  (let [m (-> (b/empty-model)
              (b/add-primitive (p/make-container {:id "x" :label "X"}))
              (b/add-primitive (p/make-actor     {:id "y" :label "Y"})))]
    (is (m/validate b/Model m))))
