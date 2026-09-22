(ns fukan.common.vocab.code.stratum-test
  "`Stratum` — a level of the code, whose modules are AUTHORED and whose evidence is the extracted
   namespace graph. What has to hold: an edge out of a level is legal only along a declared
   `:rests-on`, transitively resting on a level licenses nothing, and a module in no stratum is
   unconstrained — there is no coverage law."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            ;; the composition root — registers the Clojure FACT extractor and the Cozo check engine
            [fukan.infra.model]
            [fukan.common.vocab.code.module :as module]
            [fukan.common.vocab.code.stratum :as stratum]
            [fukan.common.extraction.clojure.module :as clj-module]
            [fukan.common.extraction.clojure.operation :as clj-op]))

(defn- offenders
  "Offender rows of the law keyed `k`, each cell resolved to its name. Addressed by KEY, not by a
   substring of the description: an unknown key throws, where a description that has been reworded
   upstream silently matches nothing — and a test asserting `empty?` would then pass for the wrong
   reason, which is the failure this whole surface exists to prevent."
  [db k]
  (law/violation-rows db k))

;; ── three levels and a bystander: ui over core over base, misc in no stratum ──
;; ui calls core (declared) and base (a reach past core); core calls base (declared) and back up
;; into ui (undeclared); misc calls into base and ui, and nothing about it is declared.
(declare t-ui-fn t-core-fn t-base-fn)
(clj-op/Fn ^{:name "ui-fn"}   t-ui-fn   {:calls [t-core-fn t-base-fn]})
(clj-op/Fn ^{:name "core-fn"} t-core-fn {:calls [t-base-fn]})
(clj-op/Fn ^{:name "up-fn"}   t-up-fn   {:calls [t-ui-fn]})
(clj-op/Fn ^{:name "base-fn"} t-base-fn)
(clj-op/Fn ^{:name "misc-fn"} t-misc-fn {:calls [t-base-fn t-ui-fn]})

(clj-module/Ns ^{:name "app.ui"}   t-ns-ui   {:child [t-ui-fn]})
(clj-module/Ns ^{:name "app.core"} t-ns-core {:child [t-core-fn t-up-fn]})
(clj-module/Ns ^{:name "app.base"} t-ns-base {:child [t-base-fn]})
(clj-module/Ns ^{:name "app.misc"} t-ns-misc {:child [t-misc-fn]})

;; design modules pair with the namespaces by name suffix
(module/Module ^{:name "ui"}   t-mod-ui)
(module/Module ^{:name "core"} t-mod-core)
(module/Module ^{:name "base"} t-mod-base)
(module/Module ^{:name "misc"} t-mod-misc)

(def ^:private code-vars
  [#'t-ui-fn #'t-core-fn #'t-up-fn #'t-base-fn #'t-misc-fn
   #'t-ns-ui #'t-ns-core #'t-ns-base #'t-ns-misc
   #'t-mod-ui #'t-mod-core #'t-mod-base #'t-mod-misc])

(stratum/Stratum ^{:name "Base"} t-base {:provided-by [t-mod-base]})
(stratum/Stratum ^{:name "Core"} t-core {:provided-by [t-mod-core] :rests-on [t-base]})
(stratum/Stratum ^{:name "Ui"}   t-ui   {:provided-by [t-mod-ui]   :rests-on [t-core]})

(def ^:private strata [#'t-base #'t-core #'t-ui])

(deftest an-edge-out-of-a-level-follows-a-declared-rests-on
  (let [db (build/vars->cozo (into code-vars strata))
        found (offenders db :stratum/undeclared-dependency)]
    (testing "core calling back up into ui is an offending edge, named with both levels"
      (is (contains? found ["app.core" "app.ui" "Core" "Ui"])))
    (testing "ui resting on core does not license a call into base: reaching past a level is
              declared or it is a violation"
      (is (contains? found ["app.ui" "app.base" "Ui" "Base"])))
    (testing "the declared edges are not offenders"
      (is (not (contains? found ["app.ui" "app.core" "Ui" "Core"])))
      (is (not (contains? found ["app.core" "app.base" "Core" "Base"]))))
    (testing "a module in no stratum is unconstrained — no coverage law makes misc an offender"
      (is (not-any? #(= "app.misc" (first %)) found))
      (is (= 2 (count found))))))

(stratum/Stratum ^{:name "UiDeclared"} t-ui-declared
  {:provided-by [t-mod-ui] :rests-on [t-core t-base]})

(deftest declaring-the-skip-makes-it-a-decision
  (let [db (build/vars->cozo (into code-vars [#'t-base #'t-core #'t-ui-declared]))]
    (is (= #{["app.core" "app.ui" "Core" "UiDeclared"]}
           (offenders db :stratum/undeclared-dependency))
        "once Ui states that it rests on Base directly, its call into base is the design")))

(stratum/Stratum ^{:name "CoreAgain"} t-core-again {:provided-by [t-mod-core]})

(deftest a-module-provides-one-level
  (let [db (build/vars->cozo (into code-vars (conj strata #'t-core-again)))]
    (is (= #{["core"]} (offenders db :stratum/module-in-two-strata)))))

(declare t-cyc-b)
(stratum/Stratum ^{:name "CycA"} t-cyc-a {:provided-by [t-mod-misc] :rests-on [t-cyc-b]})
(stratum/Stratum ^{:name "CycB"} t-cyc-b {:provided-by [t-mod-base] :rests-on [t-cyc-a]})

(deftest a-level-written-in-itself-is-incoherent
  (let [db (build/vars->cozo [#'t-mod-misc #'t-mod-base #'t-cyc-a #'t-cyc-b])]
    (is (= #{["CycA"] ["CycB"]} (offenders db :stratum/rests-on-cyclic)))))

(deftest a-project-that-declares-no-stratum-is-asserting-nothing
  (testing "every law is vacuous without a declared stratum, so loading `fukan.common` cannot turn
            an existing consumer's check red"
    (let [db (build/vars->cozo code-vars)]
      (is (empty? (offenders db :stratum/undeclared-dependency)))
      (is (empty? (offenders db :stratum/module-in-two-strata)))
      (is (empty? (offenders db :stratum/rests-on-cyclic))))))
