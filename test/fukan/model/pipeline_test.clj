(ns fukan.model.pipeline-test
  "Smoke test for the lean-kernel pipeline (decision (ii)): build-model ingests
   the defstructure canvas specs into one structure substrate db, which is the
   model. (Successor to the deleted smoke / roundtrip tests of the old map
   pipeline + Phase-6 analyzer.)"
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.structure :as s]
            [fukan.model.pipeline :as pipeline]
            [fukan.canvas.projection.canvas-source :as canvas-source]
            ;; the kernel self-model's schema vocab (MetaSlot authored inline → not referred)
            [canvas.vocab.meta :refer [Concept]]
            [canvas.vocab.arch :refer [Faculty]]
            [canvas.vocab.lens :refer [Lens]]
            [canvas.vocab.probe :refer [Probe Finding]]
            [canvas.vocab.projection :refer [Projection]]
            [canvas.vocab.agent :refer [Tool]]
            [canvas.vocab.collab :refer [Phase]]))

(defn- names-of [db tag]
  (set (map first (d/q '[:find ?n :in $ ?t
                         :where [?e :structure/of ?t] [?e :entity/name ?n]]
                       db tag))))

(deftest build-model-ingests-canvas-specs-into-a-structure-db
  (testing "the model is the merged structure db over the canvas/ defstructure specs"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Module) "infra.model")
          "the canvas/infra/model spec is discovered and ingested")
      ;; infra is now modelled with the fukan-on-fukan grammar (Stage/Kind), not
      ;; the (evicted) base Function/Type vocab; subset since other specs add more
      (is (set/subset? #{"load-model" "get-model" "refresh-model"} (names-of db :Stage)))
      (is (set/subset? #{"Model" "Src"} (names-of db :Kind)))
      (is (empty? (s/check db))
          "the whole self-model satisfies every structure's laws"))))

(deftest pipeline-links-across-to-canvas-source
  (testing "build-model is a thin entry point that calls across to canvas-source/build"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Module) "model.pipeline")
          "the canvas/pipeline/model spec is ingested")
      ;; post-prune the pipeline subsystem is just build-model — the merge/ingest
      ;; machinery lives in canvas-source now, not duplicated here
      (is (= ["build-model"]
             (d/q '[:find [?n ...]
                    :where [?m :entity/name "model.pipeline"] [?m :module/child ?c]
                           [?c :structure/of :Stage] [?c :entity/name ?n]]
                  db))
          "model.pipeline declares exactly one stage — no stale duplicate merge/ingest")
      ;; the seam: build-model's cross-module :calls resolves to canvas-source/build
      (is (= ["build"]
             (d/q '[:find [?bn ...]
                    :where [?mp :entity/name "model.pipeline"] [?mp :module/child ?bm]
                           [?bm :entity/name "build-model"]
                           [?r :rel/from ?bm] [?r :rel/kind :calls] [?r :rel/to ?b]
                           [?cs :entity/name "canvas-source"] [?cs :module/child ?b]
                           [?b :entity/name ?bn]]
                  db))
          "build-model calls canvas-source/build — the cross-module link resolves post-merge"))))

(deftest canvas-source-model-shares-the-db-shape
  (testing "in the canvas_source self-model, the Db type-shape (4 uses) is one node"
    (let [db (pipeline/build-model nil)]
      (is (= 1 (count (d/q '[:find ?s
                             :where [?s :structure/of :Shape] [?s :val/kind "type"]
                                    [?r :rel/from ?s] [?r :rel/kind :type]
                                    [?r :rel/to ?t] [?t :entity/name "Db"]
                                    [?m :entity/name "canvas-source"] [?m :module/child ?t]]
                           db)))
          "Db appears in db->entity-maps in, merge-dbs in (nested) + out, build out → one node"))))

