(ns fukan.cli.commands
  "CLI command implementations.
   Each command takes [model state args] and returns
   {:response <edn-map> :state-update <fn-or-nil>}."
  (:require [clojure.string :as str]
            [fukan.projection.api :as proj]))

;; -----------------------------------------------------------------------------
;; Helpers

(defn- current-view-id
  "Get the current view-id, falling back to root."
  [model state]
  (or (:view-id state)
      (:id (proj/find-root model))))

(defn- entity-exists? [model id]
  (some? (get-in model [:nodes id])))

(defn- container? [model id]
  (seq (get-in model [:nodes id :children])))

(defn- node-summary
  "Summarize a projection node for ls output."
  [n]
  (cond-> {:id (:id n)
           :kind (:kind n)
           :label (:label n)
           :expandable? (:expandable? n)
           :child-count (:child-count n)}
    (:private? n) (assoc :private? true)))

(defn- enrich-edge
  "Add :from-label, :to-label, and stable :id to an edge."
  [model edge]
  (let [from-node (get-in model [:nodes (:from edge)])
        to-node   (get-in model [:nodes (:to edge)])]
    (-> (select-keys edge [:from :to :edge-type])
        (assoc :from-label (:label from-node)
               :to-label   (:label to-node)
               :id         (str "edge~" (:from edge) "~" (:to edge) "~" (name (:edge-type edge)))))))

