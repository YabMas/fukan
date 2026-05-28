(ns fukan.canvas.inspect.drift-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.canvas.inspect.drift :as drift]
            [fukan.model.artifact :as a]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.relations :as r]))

;; ---------------------------------------------------------------------------
;; Test fixtures
;; ---------------------------------------------------------------------------

(defn- model-with-operation
  "Build a synthetic model carrying one Operation primitive plus one
   projects edge to a Code.Function artifact. `:validity` is parameterised
   so the same fixture exercises both the clean and the absent branches.

   The primitive's `:canvas-role` is set per Sprint 2 Task 4 so drift can
   infer the canvas-kind."
  [validity & {:keys [canvas-role] :or {canvas-role :canvas/operation}}]
  (let [prim-id  "demo/start_server"
        artifact (a/make-code-function "clojure" "demo/start-server" nil true)
        aid      (a/artifact-identity artifact)
        prim     (assoc (p/make-operation
                          {:id prim-id, :label "start_server", :parameters []})
                        :canvas-role canvas-role)
        edge     (-> (r/make-edge :relation/projects
                                  (r/primitive-ref prim-id)
                                  (r/artifact-ref aid)
                                  {:projection-kind :projection-kind/operation})
                     (assoc :validity validity))]
    (-> (build/empty-model)
        (build/add-primitive prim)
        (assoc-in [:artifacts aid] artifact)
        (build/add-edge edge))))

(defn- model-with-invariant
  "Synthetic model with one canvas invariant projection edge."
  [validity]
  (let [prim-id  "demo/AlwaysHolds"
        artifact (a/make-code-function "clojure" "demo/always-holds?" nil true)
        aid      (a/artifact-identity artifact)
        prim     (assoc (p/make-operation
                          {:id prim-id, :label "AlwaysHolds" :parameters []})
                        :canvas-role :canvas/invariant)
        edge     (-> (r/make-edge :relation/projects
                                  (r/primitive-ref prim-id)
                                  (r/artifact-ref aid)
                                  {:projection-kind :projection-kind/invariant})
                     (assoc :validity validity))]
    (-> (build/empty-model)
        (build/add-primitive prim)
        (assoc-in [:artifacts aid] artifact)
        (build/add-edge edge))))

;; ---------------------------------------------------------------------------
;; Test A — clean pair → []
;; ---------------------------------------------------------------------------

(deftest clean-model-returns-no-findings
  (testing "a model whose every projects edge is :valid produces []"
    (let [m (model-with-operation :valid)]
      (is (= [] (drift/check m))
          "no :absent edges → no drift findings"))))

(deftest empty-model-returns-no-findings
  (testing "the empty model has no edges, hence no drift"
    (is (= [] (drift/check (build/empty-model))))))

;; ---------------------------------------------------------------------------
;; Test B — one missing fn → 1 finding
;; ---------------------------------------------------------------------------

(deftest absent-function-edge-produces-one-finding
  (testing ":validity :absent on a function projection → exactly one finding"
    (let [m         (model-with-operation :absent)
          findings  (drift/check m)]
      (is (= 1 (count findings)))
      (let [f (first findings)]
        (is (= :inspect.drift/missing-implementation (:check f)))
        (is (= :warning (:severity f)))
        (is (string? (:message f)))
        (is (= 1 (count (:offenders f))))
        (let [off (first (:offenders f))]
          (is (= "demo/start_server" (:stable-id off))
              "offender names the canvas side via the primitive's stable-id")
          (is (= :function (:canvas-kind off))
              "canvas-role :canvas/operation → :canvas-kind :function")
          (is (= "start-server" (:expected-symbol off))
              "expected symbol is the canonical local name")
          (is (= "src/demo.clj" (:expected-code-path off))
              "expected-code-path mirrors the conventional source layout (ns 'demo' → src/demo.clj)"))
        (is (= "demo/start_server" (-> f :detail :canvas-side-id))
            "detail bidirectionally names both sides — canvas-side-id")
        (is (= "demo/start-server" (-> f :detail :code-side-expected))
            "detail bidirectionally names both sides — code-side-expected")))))

