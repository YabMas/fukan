(ns fukan.cytoscape
  "Cytoscape.js format transformations.
   Converts internal model structures to Cytoscape-compatible data.")

(defn node->cytoscape
  "Convert an internal node to Cytoscape node format for the view API.
   Returns a flat map with :originalParent to preserve parent info
   without triggering Cytoscape compound behavior."
  [{:keys [id kind label parent ns-sym children doc private?]}]
  (let [base {:id id
              :label label
              :kind (name kind)}]
    (cond-> base
      parent (assoc :originalParent parent)
      ns-sym (assoc :ns (str ns-sym))
      doc (assoc :doc doc)
      private? (assoc :private true)
      (seq children) (assoc :childCount (count children)))))
