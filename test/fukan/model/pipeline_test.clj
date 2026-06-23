(ns fukan.model.pipeline-test
  "Smoke test for the lean-kernel pipeline (decision (ii)): build-model ingests
   the defstructure canvas specs into one structure substrate db, which is the
   model. (Successor to the deleted smoke / roundtrip tests of the old map
   pipeline + Phase-6 analyzer.)"
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [canvas.vocab.type :as dialect]
            [fukan.model.pipeline :as pipeline]
            [canvas.vocab.code.kind :refer [Kind]]
            [canvas.vocab.grouping :refer [Grouping]]
            [fukan.canvas.core.lens :refer [Projection]]))

;; tags are ns-qualified; tests pass a short handle and match by its name
(defn- names-of [db tag]
  (->> (d/q '[:find ?n ?t :where [?e :structure/of ?t] [?e :entity/name ?n]] db)
       (filter (fn [[_ t]] (= (name tag) (name t)))) (map first) set))

;; ── ad-hoc dbs built from top-level value defs (assembled per test) ──────────

;; refined [:enum …] slot validation (relocated from the retired MetaSlot) — Task 3
(defstructure ECard "Test fixture: a structure with a refined enum slot."
  {:card [:enum "one" "many"]})
(ECard ^{:name "Bad"} ec-bad {:card "lots"})

;; a projection that is neither a base nor a contextualization
(Projection ^{:name "Empty"} pe-Empty)

;; an arrow-shaped Kind — Task 4 (scalars only, so render round-trips exactly)
(Kind ^{:name "Arrow"} k-arrow [:=> [:catn [:code-root :string]] :int])

;; a map-of-shaped Kind — Task 5
(Kind ^{:name "MapOf"} k-mapof [:map-of :string :int])

(deftest arrow-schema-round-trips
  (testing "a [:=> [:catn …] Out] Kind body reflects and renders back to the same malli form"
    (let [db    (a/assemble-vars [#'k-arrow])
          seid  (ffirst (d/q '[:find ?s :where [?e :entity/name "Arrow"]
                                       [?r :rel/from ?e] [?r :rel/kind :shape] [?r :rel/to ?s]] db))]
      (is (= [:=> [:catn [:code-root :string]] :int] (dialect/render db seid))))))

(deftest map-of-schema-round-trips
  (testing "a [:map-of K V] Kind body reflects and renders back"
    (let [db   (a/assemble-vars [#'k-mapof])
          seid (ffirst (d/q '[:find ?s :where [?e :entity/name "MapOf"]
                                      [?r :rel/from ?e] [?r :rel/kind :shape] [?r :rel/to ?s]] db))]
      (is (= [:map-of :string :int] (dialect/render db seid))))))

;; a Kind authored with a positional record shape — Task 2
(Kind ^{:name "Vshape"} k-vshape [:map [:law :string] [:offenders [:vector :int]]])
(Kind ^{:name "Opaque"} k-opaque "an opaque external — no body")

(deftest kind-carries-a-positional-schema-shape
  (testing "a positional malli body becomes the Kind's :shape Schema subgraph; opaque stays bodyless"
    (let [db (a/assemble-vars [#'k-vshape #'k-opaque])
          shape-kind (fn [nm] (ffirst (d/q '[:find ?k :in $ ?nm
                                             :where [?e :entity/name ?nm]
                                                    [?r :rel/from ?e] [?r :rel/kind :shape] [?r :rel/to ?s]
                                                    [?s :val/kind ?k]] db nm)))]
      (is (= "map" (shape-kind "Vshape")) "the record shape reflects as a :map Schema")
      (is (nil? (shape-kind "Opaque"))    "an opaque Kind has no :shape relation"))))

(deftest build-model-ingests-canvas-specs-into-a-structure-db
  (testing "the model is the merged structure db over the canvas/ defstructure specs"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Module) "infra-model")
          "the canvas/infra/model spec is discovered and ingested")
      ;; infra is now modelled with the fukan-on-fukan grammar (Operation/Kind), not
      ;; the (evicted) base Function/Type vocab; subset since other specs add more
      (is (set/subset? #{"load-model" "get-model" "refresh-model"} (names-of db :Operation)))
      (is (set/subset? #{"StructureDb" "Path"} (names-of db :Kind)))
      (is (empty? (s/check db))
          "the whole self-model satisfies every structure's laws"))))

(deftest pipeline-links-across-to-canvas-source
  (testing "build-model is a thin entry point whose :calls link to canvas-source/extraction"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Module) "model-pipeline")
          "the canvas/pipeline/model spec is ingested")
      ;; post-prune the pipeline subsystem is just build-model — the ingest/union
      ;; machinery lives in canvas-source now, not duplicated here
      (is (= ["build-model"]
             (d/q '[:find [?n ...]
                    :where [?m :entity/name "model-pipeline"]
                           [?cr :rel/kind :exposes] [?cr :rel/from ?m] [?cr :rel/to ?c]
                           [?c :structure/of :canvas.vocab.code.operation/Operation] [?c :entity/name ?n]]
                  db))
          "model.pipeline exposes exactly one operation — no stale duplicate ingest")
      ;; the seams: build-model's cross-module :delegates resolve to the canvas-source
      ;; ingest/union stages and to the target extractor (design + code unified)
      (is (= #{"build" "union-dbs"}
             (set (d/q '[:find [?bn ...]
                         :where [?mp :entity/name "model-pipeline"]
                                [?cm :rel/kind :exposes] [?cm :rel/from ?mp] [?cm :rel/to ?bm]
                                [?bm :entity/name "build-model"]
                                [?r :rel/from ?bm] [?r :rel/kind :delegates] [?r :rel/to ?b]
                                [?cs :entity/name "canvas-source"]
                                [?cc :rel/kind :exposes] [?cc :rel/from ?cs] [?cc :rel/to ?b]
                                [?b :entity/name ?bn]]
                       db)))
          "build-model delegates to canvas-source's build + union-dbs (its exposed API)")
      (is (= ["run-extractor"]
             (d/q '[:find [?en ...]
                    :where [?mp :entity/name "model-pipeline"]
                           [?cm :rel/kind :exposes] [?cm :rel/from ?mp] [?cm :rel/to ?bm]
                           [?bm :entity/name "build-model"]
                           [?r :rel/from ?bm] [?r :rel/kind :delegates] [?r :rel/to ?e]
                           [?ex :entity/name "extraction"]
                           [?ec :rel/kind :exposes] [?ec :rel/from ?ex] [?ec :rel/to ?e]
                           [?e :entity/name ?en]]
                  db))
          "build-model calls extraction/run-extractor — the design↔code seam, via the plug-point"))))

