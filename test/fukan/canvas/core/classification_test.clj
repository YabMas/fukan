(ns fukan.canvas.core.classification-test
  "Stratum tests: direct-kind (depth-0) and the transitive refines*/kind-of/
   family-of closure. Also executes the dissolve test — a second classification
   declared purely as refinement data, queried with zero new machinery."
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.classification :as classification]
            [fukan.canvas.core.substrate.store :as store]))

(defn- node-with-tag
  "Datoms for a node named `nm` carrying primary tag-application `tag`."
  [nm tag]
  (let [uuid (random-uuid)]
    [{:entity/id uuid :entity/name nm}
     {:tagapp/id (str uuid "|" tag) :tagapp/node [:entity/id uuid] :tagapp/tag tag}]))

(defn- eid-named [db nm]
  (ffirst (d/q '[:find ?e :in $ ?n :where [?e :entity/name ?n]] db nm)))

;; A db mirroring the real projection: family super-tags as parent-less roots,
;; concrete tags refining them, plus a depth-2 chain for the dissolve test.
(def ^:private db
  (-> (store/create)
      (d/db-with [{:tagdef/tag :family/affordance}
                  {:tagdef/tag :canvas/invariant :tagdef/refines :family/affordance}
                  {:tagdef/tag :canvas/getter    :tagdef/refines :family/affordance}
                  ;; second classification — declared as data only:
                  {:tagdef/tag :ddd/entity         :tagdef/refines :family/affordance}
                  {:tagdef/tag :ddd/aggregate-root :tagdef/refines :ddd/entity}])
      (d/db-with (node-with-tag "Inv" :canvas/invariant))
      (d/db-with (node-with-tag "Get" :canvas/getter))
      (d/db-with (node-with-tag "Order" :ddd/aggregate-root))))

(deftest direct-kind-depth-0
  (testing "direct-kind fn returns the node's immediate tag"
    (is (= :canvas/invariant (classification/direct-kind db (eid-named db "Inv"))))
    (is (nil? (classification/direct-kind db 999999)) "no tag-application → nil")))

(deftest refines-closure-is-recursive
  (testing "refines* reaches transitive ancestors (proves datascript recursion)"
    (is (= #{[:ddd/aggregate-root] [:ddd/entity] [:family/affordance]}
           (set (d/q '[:find ?anc :in $ % ?t :where (refines* ?t ?anc)]
                     db classification/rules :ddd/aggregate-root)))
        "aggregate-root refines* itself, entity, and the family root")))

(deftest kind-of-transitive
  (testing "kind-of matches a node against any ancestor kind"
    (let [of-kind (fn [k] (set (map first (d/q '[:find ?n :in $ % ?k
                                                 :where (kind-of ?e ?k) [?e :entity/name ?n]]
                                               db classification/rules k))))]
      (is (= #{"Inv" "Get" "Order"} (of-kind :family/affordance)) "all three are affordances")
      (is (= #{"Inv"}   (of-kind :canvas/invariant)) "direct tag matches")
      (is (= #{"Order"} (of-kind :ddd/aggregate-root)))
      (is (= #{"Order"} (of-kind :ddd/entity)) "aggregate-root is-a entity, transitively"))))

(deftest family-of-single-valued
  (testing "family-of resolves each node to exactly its :family/* root"
    (is (= #{["Inv" :family/affordance]
             ["Get" :family/affordance]
             ["Order" :family/affordance]}
           (set (d/q '[:find ?n ?fam :in $ %
                       :where (family-of ?e ?fam) [?e :entity/name ?n]]
                     db classification/rules))))))

(deftest dissolve-test
  (testing "a SECOND classification (ddd/aggregate-root refines ddd/entity) needed
            only refinement data — zero new rules, zero query machinery"
    ;; The whole second axis was the two :ddd/* tagdef datoms above. Querying it
    ;; uses the same kind-of the family axis uses.
    (is (= #{"Order"}
           (set (map first (d/q '[:find ?n :in $ % ?k
                                  :where (kind-of ?e ?k) [?e :entity/name ?n]]
                                db classification/rules :ddd/entity))))
        "is-a-entity answered by the same kind-of, no consumer/rule change")))
