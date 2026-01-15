(ns fukan.web.views.schema
  "Render Malli schemas to HTML/hiccup."
  (:require [hiccup2.core :as h]
            [fukan.schema :as schema]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Schema Rendering - Direct rendering of Malli schemas to hiccup

(declare render-schema-form)

(defn- render-schema-ref
  "Render a clickable schema reference."
  [schema-key]
  (let [k (if (keyword? schema-key) schema-key (keyword schema-key))]
    [:span.schema-ref
     {"data-on:click" (str "@get('/sse/schema?id=" (namespace k) "/" (name k) "')")}
     (name k)]))

(defn- render-schema-form
  "Render a Malli schema form directly to hiccup.
   Handles qualified keywords as clickable refs, and various schema types."
  [schema-form]
  (cond
    ;; Qualified keyword - clickable schema reference
    (qualified-keyword? schema-form)
    (render-schema-ref schema-form)

    ;; Simple keyword (built-in type like :string, :int)
    (keyword? schema-form)
    [:span.type (name schema-form)]

    ;; Vector form like [:vector X], [:map ...], [:=> ...]
    (vector? schema-form)
    (let [[type & args] schema-form]
      (case type
        :vector [:span "[" (render-schema-form (first args)) ", ...]"]
        :set [:span "#{" (render-schema-form (first args)) ", ...}"]
        :map-of [:span "{" (render-schema-form (first args)) " -> " (render-schema-form (second args)) "}"]
        :maybe [:span (render-schema-form (first args)) "?"]
        :or (interpose " | " (map render-schema-form args))
        :and (interpose " & " (map render-schema-form args))
        :enum [:span (str/join " | " (map pr-str args))]
        :tuple [:span "[" (interpose ", " (map render-schema-form args)) "]"]
        :cat (interpose ", " (map render-schema-form args))
        :map [:span "map"]
        :fn [:span "fn"]
        ;; Default
        [:span (str type)]))

    ;; Fallback
    :else
    [:span (pr-str schema-form)]))

(defn render-fn-signature
  "Render a function signature from its schema directly to hiccup."
  [fn-schema]
  (when (and (vector? fn-schema) (= :=> (first fn-schema)))
    (let [[_ input output] fn-schema
          in-schemas (if (and (vector? input) (= :cat (first input)))
                       (rest input)
                       [input])]
      [:div.signature
       "("
       (interpose ", " (map render-schema-form in-schemas))
       ")"
       [:span.arrow " → "]
       (render-schema-form output)])))

(defn- render-map-schema-entries
  "Render the entries of a map schema.
   Handles [:map [:key opts? schema] ...] format."
  [entries]
  (for [entry entries
        :when (vector? entry)]
    (let [[k second-elem & rest] entry
          [opts child-schema] (if (map? second-elem)
                                [second-elem (first rest)]
                                [{} second-elem])]
      [:div.entry
       [:span.key (str k)]
       (when (:optional opts) [:span.optional " (optional)"])
       " : "
       (render-schema-form child-schema)])))

(defn- render-schema-detail-view
  "Render the full detail view for a schema form."
  [schema-form]
  (if (and (vector? schema-form) (= :map (first schema-form)))
    ;; Map schema - show entries
    (render-map-schema-entries (rest schema-form))
    ;; Other schema types - show inline
    [:div (render-schema-form schema-form)]))

;; -----------------------------------------------------------------------------
;; Public API

(defn render-schema-list
  "Render the schemas section for a namespace sidebar.
   Returns nil if no schemas exist."
  [ns-str]
  (let [ns-schemas (schema/schemas-for-ns ns-str)]
    (when (seq ns-schemas)
      (list
        [:h5 "Schemas " [:span.dep-count (str "(" (count ns-schemas) ")")]]
        [:ul.schema-list
         (for [schema-key (sort ns-schemas)]
           [:li {"data-on:click" (str "@get('/sse/schema?id=" (namespace schema-key) "/" (name schema-key) "')")}
            (name schema-key)])]))))

(defn render-schema-detail
  "Render the sidebar for viewing a schema definition.
   Takes a schema key like 'fukan.model/Model'."
  [schema-key-str]
  (let [[ns-part name-part] (str/split schema-key-str #"/" 2)
        schema-key (keyword ns-part name-part)
        schema-form (schema/get-schema schema-key)]
    (str
     (h/html
      (if schema-form
        [:div#node-info
         ;; Back link to namespace
         [:span.back-link
          {"data-on:click" (str "@get('/sse/view?id=ns:" ns-part "')")}
          (str "← " ns-part)]

         [:h4 name-part]

         [:div.schema-detail
          (render-schema-detail-view schema-form)]]
        [:div#node-info
         [:p.empty-state (str "Schema not found: " schema-key-str)]])))))
