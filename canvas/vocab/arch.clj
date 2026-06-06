(ns canvas.vocab.arch
  "The fukan-on-fukan model's ARCHITECTURE layer — the highest view: fukan's core
   concepts and how they flow. A `Faculty` is a core concept / capability; `:feeds`
   is what it produces into, `:reads` is what it operates on. (A vocab layer
   alongside shape/op/meta; this one is for the pure top-level vision — the frame
   that the subsystem models realize.)

   Vocab-only canvas spec (no `build-canvas`)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Module
  "A named grouping of model instances — the unit a subsystem view occupies. `:child`
   is a heterogeneous container (the `Any` wildcard) so a module groups Stages, Kinds,
   Concepts, Faculties — whatever its members are. `in-module` resolves over these
   `:child` relations (no privileged `:Module` tag in the kernel any more — a module is
   ordinary vocab)."
  (slot :child (many Any)))

(defstructure Connected
  "Facet: a node that participates in the directed graph over its own kind — it is not
   isolated (has some incoming or outgoing relation)."
  (law "no isolated node"
    :offenders '[?n]
    :where '[(not [?o :rel/from ?n]) (not [?i :rel/to ?n])]))

(defstructure Faculty
  "A core concept or capability of fukan, in the top-level flow view. THREE kinds of
   contribution edge — distinguished because they MATERIALIZE differently:
     :feeds     — internal dataflow (a capability produces into another). The
                  materialization functoriality (canvas.essence.view) checks it lands in code.
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
  ;; (the "no faculty is isolated" law is now Connected's, inherited via (includes Connected))
  ;; realized-by has teeth on the USE side: a faculty that READS the Model claims a
  ;; capability that operates on it, so it must be backed by a realizing module —
  ;; you cannot have a model-reading faculty with no realization. (Input faculties
  ;; that only :feed the Model are external — Canvas, Target — and outputs are
  ;; produced, so neither is required to be realized. The realized-by TARGET's
  ;; existence is already enforced by authoring: a `realized-by` clause references the
  ;; Module's var directly, so a missing module is a var-resolution error at read time;
  ;; this law adds the REQUIREMENT to have one.)
  (law "a model-reading faculty is realized by a module"
    :offenders '[?f]
    :where '[[?model :entity/name "Model"] [?model :structure/of :Faculty]
             [?rd :rel/from ?f] [?rd :rel/kind :reads] [?rd :rel/to ?model]
             (not [?rb :rel/from ?f] [?rb :rel/kind :realized-by])]))
