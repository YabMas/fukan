(ns fukan.projection.path
  "Path and navigation projection functions.
   Computes breadcrumb paths and finds root nodes."
  (:require [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Private helpers

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

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema PathSegment
  [:map
   [:id [:maybe :string]]
   [:label :string]])

(def ^:schema EntityPath
  [:vector :PathSegment])

;; -----------------------------------------------------------------------------
;; Public API

(defn find-root-node
  "Find the root node (node with parent = nil).
   Root should be a folder or namespace, not a var or schema."
  [model]
  (->> (vals (:nodes model))
       (filter #(nil? (:parent %)))
       (filter #(#{:folder :namespace} (:kind %)))
       first))

(defn entity-path
  "Compute breadcrumb path from root to entity.
   Returns a list of {:id :label} maps."
  {:malli/schema [:=> [:cat :Model [:maybe :string]] :EntityPath]}
  [model entity-id]
  (let [root-node (find-root-node model)
        root-id (:id root-node)]
    (if (or (nil? entity-id) (= entity-id root-id))
      ;; At root - just show root label
      [{:id nil :label (or (breadcrumb-label root-node) "root")}]
      ;; Build path from root to entity
      (let [path (loop [current-id entity-id
                        acc []]
                   (let [node (get-in model [:nodes current-id])]
                     (if (or (nil? node) (nil? (:parent node)))
                       acc
                       (recur (:parent node)
                              (cons {:id current-id :label (breadcrumb-label node)} acc)))))]
        (cons {:id nil :label (or (breadcrumb-label root-node) "root")} path)))))
