(ns fukan.agent.edb
  "Project a Model into an EDB suitable for the constraint Datalog evaluator.

   EDB shape: {predicate-keyword #{[tuple-arg ...] ...}}.

   Predicates emitted:
     :primitive/kind        [id kind]
     :primitive/label       [id label]
     :primitive/owner       [id owner-id]            ; via :owns edges
     :relation/kind         [edge-id kind from-id to-id]
     :relation/validity     [edge-id validity]
     :relation/projection-kind [edge-id projection-kind]
     :artifact/kind         [artifact-id kind]")

(defn- endpoint->id [endpoint]
  (cond
    (nil? endpoint) nil
    (:endpoint/primitive endpoint) (:endpoint/primitive endpoint)
    (:endpoint/artifact  endpoint) (str "artifact:" (vec (:endpoint/artifact endpoint)))
    :else nil))

(defn- edge-id [_edge idx]
  (str "edge:" idx))

(defn- primitive-tuples [primitives]
  (let [kinds  (into #{} (map (fn [[id p]] [id (:kind p)]) primitives))
        labels (into #{}
                     (keep (fn [[id p]] (when (:label p) [id (:label p)])))
                     primitives)]
    {:primitive/kind  kinds
     :primitive/label labels}))

(defn- owner-tuples [edges]
  (->> edges
       (filter #(= :owns (:kind %)))
       (map (fn [e] [(endpoint->id (:to e)) (endpoint->id (:from e))]))
       (filter #(and (first %) (second %)))
       (into #{})))

(defn- edge-tuples [edges]
  (let [indexed (map-indexed (fn [i e] [(edge-id e i) e]) edges)
        kind   (into #{}
                     (map (fn [[id e]]
                            [id (:kind e) (endpoint->id (:from e)) (endpoint->id (:to e))]))
                     indexed)
        valid  (into #{}
                     (keep (fn [[id e]] (when-let [v (:validity e)] [id v])))
                     indexed)
        pk     (into #{}
                     (keep (fn [[id e]]
                             (when-let [pk (:projection-kind e)] [id pk])))
                     indexed)]
    {:relation/kind            kind
     :relation/validity        valid
     :relation/projection-kind pk}))

(defn- artifact-tuples [artifacts]
  (into #{}
        (map (fn [[id a]] [(str "artifact:" (vec id)) (:kind a)]))
        artifacts))

(defn model->edb
  "Project a Model value into an EDB map."
  [model]
  (merge (primitive-tuples (:primitives model))
         {:primitive/owner (owner-tuples (:edges model))}
         (edge-tuples (:edges model))
         {:artifact/kind (artifact-tuples (:artifacts model))}))
