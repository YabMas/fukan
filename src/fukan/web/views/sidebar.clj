(ns fukan.web.views.sidebar
  "Render sidebar panels for entity details.
   Generic renderer dispatches on normalized entity detail shape."
  (:require [hiccup2.core :as h]
            [clojure.string :as str]
            [fukan.web.views.schema :as views.schema])
  (:import [java.net URLEncoder]))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema Html :string)

(defn- url-encode [s] (URLEncoder/encode (str s) "UTF-8"))

;; -----------------------------------------------------------------------------
;; Plain-text schema helpers

(defn- schema->str
  "Convert a malli schema form to a short plain-text string."
  [form]
  (cond
    (keyword? form) (name form)
    (and (vector? form) (seq form))
    (let [[t & args] form]
      (case t
        :vector (str "[" (schema->str (first args)) "]")
        :set    (str "#{" (schema->str (first args)) "}")
        :map-of (str "{" (schema->str (first args)) " " (schema->str (second args)) "}")
        :maybe  (str (schema->str (first args)) "?")
        :cat    (str/join ", " (map schema->str args))
        :or     (str/join " | " (map schema->str args))
        :enum   (str/join " | " (map pr-str args))
        :tuple  (str "[" (str/join ", " (map schema->str args)) "]")
        (name t)))
    :else (pr-str form)))

(defn- fn-signature-str
  "Format a [:=> ...] schema as 'input -> output' string."
  [schema]
  (when (and (vector? schema) (= :=> (first schema)))
    (let [[_ input output] schema
          ins (if (and (vector? input) (= :cat (first input)))
                (rest input)
                [input])]
      (str (str/join ", " (map schema->str ins)) " → " (schema->str output)))))

;; -----------------------------------------------------------------------------
;; Shared components

(defn- render-fn-list
  "Render a list of functions with optional signatures.
   Each entry is {:name :schema :id (optional)}.
   When :id is present, the item is clickable."
  [fns]
  [:ul
   (for [{:keys [name schema id]} fns]
     (let [attrs (when id
                   {"data-on:click" (str "@get('/sse/view?select=" (url-encode id) "')")})]
       [:li attrs
        name
        (when-let [sig (fn-signature-str schema)]
          [:div.sig sig])]))])

(defn- render-dep-list
  "Render a dependency or dependents section with heading and clickable items."
  [heading items]
  (list
   [:h5 heading " " [:span.dep-count (str "(" (count items) ")")]]
   (if (seq items)
     [:ul
      (for [[target-id {:keys [count label]}] items]
        [:li {"data-on:click" (str "@get('/sse/view?select=" (url-encode target-id) "')")}
         label
         (when (> count 1)
           [:span.dep-count (str " (" count ")")])])]
     [:p.empty-state "None"])))

(defn- render-description
  "Render a description section. Returns nil if text is nil."
  [text]
  (when text
    [:div.doc text]))

(defn- schema-click-url
  "Build the SSE schema click URL for a schema keyword.
   Handles both qualified (:ns/Name) and unqualified (:Name) keywords."
  [key]
  (str "@get('/sse/schema?id=" (url-encode (subs (str key) 1)) "')"))

(defn- render-schema-refs
  "Render a clickable schema reference list. Returns nil if schemas is nil."
  [schemas]
  (when (seq schemas)
    (list
     [:h5 "Schemas " [:span.dep-count (str "(" (count schemas) ")")]]
     [:ul.schema-list
      (for [{:keys [key]} (sort-by :key schemas)]
        [:li {"data-on:click" (schema-click-url key)}
         (name key)])])))

(defn- render-dataflow
  "Render a Dataflow section showing input and output schema types.
   Returns nil if dataflow is nil."
  [{:keys [inputs outputs]}]
  (when (or (seq inputs) (seq outputs))
    (list
     [:h5 "Dataflow"]
     (when (seq inputs)
       (list
        [:h6 "Inputs"]
        [:ul
         (for [{:keys [key]} inputs]
           [:li {"data-on:click" (schema-click-url key)}
            (name key)])]))
     (when (seq outputs)
       (list
        [:h6 "Outputs"]
        [:ul
         (for [{:keys [key]} outputs]
           [:li {"data-on:click" (schema-click-url key)}
            (name key)])])))))

(defn- render-interface
  "Render the interface section based on its type.
   Dispatches to the appropriate sub-renderer."
  [{:keys [type items]}]
  (case type
    :fn-list
    (list
     [:h5 "Public API " [:span.dep-count (str "(" (count items) ")")]]
     (render-fn-list items))

    :fn-inline
    (views.schema/render-fn-signature (first items))

    :schema-def
    [:div.schema-detail
     (views.schema/render-schema-detail-view (first items))]

    :name-list
    (list
     [:h5 "Functions"]
     [:ul.schema-list
      (for [fn-name items]
        [:li fn-name])])

    nil))

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
;; Entity renderers

(defn- render-entity-detail
  "Generic renderer for all non-edge entity types.
   Iterates through sections in order: label, description, interface, schemas, dataflow, deps, dependents."
  [{:keys [label kind description interface schemas dataflow deps dependents]}]
  (str
   (h/html
    [:div#node-info
     [:h4 label " " [:span.kind-badge (name kind)]]

     (render-description description)

     (when interface
       (render-interface interface))

     (render-schema-refs schemas)

     (when dataflow
       (render-dataflow dataflow))

     (render-dep-list "Dependencies" deps)
     (render-dep-list "Dependents" dependents)])))

(defn- render-edge-detail
  "Dedicated renderer for edge entities."
  [{:keys [label called-fns]}]
  (str
   (h/html
    [:div#node-info
     [:h4 label]
     [:h5 "Functions Called " [:span.dep-count (str "(" (count called-fns) ")")]]
     (if (seq called-fns)
       (render-fn-list called-fns)
       [:p.empty-state "No direct function calls"])])))

;; -----------------------------------------------------------------------------
;; Public API

(defn render-sidebar-html
  "Render the sidebar content for an entity detail.
   Takes a normalized entity detail map from projection/details."
  {:malli/schema [:=> [:cat :EntityDetails] :Html]}
  [entity-detail]
  (if (:label entity-detail)
    (if (= :edge (:kind entity-detail))
      (render-edge-detail entity-detail)
      (render-entity-detail entity-detail))
    (render-empty-sidebar)))

