(ns fukan.canvas.inspect.drift-test
  (:require [clojure.test :refer [deftest is testing]]
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

(deftest absent-invariant-edge-tagged-as-invariant
  (testing "umbrella check tags an absent invariant projection with :canvas-kind :invariant"
    (let [m        (model-with-invariant :absent)
          findings (drift/check m)]
      (is (= 1 (count findings)))
      (let [off (first (:offenders (first findings)))]
        (is (= :invariant (:canvas-kind off))
            "canvas-role :canvas/invariant → :canvas-kind :invariant")))))

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
