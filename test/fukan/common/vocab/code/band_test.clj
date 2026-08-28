(ns fukan.common.vocab.code.band-test
  "`Band` — the stratum whose membership is DERIVED from the namespace path and whose evidence is
   the extracted call graph. What has to hold: the laws bite when a band is declared, and are
   silent — not merely quiet — in the projects that declare none, which is most of them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.cozo.query :as cq]
            ;; the composition root — registers the Clojure FACT extractor and the Cozo check engine
            [fukan.infra.model]
            [fukan.common.vocab.code.band :as band]
            [fukan.common.extraction.clojure.module :as clj-module]
            [fukan.common.extraction.clojure.operation :as clj-op]))

(defn- law-desc [substr]
  (->> (:laws (s/structure-by-tag :fukan.common.vocab.code.band/Band))
       (map :desc) (filter #(str/includes? % substr)) first))

(defn- offenders [db substr]
  (let [desc (law-desc substr)]
    (->> (law/check db) (filter #(= desc (:law %)))
         (mapcat :offenders)
         (map (fn [row] (mapv #(:entity/name (cq/entity db %)) row)))
         set)))

;; ── a two-namespace fixture: app.ui calls app.core, and app.core calls back ──
(declare t-core-fn)
(clj-op/Fn ^{:name "ui-fn"}   t-ui-fn   {:calls [t-core-fn]})
(clj-op/Fn ^{:name "core-fn"} t-core-fn)
(clj-op/Fn ^{:name "up-fn"}   t-up-fn   {:calls [t-ui-fn]})

(clj-module/Ns ^{:name "app.ui.screen"}  t-ns-ui   {:child [t-ui-fn]})
(clj-module/Ns ^{:name "app.core.thing"} t-ns-core {:child [t-core-fn t-up-fn]})

(def ^:private fact-vars [#'t-ui-fn #'t-core-fn #'t-up-fn #'t-ns-ui #'t-ns-core])

;; ── the declaration: Ui may reach Core; Core may reach nothing ───────────────
(band/Band ^{:name "TestCore"} t-band-core {:prefix ["app.core."]})
(band/Band ^{:name "TestUi"}   t-band-ui   {:prefix ["app.ui."] :may-depend [t-band-core]})

(deftest an-undeclared-cross-band-call-is-an-offending-edge
  (testing "app.core.thing calls app.ui.screen, and Core declares no dependency on Ui. The
            offender is the whole edge plus the bands it crosses — a law naming only the caller
            says a namespace is in the wrong without saying which require is"
    (let [db (build/vars->cozo (into fact-vars [#'t-band-core #'t-band-ui]))]
      (is (= #{["app.core.thing" "app.ui.screen" "TestCore" "TestUi"]}
             (offenders db "cross-band")))
      (testing "and the declared direction is NOT an offender"
        (is (not (contains? (offenders db "cross-band")
                            ["app.ui.screen" "app.core.thing" "TestUi" "TestCore"])))))))

(deftest membership-is-derived-from-the-name-and-never-authored
  (let [db (build/vars->cozo (into fact-vars [#'t-band-core #'t-band-ui]))]
    (is (= #{["app.core.thing" "TestCore"] ["app.ui.screen" "TestUi"]}
           (set (cq/q '[:find ?nsn ?bn :in $ %
                        :where (in-band ?ns ?b) [?ns :entity/name ?nsn] [?b :entity/name ?bn]]
                      db (s/vocab-rules))))
        "no membership edge was authored — every one of these is read off the namespace's name")))

;; ── the coverage gate ────────────────────────────────────────────────────────

(clj-op/Fn ^{:name "orphan-fn"} t-orphan-fn)
(clj-module/Ns ^{:name "elsewhere.orphan"} t-ns-orphan {:child [t-orphan-fn]})

(deftest an-unbanded-namespace-is-invisible-unless-coverage-is-demanded
  (testing "`in-band` is path-derived, so a namespace under a prefix no band claims is an offender
            NOWHERE — the cross-band law needs both ends banded before it can fire. Without the
            coverage law an unbanded package calls anything, is called by anything, and the model
            stays green."
    (let [db (build/vars->cozo (into fact-vars [#'t-orphan-fn #'t-ns-orphan #'t-band-core #'t-band-ui]))]
      (is (= #{["elsewhere.orphan"]} (offenders db "belongs to a band"))
          "the coverage law is what makes the design non-opt-in"))))

(deftest a-project-that-declares-no-band-is-asserting-nothing-about-coverage
  (testing "the gate is not politeness to non-adopters, it is what the rule MEANS: a project with
            no bands makes no claim of a partition, and every law here must be vacuous for it —
            otherwise merely loading `fukan.common` would turn every consumer's check red"
    (let [db (build/vars->cozo (conj fact-vars #'t-ns-orphan))]
      (is (empty? (offenders db "belongs to a band")))
      (is (empty? (offenders db "cross-band")))
      (is (empty? (offenders db "acyclic"))))))

;; ── acyclicity ───────────────────────────────────────────────────────────────

(declare t-band-y)
(band/Band ^{:name "TestX"} t-band-x {:prefix ["x."] :may-depend [t-band-y]})
(band/Band ^{:name "TestY"} t-band-y {:prefix ["y."] :may-depend [t-band-x]})

(deftest a-cyclic-declaration-is-incoherent-intent
  (let [db (build/vars->cozo [#'t-band-x #'t-band-y])]
    (is (= #{["TestX"] ["TestY"]} (offenders db "acyclic")))))
