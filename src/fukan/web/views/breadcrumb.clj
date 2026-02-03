(ns fukan.web.views.breadcrumb
  "Render breadcrumb navigation."
  (:require [hiccup2.core :as h]))

;; Html schema defined in fukan.web.views.shell

;; -----------------------------------------------------------------------------
;; Public API

(defn render-breadcrumb
  "Render the breadcrumb navigation HTML.
   Takes pre-computed path items (from entity-path).
   Dispatches cy-navigate event for consistent navigation handling."
  {:malli/schema [:=> [:cat :EntityPath] :Html]}
  [items]
  (str
   (h/html
    [:div#breadcrumb
     (for [[idx {:keys [id label]}] (map-indexed vector items)
           :let [is-last (= idx (dec (count items)))
                 ;; Dispatch cy-navigate event via vanilla JS - bubbles up to graph-panel
                 dispatch-js (str "evt.target.dispatchEvent(new CustomEvent('cy-navigate', {bubbles: true, detail: {id: '" (or id "") "'}}))")]]
       (list
        (when (pos? idx)
          [:span.separator (h/raw "&rsaquo;")])
        (if is-last
          [:span.crumb.current label]
          [:span.crumb {"data-on:click" dispatch-js} label])))])))
