(ns canvas.web.views.breadcrumb-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.web.views.breadcrumb :as port]
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
    (is (contains? names "web.views.breadcrumb") "module present")
    (is (contains? names "BreadcrumbShortLabels") "BreadcrumbShortLabels invariant present")
    (is (contains? names "BreadcrumbCurrentItem") "BreadcrumbCurrentItem invariant present")
    (is (contains? names "BreadcrumbClickableItems") "BreadcrumbClickableItems invariant present")
    (is (contains? names "render_breadcrumb") "render_breadcrumb function present")))
