(ns fukan.agent.api
  "The agent's layered Model interface. The ONLY namespace the SCI sandbox
   exposes alongside fukan.agent.system. See AGENTS.md for orientation;
   call (help) for the live catalog."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datascript.core :as d]
            [fukan.canvas.core.substrate.store :as store]
            [fukan.canvas.inspect.coverage :as inspect-coverage]
            [fukan.canvas.inspect.drift :as inspect-drift]
            [fukan.canvas.inspect.integrity :as inspect-integrity]
            [fukan.canvas.instruct.registry :as instruct-registry]
            [fukan.canvas.lens.registry :as lens-registry]
            [fukan.canvas.lens.survey :as lens-survey]
            [fukan.canvas.project.clojure]
            [fukan.canvas.project.core :as project-core]
            [fukan.canvas.project.registry :as project-registry]
            [fukan.canvas.projection.canvas-source :as canvas-source]
            [fukan.infra.model :as infra-model]
            [fukan.model.primitives :as primitives]
            [fukan.model.relations :as relations]
            [fukan.model.vocabulary :as vocab]
            [fukan.project-layer.defaults :as defaults]))

;; -- Helpers ------------------------------------------------------------------

(defn- ensure-model
  "Return the currently loaded Model, or throw a `:model-not-loaded` ex-info."
  []
  (or (infra-model/get-model)
      (throw (ex-info "no model loaded" {:type :model-not-loaded}))))

(defn- canvas-db
  "The enriched canvas substrate db: the unified Datascript store with
   :uses edges resolved and :entity/stable-id stamped. Reuses the db the
   model lifecycle retains; falls back to a fresh enriched build when no
   model is loaded (cold eval, tests). Carved out so tests can stub via
   `with-redefs`."
  []
  (or (infra-model/get-canvas-db)
      (canvas-source/build-substrate)))

;; -- L0 Kernel ----------------------------------------------------------------

