(ns fukan.web.views
  "View rendering for the web interface.
   
   All public render functions take the model and an editor-state map.
   See AGENTS.md for internal naming conventions."
  (:require [hiccup2.core :as h]
            [cheshire.core :as json]
            [fukan.cytoscape :as cytoscape]
            [fukan.model :as model]
            [clojure.string :as str]))

(def ^:private css
  "body {
    margin: 0;
    font-family: system-ui, -apple-system, sans-serif;
    background: #f5f5f5;
  }
  #container {
    display: flex;
    height: 100vh;
  }
  #graph-panel {
    flex: 1;
    display: flex;
    flex-direction: column;
    background: #fff;
  }
  #breadcrumb {
    padding: 0.75rem 1rem;
    background: #fff;
    border-bottom: 1px solid #e0e0e0;
    font-size: 0.9rem;
    color: #7f8c8d;
    display: flex;
    align-items: center;
    gap: 0.5rem;
  }
  #breadcrumb .crumb {
    color: #2980b9;
    cursor: pointer;
    padding: 0.25rem 0.5rem;
    border-radius: 4px;
    transition: background 0.15s;
  }
  #breadcrumb .crumb:hover {
    background: #e8f4f8;
  }
  #breadcrumb .crumb.current {
    color: #2c3e50;
    font-weight: 600;
    cursor: default;
  }
  #breadcrumb .crumb.current:hover {
    background: transparent;
  }
  #breadcrumb .separator {
    color: #bdc3c7;
    font-size: 0.8rem;
  }
  #cy {
    flex: 1;
    background: #fff;
  }
  #sidebar {
    width: 320px;
    border-left: 1px solid #ddd;
    padding: 1rem;
    overflow-y: auto;
    background: #fafafa;
  }
  #sidebar h3 {
    margin: 0 0 1rem 0;
    color: #333;
    font-size: 1.1rem;
  }
  #node-info {
    margin-top: 1rem;
  }
  #node-info h4 {
    margin: 0 0 0.25rem 0;
    color: #2c3e50;
  }
  #node-info .node-type {
    font-size: 0.85rem;
    color: #7f8c8d;
    margin-bottom: 0.75rem;
  }
  #node-info h5 {
    margin: 1rem 0 0.5rem 0;
    color: #34495e;
    font-size: 0.9rem;
  }
  #node-info ul {
    padding-left: 1.2rem;
    margin: 0;
  }
  #node-info li {
    cursor: pointer;
    padding: 0.2rem 0;
    color: #2980b9;
  }
  #node-info li:hover {
    text-decoration: underline;
  }
  .empty-state {
    color: #95a5a6;
    font-style: italic;
  }
  .dep-count {
    color: #7f8c8d;
    font-weight: normal;
  }
  .doc {
    margin: 0.75rem 0;
    padding: 0.75rem;
    background: #f8f9fa;
    border-left: 3px solid #3498db;
    font-size: 0.85rem;
    line-height: 1.5;
    color: #2c3e50;
    white-space: pre-wrap;
  }
  .doc-label {
    font-size: 0.75rem;
    color: #7f8c8d;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    margin-bottom: 0.25rem;
  }
  .loading-indicator {
    position: absolute;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    background: rgba(255, 255, 255, 0.9);
    padding: 1rem 1.5rem;
    border-radius: 8px;
    box-shadow: 0 2px 8px rgba(0,0,0,0.1);
    display: none;
    z-index: 1000;
    font-size: 0.9rem;
    color: #7f8c8d;
  }
  .loading-indicator.htmx-request {
    display: block;
  }
  @keyframes spin {
    to { transform: rotate(360deg); }
  }
  .spinner {
    display: inline-block;
    width: 16px;
    height: 16px;
    border: 2px solid #e0e0e0;
    border-top-color: #2980b9;
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
    margin-right: 0.5rem;
    vertical-align: middle;
  }")

