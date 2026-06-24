(ns fukan.cozo.rules
  "The cozo-side derived-rule substrate — CozoScript rule fragments prepended to a
   ported query so its body speaks logical model edges, not the physical typed-EAV
   mirror. The Cozo analog of `fukan.canvas.core.rules` (the always-injected vocab
   rules) + `canvas.vocab.code.module/module-depends-rules`.

   This is the abstraction seam over physical storage: every ported reader/law
   composes these fragments, so when the mirror's shape changes (P4/P5 cut-over)
   only this namespace changes. Compose with `str`: `(str eav module-depends ?…)`.")

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

(def module-depends
  "Module ownership + the module→module dependency graph (calls ∪ data-adoption),
   built on `eav`. The CozoScript port of `module-depends-rules`: `owns` is
   `:exposes` ∪ `:owns` ∪ `:child`; `mdep` is a call dependency (an owned op
   `:delegates` to another module's op) UNIONed with a data-adoption dependency
   (an owned op's `:in`/`:out` is a ref-Schema whose `:names` reaches a Kind
   another module owns)."
  "
owns[m, x] := structof[m, 'canvas.vocab.code.module/Module'], relkind[r, 'exposes'], relfrom[r, m], relto[r, x]
owns[m, x] := structof[m, 'canvas.vocab.code.module/Module'], relkind[r, 'owns'],    relfrom[r, m], relto[r, x]
owns[m, x] := structof[m, 'canvas.vocab.code.module/Module'], relkind[r, 'child'],   relfrom[r, m], relto[r, x]

mdep[m, n] := owns[m, a], relkind[r, 'delegates'], relfrom[r, a], relto[r, b], owns[n, b], m != n
mdep[m, n] := owns[m, a], relkind[ri, 'in'],  relfrom[ri, a], relto[ri, sch],
              valkind[sch, 'ref'], relkind[rn, 'names'], relfrom[rn, sch], relto[rn, k], owns[n, k], m != n
mdep[m, n] := owns[m, a], relkind[ro, 'out'], relfrom[ro, a], relto[ro, sch],
              valkind[sch, 'ref'], relkind[rn, 'names'], relfrom[rn, sch], relto[rn, k], owns[n, k], m != n
")

(def subsystem
  "Subsystem membership + declared `:may-depend` edges, built on `eav` — the
   substrate the conformance/membership architecture laws read."
  "
in_subsystem[mod, sub] := structof[sub, 'canvas.vocab.code.subsystem/Subsystem'], relkind[r, 'child'], relfrom[r, sub], relto[r, mod]
declared_dep[s, t]     := structof[s, 'canvas.vocab.code.subsystem/Subsystem'], relkind[r, 'may-depend'], relfrom[r, s], relto[r, t]
")

(def correspondence
  "The authored↔extracted `op_twin` pairing (the cozo port of the `op-twin`
   defrelation), built on `eav`. `module-corresponds?` is inlined as a
   normalize-and-suffix match: the canvas module name's hyphens become dots, and it
   must be the code namespace exactly or a `.`-bounded suffix of it."
  "
canvas_module[cm] := structof[m, 'canvas.vocab.code.module/Module'], not extracted[m], ename[m, cm]
code_module[km]   := structof[m, 'canvas.vocab.code.module/Module'], extracted[m], ename[m, km]
module_corresponds[cm, km] := canvas_module[cm], code_module[km],
                              cmn = regex_replace_all(cm, '-', '.'), kmn = regex_replace_all(km, '-', '.'),
                              or(kmn == cmn, ends_with(kmn, concat('.', cmn)))

op_twin[a, b] := structof[a, 'canvas.vocab.code.operation/Operation'], not extracted[a], ename[a, n], in_module[a, cm],
                 structof[b, 'canvas.vocab.code.operation/Operation'], extracted[b], ename[b, n], in_module[b, km],
                 module_corresponds[cm, km]
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

(def effect
  "Transitive effect reachability, built on `eav`: an op REACHES effect E if it
   directly `:performs` E, or `:calls` an op that reaches E. The cozo port of
   `canvas.vocab.code.effect/reaches-effect-rules` — a recursive rule cozo
   saturates to a fixpoint over the (cyclic) call graph."
  "
reaches_effect[op, en] := relkind[pr, 'performs'], relfrom[pr, op], relto[pr, e], valname[e, en]
reaches_effect[op, en] := relkind[cr, 'calls'], relfrom[cr, op], relto[cr, mid], reaches_effect[mid, en]
")
