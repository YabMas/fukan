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
  ;; No isolated faculty — every concept participates in the flow (has an incoming
  ;; or outgoing edge). A bounded invariant; the Model's hub-ness is evident in the
  ;; model's shape (it has the most connections) rather than enforced by a (cyclic,
  ;; divergence-prone) undirected-reachability law.
  (law "no faculty is isolated"
    :offenders '[?f]
    :where '[(not [?ro :rel/from ?f])
             (not [?ri :rel/to ?f])]))
