(ns fukan.web.views.sidebar
  "Render the sidebar panel from normalized entity detail data.
   A single generic renderer handles all entity kinds — module,
   function, schema, and edge — by iterating the sections present in
   the EntityDetails structure: description, interface, schemas,
   dataflow, and dependencies. Interface rendering dispatches on
   the :type field (fn-list, fn-inline, schema-def)."
  (:require [hiccup2.core :as h]
            [clojure.string :as str]
            [fukan.web.views.schema :as views.schema])
  (:import [java.net URLEncoder]))

;; Html schema defined in fukan.web.views.shell

(defn- url-encode [s] (URLEncoder/encode (str s) "UTF-8"))

;; -----------------------------------------------------------------------------
;; Plain-text TypeExpr helpers

(defn- type-expr->str
  "Convert a TypeExpr to a short plain-text string."
  [type-expr]
  (if-not (map? type-expr)
    (pr-str type-expr)
    (case (:tag type-expr)
      :ref (name (:name type-expr))
      :primitive (:name type-expr)
      :vector (str "[" (type-expr->str (:element type-expr)) "]")
      :set    (str "#{" (type-expr->str (:element type-expr)) "}")
      :map-of (str "{" (type-expr->str (:key-type type-expr)) " " (type-expr->str (:value-type type-expr)) "}")
      :maybe  (str (type-expr->str (:inner type-expr)) "?")
      :or     (str/join " | " (map type-expr->str (:variants type-expr)))
      :and    (str/join " & " (map type-expr->str (:types type-expr)))
      :enum   (str/join " | " (map pr-str (:values type-expr)))
      :tuple  (str "[" (str/join ", " (map type-expr->str (:elements type-expr))) "]")
      :fn     (str (str/join ", " (map type-expr->str (:inputs type-expr)))
                   " \u2192 " (type-expr->str (:output type-expr)))
      :map    "map"
      :predicate "fn"
      :unknown (or (:original type-expr) "?")
      (pr-str type-expr))))

(defn- fn-signature-str
  "Format a structured function signature as 'input -> output' string."
  [sig]
  (when-let [{:keys [inputs output]} sig]
    (str (str/join ", " (map type-expr->str inputs)) " \u2192 " (type-expr->str output))))

(defn- schema-click-url
  "Build the SSE schema click URL for a schema keyword.
   Handles both qualified (:ns/Name) and unqualified (:Name) keywords."
  [key]
  (str "@get('/sse/schema?id=" (url-encode (subs (str key) 1)) "')"))

;; -----------------------------------------------------------------------------
;; Rich TypeExpr rendering (clickable schema refs)

(defn- type-expr->hiccup
  "Convert a TypeExpr to Hiccup with clickable schema refs."
  [type-expr]
  (if-not (map? type-expr)
    [:span (pr-str type-expr)]
    (case (:tag type-expr)
      :ref       [:span.schema-ref
                  {"data-on:click.stop" (schema-click-url (:name type-expr))}
                  (name (:name type-expr))]
      :primitive [:span (:name type-expr)]
      :vector    (list "[" (type-expr->hiccup (:element type-expr)) "]")
      :set       (list "#{" (type-expr->hiccup (:element type-expr)) "}")
      :map-of    (list "{" (type-expr->hiccup (:key-type type-expr))
                       " " (type-expr->hiccup (:value-type type-expr)) "}")
      :maybe     (list (type-expr->hiccup (:inner type-expr)) "?")
      :or        (interpose " | " (map type-expr->hiccup (:variants type-expr)))
      :and       (interpose " & " (map type-expr->hiccup (:types type-expr)))
      :enum      [:span (str/join " | " (map pr-str (:values type-expr)))]
      :tuple     (list "[" (interpose ", " (map type-expr->hiccup (:elements type-expr))) "]")
      :fn        (list (interpose ", " (map type-expr->hiccup (:inputs type-expr)))
                       [:span.arrow " \u2192 "]
                       (type-expr->hiccup (:output type-expr)))
      :map       [:span "map"]
      :predicate [:span "fn"]
      :unknown   [:span (or (:original type-expr) "?")]
      [:span (pr-str type-expr)])))

(defn- fn-signature-hiccup
  "Format a function signature as Hiccup with clickable schema refs."
  [sig]
  (when-let [{:keys [inputs output]} sig]
    [:div.sig
     (interpose ", " (map type-expr->hiccup inputs))
     [:span.arrow " \u2192 "]
     (type-expr->hiccup output)]))

;; -----------------------------------------------------------------------------
;; Shared components

(defn- render-fn-list
  "Render a list of functions with optional signatures.
   Each entry is {:name :schema :id (optional)}.
   When :id is present, the item is clickable.
   Schema refs in signatures are individually clickable (drill-down)."
  [fns]
  [:ul
   (for [{:keys [name schema id]} fns]
     (let [attrs (when id
                   {"data-on:click" (str "@get('/sse/view?select=" (url-encode id) "')")})]
       [:li.fn-card attrs
        name
        (when schema
          (fn-signature-hiccup schema))]))])

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

