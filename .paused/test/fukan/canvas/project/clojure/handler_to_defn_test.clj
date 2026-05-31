(ns fukan.canvas.project.clojure.handler-to-defn-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fukan.canvas.project.core :as core]
            [fukan.canvas.project.clojure :as _clojure-lens]
            [fukan.project-layer.defaults :as defaults]))

(def ^:private registry (defaults/fukan-on-fukan))

;; Ground-truth: canvas/distributed/election.clj :: on_vote_requested.
;; The event vocab stores :on/:emits as pr-str'd keywords on
;; :formal-expression; `affordance-element` surfaces them as the
;; same strings (with leading `:`).
(def ^:private on-vote-requested-element
  {:model-element-kind :Affordance
   :canvas-role        :canvas/handler
   :stable-id          "distributed.election/on_vote_requested"
   :entity-name        "on_vote_requested"
   :module-coord       "distributed.election"
   :doc                "A peer evaluates an incoming vote request."
   :on                 ":election/VoteRequested"
   :emits              [":election/VoteGranted" ":election/VoteDenied"]})

(deftest produces-valid-projection
  (let [p (core/project :clojure on-vote-requested-element {:registry registry})]
    (is (core/valid-projection? p))
    (is (= [] (core/validate-projection p)))))

(deftest projection-kind-and-routing
  (let [p (core/project :clojure on-vote-requested-element {:registry registry})]
    (is (= :clojure/handler-to-defn (:projection-kind p)))
    (is (= :Affordance              (:model-element-kind p)))))

(deftest target-derivation-kebab-cases
  (let [p (core/project :clojure on-vote-requested-element {:registry registry})]
    (is (= "src/fukan/distributed/election.clj" (-> p :target :path)))
    (is (= "fukan.distributed.election"         (-> p :target :namespace)))
    (is (= "on-vote-requested"                  (-> p :target :symbol)))))

(deftest template-defn-shape
  (let [p (core/project :clojure on-vote-requested-element {:registry registry})
        t (:template p)]
    (is (str/includes? t "(defn on-vote-requested"))
    (is (str/includes? t "\"A peer evaluates an incoming vote request.\""))
    (is (str/includes? t "[payload state]"))
    (is (str/includes? t ":malli/schema"))))

(deftest malli-schema-binds-payload-to-on-event-ref
  (let [p (core/project :clojure on-vote-requested-element {:registry registry})
        t (:template p)]
    (is (str/includes? t "[:=> [:cat :election/VoteRequested :any] :any]"))))

(deftest exception-stub-carries-canvas-id-on-and-emits
  (let [p (core/project :clojure on-vote-requested-element {:registry registry})
        t (:template p)]
    (is (str/includes? t "(throw (ex-info \"on-vote-requested: not yet implemented\""))
    (is (str/includes? t ":canvas-id \"distributed.election/on_vote_requested\""))
    (is (str/includes? t ":on :election/VoteRequested"))
    (is (str/includes? t ":emits [:election/VoteGranted :election/VoteDenied]"))))

(deftest prose-envelope-names-trigger-and-emits
  (let [p (core/project :clojure on-vote-requested-element {:registry registry})
        pr (:prose p)]
    (is (str/starts-with? pr "Reactive handler: on_vote_requested."))
    (is (str/includes? pr "Fires on: :election/VoteRequested."))
    (is (str/includes? pr "May emit: :election/VoteGranted, :election/VoteDenied."))))

(deftest context-carries-on-and-emits
  (let [p (core/project :clojure on-vote-requested-element {:registry registry})]
    (is (= :election/VoteRequested                       (-> p :context :on)))
    (is (= [:election/VoteGranted :election/VoteDenied] (-> p :context :emits)))
    (is (true? (-> p :context :reactive?)))
    (is (= 2   (-> p :context :arity)))))

(deftest handler-without-emits
  (testing "on_heartbeat_received declares no emits — the projection omits the clause"
    (let [el {:model-element-kind :Affordance
              :canvas-role        :canvas/handler
              :stable-id          "distributed.election/on_heartbeat_received"
              :entity-name        "on_heartbeat_received"
              :module-coord       "distributed.election"
              :doc                "Any node receiving a valid heartbeat resets its election timeout."
              :on                 ":election/HeartbeatReceived"}
          p  (core/project :clojure el {:registry registry})
          t  (:template p)
          pr (:prose p)]
      (is (str/includes? t ":on :election/HeartbeatReceived"))
      (is (not (str/includes? t ":emits [")))
      (is (not (str/includes? pr "May emit:"))))))
