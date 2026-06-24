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
