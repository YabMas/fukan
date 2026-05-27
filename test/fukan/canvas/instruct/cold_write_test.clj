(ns fukan.canvas.instruct.cold-write-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.canvas.instruct.cold-write :as cold-write]
            [fukan.canvas.instruct.core :as core]))

;; ---------------------------------------------------------------------------
;; Synthetic projections — mirror the Layer-A shape

(defn- spec
  [kind sym element-id template prose]
  {:projection-kind    kind
   :lens-id            :clojure
   :model-element-kind :Type
   :model-element-id   element-id
   :target             {:path      "src/fukan/distributed/cluster.clj"
                        :namespace "fukan.distributed.cluster"
                        :symbol    sym}
   :template           template
   :prose              prose
   :context            {:canvas-source-ref "canvas/distributed/cluster.clj"}})

(def ^:private node-id-spec
  (spec :clojure/value-to-def "NodeId"
        "distributed.cluster/type/NodeId"
        "(def NodeId\n  [:any {:description \"opaque stable identity\"}])"
        "An opaque, stable identity for a cluster member."))

(def ^:private term-spec
  (spec :clojure/value-to-def "Term"
        "distributed.cluster/type/Term"
        "(def Term\n  [:any {:description \"monotonically increasing clock\"}])"
        "A monotonically increasing logical clock."))

(def ^:private node-spec
  (spec :clojure/type-to-malli "Node"
        "distributed.cluster/type/Node"
        "(def Node\n  [:map [:id :NodeId] [:role :NodeRole]])"
        "A known member of the cluster."))

(def ^:private invariant-spec
  {:projection-kind    :clojure/invariant-to-predicate
   :lens-id            :clojure
   :model-element-kind :Affordance
   :model-element-id   "distributed.cluster/invariant/AtMostOneLeaderPerTerm"
   :target             {:path      "src/fukan/distributed/cluster.clj"
                        :namespace "fukan.distributed.cluster"
                        :symbol    "at-most-one-leader-per-term"}
   :template           "(defn at-most-one-leader-per-term [model] :stub)"
   :prose              "Across the cluster, at most one Leader per Term."
   :context            {}})

(def ^:private all-specs
  [node-id-spec term-spec node-spec invariant-spec])

;; ---------------------------------------------------------------------------
;; Scenario shape

(deftest scenario-satisfies-contract
  (is (core/valid-scenario? cold-write/scenario))
  (is (= :code-side/cold-write (:scenario-id cold-write/scenario))))

;; ---------------------------------------------------------------------------
;; build-context

(deftest build-context-bundles-projections
  (let [opts {:module-id    "distributed.cluster"
              :projections  all-specs}
        ctx  ((:build-context cold-write/scenario) nil opts)]
    (is (= "distributed.cluster" (:module-id ctx)))
    (is (= "src/fukan/distributed/cluster.clj" (:target-file ctx)))
    (is (= 4 (count (:projections ctx))))
    (is (contains? ctx :conventions-pointer))
    (is (contains? ctx :discipline-prose))))

(deftest build-context-detects-existing-target-file
  (testing "absent target by default; opt overrides"
    (let [absent ((:build-context cold-write/scenario) nil
                  {:module-id "x.y" :projections [node-id-spec]
                   :target-file-exists? false})
          present ((:build-context cold-write/scenario) nil
                   {:module-id "x.y" :projections [node-id-spec]
                    :target-file-exists? true})]
      (is (false? (:target-already-exists? absent)))
      (is (true? (:target-already-exists? present))))))

;; ---------------------------------------------------------------------------
;; include-entity-ids subsetting

(deftest include-entity-ids-subsets
  (testing "only listed ids appear in :projections"
    (let [opts {:module-id "distributed.cluster"
                :projections all-specs
                :include-entity-ids
                #{"distributed.cluster/type/NodeId"
                  "distributed.cluster/type/Term"
                  "distributed.cluster/invariant/AtMostOneLeaderPerTerm"}}
          ctx  ((:build-context cold-write/scenario) nil opts)
          ids  (set (map :model-element-id (:projections ctx)))]
      (is (= 3 (count ids)))
      (is (= (:include-entity-ids opts) ids)))))

