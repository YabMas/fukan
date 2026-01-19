(ns fukan.web.views.sidebar
  "Render sidebar panels for different node types."
  (:require [hiccup2.core :as h]
            [fukan.web.views.schema :as views.schema]))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema Html :string)

;; -----------------------------------------------------------------------------
;; Node data accessors (via :data map)

(defn- node-ns-sym [node]
  (get-in node [:data :ns-sym]))

(defn- node-var-sym [node]
  (get-in node [:data :var-sym]))

(defn- node-doc [node]
  (get-in node [:data :doc]))

(defn- node-schema-key [node]
  (get-in node [:data :schema-key]))

;; -----------------------------------------------------------------------------
;; Schema helpers

(defn- get-var-schema
  "Get the malli schema from a var's metadata, if present."
  [ns-sym var-sym]
  (try
    (when-let [v (ns-resolve (find-ns ns-sym) var-sym)]
      (:malli/schema (meta v)))
    (catch Exception _ nil)))

;; -----------------------------------------------------------------------------
;; Empty state

(defn- render-empty-sidebar
  "Render empty sidebar state."
  []
  (str
   (h/html
    [:div#node-info
     [:p.empty-state "Click a node to see details"]])))

;; -----------------------------------------------------------------------------
;; Node type renderers

(defn- render-var-info
  "Render the sidebar fragment for a var node."
  [node deps dependents]
  (let [label (:label node)
        ns-sym (node-ns-sym node)
        var-sym (node-var-sym node)
        doc (node-doc node)
        fn-schema (get-var-schema ns-sym var-sym)]
    (str
     (h/html
      [:div#node-info
       [:h4 label]

       (when doc
         [:div.doc doc])

       ;; Function signature from schema
       (views.schema/render-fn-signature fn-schema)

       [:h5 "Dependencies " [:span.dep-count (str "(" (count deps) ")")]]
       (if (seq deps)
         [:ul
          (for [[target-id {:keys [count label]}] deps]
            [:li {"data-on:click" (str "@get('/sse/view?select=" target-id "')")}
             label
             (when (> count 1)
               [:span.dep-count (str " (" count ")")])])]
         [:p.empty-state "None"])

       [:h5 "Dependents " [:span.dep-count (str "(" (count dependents) ")")]]
       (if (seq dependents)
         [:ul
          (for [[source-id {:keys [count label]}] dependents]
            [:li {"data-on:click" (str "@get('/sse/view?select=" source-id "')")}
             label
             (when (> count 1)
               [:span.dep-count (str " (" count ")")])])]
         [:p.empty-state "None"])]))))

(defn- render-namespace-info
  "Render the sidebar fragment for a namespace node."
  [ns-schemas node deps dependents]
  (let [label (:label node)
        doc (node-doc node)]
    (str
     (h/html
      [:div#node-info
       [:h4 label]

       (when doc
         [:div.doc doc])

       (views.schema/render-schema-list ns-schemas)

       [:h5 "Dependencies " [:span.dep-count (str "(" (count deps) ")")]]
       (if (seq deps)
         [:ul
          (for [[target-id {:keys [count label]}] deps]
            [:li {"data-on:click" (str "@get('/sse/view?select=" target-id "')")}
             label
             (when (> count 1)
               [:span.dep-count (str " (" count ")")])])]
         [:p.empty-state "None"])

       [:h5 "Dependents " [:span.dep-count (str "(" (count dependents) ")")]]
       (if (seq dependents)
         [:ul
          (for [[source-id {:keys [count label]}] dependents]
            [:li {"data-on:click" (str "@get('/sse/view?select=" source-id "')")}
             label
             (when (> count 1)
               [:span.dep-count (str " (" count ")")])])]
         [:p.empty-state "None"])]))))

(defn- render-folder-info
  "Render the sidebar fragment for a folder node."
  [node deps dependents]
  (let [label (:label node)]
    (str
     (h/html
      [:div#node-info
       [:h4 label]

       [:h5 "Dependencies " [:span.dep-count (str "(" (count deps) ")")]]
       (if (seq deps)
         [:ul
          (for [[target-id {:keys [count label]}] deps]
            [:li {"data-on:click" (str "@get('/sse/view?select=" target-id "')")}
             label
             (when (> count 1)
               [:span.dep-count (str " (" count ")")])])]
         [:p.empty-state "None"])

       [:h5 "Dependents " [:span.dep-count (str "(" (count dependents) ")")]]
       (if (seq dependents)
         [:ul
          (for [[source-id {:keys [count label]}] dependents]
            [:li {"data-on:click" (str "@get('/sse/view?select=" source-id "')")}
             label
             (when (> count 1)
               [:span.dep-count (str " (" count ")")])])]
         [:p.empty-state "None"])]))))

(defn- render-schema-node-info
  "Render the sidebar fragment for a schema node.
   Delegates to render-schema-detail."
  [schema-form node]
  (let [schema-key (node-schema-key node)]
    (views.schema/render-schema-detail (str (namespace schema-key) "/" (name schema-key)) schema-form)))

(defn- render-interface-info
  "Render the sidebar fragment for an interface node."
  [node]
  (let [description (get-in node [:data :description])
        functions (get-in node [:data :functions])]
    (str
     (h/html
      [:div#node-info
       [:h4 "Interface"]
       (when description
         [:div.doc description])
       (when (seq functions)
         (list
          [:h5 "Functions"]
          [:ul.schema-list
           (for [fn-name functions]
             [:li fn-name])]))]))))

(defn- render-edge-info
  "Render the sidebar fragment for an edge selection.
   Shows the target functions being called."
  [node underlying-edges total-count]
  (let [called-fns (->> underlying-edges
                        (map :to-var)
                        (distinct)
                        (sort-by :label))]
    (str
     (h/html
      [:div#node-info
       [:h5 "Functions Called " [:span.dep-count (str "(" (count called-fns) ")")]]
       (if (seq called-fns)
         [:ul
          (for [{:keys [id label signature]} called-fns]
            [:li
             {"data-on:click" (str "@get('/sse/view?select=" id "')")}
             label
             (when signature
               (views.schema/render-fn-signature signature))])]
         [:p.empty-state "No direct function calls"])]))))

;; -----------------------------------------------------------------------------
;; Public API

(defn render-sidebar-html
  "Render the sidebar content for a node or edge.
   Returns empty state if node is nil.
   Takes sidebar data map with :node :deps :dependents :ns-schemas :schema-form
   or for edges: :node :underlying-edges :total-count."
  {:malli/schema [:=> [:cat :EntityDetails] :Html]}
  [{:keys [node deps dependents ns-schemas schema-form underlying-edges total-count]}]
  (if node
    (case (:kind node)
      :var (render-var-info node deps dependents)
      :namespace (render-namespace-info ns-schemas node deps dependents)
      :folder (render-folder-info node deps dependents)
      :schema (render-schema-node-info schema-form node)
      :interface (render-interface-info node)
      :edge (render-edge-info node underlying-edges total-count)
      (str (h/html [:div#node-info [:p.empty-state "Unknown node type"]])))
    (render-empty-sidebar)))

(defn render-schema-sidebar
  "Render the sidebar for viewing a schema definition.
   Takes a schema-id string like 'fukan.model/Model' and the pre-computed schema form."
  {:malli/schema [:=> [:cat :string :any] :Html]}
  [schema-id schema-form]
  (views.schema/render-schema-detail schema-id schema-form))
