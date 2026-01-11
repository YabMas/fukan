(ns fukan.web.views.common
  "Shared utilities for view computation.")

(defn find-root-node
  "Find the root node (node with parent = nil).
   Root should be a folder or namespace, not a var or schema."
  [m]
  (->> (vals (:nodes m))
       (filter #(nil? (:parent %)))
       (filter #(#{:folder :namespace} (:kind %)))
       first))
