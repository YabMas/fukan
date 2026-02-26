(ns fukan.web.views.schema
  "Render Malli schemas to HTML/hiccup."
  (:require [clojure.string :as str])
  (:import [java.net URLEncoder]))

;; -----------------------------------------------------------------------------
;; Schema Rendering - Direct rendering of Malli schemas to hiccup

(declare render-schema-form)

(defn- url-encode [s] (URLEncoder/encode (str s) "UTF-8"))

(defn- render-schema-ref
  "Render a clickable schema reference."
  [schema-key]
  (let [k (if (keyword? schema-key) schema-key (keyword schema-key))]
    [:span.schema-ref
     {"data-on:click" (str "@get('/sse/schema?id=" (url-encode (str (namespace k) "/" (name k))) "')")}
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
    (let [[type & raw-args] schema-form
          ;; Skip Malli property map if present (e.g. [:enum {:description "..."} :a :b])
          args (if (map? (first raw-args)) (rest raw-args) raw-args)]
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

(defn render-schema-detail-view
  "Render the full detail view for a schema form."
  [schema-form]
  (if (and (vector? schema-form) (= :map (first schema-form)))
    ;; Map schema - show entries
    (render-map-schema-entries (rest schema-form))
    ;; Other schema types - show inline
    [:div (render-schema-form schema-form)]))

