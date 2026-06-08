(ns canvas.domain.faculty
  "Domain grammar — what a `Faculty` IS: a core concept/capability of fukan. The
   vocabulary the structural perspective (`canvas.perspectives.structure.overview`) is
   written in. (Split out of the old `canvas.vocab.arch`: `Faculty` is a fukan-specific
   domain concept and lives here; the generic `Module`/`Connected` primitives moved to
   `canvas.language.grouping`.)

   Vocab-only canvas spec (no build-canvas)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Faculty
  "A core concept or capability of fukan, related to other faculties by THREE kinds of
   contribution edge:
     :feeds     — internal dataflow: a capability produces into another (Lens feeds Probe).
     :builds-on — foundation: this faculty rests on another (Model builds-on Structure).
     :supplies  — external input through a decoupled seam (Canvas supplies the Model).
   `:reads` is what it operates on.

   The domain stays pure — a Faculty names only other faculties, never what realizes it in
   code. That model↔code mapping (and the law that a `:feeds` must land as a module link)
   is the correspondence concern, in `canvas.materialize.correspondence`."
  (includes Connected)
  (slot :doc       (optional :String))
  (slot :feeds     (many Faculty))   ; internal dataflow
  (slot :builds-on (many Faculty))   ; foundation — built upon
  (slot :supplies  (many Faculty))   ; external input through a decoupled seam
  (slot :reads     (many Faculty)))  ; operates on / consumes
