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

(defn- view-url
  "Build a /sse/view?select= URL for full app navigation.
   Includes current view state (expanded, show_private, visible_edge_types)
   so the graph preserves its layout."
  [node-id]
  (str "@get('/sse/view?select=" (url-encode node-id)
       "&expanded=' + window.getExpandedParam() + '"
       "&show_private=' + window.getShowPrivateParam() + '"
       "&visible_edge_types=' + window.getVisibleEdgeTypesParam())"))

;; -----------------------------------------------------------------------------
;; Rich TypeExpr rendering (clickable schema refs)

(defn- type-expr->hiccup
  "Convert a TypeExpr to Hiccup with clickable schema refs.
   schema-ids is a keyword→node-id map for resolving refs to view URLs."
  [schema-ids type-expr]
  (if-not (map? type-expr)
    [:span (pr-str type-expr)]
    (case (:tag type-expr)
      :ref       (let [node-id (get schema-ids (:name type-expr))]
                   (if node-id
                     [:span.schema-ref
                      {"data-on:click.stop" (view-url node-id)}
                      (name (:name type-expr))]
                     [:span (name (:name type-expr))]))
      :primitive [:span (:name type-expr)]
      :vector    (list "[" (type-expr->hiccup schema-ids (:element type-expr)) "]")
      :set       (list "#{" (type-expr->hiccup schema-ids (:element type-expr)) "}")
      :map-of    (list "{" (type-expr->hiccup schema-ids (:key-type type-expr))
                       " " (type-expr->hiccup schema-ids (:value-type type-expr)) "}")
      :maybe     (list (type-expr->hiccup schema-ids (:inner type-expr)) "?")
      :or        (interpose " | " (map (partial type-expr->hiccup schema-ids) (:variants type-expr)))
      :and       (interpose " & " (map (partial type-expr->hiccup schema-ids) (:types type-expr)))
      :enum      [:span (str/join " | " (map pr-str (:values type-expr)))]
      :tuple     (list "[" (interpose ", " (map (partial type-expr->hiccup schema-ids) (:elements type-expr))) "]")
      :fn        (list (interpose ", " (map (partial type-expr->hiccup schema-ids) (:inputs type-expr)))
                       [:span.arrow " \u2192 "]
                       (type-expr->hiccup schema-ids (:output type-expr)))
      :map       [:span "map"]
      :predicate [:span "fn"]
      :unknown   [:span (or (:original type-expr) "?")]
      [:span (pr-str type-expr)])))

(defn- fn-signature-hiccup
  "Format a function signature as Hiccup with clickable schema refs."
  [schema-ids sig]
  (when-let [{:keys [inputs output]} sig]
    [:div.sig
     (interpose ", " (map (partial type-expr->hiccup schema-ids) inputs))
     [:span.arrow " \u2192 "]
     (type-expr->hiccup schema-ids output)]))

;; -----------------------------------------------------------------------------
;; Shared components

(defn- render-fn-list
  "Render a list of functions with optional signatures.
   Each entry is {:name :signature :id (optional)}.
   When :id is present, the item is clickable.
   Schema refs in signatures are individually clickable (navigates to schema node)."
  [schema-ids fns]
  [:ul
   (for [{:keys [name signature id]} fns]
     (let [attrs (when id
                   {"data-on:click" (view-url id)})]
       [:li.fn-card attrs
        name
        (when signature
          (fn-signature-hiccup schema-ids signature))]))])

(defn- render-dep-list
  "Render a dependency or dependents section with heading and clickable items."
  [heading items]
  (list
   [:h5 heading " " [:span.dep-count (str "(" (count items) ")")]]
   (if (seq items)
     [:ul
      (for [[target-id {:keys [count label]}] items]
        [:li {"data-on:click" (view-url target-id)}
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
   Items with :id are clickable; others are plain labels."
  [{:keys [inputs outputs]}]
  (letfn [(render-items [items]
            [:ul
             (for [{:keys [label id]} items]
               (if id
                 [:li {"data-on:click" (view-url id)} label]
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
;; Interface rendering

(defn- render-interface
  "Render the interface section based on its type.
   Dispatches to the appropriate sub-renderer.
   Modules (:fn-list) show Operations with clickable schema refs per function.
   Leaves (:fn-inline) show Inputs and Outputs only.
   Schema defs and name-lists render their own content without IO sections."
  [{:keys [type items registry]} dataflow schema-ids]
  (case type
    :fn-list
    (list
     [:h5 "Operations " [:span.dep-count (str "(" (count items) ")")]]
     (render-fn-list schema-ids items))

    :fn-inline
    (render-io-sections dataflow)

    :schema-def
    [:div.schema-detail
     (views.schema/render-schema-detail-view (or schema-ids {}) (first items) (or registry {}))]

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
      (for [{:keys [label id]} schemas]
        [:li (when id {"data-on:click" (view-url id)})
         label])])))

(defn- render-entity-detail
  "Generic renderer for all non-edge entity types.
   Iterates through sections in order: label, description,
   guarantees, defined types, interface."
  [{:keys [label kind description guarantees schemas schema-ids interface dataflow]}]
  (str
   (h/html
    [:div#node-info
     [:h4 label " " [:span.kind-badge (name kind)]]

     (render-description description)
     (render-guarantees guarantees)
     (render-defined-types schemas)

     (when interface
       (render-interface interface dataflow schema-ids))])))

(defn- render-schema-ref-link
  "Render a schema as a clickable link if its node ID is known."
  [schema-ids {:keys [label schema-key]}]
  (if-let [url (views.schema/view-url schema-ids schema-key)]
    [:span.schema-ref {"data-on:click" url} label]
    [:span label]))

(defn- render-schema-ref-list
  "Render a list of schema reference pairs with clickable links."
  [schema-ids schema-refs]
  [:ul
   (for [{:keys [from-schema to-schema]} schema-refs]
     [:li.schema-ref-entry
      (render-schema-ref-link schema-ids from-schema)
      [:span.arrow " \u2192 "]
      (render-schema-ref-link schema-ids to-schema)])])

(defn- render-edge-detail
  "Dedicated renderer for edge entities.
   Dispatches by edge-type, then renders sections based on presence:
   - code-flow: 'Functions Called' when called-fns present,
                'Dispatched Functions' when dispatched-fns present,
                both for mixed edges.
   - schema-reference: 'Schema References' list."
  [{:keys [label edge-type called-fns dispatched-fns schema-refs schema-ids]}]
  (str
   (h/html
    [:div#node-info
     (case edge-type
       :code-flow
       (list
        [:h4 label]
        (when (seq called-fns)
          (list
           [:h5 "Functions Called " [:span.dep-count (str "(" (count called-fns) ")")]]
           (render-fn-list (or schema-ids {}) called-fns)))
        (when (seq dispatched-fns)
          (list
           [:h5 "Dispatched Functions " [:span.dep-count (str "(" (count dispatched-fns) ")")]]
           (render-fn-list (or schema-ids {}) dispatched-fns)))
        (when (and (empty? called-fns) (empty? dispatched-fns))
          [:p.empty-state "No direct function calls"]))

       :schema-reference
       (list
        [:h4 label]
        [:h5 "Schema References " [:span.dep-count (str "(" (count schema-refs) ")")]]
        (if (seq schema-refs)
          (render-schema-ref-list (or schema-ids {}) schema-refs)
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
