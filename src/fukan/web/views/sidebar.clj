(ns fukan.web.views.sidebar
  "Sidebar panel renderer for Plan 7 Explorer.
   render-sidebar-html handles primitive and artifact panels.
   Expanded rendering of related edges is added in Task 6.")

;; -----------------------------------------------------------------------------
;; Public API

(defn render-sidebar-html
  "MVP sidebar: render primitive id/label/intent OR artifact label/source-location.
   Accepts a raw model primitive or artifact map."
  [entity]
  (str
    "<div class='entity-panel'>"
    "<h3>" (or (:label entity) (:id entity)) "</h3>"
    "<p><strong>Kind:</strong> " (:kind entity) "</p>"
    (when (:description entity)
      (str "<p>" (:description entity) "</p>"))
    "</div>"))
