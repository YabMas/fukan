(ns fukan.web.views.schema
  "Render Malli schemas to HTML/hiccup.
   Supports registry-aware rendering for the detail view and
   plain rendering for function signatures."
  (:require [clojure.string :as str]
            [fukan.schema.forms :as forms])
  (:import [java.net URLEncoder]))

;; -----------------------------------------------------------------------------
;; Helpers

(declare render-detail-form)

(defn- url-encode [s] (URLEncoder/encode (str s) "UTF-8"))

(defn- schema-ref-url
  "Build the SSE URL for navigating to a schema.
   Handles both qualified and unqualified keywords.
   Appends trail parameter for drill-down navigation."
  [schema-key trail]
  (let [k (if (keyword? schema-key) schema-key (keyword schema-key))
        id-str (if (namespace k)
                 (str (namespace k) "/" (name k))
                 (name k))
        trail-str (when (seq trail)
                    (str "&trail=" (url-encode (str/join "," trail))))]
    (str "@get('/sse/schema?id=" (url-encode id-str) (or trail-str "") "')")))

;; -----------------------------------------------------------------------------
;; Plain rendering (for function signatures, no registry)

(defn- render-schema-ref
  "Render a clickable schema reference (plain mode, no trail)."
  [schema-key]
  [:span.schema-ref
   {"data-on:click" (schema-ref-url schema-key nil)}
   (name schema-key)])

(defn- render-schema-form
  "Render a Malli schema form directly to hiccup.
   Handles qualified keywords as clickable refs, and various schema types.
   Used for function signatures where registry resolution isn't needed."
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
    (let [type (forms/form-tag schema-form)
          args (forms/form-children schema-form)]
      (case type
        :vector [:span "[" (render-schema-form (first args)) "]"]
        :set [:span "#{" (render-schema-form (first args)) "}"]
        :map-of [:span "{" (render-schema-form (first args)) " \u2192 " (render-schema-form (second args)) "}"]
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

;; -----------------------------------------------------------------------------
;; Detail rendering (registry-aware, with drill-down navigation)

(defn- render-detail-ref
  "Render a named schema reference in detail mode.
   Always shows as a clickable link with the schema name.
   Description tooltip shown when available."
  [schema-key registry trail]
  (let [{:keys [doc]} (get registry schema-key)
        attrs (cond-> {"data-on:click" (schema-ref-url schema-key trail)}
                doc (assoc :title doc))]
    [:span.schema-ref.schema-drilldown attrs (name schema-key)]))

(defn- render-detail-form
  "Render a Malli schema form to hiccup with registry resolution.
   Resolves named refs to show their shape inline when simple."
  [schema-form registry trail]
  (cond
    ;; Named schema ref - resolve from registry
    (qualified-keyword? schema-form)
    (render-detail-ref schema-form registry trail)

    ;; Unqualified keyword that's in the registry (e.g. :NodeKind)
    (and (keyword? schema-form) (contains? registry schema-form))
    (render-detail-ref schema-form registry trail)

    ;; Simple keyword (built-in type like :string, :int)
    (keyword? schema-form)
    [:span.type (name schema-form)]

    ;; Vector form
    (vector? schema-form)
    (let [type (forms/form-tag schema-form)
          args (forms/form-children schema-form)]
      (case type
        :vector [:span "[" (render-detail-form (first args) registry trail) "]"]
        :set [:span "#{" (render-detail-form (first args) registry trail) "}"]
        :map-of [:span "{" (render-detail-form (first args) registry trail)
                 " \u2192 " (render-detail-form (second args) registry trail) "}"]
        :maybe [:span (render-detail-form (first args) registry trail) "?"]
        :or [:span.schema-or (interpose [:span.schema-sep " | "]
                                        (map #(render-detail-form % registry trail) args))]
        :and (interpose " & " (map #(render-detail-form % registry trail) args))
        :enum [:span.type (str/join " | " (map pr-str args))]
        :tuple [:span "[" (interpose ", " (map #(render-detail-form % registry trail) args)) "]"]
        :cat (interpose ", " (map #(render-detail-form % registry trail) args))
        :=> (let [{:keys [inputs output]} (forms/fn-schema-parts schema-form)]
              [:span "("
               (interpose ", " (map #(render-detail-form % registry trail) inputs))
               " \u2192 "
               (render-detail-form output registry trail)
               ")"])
        :map [:span.type "map"]
        :fn [:span.type "fn"]
        [:span (str type)]))

    :else
    [:span (pr-str schema-form)]))

;; -----------------------------------------------------------------------------
;; Map entry rendering

(defn- render-map-entries
  "Render the entries of a map schema with registry resolution.
   Each entry shows key, optionality, and the resolved type."
  [entries registry trail]
  (for [entry entries
        :when (vector? entry)]
    (let [[k second-elem & rest-elems] entry
          [opts child-schema] (if (map? second-elem)
                                [second-elem (first rest-elems)]
                                [{} second-elem])
          entry-desc (:description opts)]
      [:div.entry
       [:span.key (str k)]
       (when (:optional opts) [:span.optional "?"])
       [:span.entry-sep " : "]
       [:span.entry-type (render-detail-form child-schema registry trail)]
       (when entry-desc
         [:div.entry-doc entry-desc])])))

;; -----------------------------------------------------------------------------
;; Or-variant rendering

(defn- render-or-variants
  "Render the variants of an :or schema, each as a separate block."
  [variants registry trail]
  [:div.schema-variants
   (for [[idx variant] (map-indexed vector variants)]
     (if (and (vector? variant) (= :map (first variant)))
       ;; Map variant - show entries in a block
       (let [desc (forms/form-description variant)
             entries (filter vector? (rest variant))]
         [:div.schema-variant
          [:div.variant-label (str "variant " (inc idx))]
          (when desc [:div.variant-doc desc])
          (render-map-entries entries registry trail)])
       ;; Non-map variant - show inline
       [:div.schema-variant
        [:div.variant-label (str "variant " (inc idx))]
        [:div.entry (render-detail-form variant registry trail)]]))])

;; -----------------------------------------------------------------------------
;; Public API

(defn render-schema-detail-view
  "Render the full detail view for a schema form.
   Shows entries (for maps), variants (for :or), or inline form (for other types).
   Description is handled by the entity detail renderer, not duplicated here.
   registry: {keyword -> {:form :doc}} for resolving named refs.
   trail: vector of schema IDs for drill-down navigation."
  [schema-form registry trail]
  (cond
    ;; Map schema - show entries
    (and (vector? schema-form) (= :map (first schema-form)))
    (let [entries (filter vector? (rest schema-form))]
      [:div.schema-entries
       (render-map-entries entries registry trail)])

    ;; Or schema - show variants
    (and (vector? schema-form) (= :or (first schema-form)))
    (let [args (forms/form-children schema-form)]
      (render-or-variants args registry trail))

    ;; Other schema types - show inline
    :else
    [:div.schema-inline
     (render-detail-form schema-form registry trail)]))
