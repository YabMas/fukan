(ns fukan.canvas.core.rules
  "The fixed substrate rules — vocab-agnostic datalog the model always carries, independent of any
   vocabulary. The vocab-DERIVED rules (kind / relation / inclusion / coproduct / defrelation /
   `contains` union / transitive closures / `in-module` / correspondence `twin`) are emitted by the
   kernel's declaration registry (`fukan.canvas.core.structure/terms-of`); this namespace holds only
   the fixed substrate, which `terms-of` composes in. It takes no dependency on the kernel, so the
   kernel can consume the rules (in `check`) without a `structure ↔ rules` cycle.")

(def substrate-rules
  "Fixed rules for substrate relations — vocab-agnostic: `named` (over `:entity/name`) and the
   two PROVENANCE strata over the stratum attribute (`substrate/stratum-attr` = `:val/extracted`,
   embedded literally here because these rules are pure quoted data — keep in sync): `(fact ?n)`
   a node stamped by the build as extracted-from-code, `(design ?n)` an authored node (the
   positive `:structure/of` clause keeps the rule range-bound under negation). `in-module` is
   NOT here — it is derived from the vocab-declared `contains` relation by the kernel's declaration
   registry (the `:contains` handler), so the substrate names no code-vocab relation kind."
  '[[(named ?e ?n) [?e :entity/name ?n]]
    [(fact ?n) [?n :val/extracted true]]
    [(design ?n) [?n :structure/of ?_k] (not [?n :val/extracted true])]])
