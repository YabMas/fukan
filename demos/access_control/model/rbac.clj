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
   declared first), resolved by within-module's second pass."
  (:require [fukan.canvas.core.structure :as s]
            [demos.access-control.vocab.core
             :refer [Action Resource Permission Role]]))

(defn build []
  (s/with-structures
    (s/within-module "rbac"
      (Action "read")
      (Action "submit")
      (Action "approve")

      (Resource "Report")
      (Resource "Budget")

      (Permission "read-report"    (action read)    (resource Report))
      (Permission "read-budget"    (action read)    (resource Budget))
      (Permission "submit-report"  (action submit)  (resource Report)
                                   (conflicts-with approve-report))   ; forward ref
      (Permission "approve-report" (action approve) (resource Report))
      (Permission "approve-budget" (action approve) (resource Budget))

      (Role "viewer"   (grants read-report read-budget))
      (Role "author"   (inherits viewer) (grants submit-report))
      (Role "approver" (inherits viewer) (grants approve-report approve-budget)))))
