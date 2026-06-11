(ns demos.access-control.model.rbac
  "A document-approval RBAC policy modelled with the access-control vocabulary:

     actions   : read, submit, approve
     resources : Report, Budget
     permissions (action,resource):
       read-report, read-budget, submit-report, approve-report, approve-budget
       — submit-report ⊗ approve-report  (separation of duties)
     roles:
       viewer   : read-report, read-budget
       author   : inherits viewer + submit-report
       approver : inherits viewer + approve-report, approve-budget

   `viewer` is a diamond apex (both author and approver inherit it). No role holds
   both submit-report and approve-report, so SoD is satisfied. The SoD conflict is
   declared as a FORWARD reference (submit-report :conflicts-with approve-report,
   declared first), resolved by var-capture at assemble time."
  (:require [fukan.canvas.core.assemble :as a]
            [demos.access-control.vocab.core
             :refer [Action Resource Permission Role]]))

;; Actions
(Action ^{:name "read"} read-action)
(Action submit)
(Action approve)

;; Resources
(Resource Report)
(Resource Budget)

;; Permissions — forward ref: submit-report declares conflicts-with approve-report
;; before approve-report is defined; var-capture resolves at assemble time.
(declare approve-report)
(Permission read-report    {:action read-action :resource Report})
(Permission read-budget    {:action read-action :resource Budget})
(Permission submit-report  {:action submit  :resource Report
                            :conflicts-with [approve-report]})
(Permission approve-report {:action approve :resource Report})
(Permission approve-budget {:action approve :resource Budget})

;; Roles — viewer is a diamond apex (inherited by both author and approver)
(Role viewer   {:grants [read-report read-budget]})
(Role author   {:inherits [viewer] :grants [submit-report]})
(Role approver {:inherits [viewer] :grants [approve-report approve-budget]})

(defn build [] (a/assemble ['demos.access-control.model.rbac]))
