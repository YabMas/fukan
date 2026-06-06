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
  "A core concept or capability of fukan, in the top-level flow view."
  (includes Connected)
  (slot :doc   (optional :String))
  (slot :feeds (many Faculty))     ; produces into / contributes to
  (slot :reads (many Faculty))     ; operates on / consumes
  ;; cross-VIEW link: the subsystem views (modules) that realize this concept —
  ;; authored with `(realized-by (across "<module>"))`, resolved post-merge
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