(deftest the-structuredb-kind-is-one-shared-owned-node
  (testing "the StructureDb Kind is a single node owned by core.substrate (its node home);
            subsystems reference it (no per-subsystem Model/Db redeclaration)"
    (let [db (pipeline/build-model nil)]
      (is (= 1 (count (d/q '[:find ?k :where [?k :structure/of :canvas.vocab.code.kind/Kind] [?k :entity/name "StructureDb"]] db)))
          "exactly one StructureDb Kind node")
      (is (= ["core-substrate"]
             (d/q '[:find [?mn ...]
                    :where [?k :entity/name "StructureDb"] [?k :structure/of :canvas.vocab.code.kind/Kind]
                           [?r :rel/kind :owns] [?r :rel/from ?m] [?r :rel/to ?k] [?m :entity/name ?mn]]
                  db))
          "core.substrate is its sole owner (the db it constructs)")
      (is (= 1 (count (d/q '[:find ?s
                             :where [?s :structure/of :canvas.vocab.type/Schema] [?s :val/kind "ref"]
                                    [?r :rel/from ?s] [?r :rel/kind :names] [?r :rel/to ?k]
                                    [?k :entity/name "StructureDb"]]
                           db)))
          "the ref-schema naming it is one value-identified node, reused across every subsystem"))))

(deftest canvas-source-effects-are-captured-and-value-identified
  (testing "Operation effects are recorded; :io (performed by 4 stages) is one shared node"
    (let [db (pipeline/build-model nil)]
      (is (= 1 (count (d/q '[:find ?e :where [?e :structure/of :canvas.vocab.code.effect/Effect] [?e :val/name "io"]] db)))
          "the :io effect is value-identified across every stage that performs it")
      (is (seq (d/q '[:find ?r
                      :where [?b :structure/of :canvas.vocab.code.operation/Operation] [?b :entity/name "build"]
                             [?r :rel/from ?b] [?r :rel/kind :performs] [?r :rel/to ?e]
                             [?e :structure/of :canvas.vocab.code.effect/Effect] [?e :val/name "io"]]
                    db))
          "build performs :io"))))

(deftest kernel-meta-model-captures-structure-composition
  (testing "the reflexive kernel model: 'a structure = slots + laws' reads off the
            REFLECTED grammar (the hand-modelled defstructure-layer Concepts are gone);
            the hand-modelled remainder is the substrate (Node, Relation)"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Module) "core-structure"))
      (is (= #{"tag" "value" "includes" "law" "realizes"}
             (set (d/q '[:find [?l ...]
                         :where [?st :structure/of :canvas.vocab.grammar/Structure]
                                [?st :val/tag ":canvas.vocab.grammar/Structure"]
                                [?r :rel/from ?st] [?r :rel/label ?l]] db)))
          "the Structure meta-structure's own reified slots — the strange loop")
      (is (= ":canvas.vocab.grammar/Law"
             (ffirst (d/q '[:find ?lt
                            :where [?st :val/tag ":canvas.vocab.grammar/Structure"]
                                   [?r :rel/from ?st] [?r :rel/label "law"] [?r :rel/to ?t]
                                   [?t :val/tag ?lt]] db)))
          "Structure.law targets the reified Law structure (composition)")
      (is (= #{"from" "to" "kind" "label" "order"}
             (set (d/q '[:find [?k ...]
                         :where [?kind :structure/of :canvas.vocab.code.kind/Kind] [?kind :entity/name "Relation"]
                                [?sr :rel/from ?kind] [?sr :rel/kind :shape] [?sr :rel/to ?schema]
                                [?fr :rel/from ?schema] [?fr :rel/kind :field] [?fr :rel/to ?f]
                                [?f :val/key ?k]] db)))
          "the substrate Relation is a Kind whose :shape is a :map of its fields"))))

