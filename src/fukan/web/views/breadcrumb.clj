(ns fukan.web.views.breadcrumb
  "Breadcrumb navigation computation."
  (:require [fukan.web.views.common :as common]
            [clojure.string :as str]))

(defn- breadcrumb-label
  "Get a short label for breadcrumb display.
   For namespaces, returns just the last segment (e.g. 'handler' instead of 'fukan.web.handler')."
  [node]
  (let [label (:label node)
        kind (:kind node)]
    (if (= kind :namespace)
      ;; Extract last segment of namespace
      (last (str/split label #"\."))
      label)))

(defn compute-breadcrumb
  "Compute breadcrumb path from root to entity.
   Returns a list of {:id :label} maps."
  [m entity-id]
  (let [root-node (common/find-root-node m)
        root-id (:id root-node)]
    (if (or (nil? entity-id) (= entity-id root-id))
      ;; At root - just show root label
      [{:id nil :label (or (breadcrumb-label root-node) "root")}]
      ;; Build path from root to entity
      (let [path (loop [current-id entity-id
                        acc []]
                   (let [node (get-in m [:nodes current-id])]
                     (if (or (nil? node) (nil? (:parent node)))
                       acc
                       (recur (:parent node)
                              (cons {:id current-id :label (breadcrumb-label node)} acc)))))]
        (cons {:id nil :label (or (breadcrumb-label root-node) "root")} path)))))
