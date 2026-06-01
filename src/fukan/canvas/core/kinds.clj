(ns fukan.canvas.core.kinds
  "Flat node classification — the lean replacement for the retired classification
   refinement stratum. A node's kind is its tag-application tag; its family is
   that tag's `:family/*` super-tag, stamped flatly (no refinement lattice; the
   stratum's own post-mortem found it was depth-1 in practice).

   `tag->family` is the residue of the old construction/vocab `:family` fields —
   throwaway: it serves only the legacy model-map projection (canvas_source),
   which step 3B replaces by sourcing the model from the structure substrate.
   Kept minimal: family-of / direct-kind / element-kind + the `rules` set, the
   only surface the surviving floor still calls."
  (:require [datascript.core :as d]))

(def tag->family
  "Canvas kind-tag → its `:family/*` super-tag (flat; was the depth-1 lattice).
   `:canvas/exported` is a marker, not a kind, so it carries no family."
  {:canvas/module    :family/module
   :canvas/function  :family/affordance
   :canvas/invariant :family/affordance
   :canvas/rule      :family/affordance
   :canvas/getter    :family/affordance
   :canvas/checker   :family/affordance
   :canvas/event     :family/affordance
   :canvas/handler   :family/affordance
   :canvas/value     :family/type
   :canvas/record    :family/type
   :canvas/state     :family/state})

(def ^:private super-tag->element-kind
  {:family/module :Module :family/affordance :Affordance
   :family/type   :Type   :family/state      :State})

(defn tagdef-datoms
  "Datoms stamping each kind-tag's `:family/*` super-tag as `:tagdef/family`, so
   the `family-of` rule can resolve a node's family by a direct join. Replaces
   classification/tagdef-datoms (which read the now-deleted vocab registry)."
  []
  (mapv (fn [[tag fam]] {:tagdef/tag tag :tagdef/family fam}) tag->family))

(def rules
  "Datascript rule set; thread as the `%` input. Flat — no refines* lattice."
  '[[(direct-kind ?e ?tag)
     [?ta :tagapp/node ?e]
     [?ta :tagapp/tag ?tag]]
    [(family-of ?e ?fam)
     [?ta :tagapp/node ?e]
     [?ta :tagapp/tag ?t]
     [?td :tagdef/tag ?t]
     [?td :tagdef/family ?fam]]])

(defn direct-kind
  "The immediate classification tag of node `eid` (its tag-application tag), or nil."
  [db eid]
  (ffirst
   (d/q '[:find ?tag :in $ ?e
          :where [?ta :tagapp/node ?e] [?ta :tagapp/tag ?tag]]
        db eid)))

(defn family-of
  "The `:family/*` super-tag of node `eid`, or nil."
  [db eid]
  (ffirst
   (d/q '[:find ?fam :in $ % ?e :where (family-of ?e ?fam)] db rules eid)))

(defn element-kind
  "The legacy element-kind keyword (:Module/:Affordance/:Type/:State) of node
   `eid`, derived from its family super-tag, or nil."
  [db eid]
  (super-tag->element-kind (family-of db eid)))
