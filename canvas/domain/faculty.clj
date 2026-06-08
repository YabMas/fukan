(ns canvas.domain.faculty
  "Domain grammar — what a `Faculty` IS: a core concept/capability of fukan. The
   vocabulary the structural perspective (`canvas.perspectives.structure.overview`) is
   written in. (Split out of the old `canvas.vocab.arch`: `Faculty` is a fukan-specific
   domain concept and lives here; the generic `Module`/`Connected` primitives moved to
   `canvas.language.grouping`.)

   Vocab-only canvas spec (no build-canvas)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Faculty
  "A core concept or capability of fukan. THREE kinds of
   contribution edge — distinguished because they MATERIALIZE differently:
     :feeds     — internal dataflow (a capability produces into another). The
                  materialization functoriality (canvas.domain.view) checks it lands in code.
     :builds-on — foundation: this faculty is built upon another (Model builds-on the
                  Structure primitive). A rests-on relation, not a runtime connection.
     :supplies  — external input through a DECOUPLED seam (Canvas authors specs in; Target
                  is extracted via the vocab-blind plug-point). Intentionally not statically
                  connected, so the materialization law rightly does not apply.
   `:reads` is what it operates on."
  (includes Connected)
  (slot :doc   (optional :String))
  (slot :feeds       (many Faculty))   ; internal dataflow — materialization-checked
  (slot :builds-on   (many Faculty))   ; foundation — built upon
  (slot :supplies    (many Faculty))   ; external input through a decoupled seam
  (slot :reads       (many Faculty))   ; operates on / consumes
  ;; cross-VIEW link: the subsystem views (modules) that realize this concept
  (slot :realized-by (many Module))
  (law "a model-reading faculty is realized by a module"
    :offenders '[?f]
    :where '[[?model :entity/name "Model"] [?model :structure/of :Faculty]
             [?rd :rel/from ?f] [?rd :rel/kind :reads] [?rd :rel/to ?model]
             (not [?rb :rel/from ?f] [?rb :rel/kind :realized-by])]))