(deftest refined-enum-slot-catches-out-of-set-value
  (testing "a refined [:enum …] scalar slot rejects an out-of-enum value via its generated law"
    (let [bad (a/assemble-vars [#'ec-bad])]
      (is (contains? (set (map :law (s/check bad)))
                     "ECard.card value must satisfy [:enum \"one\" \"many\"]")))))

(deftest lenses-modelled-as-cross-cutting-focuses
  (testing "the lens view: each lens is a focus over the model (the old lenses + checks' aspects)"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Grouping) "lens"))
      (is (set/subset? #{"survey" "patterns" "consistency" "tar-pit" "integrity" "coverage" "drift"}
                       (names-of db :Lens))))))

(deftest projection-subsystem-modelled-as-target-representations
  (testing "the projection view: model re-presented into targets through a lens + source→artifact mappings"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Grouping) "projection"))
      ;; Blueprint (code) + DriftClose (instructions — instruct ⊂ projection); more to come
      (is (set/subset? #{"Blueprint" "DriftClose"} (names-of db :Projection)))
      ;; a projection composes lens ∘ act too: Blueprint renders THROUGH the survey lens
      (is (seq (d/q '[:find ?l
                      :where [?p :structure/of :fukan.canvas.core.lens/Projection] [?p :entity/name "Blueprint"]
                             [?r :rel/from ?p] [?r :rel/kind :through] [?r :rel/to ?l]
                             [?l :structure/of :fukan.canvas.core.lens/Lens] [?l :entity/name "survey"]]
                    db))
          "Blueprint renders through the survey lens")
      ;; a projection is built from mappings (value-typed source→artifact pairs)
      (is (seq (d/q '[:find ?mp
                      :where [?p :structure/of :fukan.canvas.core.lens/Projection] [?p :entity/name "Blueprint"]
                             [?r :rel/from ?p] [?r :rel/kind :maps] [?r :rel/to ?mp]
                             [?mp :structure/of :fukan.canvas.core.lens/Mapping]
                             [?mp :val/from "a function"] [?mp :val/to "a defn"]]
                    db))
          "the Blueprint projection maps a function → a defn"))))

(deftest a-lens-is-reused-across-acts
  (testing "the payoff: ONE drift lens is a shared focus — the read focus AND the drift-close projection's focus"
    (let [db (pipeline/build-model nil)]
      (is (= 1 (count (d/q '[:find ?l :where [?l :structure/of :fukan.canvas.core.lens/Lens] [?l :entity/name "drift"]] db)))
          "there is exactly one drift lens node")
      (is (seq (d/q '[:find ?l
                      :where [?l :structure/of :fukan.canvas.core.lens/Lens] [?l :entity/name "drift"]
                             ;; rendered by a projection (drift-close) THROUGH the same focus
                             [?pj :structure/of :fukan.canvas.core.lens/Projection] [?rj :rel/from ?pj] [?rj :rel/kind :through] [?rj :rel/to ?l]]
                    db))
          "the one drift focus is rendered by the DriftClose projection"))))

(deftest projection-that-is-neither-base-nor-contextualization-is-caught
  (testing "a projection with neither mappings nor a contextualized base trips the flavour law"
    (let [db (a/assemble-vars [#'pe-Empty])]
      (is (contains? (set (map :law (s/check db)))
                     "a projection is a base (declares mappings) or a contextualization (frames another)")))))
