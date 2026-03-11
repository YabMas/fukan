(ns fukan.web.views.breadcrumb
  "Render breadcrumb navigation from path projection data.
   Produces an HTML breadcrumb trail from a vector of path items,
   with each ancestor as a clickable navigation link."
  (:require [hiccup2.core :as h])
  (:import [java.net URLEncoder]))

;; Html schema defined in fukan.web.views.shell

(defn- url-encode [s] (URLEncoder/encode (str s) "UTF-8"))

(defn- navigate-url
  "Build a /sse/view navigation URL preserving graph view state."
  [id]
  (str "@get('/sse/view?id=" (url-encode (or id ""))
       "&expanded=' + window.getExpandedParam() + '"
       "&show_private=' + window.getShowPrivateParam() + '"
       "&visible_edge_types=' + window.getVisibleEdgeTypesParam())"))

;; -----------------------------------------------------------------------------
;; Public API

(defn render-breadcrumb
  "Render the breadcrumb navigation HTML.
   Takes pre-computed path items (from entity-path).
   Uses Datastar @get() for navigation, preserving graph view state."
  {:malli/schema [:=> [:cat :EntityPath] :Html]}
  [items]
  (str
   (h/html
    [:div#breadcrumb
     (for [[idx {:keys [id label]}] (map-indexed vector items)
           :let [is-last (= idx (dec (count items)))]]
       (list
        (when (pos? idx)
          [:span.separator (h/raw "&rsaquo;")])
        (if is-last
          [:span.crumb.current label]
          [:span.crumb {"data-on:click" (navigate-url id)} label])))])))
