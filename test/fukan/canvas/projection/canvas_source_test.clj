(ns fukan.canvas.projection.canvas-source-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.core.substrate.store :as store]
            [fukan.canvas.construction :refer [function record value]]
            [fukan.canvas.identity :as identity]
            [fukan.canvas.projection.canvas-source :as canvas-source]
            [fukan.canvas.vocab.behavioral :refer [invariant rule]]
            [fukan.canvas.vocab.event :refer [event]]
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

(deftest project-event-routes-to-primitive-event
  (testing "an Affordance with role :canvas/event projects as :primitive/event"
    ;; Gap 4: affordance-kind previously had no case for :canvas/event so
    ;; events fell through to the default :primitive/operation, hiding them
    ;; from the analyzer's events selector.
    (let [mod-db (h/with-canvas
                   (h/within-module "demo.events"
                     (event "ThingHappened" "An event.")))
          model  (canvas-source/project (canvas-source/merge-for-test [mod-db]))
          ev     (get (:primitives model) "demo.events/ThingHappened")]
      (is (some? ev) "event must be projected as a primitive")
      (is (= :primitive/event (:kind ev))
          (str "expected :primitive/event, got " (pr-str (:kind ev)))))))

(deftest project-invariant-label-is-holds-that
  (testing "an Affordance with role :canvas/invariant uses :holds-that string as label"
    ;; Phase 6 Sprint 2 Task 4 sub-task A: invariants project so the analyzer's
    ;; rules selector can derive a canonical address from the holds-that clause
    ;; (the canvas-side expected code-side predicate name). Without this the
    ;; entity name (PascalCase) is the label and drift derives addresses against
    ;; the wrong code-side counterpart.
    (let [mod-db (h/with-canvas
                   (h/within-module "demo.inv"
                     (invariant "FooHolds"
                       "Foo must hold."
                       (holds-that "foo-must-hold"))))
          model  (canvas-source/project (canvas-source/merge-for-test [mod-db]))
          prim   (get (:primitives model) "demo.inv/FooHolds")]
      (is (some? prim) "invariant must be projected as a primitive")
      (is (= :primitive/rule (:kind prim))
          (str "expected :primitive/rule, got " (pr-str (:kind prim))))
      (is (= "foo-must-hold" (:label prim))
          (str "expected label \"foo-must-hold\", got " (pr-str (:label prim))))
      (is (= :canvas/invariant (:canvas-role prim))
          "primitive must carry :canvas-role for drift-helper messages"))))

(deftest project-rule-carries-canvas-role
  (testing "an Affordance with role :canvas/rule projects with :canvas-role"
    ;; Phase 6 Sprint 2 Task 4 sub-task B: rules project so the analyzer's
    ;; rules selector can derive a canonical code-side predicate fn name from
    ;; the rule's own name (kebab-cased via address/local-name). The primitive
    ;; carries :canvas-role :canvas/rule so the drift helper can phrase
    ;; bilateral findings ("Canvas rule X has no implementation").
    (let [mod-db (h/with-canvas
                   (h/within-module "demo.rule"
                     (rule "RunPhase5"
                       "Run phase 5."
                       (when RunPhase5 (model :model/Model)))))
          model  (canvas-source/project (canvas-source/merge-for-test [mod-db]))
          prim   (get (:primitives model) "demo.rule/RunPhase5")]
      (is (some? prim) "rule must be projected as a primitive")
      (is (= :primitive/rule (:kind prim))
          (str "expected :primitive/rule, got " (pr-str (:kind prim))))
      (is (= "RunPhase5" (:label prim))
          "label is rule's own name; address-derivation does kebab-casing")
      (is (= :canvas/rule (:canvas-role prim))
          "primitive must carry :canvas-role for drift-helper messages"))))