(defn render-app-shell
  "Render the initial HTML page shell."
  []
  (str
   "<!DOCTYPE html>"
   (h/html
    [:html {:lang "en"}
     [:head
      [:meta {:charset "UTF-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      [:title "Fukan"]
      [:script {:src "https://unpkg.com/cytoscape@3.28.1/dist/cytoscape.min.js"}]
      [:script {:src "https://unpkg.com/dagre@0.8.5/dist/dagre.min.js"}]
      [:script {:src "https://unpkg.com/cytoscape-dagre@2.5.0/cytoscape-dagre.js"}]
      [:script {:src "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.7/bundles/datastar.js"
                :type "module"}]
      [:style css]]
     [:body
      [:div#container
        ;; Graph panel with Cytoscape event listeners and loading indicator
       [:div#graph-panel
        {"data-on:cy-select" "@get('/sse/sidebar?id=' + evt.detail.id)"
         "data-on:cy-navigate" "@get('/sse/view?id=' + evt.detail.id)"
         "data-indicator" "#loading"}
        [:div#breadcrumb]
        [:div#cy]
        [:div#loading.loading-indicator
         [:span.spinner]
         "Loading..."]]
       [:div#sidebar
        [:div#node-info
         [:p.empty-state "Click a node to see details"]]]]
      ;; Initial load trigger - reads URL for deep linking
      [:div {"data-init" "@get('/sse/view' + window.location.search)"}]
      [:script {:src "/public/app.js"}]]])))

;; -----------------------------------------------------------------------------
;; Breadcrumb

(defn- render-breadcrumb-html
  "Render breadcrumb items to HTML.
   Uses Datastar data-on:click for navigation."
  [items]
  (str
   (h/html
    [:div#breadcrumb
     (for [[idx {:keys [id label]}] (map-indexed vector items)
           :let [is-last (= idx (dec (count items)))
                 url (if id
                       (str "/sse/view?id=" id)
                       "/sse/view")]]
       (list
        (when (pos? idx)
          [:span.separator (h/raw "&rsaquo;")])
        (if is-last
          [:span.crumb.current label]
          [:span.crumb {"data-on:click" (str "@get('" url "')")} label])))])))

;; -----------------------------------------------------------------------------
;; Sidebar

(defn- render-empty-sidebar
  "Render empty sidebar state."
  []
  (str
   (h/html
    [:div#node-info
     [:p.empty-state "Click a node to see details"]])))

;; -----------------------------------------------------------------------------
;; Formatting helpers

(defn- format-var-id
  "Format a var ID for display (extract ns/name from var:ns/name)."
  [var-id]
  (if (and var-id (.startsWith var-id "var:"))
    (subs var-id 4)
    var-id))

(defn- format-ns-id
  "Format a namespace ID for display."
  [ns-id]
  (if (and ns-id (.startsWith ns-id "ns:"))
    (subs ns-id 3)
    ns-id))

(defn- render-var-info
  "Render the sidebar fragment for a var node."
  [{:keys [id label ns-sym doc]} deps dependents]
  (str
   (h/html
    [:div#node-info
     [:h4 label]

     (when doc
       [:div.doc doc])

     [:h5 "Dependencies " [:span.dep-count (str "(" (count deps) ")")]]
     (if (seq deps)
       [:ul
        (for [{:keys [to]} deps]
          [:li {"data-on:click" (str "@get('/sse/view?select=" to "')")}
           (format-var-id to)])]
       [:p.empty-state "None"])

     [:h5 "Dependents " [:span.dep-count (str "(" (count dependents) ")")]]
     (if (seq dependents)
       [:ul
        (for [{:keys [from]} dependents]
          [:li {"data-on:click" (str "@get('/sse/view?select=" from "')")}
           (format-var-id from)])]
       [:p.empty-state "None"])])))

(defn- render-namespace-info
  "Render the sidebar fragment for a namespace node."
  [{:keys [id label children doc]} deps dependents]
  (str
   (h/html
    [:div#node-info
     [:h4 label]

     (when doc
       [:div.doc doc])

     [:h5 "Dependencies " [:span.dep-count (str "(" (count deps) ")")]]
     (if (seq deps)
       [:ul
        (for [[target-id edge-count] deps]
          [:li {"data-on:click" (str "@get('/sse/view?select=" target-id "')")}
           (format-ns-id target-id)
           (when (> edge-count 1)
             [:span.dep-count (str " (" edge-count ")")])])]
       [:p.empty-state "None"])

     [:h5 "Dependents " [:span.dep-count (str "(" (count dependents) ")")]]
     (if (seq dependents)
       [:ul
        (for [[source-id edge-count] dependents]
          [:li {"data-on:click" (str "@get('/sse/view?select=" source-id "')")}
           (format-ns-id source-id)
           (when (> edge-count 1)
             [:span.dep-count (str " (" edge-count ")")])])]
       [:p.empty-state "None"])])))

(defn- render-folder-info
  "Render the sidebar fragment for a folder node."
  [{:keys [id label children]} deps dependents]
  (str
   (h/html
    [:div#node-info
     [:h4 label]

     [:h5 "Dependencies " [:span.dep-count (str "(" (count deps) ")")]]
     (if (seq deps)
       [:ul
        (for [[target-id edge-count] deps]
          [:li {"data-on:click" (str "@get('/sse/view?select=" target-id "')")}
           target-id
           (when (> edge-count 1)
             [:span.dep-count (str " (" edge-count ")")])])]
       [:p.empty-state "None"])

     [:h5 "Dependents " [:span.dep-count (str "(" (count dependents) ")")]]
     (if (seq dependents)
       [:ul
        (for [[source-id edge-count] dependents]
          [:li {"data-on:click" (str "@get('/sse/view?select=" source-id "')")}
           source-id
           (when (> edge-count 1)
             [:span.dep-count (str " (" edge-count ")")])])]
       [:p.empty-state "None"])])))

(defn- render-sidebar-html
  "Render the sidebar content for a node.
   Returns empty state if node is nil."
  [{:keys [node deps dependents]}]
  (if node
    (case (:kind node)
      :var (render-var-info node deps dependents)
      :namespace (render-namespace-info node deps dependents)
      :folder (render-folder-info node deps dependents)
      (str (h/html [:div#node-info [:p.empty-state "Unknown node type"]])))
    (render-empty-sidebar)))

;; -----------------------------------------------------------------------------
;; View Computation - Unified view generation for any entity

(defn- compute-namespace-deps
  "Compute aggregated dependencies for a namespace.
   Returns {target-ns-id -> edge-count}."
  [ns-id model]
  (let [children (get-in model [:nodes ns-id :children] #{})
        ;; Get all edges from vars in this namespace
        edges (mapcat #(get (:edges-by-from model) %) children)]
    ;; Group by target namespace and count
    (->> edges
         (map (fn [{:keys [to]}]
                (let [target-node (get-in model [:nodes to])
                      target-ns (:parent target-node)]
                  target-ns)))
         (remove #{ns-id}) ; exclude self-references
         (frequencies))))

(defn- compute-namespace-dependents
  "Compute aggregated dependents for a namespace.
   Returns {source-ns-id -> edge-count}."
  [ns-id model]
  (let [children (get-in model [:nodes ns-id :children] #{})
        ;; Get all edges to vars in this namespace
        edges (mapcat #(get (:edges-by-to model) %) children)]
    ;; Group by source namespace and count
    (->> edges
         (map (fn [{:keys [from]}]
                (let [source-node (get-in model [:nodes from])
                      source-ns (:parent source-node)]
                  source-ns)))
         (remove #{ns-id}) ; exclude self-references
         (frequencies))))

(defn- edges-for-entity
  "Get the appropriate edge indexes for an entity based on its kind."
  [m entity-id]
  (case (get-in m [:nodes entity-id :kind])
    :var {:by-from (:edges-by-from m)
          :by-to (:edges-by-to m)}
    :namespace {:by-from (:ns-edges-by-from m)
                :by-to (:ns-edges-by-to m)}
    :folder {:by-from (:folder-edges-by-from m)
             :by-to (:folder-edges-by-to m)}
    ;; Default fallback
    {:by-from {} :by-to {}}))

(defn- breadcrumb-label
  "Get a short label for breadcrumb display.
   For namespaces, returns just the last segment (e.g. 'handler' instead of 'fukan.web.handler')."
  [node]
  (let [label (:label node)
        kind (:kind node)]
    (if (= kind :namespace)
      ;; Extract last segment of namespace
      (last (str/split label #"\."))
      label)))

(defn- find-root-node
  "Find the root node (node with parent = nil)."
  [m]
  (->> (vals (:nodes m))
       (filter #(nil? (:parent %)))
       (filter #(not= (:kind %) :var))  ; Root should be folder or namespace
       first))

(defn- compute-breadcrumb-
  "Compute breadcrumb path from root to entity.
   Returns a list of {:id :label} maps."
  [m entity-id]
  (let [root-node (find-root-node m)
        root-id (:id root-node)]
    (if (or (nil? entity-id) (= entity-id root-id))
      ;; At root - just show root label
      [{:id nil :label (or (breadcrumb-label root-node) "root")}]
      ;; Build path from root to entity
      (let [path (loop [current-id entity-id
                        acc []]
                   (let [node (get-in m [:nodes current-id])]
                     (if (or (nil? node) (nil? (:parent node)))
                       acc
                       (recur (:parent node)
                              (cons {:id current-id :label (breadcrumb-label node)} acc)))))]
        (cons {:id nil :label (or (breadcrumb-label root-node) "root")} path)))))

(defn- get-children
  "Get the children of an entity (entities whose parent is this entity)."
  [m entity-id]
  (get-in m [:nodes entity-id :children] #{}))

(defn- get-child-edges
  "Get edges at the child level (one level down from entity).
   For a folder, these are namespace-level edges.
   For a namespace, these are var-level edges."
  [m entity-id]
  (let [entity-kind (get-in m [:nodes entity-id :kind])]
    (case entity-kind
      :folder (:ns-edges m)
      :namespace (:edges m)
      ;; vars have no children, so no child-level edges
      [])))

(defn- compute-container-view
  "Compute view when the selected entity is a container (has children).
   
   Shows:
   - The selected container
   - All children of the container (inside the selected container)
   - Edges between children (sibling relationships)
   - External entities that children relate to (grouped in their own containers)
   - Cross-container edges from children to external entities"
  [m entity-id]
  (let [children-ids (get-children m entity-id)
        children-set (set children-ids)

        ;; Get edges at the child level
        child-edges (get-child-edges m entity-id)

        ;; Find edges between children (sibling edges)
        sibling-edges (->> child-edges
                           (filter (fn [{:keys [from to]}]
                                     (and (contains? children-set from)
                                          (contains? children-set to)))))

        ;; Find edges from children to entities outside this container
        outgoing-edges (->> child-edges
                            (filter (fn [{:keys [from to]}]
                                      (and (contains? children-set from)
                                           (not (contains? children-set to))))))

        incoming-edges (->> child-edges
                            (filter (fn [{:keys [from to]}]
                                      (and (contains? children-set to)
                                           (not (contains? children-set from))))))

        cross-container-edges (concat outgoing-edges incoming-edges)

        ;; Collect the external related entities (entities outside this container)
        external-entities (->> cross-container-edges
                               (mapcat (fn [{:keys [from to]}]
                                         [(when-not (contains? children-set from) from)
                                          (when-not (contains? children-set to) to)]))
                               (remove nil?)
                               (into #{}))

        ;; Find parent containers for external entities
        ;; These become top-level compound nodes (siblings of the selected container)
        ;; Exclude containers that are already children of the selected entity
        external-containers (->> external-entities
                                 (map #(:parent (get-in m [:nodes %])))
                                 (remove nil?)
                                 (remove #{entity-id}) ; don't include self
                                 (remove children-set) ; don't include children (they're already shown)
                                 (into #{}))

        ;; The selected container (root of this view)
        selected-node {:id entity-id
                       :label (:label (get-in m [:nodes entity-id]))
                       :kind (name (:kind (get-in m [:nodes entity-id])))
                       :originalId entity-id
                       :childCount (count children-ids)}

        ;; All children are simple nodes inside the selected container
        child-nodes (for [cid children-ids
                          :let [node (get-in m [:nodes cid])]
                          :when node]
                      (-> (cytoscape/node->cytoscape node)
                          (assoc :parent entity-id)))

        ;; External container nodes (top-level, not nested)
        external-container-nodes (for [ecid external-containers
                                       :let [node (get-in m [:nodes ecid])]
                                       :when node]
                                   {:id ecid
                                    :label (:label node)
                                    :kind (name (:kind node))
                                    :originalId ecid
                                    :childCount (count (get-children m ecid))})

        ;; External entity nodes (inside their parent containers)
        external-entity-nodes (for [eid external-entities
                                    :let [node (get-in m [:nodes eid])]
                                    :when node]
                                (-> (cytoscape/node->cytoscape node)
                                    (assoc :parent (:parent node))))

        ;; Edges between external entities (within the same external container)
        ;; TODO: This is a tactical fix. The approach of "partially showing" contents
        ;; of child containers is complex and may need rethinking for a cleaner model.
        internal-external-edges (->> child-edges
                                     (filter (fn [{:keys [from to]}]
                                               (and (contains? external-entities from)
                                                    (contains? external-entities to)))))

        ;; All edges to show
        all-edges (distinct (concat sibling-edges cross-container-edges internal-external-edges))
        edges (->> all-edges
                   (map-indexed (fn [idx {:keys [from to]}]
                                  {:id (str "e" idx)
                                   :source from
                                   :target to})))]

    {:nodes (vec (concat [selected-node]
                         child-nodes
                         external-container-nodes
                         external-entity-nodes))
     :edges (vec edges)}))

(defn- compute-leaf-view
  "Compute view when the selected entity is a leaf (no children).
   
   Shows:
   - The selected entity
   - All entities it has relationships with (both directions)
   - Grouped by their parent container
   - Only edges involving the selected entity"
  [m entity-id]
  (let [{:keys [by-from by-to]} (edges-for-entity m entity-id)

        ;; Get related entities via edges (both directions)
        outgoing (get by-from entity-id [])
        incoming (get by-to entity-id [])
        outgoing-ids (map :to outgoing)
        incoming-ids (map :from incoming)
        related-ids (into #{} (concat outgoing-ids incoming-ids))

        ;; All visible entities
        visible-ids (conj related-ids entity-id)
        visible-nodes (keep #(get-in m [:nodes %]) visible-ids)

        ;; Group by parent for compound nodes
        parent-ids (->> visible-nodes
                        (map :parent)
                        (remove nil?)
                        (into #{}))

        ;; Container nodes for grouping
        container-nodes (for [pid parent-ids
                              :let [pnode (get-in m [:nodes pid])]
                              :when pnode]
                          {:id pid
                           :label (:label pnode)
                           :kind (name (:kind pnode))
                           :originalId pid
                           :childCount (count (get-children m pid))})

        ;; Entity nodes
        entity-nodes (for [n visible-nodes]
                       (-> (cytoscape/node->cytoscape n)
                           (assoc :parent (:parent n))))

        ;; Only edges involving the selected entity
        all-edges (concat outgoing incoming)
        edges (->> all-edges
                   distinct
                   (map-indexed (fn [idx {:keys [from to]}]
                                  {:id (str "e" idx)
                                   :source from
                                   :target to})))]

    {:nodes (vec (concat container-nodes entity-nodes))
     :edges (vec edges)}))

(defn- compute-graph-
  "Compute graph data for any entity.
   
   For containers (entities with children):
   - Shows all children
   - Shows edges between children (sibling relationships)
   - If children relate to entities in sibling containers, shows those explicitly
   
   For leaves (entities without children):
   - Shows the entity and all its relationships
   - Groups related entities by their parent container
   
   Returns {:nodes :edges :selectedId} in Cytoscape format."
  [m entity-id]
  (let [;; If no entity-id, use root
        entity-id (or entity-id (:id (find-root-node m)))
        children-ids (get-children m entity-id)

        ;; Dispatch based on whether entity has children
        base-view (if (seq children-ids)
                    (compute-container-view m entity-id)
                    (compute-leaf-view m entity-id))]

    (assoc base-view :selectedId entity-id)))

(defn- compute-sidebar-
  "Compute the sidebar data for a node.
   Returns {:node :deps :dependents} map."
  [model node-id]
  (let [node (get-in model [:nodes node-id])
        deps (when node
               (case (:kind node)
                 :var (get (:edges-by-from model) node-id [])
                 :namespace (compute-namespace-deps node-id model)
                 nil))
        dependents (when node
                     (case (:kind node)
                       :var (get (:edges-by-to model) node-id [])
                       :namespace (compute-namespace-dependents node-id model)
                       nil))]
    {:node node :deps deps :dependents dependents}))

(defn- compute-highlighted-edges-
  "Compute which edges should be highlighted for a selected node."
  [edges selected-id]
  (when selected-id
    (->> edges
         (keep (fn [{:keys [id source target]}]
                 (when (or (= source selected-id)
                           (= target selected-id))
                   id)))
         vec)))

;; -----------------------------------------------------------------------------
;; Public Render API
;;
;; All public render functions take (model, editor-state) and return output.
;; editor-state is a map with :view-id and/or :selected-id

(defn render-graph
  "Render graph data for Cytoscape.
   Takes model and editor-state with :view-id and :selected-id.
   Returns {:nodes :edges :selectedId :highlightedEdges}."
  [model {:keys [view-id selected-id]}]
  (let [graph (compute-graph- model view-id)
        selected-id (or selected-id (:selectedId graph))
        highlighted-edges (compute-highlighted-edges- (:edges graph) selected-id)]
    {:nodes (:nodes graph)
     :edges (:edges graph)
     :selectedId selected-id
     :highlightedEdges highlighted-edges}))

(defn render-breadcrumb
  "Render the breadcrumb navigation HTML.
   Takes model and editor-state with :view-id."
  [model {:keys [view-id]}]
  (let [items (compute-breadcrumb- model view-id)]
    (render-breadcrumb-html items)))

(defn render-sidebar
  "Render the sidebar HTML.
   Takes model and editor-state with :selected-id."
  [model {:keys [selected-id]}]
  (let [data (compute-sidebar- model selected-id)]
    (render-sidebar-html data)))
