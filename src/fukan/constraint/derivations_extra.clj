(ns fukan.constraint.derivations-extra
  "Additional Datalog derivations layered as rules over the kernel-universal
   EDB (fukan.constraint.derivations).

   `depends-on` is the structural dependency relation per MODEL.md §6.6 —
   transitive closure of the :edge predicate. Two rules:
     depends-on(?x, ?y) :- edge(?x, ?_rel, ?y).
     depends-on(?x, ?z) :- edge(?x, ?_rel, ?y), depends-on(?y, ?z)."
  (:require [fukan.constraint.ast :as ast]))

(defn depends-on-rules
  "Return the two Datalog rules defining :depends-on transitively over :edge."
  []
  [(ast/make-rule
     (ast/make-atom :depends-on [:?x :?y])
     [(ast/make-atom :edge [:?x :?_rel :?y])])
   (ast/make-rule
     (ast/make-atom :depends-on [:?x :?z])
     [(ast/make-atom :edge [:?x :?_rel :?y])
      (ast/make-atom :depends-on [:?y :?z])])])