(deftest canvas-source-effects-are-captured-and-value-identified
  (testing "Stage effects are recorded; :io (performed by 4 stages) is one shared node"
    (let [db (pipeline/build-model nil)]
      (is (= 1 (count (d/q '[:find ?e :where [?e :structure/of :Effect] [?e :val/name "io"]] db)))
          "the :io effect is value-identified across every stage that performs it")
      (is (seq (d/q '[:find ?r
                      :where [?b :structure/of :Stage] [?b :entity/name "build"]
                             [?r :rel/from ?b] [?r :rel/kind :performs] [?r :rel/to ?e]
                             [?e :structure/of :Effect] [?e :val/name "io"]]
                    db))
          "build performs :io"))))

(deftest kernel-meta-model-captures-structure-composition
  (testing "the reflexive kernel model: Structure is composed of Slot and Law"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Module) "core.structure"))
      (is (= #{"slot" "law" "value"}
             (set (map first (d/q '[:find ?n
                                    :where [?st :structure/of :Concept] [?st :entity/name "Structure"]
                                           [?r :rel/from ?st] [?r :rel/kind :slot] [?r :rel/to ?ms]
                                           [?ms :val/name ?n]] db))))
          "Structure's MetaSlots are slot, law, value")
      (is (seq (d/q '[:find ?slot-c
                      :where [?st :structure/of :Concept] [?st :entity/name "Structure"]
                             [?r :rel/from ?st] [?r :rel/kind :slot] [?r :rel/to ?ms]
                             [?ms :val/name "slot"]
                             [?o :rel/from ?ms] [?o :rel/kind :of] [?o :rel/to ?slot-c]
                             [?slot-c :entity/name "Slot"]] db))
          "Structure.slot targets the Slot concept (composition)"))))

(deftest metaslot-cardinality-law-catches-unknown-cardinality
  (testing "a MetaSlot whose cardinality is outside the known set is caught"
    (let [bad (s/with-structures
                (s/within-module "k"
                  (Concept "T")
                  (Concept "X" (slot (MetaSlot (name "f") (cardinality "lots") (of T))))))]
      (is (contains? (set (map :law (s/check bad)))
                     "a slot's cardinality is one of the known cardinalities")))))

(deftest overview-model-makes-the-model-the-hub
  (testing "the top-level overview: every faculty connects to the Model, which is the hub"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Module) "fukan"))
      ;; the Model faculty has the most flow connections (everything orbits it)
      (is (<= 6 (count (d/q '[:find ?r
                              :where [?m :structure/of :Faculty] [?m :entity/name "Model"]
                                     (or [?r :rel/to ?m] [?r :rel/from ?m])]
                            db)))
          "many faculties feed or read the Model"))))

(deftest orphan-faculty-is-caught
  (testing "a faculty with no flow edges trips the no-isolated-faculty law"
    (let [db (s/with-structures
               (s/within-module "f"
                 (Faculty "Model")
                 (Faculty "Reader" (reads Model))   ; Model gets an incoming edge; Reader an outgoing
                 (Faculty "Loner")))]               ; connects to nothing
      (is (contains? (set (map :law (s/check db))) "no faculty is isolated")))))

