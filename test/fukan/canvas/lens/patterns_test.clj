(ns fukan.canvas.lens.patterns-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.construction :refer [function value]]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.lens.patterns :as patterns]
            [fukan.canvas.lens.registry :as registry]
            [fukan.canvas.lens.survey :as survey]
            [fukan.canvas.vocab.validation :refer [checker]]))

;; ---------------------------------------------------------------------------
;; Three structurally-similar `function` affordances → one cluster.
;; ---------------------------------------------------------------------------

(deftest three-similar-affordances-cluster
  (testing "three functions sharing role/inputs/outputs surface as one cluster"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (function "a"
                   "First."
                   (takes [x :String])
                   (gives :String))
                 (function "b"
                   "Second."
                   (takes [x :String])
                   (gives :String))
                 (function "c"
                   "Third."
                   (takes [x :String])
                   (gives :String))))
          {:keys [clusters]} (patterns/compute db {})]
      (is (= 1 (count clusters))
          "exactly one cluster expected for three identically-shaped functions")
      (let [c (first clusters)]
        (is (= 3 (:size c)))
        (is (= ["demo/a" "demo/b" "demo/c"] (:members c))
            "members must be the stable-ids of the three functions")
        (is (= [:String] (:input-types  (:signature c))))
        (is (= [:String] (:output-types (:signature c))))
        (is (false? (:has-formal-expr (:signature c))))
        (is (false? (:has-returns     (:signature c))))
        (is (nil? (:existing-lift c))
            "non-vocab role should not trigger existing-lift annotation")))))

(deftest below-threshold-not-a-cluster
  (testing "two structurally-similar affordances do not form a cluster"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (function "a"
                   "First."
                   (takes [x :String])
                   (gives :String))
                 (function "b"
                   "Second."
                   (takes [x :String])
                   (gives :String))))
          {:keys [clusters]} (patterns/compute db {})]
      (is (empty? clusters)))))

;; ---------------------------------------------------------------------------
;; Three checkers share the :canvas/checker role → existing-lift annotated.
;; ---------------------------------------------------------------------------

(deftest checker-cluster-annotated-with-existing-lift
  (testing "three checkers share :canvas/checker role → existing-lift attached"
    (let [db (h/with-canvas
               (h/within-module "model.spec"
                 (value "Model" "The model."))
               (h/within-module "agent"
                 (value "Violation" "A violation."))
               (h/within-module "rules-a"
                 (checker "check" "Check A."))
               (h/within-module "rules-b"
                 (checker "check" "Check B."))
               (h/within-module "rules-c"
                 (checker "check" "Check C.")))
          {:keys [clusters]} (patterns/compute db {})]
      (is (= 1 (count clusters))
          "expected exactly one checker cluster")
      (let [c (first clusters)]
        (is (= 3 (:size c)))
        (is (= :canvas/checker (:role (:signature c))))
        (is (= "vocab.validation/checker" (:existing-lift c))
            "cluster of checkers must be annotated with the existing-lift")))))

;; ---------------------------------------------------------------------------
;; Render — formatting and verdicts.
;; ---------------------------------------------------------------------------

(deftest render-frames-new-lift-candidate
  (testing "render frames a non-vocab cluster as a rule-of-three lift candidate"
    (let [findings {:clusters
                    [{:size 3
                      :signature {:role            :fukan.canvas.monolith/exposed-call
                                  :input-types     [:String]
                                  :output-types    [:String]
                                  :has-formal-expr false
                                  :has-returns     false}
                      :members ["demo/a" "demo/b" "demo/c"]}]}
          out (patterns/render findings {})]
      (is (re-find #"Rule of three" out))
      (is (re-find #"demo/a" out)))))

(deftest render-frames-existing-lift-as-confirmation
  (testing "render frames a cluster with an existing-lift as a consistency check"
    (let [findings {:clusters
                    [{:size 3
                      :signature {:role :canvas/checker
                                  :input-types [:model/Model]
                                  :output-types [:agent/Violation]
                                  :has-formal-expr false
                                  :has-returns false}
                      :members ["a/check" "b/check" "c/check"]
                      :existing-lift "vocab.validation/checker"}]}
          out (patterns/render findings {})]
      (is (re-find #"Already lifted" out))
      (is (re-find #"vocab.validation/checker" out)))))

(deftest render-empty-when-no-clusters
  (testing "render returns a clear no-clusters message"
    (let [out (patterns/render {:clusters []} {})]
      (is (re-find #"No clusters" out)))))

;; ---------------------------------------------------------------------------
;; Lens contract.
;; ---------------------------------------------------------------------------

(deftest lens-var-is-well-formed
  (testing "the registered lens var carries the expected keys"
    (is (= :patterns (:id patterns/lens)))
    (is (string? (:description     patterns/lens)))
    (is (string? (:prompt-fragment patterns/lens)))
    (is (fn?     (:compute         patterns/lens)))
    (is (fn?     (:render          patterns/lens)))))

;; ---------------------------------------------------------------------------
;; Registration: the substrate must surface the patterns lens.
;; ---------------------------------------------------------------------------

(deftest patterns-lens-registered
  (testing "the registry exposes the patterns lens by id"
    (is (some #(= :patterns (:id %)) (registry/all-lenses)))
    (is (= patterns/lens (registry/lens-by-id :patterns)))))

(deftest survey-against-real-canvas-runs-patterns
  (testing "survey/run :patterns against a synthetic canvas produces a finding shape"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (function "f"
                   "Identity-ish."
                   (takes [x :String])
                   (gives :String))))
          result (survey/run db [:patterns])]
      (is (= [:patterns] (:survey/lenses result)))
      (let [r (first (:survey/results result))]
        (is (= :patterns (:lens/id r)))
        (is (string? (:lens/rendered r)))
        (is (some? (:lens/findings r)))))))
