(ns canvas.vocab.arch
  "The fukan-on-fukan model's ARCHITECTURE layer — the highest view: fukan's core
   concepts and how they flow. A `Faculty` is a core concept / capability; `:feeds`
   is what it produces into, `:reads` is what it operates on. (A vocab layer
   alongside shape/op/meta; this one is for the pure top-level vision — the frame
   that the subsystem models realize.)

   Vocab-only canvas spec (no `build-canvas`)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Faculty
  "A core concept or capability of fukan, in the top-level flow view."
  (slot :doc   (optional :String))
  (slot :feeds (many Faculty))     ; produces into / contributes to
  (slot :reads (many Faculty))     ; operates on / consumes
  ;; cross-VIEW link: the subsystem views (modules) that realize this concept —
  ;; authored with `(realized-by (across "<module>"))`, resolved post-merge
  (slot :realized-by (many Module))
  ;; No isolated faculty — every concept participates in the flow (has an incoming
  ;; or outgoing edge). A bounded invariant; the Model's hub-ness is evident in the
  ;; model's shape (it has the most connections) rather than enforced by a (cyclic,
  ;; divergence-prone) undirected-reachability law.
  (law "no faculty is isolated"
    :offenders '[?f]
    :where '[(not [?ro :rel/from ?f])
             (not [?ri :rel/to ?f])])
  ;; realized-by has teeth on the USE side: a faculty that READS the Model claims a
  ;; capability that operates on it, so it must be backed by a realizing module —
  ;; you cannot have a model-reading faculty with no realization. (Input faculties
  ;; that only :feed the Model are external — Canvas, Target — and outputs are
  ;; produced, so neither is required to be realized. The realized-by TARGET's
  ;; existence is already enforced at ingest: resolve-cross-refs throws on an
  ;; unresolved reference; this law adds the REQUIREMENT to have one.)
  (law "a model-reading faculty is realized by a module"
    :offenders '[?f]
    :where '[[?model :entity/name "Model"] [?model :structure/of :Faculty]
             [?rd :rel/from ?f] [?rd :rel/kind :reads] [?rd :rel/to ?model]
             (not [?rb :rel/from ?f] [?rb :rel/kind :realized-by])]))