(deftest model-reading-faculty-without-realization-is-caught
  (testing "a faculty that reads the Model but names no realizing module trips the realized-by law"
    (let [db (s/with-structures
               (s/within-module "f"
                 (Faculty "Model")
                 (Faculty "Realized" (reads Model) (realized-by (across "some-module"))) ; reads + claims realization → ok
                 (Faculty "Bare" (reads Model))))                                         ; reads, no realization → caught
          flagged (->> (s/check db)
                       (filter #(= "a model-reading faculty is realized by a module" (:law %)))
                       (mapcat :offenders) (map first) set)
          eid (fn [n] (ffirst (d/q '[:find ?f :in $ ?n :where [?f :entity/name ?n]] db n)))]
      (is (contains? flagged (eid "Bare"))
          "a model-reading faculty with no realized-by is flagged")
      (is (not (contains? flagged (eid "Realized")))
          "a model-reading faculty that claims realization is not flagged"))))

(deftest the-self-model-satisfies-the-realized-by-law
  (testing "every model-reading faculty in the real overview is backed by a realizing module"
    (let [db (pipeline/build-model nil)]
      (is (not (contains? (set (map :law (s/check db)))
                          "a model-reading faculty is realized by a module"))
          "Lens/Probe/Projection/Agent each name a realizing module"))))

(deftest cross-module-ref-resolves-post-merge
  (testing "overview's Structure faculty is realized-by → the core.structure module node"
    (let [db (pipeline/build-model nil)]
      (is (seq (d/q '[:find ?m
                      :where [?f :structure/of :Faculty] [?f :entity/name "Structure"]
                             [?r :rel/from ?f] [?r :rel/kind :realized-by] [?r :rel/to ?m]
                             [?m :structure/of :Module] [?m :entity/name "core.structure"]]
                    db))
          "the cross-ref resolved to the core.structure :Module node")
      (is (empty? (d/q '[:find ?r :where [?r :rel/to-ref _] (not [?r :rel/to _])] db))
          "every cross-ref in the merged model is resolved"))))

(deftest unresolved-cross-ref-throws
  (testing "a cross-ref to a non-existent module/node throws at resolution"
    (let [db (d/db-with (s/create) [{:entity/id "r1" :rel/to-ref ["nope"]}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unresolved cross-module reference"
                            (canvas-source/resolve-cross-refs db))))))

(deftest lenses-modelled-as-cross-cutting-focuses
  (testing "the lens view: each lens is a focus over the model (the old lenses + checks' aspects)"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Module) "lens"))
      (is (set/subset? #{"survey" "patterns" "consistency" "tar-pit" "integrity" "coverage" "drift"}
                       (names-of db :Lens))))))

(deftest probe-acts-read-through-a-lens-yielding-findings
  (testing "the probe view: a probe reads the model THROUGH a lens (cross-module) → a finding; inspect = gating"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Module) "probe"))
      (is (set/subset? #{"survey" "patterns" "integrity" "drift"} (names-of db :Probe)))
      ;; lens ∘ act composition: the patterns probe reads through the patterns lens,
      ;; resolved cross-module to the lens node
      (is (seq (d/q '[:find ?l
                      :where [?p :structure/of :Probe] [?p :entity/name "patterns"]
                             [?r :rel/from ?p] [?r :rel/kind :through] [?r :rel/to ?l]
                             [?l :structure/of :Lens] [?l :entity/name "patterns"]]
                    db))
          "the patterns probe reads through the patterns lens")
      ;; inspect ⊂ probe — drift is a probe whose finding GATES (a trust Signal)
      (is (seq (d/q '[:find ?f
                      :where [?p :structure/of :Probe] [?p :entity/name "drift"]
                             [?r :rel/from ?p] [?r :rel/kind :yields] [?r :rel/to ?f]
                             [?f :structure/of :Finding] [?f :val/gating true]]
                    db))
          "drift is an inspect — a probe whose finding gates"))))

(deftest overview-lens-and-probe-faculties-interlock-with-their-views
  (testing "the top-level Lens and Probe faculties are realized-by their modules (cross-ref interlock)"
    (let [db (pipeline/build-model nil)]
      (is (seq (d/q '[:find ?m
                      :where [?f :structure/of :Faculty] [?f :entity/name "Lens"]
                             [?r :rel/from ?f] [?r :rel/kind :realized-by] [?r :rel/to ?m]
                             [?m :structure/of :Module] [?m :entity/name "lens"]]
                    db))
          "the Lens faculty links to the lens view")
      (is (seq (d/q '[:find ?m
                      :where [?f :structure/of :Faculty] [?f :entity/name "Probe"]
                             [?r :rel/from ?f] [?r :rel/kind :realized-by] [?r :rel/to ?m]
                             [?m :structure/of :Module] [?m :entity/name "probe"]]
                    db))
          "the Probe faculty links to the probe view"))))

(deftest orphan-finding-is-caught
  (testing "a finding yielded by no probe trips the probe law"
    (let [db (s/with-structures
               (s/within-module "p"
                 (Lens "l" (focus "things"))
                 (Finding "Used"   (gating false))
                 (Finding "Orphan" (gating false))
                 (Probe "x" (through l) (yields Used))))]
      (is (contains? (set (map :law (s/check db))) "every finding is yielded by some probe")))))

(deftest projection-subsystem-modelled-as-target-representations
  (testing "the projection view: model re-presented into targets through a lens + source→artifact mappings"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Module) "projection"))
      ;; Blueprint (code) + DriftClose (instructions — instruct ⊂ projection); more to come
      (is (set/subset? #{"Blueprint" "DriftClose"} (names-of db :Projection)))
      ;; a projection composes lens ∘ act too: Blueprint renders THROUGH the survey lens
      (is (seq (d/q '[:find ?l
                      :where [?p :structure/of :Projection] [?p :entity/name "Blueprint"]
                             [?r :rel/from ?p] [?r :rel/kind :through] [?r :rel/to ?l]
                             [?l :structure/of :Lens] [?l :entity/name "survey"]]
                    db))
          "Blueprint renders through the survey lens")
      ;; a projection is built from mappings (value-typed source→artifact pairs)
      (is (seq (d/q '[:find ?mp
                      :where [?p :structure/of :Projection] [?p :entity/name "Blueprint"]
                             [?r :rel/from ?p] [?r :rel/kind :maps] [?r :rel/to ?mp]
                             [?mp :structure/of :Mapping]
                             [?mp :val/from "a function"] [?mp :val/to "a defn"]]
                    db))
          "the Blueprint projection maps a function → a defn")
      (is (seq (d/q '[:find ?m
                      :where [?f :structure/of :Faculty] [?f :entity/name "Projection"]
                             [?r :rel/from ?f] [?r :rel/kind :realized-by] [?r :rel/to ?m]
                             [?m :structure/of :Module] [?m :entity/name "projection"]]
                    db))
          "the Projection faculty interlocks with the projection view"))))

