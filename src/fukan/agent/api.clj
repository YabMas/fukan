(ns fukan.agent.api
  "The agent's layered Model interface. The ONLY namespace the SCI sandbox
   exposes alongside fukan.agent.system. See AGENTS.md for orientation;
   call (help) for the live catalog."
  (:require [fukan.agent.edb :as edb]
            [fukan.agent.query :as query]
            [fukan.infra.model :as infra-model]
            [fukan.model.primitives :as primitives]
            [fukan.model.relations :as relations]
            [fukan.model.vocabulary :as vocab]))

;; -- Helpers ------------------------------------------------------------------

(defn- ensure-model
  "Return the currently loaded Model, or throw a `:model-not-loaded` ex-info."
  []
  (or (infra-model/get-model)
      (throw (ex-info "no model loaded" {:type :model-not-loaded}))))

;; -- L0 Kernel ----------------------------------------------------------------

(defn ^{:agent/layer :L0
        :agent/doc "Datalog over the loaded Model. Form: [:find … :where …]."
        :agent/example "(q '[:find ?p :where [?p :primitive/kind :primitive/behaviour]])"}
  q
  "Evaluate a Datalog query against the loaded Model.
   Returns a vector of binding maps keyed by :find variables."
  [form]
  (let [m (ensure-model)]
    (query/evaluate (query/parse form) (edb/model->edb m))))

;; -- L1 Probes ----------------------------------------------------------------

(def ^:private default-limit 1000)

(defn- summary [p]
  (select-keys p [:id :kind :label]))

(defn- apply-filters [p filters]
  (reduce-kv
    (fn [keep? k v]
      (and keep?
           (case k
             :kind  (= v (:kind p))
             :label (= v (:label p))
             (throw (ex-info (str "unknown primitive filter: " k)
                             {:type :unknown-filter :filter k})))))
    true filters))

(defn- envelope [rows limit offset]
  (let [total   (count rows)
        page    (vec (->> rows (drop offset) (take limit)))
        end     (+ offset (count page))]
    {:rows page
     :truncated? (< end total)
     :total total
     :offset offset}))

