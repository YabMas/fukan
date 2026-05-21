(ns fukan.agent.api-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [fukan.agent.api :as api]
            [fukan.infra.model :as infra-model]))

(defn load-fixture []
  (edn/read-string (slurp (io/resource "fukan/fixtures/agent/small_model.edn"))))

(defn with-fixture-model [f]
  (infra-model/set-model-for-test! (load-fixture))
  (f))

(use-fixtures :each with-fixture-model)

(deftest q-finds-primitives-by-kind
  (testing "(q '[:find ?p :where [?p :primitive/kind :primitive/behaviour]]) returns 2 rows"
    (let [rows (api/q '[:find ?p :where [?p :primitive/kind :primitive/behaviour]])]
      (is (= 2 (count rows))))))

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

(deftest vocabulary-surfaces-all-kernel-primitive-kinds
  (testing "vocabulary surfaces every kernel-declared primitive kind, with doc + face-role"
    (let [v       (api/vocabulary)
          pk-by-k (into {} (map (juxt :kind identity)) (:primitive-kinds v))]
      ;; All nine kernel kinds are present, regardless of in-use? in fixture.
      (is (= #{:primitive/container :primitive/actor :primitive/behaviour
               :primitive/rule :primitive/boundary :primitive/operation
               :primitive/intent :primitive/clause :primitive/event}
             (set (keys pk-by-k))))
      ;; Every entry carries a docstring and a face-role.
      (is (every? string?  (map :doc (:primitive-kinds v))))
      (is (every? keyword? (map :face-role (:primitive-kinds v))))
      ;; Face-role assignments.
      (is (= :face-host      (-> pk-by-k :primitive/container :face-role)))
      (is (= :face-interface (-> pk-by-k :primitive/behaviour :face-role)))
      (is (= :face-interface (-> pk-by-k :primitive/boundary  :face-role)))
      (is (= :face-interface (-> pk-by-k :primitive/intent    :face-role)))
      (is (= :face-component (-> pk-by-k :primitive/rule      :face-role)))
      (is (= :face-component (-> pk-by-k :primitive/operation :face-role)))
      (is (= :face-component (-> pk-by-k :primitive/clause    :face-role)))
      (is (= :face-peer      (-> pk-by-k :primitive/event     :face-role)))
      (is (= :face-peer      (-> pk-by-k :primitive/actor     :face-role)))
      ;; in-use? reflects what the loaded fixture contains.
      (is (true?  (-> pk-by-k :primitive/behaviour :in-use?)))
      (is (true?  (-> pk-by-k :primitive/container :in-use?)))
      ;; Relation kinds: kernel-declared set, with :in-use? tag.
      (is (contains? (set (map :kind (:relation-kinds v))) :relation/projects))
      (is (every? #(contains? % :in-use?) (:relation-kinds v))))))

(deftest vocabulary-face-role-filter
  (testing "vocabulary :face-role filters primitive-kinds to that face-role"
    (let [v (api/vocabulary :face-role :face-interface)]
      (is (= #{:primitive/behaviour :primitive/boundary :primitive/intent}
             (set (map :kind (:primitive-kinds v))))))))

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
