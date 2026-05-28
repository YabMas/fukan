(ns fukan.agent.api
  "The agent's layered Model interface. The ONLY namespace the SCI sandbox
   exposes alongside fukan.agent.system. See AGENTS.md for orientation;
   call (help) for the live catalog."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datascript.core :as d]
            [fukan.agent.edb :as edb]
            [fukan.agent.query :as query]
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
  (inspect-integrity/check (canvas-source/build-canvas-db)))

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
  (inspect-coverage/check (canvas-source/build-canvas-db)))

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
                       (canvas-source/build-canvas-db)
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
   (let [db  (canvas-source/build-canvas-db)
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
   record via the presence of `:type/field-shapes` (records only)."
  [db eid stable-id module-name entity-name]
  (let [ent          (d/entity db eid)
        doc          (:type/doc ent)
        field-shapes (some->> (:type/field-shapes ent)
                              (map (fn [[fname pr-shape]]
                                     [fname (read-shape pr-shape)]))
                              (vec))]
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
        shape   (read-shape (:affordance/shape ent))
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

(defn- canvas-db
  "Live canvas-db. Carved out so tests can stub via `with-redefs`."
  []
  (canvas-source/build-canvas-db))

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
;; dispatch is *not* part of the controller's contract. See
;; `doc/plans/2026-05-28-closure-controller-design.md` (Sprint 1) for the
;; full design.
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
   defensively — orphans don't get an instruction rendered)."
  [finding scenario-id]
  (when-let [offender (first (:offenders finding))]
    (let [stable-id   (:stable-id offender)
          code-path   (:expected-code-path offender)
          canvas-kind (:canvas-kind offender)
          rendered    (instruct finding scenario-id)]
      {:stable-id   stable-id
       :scenario    scenario-id
       :check       (:check finding)
       :rendered    (:rendered rendered)
       :code-spec   (:code-spec rendered)
       :context     (cond-> {}
                      code-path   (assoc :expected-code-path code-path)
                      canvas-kind (assoc :canvas-kind canvas-kind))
       :batch-key   (or code-path :no-path)})))

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
                                                          (placeholder;
                                                          architect-side
                                                          retry lands in
                                                          Sprint 4)

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
                    per plan entry."
        :agent/example "(close-drift-plan :module-coord \"distributed.cluster\")
                        (close-drift-plan :stable-id \"distributed.log/AppendEntriesRequested\")"}
  close-drift-plan
  [& {:keys [module-coord check stable-id limit max-attempts]
      :or {limit 25 max-attempts 2}
      :as opts}]
  (let [known #{:module-coord :check :stable-id :limit :max-attempts}
        unknown (seq (remove known (keys opts)))]
    (when unknown
      (throw (ex-info (str "unknown close-drift-plan filter: " (first unknown))
                      {:type :unknown-filter :filter (first unknown)})))
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
                        (update acc :plan conj entry)
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
       :max-attempts max-attempts})))

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

(defn- classify-outcome
  "Decide a per-finding outcome from the plan entry + pre/post drift +
   report. Returns
     {:outcome :closed | :failed | :no-report
      :requires-retry? bool
      :escalation-reason <kw or nil>}.

   Sprint 4 lands the full six-trigger classification (Task 12). MVP
   handles the three triggers the architect's single-pass loop can
   actually surface."
  [plan-entry report post-findings attempts max-attempts]
  (let [present? (finding-still-present? (:stable-id plan-entry) post-findings)]
    (cond
      (nil? report)
      {:outcome :no-report
       :requires-retry? false
       :escalation-reason :no-report}

      (not present?)
      {:outcome :closed
       :requires-retry? false
       :escalation-reason nil}

      ;; Still present after dispatch.
      :else
      {:outcome :failed
       :requires-retry? (< attempts max-attempts)
       :escalation-reason (when-not (< attempts max-attempts)
                            :attempts-exhausted)})))

