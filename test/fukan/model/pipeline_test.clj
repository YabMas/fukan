(ns fukan.model.pipeline-test
  "Smoke test for the lean-kernel pipeline (decision (ii)): build-model ingests
   the defstructure canvas specs into one structure substrate db, which is the
   model. (Successor to the deleted smoke / roundtrip tests of the old map
   pipeline + Phase-6 analyzer.)"
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s]
            [fukan.model.pipeline :as pipeline]
            [canvas.vocabulary.meta :refer [Concept MetaSlot]]
            [lib.grouping :refer [Grouping]]
            [canvas.vocabulary.act :refer [Projection]]))

;; tags are ns-qualified; tests pass a short handle and match by its name
(defn- names-of [db tag]
  (->> (d/q '[:find ?n ?t :where [?e :structure/of ?t] [?e :entity/name ?n]] db)
       (filter (fn [[_ t]] (= (name tag) (name t)))) (map first) set))

;; ── ad-hoc dbs built from top-level value defs (assembled per test) ──────────

;; a MetaSlot with an unknown cardinality
(Concept ^{:name "T"} mc-T)
(Concept ^{:name "X"} mc-X {:slot [(MetaSlot {:name "f" :cardinality "lots" :of mc-T})]})

;; a projection that is neither a base nor a contextualization
(Projection ^{:name "Empty"} pe-Empty)

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
                           [?c :structure/of :lib.code/Operation] [?c :entity/name ?n]]
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
  (testing "after consolidation the StructureDb Kind is a single node owned by core.structure;
            subsystems reference it (no per-subsystem Model/Db redeclaration)"
    (let [db (pipeline/build-model nil)]
      (is (= 1 (count (d/q '[:find ?k :where [?k :structure/of :lib.code/Kind] [?k :entity/name "StructureDb"]] db)))
          "exactly one StructureDb Kind node")
      (is (= ["core-structure"]
             (d/q '[:find [?mn ...]
                    :where [?k :entity/name "StructureDb"] [?k :structure/of :lib.code/Kind]
                           [?r :rel/kind :owns] [?r :rel/from ?m] [?r :rel/to ?k] [?m :entity/name ?mn]]
                  db))
          "core.structure is its sole owner")
      (is (= 1 (count (d/q '[:find ?s
                             :where [?s :structure/of :lib.type.malli/Schema] [?s :val/kind "ref"]
                                    [?r :rel/from ?s] [?r :rel/kind :names] [?r :rel/to ?k]
                                    [?k :entity/name "StructureDb"]]
                           db)))
          "the ref-schema naming it is one value-identified node, reused across every subsystem"))))

(deftest canvas-source-effects-are-captured-and-value-identified
  (testing "Operation effects are recorded; :io (performed by 4 stages) is one shared node"
    (let [db (pipeline/build-model nil)]
      (is (= 1 (count (d/q '[:find ?e :where [?e :structure/of :lib.code/Effect] [?e :val/name "io"]] db)))
          "the :io effect is value-identified across every stage that performs it")
      (is (seq (d/q '[:find ?r
                      :where [?b :structure/of :lib.code/Operation] [?b :entity/name "build"]
                             [?r :rel/from ?b] [?r :rel/kind :performs] [?r :rel/to ?e]
                             [?e :structure/of :lib.code/Effect] [?e :val/name "io"]]
                    db))
          "build performs :io"))))

(deftest grammar-is-reflected-onto-the-model
  (testing "the registry has no off-graph remainder: the subject grammar is reified —
            slots as :slot/<card> edges, refined scalars as Schema values, strange loop closed"
    (let [db (pipeline/build-model nil)]
      (is (= {"into" :slot/one, "polarity" :slot/one}
             (into {} (d/q '[:find ?l ?k
                             :where [?s :structure/of :lib.grammar/Structure]
                                    [?s :val/tag ":canvas.subject/Source"]
                                    [?r :rel/from ?s] [?r :rel/kind ?k] [?r :rel/label ?l]] db)))
          "Source's slots are cardinality-kinded, name-labeled edges")
      (is (= "enum"
             (ffirst (d/q '[:find ?kind
                            :where [?s :val/tag ":canvas.subject/Source"]
                                   [?r :rel/from ?s] [?r :rel/label "polarity"] [?r :rel/to ?t]
                                   [?t :val/kind ?kind]] db)))
          "the refined polarity slot targets its Schema value")
      (is (seq (d/q '[:find ?s :where [?s :val/tag ":lib.grammar/Structure"]] db))
          "the reflection self-reifies (Structure has a Structure node)"))))

(deftest kernel-meta-model-captures-structure-composition
  (testing "the reflexive kernel model: 'a structure = slots + laws' reads off the
            REFLECTED grammar (the hand-modelled defstructure-layer Concepts are gone);
            the hand-modelled remainder is the substrate (Node, Relation)"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Module) "core-structure"))
      (is (= #{"tag" "value" "includes" "law" "realizes"}
             (set (d/q '[:find [?l ...]
                         :where [?st :structure/of :lib.grammar/Structure]
                                [?st :val/tag ":lib.grammar/Structure"]
                                [?r :rel/from ?st] [?r :rel/label ?l]] db)))
          "the Structure meta-structure's own reified slots — the strange loop")
      (is (= ":lib.grammar/Law"
             (ffirst (d/q '[:find ?lt
                            :where [?st :val/tag ":lib.grammar/Structure"]
                                   [?r :rel/from ?st] [?r :rel/label "law"] [?r :rel/to ?t]
                                   [?t :val/tag ?lt]] db)))
          "Structure.law targets the reified Law structure (composition)")
      (is (= #{"from" "to" "kind" "label" "order"}
             (set (d/q '[:find [?n ...]
                         :where [?c :structure/of :canvas.vocabulary.meta/Concept]
                                [?c :entity/name "Relation"]
                                [?r :rel/from ?c] [?r :rel/kind :slot] [?r :rel/to ?ms]
                                [?ms :val/name ?n]] db)))
          "the substrate Relation stays hand-modelled — below the registry"))))

