(ns demos.access-control.access-control-test
  "Regression for the access-control demo: the document-approval policy is
   well-formed, and each kind of ill-formedness — an inheritance cycle, a
   separation-of-duties violation reached through inheritance, a permission whose
   action is not an Action — is caught. Run via `clj -M:demos`."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.core.assemble :as a]
            [demos.access-control.model.rbac :as rbac]
            [demos.access-control.vocab.core
             :refer [Action Resource Permission Role]]))

(defn- laws [db] (set (map :law (s/check db))))

(deftest rbac-policy-is-well-formed
  (testing "the document-approval policy — diamond inheritance, SoD declared but not violated — satisfies every law"
    (is (empty? (s/check (rbac/build))))))

;; ── case 1: inheritance cycle — a → b → a ────────────────────────────────────
;; Both roles are used as targets before their own defs, so both need forward
;; declarations. Var-capture resolves at assemble time.

(declare c1-role-a c1-role-b)
(def c1-role-a (Role "a" (inherits c1-role-b)))
(def c1-role-b (Role "b" (inherits c1-role-a)))

(deftest inheritance-cycle-is-caught
  (testing "a role that transitively inherits itself trips the acyclic-hierarchy law"
    (let [db (a/assemble-vars [#'c1-role-a #'c1-role-b])]
      (is (contains? (laws db) "no cycle in the role inheritance hierarchy")))))

;; ── case 2: separation-of-duties violation through inheritance ────────────────
;; `c2-lead` holds neither conflicting permission directly — both arrive via
;; inheritance, exercising grants* transitivity.

(def c2-submit         (Action "submit"))
(def c2-approve        (Action "approve"))
(def c2-report         (Resource "Report"))
(declare c2-approve-report)
(def c2-submit-report  (Permission "submit-report"  (action c2-submit)  (resource c2-report)
                                                     (conflicts-with c2-approve-report)))
(def c2-approve-report (Permission "approve-report" (action c2-approve) (resource c2-report)))
(def c2-author         (Role "author"   (grants c2-submit-report)))
(def c2-approver       (Role "approver" (grants c2-approve-report)))
(def c2-lead           (Role "lead"     (inherits c2-author c2-approver)))

(deftest separation-of-duties-violation-through-inheritance-is-caught
  (testing "a role that inherits two roles whose combined effective permissions conflict is caught"
    (let [db (a/assemble-vars [#'c2-submit #'c2-approve #'c2-report
                               #'c2-submit-report #'c2-approve-report
                               #'c2-author #'c2-approver #'c2-lead])]
      (is (contains? (laws db) "no role holds two conflicting permissions (separation of duties)")))))

;; ── case 3: permission action must be an Action ───────────────────────────────
;; :action points at a Resource — wrong target type.

(def c3-report (Resource "Report"))
(def c3-bad    (Permission "bad" (action c3-report) (resource c3-report)))

(deftest permission-action-must-be-an-action
  (testing "a permission whose :action targets a Resource, not an Action, is caught"
    (let [db (a/assemble-vars [#'c3-report #'c3-bad])]
      (is (contains? (laws db) "Permission.action target must be a Action")))))
