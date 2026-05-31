(ns fukan.web.views.breadcrumb
  "Breadcrumb rendering — navigation trail from smart-root to the
   current view.

   Realises render_breadcrumb from breadcrumb.boundary and honours the
   invariants declared in breadcrumb.allium:

     BreadcrumbShortLabels    — namespace names show only the last
                                dotted segment.
     BreadcrumbCurrentItem    — the last item is non-clickable and
                                styled as current.
     BreadcrumbClickableItems — all non-last items dispatch
                                NavigateToAncestor(ancestor_id)."
  (:require [hiccup2.core :as h]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Helpers

(defn- short-label
  "BreadcrumbShortLabels: namespace-like labels (dot-separated) are
   reduced to their last segment. Non-dotted labels are returned as-is."
  [label]
  (when label
    (let [s (str label)]
      (if (str/includes? s ".")
        (last (str/split s #"\."))
        s))))

(defn- item-label
  [item]
  (or (short-label (:label item))
      (short-label (:id item))
      ""))

;; -----------------------------------------------------------------------------
;; Public API

(defn render-breadcrumb
  "Render the navigation breadcrumb trail.

   path is an ordered sequence of entity-path items, root-first. Each
   item is a map with at least :id and optionally :label. The final
   item represents the current view.

   Returns an HTML string."
  [path]
  (let [items (vec path)
        n     (count items)]
    (str
      (h/html
        [:nav.breadcrumb {:aria-label "breadcrumb"}
         [:ol.breadcrumb-list
          (map-indexed
            (fn [idx item]
              (let [current? (= idx (dec n))
                    label    (item-label item)]
                (if current?
                  ;; BreadcrumbCurrentItem: last item is non-clickable.
                  [:li.breadcrumb-item.breadcrumb-current
                   {:aria-current "page"}
                   label]
                  ;; BreadcrumbClickableItems: non-last items dispatch
                  ;; NavigateToAncestor with the item's id as
                  ;; ancestor_id.
                  [:li.breadcrumb-item
                   [:a.breadcrumb-link
                    {:href           "#"
                     :data-on-click  (str "@get('/navigate?ancestor_id="
                                          (:id item) "')")
                     :data-ancestor-id (:id item)}
                    label]])))
            items)]]))))
