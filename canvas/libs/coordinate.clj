(ns canvas.libs.coordinate
  "Canvas port of libs/coordinate.allium + coordinate.boundary.

   Coverage:
     - invariant CanonicalisePathPure              → vocab.behavioral/invariant
     - invariant CanonicalisePathIdempotent        → vocab.behavioral/invariant
     - invariant CanonicalisePathExtensionStripped → vocab.behavioral/invariant
     - invariant CanonicalisePathRelativeResolution→ vocab.behavioral/invariant
     - fn canonicalise_path                        → construction/function

   No value types; no exports clause in source boundary file."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "libs.coordinate"

      ;; Invariants from coordinate.allium

      (invariant "CanonicalisePathPure"
        "canonicalise_path is a pure function of its two String inputs: the
         same (host_coord, raw_path) pair always yields the same canonical
         coordinate. No file I/O, no global state."
        (holds-that "canonicalise-path-pure"))

      (invariant "CanonicalisePathIdempotent"
        "Canonicalisation is idempotent: feeding an already-canonical coordinate
         back through canonicalise_path (with any host) yields the same
         coordinate. No extension to strip, no ./ or ../ prefix to resolve."
        (holds-that "canonicalise-path-idempotent"))

      (invariant "CanonicalisePathExtensionStripped"
        "The result never carries a .allium or .boundary suffix; both extensions
         are mechanically removed before resolution."
        (holds-that "canonicalise-path-extension-stripped"))

      (invariant "CanonicalisePathRelativeResolution"
        "./ prefixes resolve against the host file's directory; each leading
         ../ segment walks one level up from the host directory. Bare paths
         (no ./ or ../ prefix) are treated as root-relative and only have
         their extension stripped."
        (holds-that "canonicalise-path-relative-resolution"))

      ;; Public function from coordinate.boundary

      (function "canonicalise_path"
        "Resolve a raw use / contains: path against the host file's coordinate.
         Strips the .allium / .boundary extension, expands ./ relative to the
         host directory, and walks up one directory per leading ../ segment
         (chained ../ supported)."
        (takes [host_coord :String
                raw_path   :String])
        (gives :String)))))
