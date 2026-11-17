(ns canvas.architecture.cozo.rules
  "Self-spec: `fukan.cozo.rules` — the `eav` CozoScript decode: logical edge/node/leaf rules over
   the typed stored relations, for a raw CozoScript query to prepend. The query compiler names no
   view (it emits direct stored-relation access), so nothing in the build or the compiler prepends
   it. A pure data def — it exposes no operations and references nothing else."
  (:require [fukan.common.vocab.code.module :refer [Module]]))

(Module cozo-rules
  "The `eav` decode over the typed stored relations — a CozoScript prelude a raw query may
   prepend. A pure data def — exposes no operations.")