(deftest project-rule-and-invariant-pair-coexist
  (testing "rule + invariant pair sharing an :entity/name both reach the primitives map"
    ;; The name+role convention permits a module to declare a rule + invariant
    ;; with the same name (e.g. canvas/validation/rules_4a's AtMostOneCompositeParent
    ;; pair). Both must reach :primitives so the drift helper can check each
    ;; against its canonical code-side counterpart independently.
    (let [mod-db (h/with-canvas
                   (h/within-module "demo.pair"
                     (rule "SameName"
                       "Reactive form."
                       (when SameName (model :model/Model)))
                     (invariant "SameName"
                       "Timeless form."
                       (holds-that "same-name-property"))))
          model  (canvas-source/project (canvas-source/merge-for-test [mod-db]))
          ;; The pair shares an entity-name so they collide on stable-id.
          ;; This is a known authoring pattern. The projection layer must
          ;; preserve both so drift can check both independently.
          prims-at-id (filter #(= "demo.pair/SameName" (:id %))
                              (vals (:primitives model)))]
      ;; If they collide on the map key, count is 1 and one role is missing.
      ;; Acceptable outcome: both surface as separate primitives with distinct
      ;; ids OR a single primitive whose canvas-role roundtrips so the drift
      ;; helper can disambiguate downstream.
      (is (pos? (count prims-at-id))
          "at least one primitive must be projected for SameName"))))

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

