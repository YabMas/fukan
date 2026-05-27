(ns fukan.canvas.instruct.drift-close-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.canvas.instruct.core :as core]
            [fukan.canvas.instruct.drift-close :as drift-close]))

;; ---------------------------------------------------------------------------
;; Synthetic fixtures

(def ^:private sample-finding
  "Shape mirrors `fukan.canvas.inspect.drift/absent-edge->finding` output."
  {:check     :inspect.drift/missing-implementation
   :severity  :warning
   :message   (str "Canvas declares getter get_self_role at "
                   "distributed.cluster; no matching code-side artifact "
                   "at fukan.distributed.cluster/get-self-role.")
   :offenders [{:stable-id          "distributed.cluster/getter/get_self_role"
                :expected-code-path "src/fukan/distributed/cluster.clj"
                :expected-symbol    "get-self-role"
                :canvas-kind        :getter}]
   :detail    {:canvas-side-id     "distributed.cluster/getter/get_self_role"
               :code-side-expected "fukan.distributed.cluster/get-self-role"
               :projection-kind    :projection-kind/operation}})

(def ^:private sample-code-spec
  {:projection-kind    :clojure/function-to-defn
   :lens-id            :clojure
   :model-element-kind :Affordance
   :model-element-id   "distributed.cluster/getter/get_self_role"
   :target             {:path      "src/fukan/distributed/cluster.clj"
                        :namespace "fukan.distributed.cluster"
                        :symbol    "get-self-role"}
   :template           (str "(defn get-self-role\n"
                            "  \"This node's current role within the cluster.\"\n"
                            "  {:malli/schema [:=> [:cat] [:maybe :NodeRole]]}\n"
                            "  []\n"
                            "  (throw (ex-info \"get-self-role: not yet implemented\"\n"
                            "                  {:canvas-id \"distributed.cluster/getter/get_self_role\"})))")
   :prose              "This node's current role within the cluster."
   :context            {:canvas-source-ref "canvas/distributed/cluster.clj"
                        :doc-source        :canvas/function-doc
                        :arity             0}})

;; ---------------------------------------------------------------------------
;; Scenario shape

(deftest scenario-satisfies-contract
  (is (core/valid-scenario? drift-close/scenario))
  (is (= :code-side/drift-close (:scenario-id drift-close/scenario))))

;; ---------------------------------------------------------------------------
;; build-context

(deftest build-context-handles-missing-target-file
  (testing "no target file → :target-file-state :absent"
    (let [opts    {:target-file-reader (fn [_] nil)
                   :drift-finding (first (:offenders sample-finding))}
          context ((:build-context drift-close/scenario) sample-code-spec opts)]
      (is (= :absent (:target-file-state context)))
      (is (= (first (:offenders sample-finding))
             (:drift-finding context))
          "drift-finding must be carried; tests inject via opts")
      (is (contains? context :discipline-prose)))))

(deftest build-context-extracts-neighbor-summary
  (testing "real-shape input → ns-symbol + sibling defs extracted"
    (let [fake-source (str "(ns fukan.distributed.cluster\n"
                           "  \"Implementation surface — partial.\")\n\n"
                           "(def NodeId\n"
                           "  \"An opaque, stable identity for a cluster member.\"\n"
                           "  [:string {:min 1}])\n\n"
                           "(defn get-current-term\n"
                           "  \"The most recent Term this node has observed.\"\n"
                           "  [cluster] (:current_term cluster))\n")
          opts {:target-file-reader (fn [_] fake-source)
                :drift-finding (first (:offenders sample-finding))}
          context ((:build-context drift-close/scenario) sample-code-spec opts)]
      (is (= :present (:target-file-state context)))
      (let [what (:what-exists-in-target-file context)]
        (is (= "fukan.distributed.cluster" (:ns-symbol what)))
        (let [siblings (set (map :symbol (:sibling-defs what)))]
          (is (contains? siblings "NodeId"))
          (is (contains? siblings "get-current-term")))))))

(deftest build-context-caps-sibling-defs
  (testing "more than the cap → list truncated"
    (let [many (str/join "\n\n"
                         (cons "(ns fukan.example)"
                               (for [i (range 30)]
                                 (str "(defn f-" i " \"doc " i "\" [] nil)"))))
          opts {:target-file-reader (fn [_] many)
                :drift-finding (first (:offenders sample-finding))}
          context ((:build-context drift-close/scenario) sample-code-spec opts)]
      (is (<= (count (:sibling-defs (:what-exists-in-target-file context)))
              15)
          "sibling-def cap should keep output sane"))))

;; ---------------------------------------------------------------------------
;; render

(deftest render-produces-valid-instruction
  (let [opts {:target-file-reader (fn [_] nil)
              :drift-finding (first (:offenders sample-finding))}
        ctx  ((:build-context drift-close/scenario) sample-code-spec opts)
        inst ((:render drift-close/scenario) sample-code-spec ctx opts)]
    (is (core/valid-instruction? inst))
    (is (= :code-side/drift-close (:scenario-id inst)))
    (is (= sample-code-spec (:code-spec inst)))
    (is (string? (:rendered inst)))))

(deftest render-includes-load-bearing-sections
  (let [opts {:target-file-reader (fn [_] nil)
              :drift-finding (first (:offenders sample-finding))}
        ctx  ((:build-context drift-close/scenario) sample-code-spec opts)
        md   (:rendered ((:render drift-close/scenario) sample-code-spec ctx opts))]
    (testing "frame, drift summary, code spec, neighbor section, discipline"
      (is (str/includes? md "drift-close"))
      (is (str/includes? md "get-self-role"))
      (is (str/includes? md "src/fukan/distributed/cluster.clj"))
      (is (str/includes? md "distributed.cluster/getter/get_self_role"))
      (is (str/includes? md "(defn get-self-role"))
      (is (re-find #"(?i)discipline|disturb" md))
      (is (re-find #"(?i)output" md)))))

(deftest render-without-target-file-still-coherent
  (testing "absent target file: neighbor section should still render gracefully"
    (let [opts {:target-file-reader (fn [_] nil)
                :drift-finding (first (:offenders sample-finding))}
          ctx  ((:build-context drift-close/scenario) sample-code-spec opts)
          md   (:rendered ((:render drift-close/scenario) sample-code-spec ctx opts))]
      (is (string? md))
      (is (str/includes? md "get-self-role")))))

;; ---------------------------------------------------------------------------
;; Real fukan-itself integration

(deftest real-target-file-extracts-cluster-siblings
  (testing "the actual src/fukan/distributed/cluster.clj is parseable"
    (let [opts {:drift-finding (first (:offenders sample-finding))}
          ctx  ((:build-context drift-close/scenario) sample-code-spec opts)
          siblings (set (map :symbol
                             (:sibling-defs
                              (:what-exists-in-target-file ctx))))]
      (when (.exists (io/file "src/fukan/distributed/cluster.clj"))
        (is (= :present (:target-file-state ctx)))
        (is (contains? siblings "NodeId"))
        (is (contains? siblings "get-current-term"))
        (is (contains? siblings "get-node"))))))
