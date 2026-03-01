(ns fukan.web.views.schema
  "Render TypeExpr type expressions to HTML/hiccup.
   Supports registry-aware rendering for the detail view and
   plain rendering for function signatures."
  (:require [clojure.string :as str])
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
  "Render a TypeExpr to hiccup.
   Used for function signatures where registry resolution isn't needed."
  [type-expr]
  (if-not (map? type-expr)
    [:span (pr-str type-expr)]
    (case (:tag type-expr)
      :ref (render-schema-ref (:name type-expr))
      :primitive [:span.type (:name type-expr)]
      :vector [:span "[" (render-schema-form (:element type-expr)) "]"]
      :set [:span "#{" (render-schema-form (:element type-expr)) "}"]
      :map-of [:span "{" (render-schema-form (:key-type type-expr)) " \u2192 " (render-schema-form (:value-type type-expr)) "}"]
      :maybe [:span (render-schema-form (:inner type-expr)) "?"]
      :or (interpose " | " (map render-schema-form (:variants type-expr)))
      :and (interpose " & " (map render-schema-form (:types type-expr)))
      :enum [:span (str/join " | " (map pr-str (:values type-expr)))]
      :tuple [:span "[" (interpose ", " (map render-schema-form (:elements type-expr))) "]"]
      :fn [:span "(" (interpose ", " (map render-schema-form (:inputs type-expr)))
           " \u2192 " (render-schema-form (:output type-expr)) ")"]
      :map [:span "map"]
      :predicate [:span "fn"]
      :unknown [:span (or (:original type-expr) "?")]
      [:span (pr-str type-expr)])))

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
  "Render a TypeExpr to hiccup with registry resolution.
   Resolves named refs to show their shape inline when simple."
  [type-expr registry trail]
  (if-not (map? type-expr)
    [:span (pr-str type-expr)]
    (case (:tag type-expr)
      :ref
      (let [k (:name type-expr)]
        (if (contains? registry k)
          (render-detail-ref k registry trail)
          (render-detail-ref k registry trail)))

      :primitive
      (let [n (:name type-expr)]
        (if (contains? registry (keyword n))
          (render-detail-ref (keyword n) registry trail)
          [:span.type n]))

      :vector [:span "[" (render-detail-form (:element type-expr) registry trail) "]"]
      :set [:span "#{" (render-detail-form (:element type-expr) registry trail) "}"]
      :map-of [:span "{" (render-detail-form (:key-type type-expr) registry trail)
               " \u2192 " (render-detail-form (:value-type type-expr) registry trail) "}"]
      :maybe [:span (render-detail-form (:inner type-expr) registry trail) "?"]
      :or [:span.schema-or (interpose [:span.schema-sep " | "]
                                      (map #(render-detail-form % registry trail) (:variants type-expr)))]
      :and (interpose " & " (map #(render-detail-form % registry trail) (:types type-expr)))
      :enum [:span.type (str/join " | " (map pr-str (:values type-expr)))]
      :tuple [:span "[" (interpose ", " (map #(render-detail-form % registry trail) (:elements type-expr))) "]"]
      :fn [:span "("
           (interpose ", " (map #(render-detail-form % registry trail) (:inputs type-expr)))
           " \u2192 "
           (render-detail-form (:output type-expr) registry trail)
           ")"]
      :map [:span.type "map"]
      :predicate [:span.type "fn"]
      :unknown [:span (or (:original type-expr) "?")]
      [:span (pr-str type-expr)])))

;; -----------------------------------------------------------------------------
;; Map entry rendering

(defn- render-map-entries
  "Render the entries of a map TypeExpr with registry resolution.
   Each entry shows key, optionality, and the resolved type."
  [entries registry trail]
  (for [entry entries]
    [:div.entry
     [:span.key (:key entry)]
     (when (:optional entry) [:span.optional "?"])
     [:span.entry-sep " : "]
     [:span.entry-type (render-detail-form (:type entry) registry trail)]
     (when (:description entry)
       [:div.entry-doc (:description entry)])]))

;; -----------------------------------------------------------------------------
;; Or-variant rendering

(defn- render-or-variants
  "Render the variants of an :or TypeExpr, each as a separate block."
  [variants registry trail]
  [:div.schema-variants
   (for [[idx variant] (map-indexed vector variants)]
     (if (= :map (:tag variant))
       ;; Map variant - show entries in a block
       [:div.schema-variant
        [:div.variant-label (str "variant " (inc idx))]
        (when (:description variant) [:div.variant-doc (:description variant)])
        (render-map-entries (:entries variant) registry trail)]
       ;; Non-map variant - show inline
       [:div.schema-variant
        [:div.variant-label (str "variant " (inc idx))]
        [:div.entry (render-detail-form variant registry trail)]]))])

;; -----------------------------------------------------------------------------
;; Public API

(defn render-schema-detail-view
  "Render the full detail view for a TypeExpr.
   Shows entries (for maps), variants (for :or), or inline form (for other types).
   Description is handled by the entity detail renderer, not duplicated here.
   registry: {keyword -> {:form :doc}} for resolving named refs.
   trail: vector of schema IDs for drill-down navigation."
  [type-expr registry trail]
  (cond
    ;; Map TypeExpr - show entries
    (= :map (:tag type-expr))
    [:div.schema-entries
     (render-map-entries (:entries type-expr) registry trail)]

    ;; Or TypeExpr - show variants
    (= :or (:tag type-expr))
    (render-or-variants (:variants type-expr) registry trail)

    ;; Other TypeExpr types - show inline
    :else
    [:div.schema-inline
     (render-detail-form type-expr registry trail)]))
