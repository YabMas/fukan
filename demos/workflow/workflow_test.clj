(ns demos.workflow.workflow-test
  "Regression for the workflow demo: the order-fulfilment workflow is well-formed
   (fork + join + terminal), and each kind of ill-formedness — an unreachable
   step, an edge back to the start, a non-terminating loop — is caught. Run via
   `clj -M:demos`."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [demos.workflow.model.fulfilment :as fulfilment]
            [demos.workflow.vocab.core :refer [Step Workflow]]))

(defn- laws [db] (set (map :law (s/check db))))

(deftest fulfilment-workflow-is-well-formed
  (testing "the order-fulfilment workflow — with its fork and join — satisfies every law"
    (is (empty? (s/check (fulfilment/build))))))

(deftest unreachable-step-is-caught
  (testing "a step in the workflow that the start cannot reach trips the reachability law"
    (let [db (s/with-structures
               (s/within-module "wf"
                 (Step "a" (next b))
                 (Step "b")                        ; terminal
                 (Step "orphan")                   ; a :step, but nothing reaches it
                 (Workflow "w" (start a) (step a) (step b) (step orphan))))]
      (is (contains? (laws db) "every step is reachable from the start step")))))

(deftest edge-back-to-start-is-caught
  (testing "a transition back to the start step — re-entering the entry — is caught"
    (let [db (s/with-structures
               (s/within-module "wf"
                 (Step "a" (next b c))             ; fork; forward refs resolve via two-pass
                 (Step "b" (next a))               ; back-edge to the start
                 (Step "c")                        ; terminal — so the no-terminal law is NOT what fires
                 (Workflow "w" (start a) (step a) (step b) (step c))))]
      (is (contains? (laws db) "the start step has no incoming transition")))))

(deftest workflow-with-no-terminal-is-caught
  (testing "a workflow whose steps loop forever — no terminal — is caught (cycle off the start)"
    (let [db (s/with-structures
               (s/within-module "wf"
                 (Step "entry" (next a))
                 (Step "a" (next b))
                 (Step "b" (next a))               ; a⇄b loop; entry feeds in but isn't in the loop
                 (Workflow "w" (start entry) (step entry) (step a) (step b))))]
      (is (contains? (laws db)
                     "the workflow has at least one terminal step (a step with no outgoing transition)")))))