(deftest include-entity-ids-empty-falls-back-to-all
  (testing "nil/empty :include-entity-ids includes everything"
    (let [opts {:module-id "distributed.cluster"
                :projections all-specs}
          ctx  ((:build-context cold-write/scenario) nil opts)]
      (is (= 4 (count (:projections ctx)))))))

;; ---------------------------------------------------------------------------
;; Neighbor selection

(deftest neighbors-respects-explicit-opts
  (let [opts {:module-id "distributed.cluster"
              :projections all-specs
              :neighbors [{:canvas-path "canvas/infra/server.clj"
                           :src-path    "src/fukan/infra/server.clj"
                           :why         "Same kind-mix."}]}
        ctx  ((:build-context cold-write/scenario) nil opts)]
    (is (= 1 (count (:neighbors ctx))))
    (is (= "canvas/infra/server.clj"
           (-> ctx :neighbors first :canvas-path)))))

;; ---------------------------------------------------------------------------
;; Render

(deftest render-produces-valid-instruction
  (let [opts {:module-id "distributed.cluster"
              :projections all-specs}
        ctx  ((:build-context cold-write/scenario) nil opts)
        inst ((:render cold-write/scenario) nil ctx opts)]
    (is (core/valid-instruction? inst))
    (is (= :code-side/cold-write (:scenario-id inst)))
    (is (string? (:rendered inst)))))

(deftest render-includes-load-bearing-sections
  (let [opts {:module-id "distributed.cluster"
              :projections all-specs
              :neighbors [{:canvas-path "canvas/infra/server.clj"
                           :src-path    "src/fukan/infra/server.clj"
                           :why         "Same kind-mix."}]}
        ctx  ((:build-context cold-write/scenario) nil opts)
        md   (:rendered ((:render cold-write/scenario) nil ctx opts))]
    (testing "frame, module summary, per-entity specs, conventions, neighbors, discipline"
      (is (str/includes? md "cold-write"))
      (is (str/includes? md "distributed.cluster"))
      (is (str/includes? md "NodeId"))
      (is (str/includes? md "Term"))
      (is (str/includes? md "Node"))
      (is (str/includes? md "at-most-one-leader-per-term"))
      (is (re-find #"(?i)conventions" md))
      (is (str/includes? md "infra/server"))
      (is (re-find #"(?i)discipline" md))
      (is (re-find #"(?i)output" md)))))

(deftest render-entity-count-summary
  (let [opts {:module-id "distributed.cluster"
              :projections all-specs}
        ctx  ((:build-context cold-write/scenario) nil opts)
        md   (:rendered ((:render cold-write/scenario) nil ctx opts))]
    (testing "summary names 4 entities total"
      (is (str/includes? md "4 entities")))))

(deftest render-subsetted-vs-full
  (testing "subsetting via :include-entity-ids shortens the output"
    (let [opts-full   {:module-id "distributed.cluster"
                       :projections all-specs}
          opts-subset {:module-id "distributed.cluster"
                       :projections all-specs
                       :include-entity-ids
                       #{"distributed.cluster/type/NodeId"
                         "distributed.cluster/type/Term"
                         "distributed.cluster/invariant/AtMostOneLeaderPerTerm"}}
          full ((:render cold-write/scenario) nil
                ((:build-context cold-write/scenario) nil opts-full)
                opts-full)
          subset ((:render cold-write/scenario) nil
                  ((:build-context cold-write/scenario) nil opts-subset)
                  opts-subset)
          line-count #(count (str/split-lines (:rendered %)))]
      (is (> (line-count full) (line-count subset))))))

;; ---------------------------------------------------------------------------
;; Real fukan-itself integration (file-system smoke test)

(deftest real-canvas-target-paths-resolve
  (testing "the canvas module path and src/ path follow the convention"
    (when (.exists (io/file "canvas/distributed/cluster.clj"))
      (let [opts {:module-id "distributed.cluster"
                  :projections all-specs}
            ctx  ((:build-context cold-write/scenario) nil opts)]
        (is (= "canvas/distributed/cluster.clj" (:module-canvas-path ctx)))
        (is (= "src/fukan/distributed/cluster.clj" (:target-file ctx)))))))
