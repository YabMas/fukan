(ns fukan.model.core
  "Schemas for static analysis and the internal graph model.
   Contains both analysis data schemas (language-agnostic) and model schemas.")

;; -----------------------------------------------------------------------------
;; Analysis Schemas
;;
;; These schemas define the normalized format that any language analyzer
;; should produce. Language-specific analyzers convert their native format
;; to these generic structures.

(def ^:schema NsDef
  "A namespace/module definition."
  [:map
   [:name :symbol]
   [:filename :string]
   [:doc {:optional true} [:maybe :string]]])

(def ^:schema VarDef
  "A variable/function/symbol definition."
  [:map
   [:ns :symbol]
   [:name :symbol]
   [:filename :string]
   [:row :int]
   [:doc {:optional true} [:maybe :string]]
   [:private {:optional true} :boolean]])

(def ^:schema VarUsage
  "A reference from one var to another."
  [:map
   [:from :symbol]                         ; source namespace
   [:from-var {:optional true} :symbol]    ; source var (nil if top-level)
   [:to :symbol]                           ; target namespace
   [:name :symbol]])                       ; target var name

(def ^:schema NsUsage
  "A namespace require/import relationship."
  [:map
   [:from :symbol]       ; requiring namespace
   [:to :symbol]         ; required namespace
   [:filename :string]])

(def ^:schema AnalysisData
  "The normalized output format from any language analyzer."
  [:map
   [:namespace-definitions [:vector NsDef]]
   [:var-definitions [:vector VarDef]]
   [:var-usages [:vector VarUsage]]
   [:namespace-usages {:optional true} [:vector NsUsage]]])

;; -----------------------------------------------------------------------------
;; Model Schemas

(def ^:schema NodeId :string)
(def ^:schema NodeKind [:enum :folder :namespace :var :schema])

(def ^:schema Node
  "A node in the graph. Kind-specific fields stored in :data map."
  [:map
   [:id NodeId]
   [:kind NodeKind]
   [:label :string]
   [:parent {:optional true} [:maybe NodeId]]
   [:children [:set :string]]
   [:data {:optional true} :map]])  ; kind-specific: path, ns-sym, var-sym, doc, private?, schema-key, etc.

(def ^:schema Edge
  "A directed edge between two nodes. All info derived from connected nodes."
  [:map
   [:from NodeId]
   [:to NodeId]])

(def ^:schema Model
  "Simplified model: just nodes and edges. No pre-computed aggregations or indexes."
  [:map
   [:nodes [:map-of NodeId Node]]
   [:edges [:vector Edge]]])

;; -----------------------------------------------------------------------------
;; ID Generation

(defn gen-id
  "Generate a UUID for a node."
  []
  (str (random-uuid)))

;; -----------------------------------------------------------------------------
;; Tree Operations

(defn- add-child-to-parent
  "Add a child ID to a parent node's children set."
  [nodes child-id parent-id]
  (if (and parent-id (contains? nodes parent-id))
    (update-in nodes [parent-id :children] conj child-id)
    nodes))

(defn wire-children
  "Wire up parent-child relationships based on :parent fields."
  [nodes]
  (reduce (fn [acc [id node]]
            (if-let [parent-id (:parent node)]
              (add-child-to-parent acc id parent-id)
              acc))
          nodes
          nodes))

(defn remove-empty-folders
  "Remove folder nodes that have no children.
   Returns updated nodes map."
  [nodes]
  (let [;; First, wire children to see which folders are empty
        wired (wire-children nodes)
        ;; Find folders with no children
        empty-folder-ids (->> wired
                              (filter (fn [[_ node]]
                                        (and (= :folder (:kind node))
                                             (empty? (:children node)))))
                              (map first)
                              set)]
    ;; Remove empty folders
    (apply dissoc nodes empty-folder-ids)))

(defn find-smart-root
  "Find a smart starting container by skipping single-child folders.
   Returns the ID of the deepest folder that has multiple children
   or non-folder children."
  [nodes]
  (loop [container-id nil]
    (let [children (->> (vals nodes)
                        (filter (fn [node]
                                  (if container-id
                                    (= (:parent node) container-id)
                                    ;; Root level: no parent or parent not in nodes
                                    (let [p (:parent node)]
                                      (or (nil? p) (not (contains? nodes p)))))))
                        (remove #(= (:kind %) :var)))] ; Folders and namespaces only
      ;; If exactly one child and it's a folder, descend
      (if (and (= 1 (count children))
               (= :folder (:kind (first children))))
        (recur (:id (first children)))
        container-id))))

(defn prune-to-smart-root
  "Remove nodes above smart-root and set smart-root's parent to nil.
   Returns the pruned nodes map."
  [nodes smart-root-id]
  (if (nil? smart-root-id)
    ;; No smart-root means we're already at the real root
    nodes
    ;; Find all ancestors to remove
    (let [ancestors-to-remove (loop [current-id smart-root-id
                                     ancestors #{}]
                                (let [node (get nodes current-id)
                                      parent-id (:parent node)]
                                  (if (or (nil? parent-id) (not (contains? nodes parent-id)))
                                    ancestors
                                    (recur parent-id (conj ancestors parent-id)))))]
      (-> nodes
          ;; Remove ancestor nodes
          (#(apply dissoc % ancestors-to-remove))
          ;; Set smart-root's parent to nil
          (assoc-in [smart-root-id :parent] nil)))))
