(ns demos.access-control.access-control-test
  "Regression for the access-control demo: the document-approval policy is
   well-formed, and each kind of ill-formedness — an inheritance cycle, a
   separation-of-duties violation reached through inheritance, a permission whose
   action is not an Action — is caught. Run via `clj -M:demos`."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [demos.access-control.model.rbac :as rbac]
            [demos.access-control.vocab.core
             :refer [Action Resource Permission Role]]))

(defn- laws [db] (set (map :law (s/check db))))

(deftest rbac-policy-is-well-formed
  (testing "the document-approval policy — diamond inheritance, SoD declared but not violated — satisfies every law"
    (is (empty? (s/check (rbac/build))))))

(deftest inheritance-cycle-is-caught
  (testing "a role that transitively inherits itself trips the acyclic-hierarchy law"
    (let [db (s/with-structures
               (s/within-module "ac"
                 (Role "a" (inherits b))     ; forward ref; cycle authored via two-pass
                 (Role "b" (inherits a))))]
      (is (contains? (laws db) "no cycle in the role inheritance hierarchy")))))

(deftest separation-of-duties-violation-through-inheritance-is-caught
  (testing "a role that inherits two roles whose combined effective permissions conflict is caught"
    (let [db (s/with-structures
               (s/within-module "ac"
                 (Action "submit") (Action "approve")
                 (Resource "Report")
                 (Permission "submit-report"  (action submit)  (resource Report)
                                              (conflicts-with approve-report))
                 (Permission "approve-report" (action approve) (resource Report))
                 (Role "author"   (grants submit-report))
                 (Role "approver" (grants approve-report))
                 ;; `lead` holds neither permission directly — both arrive through
                 ;; inheritance, so this exercises grants* transitivity.
                 (Role "lead" (inherits author approver))))]
      (is (contains? (laws db) "no role holds two conflicting permissions (separation of duties)")))))

(deftest permission-action-must-be-an-action
  (testing "a permission whose :action targets a Resource, not an Action, is caught"
    (let [db (s/with-structures
               (s/within-module "ac"
                 (Resource "Report")
                 ;; :action points at a Resource — wrong target type
                 (Permission "bad" (action Report) (resource Report))))]
      (is (contains? (laws db) "Permission.action target must be a Action")))))
