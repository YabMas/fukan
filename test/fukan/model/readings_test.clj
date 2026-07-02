(ns fukan.model.readings-test
  "The READINGS — Projections whose target artifact is a Finding, rendered from their resolved
   focus (each reading's own inline :select) by materialize/render-finding. The defining property:
   a reading renders ONLY its resolved focus — it never re-selects, so the focus cannot drift from
   the reading.

   fukan's own Boundary reading ships via canvas/principles/parse_dont_validate. The other
   readings (Patterns/Consistency/Depth) are exercised through ad-hoc instances carrying the same
   names the render-finding methods dispatch on — folded onto the model for these tests."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.lens :refer [Projection]]
            [fukan.model.pipeline :as pipeline]
            ;; composition root: registers the FACT extractor + the Cozo check engine
            [fukan.infra.model]
            [fukan.model.materialize :as m]))

;; ad-hoc reading instances — the names match the registered render-finding methods
(Projection ^{:name "Patterns"} rt-patterns
  "Recurring structures — structural triplets borne by more than one relation (a reading)."
  {:select '[[?n :rel/kind _]]})
(Projection ^{:name "Consistency"} rt-consistency
  "Operation-name ambiguity — names borne by more than one module (a reading)."
  {:select '[(Operation ?n)]})
(Projection ^{:name "Depth"} rt-depth
  "Module depth — interface size against implementation size, shallowest first (a reading)."
  {:select '[(Module ?n)]})

(defn- model+readings
  "The design model with the three ad-hoc reading instances folded on (Boundary ships with the model)."
  []
  (build/fold-vars->cozo (pipeline/build-model nil)
                         [#'rt-patterns #'rt-consistency #'rt-depth]))

(defn- proj [db nm]
  (ffirst (cq/q '[:find ?e :in $ ?n
                  :where [?e :structure/of :fukan.canvas.core.lens/Projection] [?e :entity/name ?n]] db nm)))

(deftest read-all-runs-the-reading-projections
  (testing "read-all renders every reading projection present in the db into a Finding, keyed by name"
    (let [db  (model+readings)
          all (m/read-all db)]
      (is (= #{"Patterns" "Consistency" "Depth" "Boundary"} (set (keys all)))
          "the reading projections")
      (is (seq (:observations (all "Patterns"))) "patterns: recurring structural triplets")
      (is (every? (fn [o] (and (set? (:focus o)) (keyword? (:as o)) (string? (:note o))))
                  (mapcat :observations (vals all)))
          "every observation is {focus tag note}"))))

(deftest principled-readings-ship-with-the-model
  (testing "the principle files mint the shipped readings — a plain build carries them"
    (is (= #{"Boundary"} (set (keys (m/read-all (pipeline/build-model nil))))))))

(deftest a-reading-renders-only-its-focus
  (testing "Patterns renders the relation nodes its inline :select focuses — and ONLY them"
    (let [db     (model+readings)
          result (m/read-projection db (proj db "Patterns"))]
      (is (= "Patterns" (:lens result)))
      (is (seq (:observations result)) "the self-model has recurring structures")
      (let [o (first (:observations result))]
        (is (= :pattern (:as o)))
        (is (set? (:focus o)))
        (is (seq (:focus o))))
      ;; the anti-drift property: render reads ONLY its focus — an empty focus yields nothing, even
      ;; though the whole db is full of relations. (A relation-less focus ⇒ no patterns.)
      (is (empty? (:observations (m/render-finding db "Patterns" #{})))
          "empty focus ⇒ no patterns — the render never re-queries past its focus"))))

(deftest render-finding-rejects-an-unknown-projection
  (testing "render-finding's :default throws for a projection with no reading renderer"
    (is (thrown? clojure.lang.ExceptionInfo
                 (m/render-finding (pipeline/build-model nil) "Nonesuch" #{})))))

(deftest depth-reading-surfaces-interface-vs-implementation
  (testing "Depth renders per-module interface/implementation counts, shallowest first"
    (let [db     (model+readings)
          result (m/read-projection db (proj db "Depth"))]
      (is (= "Depth" (:lens result)))
      (is (seq (:observations result)) "the self-model has modules")
      (is (every? (fn [o] (and (= :depth (:as o))
                               (re-find #"interface \d+ ops / implementation \d+ members" (:note o))))
                  (:observations result)))
      ;; anti-drift: the render reads ONLY its focus
      (is (empty? (:observations (m/render-finding db "Depth" #{})))))))

(deftest boundary-reading-tells-the-trust-story
  (testing "Boundary renders per-trust-boundary: declared parsers, undeclared producers, validator shapes"
    (let [db     (pipeline/build-model nil)
          result (m/read-projection db (proj db "Boundary"))
          notes  (mapv :note (:observations result))
          tagged (fn [as] (filter #(= as (:as %)) (:observations result)))]
      (is (= "Boundary" (:lens result)))
      (is (= 2 (count (tagged :parser)))
          "load-model and refresh-model are the declared parsers")
      (is (every? #(re-find #"fails by throw" (:note %)) (tagged :parser))
          "both declared parsers perform :throws — their failure channel is visible")
      (is (some #(re-find #"get-model" %) (map :note (tagged :producer)))
          "get-model outputs StructureDb without being declared — surfaced for judgment")
      (is (empty? (tagged :validator-shaped))
          "no public boolean op takes StructureDb — no validate-smell today")
      (is (every? #(re-find #"StructureDb" %) notes)
          "every observation names the trusted kind")
      ;; anti-drift: the render reads ONLY its focus
      (is (empty? (:observations (m/render-finding db "Boundary" #{})))))))

(deftest depth-orders-shallowest-first
  (testing "on a synthetic pair: mod.two (1 op / 1 member, depth 1.0) reads shallower than
            mod.one (2 ops / 4 members, depth 2.0); a no-surface module sorts last"
    (let [db  (build/maps->cozo
               [{:entity/id "m1" :structure/of :canvas.vocab.code.module/Module :entity/name "mod.one"}
                {:entity/id "m2" :structure/of :canvas.vocab.code.module/Module :entity/name "mod.two"}
                {:entity/id "m3" :structure/of :canvas.vocab.code.module/Module :entity/name "mod.three"}
                {:entity/id "o1" :structure/of :canvas.vocab.code.operation/Operation :entity/name "op-1"}
                {:entity/id "o2" :structure/of :canvas.vocab.code.operation/Operation :entity/name "op-2"}
                {:entity/id "o3" :structure/of :canvas.vocab.code.operation/Operation :entity/name "op-3"}
                {:entity/id "c1" :structure/of :canvas.vocab.code.operation/Operation :entity/name "helper-1"}
                {:entity/id "c2" :structure/of :canvas.vocab.code.operation/Operation :entity/name "helper-2"}
                {:entity/id "c3" :structure/of :canvas.vocab.code.operation/Operation :entity/name "helper-3"}]
               [{:rel/id "r1" :rel/from [:entity/id "m1"] :rel/kind :exposes :rel/to [:entity/id "o1"]}
                {:rel/id "r2" :rel/from [:entity/id "m1"] :rel/kind :exposes :rel/to [:entity/id "o2"]}
                {:rel/id "r3" :rel/from [:entity/id "m1"] :rel/kind :child   :rel/to [:entity/id "c1"]}
                {:rel/id "r4" :rel/from [:entity/id "m1"] :rel/kind :child   :rel/to [:entity/id "c2"]}
                {:rel/id "r5" :rel/from [:entity/id "m2"] :rel/kind :exposes :rel/to [:entity/id "o3"]}
                {:rel/id "r6" :rel/from [:entity/id "m3"] :rel/kind :child   :rel/to [:entity/id "c3"]}])
          all (set (map first (cq/q '[:find ?m :where [?m :structure/of :canvas.vocab.code.module/Module]] db)))
          fdg (m/render-finding db "Depth" all)]
      (is (= ["mod.two: interface 1 ops / implementation 1 members (depth 1.0)"
              "mod.one: interface 2 ops / implementation 4 members (depth 2.0)"
              "mod.three: interface 0 ops / implementation 1 members (no public surface)"]
             (mapv :note (:observations fdg)))))))
