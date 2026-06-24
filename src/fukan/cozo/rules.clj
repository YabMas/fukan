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
   relations. The universal base prepended to every cozo query."
  "
relfrom[r, e]    := *t_int[r, 'rel/from', e]
relto[r, e]      := *t_int[r, 'rel/to', e]
relkind[r, k]    := *t_str[r, 'rel/kind', k]
ename[e, n]      := *t_str[e, 'entity/name', n]
structof[e, tag] := *t_str[e, 'structure/of', tag]
valkind[e, k]    := *t_str[e, 'val/kind', k]
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
