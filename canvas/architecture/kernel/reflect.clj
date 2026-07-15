(ns canvas.architecture.kernel.reflect
  "Self-spec: fukan's GRAMMAR REFLECTION — `core.reflect` (`fukan.canvas.core.reflect`): reify the
   structure registry into the model graph (every defstructure → a `Structure` node, slots → edges,
   laws → `:val/form` payloads, one `Vocabulary` per ns). Kernel-native machinery, grammar-agnostic
   and run on every build — the meta-grammar sibling of the act grammar in `core.lens`, hence part
   of the kernel, not the reusable `fukan.common` vocab. It reads the live registry (`all-structures`,
   `correspondence`, `scalar-slot?`) and reaches the type dialect only through the neutral SPI
   (`reflect-type`)."
  (:require [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.module :refer [Module]]
            [canvas.architecture.kernel.structure :as kernel]
            [canvas.architecture.kernel.typing :as typing]))

(Operation reflect
  "Reify the registry: the structure `tags` in use (+ `extra-seeds` ns-name strings) → the model's
   `{:nodes :rels}` grammar sub-graph, deduping shared `^:value` type nodes. PURE (db-agnostic); throws
   on a dangling grammar reference or a cross-family duplicate law key (via the correspondence seam)."
  {:signature [:=> [:catn [:tags [:vector :any]] [:extra-seeds [:vector :any]]] :map]
   :performs  [:throws]                            ; correspondence dup-key guard + dangling-grammar-ref throw
   :delegates [kernel/all-structures kernel/correspondence kernel/scalar-slot? typing/reflect-type]})

(Module core-reflect
  "Grammar reflection — the registry projected onto the graph. Its meta-grammar node types
   (Structure/Law/Vocabulary/Relation) are the tool's vocabulary for describing grammars."
  {:exposes [reflect]})
