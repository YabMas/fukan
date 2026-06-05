(ns fukan.canvas.projection.finding
  "The Finding data type — a probe's output. A Finding is a list of Observations,
   each pairing a `:focus` (a node-set / induced sub-graph) with an `:as` tag (what
   the sub-graph IS — an open keyword that routes the next act) and a `:note` (a
   human descriptor). The focus is the composition currency: a probe emits foci, a
   projection consumes them.

   `finding->text` is the trivial text projection of a finding — the legacy
   \"list of strings\" payload, revealed as the simplest projection (its notes).")

(defn observation
  "One observed sub-graph: `focus` (a set of node eids), `as` (an open keyword tag),
   `note` (a human descriptor string)."
  [focus as note]
  {:focus focus :as as :note note})

(defn finding
  "A probe's output: the lens name, whether it gates, and its observations."
  [lens gating observations]
  {:lens lens :gating gating :observations (vec observations)})

(defn finding->text
  "The trivial text projection: a finding's observation notes, in order."
  [fdg]
  (mapv :note (:observations fdg)))
