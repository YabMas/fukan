(ns fukan.canvas.core.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.classification :as classification]
            [fukan.canvas.core.substrate :as sub]
            [fukan.canvas.core.substrate.store :as store]
            [fukan.model.artifact :as a]
            [fukan.model.relations :as r]))

(deftest store-creation
  (testing "creates an empty store"
    (let [s (store/create)]
      (is (some? s))
      (is (empty? (store/all-modules s))))))

(deftest transact-module
  (testing "adds a Module and finds it"
    (let [s (-> (store/create)
                (d/db-with (classification/tagdef-datoms))
                (store/transact! (sub/module "accounts")))]
      (is (= 1 (count (store/all-modules s))))
      (is (= "accounts" (-> s store/all-modules first :name))))))

(deftest transact-affordance-with-module
  (testing "affordances-in finds Affordances via :module/child on the owning Module"
    (let [m   (sub/module "accounts")
          a   (sub/affordance "create" :role :canvas/function)
          mid (sub/id-of m)
          s   (-> (store/create)
                  (d/db-with (classification/tagdef-datoms))
                  (store/transact! m)
                  (store/transact! a)
                  (d/db-with [[:db/add [:entity/id mid]
                                       :module/child
                                       [:entity/id (sub/id-of a)]]]))]
      (is (= 1 (count (store/affordances-in s mid)))))))

(deftest store-persists-affordance-doc
  (testing ":affordance/doc lands in the store"
    (let [m (sub/module "accounts")
          a (sub/affordance "find-by-email"
              :doc "Look up an account.")
          s (-> (store/create)
                (store/transact! m)
                (store/transact! a))]
      (is (= [["find-by-email" "Look up an account."]]
             (vec (d/q '[:find ?n ?doc
                         :where [?e :entity/name ?n]
                                [?e :affordance/doc ?doc]]
                       s)))))))

(deftest store-persists-type-doc
  (testing ":type/doc lands in the store"
    (let [t (sub/type-record "Account" [["email" {:kind :atomic :name :String}]]
              :doc "Account record.")
          s (-> (store/create)
                (store/transact! t))]
      (is (= [["Account" "Account record."]]
             (vec (d/q '[:find ?n ?doc
                         :where [?e :entity/name ?n]
                                [?e :type/doc ?doc]]
                       s)))))))

(deftest children-of-module-query
  (testing "children-of-module returns [type name] pairs for all module children"
    (let [m   (sub/module "accounts")
          a   (sub/affordance "create" :role :canvas/function)
          t   (sub/type-record "Account" [])
          mid (sub/id-of m)
          s   (-> (store/create)
                  (d/db-with (classification/tagdef-datoms))
                  (store/transact! m)
                  (store/transact! a)
                  (store/transact! t)
                  (d/db-with [[:db/add [:entity/id mid] :module/child [:entity/id (sub/id-of a)]]
                               [:db/add [:entity/id mid] :module/child [:entity/id (sub/id-of t)]]]))]
      (is (= #{[:Affordance "create"] [:Type "Account"]}
             (set (store/children-of-module s mid)))))))

