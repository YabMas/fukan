(ns fukan.canvas.construction-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.classification :as classification]
            [datascript.core :as d]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record value exports]]
            [fukan.canvas.vocab.behavioral :refer [rule]]
            [fukan.canvas.vocab.event :refer [event]]
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

(deftest function-lift-rejects-truly-unknown-form
  (testing "a truly unknown form name raises diagnostic"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"`bogus` is not a body form of `function`"
          (h/with-canvas
            (h/within-module "x"
              (function "f" "doc."
                (takes [a :String])
                (bogus :something))))))))

(deftest record-lift-produces-type
  (testing "(record …) produces a Type owned by the enclosing module"
    (let [db (h/with-canvas
               (h/within-module "accounts"
                 (record "Account"
                   "An account record."
                   (field email :String)
                   (field name :String))))
          mid (ffirst (d/q '[:find ?id :in $ % ?fam :where (kind-of ?e ?fam) [?e :entity/id ?id]] db classification/rules :family/module))]
      (is (= 1 (count (store/all-modules db))))
      (is (= #{[:Type "Account"]} (set (store/children-of-module db mid)))))))

(deftest value-lift-creates-opaque-type
  (testing "(value …) produces atomic Types owned by the enclosing module"
    (let [db (h/with-canvas
               (h/within-module "constraint.evaluator"
                 (value "Stratum" "An opaque stratification level.")
                 (value "Binding" "A logical-variable binding.")))
          types (d/q '[:find ?n :in $ % ?fam :where (kind-of ?e ?fam) [?e :entity/name ?n]] db classification/rules :family/type)
          mid   (ffirst (d/q '[:find ?id :in $ % ?fam :where (kind-of ?e ?fam) [?e :entity/id ?id]] db classification/rules :family/module))]
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
                             :in $ % ?fam
                             :where (kind-of ?e ?fam)
                                    [?e :entity/name "ServerOpts"]
                                    [?e :entity/name ?n]]
                           db classification/rules :family/type)))))))

(deftest function-accepts-list-takes
  (testing "(takes [rules (list-of :ConstraintRule)]) works"
    (let [db (h/with-canvas
               (h/within-module "constraint.evaluator"
                 (function "evaluate" "Evaluate constraint rules over EDB."
                   (takes [rules (list-of :ConstraintRule)] [edb :EDB])
                   (gives :Model))))]
      (is (= 1 (count (d/q '[:find ?n
                             :in $ % ?fam
                             :where (kind-of ?e ?fam)
                                    [?e :entity/name "evaluate"]
                                    [?e :entity/name ?n]]
                           db classification/rules :family/affordance)))))))

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
  (testing "(exports …) tags the listed declarations with :canvas/exported"
    (let [db (h/with-canvas
               (h/within-module "infra.server"
                 (record "ServerOpts" "Server config." (field port :Integer))
                 (record "ServerInfo" "Server info."  (field uptime :Integer))
                 (function "start_server" "Start the server." (gives :Unit))
                 (exports ServerOpts ServerInfo start_server)))
          tagged (d/q '[:find ?n
                        :where [?e :entity/tag :canvas/exported]
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
                        :where [?e :entity/tag :canvas/exported]
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
                      :where [?e :affordance/doc ?doc]
                             [?e :entity/name ?n]]
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
                      :where [?e :type/doc ?doc]
                             [?e :entity/name ?n]]
                    db)]
      (is (= [["Account" "An account record."]] (vec rows))))))

(deftest function-with-triggers-creates-relation
  (testing "(triggers RuleName) emits a :triggers Relation from function to rule"
    (let [db (h/with-canvas
               (h/within-module "validation.phase4"
                 (rule "RunPhase4"
                   "Phase 4 entry point."
                   (when RunPhase4 (model :model/Model)))
                 (function "run" "Run the sub-phases."
                   (takes [model :model/Model])
                   (gives :Phase4Result)
                   (triggers RunPhase4))))
          relations (d/q '[:find ?from-name ?to-name
                            :in $ %
                            :where (direct-kind ?from :canvas/function)
                                   [?from :entity/name ?from-name]
                                   [?from :triggers ?to]
                                   [?to :entity/name ?to-name]]
                          db classification/rules)]
      (is (= [["run" "RunPhase4"]] (vec relations))))))

(deftest function-with-emits-creates-relation
  (testing "(emits EventName) emits an :emits Relation from function to event"
    (let [db (h/with-canvas
               (h/within-module "event-driven.payment"
                 (event "PaymentSucceeded"
                   "The payment was successfully processed."
                   (payload [payment_id :String]
                            [order_id   :String]))
                 (function "process_payment" "Attempt to process a payment."
                   (takes [payment :Payment])
                   (gives :Payment)
                   (emits PaymentSucceeded))))
          relations (d/q '[:find ?from-name ?to-name
                            :in $ %
                            :where (direct-kind ?from :canvas/function)
                                   [?from :entity/name ?from-name]
                                   [?from :emits ?to]
                                   [?to :entity/name ?to-name]]
                          db classification/rules)]
      (is (= [["process_payment" "PaymentSucceeded"]] (vec relations))))))

(deftest function-with-multiple-emits-creates-multiple-relations
  (testing "(emits …) is repeatable — one Relation per emitted event"
    (let [db (h/with-canvas
               (h/within-module "event-driven.payment"
                 (event "PaymentSucceeded"
                   "The payment was successfully processed."
                   (payload [payment_id :String]))
                 (event "PaymentFailed"
                   "The payment was declined or failed."
                   (payload [payment_id :String]
                            [reason     :String]))
                 (function "process_payment" "Attempt to process a payment."
                   (takes [payment :Payment])
                   (gives :Payment)
                   (emits PaymentSucceeded)
                   (emits PaymentFailed))))
          relations (d/q '[:find ?from-name ?to-name
                           :where [?from :entity/name ?from-name]
                                  [?from :emits ?to]
                                  [?to :entity/name ?to-name]]
                         db)]
      (is (= #{["process_payment" "PaymentSucceeded"]
               ["process_payment" "PaymentFailed"]}
             (set relations))))))

(deftest function-with-returns-label-is-queryable
  (testing "(returns \"post.result\") populates :affordance/returns-label"
    (let [db (h/with-canvas
               (h/within-module "validation.phase4"
                 (function "run" "Run the sub-phases."
                   (takes [model :model/Model])
                   (gives :Phase4Result)
                   (returns "post.result"))))]
      (is (= [["run" "post.result"]]
             (vec (d/q '[:find ?n ?label
                          :where [?a :entity/name ?n]
                                 [?a :affordance/returns-label ?label]]
                        db)))))))
