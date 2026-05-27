(ns fukan.canvas.project.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.project.core :as core]))

(def ^:private minimal-valid
  {:projection-kind    :clojure/example
   :lens-id            :clojure
   :model-element-kind :Type
   :model-element-id   "infra.server/type/ServerOpts"
   :target             {:path      "src/fukan/infra/server.clj"
                        :namespace "fukan.infra.server"
                        :symbol    "ServerOpts"}
   :template           "(def ServerOpts [:map])"
   :prose              "HTTP server configuration."
   :context            {}})

(deftest valid-projection-passes
  (is (core/valid-projection? minimal-valid))
  (is (= [] (core/validate-projection minimal-valid))))

(deftest projection-kind-must-be-qualified
  (testing "simple keyword is rejected (must be namespaced)"
    (let [m (assoc minimal-valid :projection-kind :example)]
      (is (not (core/valid-projection? m)))
      (is (some #(re-find #":projection-kind" %)
                (core/validate-projection m))))))

(deftest model-element-id-must-be-string
  (let [m (assoc minimal-valid :model-element-id :not-a-string)]
    (is (not (core/valid-projection? m)))))

(deftest template-may-be-nil
  (let [m (assoc minimal-valid :template nil)]
    (is (core/valid-projection? m))))

(deftest target-namespace-may-be-nil
  (testing "TypeScript-like projection where module-by-file leaves namespace nil"
    (let [m (assoc-in minimal-valid [:target :namespace] nil)]
      (is (core/valid-projection? m)))))

(deftest invalid-target
  (let [m (assoc minimal-valid :target {:path 123 :symbol "X"})]
    (is (not (core/valid-projection? m)))))

(deftest dispatch-key-of-affordance-uses-canvas-role
  (let [el {:model-element-kind :Affordance
            :canvas-role        :canvas/event
            :stable-id          "x/event/Y"}]
    (is (= :canvas/event (core/dispatch-key-of el)))))

(deftest dispatch-key-of-type-uses-kind
  (let [el {:model-element-kind :Type
            :stable-id          "x/type/Y"}]
    (is (= :Type (core/dispatch-key-of el)))))

(deftest dispatch-key-of-type-refines-by-type-kind
  (testing "atomic and record types route to distinct dispatch keys"
    (is (= :Type/atomic
           (core/dispatch-key-of {:model-element-kind :Type :type-kind :atomic})))
    (is (= :Type/record
           (core/dispatch-key-of {:model-element-kind :Type :type-kind :record})))))

(deftest dispatch-key-of-module-uses-kind
  (let [el {:model-element-kind :Module
            :stable-id          "x"}]
    (is (= :Module (core/dispatch-key-of el)))))

(deftest default-method-throws-with-context
  (testing "unregistered dispatch throws ex-info naming the dispatch key"
    (let [el {:model-element-kind :Affordance
              :canvas-role        :nope/unknown
              :stable-id          "x/event/Y"}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"no project-lens projection registered"
                            (core/project :clojure el {}))))))

(deftest render-shape-atomic-alias-table
  (is (= :int    (core/render-shape {:kind :atomic :name :Integer})))
  (is (= :string (core/render-shape {:kind :atomic :name :String})))
  (is (= :any    (core/render-shape {:kind :atomic :name :Unit}))))

(deftest render-shape-unknown-atomic-passes-through
  (is (= :NodeId (core/render-shape {:kind :atomic :name :NodeId}))))

(deftest render-shape-ref-preserves-qualified-keyword
  (is (= :cluster/NodeId (core/render-shape {:kind :ref :target :cluster/NodeId}))))

(deftest render-shape-optional
  (is (= [:maybe :int]
         (core/render-shape {:kind :optional :inner {:kind :atomic :name :Integer}}))))

(deftest render-shape-list-set-map
  (is (= [:sequential :int]
         (core/render-shape {:kind :list :elem {:kind :atomic :name :Integer}})))
  (is (= [:set :cluster/NodeId]
         (core/render-shape {:kind :set :elem {:kind :ref :target :cluster/NodeId}})))
  (is (= [:map-of :string :int]
         (core/render-shape {:kind :map
                             :key {:kind :atomic :name :String}
                             :val {:kind :atomic :name :Integer}}))))

(deftest render-shape-sum-of-and-tuple
  (is (= [:or :int :string]
         (core/render-shape {:kind :sum
                             :variants [{:kind :atomic :name :Integer}
                                        {:kind :atomic :name :String}]})))
  (is (= [:tuple :int :string]
         (core/render-shape {:kind :tuple
                             :elems [{:kind :atomic :name :Integer}
                                     {:kind :atomic :name :String}]}))))

(deftest render-shape-record
  (is (= [:map [:n :int] [:s :string]]
         (core/render-shape
          {:kind :record
           :fields [['n {:kind :atomic :name :Integer}]
                    ['s {:kind :atomic :name :String}]]}))))

(deftest ns->path-convention
  (is (= "src/fukan/infra/server.clj" (core/ns->path "fukan.infra.server")))
  (is (= "src/fukan/project_layer/registry.clj"
         (core/ns->path "fukan.project-layer.registry")))
  (is (nil? (core/ns->path nil))))
