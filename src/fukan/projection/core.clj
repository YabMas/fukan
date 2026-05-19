(ns fukan.projection.core
  "Model → cytoscape-compatible graph projection.

   Replaces the OLD code-graph projection (per DESIGN.md line 493) with
   one that consumes the new Model (primitives + thirteen kernel
   relations + artifacts).

   Output shape:
     {:nodes [<node>...]
      :edges [<edge>...]}
   where node and edge keys are kebab-case Clojure data; the
   web/views/cytoscape transformer handles camelCase + JSON output."
  (:require [clojure.string :as str]))

(defn- allium-kind-of
  [model id]
  (let [tags (filter (fn [ta]
                       (and (= "Allium" (-> ta :tag :namespace))
                            (= :target/primitive (-> ta :target :case))
                            (= id (-> ta :target :id))))
                     (:tag-apps model))]
    (some (comp keyword str/lower-case :name :tag) tags)))

(defn- primitive->node
  [model [id primitive]]
  (cond-> {:id    id
           :kind  (:kind primitive)
           :label (:label primitive)}
    (allium-kind-of model id) (assoc :allium-kind (allium-kind-of model id))))

(defn- artifact-node-id
  [aid]
  (let [[case-kw language qname] aid
        case-str (if (namespace case-kw)
                   (str (namespace case-kw) "/" (name case-kw))
                   (name case-kw))]
    (str "artifact:" case-str ":" language ":" qname)))

(defn- artifact->node
  [[aid artifact]]
  (let [[case-kw _ qname] aid]
    {:id    (artifact-node-id aid)
     :kind  (keyword "artifact" (name case-kw))
     :label qname
     :source-location (get-in artifact [:sub :source-location])}))

(defn- endpoint->node-id
  [endpoint]
  (case (:case endpoint)
    :endpoint/primitive (:id endpoint)
    :endpoint/substrate (:container endpoint)
    :endpoint/artifact  (artifact-node-id (:id endpoint))))

(defn- edge->ui-edge
  [idx edge]
  (cond-> {:id    (str "e" idx)
           :from  (endpoint->node-id (:from edge))
           :to    (endpoint->node-id (:to edge))
           :kind  (:kind edge)}
    (= :relation/projects (:kind edge))
    (assoc :projection-kind (:projection-kind edge)
           :validity        (:validity edge))))

(defn project-model
  "Project a Model into {:nodes :edges} in the new format."
  [model]
  {:nodes (vec (concat
                 (map #(primitive->node model %) (:primitives model))
                 (map artifact->node (:artifacts model))))
   :edges (vec (map-indexed edge->ui-edge (:edges model)))})
