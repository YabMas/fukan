(ns fukan.agent.api-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [fukan.agent.api :as api]
            [fukan.infra.model :as infra-model]))

(defn load-fixture []
  (edn/read-string (slurp (io/resource "fukan/fixtures/agent/small_model.edn"))))

(defn with-fixture-model [f]
  (infra-model/set-model-for-test! (load-fixture))
  (f))

(use-fixtures :each with-fixture-model)

(deftest q-runs-datascript-over-the-substrate
  (testing "q is real Datascript d/q over the canvas substrate db"
    ;; addressing currency: resolve a known module by its stable-id
    (is (= #{["infra.server"]}
           (api/q '[:find ?id
                    :where [?e :entity/type :Module]
                           [?e :entity/stable-id "infra.server"]
                           [?e :entity/stable-id ?id]])))
    ;; precision the old EDB L0 lacked: invariants are distinguishable from
    ;; rules (both collapsed to :primitive/rule under the projected vocabulary)
    (let [invs (api/q '[:find ?id
                        :where [?e :affordance/role :canvas/invariant]
                               [?e :entity/stable-id ?id]])]
      (is (set? invs) "d/q returns a set of tuples")
      (is (pos? (count invs)))
      (is (every? #(string? (first %)) invs) "results carry stable-id strings"))))

(deftest primitives-all
  (testing "(primitives) returns the standard listing envelope"
    (let [r (api/primitives)]
      (is (= 4 (count (:rows r))))
      (is (= 4 (:total r)))
      (is (false? (:truncated? r)))
      (is (every? #(contains? % :id) (:rows r)))
      (is (every? #(contains? % :kind) (:rows r))))))

(deftest primitives-by-kind
  (testing "(primitives :kind :primitive/behaviour) filters"
    (let [r (api/primitives :kind :primitive/behaviour)]
      (is (= 2 (count (:rows r))))
      (is (every? #(= :primitive/behaviour (:kind %)) (:rows r))))))

(deftest primitives-truncation
  (testing ":truncated? true and :total N when limit exceeded"
    (let [r (api/primitives :limit 2)]
      (is (= 2 (count (:rows r))))
      (is (true? (:truncated? r)))
      (is (= 4 (:total r)))
      (is (zero? (:offset r))))))

(deftest primitives-offset-pagination
  (testing "successive pages via :limit + :offset cover the full set without overlap"
    (let [page1 (api/primitives :limit 2 :offset 0)
          page2 (api/primitives :limit 2 :offset 2)
          page3 (api/primitives :limit 2 :offset 4)
          ids-1 (set (map :id (:rows page1)))
          ids-2 (set (map :id (:rows page2)))]
      (is (= 2 (count (:rows page1))))
      (is (= 2 (count (:rows page2))))
      (is (empty? (:rows page3)))
      (is (true?  (:truncated? page1)))
      (is (false? (:truncated? page2)))
      (is (= 2 (:offset page2)))
      (is (empty? (set/intersection ids-1 ids-2))
          "offset shifts to the next slice — no overlap with previous page"))))

(deftest get-primitive-returns-full-detail
  (testing "get-primitive returns the full primitive map, not the summary"
    (let [p (api/get-primitive "behaviour:hex/core/r-mint")]
      (is (= :primitive/behaviour (:kind p)))
      (is (contains? p :rules))
      (is (= "mint" (:label p))))))

(deftest get-primitive-missing-returns-nil
  (testing "missing id returns nil"
    (is (nil? (api/get-primitive "behaviour:does-not-exist")))))

(deftest relations-all
  (testing "(relations) returns all edges in the model"
    (let [r (api/relations)]
      (is (= 5 (count (:rows r))))
      (is (= 5 (:total r))))))

(deftest relations-by-kind
  (testing "(relations :kind :relation/projects) filters to projects edges"
    (let [r (api/relations :kind :relation/projects)]
      (is (= 2 (count (:rows r)))))))

(deftest relations-by-validity
  (testing "(relations :kind :relation/projects :validity :absent) finds drift candidates"
    (let [r (api/relations :kind :relation/projects :validity :absent)]
      (is (= 1 (count (:rows r))))
      (is (= "behaviour:hex/core/r-mint"
             (-> r :rows first :from :id))))))

(deftest relations-by-from
  (testing "(relations :from id) filters edges originating at id"
    (let [r (api/relations :from "container:hex/core")]
      (is (every? #(= "container:hex/core"
                      (-> % :from :id)) (:rows r))))))

(deftest vocabulary-surfaces-canvas-tag-definitions
  (testing "vocabulary surfaces the registered canvas tag-definitions"
    (let [v      (api/vocabulary)
          by-tag (into {} (map (juxt :tag identity)) (:tags v))]
      ;; Representative canvas kinds are present, with their families.
      (is (contains? by-tag :canvas/function))
      (is (contains? by-tag :canvas/invariant))
      (is (contains? by-tag :canvas/record))
      (is (= :Affordance (:family (by-tag :canvas/function))))
      (is (= :Type       (:family (by-tag :canvas/record))))
      ;; Every entry carries a doc and an :in-use? flag.
      (is (every? string?            (map :doc (:tags v))))
      (is (every? #(contains? % :in-use?) (:tags v))))))

(deftest schema-for-kind
  (testing "(schema :kind :primitive/behaviour) surfaces attribute keys observed in fixture"
    (let [s (api/schema :kind :primitive/behaviour)]
      (is (contains? (set (:attributes s)) :rules))
      (is (contains? (set (:attributes s)) :label)))))

(deftest idioms-empty-on-fixture
  (testing "idioms returns empty vec when project layer has no entries"
    (is (vector? (api/idioms)))))

(deftest constraints-empty-on-fixture
  (testing "constraints returns empty vec on fixture (no constraint defs)"
    (is (vector? (api/constraints)))))

(deftest violations-empty-on-fixture
  (testing "violations returns empty vec on fixture"
    (is (vector? (api/violations)))))

(deftest drift-finds-absent-projections
  (testing "(drift) returns absent projections with their source primitive"
    (let [d (api/drift)]
      (is (= 1 (count d)))
      (is (= "behaviour:hex/core/r-mint" (-> d first :from :id)))
      (is (= :primitive/behaviour (-> d first :primitive :kind))))))

(deftest drift-equivalent-to-l1-form
  (testing "drift result set matches the L1 composition it documents"
    (let [drift-l2 (set (map (juxt :validity #(-> % :from :id))
                             (api/drift)))
          drift-l1 (set (map (juxt :validity #(-> % :from :id))
                             (:rows (api/relations :kind :relation/projects :validity :absent))))]
      (is (= drift-l1 drift-l2)))))

(deftest drift-filter-by-projection-kind
  (testing "(drift :projection-kind :clojure) returns only clojure-target drift"
    (is (= 1 (count (api/drift :projection-kind :clojure))))))

(deftest neighborhood-returns-primitive-and-one-hop
  (testing "(neighborhood id) returns primitive + outgoing + incoming + neighbor summaries"
    (let [n (api/neighborhood "container:hex/core")]
      (is (= "container:hex/core" (-> n :primitive :id)))
      (is (= 3 (count (:outgoing n))))
      (is (= 0 (count (:incoming n))))
      (is (= 3 (count (:neighbors n)))))))

(deftest artifacts-listing
  (testing "(artifacts) returns the standard envelope"
    (let [r (api/artifacts)]
      (is (= 3 (count (:rows r))))
      (is (= 3 (:total r))))))

(deftest artifacts-by-public
  (testing "(artifacts :public? true) filters to public functions only"
    (let [r (api/artifacts :public? true)]
      (is (= 2 (count (:rows r))))
      (is (every? :public? (:rows r))))))

(deftest artifacts-by-sub-case
  (testing "(artifacts :sub-case :code/function) filters by sub-case"
    (let [r (api/artifacts :sub-case :code/function)]
      (is (= 3 (count (:rows r)))))))

(deftest coverage-computes-public-fn-coverage
  (testing "(coverage) counts public functions covered by spec via :valid edges"
    (let [c (api/coverage)]
      ;; fixture: 2 public fns (burn covered, mint-helper unprojected), 1 private
      (is (= 2 (:total-public-functions c)))
      (is (= 1 (:covered c)))
      (is (= 1 (:unprojected c)))
      (is (= 0 (:expected-not-realised c)))
      (is (= 0.5 (:covered-ratio c)))
      (is (= 0.5 (:unprojected-ratio c))))))

(deftest neighborhood-missing-returns-nil
  (is (nil? (api/neighborhood "behaviour:does-not-exist"))))

;; -- Phase 7 Sprint 3 Task N: project-lens + scenario surfaces ----------------
;;
;; These tests touch the live canvas db (`canvas-source/build-canvas-db`) — no
;; small_model fixture stand-in is possible because the canvas db is a separate
;; substrate from the loaded Model. Ground-truth references come from
;; canvas/distributed/cluster.clj (NodeId value, Node record, get_node fn).

(deftest spec-projects-atomic-type-from-stable-id
  (testing "(spec \"<stable-id>\") returns a valid value-to-def projection"
    (let [p (api/spec "distributed.cluster/type/NodeId")]
      (is (= :clojure/value-to-def (:projection-kind p)))
      (is (= :clojure              (:lens-id p)))
      (is (= :Type                 (:model-element-kind p)))
      (is (= "distributed.cluster/type/NodeId" (:model-element-id p)))
      (is (= "NodeId"              (-> p :target :symbol)))
      (is (str/includes? (:template p) "(def ^:schema NodeId")))))

(deftest spec-projects-record-type-from-stable-id
  (testing "(spec \"<record stable-id>\") routes to type-to-malli"
    (let [p (api/spec "distributed.cluster/type/Node")]
      (is (= :clojure/type-to-malli (:projection-kind p)))
      (is (= :Type                  (:model-element-kind p)))
      (is (str/includes? (:template p) "(def ^:schema Node"))
      (is (str/includes? (:template p) ":map")))))

(deftest spec-projects-function-affordance-from-stable-id
  (testing "(spec \"<affordance stable-id>\") routes to function-to-defn"
    (let [p (api/spec "distributed.cluster/get_node")]
      (is (= :clojure/function-to-defn (:projection-kind p)))
      (is (= :Affordance               (:model-element-kind p)))
      (is (= "get-node"                (-> p :target :symbol)))
      (is (str/includes? (:template p) "(defn get-node")))))

(deftest spec-passes-through-pre-built-element-map
  (testing "spec round-trips an existing element map without canvas-db lookup"
    (let [el {:model-element-kind :Type
              :type-kind          :atomic
              :stable-id          "synthetic/type/Foo"
              :entity-name        "Foo"
              :module-coord       "synthetic"
              :doc                "Inline test element."}
          p  (api/spec el)]
      (is (= :clojure/value-to-def (:projection-kind p)))
      (is (= "synthetic/type/Foo"  (:model-element-id p))))))

(deftest spec-from-drift-finding-uses-first-offender
  (testing "spec accepts a canvas-drift finding and pulls its first offender's stable-id"
    (let [finding {:check     :inspect.drift/missing-implementation
                   :severity  :warning
                   :offenders [{:stable-id "distributed.cluster/type/NodeId"
                                :expected-symbol "NodeId"}]}
          p       (api/spec finding)]
      (is (= :clojure/value-to-def (:projection-kind p))))))

(deftest spec-unknown-stable-id-throws
  (testing "spec on an absent stable-id throws :element-not-found"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"no canvas entity"
                          (api/spec "no.such.module/type/Nope")))))

(deftest instruct-composes-drift-close
  (testing "(instruct id :code-side/drift-close) returns a full instruction"
    (let [i (api/instruct "distributed.cluster/type/NodeId" :code-side/drift-close)]
      (is (= :code-side/drift-close (:scenario-id i)))
      (is (= :clojure/value-to-def  (-> i :code-spec :projection-kind)))
      (is (map? (:scenario-context i)))
      (is (string? (:rendered i)))
      (is (str/includes? (:rendered i) "drift-close")))))

(deftest instruct-from-drift-finding-carries-finding-context
  (testing "instruct propagates a drift finding into the scenario context"
    (let [finding {:check     :inspect.drift/missing-implementation
                   :severity  :warning
                   :offenders [{:stable-id          "distributed.cluster/type/NodeId"
                                :expected-code-path "src/fukan/distributed/cluster.clj"
                                :expected-symbol    "NodeId"
                                :canvas-kind        :type}]}
          i (api/instruct finding :code-side/drift-close)]
      (is (= :code-side/drift-close (:scenario-id i)))
      (is (= "distributed.cluster/type/NodeId"
             (-> i :scenario-context :drift-finding :stable-id))))))

(deftest instruct-unknown-scenario-throws
  (testing "instruct on an unregistered scenario id throws :scenario-not-found"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"no scenario registered"
                          (api/instruct "distributed.cluster/type/NodeId"
                                        :code-side/never-registered)))))

(deftest canvas-projections-lists-registered-dispatch-keys
  (testing "(canvas-projections) surfaces every registered [lens dispatch-key] pair"
    (let [ps      (api/canvas-projections)
          keys-of (set (map (juxt :lens-id :dispatch-key) ps))]
      (is (>= (count ps) 6) "Phase 7 Sprint 3 ships >= 6 Clojure-lens projections")
      (is (every? #(contains? % :lens-id) ps))
      (is (every? #(contains? % :dispatch-key) ps))
      ;; Sanity: a few known projections must be present.
      (is (contains? keys-of [:clojure :Type/atomic]))
      (is (contains? keys-of [:clojure :Type/record]))
      (is (contains? keys-of [:clojure :canvas/function]))
      (is (contains? keys-of [:clojure :canvas/invariant]))
      (is (contains? keys-of [:clojure :canvas/rule]))
      (is (contains? keys-of [:clojure :canvas/event])))))

(deftest canvas-scenarios-lists-registered-scenarios
  (testing "(canvas-scenarios) surfaces every registered Layer-B scenario"
    (let [ss     (api/canvas-scenarios)
          ids-of (set (map :scenario-id ss))]
      (is (>= (count ss) 2) "Phase 7 Sprint 3 ships >= 2 scenarios")
      (is (every? #(contains? % :scenario-id) ss))
      (is (every? #(contains? % :description) ss))
      (is (every? #(contains? % :prompt-fragment) ss))
      (is (contains? ids-of :code-side/drift-close))
      (is (contains? ids-of :code-side/cold-write)))))
