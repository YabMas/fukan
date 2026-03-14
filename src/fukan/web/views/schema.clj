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

(defn view-url
  "Build a /sse/view?select= URL for full app navigation.
   Resolves schema keyword to node ID via the lookup map.
   Includes current view state so the graph preserves its layout."
  [schema-ids schema-key]
  (let [k (if (keyword? schema-key) schema-key (keyword schema-key))
        node-id (get schema-ids k)]
    (when node-id
      (str "@get('/sse/view?select=" (url-encode node-id)
           "&expanded=' + window.getExpandedParam() + '"
           "&show_private=' + window.getShowPrivateParam() + '"
           "&visible_edge_types=' + window.getVisibleEdgeTypesParam())"))))

;; -----------------------------------------------------------------------------
;; Plain rendering (for function signatures, no registry)

(defn- render-schema-ref
  "Render a clickable schema reference (plain mode)."
  [schema-ids schema-key]
  (if-let [url (view-url schema-ids schema-key)]
    [:span.schema-ref {"data-on:click" url} (name schema-key)]
    [:span (name schema-key)]))

(defn- render-schema-form
  "Render a TypeExpr to hiccup.
   Used for function signatures where registry resolution isn't needed."
  [schema-ids type-expr]
  (if-not (map? type-expr)
    [:span (pr-str type-expr)]
    (case (:tag type-expr)
      :ref (render-schema-ref schema-ids (:name type-expr))
      :primitive [:span.type (:name type-expr)]
      :vector [:span "[" (render-schema-form schema-ids (:element type-expr)) "]"]
      :set [:span "#{" (render-schema-form schema-ids (:element type-expr)) "}"]
      :map-of [:span "{" (render-schema-form schema-ids (:key-type type-expr)) " \u2192 " (render-schema-form schema-ids (:value-type type-expr)) "}"]
      :maybe [:span (render-schema-form schema-ids (:inner type-expr)) "?"]
      :or (interpose " | " (map (partial render-schema-form schema-ids) (:variants type-expr)))
      :and (interpose " & " (map (partial render-schema-form schema-ids) (:types type-expr)))
      :enum [:span (str/join " | " (map pr-str (:values type-expr)))]
      :tuple [:span "[" (interpose ", " (map (partial render-schema-form schema-ids) (:elements type-expr))) "]"]
      :fn [:span "(" (interpose ", " (map (partial render-schema-form schema-ids) (:inputs type-expr)))
           " \u2192 " (render-schema-form schema-ids (:output type-expr)) ")"]
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
  [schema-ids schema-key registry]
  (let [{:keys [doc]} (get registry schema-key)
        url (view-url schema-ids schema-key)
        attrs (cond-> {}
                url (assoc "data-on:click" url)
                doc (assoc :title doc))]
    (if url
      [:span.schema-ref.schema-drilldown attrs (name schema-key)]
      [:span (name schema-key)])))

(defn- render-detail-form
  "Render a TypeExpr to hiccup with registry resolution.
   Resolves named refs to show their shape inline when simple."
  [schema-ids type-expr registry]
  (if-not (map? type-expr)
    [:span (pr-str type-expr)]
    (let [recur-fn #(render-detail-form schema-ids % registry)]
      (case (:tag type-expr)
        :ref
        (render-detail-ref schema-ids (:name type-expr) registry)

        :primitive
        (let [n (:name type-expr)]
          (if (contains? registry (keyword n))
            (render-detail-ref schema-ids (keyword n) registry)
            [:span.type n]))

        :vector [:span "[" (recur-fn (:element type-expr)) "]"]
        :set [:span "#{" (recur-fn (:element type-expr)) "}"]
        :map-of [:span "{" (recur-fn (:key-type type-expr))
                 " \u2192 " (recur-fn (:value-type type-expr)) "}"]
        :maybe [:span (recur-fn (:inner type-expr)) "?"]
        :or [:span.schema-or (interpose [:span.schema-sep " | "]
                                        (map recur-fn (:variants type-expr)))]
        :and (interpose " & " (map recur-fn (:types type-expr)))
        :enum [:span.type (str/join " | " (map pr-str (:values type-expr)))]
        :tuple [:span "[" (interpose ", " (map recur-fn (:elements type-expr))) "]"]
        :fn [:span "("
             (interpose ", " (map recur-fn (:inputs type-expr)))
             " \u2192 "
             (recur-fn (:output type-expr))
             ")"]
        :map [:span.type "map"]
        :predicate [:span.type "fn"]
        :unknown [:span (or (:original type-expr) "?")]
        [:span (pr-str type-expr)]))))

;; -----------------------------------------------------------------------------
;; Map entry rendering

(defn- render-map-entries
  "Render the entries of a map TypeExpr with registry resolution.
   Each entry shows key, optionality, and the resolved type."
  [schema-ids entries registry]
  (for [entry entries]
    [:div.entry
     [:span.key (:key entry)]
     (when (:optional entry) [:span.optional "?"])
     [:span.entry-sep " : "]
     [:span.entry-type (render-detail-form schema-ids (:type entry) registry)]
     (when (:description entry)
       [:div.entry-doc (:description entry)])]))

;; -----------------------------------------------------------------------------
;; Or-variant rendering

(defn- render-or-variants
  "Render the variants of an :or TypeExpr, each as a separate block."
  [schema-ids variants registry]
  [:div.schema-variants
   (for [[idx variant] (map-indexed vector variants)]
     (if (= :map (:tag variant))
       ;; Map variant - show entries in a block
       [:div.schema-variant
        [:div.variant-label (str "variant " (inc idx))]
        (when (:description variant) [:div.variant-doc (:description variant)])
        (render-map-entries schema-ids (:entries variant) registry)]
       ;; Non-map variant - show inline
       [:div.schema-variant
        [:div.variant-label (str "variant " (inc idx))]
        [:div.entry (render-detail-form schema-ids variant registry)]]))])

;; -----------------------------------------------------------------------------
;; Public API

(defn render-schema-detail-view
  "Render the full detail view for a TypeExpr.
   Shows entries (for maps), variants (for :or), or inline form (for other types).
   Description is handled by the entity detail renderer, not duplicated here.
   schema-ids: keyword→node-id map for click navigation.
   registry: {keyword -> {:form :doc}} for resolving named refs."
  [schema-ids type-expr registry]
  (cond
    ;; Map TypeExpr - show entries
    (= :map (:tag type-expr))
    [:div.schema-entries
     (render-map-entries schema-ids (:entries type-expr) registry)]

    ;; Or TypeExpr - show variants
    (= :or (:tag type-expr))
    (render-or-variants schema-ids (:variants type-expr) registry)

    ;; Other TypeExpr types - show inline
    :else
    [:div.schema-inline
     (render-detail-form schema-ids type-expr registry)]))
