(ns fukan.projection.path
  "Navigation projection: breadcrumb paths and root discovery.
   Computes the ancestor chain from any entity to the tree root for
   breadcrumb rendering, and locates the model's root node (the
   topmost module after smart-root pruning).")

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema PathSegment
  [:map {:description "One step in a breadcrumb path from root to the current entity."}
   [:id {:description "Node ID to navigate to, nil for the root."} [:maybe :NodeId]]
   [:label {:description "Short display label from the node."} :string]])

(def ^:schema EntityPath
  [:vector {:description "Breadcrumb path from root to an entity, ordered ancestor-first."} :PathSegment])

;; -----------------------------------------------------------------------------
;; Public API

(defn find-root-node
  "Find the root node (node with parent = nil).
   Root should be a module, not a function or schema."
  {:malli/schema [:=> [:cat :Model] [:maybe :Node]]}
  [model]
  (->> (vals (:nodes model))
       (filter #(nil? (:parent %)))
       (filter #(= :module (:kind %)))
       first))

(defn entity-path
  "Compute breadcrumb path from root to entity.
   Returns a list of {:id :label} maps.
   Uses node labels directly — labels are pre-shortened by analyzers."
  {:malli/schema [:=> [:cat :Model [:maybe :NodeId]] :EntityPath]}
  [model entity-id]
  (let [root-node (find-root-node model)
        root-id (:id root-node)]
    (if (or (nil? entity-id) (= entity-id root-id))
      ;; At root - just show root label
      [{:id nil :label (or (:label root-node) "root")}]
      ;; Build path from root to entity
      (let [path (loop [current-id entity-id
                        acc []]
                   (let [node (get-in model [:nodes current-id])]
                     (if (or (nil? node) (nil? (:parent node)))
                       acc
                       (recur (:parent node)
                              (cons {:id current-id :label (:label node)} acc)))))]
        (cons {:id nil :label (or (:label root-node) "root")} path)))))
