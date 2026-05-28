(ns fukan.canvas.project.coverage-test
  "Regression guard: every canvas-role and type-kind that canvas-source
   emits must have a registered Clojure-lens projection (or be on the
   explicit allow-list of intentionally-unprojected discriminators).

   When this test fails, the canvas-source's emission surface has grown
   a new affordance role or type-kind. Either:

     1. register a Clojure-lens projection at
        `src/fukan/canvas/project/clojure/<role-or-kind>_to_<idiom>.clj`,
        defmethod-ing on `[:clojure <discriminator>]`, and require it
        in `fukan.canvas.project.clojure`, OR
     2. add the discriminator to `intentionally-unprojected` here with
        a comment explaining why no projection is required.

   This test is the Phase 7.5 Sprint 1 anti-regression: the Phase 7
   trial-run found four canvas-roles silently uncovered (`:canvas/getter`,
   `:canvas/handler`, `:canvas/checker`, plus a defensive
   `:canvas/operation`). Sprint 1 added projections; this guard keeps
   the next emission from re-opening the gap."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [datascript.core :as d]
            [fukan.canvas.project.core :as core]
            [fukan.canvas.project.clojure :as _clojure-lens]
            [fukan.canvas.projection.canvas-source :as canvas-source]))

;; ---------------------------------------------------------------------------
;; Discriminator sets

(def ^:private intentionally-unprojected-affordance-roles
  "Affordance roles that exist in canvas-source/affordance-kind's `case`
   table but for which a Clojure-lens projection is intentionally not
   required. Add a role here with a comment explaining why; the default
   stance is to register a projection."
  #{;; (empty — every emitted role currently has a projection)
    })

(def ^:private intentionally-unprojected-type-kinds
  "Type-kinds (per `core/dispatch-key-of`) that exist in canvas-source's
   emission surface but for which a Clojure-lens projection is
   intentionally not required."
  #{;; (empty — both :atomic and :record have projections)
    })

;; ---------------------------------------------------------------------------
;; Live-canvas discovery

(defn- live-canvas-db
  []
  (canvas-source/build-canvas-db))

(defn- emitted-affordance-roles
  "Distinct :affordance/role values currently emitted by canvas-source's
   ingestion of the canvas/ tree."
  [db]
  (into #{}
        (map first)
        (d/q '[:find ?role
               :where [_ :affordance/role ?role]]
             db)))