(defn- render-verify-markdown
  "Render the verify report as markdown — terse, factual, decision-ready.
   Mirrors Phase 7's `(instruct …)` body discipline (structured headings,
   no marketing)."
  [{:keys [scope counts per-finding]}]
  (let [{:keys [findings-total findings-closed findings-failed
                findings-escalated]} counts
        scope-line (cond
                     (:stable-id scope) (str "stable-id " (:stable-id scope))
                     (:module-coord scope) (str "module-coord " (pr-str (:module-coord scope))
                                                (when (:check scope)
                                                  (str ", check " (pr-str (:check scope)))))
                     (:check scope) (str "check " (pr-str (:check scope)))
                     :else "all canvas drift")
        lines (atom [])
        push! (fn [& xs] (swap! lines conj (apply str xs)))]
    (push! "# Close-drift report")
    (push! "")
    (push! "**Scope:** " scope-line)
    (push! "")
    (push! "**Summary:** " findings-closed " of " findings-total
           " closed; " findings-failed " failed; "
           findings-escalated " escalated.")
    (push! "")
    (when (seq per-finding)
      (push! "## Per-finding outcomes")
      (push! "")
      (doseq [pf per-finding]
        (push! "- `" (:stable-id pf) "` — **" (name (:outcome pf)) "**"
               (when (and (:attempts pf) (pos? (:attempts pf)))
                 (str " (attempts: " (:attempts pf) ")"))
               (when (:escalation-reason pf)
                 (str " · escalation: `" (name (:escalation-reason pf)) "`")))))
    (push! "")
    (let [escalated (filter #(= :failed (:outcome %)) per-finding)]
      (when (seq escalated)
        (push! "## Escalations")
        (push! "")
        (doseq [pf escalated]
          (push! "### `" (:stable-id pf) "`")
          (push! "")
          (when-let [r (:escalation-reason pf)]
            (push! "**Reason:** `" (name r) "`")
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
                       :escalation-reason   <kw or nil>
                       :report-excerpt      <truncated subagent narrative>
                       :pre-drift-snapshot  <compact finding snapshot>
                       :post-drift-snapshot <compact finding snapshot or nil>}

                    Sprint 4 lands the full six-trigger classification
                    (Task 12). MVP recognises `:attempts-exhausted`,
                    `:scenario-not-found` (via the plan's :unhandled),
                    and `:no-report`."
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
                                (let [rs       (get report-by-id (:stable-id pe))
                                      ;; Latest attempt for the finding.
                                      latest   (last (sort-by (fnil :attempt 0) (or rs [])))
                                      attempts (count rs)
                                      cls      (classify-outcome pe latest post-findings
                                                                  attempts max-attempts)
                                      post-finding (some (fn [f]
                                                           (when (= (:stable-id pe)
                                                                    (-> f :offenders first :stable-id))
                                                             f))
                                                         post-findings)]
                                  (cond-> {:stable-id          (:stable-id pe)
                                           :outcome            (:outcome cls)
                                           :attempts           attempts
                                           :requires-retry?    (:requires-retry? cls)
                                           :escalation-reason  (:escalation-reason cls)
                                           :report-excerpt     (some-> latest :report (excerpt 280))
                                           :pre-drift-snapshot (get pre-snapshots (:stable-id pe))
                                           :post-drift-snapshot (snapshot-finding post-finding)})))
                              plan-entries)
          ;; Unhandled findings (no scenario) count as escalated.
          unhandled-entries (mapv (fn [u]
                                    {:stable-id          (:stable-id u)
                                     :outcome            :failed
                                     :attempts           0
                                     :requires-retry?    false
                                     :escalation-reason  (:reason u)
                                     :report-excerpt     (:detail u)
                                     :pre-drift-snapshot nil
                                     :post-drift-snapshot nil})
                                  (or unhandled []))
          all-pf        (into per-finding unhandled-entries)
          closed-count  (count (filter #(= :closed   (:outcome %)) all-pf))
          failed-count  (count (filter #(= :failed   (:outcome %)) all-pf))
          no-report-cnt (count (filter #(= :no-report (:outcome %)) all-pf))
          escalated-cnt (count (filter :escalation-reason all-pf))
          counts        {:findings-total      (count all-pf)
                         :findings-closed     closed-count
                         :findings-failed     failed-count
                         :findings-no-report  no-report-cnt
                         :findings-escalated  escalated-cnt}
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