(deftest a-lens-is-reused-across-acts
  (testing "the payoff: ONE drift lens feeds BOTH the drift inspect-probe AND the drift-close projection"
    (let [db (pipeline/build-model nil)]
      (is (= 1 (count (d/q '[:find ?l :where [?l :structure/of :Lens] [?l :entity/name "drift"]] db)))
          "there is exactly one drift lens node")
      (is (seq (d/q '[:find ?l
                      :where [?l :structure/of :Lens] [?l :entity/name "drift"]
                             ;; consumed by a probe (the inspect) …
                             [?pr :structure/of :Probe] [?rp :rel/from ?pr] [?rp :rel/kind :through] [?rp :rel/to ?l]
                             ;; … AND by a projection (drift-close)
                             [?pj :structure/of :Projection] [?rj :rel/from ?pj] [?rj :rel/kind :through] [?rj :rel/to ?l]]
                    db))
          "one drift focus, composed with two different acts"))))

(deftest projection-with-no-mappings-is-caught
  (testing "a projection that maps nothing trips the at-least-one-mapping law"
    (let [db (s/with-structures
               (s/within-module "p"
                 (Projection "Empty")))]
      (is (contains? (set (map :law (s/check db)))
                     "Projection.maps requires at least one (found none)")))))

(deftest agent-composes-its-own-tools-from-primitives
  (testing "the agent view: each Tool is composed from primitive lenses+acts — bundling or chaining"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Module) "agent"))
      (is (set/subset? #{"health-audit" "tangle-diagnosis" "audit-and-close" "contract-scaffold"}
                       (names-of db :Tool)))
      ;; a BUNDLE: health-audit probes three focuses at once — no single faculty act does
      (is (= 3 (count (d/q '[:find ?l
                             :where [?t :structure/of :Tool] [?t :entity/name "health-audit"]
                                    [?r :rel/from ?t] [?r :rel/kind :probes] [?r :rel/to ?l]]
                           db)))
          "health-audit bundles three probe-focuses into one tool")
      ;; a CHAIN: audit-and-close probes a focus AND projects through one
      (is (seq (d/q '[:find ?t
                      :where [?t :structure/of :Tool] [?t :entity/name "audit-and-close"]
                             [?rp :rel/from ?t] [?rp :rel/kind :probes]   [?rp :rel/to _]
                             [?rj :rel/from ?t] [?rj :rel/kind :projects] [?rj :rel/to _]]
                    db))
          "audit-and-close chains a probe and a projection")
      (is (seq (d/q '[:find ?m
                      :where [?f :structure/of :Faculty] [?f :entity/name "Agent"]
                             [?r :rel/from ?f] [?r :rel/kind :realized-by] [?r :rel/to ?m]
                             [?m :structure/of :Module] [?m :entity/name "agent"]]
                    db))
          "the Agent faculty interlocks with the agent view"))))