(deftest affordance-input-types-are-queryable
  (testing "an affordance with cross-module-typed inputs has those types as queryable datoms"
    (let [m (sub/module "evaluator")
          a (sub/affordance "evaluate_rules"
              :shape {:kind :arrow
                      :inputs {:kind :record
                               :fields [["rules" {:kind :list :elem {:kind :ref :target :ast/ConstraintRule}}]
                                        ["edb" {:kind :ref :target :derivations/EDB}]]}
                      :outputs {:kind :ref :target :derivations/EDB}})
          db (-> (store/create) (store/transact! m) (store/transact! a))]
      (is (= #{:ast/ConstraintRule :derivations/EDB}
             (set (map first (d/q '[:find ?t :where [?a :affordance/input-types ?t]] db)))))
      (is (= #{:derivations/EDB}
             (set (map first (d/q '[:find ?t :where [?a :affordance/output-types ?t]] db))))))))

(deftest affordance-without-shape-has-no-type-attrs
  (testing "a no-shape Affordance (invariant/rule) does NOT get input/output type datoms"
    (let [m (sub/module "constraint.evaluator")
          a (sub/affordance "StratifiedFixedPoint"
              :formal-expression "Always converges.")
          db (-> (store/create) (store/transact! m) (store/transact! a))]
      (is (empty? (d/q '[:find ?t :where [?a :affordance/input-types ?t]] db)))
      (is (empty? (d/q '[:find ?t :where [?a :affordance/output-types ?t]] db))))))

(deftest type-field-types-are-queryable
  (testing "a record Type with cross-module-typed fields has those types as queryable datoms"
    (let [t (sub/type-record "Phase4Result"
              [["model" {:kind :ref :target :model/Model}]
               ["violations" {:kind :list :elem {:kind :ref :target :agent/Violation}}]])
          db (-> (store/create) (store/transact! t))]
      (is (= #{:model/Model :agent/Violation}
             (set (map first (d/q '[:find ?ft :where [?ty :type/field-types ?ft]] db))))))))

(deftest atomic-type-has-no-field-types
  (testing "a Type with :kind :atomic has no :type/field-types"
    (let [t (sub/type-primitive "Stratum")
          db (-> (store/create) (store/transact! t))]
      (is (empty? (d/q '[:find ?ft :where [?ty :type/field-types ?ft]] db))))))

(deftest type-fields-tuples-atomic-and-optional
  (testing "a record Type produces :type/fields [field-name type-name] datoms"
    (let [t (sub/type-record "User"
              [["email" {:kind :atomic :name :String}]
               ["age" {:kind :optional :inner {:kind :atomic :name :Integer}}]])
          db (-> (store/create) (store/transact! t))]
      (is (= #{[:email :String] [:age :Integer]}
             (set (map first (d/q '[:find ?p :where [?ty :type/fields ?p]] db))))))))

(deftest type-fields-tuples-composite
  (testing "composite-shape fields emit one :type/fields datom per type-name in shape"
    (let [t (sub/type-record "Order"
              [["items" {:kind :list :elem {:kind :ref :target :Item}}]
               ["total" {:kind :atomic :name :Decimal}]
               ["meta"  {:kind :map
                         :key {:kind :atomic :name :String}
                         :val {:kind :ref :target :Bar}}]])
          db (-> (store/create) (store/transact! t))]
      (is (= #{[:items :Item]
               [:total :Decimal]
               [:meta :String]
               [:meta :Bar]}
             (set (map first (d/q '[:find ?p :where [?ty :type/fields ?p]] db))))))))

(deftest type-fields-query-distinguishes-by-field-and-type
  (testing "query 'all records with a :status field of type :String' returns only the matching record"
    (let [r1 (sub/type-record "WithStringStatus"
               [["status" {:kind :atomic :name :String}]])
          r2 (sub/type-record "WithEnumStatus"
               [["status" {:kind :ref :target :OrderStatus}]])
          db (-> (store/create)
                 (store/transact! r1)
                 (store/transact! r2))]
      (is (= #{"WithStringStatus"}
             (set (map first
                       (d/q '[:find ?n
                              :where [?ty :type/fields [:status :String]]
                                     [?ty :entity/name ?n]]
                            db))))))))

(deftest atomic-type-has-no-fields-tuples
  (testing "a Type with :kind :atomic has no :type/fields datoms"
    (let [t (sub/type-primitive "Stratum")
          db (-> (store/create) (store/transact! t))]
      (is (empty? (d/q '[:find ?p :where [?ty :type/fields ?p]] db))))))

;; ── Phase-6 content as datoms (Step B) ─────────────────────────────────────

(deftest artifact-datoms-code-function
  (testing "a Code.Function artifact round-trips its queryable attrs"
    (let [art (a/make-code-function "clojure" "fukan.infra.server/start-server" nil true)
          db  (-> (store/create) (d/db-with (store/artifact->datoms art)))]
      (is (= #{["fukan.infra.server/start-server" :artifact/code :code/function "clojure" true]}
             (d/q '[:find ?qn ?c ?sc ?lang ?pub
                    :where [?a :artifact/qualified-name ?qn]
                           [?a :artifact/case ?c]
                           [?a :artifact/sub-case ?sc]
                           [?a :artifact/language ?lang]
                           [?a :artifact/public ?pub]]
                  db))))))

(deftest artifact-datoms-public-tri-state-absent
  (testing "public? absent => no :artifact/public datom (distinct from public? false)"
    (let [art (a/make-code-function "clojure" "fukan.x/y")          ; public? nil
          db  (-> (store/create) (d/db-with (store/artifact->datoms art)))]
      (is (empty? (d/q '[:find ?p :where [?a :artifact/public ?p]] db))))))

(deftest artifact-datoms-data-structure-fields-and-source
  (testing "a Code.DataStructure artifact stores pr-str'd fields + source location"
    (let [art (-> (a/make-code-data-structure "clojure" "fukan.infra.server/ServerOpts"
                                              {:file "src/fukan/infra/server.clj" :line 12})
                  (assoc-in [:sub :fields] [[:port [:maybe :int]]]))
          db  (-> (store/create) (d/db-with (store/artifact->datoms art)))]
      (is (= "src/fukan/infra/server.clj"
             (ffirst (d/q '[:find ?f :where [?a :artifact/source-file ?f]] db))))
      (is (= 12 (ffirst (d/q '[:find ?l :where [?a :artifact/source-line ?l]] db))))
      (is (= #{(pr-str [:port [:maybe :int]])}
             (set (map first (d/q '[:find ?fl :where [?a :artifact/fields ?fl]] db))))))))

(deftest artifact-id-deterministic-upserts
  (testing "re-transacting the same artifact upserts to a single entity"
    (let [art (a/make-code-function "clojure" "fukan.x/y" nil true)
          db  (-> (store/create)
                  (d/db-with (store/artifact->datoms art))
                  (d/db-with (store/artifact->datoms art)))]
      (is (= 1 (count (d/q '[:find ?a :where [?a :artifact/id _]] db)))))))

(deftest edge-datoms-projects-resolves-endpoints
  (testing "a reified projects edge links primitive entity → artifact entity with metadata"
    (let [m            (sub/module "infra.server")
          aff          (sub/affordance "start_server")
          stable       "infra.server/start_server"
          art          (a/make-code-function "clojure" "fukan.infra.server/start-server" nil true)
          edge         (-> (r/make-edge :relation/projects
                                        (r/primitive-ref stable)
                                        (r/artifact-ref (a/artifact-identity art))
                                        {:projection-kind :projection-kind/operation})
                           (assoc :validity :valid))
          stable->uuid {stable (sub/id-of aff)}
          db           (-> (store/create)
                           (store/transact! m)
                           (store/transact! aff)
                           (d/db-with (store/artifact->datoms art))
                           (d/db-with (store/edge->datoms edge stable->uuid)))]
      (is (= #{[:relation/projects :projection-kind/operation :valid
                "start_server" "fukan.infra.server/start-server"]}
             (d/q '[:find ?k ?pk ?v ?fn ?qn
                    :where [?e :edge/kind ?k]
                           [?e :edge/projection-kind ?pk]
                           [?e :edge/validity ?v]
                           [?e :edge/from ?from]
                           [?from :entity/name ?fn]
                           [?e :edge/to ?art]
                           [?art :artifact/qualified-name ?qn]]
                  db))))))

(deftest edge-datoms-unresolved-from-drops
  (testing "edge->datoms returns [] when the from stable-id doesn't resolve"
    (let [art  (a/make-code-function "clojure" "fukan.x/y")
          edge (r/make-edge :relation/projects
                            (r/primitive-ref "missing/thing")
                            (r/artifact-ref (a/artifact-identity art))
                            {:projection-kind :projection-kind/operation})]
      (is (= [] (store/edge->datoms edge {}))))))
