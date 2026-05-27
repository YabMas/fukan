(ns fukan.canvas.project.clojure.event-to-schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fukan.canvas.project.core :as core]
            [fukan.canvas.project.clojure :as _clojure-lens]
            [fukan.canvas.core.shape :as shape]
            [fukan.project-layer.defaults :as defaults]))

(def ^:private registry (defaults/fukan-on-fukan))

;; Ground-truth: canvas/distributed/election.clj :: LeaderElected.
(def ^:private leader-elected-element
  {:model-element-kind :Affordance
   :canvas-role        :canvas/event
   :stable-id          "distributed.election/event/LeaderElected"
   :entity-name        "LeaderElected"
   :module-coord       "distributed.election"
   :doc                "A candidate has received a strict majority of grants and has transitioned to Leader for the term."
   :payload            [['term   (shape/parse :cluster/Term)]
                        ['leader (shape/parse :cluster/NodeId)]]})

(deftest produces-valid-projection
  (let [p (core/project :clojure leader-elected-element {:registry registry})]
    (is (core/valid-projection? p))
    (is (= [] (core/validate-projection p)))))

(deftest projection-kind-and-routing
  (let [p (core/project :clojure leader-elected-element {:registry registry})]
    (is (= :clojure/event-to-schema (:projection-kind p)))
    (is (= :Affordance              (:model-element-kind p)))))

(deftest target-derivation
  (let [p (core/project :clojure leader-elected-element {:registry registry})]
    (is (= "src/fukan/distributed/election.clj" (-> p :target :path)))
    (is (= "fukan.distributed.election"         (-> p :target :namespace)))
    (is (= "LeaderElected"                      (-> p :target :symbol)))))

(deftest template-uses-event-meta-tag
  (let [p (core/project :clojure leader-elected-element {:registry registry})
        t (:template p)]
    (is (str/includes? t "(def ^:event LeaderElected"))
    (is (not (str/includes? t "^:schema")))))

(deftest template-renders-payload-fields
  (let [p (core/project :clojure leader-elected-element {:registry registry})
        t (:template p)]
    (is (str/includes? t "[:term :cluster/Term]"))
    (is (str/includes? t "[:leader :cluster/NodeId]"))))

(deftest template-includes-description
  (let [p (core/project :clojure leader-elected-element {:registry registry})
        t (:template p)]
    (is (str/includes? t ":description"))
    (is (str/includes? t "strict majority"))))

(deftest empty-payload-renders-marker-map
  (testing "marker events (no payload) still produce a valid :map schema"
    (let [el {:model-element-kind :Affordance
              :canvas-role        :canvas/event
              :stable-id          "distributed.election/event/Tick"
              :entity-name        "Tick"
              :module-coord       "distributed.election"
              :doc                "Time advanced."
              :payload            []}
          p (core/project :clojure el {:registry registry})
          t (:template p)]
      (is (str/includes? t "(def ^:event Tick"))
      (is (str/includes? t "[:map {:description \"Time advanced.\"}])")))))

(deftest context-carries-source-ref-and-payload-count
  (let [p (core/project :clojure leader-elected-element {:registry registry})]
    (is (= "canvas/distributed/election.clj"
           (-> p :context :canvas-source-ref)))
    (is (= :canvas/event-doc (-> p :context :doc-source)))
    (is (= 2                 (-> p :context :payload-count)))))
