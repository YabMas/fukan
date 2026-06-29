(ns fukan.model.readings-test
  "The READINGS — patterns/consistency, Projections whose target artifact is a Finding, rendered
   through their :through lens by materialize/render-finding. The defining property (vs the old
   probes): a reading renders ONLY its lens's focus — it never re-selects, so it cannot drift from
   its lens."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.query :as cq]
            [fukan.model.pipeline :as pipeline]
            ;; composition root: registers the FACT extractor + the Cozo check engine
            [fukan.infra.model]
            [fukan.model.materialize :as m]))

(defn- proj [db nm]
  (ffirst (cq/q '[:find ?e :in $ ?n
                  :where [?e :structure/of :fukan.canvas.core.lens/Projection] [?e :entity/name ?n]] db nm)))

(deftest read-all-runs-the-reading-projections
  (testing "read-all renders every reading projection through its lens into a Finding, keyed by name"
    (let [db  (pipeline/build-model nil)
          all (m/read-all db)]
      (is (= #{"Patterns" "Consistency"} (set (keys all)))
          "the reading projections")
      (is (seq (:observations (all "Patterns"))) "patterns: recurring structural triplets")
      (is (every? (fn [o] (and (set? (:focus o)) (keyword? (:as o)) (string? (:note o))))
                  (mapcat :observations (vals all)))
          "every observation is {focus tag note}"))))

(deftest a-reading-renders-only-its-lens-focus
  (testing "Patterns renders the relation nodes its :through lens selects — and ONLY them"
    (let [db     (pipeline/build-model nil)
          result (m/read-projection db (proj db "Patterns"))]
      (is (= "Patterns" (:lens result)))
      (is (seq (:observations result)) "the self-model has recurring structures")
      (let [o (first (:observations result))]
        (is (= :pattern (:as o)))
        (is (set? (:focus o)))
        (is (seq (:focus o))))
      ;; the anti-drift property: render reads ONLY its focus — an empty focus yields nothing, even
      ;; though the whole db is full of relations. (A relation-less lens focus ⇒ no patterns.)
      (is (empty? (:observations (m/render-finding db "Patterns" #{})))
          "empty focus ⇒ no patterns — the render never re-queries past its focus"))))

(deftest render-finding-rejects-an-unknown-projection
  (testing "render-finding's :default throws for a projection with no reading renderer"
    (is (thrown? clojure.lang.ExceptionInfo
                 (m/render-finding (pipeline/build-model nil) "Nonesuch" #{})))))
