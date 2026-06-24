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

(defn nonconformant-modules
  "The offenders of the :may-depend conformance law: modules whose cross-subsystem
   module-dependency follows no declared `:may-depend` edge, as a set of module
   names. Uses Cozo's stratified `not declared_dep[s, t]` (s/t bound), where
   datascript routed the negation through a `not-join`. The Cozo twin of the
   datascript conformance law."
  [cdb]
  (set (map first (db/q cdb (str rules/eav rules/module-depends rules/subsystem
                                 "?[mn] := mdep[m, n], in_subsystem[m, s], in_subsystem[n, t], s != t, not declared_dep[s, t], ename[m, mn]")))))

(defn unclustered-modules
  "The offenders of the Subsystem membership law: authored Modules (not extracted)
   that belong to no Subsystem, as a set of module names. The law's vacuity guard
   (no offenders unless a Subsystem is modelled) is applied here in Clojure,
   mirroring the datascript law. The Cozo twin of the datascript membership law."
  [cdb]
  (if (empty? (db/q cdb "?[s] := *t_str[s, 'structure/of', 'canvas.vocab.code.subsystem/Subsystem']"))
    #{}                                                   ; no Subsystem modelled → vacuous
    (set (map first
              (db/q cdb (str rules/eav rules/subsystem "
authored_module[mod] := structof[mod, 'canvas.vocab.code.module/Module'], not *t_bool[mod, 'val/extracted', true]
clustered[mod]       := in_subsystem[mod, _]
?[mn] := authored_module[mod], not clustered[mod], ename[mod, mn]
"))))))