;; ---------------------------------------------------------------------------
;; Test C — one missing invariant → finding with :canvas-kind :invariant
;; ---------------------------------------------------------------------------

(defn- model-with-property-test-invariant
  "Phase 8 Sprint 5 fixture — synthetic model with one canvas invariant
   projection edge whose `:projection-kind` is
   `:projection-kind/property-test` (test-side artifact under `test/`).
   The artifact's qualified-name carries the `-test` ns suffix per the
   address registry's property-test convention."
  [validity]
  (let [prim-id  "demo/MajorityHolds"
        artifact (a/make-code-function "clojure" "demo-test/majority-holds-property" nil nil)
        aid      (a/artifact-identity artifact)
        prim     (assoc (p/make-operation
                          {:id prim-id, :label "MajorityHolds" :parameters []})
                        :canvas-role :canvas/invariant)
        edge     (-> (r/make-edge :relation/projects
                                  (r/primitive-ref prim-id)
                                  (r/artifact-ref aid)
                                  {:projection-kind :projection-kind/property-test})
                     (assoc :validity validity))]
    (-> (build/empty-model)
        (build/add-primitive prim)
        (assoc-in [:artifacts aid] artifact)
        (build/add-edge edge))))

(deftest absent-invariant-edge-tagged-as-invariant
  (testing "umbrella check tags an absent invariant projection with :canvas-kind :invariant"
    (let [m        (model-with-invariant :absent)
          findings (drift/check m)]
      (is (= 1 (count findings)))
      (let [off (first (:offenders (first findings)))]
        (is (= :invariant (:canvas-kind off))
            "canvas-role :canvas/invariant → :canvas-kind :invariant")))))

(deftest absent-property-test-edge-resolves-to-test-side-path
  ;; Phase 8 Sprint 5 — a canvas invariant whose projection-kind is
  ;; :projection-kind/property-test resolves its expected-code-path
  ;; under test/ rather than src/. The offender additionally carries
  ;; the projection-kind so Layer B can branch on it for kind-aware
  ;; neighbor rendering.
  (testing "an absent :projection-kind/property-test edge points at test/"
    (let [m        (model-with-property-test-invariant :absent)
          findings (drift/check m)]
      (is (= 1 (count findings)))
      (let [f   (first findings)
            off (first (:offenders f))]
        (is (= :inspect.drift/missing-implementation (:check f)))
        (is (= "test/demo_test.clj" (:expected-code-path off))
            "expected-code-path is rooted at test/, not src/")
        (is (= "majority-holds-property" (:expected-symbol off))
            "expected-symbol is the defspec name with the -property suffix")
        (is (= :projection-kind/property-test (:projection-kind off))
            "offender carries the projection-kind so drift-close can pick the property-test rendering")
        (is (= :invariant (:canvas-kind off))
            "canvas-kind stays :invariant — the test-side rendering doesn't change the semantic kind")))))

(deftest valid-property-test-edge-emits-no-finding
  (testing ":validity :valid → no drift finding even when projection-kind is property-test"
    (let [m (model-with-property-test-invariant :valid)]
      (is (= [] (drift/check m))))))

;; ---------------------------------------------------------------------------
;; Umbrella behaviour — mixed kinds in one model
;; ---------------------------------------------------------------------------

