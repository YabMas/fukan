(ns fukan.cozo.rules
  "The cozo-side CozoScript substrate — rule fragments prepended to a query so its
   body speaks logical model edges, not the physical typed-EAV mirror. The abstraction
   seam over physical storage: when the mirror's shape changes, only this namespace does.
   Compose with `str`: `(str eav \"…query…\")` (code-surface fragments live in vocab, not here).

   `eav` (the logical edge/node/leaf decode) underpins the native build's raw queries
   (`fukan.cozo.build`). The query/law compiler (`fukan.cozo.query`) names NO view: it emits
   each clause as DIRECT stored-relation access, because a view is materialized and a
   materialized rule has no key — see `query/compile-clause`. A unified `triple` view lived
   here until 2026-08-25 and cost ~136x on any multi-hop join. Code-surface CozoScript that names code-vocab
   (`Operation`/`:calls`) lives in canvas, not here: this substrate is vocab-agnostic. The
   earlier hand-ported LAW fragments (module-depends / subsystem / correspondence / effect)
   were retired once the law/query compiler subsumed them.")

(def eav
  "Logical EAV decode — reified-edge, node, and leaf views over the mirror's typed
   relations. A purely generic, vocab-agnostic decode: the universal base prepended to
   every cozo query."
  "
relfrom[r, e]    := *t_int[r, 'rel/from', e]
relto[r, e]      := *t_int[r, 'rel/to', e]
relkind[r, k]    := *t_str[r, 'rel/kind', k]
ename[e, n]      := *t_str[e, 'entity/name', n]
structof[e, tag] := *t_str[e, 'structure/of', tag]
valkind[e, k]    := *t_str[e, 'val/kind', k]
valname[e, n]    := *t_str[e, 'val/name', n]
extracted[e]     := *t_bool[e, 'val/extracted', true]
isprivate[e]     := *t_bool[e, 'val/private', true]
")
