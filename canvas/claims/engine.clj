(ns canvas.claims.engine
  "What the placement of fukan's query engine commits its modules to: the kernel defines the
   presentation and the model, the engine evaluates them, and everything else reads through the
   engine's `q`. Each Claim's name is the id the design record carries for it and its docstring is
   the record's statement."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.architecture.kernel.structure :as structure]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.kernel.lens :as lens]
            [canvas.architecture.cozo.db :as db]
            [canvas.architecture.cozo.query :as query]
            [canvas.architecture.cozo.rules :as rules]
            [canvas.architecture.cozo.mirror :as mirror]
            [canvas.architecture.cozo.law :as law]))

(Claim kernel-names-no-engine
  "No module of the kernel subsystem depends on a module of any other subsystem, so none depends on
   the engine: the kernel runs no query and evaluates no law. What the engine evaluates with, it
   takes from the kernel — the presentation, the assembled instances, and the type dialect's
   value-validity check."
  {:about [structure/core-structure substrate/core-substrate] :evidence "law"})

(Claim engine-holds-what-knows-cozo
  "Every declared module that requires cozo-clj, emits CozoScript or reads or writes the typed
   stored relations is a member of the cozo subsystem, which depends on the kernel and on nothing
   else."
  {:about [db/cozo-db query/cozo-query rules/cozo-rules mirror/cozo-mirror law/cozo-law]
   :evidence "round"})

(Claim lens-is-a-reader
  "The lens engine is a member of the projection subsystem and reads the model only through the
   engine's q and entity; no module of the kernel or the engine depends on it."
  {:about [lens/core-lens query/cozo-query] :evidence "round"})
