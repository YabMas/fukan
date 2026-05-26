# static-lib demo — canvas lower-bound stress-test

Five modules of types and pure functions, no behavioral content.
Tests whether the substrate is over-built for trivial cases.

## Modules

| Namespace | Content |
|-----------|---------|
| `demo.static-lib.vec2` | `Vec2` record + pure functions (add, sub, dot, magnitude) |
| `demo.static-lib.vec3` | `Vec3` record + pure functions (add, sub, dot, cross, magnitude) |
| `demo.static-lib.matrix` | `Matrix3x3` record + pure functions (add, mul, transpose, determinant) |
| `demo.static-lib.transform` | `Transform` record + compose/invert over Matrix3x3 |
| `demo.static-lib.operations` | Cross-cutting: apply_transform, project_vec3_to_vec2, lerp |

## What vocab was needed

Only `construction` primitives: `record`, `function`, `exports`.
No behavioral lifts required. Ported cleanly using only the construction layer.

## Findings

See `doc/plans/2026-05-26-stress-test-findings.md`.
