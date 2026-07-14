(ns fukan.common.vocab.code-test
  "Module-dependency readings on the code grammar."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            [fukan.common.vocab.code.kind :as kind]
            [fukan.common.vocab.code.operation :as operation]
            [fukan.common.vocab.code.module :as module]
            [fukan.common.vocab.code.plug-point :as plug-point]
            [fukan.common.vocab.code.subsystem :as subsystem]))

(module/Module ^{:name "fx-impl"}  t-fx-impl  "a fixture module")
(module/Module ^{:name "fx-infra"} t-fx-infra "another fixture module")

;; ── module-dependency fixtures: a→b by a delegate edge; c adopts a Kind d owns ──
(kind/Kind DShape :string)
(operation/Operation ^{:name "b-op"} t-b-op "callee")
(operation/Operation ^{:name "a-op"} t-a-op {:delegates [t-b-op]})
;; c-op takes a DShape (a ref to a Kind owned by module D) → data-adoption dependency c→d
(operation/Operation ^{:name "c-op"} t-c-op {:signature [:=> [:catn [:x DShape]] :nil]})
(module/Module ^{:name "A"} t-mod-a {:exposes [t-a-op]})
(module/Module ^{:name "B"} t-mod-b {:exposes [t-b-op]})
(module/Module ^{:name "C"} t-mod-c {:exposes [t-c-op]})
(module/Module ^{:name "D"} t-mod-d {:owns [DShape]})

(deftest module-dependencies-unions-calls-and-data-adoption
  (testing "M depends on N via a delegate (call) OR via adopting a Kind N owns (data)"
    (let [db (build/vars->cozo [#'DShape #'t-b-op #'t-a-op #'t-c-op
                               #'t-mod-a #'t-mod-b #'t-mod-c #'t-mod-d])
          deps (module/module-dependencies db)]
      (is (contains? deps ["A" "B"]) "call dependency: A's op delegates to B's op")
      (is (contains? deps ["C" "D"]) "data-adoption: C's op adopts a Kind D owns")
      (is (not (contains? deps ["A" "A"])) "no self-dependency"))))

;; ── Module :extracted provenance (symmetric with Operation) ──────────────────────
(module/Module ^{:name "t-ext-mod"} t-ext-mod {:extracted true})

(deftest module-carries-extracted-provenance
  (testing "a Module authored with {:extracted true} stamps :val/extracted (symmetric with Operation)"
    (let [db (build/vars->cozo [#'t-ext-mod])]
      (is (true? (ffirst (cq/q '[:find ?x :where [?m :structure/of :fukan.common.vocab.code.module/Module] [?m :val/extracted ?x]] db)))
          "Module :extracted is stored as :val/extracted"))))

;; ── Subsystem: clusters Modules + declares the :may-depend DAG (self-reference) ──
(declare t-sub-b)
(subsystem/Subsystem ^{:name "sub-a"} t-sub-a {:child [t-fx-impl] :may-depend [t-sub-b]})
(subsystem/Subsystem ^{:name "sub-b"} t-sub-b {:child [t-fx-infra]})

(deftest subsystem-clusters-modules-and-declares-may-depend
  (testing "a Subsystem owns Modules via :child and declares :may-depend to another Subsystem"
    (let [db (build/vars->cozo [#'t-fx-impl #'t-fx-infra #'t-sub-a #'t-sub-b])
          a  (ffirst (cq/q '[:find ?s :where [?s :entity/name "sub-a"]] db))]
      (is (= #{"fx-impl"}
             (set (cq/q '[:find [?mn ...] :in $ ?a
                         :where [?r :rel/from ?a] [?r :rel/kind :child] [?r :rel/to ?m] [?m :entity/name ?mn]]
                       db a)))
          ":child edges reach the clustered Modules")
      (is (= #{"sub-b"}
             (set (cq/q '[:find [?tn ...] :in $ ?a
                         :where [?r :rel/from ?a] [?r :rel/kind :may-depend] [?r :rel/to ?t] [?t :entity/name ?tn]]
                       db a)))
          ":may-depend is a self-reference to another Subsystem (mirrors Operation :delegates)"))))

;; ── the plug-point seam: a Module :offers a PlugPoint it owns; another Module :satisfies it (inverted) ──
(plug-point/PlugPoint ^{:name "Backend"} t-plug-point "a plug-point")
(module/Module ^{:name "Owner"} t-mod-owner   {:offers [t-plug-point]})
(module/Module ^{:name "Impl"}  t-mod-impl    {:satisfies [t-plug-point]})

(deftest module-offers-and-satisfies-a-plug-point
  (testing "an owner Module :offers a PlugPoint and an implementer Module :satisfies it — the inverted plug-point seam"
    (let [db (build/vars->cozo [#'t-plug-point #'t-mod-owner #'t-mod-impl])]
      (is (= #{["Owner" "Backend"]}
             (set (cq/q '[:find ?mn ?cn
                          :where [?r :rel/kind :offers] [?r :rel/from ?m] [?m :entity/name ?mn]
                                 [?r :rel/to ?c] [?c :entity/name ?cn]]
                        db)))
          ":offers edge runs owner Module → owned PlugPoint")
      (is (= #{["Impl" "Backend"]}
             (set (cq/q '[:find ?mn ?cn
                          :where [?r :rel/kind :satisfies] [?r :rel/from ?m] [?m :entity/name ?mn]
                                 [?r :rel/to ?c] [?c :entity/name ?cn]]
                        db)))
          ":satisfies edge runs implementer Module → the PlugPoint it satisfies (owned elsewhere)"))))
