(ns fukan.cozo.law-violations-test
  "The general law ENGINE (the datalog→CozoScript compiler) must surface the SAME
   offenders as datascript's `check` on synthetic VIOLATING fixtures — the FIRING
   direction the green-model agreement test can't exercise. This parallels `check-test`
   (which validates the hand-ported oracle queries); here the COMPILED law is the subject,
   over the rule families rule-compilation lights up: the architecture-quality laws
   (or-join / recursion / subsystem rules), the correspondence laws (`op-twin` +
   `module-corresponds?`), and the effect law (recursive `reaches-effect`). The fixtures
   mirror the oracle's so the two stay comparable."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            ;; composition root — registers the extractor + loads the full vocab/laws
            [fukan.infra.model]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s]
            [fukan.cozo.db :as db]
            [fukan.cozo.mirror :as mirror]
            [fukan.cozo.law :as law]
            [canvas.vocab.code.operation :as operation]
            [canvas.vocab.code.module :as module]
            [canvas.vocab.code.subsystem :as subsystem]))

(defn- ds-names
  "Datascript `check` offenders for the law whose desc contains `substr`, as entity names."
  [ds substr]
  (->> (s/check ds)
       (filter #(str/includes? (:law %) substr))
       (mapcat :offenders) (map first)
       (map #(:entity/name (d/entity ds %))) set))

(defn- compiler-names
  "The COMPILED law whose desc contains `substr`, its offenders over the mirror of `ds`,
   mapped to entity names: the mirror preserves ds eids, so each offender eid-string
   resolves back through `ds`. Closes the db after."
  [ds substr]
  (let [cdb (mirror/mirror ds)]
    (try
      (->> (law/check-structural cdb)
           (filter #(and (:offenders %) (str/includes? (:law %) substr)))
           (mapcat :offenders) (map first)
           (map #(:entity/name (d/entity ds (Long/parseLong %)))) set)
      (finally (db/close cdb)))))

(defn- agrees
  "Assert the compiled law matched by `substr` flags exactly `expected`, and that
   datascript agrees (the precondition that `expected` is the real verdict)."
  [ds substr expected]
  (is (= expected (ds-names ds substr)) (str "precondition: datascript flags " substr))
  (is (= (ds-names ds substr) (compiler-names ds substr)) (str "compiled law == datascript for " substr)))

;; ── no-mutual-dependency: MA's op delegates to MB's op and MB's back to MA's ──
;; (module-depends rules: or-join over :in/:out, not=; :scope :global)
(declare t-mb-op)
(operation/Operation ^{:name "ma-op"} t-ma-op {:delegates [t-mb-op]})
(operation/Operation ^{:name "mb-op"} t-mb-op {:delegates [t-ma-op]})
(module/Module ^{:name "MA"} t-mod-ma {:exposes [t-ma-op]})
(module/Module ^{:name "MB"} t-mod-mb {:exposes [t-mb-op]})

(deftest mutual-dependency-compiled-matches-datascript
  (testing "compiled no-mutual-dependency == datascript on a synthetic cycle"
    (agrees (a/assemble-vars [#'t-ma-op #'t-mb-op #'t-mod-ma #'t-mod-mb])
            "mutually depend" #{"MA" "MB"})))

;; ── acyclicity: a :may-depend 2-cycle CY-A ⇄ CY-B (recursive sub-reaches) ──
(declare t-sub-cy-b)
(subsystem/Subsystem ^{:name "CY-A"} t-sub-cy-a {:may-depend [t-sub-cy-b]})
(subsystem/Subsystem ^{:name "CY-B"} t-sub-cy-b {:may-depend [t-sub-cy-a]})

(deftest acyclicity-compiled-matches-datascript
  (testing "compiled :may-depend acyclicity == datascript on a synthetic cycle"
    (agrees (a/assemble-vars [#'t-sub-cy-a #'t-sub-cy-b])
            "acyclic" #{"CY-A" "CY-B"})))

;; ── conformance: M-S → M-T cross-subsystem, S-bad declares no :may-depend T ──
;; (subsystem rules + stratified `not declared_dep`)
(operation/Operation ^{:name "op-t"} t-op-t "callee in T")
(operation/Operation ^{:name "op-s"} t-op-s {:delegates [t-op-t]})
(module/Module ^{:name "M-S"} t-cm-s {:exposes [t-op-s]})
(module/Module ^{:name "M-T"} t-cm-t {:exposes [t-op-t]})
(declare t-sub-T)
(subsystem/Subsystem ^{:name "S-bad"} t-sub-S-bad {:child [t-cm-s]})
(subsystem/Subsystem ^{:name "T"}     t-sub-T     {:child [t-cm-t]})

(deftest conformance-compiled-matches-datascript
  (testing "compiled :may-depend conformance == datascript on an undeclared cross-subsystem dep"
    (agrees (a/assemble-vars [#'t-op-t #'t-op-s #'t-cm-s #'t-cm-t #'t-sub-S-bad #'t-sub-T])
            "cross-subsystem" #{"M-S"})))

;; ── membership: an authored Module in no Subsystem (with a Subsystem present) ──
(module/Module ^{:name "orphan"} t-orphan "a module in no subsystem")
(module/Module ^{:name "homed"}  t-homed  "a module in a subsystem")
(subsystem/Subsystem ^{:name "home"} t-sub-home {:child [t-homed]})

(deftest membership-compiled-matches-datascript
  (testing "compiled Subsystem membership == datascript on an unclustered authored module"
    (agrees (a/assemble-vars [#'t-orphan #'t-homed #'t-sub-home])
            "belongs to a Subsystem" #{"orphan"}))
  (testing "no Subsystem modelled → both vacuously empty"
    (agrees (a/assemble-vars [#'t-orphan]) "belongs to a Subsystem" #{})))

;; ── Encapsulation: a public extracted op with no authored twin (op-twin not-join) ──
(operation/Operation ^{:name "loose"} t-loose {:extracted true})
(module/Module ^{:name "fukan.x.thing"} t-thing {:child [t-loose]})

(deftest encapsulation-compiled-matches-datascript
  (testing "compiled Encapsulation == datascript on an uncovered public extracted op"
    (agrees (a/assemble-vars [#'t-loose #'t-thing])
            "public extracted operation is covered" #{"loose"})))

;; ── Realization: an authored op with no extracted twin (+ an extracted op for the guard) ──
(operation/Operation ^{:name "extracted-thing"} t-ext {:extracted true})
(operation/Operation ^{:name "ghost"} t-ghost "authored, unrealized")
(module/Module ^{:name "fukan.realm"} t-realm {:child [t-ghost t-ext]})

(deftest realization-compiled-matches-datascript
  (testing "compiled Realization == datascript on an authored op with no twin"
    (agrees (a/assemble-vars [#'t-ext #'t-ghost #'t-realm])
            "is realized by an extracted operation" #{"ghost"})))

;; ── CallRealization: an authored cross-module delegation with no realizing call ──
;; (in-module rules + not-join over module_corresponds; the guard holds an extracted call)
(declare t-db)
(operation/Operation ^{:name "da"} t-da {:delegates [t-db]})
(operation/Operation ^{:name "db"} t-db "authored callee in b")
(module/Module ^{:name "a"} t-mod-a {:exposes [t-da]})
(module/Module ^{:name "b"} t-mod-b {:exposes [t-db]})
(operation/Operation ^{:name "ge2"} t-ge2 {:extracted true})
(operation/Operation ^{:name "ge"}  t-ge  {:extracted true :calls [t-ge2]})
(module/Module ^{:name "fukan.guard"} t-guard {:extracted true :child [t-ge t-ge2]})

(deftest call-realization-compiled-matches-datascript
  (testing "compiled CallRealization == datascript on an unrealized delegation"
    (agrees (a/assemble-vars [#'t-da #'t-db #'t-mod-a #'t-mod-b #'t-ge #'t-ge2 #'t-guard])
            "intended cross-module delegation" #{"da"})))

;; ── Fidelity: an extracted cross-module call between modelled faculties, undeclared ──
;; (intended rule with module_corresponds generating km; `not intended`)
(declare t-cb)
(operation/Operation ^{:name "ca"} t-ca {:extracted true :calls [t-cb]})
(operation/Operation ^{:name "cb"} t-cb {:extracted true})
(module/Module ^{:name "fukan.alpha"} t-code-alpha {:extracted true :child [t-ca]})
(module/Module ^{:name "fukan.beta"}  t-code-beta  {:extracted true :child [t-cb]})
(module/Module ^{:name "alpha"} t-auth-alpha "authored, corresponds fukan.alpha")
(module/Module ^{:name "beta"}  t-auth-beta  "authored, corresponds fukan.beta")

(deftest fidelity-compiled-matches-datascript
  (testing "compiled Fidelity == datascript on an undeclared modelled-faculty call"
    (agrees (a/assemble-vars [#'t-ca #'t-cb #'t-code-alpha #'t-code-beta #'t-auth-alpha #'t-auth-beta])
            "actual cross-module call between modelled" #{"ca"})))

;; ── EffectCorrespondence: a modelled op whose extracted twin reaches an undeclared effect ──
;; (recursive reaches-effect + op-twin + not-join)
(operation/Operation ^{:name "doio"} t-doio-auth "authored, declares no effects")
(operation/Operation ^{:name "doio"} t-doio-ext {:extracted true :performs [:io]})
(module/Module ^{:name "svc"} t-svc {:exposes [t-doio-auth]})
(module/Module ^{:name "fukan.svc"} t-code-svc {:extracted true :child [t-doio-ext]})

(deftest effect-correspondence-compiled-matches-datascript
  (testing "compiled EffectCorrespondence == datascript on an under-declared op"
    (agrees (a/assemble-vars [#'t-doio-auth #'t-doio-ext #'t-svc #'t-code-svc])
            "transitively reaches is declared" #{"doio"})))
