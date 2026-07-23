(ns fukan.canvas.core.rules
  "The fixed substrate rules — vocab-agnostic datalog the model always carries, independent of any
   vocabulary. The vocab-DERIVED rules (kind / relation / relation-inclusion / defrelation /
   compiler-minted closures / the essential correspond's `corresponds`/`realized-*` pairing rules)
   are emitted by the kernel's closed declaration lowering
   (`fukan.canvas.core.structure/terms-of`) from what the VOCABULARY declares — containment
   (`contains`, its closure, `within`) is no exception, and rides ordinary relation elements like
   any other. This namespace holds only the fixed substrate, which `terms-of` composes in. It takes
   no dependency on the kernel, so the kernel can consume the rules (in `check`) without a
   `structure ↔ rules` cycle.")

(def substrate-rules
  "Fixed rules for substrate relations — vocab-agnostic: `named` (over `:entity/name`) and the
   two PROVENANCE strata over the stratum attribute (`substrate/stratum-attr` = `:val/extracted`,
   embedded literally here because these rules are pure quoted data — keep in sync): `(fact ?n)`
   a node stamped by the build as extracted-from-code, `(design ?n)` an authored node (the
   positive `:structure/of` clause keeps the rule range-bound under negation).

   `contains` and `within` are NOT here and no longer anywhere in the kernel: they are declared by
   the VOCABULARY as relation elements (`fukan.common.vocab.grouping` / `…vocab.code.module`). Until
   2026-07-17 the kernel emitted both — this docstring claimed the substrate named no code-vocab
   relation, which was true only because the hardcoding sat one file over, in `structure/terms-of`."
  '[[(named ?e ?n) [?e :entity/name ?n]]
    [(fact ?n) [?n :val/extracted true]]
    [(design ?n) [?n :structure/of ?_k] (not [?n :val/extracted true])]])
