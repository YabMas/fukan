(ns lib.arch-test
  "The opt-in clean-architecture quality layer: no two modules mutually depend."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s]
            ;; the composition root — registers fukan's Clojure extractor, so `build-model "src"`
            ;; merges extracted code (the call graph latent-boundaries reads) onto the design graph
            [fukan.infra.model]
            [fukan.model.pipeline :as pipeline]
            [lib.code :as code]
            [lib.arch :as la]))

(defn- law-desc [substr]
  (->> (s/structure-by-tag :lib.arch/ModuleArchitecture) :laws
       (map :desc) (filter #(str/includes? % substr)) first))

(defn- offenders [db substr]
  (let [desc (law-desc substr)]
    (->> (s/check db) (filter #(= desc (:law %)))
         (mapcat :offenders) (map first) (map #(:entity/name (d/entity db %))) set)))

;; a synthetic mutual pair: A's op delegates to B's op and B's op delegates to A's op
(declare t-mb-op)
(code/Operation ^{:name "ma-op"} t-ma-op {:delegates [t-mb-op]})
(code/Operation ^{:name "mb-op"} t-mb-op {:delegates [t-ma-op]})
(code/Module ^{:name "MA"} t-mod-ma {:exposes [t-ma-op]})
(code/Module ^{:name "MB"} t-mod-mb {:exposes [t-mb-op]})

(deftest mutual-dependency-fires
  (testing "two modules whose ops mutually delegate violate the no-mutual-dependency law"
    (let [db (a/assemble-vars [#'t-ma-op #'t-mb-op #'t-mod-ma #'t-mod-mb])]
      (is (= #{"MA" "MB"} (offenders db "mutually depend"))))))

(deftest fukan-module-graph-has-no-mutual-dependency
  (testing "fukan's own module graph is acyclic — the quality law is a green opt-in"
    (is (empty? (offenders (pipeline/build-model nil) "mutually depend")))))

;; ── conformance fixtures: S's op delegates to T's op (cross-subsystem) ──
(code/Operation ^{:name "op-t"} t-op-t "callee in T")
(code/Operation ^{:name "op-s"} t-op-s {:delegates [t-op-t]})
(code/Module ^{:name "M-S"} t-cm-s {:exposes [t-op-s]})
(code/Module ^{:name "M-T"} t-cm-t {:exposes [t-op-t]})
(declare t-sub-T)
(code/Subsystem ^{:name "S-ok"}  t-sub-S-ok  {:child [t-cm-s] :may-depend [t-sub-T]})  ; declares the dep
(code/Subsystem ^{:name "S-bad"} t-sub-S-bad {:child [t-cm-s]})                          ; does NOT
(code/Subsystem ^{:name "T"}     t-sub-T     {:child [t-cm-t]})

(deftest conformance-green-when-cross-dep-is-declared
  (testing "M-S → M-T conforms because subsystem S-ok declares :may-depend T"
    (let [db (a/assemble-vars [#'t-op-t #'t-op-s #'t-cm-s #'t-cm-t #'t-sub-S-ok #'t-sub-T])]
      (is (empty? (offenders db "cross-subsystem"))))))

(deftest conformance-fires-on-undeclared-cross-dep
  (testing "M-S → M-T violates because S-bad does NOT declare :may-depend T"
    (let [db (a/assemble-vars [#'t-op-t #'t-op-s #'t-cm-s #'t-cm-t #'t-sub-S-bad #'t-sub-T])]
      (is (= #{"M-S"} (offenders db "cross-subsystem"))))))

;; ── acyclicity fixtures: a 2-cycle in :may-depend ──
(declare t-sub-cy-b)
(code/Subsystem ^{:name "CY-A"} t-sub-cy-a {:may-depend [t-sub-cy-b]})
(code/Subsystem ^{:name "CY-B"} t-sub-cy-b {:may-depend [t-sub-cy-a]})

(deftest may-depend-acyclicity-fires-on-a-cycle
  (testing "a :may-depend cycle CY-A ⇄ CY-B violates the acyclicity law"
    (let [db (a/assemble-vars [#'t-sub-cy-a #'t-sub-cy-b])]
      (is (= #{"CY-A" "CY-B"} (offenders db "acyclic"))))))

(deftest fukan-may-depend-graph-is-acyclic
  (testing "fukan's declared :may-depend DAG is acyclic"
    (is (empty? (offenders (pipeline/build-model nil) "acyclic")))))

;; ── membership fixtures: a module in no subsystem (with a subsystem present) ──
(code/Module ^{:name "orphan"} t-orphan "a module in no subsystem")
(code/Module ^{:name "homed"}  t-homed  "a module in a subsystem")
(code/Module t-ext-orphan {:extracted true})   ; an extracted code-fact module, in no subsystem
(code/Subsystem ^{:name "home"} t-sub-home {:child [t-homed]})

(deftest membership-ignores-extracted-modules
  (testing "an extracted (code-fact) module in no subsystem is NOT a membership offender"
    (let [db (a/assemble-vars [#'t-ext-orphan #'t-homed #'t-sub-home])]
      (is (empty? (offenders db "belongs to a Subsystem"))
          "design-membership is for authored modules; extracted modules are out of scope"))))

(deftest membership-fires-on-unclustered-module
  (testing "with a Subsystem present, a Module in none is an offender"
    (let [db (a/assemble-vars [#'t-orphan #'t-homed #'t-sub-home])]
      (is (= #{"orphan"} (offenders db "belongs to a Subsystem"))))))

(deftest membership-vacuous-without-subsystems
  (testing "no Subsystem modelled → the membership law is vacuous (guard)"
    (let [db (a/assemble-vars [#'t-orphan])]
      (is (empty? (offenders db "belongs to a Subsystem"))))))

(deftest fukan-every-module-is-clustered
  (testing "every fukan Module belongs to a subsystem"
    (is (empty? (offenders (pipeline/build-model nil) "belongs to a Subsystem")))))

;; ── latent-boundaries (interface-segregation discovery) ──
;; Synthetic EXTRACTED graphs: HOST exposes public ops; consumer modules :call them. The reading
;; partitions HOST's public surface by shared-clientele and reports ≥2-op consumer-disjoint bundles.

;; HOST: a1,a2 captured by CX; b1,b2 captured by CY — two disjoint clienteles.
(code/Operation ^{:name "a1"} t-a1 {:extracted true})
(code/Operation ^{:name "a2"} t-a2 {:extracted true})
(code/Operation ^{:name "b1"} t-b1 {:extracted true})
(code/Operation ^{:name "b2"} t-b2 {:extracted true})
(code/Module ^{:name "HOST"} t-host {:exposes [t-a1 t-a2 t-b1 t-b2]})
(code/Operation ^{:name "cx-op"} t-cx-op {:extracted true :calls [t-a1 t-a2]})
(code/Module ^{:name "CX"} t-cx {:exposes [t-cx-op]})
(code/Operation ^{:name "cy-op"} t-cy-op {:extracted true :calls [t-b1 t-b2]})
(code/Module ^{:name "CY"} t-cy {:exposes [t-cy-op]})

(deftest latent-boundary-fires-on-disjoint-clienteles
  (testing "a module whose public ops split into two consumer-disjoint bundles is surfaced"
    (let [db (a/assemble-vars [#'t-a1 #'t-a2 #'t-b1 #'t-b2 #'t-host
                               #'t-cx-op #'t-cx #'t-cy-op #'t-cy])
          lb (la/latent-boundaries db)
          bundles (->> (get lb "HOST") (map (comp set :ops)) set)]
      (is (= #{"HOST"} (set (keys lb))) "only HOST has a split surface (consumers have no clientele)")
      (is (= #{#{"a1" "a2"} #{"b1" "b2"}} bundles)
          "the two disjoint clienteles are recovered as the sub-interfaces"))))

;; COH: c1,c2 BOTH captured by CZ — one shared clientele, no internal seam.
(code/Operation ^{:name "c1"} t-c1 {:extracted true})
(code/Operation ^{:name "c2"} t-c2 {:extracted true})
(code/Module ^{:name "COH"} t-coh {:exposes [t-c1 t-c2]})
(code/Operation ^{:name "cz-op"} t-cz-op {:extracted true :calls [t-c1 t-c2]})
(code/Module ^{:name "CZ"} t-cz {:exposes [t-cz-op]})

(deftest latent-boundary-silent-on-cohesive-surface
  (testing "a module whose whole public surface shares one clientele is NOT flagged"
    (let [db (a/assemble-vars [#'t-c1 #'t-c2 #'t-coh #'t-cz-op #'t-cz])]
      (is (empty? (la/latent-boundaries db))
          "one clientele = the whole surface = no proper sub-interface"))))

;; LONE: d1 captured by CW, d2 by CV — two disjoint clienteles but each a LONE op (no cohesion).
(code/Operation ^{:name "d1"} t-d1 {:extracted true})
(code/Operation ^{:name "d2"} t-d2 {:extracted true})
(code/Module ^{:name "LONE"} t-lone {:exposes [t-d1 t-d2]})
(code/Operation ^{:name "cw-op"} t-cw-op {:extracted true :calls [t-d1]})
(code/Module ^{:name "CW"} t-cw {:exposes [t-cw-op]})
(code/Operation ^{:name "cv-op"} t-cv-op {:extracted true :calls [t-d2]})
(code/Module ^{:name "CV"} t-cv {:exposes [t-cv-op]})

(deftest latent-boundary-cohesion-gate-rejects-lone-captives
  (testing "disjoint clienteles of LONE ops (no ≥2-op bundle) are below the cohesion gate"
    (let [db (a/assemble-vars [#'t-d1 #'t-d2 #'t-lone #'t-cw-op #'t-cw #'t-cv-op #'t-cv])]
      (is (empty? (la/latent-boundaries db))
          "a latent sub-interface is a bundle, not a lone captive op"))))

(deftest fukan-latent-boundaries-post-substrate-extraction
  (testing "the substrate is a clean leaf (not flagged); core-structure keeps only the reader residue"
    (let [lb (la/latent-boundaries (pipeline/build-model "src"))
          cs (get lb "fukan.canvas.core.structure")
          in-a-bundle? (fn [op] (some (fn [b] (some #{op} (:ops b))) cs))]
      ;; The node substrate was extracted DOWNWARD: its surface is one clientele (the builders), so it
      ;; never flags — the cleanest possible boundary. The construction toolkit left core-structure with it.
      (is (not (contains? lb "fukan.canvas.core.substrate"))
          "core-substrate is a self-contained base layer — one clientele, no consumer-disjoint split")
      ;; core-structure still flags, but only with the RESIDUE: its check/introspect reader surface is
      ;; consumer-disjoint from the lone `value-literal->iv` (the registry-bound builder, used only by
      ;; typing). We DECIDE to leave it — core-structure is the cohesive defstructure-grammar module.
      (is (some? cs) "core-structure flags with the reader-vs-value-construction residue")
      (is (in-a-bundle? "check") "the surfaced bundle is the check/introspect reader surface")
      (is (not (in-a-bundle? "value-literal->iv"))
          "value-literal->iv is the disjoint lone builder, not part of the reader bundle"))))
