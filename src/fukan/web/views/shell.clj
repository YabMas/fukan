(ns fukan.web.views.shell
  "Plan 1 placeholder page. The full explorer is rewritten in Plan 6."
  (:require [hiccup2.core :as h]))

(defn render-app-shell
  "Render the placeholder page."
  [_request]
  (str
    (h/html
      [:html
       [:head [:title "fukan — kernel substrate v0"]]
       [:body
        [:h1 "fukan"]
        [:p "Kernel substrate v0 is live. Analyzers and the explorer are pending."]
        [:p "See "
         [:a {:href "https://github.com/yabmas/fukan/blob/main/doc/plans/2026-05-18-kernel-substrate.md"}
          "Plan 1"]
         " for the current scope."]]])))