(deftest mixed-absent-kinds-produce-grouped-findings
  (testing "operation + invariant both absent → 2 findings, distinguishable by :canvas-kind"
    (let [op-prim-id  "demo/start_server"
          inv-prim-id "demo/AlwaysHolds"
          op-art      (a/make-code-function "clojure" "demo/start-server" nil true)
          inv-art     (a/make-code-function "clojure" "demo/always-holds?" nil true)
          op-aid      (a/artifact-identity op-art)
          inv-aid     (a/artifact-identity inv-art)
          op-prim     (assoc (p/make-operation
                               {:id op-prim-id :label "start_server" :parameters []})
                             :canvas-role :canvas/operation)
          inv-prim    (assoc (p/make-operation
                               {:id inv-prim-id :label "AlwaysHolds" :parameters []})
                             :canvas-role :canvas/invariant)
          op-edge     (-> (r/make-edge :relation/projects
                                       (r/primitive-ref op-prim-id)
                                       (r/artifact-ref op-aid)
                                       {:projection-kind :projection-kind/operation})
                          (assoc :validity :absent))
          inv-edge    (-> (r/make-edge :relation/projects
                                       (r/primitive-ref inv-prim-id)
                                       (r/artifact-ref inv-aid)
                                       {:projection-kind :projection-kind/invariant})
                          (assoc :validity :absent))
          m           (-> (build/empty-model)
                          (build/add-primitive op-prim)
                          (build/add-primitive inv-prim)
                          (assoc-in [:artifacts op-aid] op-art)
                          (assoc-in [:artifacts inv-aid] inv-art)
                          (build/add-edge op-edge)
                          (build/add-edge inv-edge))
          findings    (drift/check m)
          by-kind     (->> findings
                           (map #(-> % :offenders first :canvas-kind))
                           (into #{}))]
      (is (= 2 (count findings)))
      (is (= #{:function :invariant} by-kind)
          "the umbrella check produces one finding per kind, distinguishable via :canvas-kind"))))

;; ---------------------------------------------------------------------------
;; Test D — agent api integration
;; ---------------------------------------------------------------------------

(deftest agent-api-exposes-canvas-drift-under-trust
  (testing "the agent api exposes the trust-tier drift helper with the right metadata"
    (require 'fukan.agent.api)
    (let [v (ns-resolve (find-ns 'fukan.agent.api) 'canvas-drift)]
      (is (some? v)
          "fukan.agent.api/canvas-drift must be defined")
      (let [m (meta v)]
        (is (= :trust (:agent/layer m))
            "canvas-drift must be tagged :agent/layer :trust")
        (is (string? (:agent/doc m))
            "canvas-drift must carry :agent/doc")
        (is (true? (:export m))
            "canvas-drift must carry ^:export metadata")))))

(deftest help-lists-canvas-drift-under-trust
  (testing "(help) must list canvas-drift under the :trust layer of fukan.agent.api"
    (require 'fukan.agent.system)
    (let [help-fn (ns-resolve (find-ns 'fukan.agent.system) 'help)
          surface (help-fn)
          trust   (get-in surface ['fukan.agent.api :trust])
          names   (set (map :name trust))]
      (is (contains? names 'canvas-drift)
          (str "(help) must list canvas-drift under :trust; saw: " (pr-str names))))))

;; ---------------------------------------------------------------------------
;; Shape-drift tests (Task 7 — Sub-task D)
;;
;; Test fixtures build a synthetic model + canvas-db pair carrying matching
;; Type primitives (canvas side) and Code.DataStructure artifacts (code side)
;; with controllable field shapes, so each drift sub-shape — only-in-canvas,
;; only-in-code, type-mismatch — is exercised in isolation plus together.

(defn- model-with-record-projection
  "Synthetic model with one canvas Type primitive + matching
   Code.DataStructure artifact at qualified-name `qname`. The artifact
   carries `code-fields`; the projects edge is `:valid` because we want to
   test SHAPE drift, not missing-implementation drift."
  [prim-id qname code-fields]
  (let [base-art (a/make-code-data-structure "clojure" qname nil)
        artifact (cond-> base-art
                   (some? code-fields) (assoc-in [:sub :fields] code-fields))
        aid      (a/artifact-identity artifact)
        prim     (assoc (p/make-container
                          {:id prim-id :label (peek (str/split qname #"/"))})
                        :canvas-role :canvas/type)
        edge     (-> (r/make-edge :relation/projects
                                  (r/primitive-ref prim-id)
                                  (r/artifact-ref aid)
                                  {:projection-kind :projection-kind/schema})
                     (assoc :validity :valid))]
    (-> (build/empty-model)
        (build/add-primitive prim)
        (assoc-in [:artifacts aid] artifact)
        (build/add-edge edge))))

(defn- canvas-db-with-type-fields
  "Build a tiny canvas-db carrying one Module + one Type entity (the Type a
   child of the Module). The Type carries `:type/fields` as a set of
   `[field-name-kw type-name-kw]` tuples. `stable-id` is parsed to derive
   the module-name segment (everything before `/type/`); the entity-name
   comes from the segment after `/type/`. The resulting db lets drift's
   shape-drift check derive the same stable-id by joining Type → Module."
  [stable-id _entity-name-ignored field-tuples]
  (require 'fukan.canvas.core.substrate.store)
  (require 'datascript.core)
  (let [create  (resolve 'fukan.canvas.core.substrate.store/create)
        db-with (resolve 'datascript.core/db-with)
        [mod-name type-name] (str/split stable-id #"/type/" 2)
        mod-uuid  (str "mod-" mod-name)
        type-uuid (str "type-" type-name)]
    (db-with (create)
             [{:db/id      -1
               :entity/id  mod-uuid
               :entity/type :Module
               :entity/name mod-name}
              {:db/id      -2
               :entity/id  type-uuid
               :entity/type :Type
               :entity/name type-name
               :type/fields (set field-tuples)}
              [:db/add -1 :module/child -2]])))

(deftest shape-drift-clean-record-no-finding
  (testing "canvas record and code record with identical fields (post-alias) → no shape-drift finding"
    (let [prim-id "infra.server/type/ServerOpts"
          m       (model-with-record-projection
                    prim-id
                    "infra.server/ServerOpts"
                    [[:port :int]])
          db      (canvas-db-with-type-fields prim-id "ServerOpts" [[:port :Integer]])
          findings (drift/check-shape-drift m db)]
      (is (= [] findings)
          "Canvas :Integer ↔ Code :int normalises via the alias table; clean"))))

(deftest shape-drift-only-in-code-produces-finding
  (testing "canvas has {:id :String}, code has {:id :string :email :string} → finding with :only-in-code"
    (let [prim-id "demo.users/type/User"
          m       (model-with-record-projection
                    prim-id
                    "demo.users/User"
                    [[:id :string] [:email :string]])
          db      (canvas-db-with-type-fields prim-id "User" [[:id :String]])
          findings (drift/check-shape-drift m db)]
      (is (= 1 (count findings)))
      (let [f (first findings)]
        (is (= :inspect.drift/shape-drift-on-record (:check f)))
        (is (= :warning (:severity f)))
        (let [off (first (:offenders f))]
          (is (= "demo.users/type/User" (:stable-id off)))
          (is (= {:email :string}
                 (get-in off [:delta :only-in-code]))
              "the email field is only in code")
          (is (empty? (get-in off [:delta :only-in-canvas])))
          (is (empty? (get-in off [:delta :type-mismatch]))))))))

(deftest shape-drift-only-in-canvas-produces-finding
  (testing "canvas has more fields than code → finding with :only-in-canvas"
    (let [prim-id "demo.users/type/User"
          m       (model-with-record-projection
                    prim-id
                    "demo.users/User"
                    [[:id :string]])
          db      (canvas-db-with-type-fields prim-id "User"
                                              [[:id :String] [:email :String]])
          findings (drift/check-shape-drift m db)]
      (is (= 1 (count findings)))
      (let [off (-> findings first :offenders first)]
        (is (= {:email :String}
               (get-in off [:delta :only-in-canvas])))
        (is (empty? (get-in off [:delta :only-in-code])))))))

(deftest shape-drift-type-mismatch-produces-finding
  (testing "canvas {:port :String}, code {:port :int} → type-mismatch finding"
    (let [prim-id "infra.server/type/ServerOpts"
          m       (model-with-record-projection
                    prim-id
                    "infra.server/ServerOpts"
                    [[:port :int]])
          db      (canvas-db-with-type-fields prim-id "ServerOpts"
                                              [[:port :String]])
          findings (drift/check-shape-drift m db)]
      (is (= 1 (count findings)))
      (let [off (-> findings first :offenders first)]
        (is (= {:port {:canvas :String :code :int}}
               (get-in off [:delta :type-mismatch]))
            ":String and :int normalise to different canonical forms — mismatch")))))

(deftest shape-drift-skipped-when-artifact-has-no-fields
  (testing "an artifact without :fields (e.g. defrecord wasn't read) is silently skipped"
    (let [prim-id "demo.users/type/User"
          m       (model-with-record-projection
                    prim-id
                    "demo.users/User"
                    nil)  ; no :fields slot
          db      (canvas-db-with-type-fields prim-id "User" [[:id :String]])]
      (is (= [] (drift/check-shape-drift m db))
          "no comparison possible without code-side fields"))))

;; ---------------------------------------------------------------------------
;; Compound-shape normalisation (Phase 7 Sprint 2 Task 3)
;;
;; Each test passes a richer field shape on one or both sides — full Malli
;; vectors on the code side, full canvas parsed-shape maps on the canvas
;; side. The fixtures bypass the substrate-level flattening so we exercise
;; the comparator's recursion directly. A separate fixture
;; `canvas-db-with-type-field-shapes` writes parsed shapes into the new
;; `:type/field-shapes` substrate attr, mirroring the real ingestion path.

(defn- canvas-db-with-type-field-shapes
  "Build a tiny canvas-db carrying one Module + one Type entity with
   parsed compound shapes per field. `shape-tuples` is a sequence of
   `[field-name-kw parsed-shape-edn-map]` pairs. The fixture pr-strs the
   shape and writes it under `:type/field-shapes` (cardinality-many) so
   the drift check's substrate query path picks it up."
  [stable-id field-shapes]
  (require 'fukan.canvas.core.substrate.store)
  (require 'datascript.core)
  (let [create  (resolve 'fukan.canvas.core.substrate.store/create)
        db-with (resolve 'datascript.core/db-with)
        [mod-name type-name] (str/split stable-id #"/type/" 2)
        mod-uuid  (str "mod-" mod-name)
        type-uuid (str "type-" type-name)]
    (db-with (create)
             [{:db/id      -1
               :entity/id  mod-uuid
               :entity/type :Module
               :entity/name mod-name}
              {:db/id      -2
               :entity/id  type-uuid
               :entity/type :Type
               :entity/name type-name
               :type/field-shapes (set (mapv (fn [[fname shape]]
                                               [fname (pr-str shape)])
                                             field-shapes))}
              [:db/add -1 :module/child -2]])))

(deftest shape-drift-set-of-equivalent-to-malli-set
  (testing "canvas (set-of :NodeId) vs code [:set :NodeId] → no finding"
    (let [prim-id "demo/type/Graph"
          m       (model-with-record-projection
                    prim-id "demo/Graph"
                    [[:nodes [:set :NodeId]]])
          db      (canvas-db-with-type-field-shapes
                    prim-id
                    [[:nodes {:kind :set :elem {:kind :atomic :name :NodeId}}]])]
      (is (= [] (drift/check-shape-drift m db))
          "outer kind :set matches; inner :NodeId matches"))))

(deftest shape-drift-list-of-equivalent-to-malli-sequential
  (testing "canvas (list-of :Item) vs code [:sequential :Item] → no finding"
    (let [prim-id "demo/type/Order"
          m       (model-with-record-projection
                    prim-id "demo/Order"
                    [[:items [:sequential :Item]]])
          db      (canvas-db-with-type-field-shapes
                    prim-id
                    [[:items {:kind :list :elem {:kind :atomic :name :Item}}]])]
      (is (= [] (drift/check-shape-drift m db))
          "Malli :sequential canonicalises to canvas :list-of"))))

(deftest shape-drift-list-of-equivalent-to-malli-vector
  (testing "canvas (list-of :Item) vs code [:vector :Item] → no finding"
    ;; Interpretive call: the canvas vocabulary has one ordered-collection
    ;; compound (`list-of`); Malli has three (`:sequential`, `:vector`,
    ;; `:list`). Treating them as equivalent is the smallest call that
    ;; collapses spurious noise — fukan-canvas authors don't pick between
    ;; them when writing `(list-of …)`.
    (let [prim-id "demo/type/Order"
          m       (model-with-record-projection
                    prim-id "demo/Order"
                    [[:items [:vector :Item]]])
          db      (canvas-db-with-type-field-shapes
                    prim-id
                    [[:items {:kind :list :elem {:kind :atomic :name :Item}}]])]
      (is (= [] (drift/check-shape-drift m db))
          "Malli :vector also canonicalises to canvas :list-of"))))

(deftest shape-drift-map-of-equivalent-with-alias-on-leaves
  (testing "canvas (map-of :String :Foo) vs code [:map-of :string :Foo] → no finding"
    (let [prim-id "demo/type/Idx"
          m       (model-with-record-projection
                    prim-id "demo/Idx"
                    [[:by-name [:map-of :string :Foo]]])
          db      (canvas-db-with-type-field-shapes
                    prim-id
                    [[:by-name {:kind :map
                                :key {:kind :atomic :name :String}
                                :val {:kind :atomic :name :Foo}}]])]
      (is (= [] (drift/check-shape-drift m db))
          "leaf alias collapses :String ↔ :string under the same :map outer"))))

(deftest shape-drift-optional-equivalent-to-malli-maybe
  (testing "canvas (optional :Integer) vs code [:maybe :int] → no finding"
    (let [prim-id "demo/type/Opt"
          m       (model-with-record-projection
                    prim-id "demo/Opt"
                    [[:age [:maybe :int]]])
          db      (canvas-db-with-type-field-shapes
                    prim-id
                    [[:age {:kind :optional :inner {:kind :atomic :name :Integer}}]])]
      (is (= [] (drift/check-shape-drift m db))
          "Malli :maybe canonicalises to canvas :optional, alias on inner leaf"))))

(deftest shape-drift-set-vs-vector-genuine-mismatch
  (testing "canvas (set-of :NodeId) vs code [:vector :NodeId] → ONE finding (set vs vector)"
    (let [prim-id "demo/type/Graph"
          m       (model-with-record-projection
                    prim-id "demo/Graph"
                    [[:nodes [:vector :NodeId]]])
          db      (canvas-db-with-type-field-shapes
                    prim-id
                    [[:nodes {:kind :set :elem {:kind :atomic :name :NodeId}}]])
          findings (drift/check-shape-drift m db)]
      (is (= 1 (count findings)))
      (let [off (-> findings first :offenders first)]
        (is (contains? (get-in off [:delta :type-mismatch]) :nodes)
            "outer kind :set vs :list (canonical for :vector) is real drift")))))

(deftest shape-drift-optional-vs-required-is-real-drift
  (testing "canvas (optional :Integer) vs code :int → ONE finding"
    ;; Interpretive call: a canvas-side compound (optional/maybe) facing a
    ;; bare scalar on the code side is a real divergence — the field can be
    ;; absent on one side but not the other. We surface it rather than
    ;; silently collapsing.
    (let [prim-id "demo/type/Opt"
          m       (model-with-record-projection
                    prim-id "demo/Opt"
                    [[:age :int]])
          db      (canvas-db-with-type-field-shapes
                    prim-id
                    [[:age {:kind :optional :inner {:kind :atomic :name :Integer}}]])
          findings (drift/check-shape-drift m db)]
      (is (= 1 (count findings))
          "optional vs required is genuine drift"))))

(deftest shape-drift-head-only-code-matches-canvas-compound
  (testing "head-only code keyword (today's analyzer flatten) matches canvas same-outer compound"
    ;; This mirrors what the Clojure target analyzer emits today: for a
    ;; field declared `[:set :NodeId]` it extracts type `:set` (head
    ;; only). The comparator must treat the head-only code-side as a
    ;; wildcard *inside* the same outer compound kind, otherwise the
    ;; cytoscape-style noise resurfaces.
    (let [prim-id "demo/type/Graph"
          m       (model-with-record-projection
                    prim-id "demo/Graph"
                    [[:nodes :set]])
          db      (canvas-db-with-type-field-shapes
                    prim-id
                    [[:nodes {:kind :set :elem {:kind :atomic :name :NodeId}}]])]
      (is (= [] (drift/check-shape-drift m db))
          "code `:set` head canonicalises to {:set :unknown}; wildcard matches inner :NodeId"))))

(deftest shape-drift-head-only-set-vs-canvas-list-still-mismatches
  (testing "head-only code :sequential vs canvas (set-of …) → mismatch (outer kinds differ)"
    (let [prim-id "demo/type/Graph"
          m       (model-with-record-projection
                    prim-id "demo/Graph"
                    [[:nodes :sequential]])
          db      (canvas-db-with-type-field-shapes
                    prim-id
                    [[:nodes {:kind :set :elem {:kind :atomic :name :NodeId}}]])
          findings (drift/check-shape-drift m db)]
      (is (= 1 (count findings))
          "outer kind disagreement (:list canonical vs :set) is real even with unknown inner"))))

;; ---------------------------------------------------------------------------
;; Scoped check via :module-coord (Phase 7 Sprint 2 Task 5)
;;
;; Filtering happens post-walk on the structured findings. Prefix-matching is
;; dot-segment-aware: `:module-coord "mod-a"` matches `mod-a` and `mod-a.sub`
;; but NOT `mod-ab`. Compare against the module-coord extracted from each
;; finding's offender :stable-id (the leading segment before `/`).

(defn- model-with-named-operation
  "Synthetic single-operation model with full control over the canvas-side
   stable-id (i.e. the module coord), used to drive scope-filter tests
   without depending on global canvas-source state."
  [prim-id qname]
  (let [artifact (a/make-code-function "clojure" qname nil true)
        aid      (a/artifact-identity artifact)
        prim     (assoc (p/make-operation
                          {:id prim-id :label prim-id :parameters []})
                        :canvas-role :canvas/operation)
        edge     (-> (r/make-edge :relation/projects
                                  (r/primitive-ref prim-id)
                                  (r/artifact-ref aid)
                                  {:projection-kind :projection-kind/operation})
                     (assoc :validity :absent))]
    (-> (build/empty-model)
        (build/add-primitive prim)
        (assoc-in [:artifacts aid] artifact)
        (build/add-edge edge))))

(defn- merge-models
  "Stitch two synthetic models into one — concat their primitives, artifacts,
   and edges. Suitable for scope-filter fixtures that need findings spanning
   multiple modules."
  [& models]
  (reduce (fn [acc m]
            (-> acc
                (update :primitives merge (:primitives m))
                (update :artifacts  merge (:artifacts m))
                (update :edges      into (:edges m))))
          (build/empty-model)
          models))

(deftest scope-filter-narrows-to-single-module
  (testing "(check m {:module-coord \"mod-a\"}) returns only findings from mod-a"
    (let [m (merge-models
              (model-with-named-operation "mod-a/foo" "mod-a/foo")
              (model-with-named-operation "mod-a/bar" "mod-a/bar")
              (model-with-named-operation "mod-b/baz" "mod-b/baz"))]
      (is (= 3 (count (drift/check m)))
          "unfiltered → all three findings")
      (is (= 2 (count (drift/check m {:module-coord "mod-a"})))
          "scope :module-coord mod-a → only mod-a/* findings")
      (is (= 1 (count (drift/check m {:module-coord "mod-b"}))))
      (is (= 0 (count (drift/check m {:module-coord "nonexistent"})))
          "no matching module → empty"))))

(deftest scope-filter-respects-dot-segment-boundary
  (testing "prefix-match is dot-segment-aware: mod-a does NOT match mod-ab"
    (let [m (merge-models
              (model-with-named-operation "mod-a/foo" "mod-a/foo")
              (model-with-named-operation "mod-ab/foo" "mod-ab/foo"))]
      (is (= 2 (count (drift/check m))))
      (is (= 1 (count (drift/check m {:module-coord "mod-a"})))
          "string-prefix would erroneously match mod-ab; segment-aware doesn't")
      (is (= 1 (count (drift/check m {:module-coord "mod-ab"})))
          "exact module-coord still matches"))))

(deftest scope-filter-includes-deeper-segments
  (testing "submodule findings are included by a parent-module scope"
    (let [m (merge-models
              (model-with-named-operation "mod-a/foo" "mod-a/foo")
              (model-with-named-operation "mod-a.sub/foo" "mod-a/sub/foo")
              (model-with-named-operation "mod-a.sub.deep/foo" "mod-a/sub/deep/foo"))]
      (is (= 3 (count (drift/check m {:module-coord "mod-a"})))
          "mod-a scope includes mod-a, mod-a.sub, mod-a.sub.deep")
      (is (= 2 (count (drift/check m {:module-coord "mod-a.sub"})))
          "mod-a.sub scope includes mod-a.sub and mod-a.sub.deep, not mod-a")
      (is (= 1 (count (drift/check m {:module-coord "mod-a.sub.deep"})))
          "fully-qualified scope matches exactly one"))))

(deftest scope-filter-applies-to-shape-drift-findings-too
  (testing "shape-drift findings filter on stable-id module-coord just like missing-impl"
    (let [prim-a "demo.a/type/User"
          prim-b "demo.b/type/Order"
          m  (merge-models
               (model-with-record-projection prim-a "demo.a/User" [[:id :string]])
               (model-with-record-projection prim-b "demo.b/Order" [[:id :string]]))
          db (-> (canvas-db-with-type-fields prim-a "User"
                                             [[:id :String] [:email :String]]))
          ;; The fixture only supports a single Type per db; for the
          ;; shape-drift portion we only assert that the filter narrows
          ;; when a single-module db is supplied.
          findings-a (drift/check m db {:module-coord "demo.a"})
          findings-other (drift/check m db {:module-coord "demo.b"})]
      (is (every? #(str/starts-with?
                     (-> % :offenders first :stable-id) "demo.a")
                  findings-a)
          "every retained finding lies under the scoped module")
      (is (every? #(str/starts-with?
                     (-> % :offenders first :stable-id) "demo.b")
                  findings-other)))))

(deftest scope-filter-noop-on-nil
  (testing "(check m nil) and (check m {}) are equivalent to no-arg form"
    (let [m (model-with-named-operation "mod-x/foo" "mod-x/foo")]
      (is (= (drift/check m) (drift/check m {})))
      (is (= (drift/check m) (drift/check m {:module-coord nil}))))))

(deftest agent-api-canvas-drift-accepts-module-coord
  (testing "(canvas-drift :module-coord …) is callable via the agent api"
    (require 'fukan.agent.api)
    (let [v (ns-resolve (find-ns 'fukan.agent.api) 'canvas-drift)
          m (meta v)]
      (is (some? v))
      ;; arglist must accept a varargs kwarg form
      (is (some #(some #{'&} %) (:arglists m))
          (str ":arglists must include a varargs form; saw " (pr-str (:arglists m)))))))

(deftest check-aggregates-missing-impl-and-shape-drift
  (testing "drift/check returns both missing-implementation and shape-drift findings"
    (let [;; A clean missing-impl pair
          op-prim-id "demo/start_server"
          op-art     (a/make-code-function "clojure" "demo/start-server" nil true)
          op-aid     (a/artifact-identity op-art)
          op-prim    (assoc (p/make-operation
                              {:id op-prim-id :label "start_server" :parameters []})
                            :canvas-role :canvas/operation)
          op-edge    (-> (r/make-edge :relation/projects
                                      (r/primitive-ref op-prim-id)
                                      (r/artifact-ref op-aid)
                                      {:projection-kind :projection-kind/operation})
                         (assoc :validity :absent))

          ;; A shape-drift pair (canvas has extra field)
          type-prim-id "demo.users/type/User"
          type-art     (-> (a/make-code-data-structure "clojure" "demo.users/User" nil)
                           (assoc-in [:sub :fields] [[:id :string]]))
          type-aid     (a/artifact-identity type-art)
          type-prim    (assoc (p/make-container
                                {:id type-prim-id :label "User"})
                              :canvas-role :canvas/type)
          type-edge    (-> (r/make-edge :relation/projects
                                        (r/primitive-ref type-prim-id)
                                        (r/artifact-ref type-aid)
                                        {:projection-kind :projection-kind/schema})
                           (assoc :validity :valid))

          m (-> (build/empty-model)
                (build/add-primitive op-prim)
                (build/add-primitive type-prim)
                (assoc-in [:artifacts op-aid] op-art)
                (assoc-in [:artifacts type-aid] type-art)
                (build/add-edge op-edge)
                (build/add-edge type-edge))
          db (canvas-db-with-type-fields type-prim-id "User"
                                         [[:id :String] [:email :String]])
          findings (drift/check m db)
          checks   (set (map :check findings))]
      (is (= 2 (count findings)))
      (is (= #{:inspect.drift/missing-implementation
               :inspect.drift/shape-drift-on-record}
             checks)))))
