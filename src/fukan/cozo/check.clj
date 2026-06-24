(ns fukan.cozo.check
  "fukan's laws ported onto the Cozo mirror as offender queries — the cozo-side
   twins the datascript→Cozo oracle checks against `structure/check`. Each composes
   the shared `fukan.cozo.rules` substrate and adds the law's offender entry."
  (:require [fukan.cozo.db :as db]
            [fukan.cozo.rules :as rules]))

(defn mutually-dependent-modules
  "The offenders of the no-mutual-dependency architecture law: modules m with
   module-depends m→n AND n→m, as a set of module names. CozoScript over the Cozo
   mirror `cdb`. The Cozo twin of the datascript no-mutual-dependency law."
  [cdb]
  (set (map first (db/q cdb (str rules/eav rules/module-depends
                                 "?[mn] := mdep[m, n], mdep[n, m], ename[m, mn]")))))

(defn cyclic-subsystems
  "The offenders of the :may-depend acyclicity law: subsystems that transitively
   depend on themselves, as a set of subsystem names. The transitive `sub_reaches`
   is a RECURSIVE CozoScript rule — cozo evaluates the closure natively, where
   datascript needed a carefully-guarded self-recursive rule. The Cozo twin of the
   datascript acyclicity law."
  [cdb]
  (set (map first
            (db/q cdb (str rules/eav "
sub_reaches[s, t] := relkind[r, 'may-depend'], relfrom[r, s], relto[r, t]
sub_reaches[s, t] := relkind[r, 'may-depend'], relfrom[r, s], relto[r, mid], sub_reaches[mid, t]
?[sn] := structof[s, 'canvas.vocab.code.subsystem/Subsystem'], sub_reaches[s, s], ename[s, sn]
")))))