(defn ^{:agent/layer :L1
        :agent/doc "List Model primitive summaries. Optional filters: :kind :label.
                    Returns {:rows … :truncated? bool :total N}."
        :agent/example "(primitives :kind :primitive/behaviour)"}
  primitives
  [& {:keys [limit offset] :or {limit default-limit offset 0} :as opts}]
  (let [m       (ensure-model)
        filters (dissoc opts :limit :offset)
        rows    (->> (vals (:primitives m))
                     (filter #(apply-filters % filters))
                     (map summary))]
    (envelope rows limit offset)))

(defn ^{:agent/layer :L1
        :agent/doc "Return the full primitive map for an id, or nil if absent."
        :agent/example "(get-primitive \"behaviour:hex/core/r-mint\")"}
  get-primitive
  [id]
  (when-let [m (infra-model/get-model)]
    (get-in m [:primitives id])))

(def ^:private known-relation-filters
  #{:kind :from :to :validity :projection-kind})

(defn- relation-matches? [edge {:keys [kind from to validity projection-kind] :as filters}]
  (let [unknown (seq (remove known-relation-filters (keys filters)))]
    (when unknown
      (throw (ex-info (str "unknown relation filter: " (first unknown))
                      {:type :unknown-filter :filter (first unknown)}))))
  (and (or (nil? kind) (= kind (:kind edge)))
       (or (nil? from) (= from (-> edge :from :id)))
       (or (nil? to)   (= to   (-> edge :to   :id)))
       (or (nil? validity) (= validity (:validity edge)))
       (or (nil? projection-kind) (= projection-kind (:projection-kind edge)))))

(defn ^{:agent/layer :L1
        :agent/doc "List Model relations (edges). Filters: :kind :from :to :validity
                    :projection-kind. Returns {:rows … :truncated? bool :total N}.
                    Each row is a full edge map (edges are compact)."
        :agent/example "(relations :kind :projects :validity :absent)"}
  relations
  [& {:keys [limit offset] :or {limit default-limit offset 0} :as opts}]
  (let [m       (ensure-model)
        filters (dissoc opts :limit :offset)
        rows    (->> (:edges m)
                     (filter #(relation-matches? % filters)))]
    (envelope rows limit offset)))

(def ^:private known-vocabulary-filters #{:face-role})

(defn- primitive-kind-entry [kind in-use-pks]
  {:kind      kind
   :doc       (get vocab/primitive-kind-docs kind)
   :face-role (get vocab/primitive-kind-face-roles kind)
   :in-use?   (contains? in-use-pks kind)})

(defn- relation-kind-entry [kind in-use-rks]
  {:kind    kind
   :in-use? (contains? in-use-rks kind)})

(defn ^{:agent/layer :L1
        :agent/doc "Surface the kernel-declared primitive-kinds (with one-sentence
                    docs and :face-role tags) and relation-kinds. Includes every
                    kind the kernel substrate declares, whether or not the loaded
                    Model contains an instance — each entry carries :in-use? so
                    callers can distinguish 'kernel surface' from 'observed in
                    this model'. Optional filter: :face-role (one of :face-host,
                    :face-interface, :face-component, :face-peer)."
        :agent/example "(vocabulary) (vocabulary :face-role :face-interface)"}
  vocabulary
  [& {:as opts}]
  (let [unknown (seq (remove known-vocabulary-filters (keys opts)))]
    (when unknown
      (throw (ex-info (str "unknown vocabulary filter: " (first unknown))
                      {:type :unknown-filter :filter (first unknown)}))))
  (let [m           (ensure-model)
        in-use-pks  (into #{} (map :kind) (vals (:primitives m)))
        in-use-rks  (into #{} (map :kind) (:edges m))
        face-role   (:face-role opts)
        pk-entries  (->> primitives/primitive-kinds
                         (map #(primitive-kind-entry % in-use-pks))
                         (filter #(or (nil? face-role) (= face-role (:face-role %))))
                         (sort-by :kind)
                         vec)
        rk-entries  (->> relations/relation-kinds
                         (map #(relation-kind-entry % in-use-rks))
                         (sort-by :kind)
                         vec)]
    (cond-> {:primitive-kinds pk-entries}
      (nil? face-role) (assoc :relation-kinds rk-entries))))

(defn ^{:agent/layer :L1
        :agent/doc "Surface the attribute keys observed on primitives of a given :kind,
                    plus the relation kinds they participate in. Empirical — read from
                    the loaded Model, not the static schema."
        :agent/example "(schema :kind :primitive/behaviour)"}
  schema
  [& {:keys [kind]}]
  (let [m       (ensure-model)
        matched (filter #(= kind (:kind %)) (vals (:primitives m)))
        attrs   (into #{} (mapcat keys) matched)
        ids     (into #{} (map :id) matched)
        rels    (into #{}
                      (keep (fn [e]
                              (when (or (ids (-> e :from :id))
                                        (ids (-> e :to :id)))
                                (:kind e))))
                      (:edges m))]
    {:kind kind
     :attributes (sort attrs)
     :relations  (sort rels)
     :count      (count matched)}))

(def ^:private known-artifact-filters
  #{:sub-case :language :public?})

(defn- artifact-summary [a]
  (let [sub (:sub a)]
    {:id            (str "artifact:" (vec [(get sub :case)
                                            (:language a)
                                            (:qualified-name sub)]))
     :case          (:case a)
     :sub-case      (:case sub)
     :language      (:language a)
     :qualified-name (:qualified-name sub)
     :public?       (:public? sub)
     :source-location (:source-location sub)}))

(defn- artifact-matches? [a {:keys [sub-case language public?] :as filters}]
  (let [unknown (seq (remove known-artifact-filters (keys filters)))]
    (when unknown
      (throw (ex-info (str "unknown artifact filter: " (first unknown))
                      {:type :unknown-filter :filter (first unknown)}))))
  (and (or (nil? sub-case) (= sub-case (-> a :sub :case)))
       (or (nil? language) (= language (:language a)))
       (or (nil? public?)  (= public?  (-> a :sub :public?)))))

(defn ^{:agent/layer :L1
        :agent/doc "List artifact summaries. Filters: :sub-case (:code/function or
                    :code/data-structure), :language, :public?. Returns the
                    standard envelope {:rows … :truncated? bool :total N}."
        :agent/example "(artifacts :sub-case :code/function :public? true)"}
  artifacts
  [& {:keys [limit offset] :or {limit default-limit offset 0} :as opts}]
  (let [m       (ensure-model)
        filters (dissoc opts :limit :offset)
        rows    (->> (vals (:artifacts m))
                     (filter #(artifact-matches? % filters))
                     (map artifact-summary))]
    (envelope rows limit offset)))

(defn ^{:agent/layer :L1
        :agent/doc "Project-layer idiom entries. Returns a vector of entry maps."
        :agent/example "(idioms)"}
  idioms
  [& _opts]
  (or (when-let [m (infra-model/get-model)]
        (vec (or (:idioms m) (-> m :project-layer :idioms))))
      []))

(defn ^{:agent/layer :L1
        :agent/doc "Project-layer constraint definitions. Returns a vector."
        :agent/example "(constraints)"}
  constraints
  [& _opts]
  (or (when-let [m (infra-model/get-model)]
        (vec (or (:constraints m) (-> m :project-layer :constraints))))
      []))

(defn ^{:agent/layer :L1
        :agent/doc "Current constraint violations. Filters: :severity."
        :agent/example "(violations :severity :error)"}
  violations
  [& {:keys [severity] :as opts}]
  (let [unknown (seq (remove #{:severity} (keys opts)))]
    (when unknown
      (throw (ex-info (str "unknown violation filter: " (first unknown))
                      {:type :unknown-filter :filter (first unknown)}))))
  (let [m (infra-model/get-model)
        all (vec (or (:violations m) []))]
    (if severity
      (filterv #(= severity (:severity %)) all)
      all)))

;; -- L2 Views -----------------------------------------------------------------

(defn ^{:agent/layer :L2
        :agent/origin :built-in
        :agent/doc "Absent projections, joined with their source primitive.
                    Optional :projection-kind filter. Returns a vector (not a
                    listing envelope) because L2 views are pre-shaped for the
                    question they answer."
        :agent/example "(drift) (drift :projection-kind :clojure)"}
  drift
  [& {:keys [projection-kind]}]
  (let [rows (if projection-kind
               (:rows (relations :kind :relation/projects :validity :absent :projection-kind projection-kind))
               (:rows (relations :kind :relation/projects :validity :absent)))]
    (mapv (fn [e]
            (assoc e :primitive (get-primitive (-> e :from :id))))
          rows)))

(defn ^{:agent/layer :L2
        :agent/origin :built-in
        :agent/doc "Primitive + its one-hop outgoing and incoming edges + summaries
                    of the directly-connected neighbors. Multi-hop is the caller's
                    job at L0."
        :agent/example "(neighborhood \"container:hex/core\")"}
  neighborhood
  [id]
  (when-let [p (get-primitive id)]
    (let [out (:rows (relations :from id))
          in  (:rows (relations :to id))
          neighbor-ids (->> (concat out in)
                            (mapcat (juxt #(-> % :from :id)
                                          #(-> % :to   :id)))
                            (remove #{id nil})
                            distinct)
          neighbors (mapv #(let [np (get-primitive %)]
                             (select-keys np [:id :kind :label]))
                          neighbor-ids)]
      {:primitive p
       :outgoing  out
       :incoming  in
       :neighbors neighbors})))

(defn ^{:agent/layer :L2
        :agent/origin :built-in
        :agent/doc "Spec→code coverage for public Clojure functions.
                    Returns a map with counts and percentages. Public functions
                    only — spec is not expected to cover private helpers or
                    data structures. A function is 'covered' when at least one
                    inbound :projects edge has :validity :valid; 'expected' when
                    edges exist but all are :absent/:stale; 'unprojected' when
                    no spec primitive references it."
        :agent/example "(coverage)"}
  coverage
  []
  (let [m            (ensure-model)
        publics      (->> (vals (:artifacts m))
                          (filter #(and (= :code/function (-> % :sub :case))
                                        (true? (-> % :sub :public?)))))
        edges        (filter #(= :relation/projects (:kind %)) (:edges m))
        valid-tos    (set (keep #(when (= :valid   (:validity %)) (-> % :to :id)) edges))
        absent-tos   (set (keep #(when (= :absent  (:validity %)) (-> % :to :id)) edges))
        any-edge-tos (set (keep #(-> % :to :id) edges))
        artifact-id  (fn [a]
                       [(-> a :sub :case) (:language a) (-> a :sub :qualified-name)])
        covered      (filter #(contains? valid-tos    (artifact-id %)) publics)
        expected     (filter #(and (contains? any-edge-tos (artifact-id %))
                                   (not (contains? valid-tos (artifact-id %))))
                             publics)
        unprojected  (remove #(contains? any-edge-tos (artifact-id %)) publics)
        total        (count publics)
        ratio        (fn [n] (if (zero? total) 0.0 (/ (double n) total)))]
    {:total-public-functions total
     :covered                (count covered)
     :expected-not-realised  (count expected)
     :unprojected            (count unprojected)
     :covered-ratio          (ratio (count covered))
     :unprojected-ratio      (ratio (count unprojected))
     :absent-edge-count      (count absent-tos)}))
