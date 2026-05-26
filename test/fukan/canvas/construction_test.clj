(ns fukan.canvas.construction-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record value exports]]
            [fukan.canvas.core.substrate.store :as store]))

(deftest function-lift-produces-affordance
  (testing "(function …) produces a callable Affordance inside a Module"
    (let [db (h/with-canvas
               (h/within-module "accounts"
                 (function "find-by-email"
                   "Look up an account by email."
                   (takes [email :String])
                   (gives :Account))))]
      (is (= 1 (count (store/all-modules db)))))))

(deftest function-lift-rejects-unknown-form
  (testing "unknown form raises diagnostic"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"`returns` is not a body form of `function`"
          (h/with-canvas
            (h/within-module "x"
              (function "f" "doc."
                (takes [a :String])
                (returns :Bool))))))))

(deftest record-lift-produces-type
  (testing "(record …) produces a Type owned by the enclosing module"
    (let [db (h/with-canvas
               (h/within-module "accounts"
                 (record "Account"
                   "An account record."
                   (field email :String)
                   (field name :String))))
          mid (ffirst (d/q '[:find ?id :where [?e :entity/type :Module] [?e :entity/id ?id]] db))]
      (is (= 1 (count (store/all-modules db))))
      (is (= #{[:Type "Account"]} (set (store/children-of-module db mid)))))))

(deftest value-lift-creates-opaque-type
  (testing "(value …) produces atomic Types owned by the enclosing module"
    (let [db (h/with-canvas
               (h/within-module "constraint.evaluator"
                 (value "Stratum" "An opaque stratification level.")
                 (value "Binding" "A logical-variable binding.")))
          types (d/q '[:find ?n :where [?e :entity/type :Type] [?e :entity/name ?n]] db)
          mid   (ffirst (d/q '[:find ?id :where [?e :entity/type :Module] [?e :entity/id ?id]] db))]
      (is (= 2 (count types)))
      (is (= #{"Stratum" "Binding"} (set (map first types))))
      (is (= #{[:Type "Stratum"] [:Type "Binding"]} (set (store/children-of-module db mid)))))))

(deftest record-accepts-optional-fields
  (testing "(field port (optional :Integer)) works"
    (let [db (h/with-canvas
               (h/within-module "infra.server"
                 (record "ServerOpts" "Server config."
                   (field port (optional :Integer))
                   (field host :String))))]
      ;; The Type is in the store
      (is (= 1 (count (d/q '[:find ?n
                             :where [?e :entity/type :Type]
                                    [?e :entity/name "ServerOpts"]
                                    [?e :entity/name ?n]]
                           db)))))))

(deftest function-accepts-list-takes
  (testing "(takes [rules (list-of :ConstraintRule)]) works"
    (let [db (h/with-canvas
               (h/within-module "constraint.evaluator"
                 (function "evaluate" "Evaluate constraint rules over EDB."
                   (takes [rules (list-of :ConstraintRule)] [edb :EDB])
                   (gives :Model))))]
      (is (= 1 (count (d/q '[:find ?n
                             :where [?e :entity/type :Affordance]
                                    [?e :entity/name "evaluate"]
                                    [?e :entity/name ?n]]
                           db)))))))

(deftest function-with-cross-module-takes-creates-references
  (testing "(takes [rules (list-of :ast/ConstraintRule)]) creates a :references Relation"
    (let [db (h/with-canvas
               (h/within-module "constraint.evaluator"
                 (function "evaluate_rules" "Evaluate rules over EDB."
                   (takes [rules (list-of :ast/ConstraintRule)]
                          [edb :derivations/EDB])
                   (gives :Model))))
          refs (d/q '[:find ?to
                      :where [_ :references ?to]]
                    db)]
      (is (>= (count refs) 2))
      (let [targets (set (map first refs))]
        (is (contains? targets :ast/ConstraintRule))
        (is (contains? targets :derivations/EDB))))))

(deftest record-with-cross-module-field-creates-references
  (testing "(field model :model/Model) creates a :references Relation"
    (let [db (h/with-canvas
               (h/within-module "validation.phase4"
                 (record "Phase4Result" "Result of phase 4 validation."
                   (field model :model/Model)
                   (field violations (list-of :agent/Violation)))))
          refs (d/q '[:find ?to
                      :where [_ :references ?to]]
                    db)
          targets (set (map first refs))]
      (is (contains? targets :model/Model))
      (is (contains? targets :agent/Violation)))))

(deftest exports-tags-listed-names
  (testing "(exports …) tags the listed declarations with :exported"
    (let [db (h/with-canvas
               (h/within-module "infra.server"
                 (record "ServerOpts" "Server config." (field port :Integer))
                 (record "ServerInfo" "Server info."  (field uptime :Integer))
                 (function "start_server" "Start the server." (gives :Unit))
                 (exports ServerOpts ServerInfo start_server)))
          tagged (d/q '[:find ?n
                        :where [?e :entity/tag :exported]
                               [?e :entity/name ?n]]
                      db)]
      (is (= #{"ServerOpts" "ServerInfo" "start_server"} (set (map first tagged)))))))

(deftest exports-tolerates-unknown-names
  (testing "exports for a name not declared in this module is a silent no-op"
    ;; Don't want exports to throw on a typo — just doesn't tag anything.
    ;; (A future linter could warn, but the canvas mechanism shouldn't fail.)
    (let [db (h/with-canvas
               (h/within-module "infra.server"
                 (record "ServerOpts" "." (field port :Integer))
                 (exports ServerOpts NonexistentName)))
          tagged (d/q '[:find ?n
                        :where [?e :entity/tag :exported]
                               [?e :entity/name ?n]]
                      db)]
      ;; Only ServerOpts should be tagged
      (is (= #{"ServerOpts"} (set (map first tagged)))))))

(deftest function-persists-doc
  (testing "(function …) stores the docstring in :affordance/doc"
    (let [db (h/with-canvas
               (h/within-module "accounts"
                 (function "find-by-email"
                   "Look up an account by email."
                   (takes [email :String])
                   (gives :Account))))
          rows (d/q '[:find ?n ?doc
                      :where [?e :entity/type :Affordance]
                             [?e :entity/name ?n]
                             [?e :affordance/doc ?doc]]
                    db)]
      (is (= [["find-by-email" "Look up an account by email."]] (vec rows))))))

(deftest record-persists-doc
  (testing "(record …) stores the docstring in :type/doc"
    (let [db (h/with-canvas
               (h/within-module "accounts"
                 (record "Account"
                   "An account record."
                   (field email :String)
                   (field name :String))))
          rows (d/q '[:find ?n ?doc
                      :where [?e :entity/type :Type]
                             [?e :entity/name ?n]
                             [?e :type/doc ?doc]]
                    db)]
      (is (= [["Account" "An account record."]] (vec rows))))))
