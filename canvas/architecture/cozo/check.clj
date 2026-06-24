(ns canvas.architecture.cozo.check
  "Self-spec: `fukan.cozo.check` — fukan's LAWS ported onto the Cozo mirror as
   offender queries (the datascript→Cozo migration). Each returns the offenders
   its datascript law would; the oracle asserts the two agree — including on
   synthetic violating fixtures, since the real model satisfies every law.
   TRANSITIONAL — becomes the check surface once datascript is gone (P5)."
  (:require [canvas.vocab.code.operation :refer [Operation]]
            [canvas.vocab.code.module :refer [Module]]
            [canvas.architecture.cozo.db :as db]))

(Module cozo-check
  "Law offender-queries over the Cozo mirror — the cozo-side twins the oracle
   checks against datascript's structure/check."
  (Operation mutually-dependent-modules
    "The offenders of the no-mutual-dependency architecture law: modules that mutually depend (module-depends m→n AND n→m), as a set of module names. CozoScript over the mirror."
    {:signature [:=> [:catn [:db db/CozoDb]] :any]
     :delegates [db/q]})
  (Operation cyclic-subsystems
    "The offenders of the :may-depend acyclicity law: subsystems that transitively depend on themselves (a :may-depend cycle), as a set of subsystem names. CozoScript over the mirror, via a recursive sub-reaches rule."
    {:signature [:=> [:catn [:db db/CozoDb]] :any]
     :delegates [db/q]})
  (Operation nonconformant-modules
    "The offenders of the :may-depend conformance law: modules whose cross-subsystem dependency is not covered by a declared :may-depend edge, as a set of module names. CozoScript over the mirror."
    {:signature [:=> [:catn [:db db/CozoDb]] :any]
     :delegates [db/q]}))
