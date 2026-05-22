(ns fukan.projection.core
  "Model → cytoscape-compatible graph projection.

   Replaces the OLD code-graph projection (per DESIGN.md line 493) with
   one that consumes the new Model (primitives + thirteen kernel
   relations + artifacts).

   Output shape:
     {:nodes [<node>...]
      :edges [<edge>...]}
   where node and edge keys are kebab-case Clojure data; the
   web/views/cytoscape transformer handles camelCase + JSON output.")

(defn- tag-apps-for-primitive
  "Returns the TagApplications targeting primitive `id`."
  [model id]
  (filter (fn [ta]
            (let [tgt (:target ta)]
              (and (= :target/primitive (:case tgt))
                   (= id (:id tgt)))))
          (:tag-apps model)))

(defn- tags-for-primitive
  "Tag list for primitive `id`, each `{:namespace :name}` — the raw tag
   surface for introspection (sidebar, agents). Vocabulary-agnostic."
  [model id]
  (->> (tag-apps-for-primitive model id)
       (mapv (fn [ta]
               {:namespace (-> ta :tag :namespace)
                :name      (-> ta :tag :name)}))))

(defn- tag-ref= [a b]
  (and (= (:namespace a) (:namespace b))
       (= (:name a) (:name b))))

(defn- renderers-for-tag
  "RendererRegistrations matching `tag-ref`."
  [model tag-ref]
  (filter #(tag-ref= (:tag %) tag-ref) (:renderers model)))

(defn- merge-treatments
  "Merge two consumer-treatment maps. On collision (same key in both),
   prints a warning and the second value wins. Project-layer constraint
   discipline is to surface collisions as violations; the warning is the
   v0 surfacing."
  [tag-name a b]
  (let [collisions (filter #(contains? a %) (keys b))]
    (when (seq collisions)
      (binding [*out* *err*]
        (println "fukan.projection: renderer-treatment collision on tag"
                 tag-name "keys:" (vec collisions))))
    (merge a b)))

(defn- treatments-for-primitive
  "Walk tag-apps for primitive `id`, find matching RendererRegistrations,
   merge their treatments for the given `consumer-key` (a String, e.g.
   \"node\"). Returns the merged map, or nil when no contributor exists."
  [model id consumer-key]
  (let [contributions
        (for [ta (tag-apps-for-primitive model id)
              rr (renderers-for-tag model (:tag ta))
              :let [payload (get-in rr [:treatments consumer-key])]
              :when (some? payload)]
          [(str (-> ta :tag :namespace) "::" (-> ta :tag :name)) payload])]
    (when (seq contributions)
      (reduce (fn [acc [tag-name payload]]
                (merge-treatments tag-name acc payload))
              {}
              contributions))))

(defn- primitive->node
  [model [id primitive]]
  (let [tags      (tags-for-primitive model id)
        treatment (treatments-for-primitive model id "node")]
    (cond-> {:id    id
             :kind  (:kind primitive)
             :label (:label primitive)}
      (seq tags) (assoc :tags tags)
      treatment  (assoc :treatment treatment))))

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