(deftest metaslot-cardinality-law-catches-unknown-cardinality
  (testing "a MetaSlot whose cardinality is outside the known set is caught"
    (let [bad (a/assemble-vars [#'mc-T #'mc-X])]
      (is (contains? (set (map :law (s/check bad)))
                     "MetaSlot.cardinality value must satisfy [:enum \"one\" \"optional\" \"many\" \"some\" \"set\"]")))))

(deftest cross-module-ref-resolves
  (testing "the project Projection is realized by → the materialize module, via a SubjectRealization"
    (let [db (pipeline/build-model nil)]
      (is (seq (d/q '[:find ?m
                      :where [?f :structure/of :canvas.subject/Projection] [?f :entity/name "project"]
                             [?rz :structure/of :canvas.correspondence/SubjectRealization]
                             [?a :rel/from ?rz] [?a :rel/kind :realizes] [?a :rel/to ?f]
                             [?b :rel/from ?rz] [?b :rel/kind :by] [?b :rel/to ?m]
                             [?m :structure/of :lib.code/Module] [?m :entity/name "materialize"]]
                    db))
          "the cross-refs (ordinary var refs) resolved through the SubjectRealization node")
      (is (every? #(some? (:rel/to (d/entity db (first %))))
                  (d/q '[:find ?r :where [?r :rel/kind :by]] db))
          "every realizer relation has a resolved target — no dangling refs"))))

(deftest lenses-modelled-as-cross-cutting-focuses
  (testing "the lens view: each lens is a focus over the model (the old lenses + checks' aspects)"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Grouping) "lens"))
      (is (set/subset? #{"survey" "patterns" "consistency" "tar-pit" "integrity" "coverage" "drift"}
                       (names-of db :Lens))))))

(deftest findings-read-through-a-lens
  (testing "the finding view: a Finding reads the model THROUGH a lens (cross-module); inspect = gating"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Grouping) "probe"))
      (is (set/subset? #{"Survey" "Patterns" "IntegrityReport" "DriftReport"} (names-of db :Finding)))
      ;; lens ∘ reading composition: the Patterns finding reads through the patterns lens,
      ;; resolved cross-module to the lens node
      (is (seq (d/q '[:find ?l
                      :where [?f :structure/of :canvas.vocabulary.act/Finding] [?f :entity/name "Patterns"]
                             [?r :rel/from ?f] [?r :rel/kind :through] [?r :rel/to ?l]
                             [?l :structure/of :canvas.vocabulary.act/Lens] [?l :entity/name "patterns"]]
                    db))
          "the Patterns finding reads through the patterns lens")
      ;; inspect ⊂ reading — DriftReport is a reading whose finding GATES (a trust Signal)
      (is (seq (d/q '[:find ?f
                      :where [?f :structure/of :canvas.vocabulary.act/Finding] [?f :entity/name "DriftReport"]
                             [?f :val/gating true]]
                    db))
          "DriftReport is an inspect — a gating reading"))))

