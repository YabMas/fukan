(ns lib.code-test
  "Module roles + module-dependency readings on the lib.code grammar."
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.model.pipeline :as pipeline]
            [lib.code :as code]))

;; a fixture abstract concept (a portrait — no instances), realized by a module
(defstructure FxConcept "A fixture abstract concept (portrait).")

(code/Module ^{:name "fx-impl"}  t-fx-impl  {:realizes FxConcept})
(code/Module ^{:name "fx-infra"} t-fx-infra "a module that realizes no concept")

(deftest realizes-resolves-symbol-to-qualified-tag
  (testing "the :realizes symbol is rewritten to the concept's qualified tag string"
    (let [db (a/assemble-vars [#'t-fx-impl])
          m  (ffirst (d/q '[:find ?m :where [?m :entity/name "fx-impl"]] db))]
      (is (= ":lib.code-test/FxConcept" (:val/realizes (d/entity db m)))
          "the hook resolves FxConcept → its defining-ns+name tag"))))

;; ── module-dependency fixtures: a→b by a delegate edge; c adopts a Kind d owns ──
(code/Kind DShape :string)
(code/Operation ^{:name "b-op"} t-b-op "callee")
(code/Operation ^{:name "a-op"} t-a-op {:delegates [t-b-op]})
;; c-op takes a DShape (a ref to a Kind owned by module D) → data-adoption dependency c→d
(code/Operation ^{:name "c-op"} t-c-op {:signature [:=> [:catn [:x DShape]] :nil]})
(code/Module ^{:name "A"} t-mod-a {:exposes [t-a-op]})
(code/Module ^{:name "B"} t-mod-b {:exposes [t-b-op]})
(code/Module ^{:name "C"} t-mod-c {:exposes [t-c-op]})
(code/Module ^{:name "D"} t-mod-d {:owns [DShape]})

(deftest module-dependencies-unions-calls-and-data-adoption
  (testing "M depends on N via a delegate (call) OR via adopting a Kind N owns (data)"
    (let [db (a/assemble-vars [#'DShape #'t-b-op #'t-a-op #'t-c-op
                               #'t-mod-a #'t-mod-b #'t-mod-c #'t-mod-d])
          deps (code/module-dependencies db)]
      (is (contains? deps ["A" "B"]) "call dependency: A's op delegates to B's op")
      (is (contains? deps ["C" "D"]) "data-adoption: C's op adopts a Kind D owns")
      (is (not (contains? deps ["A" "A"])) "no self-dependency"))))

(deftest modules-by-role-groups-by-realized-concept-tag
  (testing "modules group by their :realizes tag; un-roled modules under :infrastructure"
    (let [db (a/assemble-vars [#'t-fx-impl #'t-fx-infra])]
      (is (= {":lib.code-test/FxConcept" #{"fx-impl"} :infrastructure #{"fx-infra"}}
             (code/modules-by-role db))))))

(deftest fukan-architecture-modules-carry-their-faculty-roles
  (testing "the six faculty roles sit on fukan's realizing architecture modules"
    (let [roles (code/modules-by-role (pipeline/build-model nil))]
      (is (= #{"core-structure"} (roles ":canvas.subject/Model")))
      (is (= #{"canvas-source" "target-clojure"} (roles ":canvas.subject/Source")))
      (is (= #{"probes" "target-correspondence"} (roles ":canvas.subject/Lens")))
      (is (= #{"materialize"} (roles ":canvas.subject/Projection"))))))

;; ── Subsystem: clusters Modules + declares the :may-depend DAG (self-reference) ──
(declare t-sub-b)
(code/Subsystem ^{:name "sub-a"} t-sub-a {:child [t-fx-impl] :may-depend [t-sub-b]})
(code/Subsystem ^{:name "sub-b"} t-sub-b {:child [t-fx-infra]})

(deftest subsystem-clusters-modules-and-declares-may-depend
  (testing "a Subsystem owns Modules via :child and declares :may-depend to another Subsystem"
    (let [db (a/assemble-vars [#'t-fx-impl #'t-fx-infra #'t-sub-a #'t-sub-b])
          a  (ffirst (d/q '[:find ?s :where [?s :entity/name "sub-a"]] db))]
      (is (= #{"fx-impl"}
             (set (d/q '[:find [?mn ...] :in $ ?a
                         :where [?r :rel/from ?a] [?r :rel/kind :child] [?r :rel/to ?m] [?m :entity/name ?mn]]
                       db a)))
          ":child edges reach the clustered Modules")
      (is (= #{"sub-b"}
             (set (d/q '[:find [?tn ...] :in $ ?a
                         :where [?r :rel/from ?a] [?r :rel/kind :may-depend] [?r :rel/to ?t] [?t :entity/name ?tn]]
                       db a)))
          ":may-depend is a self-reference to another Subsystem (mirrors Operation :delegates)"))))
