(ns fukan.model.readings-test
  "The READINGS — patterns/consistency/depth, Projections whose target artifact is a Finding, rendered
   through their :through lens by materialize/render-finding. The defining property (vs the old
   probes): a reading renders ONLY its lens's focus — it never re-selects, so it cannot drift from
   its lens."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
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
      (is (= #{"Patterns" "Consistency" "Depth" "Boundary"} (set (keys all)))
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

(deftest depth-reading-surfaces-interface-vs-implementation
  (testing "Depth renders per-module interface/implementation counts, shallowest first"
    (let [db     (pipeline/build-model nil)
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