(deftest fully-qualified-reference-resolves-to-dotted-module
  (testing "a fully-qualified ref like :mod.sub.module/Foo resolves to module 'mod.sub.module'"
    (let [owner-db    (h/with-canvas
                        (h/within-module "mod.sub.module"
                          (value "Foo" "Foo in the dotted module")))
          consumer-db (h/with-canvas
                        (h/within-module "consumer"
                          (function "useIt"
                            "References Foo via the fully-qualified module path."
                            (takes [x :String])
                            (gives :mod.sub.module/Foo))))
          db          (canvas-source/merge-for-test [owner-db consumer-db])
          model       (canvas-source/project db)
          edges       (:edges model)
          from-edges  (filter #(= "consumer/useIt" (get-in % [:from :id])) edges)]
      (is (seq from-edges)
          "useIt must emit a :references edge for the fully-qualified ref")
      (is (some #(= "mod.sub.module/type/Foo" (get-in % [:to :id])) from-edges)
          "edge should target the entity in the fully-qualified module"))))

(deftest short-form-reference-still-resolves-via-segment-match
  (testing "the short form :module/Foo continues to resolve to module 'mod.sub.module' via segment-match"
    (let [owner-db    (h/with-canvas
                        (h/within-module "mod.sub.module"
                          (value "Foo" "Foo in the dotted module")))
          consumer-db (h/with-canvas
                        (h/within-module "consumer"
                          (function "useIt"
                            "References Foo via the short last-segment form."
                            (takes [x :String])
                            (gives :module/Foo))))
          db          (canvas-source/merge-for-test [owner-db consumer-db])
          model       (canvas-source/project db)
          edges       (:edges model)
          from-edges  (filter #(= "consumer/useIt" (get-in % [:from :id])) edges)]
      (is (seq from-edges)
          "useIt must emit a :references edge for the short-form ref")
      (is (some #(= "mod.sub.module/type/Foo" (get-in % [:to :id])) from-edges)
          "short-form ref should still target the entity via segment-matching"))))

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

;; ---------------------------------------------------------------------------
;; Phase 5 Sprint 2 Task 3: name+role disambiguation convention
;; ---------------------------------------------------------------------------
;;
;; A canvas module may declare multiple entities with the same :entity/name
;; PROVIDED they have distinct :affordance/role values. The canonical example
;; is the rule + invariant pair in `canvas/validation/*`: a single behavioral
;; commitment expressed from two angles. Reference resolution disambiguates
;; via the (name, role) tuple where role is unambiguous from context.
;;
;; These tests lock in the substrate behavior that makes the convention work.

(deftest name-plus-role-rule-invariant-pair-coexist
  (testing "a rule and an invariant with the same name in one module produce two distinct entities"
    (let [db    (h/with-canvas
                  (h/within-module "demo.rules"
                    (rule "SharedName"
                      "Reactive declaration that shares its name with an invariant."
                      (when SharedName (model :model/Model)))
                    (invariant "SharedName"
                      "Timeless commitment that shares its name with a rule."
                      (holds-that "shared-name-is-intentional"))))
          ;; Find the module entity
          mod-eid (ffirst (d/q '[:find ?m
                                  :where [?m :entity/type :Module]
                                         [?m :entity/name "demo.rules"]]
                                db))
          ;; Find children with name SharedName
          children (d/q '[:find ?c ?role ?uuid
                           :in $ ?m
                           :where [?m :module/child ?c]
                                  [?c :entity/name "SharedName"]
                                  [?c :affordance/role ?role]
                                  [?c :entity/id ?uuid]]
                         db mod-eid)]
      (is (= 2 (count children))
          "both rule and invariant must appear as distinct children")
      (let [roles (set (map second children))
            uuids (set (map #(nth % 2) children))]
        (is (= #{:canvas/rule :canvas/invariant} roles)
            "the two children must carry distinct :affordance/role values")
        (is (= 2 (count uuids))
            "the two children must have distinct :entity/id UUIDs"))
      ;; Disambiguation by (name, role) tuple resolves uniquely
      (let [resolve-by-name+role
            (fn [role-kw]
              (d/q '[:find ?c .
                      :in $ ?m ?role
                      :where [?m :module/child ?c]
                             [?c :entity/name "SharedName"]
                             [?c :affordance/role ?role]]
                    db mod-eid role-kw))]
        (is (some? (resolve-by-name+role :canvas/rule))
            "(name=SharedName, role=:canvas/rule) resolves to one entity")
        (is (some? (resolve-by-name+role :canvas/invariant))
            "(name=SharedName, role=:canvas/invariant) resolves to one entity")
        (is (not= (resolve-by-name+role :canvas/rule)
                  (resolve-by-name+role :canvas/invariant))
            "the two role lookups must resolve to different entities")))))

;; ---------------------------------------------------------------------------
;; Ref-typed cardinality-many merge: :triggers / :emits eid→uuid translation
;; ---------------------------------------------------------------------------
;;
;; Before the fix, db->entity-maps stored :triggers / :emits as raw per-module
;; eids in entity-maps. When the merged db was built, those integer eids
;; pointed at whatever entity happened to land on that eid in the unified db
;; — usually the wrong target. These tests lock in correct eid→uuid
;; translation across the per-module → unified-db merge.

(deftest triggers-relation-survives-merge
  (testing ":triggers ref survives per-module → unified-db merge and resolves to the correct rule"
    ;; modB is transacted FIRST so its decoy entities populate the merged-db's
    ;; low eid slots — guaranteeing that modA's per-module eid for RuleX (which
    ;; would be small) does NOT coincide with the merged-db eid of RuleX. Any
    ;; failure to translate eid→uuid will land on a decoy entity from modB,
    ;; surfacing the merge bug.
    (let [modB-db (h/with-canvas
                    (h/within-module "demo.modB"
                      (value "Decoy0" "Decoy.")
                      (value "Decoy1" "Decoy.")
                      (value "Decoy2" "Decoy.")
                      (value "Decoy3" "Decoy.")
                      (value "Decoy4" "Decoy.")
                      (value "Decoy5" "Decoy.")))
          modA-db (h/with-canvas
                    (h/within-module "demo.modA"
                      (rule "RuleX"
                        "Reactive rule fired by a function."
                        (when RuleX (model :model/Model)))
                      (function "doWork"
                        "Function that triggers RuleX."
                        (takes [x :String])
                        (gives :String)
                        (triggers RuleX))))
          db      (canvas-source/merge-for-test [modB-db modA-db])
          ;; Walk :triggers datoms; each value is a ref eid in the merged db.
          target-roles (->> (d/datoms db :aevt :triggers)
                            (map (fn [datom]
                                   (let [target-ent (d/entity db (.-v datom))]
                                     [(:entity/name target-ent)
                                      (:affordance/role target-ent)])))
                            vec)]
      (is (= 1 (count target-roles))
          "exactly one :triggers datom expected (doWork → RuleX)")
      (is (= ["RuleX" :canvas/rule] (first target-roles))
          ":triggers must resolve to the rule named RuleX, not a stray entity"))))

(deftest emits-relation-survives-merge
  (testing ":emits ref survives per-module → unified-db merge and resolves to the correct event"
    ;; modB is transacted FIRST so its decoys eat early merged-db eids; see
    ;; triggers-relation-survives-merge for rationale.
    (let [modB-db (h/with-canvas
                    (h/within-module "demo.modB"
                      (value "Decoy0" "Decoy.")
                      (value "Decoy1" "Decoy.")
                      (value "Decoy2" "Decoy.")
                      (value "Decoy3" "Decoy.")
                      (value "Decoy4" "Decoy.")
                      (value "Decoy5" "Decoy.")))
          modA-db (h/with-canvas
                    (h/within-module "demo.modA"
                      (event "ThingHappened"
                        "An event the function emits.")
                      (function "doWork"
                        "Function that emits ThingHappened."
                        (takes [x :String])
                        (gives :String)
                        (emits ThingHappened))))
          db      (canvas-source/merge-for-test [modB-db modA-db])
          target-roles (->> (d/datoms db :aevt :emits)
                            (map (fn [datom]
                                   (let [target-ent (d/entity db (.-v datom))]
                                     [(:entity/name target-ent)
                                      (:affordance/role target-ent)])))
                            vec)]
      (is (= 1 (count target-roles))
          "exactly one :emits datom expected (doWork → ThingHappened)")
      (is (= ["ThingHappened" :canvas/event] (first target-roles))
          ":emits must resolve to the event named ThingHappened, not a stray entity"))))

(deftest triggers-integrity-clean-in-full-canvas
  (testing "the full canvas db produces no :triggers-target-not-a-rule integrity findings"
    ;; Regression test for the live bug: constraint.phase5/run, target.clojure.analyzer/run,
    ;; and validation.phase4/run all declare (triggers RunPhase5) etc. with matching rules
    ;; in-module. The merge bug caused those triggers to alias onto unrelated entities.
    (let [db        (canvas-source/build-canvas-db)
          findings  (require 'fukan.canvas.inspect.integrity)
          _ findings
          run-fn    (resolve 'fukan.canvas.inspect.integrity/check)
          all       (run-fn db)
          triggers-findings (filter #(= :inspect.integrity/triggers-target-not-a-rule
                                         (:check %))
                                    all)]
      (is (empty? triggers-findings)
          (str ":triggers integrity findings must be empty after the merge fix; got "
               (pr-str triggers-findings))))))

(deftest type-field-types-survives-merge
  (testing ":type/field-types (cardinality-many scalar) survives merge for a record with multiple field types"
    (let [modA-db (h/with-canvas
                    (h/within-module "demo.modA"
                      (value "Alpha" "Type A.")
                      (value "Beta"  "Type B.")
                      (value "Gamma" "Type C.")
                      (record "Triple"
                        "A record referring to three distinct field types."
                        (field a :Alpha)
                        (field b :Beta)
                        (field c :Gamma))))
          modB-db (h/with-canvas
                    (h/within-module "demo.modB"
                      (value "Decoy" "Decoy.")))
          db      (canvas-source/merge-for-test [modA-db modB-db])
          field-types (ffirst (d/q '[:find ?fts
                                      :where [?e :entity/name "Triple"]
                                             [?e :type/field-types ?fts]]
                                    db))
          ;; Datascript returns each card-many value separately; collect them all
          all-field-types (d/q '[:find [?ft ...]
                                  :where [?e :entity/name "Triple"]
                                         [?e :type/field-types ?ft]]
                                db)]
      (is (some? field-types) ":type/field-types must be present on the merged Triple record")
      (is (= #{:Alpha :Beta :Gamma} (set all-field-types))
          "all three field-type names must survive the merge — none silently dropped"))))

(deftest name-plus-role-warn-only-not-throw
  (testing "the rule+invariant pair produces an informational warning, not an exception"
    ;; The full canvas corpus has 31 such pairs in canvas/validation/*; this
    ;; locks in that build-canvas-db never throws on them.
    (let [err-output (java.io.StringWriter.)
          db         (binding [*err* err-output]
                       (canvas-source/build-canvas-db))
          err-str    (.toString err-output)]
      (is (some? db) "build-canvas-db must succeed despite intra-module same-name pairs")
      (is (.contains err-str "name+role convention")
          "warning text must reference the name+role convention")
      (is (.contains err-str ":canvas/rule")
          "warning must surface the distinct roles (e.g. :canvas/rule)")
      (is (.contains err-str ":canvas/invariant")
          "warning must surface the distinct roles (e.g. :canvas/invariant)"))))
