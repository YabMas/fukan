(ns fukan.cozo.rules
  "The cozo-side CozoScript substrate — rule fragments prepended to a query so its
   body speaks logical model edges, not the physical typed-EAV mirror. The abstraction
   seam over physical storage: when the mirror's shape changes, only this namespace does.
   Compose with `str`: `(str eav surface \"…query…\")`.

   `triple` (the unified value-typed EAV view) underpins the general query/law compiler
   (`fukan.cozo.query`); `eav` (the logical edge/node/leaf decode) underpins the native
   build's raw queries (`fukan.cozo.build`); `surface` carries the descriptive
   code-surface primitives `latent-boundaries` composes with Cozo's `ConnectedComponents`
   (the one reading that needs a fixed rule, so it drops below the compiler to raw
   CozoScript). The earlier hand-ported LAW fragments (module-depends / subsystem /
   correspondence / effect) were retired once the law/query compiler subsumed them.")

(def eav
  "Logical EAV decode — reified-edge, node, and leaf views over the mirror's typed
   relations, plus the foundational `in_module` membership (op→owning-module-name).
   The universal base prepended to every cozo query."
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

in_module[e, mname] := relkind[r, 'child'],   relfrom[r, m], relto[r, e], ename[m, mname]
in_module[e, mname] := relkind[r, 'exposes'], relfrom[r, m], relto[r, e], ename[m, mname]
in_module[e, mname] := relkind[r, 'owns'],    relfrom[r, m], relto[r, e], ename[m, mname]
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

(def surface
  "Code-surface descriptive primitives, built on `eav` — the reusable building
   blocks higher-level surface readings compose. `public_op`: a non-private
   extracted Operation (the externally-callable surface). `clientele`: the OTHER
   modules that call a public op (its external consumers). `co_consumed`: two public
   ops in the same module captured by a shared clientele. `consumed`: a public op
   that has any external clientele, with its module."
  "
public_op[o] := structof[o, 'canvas.vocab.code.operation/Operation'], extracted[o], not isprivate[o]
clientele[o, cm] := public_op[o], relkind[c, 'calls'], relto[c, o], relfrom[c, caller],
                    in_module[caller, cm], in_module[o, om], cm != om
co_consumed[a, b] := clientele[a, cm], clientele[b, cm], in_module[a, m], in_module[b, m], a < b
consumed[o, mod] := clientele[o, cm], in_module[o, mod]
")