(defn ^{:agent/layer :L0
        :agent/doc "Datascript Datalog over the canvas substrate db. Form:
                    [:find … :where …]. Query the substrate vocabulary
                    directly: :entity/type (:Module/:Affordance/:State/:Type),
                    :affordance/role (:canvas/invariant, :canvas/rule,
                    :canvas/getter, :canvas/checker, …), :entity/stable-id
                    (the cross-fn addressing currency), :module/child, :uses,
                    :edge/* (projects edges), :artifact/*, plus :triggers/:emits.
                    Returns a set of result tuples."
        :agent/example "(q '[:find ?id :where [?e :affordance/role :canvas/invariant] [?e :entity/stable-id ?id]])"}
  q
  "Evaluate a Datascript Datalog query against the canvas substrate db.
   Full d/q dialect — joins, rules, aggregates, pull. Returns a set of
   result tuples (one per :find binding row)."
  [form]
  (d/q form (canvas-db)))

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

;; -- Trust tier ---------------------------------------------------------------
;;
;; Trust-tier helpers produce decision-ready feedback: every finding is an
;; error under any methodology — no interpretive judgment required. Contrast
;; with weigh-tier lenses (Sprint 3 Task 7+), which produce interpretive
;; output the caller must weigh against context.

(defn ^{:agent/layer :trust
        :agent/origin :built-in
        :agent/doc "Cross-reference integrity check. Walks the canvas db and
                    reports every reference that fails to land — unresolved
                    :references, :triggers/:emits role mismatches, and
                    unresolved cross-module shape targets. Returns a vector
                    of finding maps; [] when clean. Every finding is
                    :severity :error."
        :agent/example "(integrity)"}
  integrity
  []
  (inspect-integrity/check (canvas-db)))

(defn ^{:agent/layer :trust
        :agent/origin :built-in
        :agent/doc "Coverage analysis. Surfaces structural coverage gaps in
                    the canvas: orphan entities (no incoming refs),
                    unreached entities (substrate-floating), exports nobody
                    consumes, modules without an (exports …) declaration,
                    rules with no triggering function, events with no
                    handler. Every finding carries :severity {:error
                    :warning :info} — callers filter by severity. Returns
                    a vector of finding maps; [] when coverage is full."
        :agent/example "(canvas-coverage)"}
  canvas-coverage
  []
  (inspect-coverage/check (canvas-db)))

(defn ^{:agent/layer :trust
        :agent/origin :built-in
        :export       true
        :agent/doc "Drift detection across canvas ↔ code. Reads the loaded
                    Model's `:relation/projects` edges and surfaces every
                    canvas declaration whose code-side counterpart is
                    missing — uniformly across functions, events,
                    invariants, rules, getters, and checkers (the
                    umbrella check). Every finding is :severity
                    :warning (drift is fact-of-discrepancy; resolution
                    is judgment). Each offender names BOTH sides —
                    canvas stable-id + expected code path + expected
                    symbol — so the LLM can weigh whether canvas or
                    code (or both) should move. Returns [] when every
                    canvas declaration has a matching code-side
                    artifact.

                    Contrast with the L2 `(drift)` view, which returns
                    raw absent edges joined with their source primitive
                    for free-form exploration; `canvas-drift` returns
                    decision-ready finding maps mirroring the integrity
                    and canvas-coverage shape.

                    Also runs shape-drift on records: compares each
                    canvas record's `:type/fields` against the matching
                    code-side defrecord/Malli schema fields (after
                    PascalCase↔lowercase alias normalisation). Each
                    shape-drift finding's offender carries `:canvas-fields`,
                    `:code-fields`, and a `:delta` of `:only-in-canvas`,
                    `:only-in-code`, `:type-mismatch`.

                    Optional :module-coord <string> narrows findings to a
                    canvas module subtree. Matching is dot-segment-aware —
                    \"distributed\" matches `distributed` and
                    `distributed.cluster.*` but NOT `distributed-foo`. Use
                    this to verify a single-module fix without re-walking
                    the whole codebase's drift output."
        :agent/example "(canvas-drift) (canvas-drift :module-coord \"distributed.cluster\")"}
  canvas-drift
  [& {:keys [module-coord] :as opts}]
  (let [unknown (seq (remove #{:module-coord} (keys opts)))]
    (when unknown
      (throw (ex-info (str "unknown canvas-drift filter: " (first unknown))
                      {:type :unknown-filter :filter (first unknown)}))))
  (inspect-drift/check (ensure-model)
                       (canvas-db)
                       {:module-coord module-coord}))

;; -- Weigh tier ---------------------------------------------------------------
;;
;; Weigh-tier helpers dispatch one or more lenses against the canvas db and
;; produce interpretive output the caller weighs against context. Lenses are
;; registered in `fukan.canvas.lens.registry` — adding a lens makes it
;; immediately available through (survey).

(defn ^{:agent/layer :weigh
        :agent/origin :built-in
        :agent/doc "Run a survey of canvas lenses. Without args, runs every
                    registered lens. With a vector of lens ids, runs only
                    those (unknown ids produce a warning entry, not an
                    error). Returns {:survey/lenses … :survey/results …
                    :survey/rendered <markdown>}. Output is interpretive —
                    findings are observations the caller weighs, not facts
                    to act on directly. See (canvas-lenses) for the
                    registered surface."
        :agent/example "(survey) (survey [:patterns])"}
  survey
  ([] (survey nil))
  ([lens-ids-or-nil]
   (let [db  (canvas-db)
         ids (or lens-ids-or-nil
                 (mapv :id (lens-registry/all-lenses)))]
     (lens-survey/run db ids))))

(defn ^{:agent/layer :weigh
        :agent/origin :built-in
        :agent/doc "List the registered lenses. Returns a vector of
                    {:id :description :prompt-fragment :has-compute?}
                    entries — the prompt-fragment is included so the LLM
                    sees how each lens primes interpretation at
                    discovery time."
        :agent/example "(canvas-lenses)"}
  canvas-lenses
  []
  (mapv (fn [lens]
          {:id              (:id lens)
           :description     (:description lens)
           :prompt-fragment (:prompt-fragment lens)
           :has-compute?    (some? (:compute lens))})
        (lens-registry/all-lenses)))

;; -- Trust tier: project-lens + scenario surfaces ----------------------------
;;
;; Phase 7 Sprint 3 Task N — expose Layer A (project lens) and Layer B
;; (scenarios) through the agent api so the canvas-author LLM can consume
;; them via `bin/fukan eval`.
;;
;; Layer A's project mm dispatches on a structured element map; canvas-db
;; carries the raw datoms. The `resolve-element` helper bridges the two:
;; given a canvas stable-id, walk the canvas-db and reconstruct the element
;; map the project lens expects (`stable-id`, `entity-name`, `module-coord`,
;; `doc`, plus kind-specific carriers — `fields`, `inputs`/`outputs`,
;; `holds-that`, `when-vec`, `payload`).
;;
;; Stable-id grammar (per `fukan.canvas.identity/stable-id`):
;;   `<module>`                  — Module
;;   `<module>/<name>`           — Affordance
;;   `<module>/type/<Name>`      — Type
;;   `<module>/state/<name>`     — State

(defn- parse-stable-id
  "Return [:Module|:Affordance|:Type|:State module-name entity-name] from a
   stable-id string, or nil if the shape is unrecognised."
  [id]
  (when (string? id)
    (let [parts (str/split id #"/" 3)]
      (cond
        (= 1 (count parts))         [:Module (first parts) (first parts)]
        (= 2 (count parts))         [:Affordance (first parts) (second parts)]
        (and (= 3 (count parts))
             (= "type" (second parts)))   [:Type (first parts) (nth parts 2)]
        (and (= 3 (count parts))
             (= "state" (second parts)))  [:State (first parts) (nth parts 2)]
        :else nil))))

(defn- read-shape
  "pr-str'd canvas shape map → parsed shape map. Returns nil on read failure."
  [s]
  (when (string? s)
    (try (edn/read-string s)
         (catch Exception _ nil))))

(defn- canvas-db-entity-for
  "Query canvas-db for the entity matching (entity-type, module-name,
   entity-name). Returns the entity id (eid) or nil. Module entities have no
   parent module — entity-name = module-name is sufficient."
  [db entity-type module-name entity-name]
  (case entity-type
    :Module
    (ffirst (d/q '[:find ?e
                   :in $ ?n
                   :where [?e :entity/type :Module]
                          [?e :entity/name ?n]]
                 db module-name))
    ;; Owned entities: child of a Module
    (ffirst (d/q '[:find ?c
                   :in $ ?mod-name ?ctype ?cname
                   :where [?m :entity/type :Module]
                          [?m :entity/name ?mod-name]
                          [?m :module/child ?c]
                          [?c :entity/type ?ctype]
                          [?c :entity/name ?cname]]
                 db module-name entity-type entity-name))))

(defn- type-element
  "Build the Layer-A element map for a canvas Type. Discriminates atomic vs
   record via the presence of `:node/shape` (records only)."
  [db eid stable-id module-name entity-name]
  (let [ent          (d/entity db eid)
        doc          (:type/doc ent)
        field-shapes (when-let [sh (:node/shape ent)]
                       (->> (:fields (store/read-reified-shape db (:db/id sh)))
                            (mapv (fn [[fname pshape]] [(keyword fname) pshape]))))]
    (cond-> {:model-element-kind :Type
             :stable-id          stable-id
             :entity-name        entity-name
             :module-coord       module-name}
      doc           (assoc :doc doc)
      (seq field-shapes)
      (assoc :type-kind :record :fields field-shapes)
      (empty? field-shapes)
      (assoc :type-kind :atomic))))

(defn- arrow-shape->inputs-outputs
  "Split an arrow shape `{:kind :arrow :inputs {:kind :record :fields …}
   :outputs <parsed-shape>}` into the [[name parsed-shape] …] form that
   function-to-defn expects, plus the bare outputs parsed-shape."
  [arrow-shape]
  (let [inputs  (vec (-> arrow-shape :inputs :fields))
        outputs (:outputs arrow-shape)]
    [inputs outputs]))

(defn- affordance-element
  "Build the Layer-A element map for a canvas Affordance. The shape carrier
   (`inputs`/`outputs`, `holds-that`, `when-vec`, `payload`) depends on
   `:affordance/role`."
  [db eid stable-id module-name entity-name]
  (let [ent     (d/entity db eid)
        role    (:affordance/role ent)
        doc     (:affordance/doc ent)
        shape   (when-let [sh (:node/shape ent)]
                  (store/read-reified-shape db (:db/id sh)))
        fe      (read-shape (:affordance/formal-expression ent))
        base    (cond-> {:model-element-kind :Affordance
                         :canvas-role        role
                         :stable-id          stable-id
                         :entity-name        entity-name
                         :module-coord       module-name}
                  doc (assoc :doc doc))]
    (case role
      :canvas/invariant
      (assoc base :holds-that (when (string? fe) fe))

      :canvas/rule
      (assoc base :when-vec (when (vector? (:when fe)) (:when fe)))

      :canvas/event
      ;; `vocab.event/event` stores the payload as
      ;; `{:kind :record :fields [[name parsed-shape] …]}` on
      ;; `:affordance/shape`. Earlier drafts of this code expected an
      ;; arrow shape and silently dropped the fields when the shape was
      ;; a record — surfaced by Phase 8 Sprint 2 Task 3's event probe.
      ;; Read the fields directly from the record shape; fall back to
      ;; an empty payload only when the canvas declared none.
      (let [payload (cond
                      (and shape (= :record (:kind shape)))
                      (vec (:fields shape))

                      (and shape (= :arrow (:kind shape)))
                      (first (arrow-shape->inputs-outputs shape))

                      :else nil)]
        (assoc base :payload (or payload [])))

      :canvas/handler
      ;; Handlers have no canvas-side `:shape` (the event vocab does not
      ;; declare an arrow shape — the on/emits formal-expression is the
      ;; carrier). Surface the :on event reference + :emits vector so
      ;; Layer A's handler-to-defn projection can render the reactive
      ;; framing. The formal-expression read here is the map shape
      ;; `{:on "<event-kw>" :emits [<event-kw> …]}` written by
      ;; `vocab.event/handler`.
      (let [fe-map (when (map? fe) fe)]
        (cond-> base
          (some? (:on fe-map))    (assoc :on    (:on fe-map))
          (seq   (:emits fe-map)) (assoc :emits (:emits fe-map))))

      ;; default: function-shaped affordance (exposed-call, operation,
      ;; getter, checker)
      (if (and shape (= :arrow (:kind shape)))
        (let [[inputs outputs] (arrow-shape->inputs-outputs shape)]
          (assoc base :inputs inputs :outputs outputs))
        base))))

(defn- module-coord->src-path
  "Convention mirror of `cold_write/module-coord->src-path` — dot→slash,
   dash→underscore, prefix `src/fukan/`, suffix `.clj`. Used by the
   `instruct` module-scope path to compute `:target-file-exists?` from
   disk so the cold-write scenario doesn't have to guess."
  [module-coord]
  (when (string? module-coord)
    (str "src/fukan/"
         (-> module-coord
             (str/replace #"-" "_")
             (str/replace #"\." "/"))
         ".clj")))

(defn- module-children-stable-ids
  "Return the stable-ids of every Affordance/Type/State entity owned by the
   given module, in canvas-declaration order. Used by `instruct` to drive
   the cold-write scenario from a module-coord scope.

   The canvas db doesn't preserve declaration order across all child kinds
   uniformly, so the result is grouped: Types first, then Affordances,
   then States. The cold-write scenario re-orders inside its own
   discipline-prose anyway (\"match canvas declaration order — types
   first …\"), so this approximation is adequate."
  [db module-name]
  (let [type-names (sort (d/q '[:find [?n ...]
                                :in $ ?mn
                                :where [?m :entity/type :Module]
                                       [?m :entity/name ?mn]
                                       [?m :module/child ?c]
                                       [?c :entity/type :Type]
                                       [?c :entity/name ?n]]
                              db module-name))
        aff-names  (sort (d/q '[:find [?n ...]
                                :in $ ?mn
                                :where [?m :entity/type :Module]
                                       [?m :entity/name ?mn]
                                       [?m :module/child ?c]
                                       [?c :entity/type :Affordance]
                                       [?c :entity/name ?n]]
                              db module-name))
        state-names (sort (d/q '[:find [?n ...]
                                 :in $ ?mn
                                 :where [?m :entity/type :Module]
                                        [?m :entity/name ?mn]
                                        [?m :module/child ?c]
                                        [?c :entity/type :State]
                                        [?c :entity/name ?n]]
                               db module-name))]
    (-> []
        (into (map #(str module-name "/type/"  %)) type-names)
        (into (map #(str module-name "/"       %)) aff-names)
        (into (map #(str module-name "/state/" %)) state-names))))

(defn- resolve-element
  "Resolve a Layer-A element from one of: a stable-id string, an existing
   element map (passed through), or a drift-finding map (the kind
   `canvas-drift` emits — offenders carry `:stable-id`).

   Throws `:element-not-found` ex-info when no canvas-db entity matches the
   parsed stable-id."
  [id-or-element]
  (cond
    (and (map? id-or-element) (contains? id-or-element :model-element-kind))
    id-or-element

    ;; Drift-finding shape: pull the first offender's stable-id.
    (and (map? id-or-element) (seq (:offenders id-or-element)))
    (recur (-> id-or-element :offenders first :stable-id))

    ;; Bare offender map (e.g. caller plucked one out of a finding).
    (and (map? id-or-element) (:stable-id id-or-element))
    (recur (:stable-id id-or-element))

    (string? id-or-element)
    (let [db          (canvas-db)
          parsed      (parse-stable-id id-or-element)
          [etype mod ename] parsed
          eid         (when parsed (canvas-db-entity-for db etype mod ename))]
      (when-not eid
        (throw (ex-info (str "no canvas entity for stable-id: " id-or-element)
                        {:type :element-not-found :stable-id id-or-element})))
      (case etype
        :Type       (type-element db eid id-or-element mod ename)
        :Affordance (affordance-element db eid id-or-element mod ename)
        :Module     {:model-element-kind :Module
                     :stable-id          id-or-element
                     :entity-name        ename
                     :module-coord       mod}
        :State      {:model-element-kind :State
                     :stable-id          id-or-element
                     :entity-name        ename
                     :module-coord       mod}))

    :else
    (throw (ex-info "spec: expected stable-id string, element map, or drift finding"
                    {:type :bad-argument :value id-or-element}))))

(defn ^{:agent/layer :trust
        :agent/origin :built-in
        :severity     :info
        :agent/doc "Project a Model element through the active Clojure lens
                    (Layer A). Accepts a canvas stable-id string
                    (\"distributed.cluster/type/NodeId\"), a Layer-A element
                    map (round-trip), or a drift finding (uses the first
                    offender's :stable-id). Returns the structured
                    projection map: :projection-kind / :lens-id /
                    :model-element-kind / :model-element-id / :target /
                    :template / :prose / :context. The :template field is
                    the deterministic code spec the implementing LLM
                    consumes; the :prose carries semantic intent.

                    Layer A is opinionated but mechanical — every
                    projection follows the lens contract documented in
                    fukan.canvas.project.core. See (canvas-projections)
                    for the registered dispatch keys."
        :agent/example "(spec \"distributed.cluster/type/NodeId\")"}
  spec
  ([id-or-element] (spec id-or-element {}))
  ([id-or-element opts]
   (let [element  (resolve-element id-or-element)
         lens-id  (or (:lens-id opts) :clojure)
         registry (or (:registry opts) (defaults/fukan-on-fukan))]
     (project-core/project lens-id element (assoc opts :registry registry)))))

(defn ^{:agent/layer :trust
        :agent/origin :built-in
        :severity     :info
        :agent/doc "Compose a Layer-A projection with a Layer-B scenario.
                    The implementing LLM consumes the result — a full
                    instruction map with the rendered markdown body, the
                    underlying code spec, and the scenario context.

                    Arguments:
                      finding-or-id  — a canvas stable-id, an element map,
                                       or a drift finding (the kind
                                       canvas-drift emits). When a drift
                                       finding is passed, it is also
                                       carried through to the scenario as
                                       `:drift-finding` so the rendered
                                       output names both sides of the gap.
                      scenario-id    — registered scenario id
                                       (:code-side/drift-close or
                                       :code-side/cold-write).
                      opts           — optional map merged into the
                                       scenario's build-context call
                                       (e.g. :include-entity-ids,
                                       :projections, :target-file-reader,
                                       :module-id).

                    Returns {:scenario-id :code-spec :scenario-context
                    :rendered}."
        :agent/example "(instruct \"infra.server/start_server\" :code-side/drift-close)
                        (instruct {:module-coord \"distributed.election\"} :code-side/cold-write)"}
  instruct
  ([finding-or-id scenario-id]
   (instruct finding-or-id scenario-id {}))
  ([finding-or-id scenario-id opts]
   (let [scenario  (instruct-registry/scenario-by-id scenario-id)
         _         (when-not scenario
                     (throw (ex-info (str "no scenario registered for id: " scenario-id)
                                     {:type :scenario-not-found
                                      :scenario-id scenario-id})))
         module-scope? (and (map? finding-or-id)
                            (string? (:module-coord finding-or-id))
                            (not (contains? finding-or-id :model-element-kind))
                            (not (:stable-id finding-or-id))
                            (not (seq (:offenders finding-or-id))))]
     (if module-scope?
       ;; Module-scoped dispatch — currently the only scenario that
       ;; consumes a whole-module scope is cold-write. Walk the canvas
       ;; for every child entity, project each through Layer A, then hand
       ;; the assembled projections vector to the scenario as if the
       ;; caller had built it. Auto-derives `:target-file-exists?` from
       ;; the conventional `src/fukan/...` path so the rendered output
       ;; gets the file-state prose right without the caller having to
       ;; pass it. Surfaced by Phase 8 Sprint 2 Task 3's cold-write
       ;; probe (no public entry point for module-scope cold-write).
       (let [module-coord (:module-coord finding-or-id)
             db           (canvas-db)
             stable-ids   (module-children-stable-ids db module-coord)
             _            (when (empty? stable-ids)
                            (throw (ex-info (str "no canvas children for module-coord: " module-coord)
                                            {:type :module-not-found
                                             :module-coord module-coord})))
             projections  (mapv #(spec %) stable-ids)
             src-path     (module-coord->src-path module-coord)
             exists?      (boolean (and src-path
                                        (.exists (java.io.File. ^String src-path))))
             build-opts   (cond-> (assoc opts
                                        :module-id module-coord
                                        :projections projections)
                            (not (contains? opts :target-file-exists?))
                            (assoc :target-file-exists? exists?))
             code-spec    nil
             context      ((:build-context scenario) code-spec build-opts)
             rendered     ((:render scenario) code-spec context build-opts)]
         rendered)
       ;; Single-element dispatch — original path.
       (let [;; If caller passed a full drift finding, surface it to the
             ;; scenario unless they explicitly overrode :drift-finding.
             drift-finding (when (and (map? finding-or-id)
                                      (seq (:offenders finding-or-id)))
                             (-> finding-or-id :offenders first
                                 (assoc :check (:check finding-or-id)
                                        :message (:message finding-or-id))))
             build-opts    (cond-> opts
                             (and drift-finding
                                  (not (contains? opts :drift-finding)))
                             (assoc :drift-finding drift-finding))
             code-spec (spec finding-or-id)
             context   ((:build-context scenario) code-spec build-opts)
             rendered  ((:render scenario) code-spec context build-opts)]
         rendered)))))

(defn ^{:agent/layer :trust
        :agent/origin :built-in
        :agent/doc "List the registered project-lens projections. Returns a
                    vector of {:lens-id :dispatch-key} entries — one per
                    [lens-id dispatch-key] pair currently `defmethod`'d on
                    the project multimethod. Mirrors (canvas-lenses) for
                    Layer-A discoverability."
        :agent/example "(canvas-projections)"}
  canvas-projections
  []
  (mapv (fn [[lens-id dispatch-key]]
          {:lens-id       lens-id
           :dispatch-key  dispatch-key})
        (project-registry/all-projections)))

(defn ^{:agent/layer :trust
        :agent/origin :built-in
        :agent/doc "List the registered Layer-B scenarios. Returns a vector
                    of {:scenario-id :description :prompt-fragment} entries
                    — the prompt-fragment is included so the LLM sees how
                    each scenario primes the rendered instruction at
                    discovery time. Mirrors (canvas-lenses) for Layer-B
                    discoverability."
        :agent/example "(canvas-scenarios)"}
  canvas-scenarios
  []
  (mapv (fn [s]
          {:scenario-id     (:scenario-id s)
           :description     (:description s)
           :prompt-fragment (:prompt-fragment s)})
        (instruct-registry/all-scenarios)))

;; -- Trust tier: closure controller (Phase 8 Sprint 3) -----------------------
;;
;; Two pure entry points compose the drift-closure dispatch loop:
;;
;;   `(close-drift-plan {…scope…})`    — renders per-finding instructions.
;;   `(close-drift-verify {:plan … :reports […]})` — verifies via fresh drift.
;;
;; The architect's Phase D loop drives between them by invoking its native
;; `Agent` tool against each plan entry's `:rendered` body and collecting
;; reports. A thin `(close-drift {…})` wrapper composes the two for tests
;; and terminal callers with an injected `:dispatch-fn` (default stub
;; returns "manual dispatch required").
;;
;; The SCI sandbox can't escape to invoke `Agent` directly; that's why
;; dispatch is *not* part of the controller's contract. See DESIGN.md
;; § "Drift, coverage, and the close-drift loop" (original design:
;; doc/plans/2026-05-28-closure-controller-design.md (Sprint 1), git history).
;;
;; Retry/concurrency thresholds (`:max-attempts`, file-fanout) ship as
;; Sprint-1-proposed defaults tagged `:trial/calibration-pending` per the
;; Sprint 2 trial findings — real-Agent dispatch data from canvas-author
;; sessions recalibrates over time.

(def ^:private drift-kind->scenario
  "Map a `(canvas-drift)` `:check` keyword to the registered Layer-B
   scenario id that closes it. Both supported drift kinds today close via
   `:code-side/drift-close` — the scenario internally dispatches by
   `:check` to pick missing-implementation vs shape-drift framing.

   Findings whose `:check` has no entry here flow into
   `close-drift-plan`'s `:unhandled` vector with reason
   `:scenario-not-found` (one of Sprint 1's six escalation triggers)."
  {:inspect.drift/missing-implementation :code-side/drift-close
   :inspect.drift/shape-drift-on-record  :code-side/drift-close})

(defn- finding->plan-entry
  "Turn one drift finding into one plan entry. Renders the per-finding
   instruction via `(instruct …)` and packages it with batching metadata
   (`:batch-key` = `:expected-code-path`). Returns nil when the finding
   has no offenders (shouldn't happen against current drift output, but
   defensively — orphans don't get an instruction rendered).

   Returns a map with `:unhandled? true` + `:reason
   :no-projection-registered` when Layer A's `(spec …)` rejects the
   finding's canvas element (Sprint 4 Task 12 escalation surface)."
  [finding scenario-id]
  (when-let [offender (first (:offenders finding))]
    (let [stable-id   (:stable-id offender)
          code-path   (:expected-code-path offender)
          canvas-kind (:canvas-kind offender)]
      (try
        (let [rendered (instruct finding scenario-id)]
          {:stable-id   stable-id
           :scenario    scenario-id
           :check       (:check finding)
           :rendered    (:rendered rendered)
           :code-spec   (:code-spec rendered)
           :context     (cond-> {:attempt 1}
                          code-path   (assoc :expected-code-path code-path)
                          canvas-kind (assoc :canvas-kind canvas-kind))
           :batch-key   (or code-path :no-path)})
        (catch clojure.lang.ExceptionInfo e
          (let [msg (.getMessage e)]
            (if (and msg (str/includes? msg "no project-lens projection registered"))
              {:unhandled? true
               :stable-id  stable-id
               :check      (:check finding)
               :reason     :no-projection-registered
               :detail     (str "Layer A has no registered projection for canvas-kind "
                                (pr-str canvas-kind) " — substrate gap upstream.")}
              (throw e))))))))

;; -- Sprint 4 — iter-2 retry rendering ---------------------------------------

(def ^:private reconciliation-preamble
  "Reconciliation-prose preamble for iter-2 instructions. Four-section
   shape per Sprint 1's 'Retry context' design — most urgent
   information first."
  (str
    "**This is iteration 2 of a drift-closure attempt.** Your previous "
    "attempt did not close the finding — `(canvas-drift)` re-ran after "
    "your edit and the same drift is still present. Read your previous "
    "attempt's report below, read the current drift state, then make a "
    "SECOND attempt that addresses why the first edit failed to close. "
    "If the failure mode looks like a substrate-side gap rather than a "
    "missed edit on your part, say so in your report — the canvas-author "
    "will escalate."))

(defn- render-iter-1-drift-section
  "Render a `:iter-1-drift` finding-snapshot as markdown. The caller
   passes either a `snapshot-finding`-shaped map or the raw drift
   finding; we accept both shapes pragmatically."
  [iter-1-drift]
  (cond
    (nil? iter-1-drift)
    "_(no drift snapshot supplied)_"

    (string? iter-1-drift)
    iter-1-drift

    (map? iter-1-drift)
    (let [check    (:check iter-1-drift)
          message  (:message iter-1-drift)
          offender (or (:offender iter-1-drift)
                       (first (:offenders iter-1-drift)))]
      (str "- check: `" (pr-str check) "`\n"
           (when message (str "- message: " message "\n"))
           (when offender
             (str "- offender: " (pr-str offender) "\n"))))

    :else
    (pr-str iter-1-drift)))

(defn- wrap-iter-2-instruction
  "Wrap an iter-1 rendered instruction with the four-section
   reconciliation prose (Sprint 1 design 'Retry context'). Sections in
   urgency order: preamble → iter-1 subagent report → iter-1 drift state
   → original instruction."
  [iter-1-rendered iter-1-report iter-1-drift]
  (str reconciliation-preamble
       "\n\n## Iter-1 subagent report\n\n"
       (or iter-1-report "_(no iter-1 report supplied)_")
       "\n\n## Iter-1 drift state\n\n"
       (render-iter-1-drift-section iter-1-drift)
       "\n\n## Original instruction\n\n"
       iter-1-rendered))

(defn- finding->unhandled-entry
  "Turn a finding whose drift-kind has no registered scenario into an
   `:unhandled` entry. Sprint 1 escalation trigger
   `:scenario-not-found`."
  [finding]
  {:stable-id (-> finding :offenders first :stable-id)
   :check     (:check finding)
   :reason    :scenario-not-found
   :detail    (str "no scenario registered for drift kind "
                   (pr-str (:check finding)))})

(defn- group-by-batch-key
  "Group plan entries by `:batch-key`. Preserves insertion order within
   each group. Returns `{batch-key [<plan-entry> …]}`."
  [plan]
  (reduce (fn [acc entry]
            (update acc (:batch-key entry) (fnil conj []) entry))
          {}
          plan))

(defn ^{:agent/layer :trust
        :agent/origin :built-in
        :severity     :info
        :export       true
        :agent/doc "Render a drift-closure plan from a scope. Calls
                    `(canvas-drift)` against the scope filter; for each
                    finding, renders the per-finding instruction via
                    `(instruct …)`; groups entries by
                    `:expected-code-path` so the architect's dispatch
                    loop can serialize same-file edits and parallelise
                    across files.

                    Scope opts (AND together):
                      :module-coord <prefix-string>
                      :check        <drift-kind-keyword>
                      :stable-id    <single-id-string>   — overrides
                                                          the other
                                                          scope filters
                      :limit        <int>                — default 25
                      :max-attempts <int>                — default 2

                    Retry opts (Sprint 4 — iter-2 rendering):
                      :retry-of      <stable-id-string>  — flags this
                                                          render as an
                                                          iter-2 attempt
                                                          for the given
                                                          finding
                      :iter-1-report <string>            — verbatim
                                                          subagent
                                                          narrative from
                                                          iter-1
                      :iter-1-drift  <snapshot or map>   — the iter-1
                                                          drift state
                                                          (post-iter-1
                                                          (canvas-drift)
                                                          output)

                    Returns
                      {:plan         [<plan-entry> …]
                       :batches      {<code-path> [<plan-entry> …]}
                       :unhandled    [<unhandled-entry> …]
                       :scope        {…echo of scope filters…}
                       :counts       {:findings-total N
                                      :findings-planned P
                                      :findings-unhandled U
                                      :findings-truncated K}
                       :truncated?   boolean
                       :remaining    <count beyond :limit>
                       :max-attempts <int — echoed for architect's loop>}

                    Pure: no dispatch, no side effects beyond reading the
                    canvas db + the loaded Model. Architect's Phase D
                    `Agent` invocations consume the `:rendered` field
                    per plan entry.

                    When `:retry-of` is present, the rendered instruction
                    is wrapped with the four-section reconciliation
                    preamble + iter-1 report + iter-1 drift + original
                    instruction (Sprint 1 design 'Retry context'). The
                    plan-entry's `:context` carries `:attempt 2` so the
                    architect's loop can route accordingly."
        :agent/example "(close-drift-plan :module-coord \"distributed.cluster\")
                        (close-drift-plan :stable-id \"distributed.log/AppendEntriesRequested\")
                        (close-drift-plan :retry-of \"x/foo\" :iter-1-report \"…\" :iter-1-drift {…})"}
  close-drift-plan
  [& {:keys [module-coord check stable-id limit max-attempts
             retry-of iter-1-report iter-1-drift]
      :or {limit 25 max-attempts 2}
      :as opts}]
  (let [known #{:module-coord :check :stable-id :limit :max-attempts
                :retry-of :iter-1-report :iter-1-drift}
        unknown (seq (remove known (keys opts)))]
    (when unknown
      (throw (ex-info (str "unknown close-drift-plan filter: " (first unknown))
                      {:type :unknown-filter :filter (first unknown)})))
    (when (and retry-of (or module-coord check stable-id))
      (throw (ex-info "close-drift-plan: :retry-of is exclusive with :module-coord/:check/:stable-id (scope is implicit — single finding)"
                      {:type :bad-argument :retry-of retry-of})))
    (if retry-of
      ;; Iter-2 render — single-finding scope implicit via :retry-of.
      (let [findings    (canvas-drift)
            finding     (some (fn [f]
                                (when (= retry-of (-> f :offenders first :stable-id))
                                  f))
                              findings)
            _           (when-not finding
                          (throw (ex-info (str "close-drift-plan: :retry-of finding not present in current drift: " retry-of)
                                          {:type :element-not-found :retry-of retry-of})))
            scenario-id (drift-kind->scenario (:check finding))
            base-entry  (and scenario-id (finding->plan-entry finding scenario-id))
            unhandled-from-projection? (and base-entry (:unhandled? base-entry))]
        (cond
          (or (not base-entry) unhandled-from-projection?)
          {:plan         []
           :batches      {}
           :unhandled    (cond
                           unhandled-from-projection? [(dissoc base-entry :unhandled?)]
                           scenario-id                []
                           :else                      [(finding->unhandled-entry finding)])
           :scope        {:retry-of retry-of}
           :counts       {:findings-total      1
                          :findings-planned    0
                          :findings-unhandled  (if (or unhandled-from-projection?
                                                       (not scenario-id))
                                                 1 0)
                          :findings-truncated  0}
           :truncated?   false
           :remaining    0
           :max-attempts max-attempts}
          :else
          (let [wrapped (wrap-iter-2-instruction (:rendered base-entry)
                                                 iter-1-report
                                                 iter-1-drift)
                entry   (-> base-entry
                            (assoc :rendered wrapped)
                            (assoc-in [:context :attempt] 2)
                            (assoc-in [:context :retry-of] retry-of))]
            {:plan         [entry]
             :batches      (group-by-batch-key [entry])
             :unhandled    []
             :scope        {:retry-of retry-of}
             :counts       {:findings-total      1
                            :findings-planned    1
                            :findings-unhandled  0
                            :findings-truncated  0}
             :truncated?   false
             :remaining    0
             :max-attempts max-attempts})))
      ;; Iter-1 render — original scope-driven path.
      (let [;; :stable-id is the strongest scope; if present, filter to it
            ;; client-side after pulling the broader (module-coord or whole)
            ;; drift output.
            drift-opts (cond-> {}
                         module-coord (assoc :module-coord module-coord))
            all-findings (apply canvas-drift (mapcat identity drift-opts))
            by-stable    (if stable-id
                           (filterv #(= stable-id (-> % :offenders first :stable-id))
                                    all-findings)
                           all-findings)
            by-check     (if check
                           (filterv #(= check (:check %)) by-stable)
                           by-stable)
            findings     by-check
            total        (count findings)
            take-n       (min total limit)
            taken        (vec (take take-n findings))
            truncated?   (> total limit)
            remaining    (max 0 (- total limit))
            ;; Partition into plannable + unhandled.
            {:keys [plan unhandled]}
            (reduce (fn [acc f]
                      (if-let [scenario-id (drift-kind->scenario (:check f))]
                        (if-let [entry (finding->plan-entry f scenario-id)]
                          (if (:unhandled? entry)
                            (update acc :unhandled conj (dissoc entry :unhandled?))
                            (update acc :plan conj entry))
                          acc)
                        (update acc :unhandled conj (finding->unhandled-entry f))))
                    {:plan [] :unhandled []}
                    taken)]
        {:plan         plan
         :batches      (group-by-batch-key plan)
         :unhandled    unhandled
         :scope        {:module-coord module-coord
                        :check        check
                        :stable-id    stable-id
                        :limit        limit}
         :counts       {:findings-total      total
                        :findings-planned    (count plan)
                        :findings-unhandled  (count unhandled)
                        :findings-truncated  remaining}
         :truncated?   truncated?
         :remaining    remaining
         :max-attempts max-attempts}))))

(defn- excerpt
  "Truncate a string at `n` chars with an ellipsis when over."
  [^String s n]
  (if (and s (> (count s) n))
    (str (subs s 0 n) "…")
    s))

(defn- snapshot-finding
  "Compact snapshot of a finding for the verify report. Drops the long
   `:detail` map so per-finding entries stay readable."
  [finding]
  (when finding
    {:check     (:check finding)
     :severity  (:severity finding)
     :message   (:message finding)
     :offender  (some-> finding :offenders first
                        (select-keys [:stable-id :expected-code-path
                                      :expected-symbol :canvas-kind]))}))

(defn- finding-still-present?
  "True if `stable-id` is still surfaced by `findings` (post-dispatch
   re-walk)."
  [stable-id findings]
  (some (fn [f]
          (= stable-id (-> f :offenders first :stable-id)))
        findings))

;; -- Sprint 4 Task 13 — canvas-side hint heuristics --------------------------

(def ^:private predicate-stubbed-patterns
  "Substring patterns that signal the implementing-LLM stubbed the
   invariant predicate without writing the real body. Heuristic (a) for
   `:canvas-side-hint` fires when both iter-1 and iter-2 reports match
   any of these."
  ["not yet implemented"
   "not implemented"
   "could not implement"
   "could not write"
   "unclear what to check"
   "not enough context"
   "needs clarification"
   "TODO"
   "placeholder"
   "stub"
   "unable to determine"])

(defn- report-suggests-stub?
  "True when the subagent's report carries any of the
   `predicate-stubbed-patterns`. Case-insensitive."
  [^String report]
  (when (string? report)
    (let [lower (.toLowerCase report)]
      (boolean (some (fn [^String pat]
                       (.contains lower (.toLowerCase pat)))
                     predicate-stubbed-patterns)))))

(defn- canvas-source-path-for
  "Map a stable-id's module-coord to its canvas source path. Mirrors
   the Phase 0 file convention `canvas/<dotpath>/<segment>.clj` —
   approximated by trying the last segment as the file. Returns the
   first path that exists, or nil."
  [stable-id]
  (when (string? stable-id)
    (let [module-coord (first (str/split stable-id #"/" 2))
          dotparts     (str/split module-coord #"\.")
          ;; Common shapes: 'canvas/foo/bar.clj' or 'canvas/foo.clj'.
          candidates   (concat
                         (when (>= (count dotparts) 2)
                           [(str "canvas/"
                                 (str/join "/" (butlast dotparts))
                                 "/"
                                 (last dotparts)
                                 ".clj")])
                         [(str "canvas/" (str/join "/" dotparts) ".clj")
                          (str "canvas/" (str/join "/" dotparts) "/" (last dotparts) ".clj")])]
      (some (fn [p]
              (when (.exists (java.io.File. ^String p))
                p))
            candidates))))

(def ^:private recent-edit-window-ms
  "How fresh a canvas file must be to count as 'recent'. 24 hours."
  (* 24 60 60 1000))

(def ^:private stable-src-window-ms
  "How old a src file must be to count as 'stable'. 7 days."
  (* 7 24 60 60 1000))

(defn- file-mtime
  "Return the file's last-modified millis, or nil if absent."
  [^String path]
  (when (and path (.exists (java.io.File. path)))
    (.lastModified (java.io.File. path))))

(defn- canvas-side-hint-shape-drift
  "Heuristic (b) — record shape-drift where the canvas-side adds fields
   but `src/` has been touched recently. The signal we *can* easily
   compute is the inverse: canvas-file freshly touched + src-file
   stable. Returns nil unless the heuristic fires."
  [plan-entry post-finding]
  (let [check       (:check plan-entry)
        stable-id   (:stable-id plan-entry)
        src-path    (-> plan-entry :context :expected-code-path)
        canvas-path (canvas-source-path-for stable-id)
        now         (System/currentTimeMillis)
        canvas-mt   (file-mtime canvas-path)
        src-mt      (file-mtime src-path)]
    (when (and (= :inspect.drift/shape-drift-on-record check)
               post-finding
               canvas-mt src-mt
               (< (- now canvas-mt) recent-edit-window-ms)
               (> (- now src-mt) stable-src-window-ms))
      {:reason :recent-canvas-stable-src
       :detail (str "Canvas file " canvas-path " was modified in the last 24h "
                    "while src file " src-path " has been stable for >7 days. "
                    "Canvas may be ahead of code — consider whether the canvas "
                    "shape addition is intentional, or retract the new fields.")})))

(defn- canvas-side-hint-stubbed-invariant
  "Heuristic (a) — invariant whose projected predicate stubbed-and-failed
   across both attempts. Fires only when:
     - canvas-kind is :invariant
     - finding still present (post-dispatch)
     - all attempt reports match `predicate-stubbed-patterns`
     - at least 2 attempts recorded
   Returns nil unless the heuristic fires."
  [plan-entry reports post-finding]
  (let [canvas-kind  (-> plan-entry :context :canvas-kind)
        stub-reports (filter #(report-suggests-stub? (:report %)) (or reports []))]
    (when (and post-finding
               (= :invariant canvas-kind)
               (>= (count reports) 2)
               (= (count reports) (count stub-reports)))
      {:reason :predicate-stubbed-twice
       :detail (str "Implementing-LLM stubbed the invariant predicate across "
                    (count reports) " attempts — each report read as 'not yet "
                    "implemented' / 'TODO' / similar. Substrate may not give "
                    "the LLM enough context to derive the predicate body. "
                    "Consider tightening the canvas `holds-that` clause or "
                    "moving the invariant to property-test projection.")})))

(defn- compute-canvas-side-hint
  "Run all canvas-side hint heuristics; return the first match or nil."
  [plan-entry reports post-finding]
  (or (canvas-side-hint-stubbed-invariant plan-entry reports post-finding)
      (canvas-side-hint-shape-drift plan-entry post-finding)))

;; -- Sprint 4 Task 12 — escalation classification ----------------------------

(defn- structured-escalation
  "Build the structured `:escalation-reason` map per Sprint 1's design.
   Keys: `:trigger` (one of the six), `:detail` (prose), and optionally
   `:hint-kind` (when `:trigger` = `:canvas-side-hint`)."
  ([trigger detail] (structured-escalation trigger detail nil))
  ([trigger detail hint-kind]
   (cond-> {:trigger trigger :detail detail}
     hint-kind (assoc :hint-kind hint-kind))))

(defn- dispatch-error-report?
  "True when the architect's `Agent` call surfaced an error rather than a
   subagent narrative — report carries `:error true` or `:error <string>`."
  [report]
  (and (map? report)
       (or (true? (:error report))
           (string? (:error report)))))

(defn- classify-outcome
  "Decide a per-finding outcome from the plan entry + pre/post drift +
   reports. Returns
     {:outcome :closed | :failed | :no-report
      :requires-retry? bool
      :escalation-reason <structured map or nil>
      :canvas-side-hint <hint-map or nil>}.

   Sprint 4 Task 12 lands the full six-trigger classification with
   structured `:escalation-reason` maps. Task 13 adds the
   `:canvas-side-hint` heuristics on top.

   Triggers handled here (other two surface via the plan's `:unhandled`
   vector — `:scenario-not-found`, `:no-projection-registered`):
     - `:dispatch-error` — latest report flagged with `:error`
     - `:attempts-exhausted` — present + attempts >= max
     - `:canvas-side-hint` — Task 13 heuristic fired
     - `:projection-emits-warning` — reserved; not fired today (Layer A
       has no `:warnings` surface yet). Phase 9 Sprint 3 Task 8 confirmed
       this trigger stays reserved: no projection in
       `src/fukan/canvas/project/clojure/*.clj` emits a `:warnings`
       vector, and synthesising one purely to fire the trigger creates
       test noise without representing real Layer A behaviour. Re-open
       when a projection has a genuine cause to fall back / skip with a
       structured warning."
  [plan-entry reports post-finding post-findings attempts max-attempts]
  (let [latest    (last (sort-by (fnil :attempt 0) (or reports [])))
        present?  (finding-still-present? (:stable-id plan-entry) post-findings)
        hint      (when present?
                    (compute-canvas-side-hint plan-entry reports post-finding))]
    (cond
      (dispatch-error-report? latest)
      {:outcome :failed
       :requires-retry? false
       :escalation-reason (structured-escalation
                            :dispatch-error
                            (str "Dispatch failed for " (:stable-id plan-entry)
                                 " — architect's Agent invocation returned an "
                                 "error rather than a subagent narrative: "
                                 (pr-str (or (:error latest) "error flag set"))))
       :canvas-side-hint nil}

      (nil? latest)
      {:outcome :no-report
       :requires-retry? false
       :escalation-reason (structured-escalation
                            :dispatch-error
                            (str "No report received for " (:stable-id plan-entry)
                                 " — architect did not dispatch this finding, "
                                 "or the subagent did not return a terminal "
                                 "report."))
       :canvas-side-hint nil}

      (not present?)
      {:outcome :closed
       :requires-retry? false
       :escalation-reason nil
       :canvas-side-hint nil}

      ;; Still present after dispatch — pick the most specific escalation.
      :else
      (let [exhausted? (>= attempts max-attempts)]
        {:outcome :failed
         :requires-retry? (not exhausted?)
         :escalation-reason
         (cond
           hint
           (structured-escalation
             :canvas-side-hint
             (:detail hint)
             (:reason hint))

           exhausted?
           (structured-escalation
             :attempts-exhausted
             (str "Finding " (:stable-id plan-entry) " failed all "
                  attempts " attempts. Drift still names the same gap "
                  "after the final dispatch — manual review required."))

           :else nil)
         :canvas-side-hint hint}))))

(defn- escalation-trigger-name
  "Extract the trigger keyword's name from either a structured map (Sprint
   4) or a bare keyword (legacy/MVP)."
  [reason]
  (cond
    (map? reason)     (some-> (:trigger reason) name)
    (keyword? reason) (name reason)
    :else             nil))

(defn- escalation-detail
  "Extract the human-readable detail string from a structured
   escalation-reason map. Returns nil for legacy bare-keyword reasons."
  [reason]
  (when (map? reason)
    (:detail reason)))

(defn- render-verify-markdown
  "Render the verify report as markdown — terse, factual, decision-ready.
   Mirrors Phase 7's `(instruct …)` body discipline (structured headings,
   no marketing)."
  [{:keys [scope counts per-finding]}]
  (let [{:keys [findings-total findings-closed findings-failed
                findings-escalated total-attempts iter-1-closure-rate
                iter-2-closure-rate total-elapsed-ms]} counts
        scope-line (cond
                     (:retry-of scope)     (str "retry-of " (:retry-of scope))
                     (:stable-id scope)    (str "stable-id " (:stable-id scope))
                     (:module-coord scope) (str "module-coord " (pr-str (:module-coord scope))
                                                (when (:check scope)
                                                  (str ", check " (pr-str (:check scope)))))
                     (:check scope)        (str "check " (pr-str (:check scope)))
                     :else                 "all canvas drift")
        lines (atom [])
        push! (fn [& xs] (swap! lines conj (apply str xs)))
        fmt-pct (fn [r] (format "%.0f%%" (* 100.0 (or r 0.0))))]
    (push! "# Close-drift report")
    (push! "")
    (push! "**Scope:** " scope-line)
    (push! "")
    (push! "**Summary:** " findings-closed " of " findings-total
           " closed; " findings-failed " failed; "
           findings-escalated " escalated.")
    (push! "")
    (push! "**Attempts:** " (or total-attempts 0) " total"
           (when (some? iter-1-closure-rate)
             (str " · iter-1 closure rate: " (fmt-pct iter-1-closure-rate)))
           (when (and (some? iter-2-closure-rate) (pos? iter-2-closure-rate))
             (str " · iter-2 closure rate: " (fmt-pct iter-2-closure-rate)))
           (when total-elapsed-ms
             (str " · total elapsed: " total-elapsed-ms "ms")))
    (push! "")
    (when (seq per-finding)
      (push! "## Per-finding outcomes")
      (push! "")
      (doseq [pf per-finding]
        (push! "- `" (:stable-id pf) "` — **" (name (:outcome pf)) "**"
               (when (and (:attempts pf) (pos? (:attempts pf)))
                 (str " (attempts: " (:attempts pf) ")"))
               (when-let [tn (escalation-trigger-name (:escalation-reason pf))]
                 (str " · escalation: `" tn "`"))
               (when-let [h (:canvas-side-hint pf)]
                 (str " · canvas-side-hint: `" (some-> (:reason h) name) "`")))))
    (push! "")
    (let [escalated (filter #(= :failed (:outcome %)) per-finding)]
      (when (seq escalated)
        (push! "## Escalations")
        (push! "")
        (doseq [pf escalated]
          (push! "### `" (:stable-id pf) "`")
          (push! "")
          (when-let [tn (escalation-trigger-name (:escalation-reason pf))]
            (push! "**Reason:** `" tn "`")
            (push! ""))
          (when-let [d (escalation-detail (:escalation-reason pf))]
            (push! d)
            (push! ""))
          (when-let [h (:canvas-side-hint pf)]
            (push! "**Canvas-side hint** (`" (some-> (:reason h) name) "`): " (:detail h))
            (push! ""))
          (when-let [rep (:report-excerpt pf)]
            (push! "**Report excerpt:** " rep)
            (push! ""))
          (when-let [snap (:post-drift-snapshot pf)]
            (push! "**Post-drift:** " (pr-str snap))
            (push! "")))))
    (let [no-report (filter #(= :no-report (:outcome %)) per-finding)]
      (when (seq no-report)
        (push! "## No report received")
        (push! "")
        (doseq [pf no-report]
          (push! "- `" (:stable-id pf) "` — architect did not dispatch this finding"))))
    (str/join "\n" @lines)))

(defn ^{:agent/layer :trust
        :agent/origin :built-in
        :severity     :info
        :export       true
        :agent/doc "Verify a drift-closure dispatch round. Consumes the
                    plan from `(close-drift-plan …)` and the architect's
                    per-finding reports; re-runs `(canvas-drift)` against
                    the plan's scope to classify outcomes.

                    **Plan-snapshot dependency.** `:plan` must be the
                    snapshot returned by `(close-drift-plan …)` taken
                    BEFORE dispatches landed. A fresh `(close-drift-plan)`
                    called AFTER dispatches won't see closed findings —
                    their stable-ids will be absent from the new plan,
                    and verify won't classify them as `:closed`. Hold the
                    original plan across the dispatch step; pass it back
                    to verify. Throws `:stale-plan` ex-info when the
                    plan-snapshot+reports shape suggests verify was
                    called after dispatch with a fresh plan (no plan
                    stable-id overlaps current drift AND `:reports` is
                    non-empty).

                    Args (as kw-args or single map):
                      :plan     <plan from close-drift-plan>
                      :reports  [{:stable-id … :report \"…\" :attempt 1} …]

                    Returns
                      {:scope        <echo of plan's scope>
                       :counts       {:findings-total N
                                      :findings-closed M
                                      :findings-failed K
                                      :findings-escalated E
                                      :findings-no-report R}
                       :per-finding  [<entry> …]
                       :rendered     \"…markdown summary…\"}

                    Per-finding entry shape:
                      {:stable-id           …
                       :outcome             :closed | :failed | :no-report
                       :attempts            <int>
                       :requires-retry?     <bool>
                       :escalation-reason   <structured map or nil>
                       :canvas-side-hint    <hint-map or nil>
                       :per-attempt         [{:attempt 1
                                              :report-excerpt \"…\"
                                              :elapsed-ms <int or nil>} …]
                       :report-excerpt      <truncated final report>
                       :pre-drift-snapshot  <compact finding snapshot>
                       :post-drift-snapshot <compact finding snapshot or nil>}

                    Escalation-reason map shape:
                      {:trigger    <one of the six trigger keywords>
                       :detail     <human-readable prose>
                       :hint-kind  <kw, only when :trigger
                                    = :canvas-side-hint>}

                    Six escalation triggers (Sprint 4 Task 12):
                      :attempts-exhausted, :no-projection-registered,
                      :projection-emits-warning (reserved),
                      :canvas-side-hint, :scenario-not-found,
                      :dispatch-error.

                    `:counts` also surfaces aggregate timing + closure
                    rates (Sprint 4 Task 14): :iter-1-closure-rate,
                    :iter-2-closure-rate, :total-attempts,
                    :total-elapsed-ms."
        :agent/example "(close-drift-verify :plan p :reports [{:stable-id \"x\" :report \"done\" :attempt 1}])"}
  close-drift-verify
  [& {:keys [plan reports] :as opts}]
  (let [known #{:plan :reports}
        unknown (seq (remove known (keys opts)))]
    (when unknown
      (throw (ex-info (str "unknown close-drift-verify arg: " (first unknown))
                      {:type :unknown-filter :filter (first unknown)})))
    (when-not (and (map? plan) (vector? (:plan plan)))
      (throw (ex-info "close-drift-verify: :plan must be the return of close-drift-plan"
                      {:type :bad-argument :plan plan})))
    ;; Stale-plan heuristic (Phase 9 Sprint 2 Task 5b). The Sprint 6
    ;; ad-hoc mistake: canvas-author calls `close-drift-plan` AFTER
    ;; dispatches landed, then passes that fresh plan to verify along
    ;; with reports captured pre-dispatch. The fresh plan won't see
    ;; closed findings — verify mis-classifies. Catch the shape: reports
    ;; carry stable-ids that the plan doesn't recognise. The plan would
    ;; legitimately not recognise a report's stable-id only when the
    ;; canvas-author is mixing reports across scopes — also a bug.
    (let [plan-stable-ids   (set (map :stable-id (:plan plan)))
          report-stable-ids (set (keep :stable-id (or reports [])))
          unmatched-reports (into #{} (remove plan-stable-ids) report-stable-ids)]
      (when (and (seq report-stable-ids)
                 (= unmatched-reports report-stable-ids))
        (throw (ex-info
                 (str "plan appears stale — its findings are absent from "
                      "current drift output. If dispatches landed since the "
                      "plan was captured, the plan snapshot from BEFORE "
                      "dispatch is required. Re-run close-drift-plan and "
                      "snapshot the result before dispatches.")
                 {:type :stale-plan
                  :plan-scope (:scope plan)
                  :reports-count (count reports)
                  :unmatched-report-stable-ids (vec unmatched-reports)}))))
    (let [max-attempts  (or (:max-attempts plan) 2)
          scope         (:scope plan)
          plan-entries  (:plan plan)
          unhandled     (:unhandled plan)
          report-by-id  (reduce (fn [acc r]
                                  (update acc (:stable-id r)
                                          (fnil conj []) r))
                                {}
                                (or reports []))
          ;; Snapshot pre-dispatch drift via the plan's findings (the
          ;; plan already captured the per-finding shape at plan time).
          pre-snapshots (into {}
                              (map (fn [pe]
                                     [(:stable-id pe)
                                      (snapshot-finding
                                        (assoc {:check (:check pe)
                                                :severity :warning
                                                :message nil
                                                :offenders [(merge {:stable-id (:stable-id pe)}
                                                                   (:context pe))]}
                                               :detail nil))]))
                              plan-entries)
          ;; Re-run drift across the plan's scope.
          drift-opts    (cond-> {}
                          (:module-coord scope) (assoc :module-coord (:module-coord scope)))
          post-findings (apply canvas-drift (mapcat identity drift-opts))
          per-finding   (mapv (fn [pe]
                                (let [rs           (vec (sort-by (fnil :attempt 0)
                                                                  (get report-by-id (:stable-id pe))))
                                      ;; Latest attempt for the finding.
                                      latest       (last rs)
                                      attempts     (count rs)
                                      post-finding (some (fn [f]
                                                           (when (= (:stable-id pe)
                                                                    (-> f :offenders first :stable-id))
                                                             f))
                                                         post-findings)
                                      cls          (classify-outcome pe rs post-finding
                                                                      post-findings
                                                                      attempts max-attempts)
                                      per-attempt  (mapv (fn [r]
                                                           {:attempt        (or (:attempt r) 1)
                                                            :report-excerpt (some-> (:report r) (excerpt 280))
                                                            :elapsed-ms     (:elapsed-ms r)})
                                                         rs)]
                                  {:stable-id          (:stable-id pe)
                                   :outcome            (:outcome cls)
                                   :attempts           attempts
                                   :requires-retry?    (:requires-retry? cls)
                                   :escalation-reason  (:escalation-reason cls)
                                   :canvas-side-hint   (:canvas-side-hint cls)
                                   :per-attempt        per-attempt
                                   :report-excerpt     (some-> latest :report (excerpt 280))
                                   :pre-drift-snapshot (get pre-snapshots (:stable-id pe))
                                   :post-drift-snapshot (snapshot-finding post-finding)}))
                              plan-entries)
          ;; Unhandled findings (no scenario / no projection) count as escalated.
          unhandled-entries (mapv (fn [u]
                                    {:stable-id          (:stable-id u)
                                     :outcome            :failed
                                     :attempts           0
                                     :requires-retry?    false
                                     :escalation-reason  (structured-escalation
                                                           (:reason u)
                                                           (or (:detail u) (str "unhandled — " (:reason u))))
                                     :canvas-side-hint   nil
                                     :per-attempt        []
                                     :report-excerpt     (:detail u)
                                     :pre-drift-snapshot nil
                                     :post-drift-snapshot nil})
                                  (or unhandled []))
          all-pf        (into per-finding unhandled-entries)
          closed-count  (count (filter #(= :closed   (:outcome %)) all-pf))
          failed-count  (count (filter #(= :failed   (:outcome %)) all-pf))
          no-report-cnt (count (filter #(= :no-report (:outcome %)) all-pf))
          escalated-cnt (count (filter :escalation-reason all-pf))
          ;; Task 14 — aggregate timing + closure rates.
          total-attempts   (reduce + 0 (map :attempts all-pf))
          retried-set      (filter #(>= (:attempts %) 2) all-pf)
          closed-iter-1    (count (filter #(and (= :closed (:outcome %))
                                                (= 1 (:attempts %)))
                                          all-pf))
          closed-iter-2    (count (filter #(and (= :closed (:outcome %))
                                                (>= (:attempts %) 2))
                                          all-pf))
          total-elapsed-ms (when-let [ms (seq (keep :elapsed-ms
                                                    (mapcat :per-attempt all-pf)))]
                             (reduce + 0 ms))
          rate (fn [n d] (if (pos? d) (double (/ n d)) 0.0))
          counts        {:findings-total       (count all-pf)
                         :findings-closed      closed-count
                         :findings-failed      failed-count
                         :findings-no-report   no-report-cnt
                         :findings-escalated   escalated-cnt
                         :total-attempts       total-attempts
                         :iter-1-closure-rate  (rate closed-iter-1 (count all-pf))
                         :iter-2-closure-rate  (rate closed-iter-2 (count retried-set))
                         :total-elapsed-ms     total-elapsed-ms}
          result        {:scope       scope
                         :counts      counts
                         :per-finding all-pf}]
      (assoc result :rendered (render-verify-markdown result)))))

(defn- default-dispatch-fn
  "Stub dispatch-fn for terminal `bin/fukan eval` callers. Surfaces a
   useful 'use the architect' message rather than silently no-op'ing.
   Tests inject their own stub via `:dispatch-fn`."
  [{:keys [stable-id]}]
  {:stable-id stable-id
   :report    "manual dispatch required — fukan-architect's Phase D close-drift mode handles dispatch"
   :attempt   1})

(defn ^{:agent/layer :trust
        :agent/origin :built-in
        :severity     :info
        :export       true
        :agent/doc "Convenience wrapper composing `(close-drift-plan …)`
                    and `(close-drift-verify …)` end-to-end.

                    Takes the same scope opts as `close-drift-plan` plus:
                      :dispatch-fn  <fn>  — called per plan entry as
                                            (f {:stable-id … :rendered …
                                                :context …}); must
                                            return {:stable-id …
                                                    :report \"…\"
                                                    :attempt 1}.

                    Defaults to a stub surfacing 'manual dispatch
                    required'. Tests inject canned-report stubs; the
                    architect's Phase D loop calls the two entry points
                    directly with real `Agent` invocations between.

                    Returns the same shape as `close-drift-verify`."
        :agent/example "(close-drift :module-coord \"distributed.cluster\")"}
  close-drift
  [& {:keys [dispatch-fn] :as opts}]
  (let [dispatch-fn (or dispatch-fn default-dispatch-fn)
        plan-opts   (dissoc opts :dispatch-fn)
        plan        (apply close-drift-plan (mapcat identity plan-opts))
        reports     (mapv (fn [entry]
                            (let [r (dispatch-fn {:stable-id (:stable-id entry)
                                                  :rendered  (:rendered entry)
                                                  :context   (:context entry)})]
                              (cond-> r
                                (nil? (:stable-id r)) (assoc :stable-id (:stable-id entry))
                                (nil? (:attempt r))   (assoc :attempt 1))))
                          (:plan plan))]
    (close-drift-verify :plan plan :reports reports)))
