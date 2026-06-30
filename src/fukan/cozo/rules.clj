(ns fukan.cozo.rules
  "The cozo-side CozoScript substrate — rule fragments prepended to a query so its
   body speaks logical model edges, not the physical typed-EAV mirror. The abstraction
   seam over physical storage: when the mirror's shape changes, only this namespace does.
   Compose with `str`: `(str eav \"…query…\")` (code-surface fragments live in vocab, not here).

   `triple` (the unified value-typed EAV view) underpins the general query/law compiler
   (`fukan.cozo.query`); `eav` (the logical edge/node/leaf decode) underpins the native
   build's raw queries (`fukan.cozo.build`). Code-surface CozoScript that names code-vocab
   (`Operation`/`:calls` — e.g. the `surface` rules `latent-boundaries` composes) lives in
   VOCAB (`canvas.vocab.code.subsystem`), not here: this substrate is vocab-agnostic. The
   earlier hand-ported LAW fragments (module-depends / subsystem / correspondence / effect)
   were retired once the law/query compiler subsumed them.")

(def eav
  "Logical EAV decode — reified-edge, node, and leaf views over the mirror's typed
   relations. A purely generic, vocab-agnostic decode: the universal base prepended to
   every cozo query. Code-surface CozoScript that names code-vocab (`in_module` over
   child/exposes/owns) lives in vocab (`canvas.vocab.code.module/in-module-cozo`), not here."
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

(def triple
  "The unified EAV view over the typed mirror relations — EIDS are always strings (an
   opaque handle), but LEAF VALUES keep their native type (Int/String/Bool), so a
   `[?e :attr ?v]` find-var binds the real value, not a stringified one. The two
   eid-VALUED int attributes (`rel/from`/`rel/to` — a relation's endpoint eids) are
   stringified too, so they join with the (stringified) subject eids; every other int
   (`rel/order` + leaf scalars) stays native. Cozo permits the mixed value column."
  "
triple[e, a, v] := *t_int[ei, a, v], a != 'rel/from', a != 'rel/to', e = to_string(ei)
triple[e, a, v] := *t_int[ei, 'rel/from', vi], a = 'rel/from', e = to_string(ei), v = to_string(vi)
triple[e, a, v] := *t_int[ei, 'rel/to', vi],   a = 'rel/to',   e = to_string(ei), v = to_string(vi)
triple[e, a, v] := *t_str[ei, a, v],  e = to_string(ei)
triple[e, a, v] := *t_bool[ei, a, v], e = to_string(ei)
")
