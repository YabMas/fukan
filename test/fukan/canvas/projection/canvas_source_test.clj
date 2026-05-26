(ns fukan.canvas.projection.canvas-source-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.core.substrate.store :as store]
            [fukan.canvas.construction :refer [function value]]
            [fukan.canvas.identity :as identity]
            [fukan.canvas.projection.canvas-source :as canvas-source]
            canvas.infra.server
            canvas.infra.model))

;; ---------------------------------------------------------------------------
;; Commit 1 tests: build-canvas-db
;; ---------------------------------------------------------------------------

(deftest build-canvas-db-produces-populated-db
  (testing "build-canvas-db returns a Datascript db with all canvas modules"
    (let [db (canvas-source/build-canvas-db)]
      (is (some? db))
      (is (seq (store/all-modules db)))
      (is (> (count (store/all-modules db)) 50)
          "Expected 60+ modules (62 canvas ports, each with ≥1 module)"))))

(deftest build-canvas-db-contains-known-modules
  (testing "known canvas port modules appear in the unified db"
    (let [db    (canvas-source/build-canvas-db)
          names (set (map :name (store/all-modules db)))]
      (is (contains? names "infra.server"))
      (is (contains? names "infra.model"))
      (is (contains? names "model.spec")))))

(deftest build-canvas-db-module-children-preserved
  (testing "module/child relationships survive the merge"
    (let [db     (canvas-source/build-canvas-db)
          mod-id (ffirst (d/q '[:find ?id
                                 :where [?e :entity/name "infra.server"]
                                        [?e :entity/id ?id]]
                               db))]
      (is (some? mod-id) "infra.server module must be present")
      (let [children (store/children-of-module db mod-id)]
        (is (pos? (count children))
            "infra.server must have children")
        (is (some #(= "start_server" (second %)) children))
        (is (some #(= "stop_server" (second %)) children))
        (is (some #(= "ServerOpts" (second %)) children))))))

(deftest build-canvas-db-references-preserved
  (testing ":references datoms survive the merge"
    (let [db   (canvas-source/build-canvas-db)
          refs (d/q '[:find ?v :where [_ :references ?v]] db)]
      (is (pos? (count refs))
          "At least some :references relations must be present"))))

(deftest build-canvas-db-no-cross-module-duplicate-warning
  (testing "build-canvas-db does not warn about cross-module duplicate names"
    ;; The fukan canvas has many cross-module name duplicates (e.g. CheckIsPure
    ;; in 7 validation.rules-4* modules). These are no longer flagged because
    ;; the module-qualified resolution disambiguates them via keyword namespace.
    ;; Only intra-module duplicates (authoring bugs) produce a warning now.
    (let [err-output (java.io.StringWriter.)
          db         (binding [*err* err-output]
                       (canvas-source/build-canvas-db))]
      (is (some? db))
      (is (not (.contains (.toString err-output) "first-match resolution will be used"))
          "old cross-module first-match warning must not appear in stderr"))))

;; ---------------------------------------------------------------------------
;; Commit 2 tests: project + build
;; ---------------------------------------------------------------------------

(deftest project-produces-model-map
  (testing "project returns a valid model map with all required keys"
    (let [model (canvas-source/build)]
      (is (map? (:primitives model)))
      (is (vector? (:edges model)))
      (is (vector? (:tag-apps model)))
      (is (vector? (:tag-defs model)))
      (is (vector? (:predicates model)))
      (is (vector? (:renderers model)))
      (is (map? (:artifacts model))))))

(deftest project-primitive-count
  (testing "build returns a model with populated primitives (all 62 canvas ports contribute)"
    (let [model (canvas-source/build)]
      (is (pos? (count (:primitives model)))
          "Expected at least some primitives from 62 canvas ports")
      (is (> (count (:primitives model)) 500)
          "Expected many primitives (62 modules + ~500 affordances + ~89 types)"))))

(deftest project-known-entity-present
  (testing "known entities from canvas ports appear in the projected model"
    (let [model (canvas-source/build)
          prims (:primitives model)]
      ;; Module
      (is (some #(= "infra.server" (:label %)) (vals prims))
          "infra.server module must appear as a primitive")
      ;; Affordance
      (is (contains? prims "infra.server/start_server")
          "start_server affordance must have stable id infra.server/start_server")
      ;; Type
      (is (contains? prims "infra.server/type/ServerOpts")
          "ServerOpts type must have stable id infra.server/type/ServerOpts"))))

(deftest project-module-has-children
  (testing "Module primitives have :children sets with their owned entities"
    (let [model       (canvas-source/build)
          infra-server (get (:primitives model) "infra.server")]
      (is (some? infra-server) "infra.server must exist")
      (is (set? (:children infra-server)) "children must be a set")
      (is (pos? (count (:children infra-server))) "infra.server must have children")
      (is (contains? (:children infra-server) "infra.server/start_server"))
      (is (contains? (:children infra-server) "infra.server/type/ServerOpts")))))

(deftest project-child-has-parent
  (testing "child primitives carry :parent pointing to their module"
    (let [model       (canvas-source/build)
          start-server (get (:primitives model) "infra.server/start_server")]
      (is (some? start-server) "start_server must exist")
      (is (= "infra.server" (:parent start-server))
          "start_server parent must be infra.server"))))

(deftest project-affordance-kinds
  (testing "Affordance role → primitive kind mapping is correct"
    (let [model (canvas-source/build)
          prims (:primitives model)
          ;; infra.server/SingleServerInstance is an invariant → :primitive/rule
          invariant-prim (get prims "infra.server/SingleServerInstance")
          ;; infra.server/start_server is an exposed-call → :primitive/operation
          operation-prim (get prims "infra.server/start_server")
          ;; infra.server/get_port is a getter → :primitive/operation
          getter-prim    (get prims "infra.server/get_port")]
      (is (= :primitive/rule      (:kind invariant-prim)) "invariant → :primitive/rule")
      (is (= :primitive/operation (:kind operation-prim)) "exposed-call → :primitive/operation")
      (is (= :primitive/operation (:kind getter-prim))    "getter → :primitive/operation"))))

(deftest project-edges-generated
  (testing ":references relations become :relation/uses edges"
    (let [model (canvas-source/build)]
      (is (pos? (count (:edges model))) "Expected some edges from :references")
      (is (every? #(= :relation/uses (:kind %)) (:edges model))
          "All canvas edges should be :relation/uses"))))

(deftest project-edge-ids-sequential
  (testing "edges have sequential string ids starting with 'e'"
    (let [model  (canvas-source/build)
          edges  (:edges model)]
      (when (seq edges)
        (is (= "e0" (:id (first edges))))
        (is (every? #(.startsWith ^String (:id %) "e") edges))))))

(deftest single-module-projection
  (testing "projecting a single canvas port produces the expected primitive count"
    (let [db    (canvas.infra.server/build-canvas)
          model (canvas-source/project db)
          prims (:primitives model)]
      ;; infra.server has: 1 module, 2 types (ServerOpts, ServerInfo),
      ;; 4 affordances (SingleServerInstance invariant, start_server, stop_server, get_port)
      (is (= 1 (count (filter #(= :primitive/container (:kind %))
                              (filter #(= (:id %) (:label %)) (vals prims))))
             ) "infra.server module must be the only top-level container")
      ;; total: 1 module + 2 types + 4 affordances = 7
      (is (= 7 (count prims))
          "infra.server should project to exactly 7 primitives"))))

(deftest references-edge-resolves-cross-module
  (testing "cross-module :references resolve to the target entity's stable id"
    (let [model    (canvas-source/build)
          ;; infra.model/load_model references :model/Model
          edges    (:edges model)
          relevant (filter #(= "infra.model/load_model" (get-in % [:from :id])) edges)]
      (is (seq relevant) "load_model must have at least one outgoing edge")
      ;; The target should be model.spec/type/Model
      (is (some #(= "model.spec/type/Model" (get-in % [:to :id])) relevant)
          "load_model edge should point to model.spec/type/Model"))))

;; ---------------------------------------------------------------------------
;; Phase 4 Sprint 1: module-qualified reference resolution
;; ---------------------------------------------------------------------------

(defn- build-dual-module-canvas
  "Build a canvas db with two modules that each declare an entity named 'Foo'.
   A third module 'consumer' has a function that references :modA/Foo,
   which should resolve to mod.modA's Foo and not mod.modB's Foo."
  []
  (let [modA-db     (h/with-canvas
                      (h/within-module "mod.modA"
                        (value "Foo" "Foo in modA")))
        modB-db     (h/with-canvas
                      (h/within-module "mod.modB"
                        (value "Foo" "Foo in modB")))
        consumer-db (h/with-canvas
                      (h/within-module "mod.consumer"
                        (function "useIt"
                          "Function that references Foo from modA only."
                          (takes [x :String])
                          (gives :modA/Foo))))]
    (canvas-source/merge-for-test [modA-db modB-db consumer-db])))

(deftest module-qualified-resolution-finds-correct-module
  (testing "when two modules both declare Foo, :modA/Foo resolves to modA's entity"
    (let [db         (build-dual-module-canvas)
          model      (canvas-source/project db)
          edges      (:edges model)
          from-edges (filter #(= "mod.consumer/useIt" (get-in % [:from :id])) edges)]
      (is (seq from-edges)
          "useIt must emit a :references edge")
      (is (some #(= "mod.modA/type/Foo" (get-in % [:to :id])) from-edges)
          "edge should target mod.modA/type/Foo")
      (is (not (some #(= "mod.modB/type/Foo" (get-in % [:to :id])) from-edges))
          "edge must NOT target mod.modB/type/Foo"))))

(deftest module-qualified-resolution-unknown-namespace
  (testing "reference to an unknown module namespace emits no edge (does not throw)"
    (let [db    (h/with-canvas
                  (h/within-module "some.module"
                    (function "f"
                      "References a non-existent module."
                      (takes [x :String])
                      (gives :nonexistent/Thing))))
          model (canvas-source/project db)
          edges (:edges model)]
      (is (empty? (filter #(= "some.module/f" (get-in % [:from :id])) edges))
          "unresolvable reference namespace should produce no edge"))))

(deftest module-qualified-resolution-unknown-entity-in-known-module
  (testing "reference to a known module namespace but absent entity name emits no edge"
    (let [modA-db  (h/with-canvas
                     (h/within-module "mod.modA"
                       (value "ActualThing" "a type")))
          consumer (h/with-canvas
                     (h/within-module "mod.consumer"
                       (function "f"
                         "References a missing entity in modA."
                         (takes [x :String])
                         (gives :modA/MissingThing))))
          db    (canvas-source/merge-for-test [modA-db consumer])
          model (canvas-source/project db)
          edges (:edges model)]
      (is (empty? (filter #(= "mod.consumer/f" (get-in % [:from :id])) edges))
          "reference to missing entity in known module should produce no edge"))))

(deftest intra-module-duplicate-throws
  (testing "a single module declaring two entities with the same name causes an error"
    ;; Build a db manually: one module, two children with identical :entity/name
    (let [mod-uuid (random-uuid)
          ent-uuid-1 (random-uuid)
          ent-uuid-2 (random-uuid)
          db (-> (store/create)
                 (d/db-with [{:entity/id   mod-uuid
                               :entity/type :Module
                               :entity/name "bad.module"}
                              {:entity/id   ent-uuid-1
                               :entity/type :Type
                               :entity/name "DuplicateName"}
                              {:entity/id   ent-uuid-2
                               :entity/type :Type
                               :entity/name "DuplicateName"}])
                 (d/db-with [[:db/add [:entity/id mod-uuid] :module/child [:entity/id ent-uuid-1]]
                              [:db/add [:entity/id mod-uuid] :module/child [:entity/id ent-uuid-2]]]))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (canvas-source/detect-intra-module-duplicates-for-test db))
          "intra-module duplicate entity names must throw ExceptionInfo"))))

;; ---------------------------------------------------------------------------
;; Phase 4 Sprint 1 Task 2: stable-id alias — end-to-end via canvas port
;; ---------------------------------------------------------------------------

(deftest infra-server-alias-resolves-via-build-canvas-db
  (testing "the sample alias in canvas.infra.server resolves via the full unified db"
    ;; canvas.infra.server declares (alias \"infra.server/start\" \"start_server\")
    ;; After building the full canvas db, resolving that old id should return
    ;; the canonical id of start_server.
    (let [db (canvas-source/build-canvas-db)]
      (is (= "infra.server/start_server"
             (identity/resolve-id db "infra.server/start"))
          "old alias 'infra.server/start' must resolve to 'infra.server/start_server' in the unified db"))))

(deftest infra-server-alias-single-port
  (testing "the sample alias resolves when projecting only the infra.server port"
    (let [db (canvas.infra.server/build-canvas)]
      (is (= "infra.server/start_server"
             (identity/resolve-id db "infra.server/start"))
          "single-port alias must resolve"))))