(defn- build-view-context
  "Compute path + children + enriched edges for a view."
  [model state view-id]
  (let [view-node (get-in model [:nodes view-id])
        {:keys [graph path]} (proj/navigate model {:view-id view-id
                                                   :expanded (:expanded state)})
        children  (->> (:nodes graph)
                       (filter #(= view-id (:parent %)))
                       (sort-by :label)
                       (mapv node-summary))
        child-ids (into #{} (map :id children))
        edges     (->> (:edges graph)
                       (filter #(or (contains? child-ids (:from %))
                                    (contains? child-ids (:to %))))
                       (mapv #(enrich-edge model %)))]
    (cond-> {:path       (vec path)
             :view-id    view-id
             :view-label (:label view-node)
             :view-kind  (:kind view-node)
             :children   children
             :edges      edges}
      (:io graph) (assoc :io (:io graph)))))

(defn- strip-ref-docs
  "Strip :doc from schema refs and interface items for overview display.
   Docs are available via info on the specific entity."
  [entity-context]
  (cond-> entity-context
    (get-in entity-context [:interface :items])
    (update-in [:interface :items] #(mapv (fn [item] (if (map? item) (dissoc item :doc) item)) %))
    (:schemas entity-context)
    (update :schemas #(mapv (fn [ref] (dissoc ref :doc)) %))
    (get-in entity-context [:dataflow :inputs])
    (update-in [:dataflow :inputs] #(mapv (fn [ref] (dissoc ref :doc)) %))
    (get-in entity-context [:dataflow :outputs])
    (update-in [:dataflow :outputs] #(mapv (fn [ref] (dissoc ref :doc)) %))))

(defn- build-entity-context
  "Compute entity-details context for embedding in navigation responses.
   Strips inline docs from references — use info for full detail."
  [model entity-id]
  (when-let [d (proj/inspect model entity-id)]
    (-> (select-keys d [:description :interface :schemas :dataflow :deps :dependents])
        strip-ref-docs)))

;; -----------------------------------------------------------------------------
;; Commands

(defn cmd-ls
  "List children and edges at current view."
  {:malli/schema [:=> [:cat :Model :map [:vector :string]] :map]}
  [model state _args]
  (let [view-id (current-view-id model state)]
    {:response (merge {:ok true :command :ls}
                      (build-view-context model state view-id))}))

(defn cmd-cd
  "Navigate into a container or up to parent."
  {:malli/schema [:=> [:cat :Model :map [:vector :string]] :map]}
  [model state args]
  (let [target (first args)]
    (cond
      ;; cd .. — go to parent
      (= target "..")
      (let [view-id (current-view-id model state)
            parent (:parent (get-in model [:nodes view-id]))]
        (if parent
          {:response (merge {:ok true :command :cd}
                            (build-view-context model state parent)
                            {:entity-details (build-entity-context model parent)})
           :state-update #(-> %
                              (assoc :view-id parent)
                              (update :history conj view-id))}
          {:response {:ok false :command :cd
                      :error "Already at root."}}))

      ;; cd <id>
      (some? target)
      (cond
        (not (entity-exists? model target))
        {:response {:ok false :command :cd
                    :error (str "Entity not found: " target)
                    :entity-id target}}

        (not (container? model target))
        {:response {:ok false :command :cd
                    :error "Cannot navigate into leaf entity. Use 'info' instead."
                    :entity-id target}}

        :else
        (let [old-view (current-view-id model state)]
          {:response (merge {:ok true :command :cd}
                            (build-view-context model state target)
                            {:entity-details (build-entity-context model target)})
           :state-update #(-> %
                              (assoc :view-id target)
                              (update :history conj old-view))}))

      :else
      {:response {:ok false :command :cd
                  :error "Usage: cd <entity-id> or cd .."}})))

(defn cmd-back
  "Pop history stack and navigate to previous view."
  {:malli/schema [:=> [:cat :Model :map [:vector :string]] :map]}
  [model state _args]
  (let [history (:history state)]
    (if (seq history)
      (let [prev (peek history)]
        {:response (merge {:ok true :command :back}
                          (build-view-context model state prev)
                          {:entity-details (build-entity-context model prev)})
         :state-update #(-> %
                            (assoc :view-id prev)
                            (update :history pop))})
      {:response {:ok false :command :back
                  :error "No history to go back to."}})))

(defn cmd-info
  "Entity details (sidebar equivalent)."
  {:malli/schema [:=> [:cat :Model :map [:vector :string]] :map]}
  [model _state args]
  (let [entity-id (first args)]
    (if (nil? entity-id)
      {:response {:ok false :command :info
                  :error "Usage: info <entity-id>"}}
      (if-let [d (proj/inspect model entity-id)]
        {:response (merge {:ok true :command :info :entity-id entity-id} d)}
        {:response {:ok false :command :info
                    :error (str "Entity not found: " entity-id)
                    :entity-id entity-id}}))))

(defn cmd-find
  "Search nodes by label (case-insensitive, max 50)."
  {:malli/schema [:=> [:cat :Model :map [:vector :string]] :map]}
  [model _state args]
  (let [pattern (str/join " " args)]
    (if (str/blank? pattern)
      {:response {:ok false :command :find
                  :error "Usage: find <pattern>"}}
      (let [pat (str/lower-case pattern)
            matches (->> (vals (:nodes model))
                         (filter #(str/includes? (str/lower-case (:label %)) pat))
                         (take 50)
                         (mapv (fn [n]
                                 {:id (:id n)
                                  :kind (:kind n)
                                  :label (:label n)
                                  :parent (:parent n)})))]
        {:response {:ok true :command :find
                    :pattern pattern
                    :count (count matches)
                    :results matches}}))))

(defn cmd-overview
  "Model summary stats."
  {:malli/schema [:=> [:cat :Model :map [:vector :string]] :map]}
  [model state _args]
  (let [nodes (vals (:nodes model))
        by-kind (frequencies (map :kind nodes))]
    {:response {:ok true :command :overview
                :src (:src state)
                :total-nodes (count nodes)
                :total-edges (count (:edges model))
                :by-kind by-kind}}))

;; -----------------------------------------------------------------------------
;; Dispatch

(def ^:private dispatch-table
  {"ls"       cmd-ls
   "cd"       cmd-cd
   "back"     cmd-back
   "info"     cmd-info
   "find"     cmd-find
   "overview" cmd-overview})

(defn parse-input
  "Parse a line of input into {:command \"name\" :args [\"arg1\" ...]}."
  [line]
  (when-not (str/blank? line)
    (let [parts (str/split (str/trim line) #"\s+")]
      {:command (first parts)
       :args (vec (rest parts))})))

(defn dispatch
  "Dispatch a parsed command. Returns {:response :state-update}."
  [model state {:keys [command args]}]
  (if-let [handler (get dispatch-table command)]
    (handler model state args)
    {:response {:ok false :command (keyword (or command "nil"))
                :error (str "Unknown command: " command ". Valid commands: overview, cd, ls, info, find, back.")}}))
