(ns demos.workflow.workflow-test
  "Regression for the workflow demo: the order-fulfilment workflow is well-formed
   (fork + join + terminal), and each kind of ill-formedness — an unreachable
   step, an edge back to the start, a non-terminating loop — is caught. Run via
   `clj -M:demos`."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.core.assemble :as a]
            [demos.workflow.model.fulfilment :as fulfilment]
            [demos.workflow.vocab.core :refer [Step Workflow]]))

(defn- laws [db] (set (map :law (s/check db))))

(deftest fulfilment-workflow-is-well-formed
  (testing "the order-fulfilment workflow — with its fork and join — satisfies every law"
    (is (empty? (s/check (fulfilment/build))))))

;; ── case 1: unreachable step ──────────────────────────────────────────────────

(declare c1-b)
(def c1-a      (Step "a"      (next c1-b)))
(def c1-b      (Step "b"))                        ; terminal
(def c1-orphan (Step "orphan"))                   ; a :step, but nothing reaches it
(def c1-w      (Workflow "w" (start c1-a) (step c1-a) (step c1-b) (step c1-orphan)))

(deftest unreachable-step-is-caught
  (testing "a step in the workflow that the start cannot reach trips the reachability law"
    (let [db (a/assemble-vars [#'c1-a #'c1-b #'c1-orphan #'c1-w])]
      (is (contains? (laws db) "every step is reachable from the start step")))))

;; ── case 2: edge back to start ────────────────────────────────────────────────
;; a and b mutually reference each other (a→{b,c}, b→a), so both need forward
;; declarations.  c2-c is declared forward because a references it before its def.

(declare c2-a c2-b c2-c)
(def c2-a (Step "a" (next c2-b c2-c)))             ; fork; forward refs
(def c2-b (Step "b" (next c2-a)))                  ; back-edge to the start
(def c2-c (Step "c"))                              ; terminal — so the no-terminal law is NOT what fires
(def c2-w (Workflow "w" (start c2-a) (step c2-a) (step c2-b) (step c2-c)))

(deftest edge-back-to-start-is-caught
  (testing "a transition back to the start step — re-entering the entry — is caught"
    (let [db (a/assemble-vars [#'c2-a #'c2-b #'c2-c #'c2-w])]
      (is (contains? (laws db) "the start step has no incoming transition")))))

;; ── case 3: no terminal (loop forever) ───────────────────────────────────────
;; a and b form a mutual loop (a→b, b→a); entry feeds in but is not in the loop.

(declare c3-a c3-b)
(def c3-entry (Step "entry" (next c3-a)))
(def c3-a     (Step "a"     (next c3-b)))
(def c3-b     (Step "b"     (next c3-a)))          ; a⇄b loop; entry feeds in but isn't in the loop
(def c3-w     (Workflow "w" (start c3-entry) (step c3-entry) (step c3-a) (step c3-b)))

(deftest workflow-with-no-terminal-is-caught
  (testing "a workflow whose steps loop forever — no terminal — is caught (cycle off the start)"
    (let [db (a/assemble-vars [#'c3-entry #'c3-a #'c3-b #'c3-w])]
      (is (contains? (laws db)
                     "the workflow has at least one terminal step (a step with no outgoing transition)")))))
