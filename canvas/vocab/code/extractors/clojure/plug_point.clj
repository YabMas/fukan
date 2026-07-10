(ns canvas.vocab.code.extractors.clojure.plug-point
  "Clojure grounding for the code `PlugPoint` vocabulary. A `defmulti` is an open dispatch point —
   structurally a plug-point: its method set is open, and consumers dispatch through the shape rather
   than naming implementations. This namespace maps defmulti var-definitions to extracted PlugPoint
   facts. The generic `PlugPoint` structure lives in `canvas.vocab.code.plug-point`."
  (:require [fukan.canvas.core.substrate :as sub]
            [canvas.vocab.code.plug-point :as plug-point]))

(def plug-point-defining
  "clj-kondo `:defined-by` values that denote a plug-point: `defmulti` (an open dispatch point)."
  #{'clojure.core/defmulti})

(defn extract-plug-point
  "Build an extracted PlugPoint InstanceValue from a clj-kondo var-definition `v` (a defmulti).
   Coarse: name + provenance only — no `:shape` (a defmulti carries no signature to derive it from).
   Provenance (`:val/extracted`) is stamped by the BUILD at the merge, not here."
  [v]
  (sub/->InstanceValue ::plug-point/PlugPoint (str (:name v)) nil nil [] false))