(deftest projection-subsystem-modelled-as-target-representations
  (testing "the projection view: model re-presented into targets through a lens + source→artifact mappings"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Grouping) "projection"))
      ;; Blueprint (code) + DriftClose (instructions — instruct ⊂ projection); more to come
      (is (set/subset? #{"Blueprint" "DriftClose"} (names-of db :Projection)))
      ;; a projection composes lens ∘ act too: Blueprint renders THROUGH the survey lens
      (is (seq (d/q '[:find ?l
                      :where [?p :structure/of :canvas.vocabulary.act/Projection] [?p :entity/name "Blueprint"]
                             [?r :rel/from ?p] [?r :rel/kind :through] [?r :rel/to ?l]
                             [?l :structure/of :canvas.vocabulary.act/Lens] [?l :entity/name "survey"]]
                    db))
          "Blueprint renders through the survey lens")
      ;; a projection is built from mappings (value-typed source→artifact pairs)
      (is (seq (d/q '[:find ?mp
                      :where [?p :structure/of :canvas.vocabulary.act/Projection] [?p :entity/name "Blueprint"]
                             [?r :rel/from ?p] [?r :rel/kind :maps] [?r :rel/to ?mp]
                             [?mp :structure/of :canvas.vocabulary.act/Mapping]
                             [?mp :val/from "a function"] [?mp :val/to "a defn"]]
                    db))
          "the Blueprint projection maps a function → a defn"))))

(deftest a-lens-is-reused-across-acts
  (testing "the payoff: ONE drift lens feeds BOTH the drift inspect (a Finding) AND the drift-close projection"
    (let [db (pipeline/build-model nil)]
      (is (= 1 (count (d/q '[:find ?l :where [?l :structure/of :canvas.vocabulary.act/Lens] [?l :entity/name "drift"]] db)))
          "there is exactly one drift lens node")
      (is (seq (d/q '[:find ?l
                      :where [?l :structure/of :canvas.vocabulary.act/Lens] [?l :entity/name "drift"]
                             ;; read by a Finding (the inspect) …
                             [?fd :structure/of :canvas.vocabulary.act/Finding] [?rp :rel/from ?fd] [?rp :rel/kind :through] [?rp :rel/to ?l]
                             ;; … AND rendered by a projection (drift-close)
                             [?pj :structure/of :canvas.vocabulary.act/Projection] [?rj :rel/from ?pj] [?rj :rel/kind :through] [?rj :rel/to ?l]]
                    db))
          "one drift focus, read by a finding and rendered by a projection"))))

(deftest projection-that-is-neither-base-nor-contextualization-is-caught
  (testing "a projection with neither mappings nor a contextualized base trips the flavour law"
    (let [db (a/assemble-vars [#'pe-Empty])]
      (is (contains? (set (map :law (s/check db)))
                     "a projection is a base (declares mappings) or a contextualization (frames another)")))))

(deftest findings-carry-realization-holds-predicates
  (testing "Patterns and IntegrityReport have a FindingCheck carrying an inline holds predicate"
    (let [db (pipeline/build-model nil)
          pred (fn [nm] (ffirst (d/q '[:find ?p :in $ ?n
                                       :where [?f :entity/name ?n] [?f :structure/of :canvas.vocabulary.act/Finding]
                                              [?r :rel/kind :realizes] [?r :rel/to ?f]
                                              [?r :rel/from ?fc] [?fc :val/pred ?p]]
                                     db nm)))]
      (is (seq? (pred "Patterns")) "Patterns' holds predicate is a stored form on its FindingCheck")
      (is (= 'fn (first (pred "Patterns"))) "it is a fn form")
      (is (seq? (pred "IntegrityReport")) "IntegrityReport's holds predicate is a stored form")
      (is (empty? (s/check db)) "the whole self-model still satisfies every law"))))

(deftest patterns-finding-carries-its-contract
  (testing "the Patterns finding declares a holds invariant in the real model"
    (let [db  (pipeline/build-model nil)
          fid (ffirst (d/q '[:find ?f
                             :where [?f :entity/name "Patterns"] [?f :structure/of :canvas.vocabulary.act/Finding]] db))]
      (is (some? (:val/holds (d/entity db fid))) "Patterns has a holds invariant")
      (is (empty? (s/check db)) "the whole self-model still satisfies every law"))))

(deftest integrity-finding-carries-its-contract
  (testing "the IntegrityReport finding declares a holds invariant in the real model"
    (let [db  (pipeline/build-model nil)
          fid (ffirst (d/q '[:find ?f
                             :where [?f :entity/name "IntegrityReport"]
                                    [?f :structure/of :canvas.vocabulary.act/Finding]] db))]
      (is (some? (:val/holds (d/entity db fid)))
          "IntegrityReport has a holds invariant")
      (is (empty? (s/check db))
          "the whole self-model still satisfies every law"))))
