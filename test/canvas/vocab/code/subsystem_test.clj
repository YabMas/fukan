(ns canvas.vocab.code.subsystem-test
  "The opt-in clean-architecture quality layer: the module-dependency graph is acyclic."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.structure :as s]
            ;; the composition root — registers fukan's Clojure FACT extractor (so `build-model "src"`
            ;; merges extracted code onto the design graph) AND loads the Cozo check engine for s/check
            [fukan.infra.model]
            [fukan.model.pipeline :as pipeline]
            [canvas.vocab.code.operation :as operation]
            [canvas.vocab.code.module :as module]
            [canvas.vocab.code.subsystem :as subsystem]))

(defn- law-desc [substr]
  (->> (s/structure-by-tag :canvas.vocab.code.subsystem/ModuleArchitecture) :laws
       (map :desc) (filter #(str/includes? % substr)) first))

(defn- offenders [db substr]
  (let [desc (law-desc substr)]
    (->> (s/check db) (filter #(= desc (:law %)))
         (mapcat :offenders) (map first) (map #(:entity/name (cq/entity db %))) set)))

;; a synthetic mutual pair: A's op delegates to B's op and B's op delegates to A's op
(declare t-mb-op)
(operation/Operation ^{:name "ma-op"} t-ma-op {:delegates [t-mb-op]})
(operation/Operation ^{:name "mb-op"} t-mb-op {:delegates [t-ma-op]})
(module/Module ^{:name "MA"} t-mod-ma {:exposes [t-ma-op]})
(module/Module ^{:name "MB"} t-mod-mb {:exposes [t-mb-op]})

(deftest module-acyclicity-fires-on-a-mutual-pair
  (testing "two modules whose ops mutually delegate (a 2-cycle) violate the acyclicity law"
    (let [db (build/vars->cozo [#'t-ma-op #'t-mb-op #'t-mod-ma #'t-mod-mb])]
      (is (= #{"MA" "MB"} (offenders db "module transitively"))))))

;; a synthetic 3-cycle A→B→C→A (each op delegates to the next module's op): NO direct mutual pair,
;; so the old 2-cycle check saw nothing — the transitive SCC law catches all three.
(declare t3-b-op t3-c-op)
(operation/Operation ^{:name "t3a-op"} t3-a-op {:delegates [t3-b-op]})
(operation/Operation ^{:name "t3b-op"} t3-b-op {:delegates [t3-c-op]})
(operation/Operation ^{:name "t3c-op"} t3-c-op {:delegates [t3-a-op]})
(module/Module ^{:name "T3A"} t3-mod-a {:exposes [t3-a-op]})
(module/Module ^{:name "T3B"} t3-mod-b {:exposes [t3-b-op]})
(module/Module ^{:name "T3C"} t3-mod-c {:exposes [t3-c-op]})

(deftest module-acyclicity-fires-on-a-transitive-cycle
  (testing "a 3-module cycle T3A→T3B→T3C→T3A — no direct mutual pair, so the OLD 2-cycle check
            missed it; the SCC law flags all three (each transitively depends on itself)"
    (let [db (build/vars->cozo [#'t3-a-op #'t3-b-op #'t3-c-op #'t3-mod-a #'t3-mod-b #'t3-mod-c])]
      (is (= #{"T3A" "T3B" "T3C"} (offenders db "module transitively"))))))

(deftest fukan-module-graph-is-acyclic
  (testing "fukan's own module graph is acyclic — no transitive cycle, the quality law is a green opt-in"
    (is (empty? (offenders (pipeline/build-model nil) "module transitively")))))

;; ── conformance fixtures: S's op delegates to T's op (cross-subsystem) ──
(operation/Operation ^{:name "op-t"} t-op-t "callee in T")
(operation/Operation ^{:name "op-s"} t-op-s {:delegates [t-op-t]})
(module/Module ^{:name "M-S"} t-cm-s {:exposes [t-op-s]})
(module/Module ^{:name "M-T"} t-cm-t {:exposes [t-op-t]})
(declare t-sub-T)
(subsystem/Subsystem ^{:name "S-ok"}  t-sub-S-ok  {:child [t-cm-s] :may-depend [t-sub-T]})  ; declares the dep
(subsystem/Subsystem ^{:name "S-bad"} t-sub-S-bad {:child [t-cm-s]})                          ; does NOT
(subsystem/Subsystem ^{:name "T"}     t-sub-T     {:child [t-cm-t]})

(deftest conformance-green-when-cross-dep-is-declared
  (testing "M-S → M-T conforms because subsystem S-ok declares :may-depend T"
    (let [db (build/vars->cozo [#'t-op-t #'t-op-s #'t-cm-s #'t-cm-t #'t-sub-S-ok #'t-sub-T])]
      (is (empty? (offenders db "cross-subsystem"))))))

(deftest conformance-fires-on-undeclared-cross-dep
  (testing "M-S → M-T violates because S-bad does NOT declare :may-depend T"
    (let [db (build/vars->cozo [#'t-op-t #'t-op-s #'t-cm-s #'t-cm-t #'t-sub-S-bad #'t-sub-T])]
      (is (= #{"M-S"} (offenders db "cross-subsystem"))))))

;; ── acyclicity fixtures: a 2-cycle in :may-depend ──
(declare t-sub-cy-b)
(subsystem/Subsystem ^{:name "CY-A"} t-sub-cy-a {:may-depend [t-sub-cy-b]})
(subsystem/Subsystem ^{:name "CY-B"} t-sub-cy-b {:may-depend [t-sub-cy-a]})

(deftest may-depend-acyclicity-fires-on-a-cycle
  (testing "a :may-depend cycle CY-A ⇄ CY-B violates the acyclicity law"
    (let [db (build/vars->cozo [#'t-sub-cy-a #'t-sub-cy-b])]
      (is (= #{"CY-A" "CY-B"} (offenders db "subsystem transitively"))))))

(deftest fukan-may-depend-graph-is-acyclic
  (testing "fukan's declared :may-depend DAG is acyclic"
    (is (empty? (offenders (pipeline/build-model nil) "subsystem transitively")))))

;; ── membership fixtures: a module in no subsystem (with a subsystem present) ──
(module/Module ^{:name "orphan"} t-orphan "a module in no subsystem")
(module/Module ^{:name "homed"}  t-homed  "a module in a subsystem")
(module/Module t-ext-orphan {:extracted true})   ; an extracted code-fact module, in no subsystem
(subsystem/Subsystem ^{:name "home"} t-sub-home {:child [t-homed]})

(deftest membership-ignores-extracted-modules
  (testing "an extracted (code-fact) module in no subsystem is NOT a membership offender"
    (let [db (build/vars->cozo [#'t-ext-orphan #'t-homed #'t-sub-home])]
      (is (empty? (offenders db "belongs to a Subsystem"))
          "design-membership is for authored modules; extracted modules are out of scope"))))

(deftest membership-fires-on-unclustered-module
  (testing "with a Subsystem present, a Module in none is an offender"
    (let [db (build/vars->cozo [#'t-orphan #'t-homed #'t-sub-home])]
      (is (= #{"orphan"} (offenders db "belongs to a Subsystem"))))))

(deftest membership-vacuous-without-subsystems
  (testing "no Subsystem modelled → the membership law is vacuous (guard)"
    (let [db (build/vars->cozo [#'t-orphan])]
      (is (empty? (offenders db "belongs to a Subsystem"))))))

(deftest fukan-every-module-is-clustered
  (testing "every fukan Module belongs to a subsystem"
    (is (empty? (offenders (pipeline/build-model nil) "belongs to a Subsystem")))))

;; ── latent-boundaries (interface-segregation discovery) ──
;; Synthetic EXTRACTED graphs: HOST exposes public ops; consumer modules :call them. The reading
;; partitions HOST's public surface by shared-clientele and reports ≥2-op consumer-disjoint bundles.

;; HOST: a1,a2 captured by CX; b1,b2 captured by CY — two disjoint clienteles.
(operation/Operation ^{:name "a1"} t-a1 {:extracted true})
(operation/Operation ^{:name "a2"} t-a2 {:extracted true})
(operation/Operation ^{:name "b1"} t-b1 {:extracted true})
(operation/Operation ^{:name "b2"} t-b2 {:extracted true})
(module/Module ^{:name "HOST"} t-host {:exposes [t-a1 t-a2 t-b1 t-b2]})
(operation/Operation ^{:name "cx-op"} t-cx-op {:extracted true :calls [t-a1 t-a2]})
(module/Module ^{:name "CX"} t-cx {:exposes [t-cx-op]})
(operation/Operation ^{:name "cy-op"} t-cy-op {:extracted true :calls [t-b1 t-b2]})
(module/Module ^{:name "CY"} t-cy {:exposes [t-cy-op]})

(deftest latent-boundary-fires-on-disjoint-clienteles
  (testing "a module whose public ops split into two consumer-disjoint bundles is surfaced"
    (let [db (build/vars->cozo [#'t-a1 #'t-a2 #'t-b1 #'t-b2 #'t-host
                               #'t-cx-op #'t-cx #'t-cy-op #'t-cy])
          lb (subsystem/latent-boundaries db)
          bundles (->> (get lb "HOST") (map (comp set :ops)) set)]
      (is (= #{"HOST"} (set (keys lb))) "only HOST has a split surface (consumers have no clientele)")
      (is (= #{#{"a1" "a2"} #{"b1" "b2"}} bundles)
          "the two disjoint clienteles are recovered as the sub-interfaces"))))

;; COH: c1,c2 BOTH captured by CZ — one shared clientele, no internal seam.
(operation/Operation ^{:name "c1"} t-c1 {:extracted true})
(operation/Operation ^{:name "c2"} t-c2 {:extracted true})
(module/Module ^{:name "COH"} t-coh {:exposes [t-c1 t-c2]})
(operation/Operation ^{:name "cz-op"} t-cz-op {:extracted true :calls [t-c1 t-c2]})
(module/Module ^{:name "CZ"} t-cz {:exposes [t-cz-op]})

(deftest latent-boundary-silent-on-cohesive-surface
  (testing "a module whose whole public surface shares one clientele is NOT flagged"
    (let [db (build/vars->cozo [#'t-c1 #'t-c2 #'t-coh #'t-cz-op #'t-cz])]
      (is (empty? (subsystem/latent-boundaries db))
          "one clientele = the whole surface = no proper sub-interface"))))

;; LONE: d1 captured by CW, d2 by CV — two disjoint clienteles but each a LONE op (no cohesion).
(operation/Operation ^{:name "d1"} t-d1 {:extracted true})
(operation/Operation ^{:name "d2"} t-d2 {:extracted true})
(module/Module ^{:name "LONE"} t-lone {:exposes [t-d1 t-d2]})
(operation/Operation ^{:name "cw-op"} t-cw-op {:extracted true :calls [t-d1]})
(module/Module ^{:name "CW"} t-cw {:exposes [t-cw-op]})
(operation/Operation ^{:name "cv-op"} t-cv-op {:extracted true :calls [t-d2]})
(module/Module ^{:name "CV"} t-cv {:exposes [t-cv-op]})

(deftest latent-boundary-cohesion-gate-rejects-lone-captives
  (testing "disjoint clienteles of LONE ops (no ≥2-op bundle) are below the cohesion gate"
    (let [db (build/vars->cozo [#'t-d1 #'t-d2 #'t-lone #'t-cw-op #'t-cw #'t-cv-op #'t-cv])]
      (is (empty? (subsystem/latent-boundaries db))
          "a latent sub-interface is a bundle, not a lone captive op"))))

(deftest fukan-latent-boundaries-post-substrate-extraction
  (testing "the substrate is a clean leaf (not flagged); core-structure keeps a print-dual reader residue"
    (let [lb (subsystem/latent-boundaries (pipeline/build-model "src"))
          cs (get lb "fukan.canvas.core.structure")
          in-a-bundle? (fn [op] (some (fn [b] (some #{op} (:ops b))) cs))]
      ;; The node substrate was extracted DOWNWARD: its surface is one clientele (the builders), so it
      ;; never flags — the cleanest possible boundary. The construction toolkit left core-structure with it.
      (is (not (contains? lb "fukan.canvas.core.substrate"))
          "core-substrate is a self-contained base layer — one clientele, no consumer-disjoint split")
      ;; core-structure still flags, but with the introspection residue: `scalar-slot?` + `structure-by-tag`,
      ;; both consumed only by the print-dual (projection.instance), consumer-disjoint from the rest of the
      ;; grammar surface. (The check/introspect reader bundle no longer surfaces here — correspondence, its
      ;; chief consumer, moved to the code-vocab correspondence, off the extraction root.)
      (is (some? cs) "core-structure flags with the print-dual introspection residue")
      (is (in-a-bundle? "structure-by-tag") "the surfaced bundle is the print-dual's introspection surface")
      (is (not (in-a-bundle? "value-literal->iv"))
          "value-literal->iv is the registry-bound builder, not part of the introspection bundle"))))
