(ns canvas.web.views.sidebar-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.web.views.sidebar :as port]
            [fukan.canvas.core.substrate.store :as store]
            [datascript.core :as d]))

(deftest canvas-build-produces-non-empty-store
  (let [db (port/build-canvas)]
    (is (some? db))
    (is (>= (count (store/all-modules db)) 1))))

(deftest canvas-build-has-expected-entities
  (let [db (port/build-canvas)
        names (->> (d/q '[:find ?n :where [?e :entity/name ?n]] db)
                   (map first)
                   set)]
    (is (contains? names "web.views.sidebar") "module present")
    (is (contains? names "SidebarSectionOrder") "SidebarSectionOrder invariant present")
    (is (contains? names "ClickableSchemaRefs") "ClickableSchemaRefs invariant present")
    (is (contains? names "EdgeRendererSections") "EdgeRendererSections invariant present")
    (is (contains? names "SidebarEmptyState") "SidebarEmptyState invariant present")
    (is (contains? names "render_sidebar_html") "render_sidebar_html function present")))
