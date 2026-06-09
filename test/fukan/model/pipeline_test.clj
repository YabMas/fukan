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
            [canvas.vocabulary.act :refer [Lens Probe Finding Projection]]))

;; tags are ns-qualified; tests pass a short handle and match by its name
(defn- names-of [db tag]
  (->> (d/q '[:find ?n ?t :where [?e :structure/of ?t] [?e :entity/name ?n]] db)
       (filter (fn [[_ t]] (= (name tag) (name t)))) (map first) set))

;; ── ad-hoc dbs built from top-level value defs (assembled per test) ──────────

;; a MetaSlot with an unknown cardinality
(def mc-T (Concept "T"))
(def mc-X (Concept "X" (slot (MetaSlot (name "f") (cardinality "lots") (of mc-T)))))

;; a finding yielded by no probe
(def of2-l      (Lens "l" (focus "things")))
(def of2-Used   (Finding "Used" (gating false)))
(def of2-Orphan (Finding "Orphan" (gating false)))
(def of2-x      (Probe "x" (through of2-l) (yields of2-Used)))

;; a projection that is neither a base nor a contextualization
(def pe-Empty (Projection "Empty"))

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
      ;; the seams: build-model's cross-module :calls resolve to the canvas-source
      ;; ingest/union stages and to the target extractor (design + code unified)
      (is (= #{"build" "union-dbs"}
             (set (d/q '[:find [?bn ...]
                         :where [?mp :entity/name "model-pipeline"]
                                [?cm :rel/kind :exposes] [?cm :rel/from ?mp] [?cm :rel/to ?bm]
                                [?bm :entity/name "build-model"]
                                [?r :rel/from ?bm] [?r :rel/kind :calls] [?r :rel/to ?b]
                                [?cs :entity/name "canvas-source"]
                                [?cc :rel/kind :exposes] [?cc :rel/from ?cs] [?cc :rel/to ?b]
                                [?b :entity/name ?bn]]
                       db)))
          "build-model calls canvas-source's build + union-dbs (its exposed API)")
      (is (= ["run-extractor"]
             (d/q '[:find [?en ...]
                    :where [?mp :entity/name "model-pipeline"]
                           [?cm :rel/kind :exposes] [?cm :rel/from ?mp] [?cm :rel/to ?bm]
                           [?bm :entity/name "build-model"]
                           [?r :rel/from ?bm] [?r :rel/kind :calls] [?r :rel/to ?e]
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

(deftest kernel-meta-model-captures-structure-composition
  (testing "the reflexive kernel model: Structure is composed of Slot and Law"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Module) "core-structure"))
      (is (= #{"slot" "law" "value"}
             (set (map first (d/q '[:find ?n
                                    :where [?st :structure/of :canvas.vocabulary.meta/Concept] [?st :entity/name "Structure"]
                                           [?r :rel/from ?st] [?r :rel/kind :slot] [?r :rel/to ?ms]
                                           [?ms :val/name ?n]] db))))
          "Structure's MetaSlots are slot, law, value")
      (is (seq (d/q '[:find ?slot-c
                      :where [?st :structure/of :canvas.vocabulary.meta/Concept] [?st :entity/name "Structure"]
                             [?r :rel/from ?st] [?r :rel/kind :slot] [?r :rel/to ?ms]
                             [?ms :val/name "slot"]
                             [?o :rel/from ?ms] [?o :rel/kind :of] [?o :rel/to ?slot-c]
                             [?slot-c :entity/name "Slot"]] db))
          "Structure.slot targets the Slot concept (composition)"))))

(deftest metaslot-cardinality-law-catches-unknown-cardinality
  (testing "a MetaSlot whose cardinality is outside the known set is caught"
    (let [bad (a/assemble-vars [#'mc-T #'mc-X])]
      (is (contains? (set (map :law (s/check bad)))
                     "a slot's cardinality is one of the known cardinalities")))))

(deftest cross-module-ref-resolves
  (testing "the project Act is realized by → the materialize module, via a SubjectRealization"
    (let [db (pipeline/build-model nil)]
      (is (seq (d/q '[:find ?m
                      :where [?f :structure/of :canvas.vocabulary.subject/Act] [?f :entity/name "project"]
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

(deftest probe-acts-read-through-a-lens-yielding-findings
  (testing "the probe view: a probe reads the model THROUGH a lens (cross-module) → a finding; inspect = gating"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Grouping) "probe"))
      (is (set/subset? #{"survey" "patterns" "integrity" "drift"} (names-of db :Probe)))
      ;; lens ∘ act composition: the patterns probe reads through the patterns lens,
      ;; resolved cross-module to the lens node
      (is (seq (d/q '[:find ?l
                      :where [?p :structure/of :canvas.vocabulary.act/Probe] [?p :entity/name "patterns"]
                             [?r :rel/from ?p] [?r :rel/kind :through] [?r :rel/to ?l]
                             [?l :structure/of :canvas.vocabulary.act/Lens] [?l :entity/name "patterns"]]
                    db))
          "the patterns probe reads through the patterns lens")
      ;; inspect ⊂ probe — drift is a probe whose finding GATES (a trust Signal)
      (is (seq (d/q '[:find ?f
                      :where [?p :structure/of :canvas.vocabulary.act/Probe] [?p :entity/name "drift"]
                             [?r :rel/from ?p] [?r :rel/kind :yields] [?r :rel/to ?f]
                             [?f :structure/of :canvas.vocabulary.act/Finding] [?f :val/gating true]]
                    db))
          "drift is an inspect — a probe whose finding gates"))))

(deftest orphan-finding-is-caught
  (testing "a finding yielded by no probe trips the probe law"
    (let [db (a/assemble-vars [#'of2-l #'of2-Used #'of2-Orphan #'of2-x])]
      (is (contains? (set (map :law (s/check db))) "every finding is yielded by some probe")))))

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
  (testing "the payoff: ONE drift lens feeds BOTH the drift inspect-probe AND the drift-close projection"
    (let [db (pipeline/build-model nil)]
      (is (= 1 (count (d/q '[:find ?l :where [?l :structure/of :canvas.vocabulary.act/Lens] [?l :entity/name "drift"]] db)))
          "there is exactly one drift lens node")
      (is (seq (d/q '[:find ?l
                      :where [?l :structure/of :canvas.vocabulary.act/Lens] [?l :entity/name "drift"]
                             ;; consumed by a probe (the inspect) …
                             [?pr :structure/of :canvas.vocabulary.act/Probe] [?rp :rel/from ?pr] [?rp :rel/kind :through] [?rp :rel/to ?l]
                             ;; … AND by a projection (drift-close)
                             [?pj :structure/of :canvas.vocabulary.act/Projection] [?rj :rel/from ?pj] [?rj :rel/kind :through] [?rj :rel/to ?l]]
                    db))
          "one drift focus, composed with two different acts"))))

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
