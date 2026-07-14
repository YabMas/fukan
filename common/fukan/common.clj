(ns fukan.common
  "The fukan common modelling grammar — one require that registers the whole vocabulary.
   Requiring this ns loads every element of the code grammar plus the malli type dialect,
   the Clojure extraction seam, and grammar reflection, so the full grammar is registered
   (e.g. for reflection / the print-dual) without depending on spec discovery. A consuming
   project `(:require [fukan.common])` for the complete grammar, or requires the specific
   `fukan.common.vocab.*` elements it authors against."
  (:require [fukan.common.vocab.grouping]
            [fukan.common.vocab.code.kind]
            [fukan.common.vocab.code.effect]
            [fukan.common.vocab.code.operation]
            [fukan.common.vocab.code.module]
            [fukan.common.vocab.code.subsystem]
            [fukan.common.vocab.code.plug-point]
            [fukan.common.typing.malli]
            [fukan.common.extraction.core]
            [fukan.common.reflect.grammar]))
