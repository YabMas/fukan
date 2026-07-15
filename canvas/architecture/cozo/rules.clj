(ns canvas.architecture.cozo.rules
  "Self-spec: fukan's shared CozoScript SUBSTRATE — `cozo.rules` (`fukan.cozo.rules`): the
   always-prepended stored-relation views the datalog→CozoScript compiler builds every query over
   (the all-string `triple` view + the typed `eav` rule). A pure data def — it exposes no
   operations and references nothing else, so it sits in the kernel query layer's substrate
   alongside `core-rules` and `cozo-query`, keeping the module chain acyclic."
  (:require [fukan.common.vocab.code.module :refer [Module]]))

(Module cozo-rules
  "The shared CozoScript substrate (the all-string `triple` view + the typed `eav` rule) the Cozo
   query compiler prepends to every query. A pure data def — exposes no operations.")
