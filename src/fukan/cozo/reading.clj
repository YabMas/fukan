(ns fukan.cozo.reading
  "Read-side queries ported onto the Cozo mirror — each the Cozo twin of a
   datascript reader, which the datascript→Cozo oracle asserts agreement with.
   TRANSITIONAL framing: becomes the read surface once datascript is gone (P5)."
  (:require [fukan.cozo.db :as db]
            [fukan.cozo.rules :as rules]))

(defn module-dependencies
  "The complete module→module dependency graph (calls ∪ data-adoption) as a set
   of [caller-name callee-name] pairs, computed in CozoScript over the Cozo mirror
   `cdb`. The Cozo twin of `canvas.vocab.code.module/module-dependencies`."
  [cdb]
  (set (db/q cdb (str rules/eav rules/module-depends
                      "?[caller, callee] := mdep[m, n], ename[m, caller], ename[n, callee]"))))
