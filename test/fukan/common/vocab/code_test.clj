(ns fukan.common.vocab.code-test
  "Module-dependency readings on the code grammar."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.structure :as s]
            [fukan.common.vocab.code.kind :as kind]
            [fukan.common.vocab.code.operation :as operation]
            [fukan.common.vocab.code.module :as module]
            [fukan.common.vocab.patterns.fulfilment :as fulfilment]
            [fukan.common.vocab.patterns.plug-point :as plug-point]
            [fukan.common.vocab.code.subsystem :as subsystem]))

(module/Module ^{:name "fx-impl"}  t-fx-impl  "a fixture module")
(module/Module ^{:name "fx-infra"} t-fx-infra "another fixture module")

;; ── module-dependency fixtures: a→b by a delegate edge; c adopts a Kind d owns ──
(kind/Kind DShape :string)
(operation/Operation ^{:name "b-op"} t-b-op "callee")
(operation/Operation ^{:name "a-op"} t-a-op {:delegates [t-b-op]})
;; c-op takes a DShape (a ref to a Kind owned by module D) → data-adoption dependency c→d
(operation/Operation ^{:name "c-op"} t-c-op {:signature [:=> [:catn [:x DShape]] :nil]})
(module/Module ^{:name "A"} t-mod-a {:child [t-a-op]})
(module/Module ^{:name "B"} t-mod-b {:child [t-b-op]})
(module/Module ^{:name "C"} t-mod-c {:child [t-c-op]})
(module/Module ^{:name "D"} t-mod-d {:child [DShape]})

;; …and E supplies a surface B owns: a fulfilment, the third way one module relies on another
(module/Module ^{:name "E"} t-mod-e "a satisfier — it owns nothing that calls B, and depends on it anyway")
(fulfilment/Fulfilment ^{:name "e-supplies-b-op"} t-ful-e {:satisfier t-mod-e :surface t-b-op})

(deftest module-dependencies-unions-calls-data-adoption-and-supply
  (testing "M depends on N via a delegate (call), via adopting a Kind N owns (data), or via
            supplying a surface N owns. The third leaves the MODULE itself rather than something
            it owns — a fulfilment is a claim a module makes about the whole of itself — and E
            below owns nothing at all, so its edge can have come from nothing else."
    (let [db (build/vars->cozo [#'DShape #'t-b-op #'t-a-op #'t-c-op #'t-ful-e
                               #'t-mod-a #'t-mod-b #'t-mod-c #'t-mod-d #'t-mod-e])
          deps (subsystem/module-dependencies db)]
      (is (contains? deps ["A" "B"]) "call dependency: A's op delegates to B's op")
      (is (contains? deps ["C" "D"]) "data-adoption: C's op adopts a Kind D owns")
      (is (contains? deps ["E" "B"]) "supply: E declares it fulfils an Operation B owns")
      (is (not (contains? deps ["A" "A"])) "no self-dependency"))))

;; ── conformance: a fulfilment is held to the :may-depend DAG like any other dependency ──
;; Without this the word would be a hole in the architecture exactly where it is most used — a
;; satisfier is usually in a different subsystem from the surface it supplies, which is what makes
;; the inversion worth declaring in the first place.
(declare t-sub-surface)
(subsystem/Subsystem ^{:name "sup-declared"}   t-sub-sup-ok  {:child [t-mod-e] :may-depend [t-sub-surface]})
(subsystem/Subsystem ^{:name "sup-undeclared"} t-sub-sup-bad {:child [t-mod-e]})
(subsystem/Subsystem ^{:name "surface-side"}   t-sub-surface {:child [t-mod-b]})

(defn- conformance-offenders [db]
  (->> (law/check db)
       (filter #(str/includes? (:law %) "cross-subsystem"))
       (mapcat :offenders)
       (map (comp :entity/name #(cq/entity db %) first))
       set))

(deftest a-declared-fulfilment-is-subject-to-may-depend-conformance
  (testing "no fulfilment may cross a subsystem boundary the architecture does not permit —
            otherwise a module could reach anywhere it liked by supplying a surface there"
    (let [vars [#'t-b-op #'t-ful-e #'t-mod-b #'t-mod-e #'t-sub-surface]]
      (is (= #{"E"} (conformance-offenders (build/vars->cozo (conj vars #'t-sub-sup-bad))))
          "the crossing MODULE is the offender, as it is for a call dependency")
      (is (empty? (conformance-offenders (build/vars->cozo (conj vars #'t-sub-sup-ok))))
          "…and declaring the edge settles it"))))

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

;; ── the plug-point seam: a PlugPoint names its :owner; `offers` is the derived converse ──
(module/Module ^{:name "Owner"} t-mod-owner)
(plug-point/PlugPoint ^{:name "Backend"} t-plug-point "a plug-point" {:owner t-mod-owner})

(deftest plug-point-names-its-owner-and-offers-derives
  (testing "the pattern names its participants — Module stays closed to the tier above it"
    (let [db (build/vars->cozo [#'t-plug-point #'t-mod-owner])]
      (is (= #{["Backend" "Owner"]}
             (set (cq/q '[:find ?cn ?mn
                          :where [?r :rel/kind :owner] [?r :rel/from ?c] [?c :entity/name ?cn]
                                 [?r :rel/to ?m] [?m :entity/name ?mn]]
                        db)))
          ":owner edge runs PlugPoint → its defining Module (authored on the pattern)")
      (is (= #{["Owner" "Backend"]}
             (set (cq/q '[:find ?mn ?cn :in $ %
                          :where (offers ?m ?c) [?m :entity/name ?mn] [?c :entity/name ?cn]]
                        db (s/vocab-rules))))
          "(offers ?m ?p) — the derived converse — reads at domain altitude"))))
