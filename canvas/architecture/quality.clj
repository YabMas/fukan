(ns canvas.architecture.quality
  "fukan opts INTO the architecture-quality vocab. `lib.arch` is opt-in (required, not auto-discovered),
   so requiring it here — from an auto-discovered canvas spec — registers its laws into fukan's own
   `check`. fukan's module graph is acyclic, so the no-mutual-dependency law passes. (This file carries
   no instances; its job is the opt-in. Fukan-specific architecture-quality config will grow here.)"
  (:require [lib.arch]))
