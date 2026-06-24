(ns fukan.cozo.reading
  "Read-side queries ported onto the Cozo mirror — each the Cozo twin of a
   datascript reader, which the datascript→Cozo oracle asserts agreement with.
   TRANSITIONAL framing: becomes the read surface once datascript is gone (P5)."
  (:require [fukan.cozo.db :as db]))

(def ^:private module-dependencies-script
  "CozoScript port of `canvas.vocab.code.module/module-depends-rules` over the
   typed EAV mirror: module→module dependency = a call dependency (an owned op
   `:delegates` to another module's op) UNIONed with a data-adoption dependency
   (an owned op's `:in`/`:out` is a ref-Schema whose `:names` reaches a Kind
   another module owns). `module-owns` is `:exposes` ∪ `:owns` ∪ `:child`."
  "
relfrom[r, e] := *t_int[r, 'rel/from', e]
relto[r, e]   := *t_int[r, 'rel/to', e]
relkind[r, k] := *t_str[r, 'rel/kind', k]
ename[e, n]   := *t_str[e, 'entity/name', n]
is_module[e]  := *t_str[e, 'structure/of', 'canvas.vocab.code.module/Module']
valkind[e, k] := *t_str[e, 'val/kind', k]

owns[m, x] := is_module[m], relkind[r, 'exposes'], relfrom[r, m], relto[r, x]
owns[m, x] := is_module[m], relkind[r, 'owns'],    relfrom[r, m], relto[r, x]
owns[m, x] := is_module[m], relkind[r, 'child'],   relfrom[r, m], relto[r, x]

mdep[m, n] := owns[m, a], relkind[r, 'delegates'], relfrom[r, a], relto[r, b], owns[n, b], m != n
mdep[m, n] := owns[m, a], relkind[ri, 'in'],  relfrom[ri, a], relto[ri, sch],
              valkind[sch, 'ref'], relkind[rn, 'names'], relfrom[rn, sch], relto[rn, k], owns[n, k], m != n
mdep[m, n] := owns[m, a], relkind[ro, 'out'], relfrom[ro, a], relto[ro, sch],
              valkind[sch, 'ref'], relkind[rn, 'names'], relfrom[rn, sch], relto[rn, k], owns[n, k], m != n

?[caller, callee] := mdep[m, n], ename[m, caller], ename[n, callee]
")

(defn module-dependencies
  "The complete module→module dependency graph (calls ∪ data-adoption) as a set
   of [caller-name callee-name] pairs, computed in CozoScript over the Cozo mirror
   `cdb`. The Cozo twin of `canvas.vocab.code.module/module-dependencies`."
  [cdb]
  (set (db/q cdb module-dependencies-script)))