(deftest one-lens-feeds-probe-projection-and-composed-tools
  (testing "capstone: the single drift lens is used by an inspect-probe, a projection, AND a composed tool"
    (let [db (pipeline/build-model nil)]
      (is (= 1 (count (d/q '[:find ?l :where [?l :structure/of :Lens] [?l :entity/name "drift"]] db)))
          "there is exactly one drift lens node")
      (is (seq (d/q '[:find ?t
                      :where [?l :structure/of :Lens] [?l :entity/name "drift"]
                             [?t :structure/of :Tool] [?t :entity/name "audit-and-close"]
                             [?r :rel/from ?t] [?r :rel/to ?l]]
                    db))
          "the audit-and-close tool composes the same drift lens the inspect-probe + projection use"))))

(deftest collaboration-loop-modelled-as-a-closed-cycle
  (testing "the collab view: phases form a closed OODA cycle, each (mostly) exercising a faculty"
    (let [db (pipeline/build-model nil)]
      (is (contains? (names-of db :Module) "collab"))
      (is (set/subset? #{"Intend" "Focus" "Observe" "Reason" "Apply" "Reinspect"}
                       (names-of db :Phase)))
      ;; a phase exercises a faculty cross-module: observe → the Probe faculty (interlock)
      (is (seq (d/q '[:find ?fac
                      :where [?p :structure/of :Phase] [?p :entity/name "Observe"]
                             [?r :rel/from ?p] [?r :rel/kind :via] [?r :rel/to ?fac]
                             [?fac :structure/of :Faculty] [?fac :entity/name "Probe"]]
                    db))
          "the observe phase exercises the Probe faculty (loop interlocks with the overview)")
      ;; the cycle closes: Reinspect flows back to Intend
      (is (seq (d/q '[:find ?i
                      :where [?p :structure/of :Phase] [?p :entity/name "Reinspect"]
                             [?r :rel/from ?p] [?r :rel/kind :next] [?r :rel/to ?i]
                             [?i :entity/name "Intend"]]
                    db))
          "the loop closes — reinspect flows back to intend")
      ;; and the build is clean — the loop-closes law is satisfied (no dangling phase)
      (is (empty? (s/check db)) "the whole self-model — loop included — satisfies every law"))))

(deftest dangling-phase-is-caught
  (testing "a phase reached by no other phase trips the loop-closes law"
    (let [db (s/with-structures
               (s/within-module "c"
                 (Phase "A" (next B))
                 (Phase "B" (next A))
                 (Phase "Dangling" (next A))))]   ; Dangling is no phase's :next
      (is (contains? (set (map :law (s/check db))) "the loop closes — every phase is reached")))))

(deftest findings-carry-inline-holds-predicates
  (testing "Patterns and IntegrityReport carry an inline holds predicate form in the model"
    (let [db (pipeline/build-model nil)
          pred (fn [nm] (:val/holds-pred
                          (d/entity db (ffirst (d/q '[:find ?f :in $ ?n
                                                      :where [?f :entity/name ?n] [?f :structure/of :Finding]]
                                                    db nm)))))]
      (is (seq? (pred "Patterns")) "Patterns' holds predicate is a stored form")
      (is (= 'fn (first (pred "Patterns"))) "it is a fn form")
      (is (seq? (pred "IntegrityReport")) "IntegrityReport's holds predicate is a stored form")
      (is (empty? (s/check db)) "the whole self-model still satisfies every law"))))

(deftest empty-tool-is-caught
  (testing "a tool that composes no primitive (neither probes nor projects) trips the law"
    (let [db (s/with-structures
               (s/within-module "a"
                 (Tool "empty" (answers "?"))))]
      (is (contains? (set (map :law (s/check db)))
                     "a tool composes at least one primitive")))))

(deftest patterns-finding-carries-its-contract
  (testing "the Patterns finding declares a shape + holds in the real model"
    (let [db  (pipeline/build-model nil)
          fid (ffirst (d/q '[:find ?f
                             :where [?f :entity/name "Patterns"] [?f :structure/of :Finding]] db))]
      (is (some? (:val/holds (d/entity db fid))) "Patterns has a holds invariant")
      (is (seq (d/q '[:find ?sh :in $ ?f
                      :where [?r :rel/from ?f] [?r :rel/kind :shape] [?r :rel/to ?sh]] db fid))
          "Patterns has a shape")
      (is (empty? (s/check db)) "the whole self-model still satisfies every law"))))

(deftest integrity-finding-carries-its-contract
  (testing "the IntegrityReport finding declares a shape + holds in the real model"
    (let [db  (pipeline/build-model nil)
          fid (ffirst (d/q '[:find ?f
                             :where [?f :entity/name "IntegrityReport"]
                                    [?f :structure/of :Finding]] db))]
      (is (some? (:val/holds (d/entity db fid)))
          "IntegrityReport has a holds invariant")
      (is (seq (d/q '[:find ?sh :in $ ?f
                      :where [?r :rel/from ?f] [?r :rel/kind :shape] [?r :rel/to ?sh]] db fid))
          "IntegrityReport has a shape")
      (is (empty? (s/check db))
          "the whole self-model still satisfies every law"))))