(defn- emitted-type-kinds
  "Distinct type-kind discriminators per `core/dispatch-key-of` for
   Types emitted by canvas-source. A Type with field-shapes is :record;
   an atomic Type (no fields) is :atomic."
  [db]
  (let [type-eids   (mapv first (d/q '[:find ?t :where [?t :entity/type :Type]] db))
        any-record? (some (fn [t]
                            (seq (or (:type/field-shapes (d/entity db t)) [])))
                          type-eids)
        any-atomic? (some (fn [t]
                            (empty? (or (:type/field-shapes (d/entity db t)) [])))
                          type-eids)]
    (cond-> #{}
      any-record? (conj :Type/record)
      any-atomic? (conj :Type/atomic))))

(defn- registered-clojure-dispatch-keys
  "The dispatch-key set currently registered against [:clojure …]."
  []
  (->> (methods core/project)
       (keys)
       (remove #(= :default %))
       (filter #(= :clojure (first %)))
       (map second)
       set))

;; ---------------------------------------------------------------------------
;; The guard

(deftest every-emitted-affordance-role-has-a-projection
  (testing "canvas-source's emission surface is fully covered by Clojure-lens projections"
    (let [db         (live-canvas-db)
          emitted    (emitted-affordance-roles db)
          registered (registered-clojure-dispatch-keys)
          exempt     intentionally-unprojected-affordance-roles
          gaps       (set/difference emitted registered exempt)]
      (is (empty? gaps)
          (str "canvas-source emits affordance roles with no Clojure-lens projection: " (vec gaps)
               "\n\nFix path:"
               "\n  1. drop a file at src/fukan/canvas/project/clojure/<role>_to_<idiom>.clj"
               "\n  2. defmethod fukan.canvas.project.core/project [:clojure <role>]"
               "\n  3. require it in fukan.canvas.project.clojure"
               "\nOR add the role to coverage_test's intentionally-unprojected-affordance-roles"
               " with a comment explaining why no projection is required.")))))

(deftest every-emitted-type-kind-has-a-projection
  (testing "canvas-source's Type emission surface is covered by :Type/atomic + :Type/record projections"
    (let [db         (live-canvas-db)
          emitted    (emitted-type-kinds db)
          registered (registered-clojure-dispatch-keys)
          exempt     intentionally-unprojected-type-kinds
          gaps       (set/difference emitted registered exempt)]
      (is (empty? gaps)
          (str "canvas-source emits Type discriminators with no Clojure-lens projection: " (vec gaps)
               "\n\nFix path:"
               "\n  1. drop a file at src/fukan/canvas/project/clojure/<discriminator>_to_<idiom>.clj"
               "\n  2. defmethod fukan.canvas.project.core/project [:clojure <discriminator>]"
               "\n  3. require it in fukan.canvas.project.clojure"
               "\nOR add the discriminator to coverage_test's intentionally-unprojected-type-kinds.")))))

(deftest canonical-sprint-1-example-projects-through
  (testing "the Phase 7 trial's canonical canvas/getter element projects through to a valid spec"
    (let [el {:model-element-kind :Affordance
              :canvas-role        :canvas/getter
              :stable-id          "distributed.cluster/get_self_role"
              :entity-name        "get_self_role"
              :module-coord       "distributed.cluster"
              :doc                "This node's current role within the cluster."
              :inputs             []
              :outputs            {:kind :optional :inner {:kind :ref :target :NodeRole}}}
          p (core/project :clojure el {:registry {:root-prefix "fukan"}})]
      (is (core/valid-projection? p))
      (is (= :clojure/getter-to-defn (:projection-kind p)))
      (is (= "get-self-role" (-> p :target :symbol))))))

(deftest both-invariant-projections-are-registered
  ;; Phase 8 Sprint 5 — invariants get two registered projections:
  ;;   * `:canvas/invariant`                      → predicate stub under src/
  ;;     (opt-in via `(projects-to :predicate)` — reserved for future)
  ;;   * `:canvas/invariant+property-test`        → defspec under test/
  ;;     (the default; the synthetic dispatch key from `dispatch-key-of`)
  ;; The coverage regression accepts either as covering an emitted
  ;; :canvas/invariant role.
  (testing "the predicate fallback projection stays registered"
    (is (contains? (registered-clojure-dispatch-keys) :canvas/invariant)))
  (testing "the property-test default projection is registered"
    (is (contains? (registered-clojure-dispatch-keys)
                   :canvas/invariant+property-test))))

(deftest canonical-invariant-projects-to-property-test
  ;; Phase 8 Sprint 5 — Option β. A canvas invariant element with no
  ;; explicit projection-kind override routes through the synthetic
  ;; dispatch key `:canvas/invariant+property-test`, landing in the
  ;; new `invariant-to-property-test` projection. The target file is
  ;; under `test/`; the symbol carries the `-property` suffix; the
  ;; template renders a `defspec` form with `clojure.test.check`
  ;; conventions.
  (testing "an invariant without :predicate override projects to a property test"
    (let [el {:model-element-kind :Affordance
              :canvas-role        :canvas/invariant
              :stable-id          "distributed.cluster/invariant/MajorityRequiredForLeadership"
              :entity-name        "MajorityRequiredForLeadership"
              :module-coord       "distributed.cluster"
              :doc                "A node becomes leader only with majority vote grants."
              :holds-that         "no node is leader for term T without > N/2 grants for T"}
          p (core/project :clojure el {:registry {:root-prefix "fukan"}})]
      (is (core/valid-projection? p))
      (is (= :clojure/invariant-to-property-test (:projection-kind p)))
      (is (= "majority-required-for-leadership-property" (-> p :target :symbol)))
      (is (= "fukan.distributed.cluster-test" (-> p :target :namespace)))
      (is (= "test/fukan/distributed/cluster_test.clj" (-> p :target :path))))))

(deftest invariant-with-predicate-override-falls-back
  ;; Phase 8 Sprint 5 — the opt-out path. When the element carries
  ;; `:canvas-projection-kind :predicate` (set by canvas-source when the
  ;; canvas declaration's `(projects-to :predicate)` clause fires —
  ;; reserved for future), `dispatch-key-of` falls back to the plain
  ;; `:canvas/invariant` role and the existing `invariant-to-predicate`
  ;; projection runs.
  (testing "an invariant with explicit :predicate projection-kind routes to invariant-to-predicate"
    (let [el {:model-element-kind     :Affordance
              :canvas-role            :canvas/invariant
              :canvas-projection-kind :predicate
              :stable-id              "validation.rules-4a/invariant/X"
              :entity-name            "X"
              :module-coord           "validation.rules-4a"
              :doc                    "Doc."
              :holds-that             "X holds."}
          p (core/project :clojure el {:registry {:root-prefix "fukan"}})]
      (is (core/valid-projection? p))
      (is (= :clojure/invariant-to-predicate (:projection-kind p))))))