(defn- render-io-sections
  "Render input and output schema type lists.
   Always renders both headings with counts; shows 'None' when empty.
   Items with :key are clickable schema refs; others are plain labels."
  [{:keys [inputs outputs]}]
  (letfn [(render-items [items]
            [:ul
             (for [{:keys [label key]} items]
               (if key
                 [:li {"data-on:click" (schema-click-url key)} label]
                 [:li label]))])]
    (list
     [:h5 "Inputs " [:span.dep-count (str "(" (count inputs) ")")]]
     (if (seq inputs)
       (render-items inputs)
       [:p.empty-state "None"])
     [:h5 "Outputs " [:span.dep-count (str "(" (count outputs) ")")]]
     (if (seq outputs)
       (render-items outputs)
       [:p.empty-state "None"]))))

;; -----------------------------------------------------------------------------
;; Schema navigation

(defn- render-schema-trail
  "Render navigation breadcrumb for schema drill-down.
   trail is a vector of parent schema ID strings; current is the active schema.
   Each parent is clickable (preserving its own ancestors), current is not."
  [trail current]
  (let [all-items (cond-> (vec trail)
                    current (conj current))
        n (count all-items)]
    (when (> n 1)
      [:div.schema-trail
       (for [[idx schema-id] (map-indexed vector all-items)
             :let [is-last (= idx (dec n))
                   ;; Trail for clicking this item = all items before it
                   click-trail (subvec all-items 0 idx)
                   trail-param (when (seq click-trail)
                                 (str "&trail=" (url-encode (str/join "," click-trail))))]]
         (list
          (when (pos? idx)
            [:span.trail-sep "\u203a"])
          (if is-last
            [:span.trail-current schema-id]
            [:span.trail-item
             {"data-on:click" (str "@get('/sse/schema?id=" (url-encode schema-id)
                                   (or trail-param "") "')")}
             schema-id])))])))

;; -----------------------------------------------------------------------------
;; Interface rendering

(defn- render-interface
  "Render the interface section based on its type.
   Dispatches to the appropriate sub-renderer.
   Modules (:fn-list) show Operations with clickable schema refs per function.
   Leaves (:fn-inline) show Inputs and Outputs only.
   Schema defs and name-lists render their own content without IO sections."
  [{:keys [type items registry]} dataflow nav]
  (case type
    :fn-list
    (list
     [:h5 "Operations " [:span.dep-count (str "(" (count items) ")")]]
     (render-fn-list items))

    :fn-inline
    (render-io-sections dataflow)

    :schema-def
    (let [trail (or (:trail nav) [])
          current (:current nav)
          link-trail (if current (conj trail current) trail)]
      [:div.schema-detail
       (views.schema/render-schema-detail-view (first items) (or registry {}) link-trail)])

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

(defn- render-guarantees
  "Render a guarantees section as a bullet list.
   Returns nil when guarantees are empty or absent."
  [guarantees]
  (when (seq guarantees)
    (list
     [:h5 "Guarantees"]
     [:ul
      (for [g guarantees]
        [:li g])])))

(defn- render-defined-types
  "Render a Defined Types section listing schemas owned by a module.
   Each item is clickable and drills down into the schema detail view."
  [schemas]
  (when (seq schemas)
    (list
     [:h5 "Defined Types " [:span.dep-count (str "(" (count schemas) ")")]]
     [:ul
      (for [{:keys [label key]} schemas]
        [:li (when key {"data-on:click" (schema-click-url key)})
         label])])))

(defn- render-entity-detail
  "Generic renderer for all non-edge entity types.
   Iterates through sections in order: label, description, guarantees,
   defined types, interface, deps, dependents."
  [{:keys [label kind description guarantees schemas interface dataflow nav]}]
  (str
   (h/html
    [:div#node-info
     ;; Schema navigation trail (when drilling down through schemas)
     (render-schema-trail (:trail nav) (:current nav))

     [:h4 label " " [:span.kind-badge (name kind)]]

     (render-description description)
     (render-guarantees guarantees)
     (render-defined-types schemas)

     (when interface
       (render-interface interface dataflow nav))])))

(defn- render-schema-ref-list
  "Render a list of schema reference pairs."
  [schema-refs]
  [:ul
   (for [{:keys [from-schema to-schema]} schema-refs]
     [:li
      [:span (:label from-schema)]
      " \u2192 "
      [:span (:label to-schema)]])])

(defn- render-edge-detail
  "Dedicated renderer for edge entities.
   Dispatches by edge-type: code-flow shows called functions,
   schema-reference shows schema references."
  [{:keys [label edge-type called-fns schema-refs]}]
  (str
   (h/html
    [:div#node-info
     [:h4 label]
     (case edge-type
       :code-flow
       (list
        [:h5 "Functions Called " [:span.dep-count (str "(" (count called-fns) ")")]]
        (if (seq called-fns)
          (render-fn-list called-fns)
          [:p.empty-state "No direct function calls"]))

       :schema-reference
       (list
        [:h5 "Schema References " [:span.dep-count (str "(" (count schema-refs) ")")]]
        (if (seq schema-refs)
          (render-schema-ref-list schema-refs)
          [:p.empty-state "No direct schema references"]))

       ;; Fallback
       [:p.empty-state "Unknown edge type"])])))

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
