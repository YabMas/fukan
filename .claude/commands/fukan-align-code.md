---
name: fukan-align-code
description: Align code with the fukan model — find :absent projections and realise them at their canonical addresses.
context: fork
agent: fukan-reconciler
argument-hint: [scope]
---

Align code with spec projections. `$ARGUMENTS` is the optional scope (filter for which absent projections to realise); empty → project-wide.

**Scope:** $ARGUMENTS

## Workflow

1. **Find absent projections.** Call `(drift)` — the L2 view returns absent projections by definition. Use `(drift :projection-kind :clojure)` to scope by target language. No `:owner` filter exists; narrow to the requested scope client-side over the returned vector.
2. **Assemble the Implementation Blueprint** per absent projection — canonical address, expected signature, model context, applicable idioms.
   - **Projector path.** If the Projector is implemented, call its current `fukan eval` entry point (discover via `(help)` inside `fukan eval`). Use the Blueprint it returns. If `(help)` reveals no Projector entry, fall through to the Manual path.
   - **Manual path.** If absent, assemble from `(get-primitive id)` + `(idioms)` + the canonical-address rule (one root-prefix knob, mechanical underscore-to-kebab transliteration). Note the manual-assembly path BOTH as an inline code comment at the generated address AND in the status report — code comment for the next reader, report for the running operator.
3. **Read existing code at the canonical address.** Don't write at a second address — honour the reconciler's *Single-canonical-address discipline*. If conflicting code lives elsewhere, surface it as an idiom or constraint question, not a free-form choice.
4. **Write code that realises the spec.** Respect project idioms from `(idioms)`. Apply mechanical underscore-to-kebab translation for Clojure realisations. Co-locate the new code where conventional for the owning module.
5. **Refresh and verify.** Run `fukan eval '(refresh)'`. Confirm the primitive's projection flipped to `:valid` via L1: `(relations :kind :relation/projects :validity :valid :from <id>)`. If the projection remains absent or went stale, iterate from step 2.

## Output

Code changes + status report. `## Applied` lists projections that flipped to `:valid`; `## Unflipped` lists those that couldn't be realised, with blocker and proposal.

```
## Applied
<file path>
  Primitive: <id>
  Address: <canonical code address>

## Unflipped
<primitive id>
  Address: <canonical code address>
  Blocker: <one-line reason — manual-assembly gap, idiom conflict, blueprint ambiguity>
  Proposal: <suggested resolution>
```

**Note:** `allium:*` is not invoked from this workflow — pure code-side work; spec is read, never written.
