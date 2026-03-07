(ns fukan.model.lint
  "Cross-module contract compliance checker.
   Verifies that every cross-module function call targets a function
   declared in the target module's contract."
  (:require [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema LintViolation
  [:map {:description "A single contract violation: a cross-module call to a function not declared in the target's contract."}
   [:from {:description "Node ID of the calling function."} :NodeId]
   [:to {:description "Node ID of the called function (not in contract)."} :NodeId]
   [:from-module {:description "Module containing the caller."} :NodeId]
   [:to-module {:description "Module containing the callee."} :NodeId]
   [:contract-fns {:description "Functions the target module does export."} [:vector :symbol]]])

(def ^:schema LintReport
  [:map {:description "Contract compliance report: violations and aggregate statistics."}
   [:violations {:description "Cross-module calls that target unexported functions."} [:vector :LintViolation]]
   [:stats {:description "Edge counts for the checked model."}
    [:map
     [:total-edges {:description "Total edges in the model."} :int]
     [:cross-module-edges {:description "Edges crossing module boundaries."} :int]
     [:violations {:description "Cross-module edges targeting unexported functions."} :int]]]])

;; -----------------------------------------------------------------------------
;; Helpers

(defn- descendant-ids
  "Collect all descendant node IDs of a module by walking :children recursively."
  [nodes module-id]
  (loop [queue (vec (:children (get nodes module-id)))
         acc #{}]
    (if (empty? queue)
      acc
      (let [id (first queue)
            node (get nodes id)
            children (vec (:children node))]
        (recur (into (subvec queue 1) children)
               (conj acc id))))))

;; -----------------------------------------------------------------------------
;; Core linter

(defn check-contracts
  "Check all cross-module edges against declared contracts.
   Only checks edges targeting :function nodes — contracts declare
   functions, so only function-level edges can be violations.
   Returns a lint report with violations and stats."
  {:malli/schema [:=> [:cat :Model] :LintReport]}
  [{:keys [nodes edges]}]
  (let [;; 1. Collect modules: module nodes with a boundary containing functions
        modules (->> (vals nodes)
                     (filter #(and (= :module (:kind %))
                                   (seq (get-in % [:data :boundary :functions]))))
                     (map :id)
                     set)

        ;; 2. Build boundary index: {module-id -> #{node-id ...}}
        contract-index
        (into {}
              (map (fn [mod-id]
                     (let [fns (get-in nodes [mod-id :data :boundary :functions])
                           ids (->> fns (keep :id) set)]
                       [mod-id ids])))
              modules)

        ;; 3. Build membership index: {module-id -> #{descendant-id ...}}
        membership-index
        (into {}
              (map (fn [mod-id]
                     [mod-id (descendant-ids nodes mod-id)]))
              modules)

        ;; Reverse: {node-id -> module-id} for quick lookup
        node->module
        (reduce (fn [acc [mod-id members]]
                  (reduce (fn [a nid] (assoc a nid mod-id))
                          acc members))
                (into {} (map (fn [m] [m m]) modules))
                membership-index)

        ;; 4. Filter to function-targeting cross-module edges, check contracts
        fn-edges (filter #(= :function (:kind (get nodes (:to %)))) edges)

        violations
        (->> fn-edges
             (keep (fn [{:keys [from to]}]
                     (let [from-mod (get node->module from)
                           to-mod (get node->module to)]
                       (when (and from-mod to-mod
                                  (not= from-mod to-mod)
                                  (not (contains? (get contract-index to-mod) to)))
                         {:from from
                          :to to
                          :from-module from-mod
                          :to-module to-mod
                          :contract-fns (mapv :name
                                              (get-in nodes [to-mod :data :boundary :functions]))}))))
             vec)

        cross-module-count
        (->> fn-edges
             (filter (fn [{:keys [from to]}]
                       (let [fm (get node->module from)
                             tm (get node->module to)]
                         (and fm tm (not= fm tm)))))
             count)]

    {:violations violations
     :stats {:total-edges (count edges)
             :cross-module-edges cross-module-count
             :violations (count violations)}}))

;; -----------------------------------------------------------------------------
;; Report formatting

(defn format-report
  "Render a lint report as a human-readable string."
  {:malli/schema [:=> [:cat :LintReport] :string]}
  [{:keys [violations stats]}]
  (let [header (str "Contract lint: "
                    (:violations stats) " violation(s) found"
                    " (" (:cross-module-edges stats) " cross-module edges checked)\n")
        body (str/join
               "\n"
               (map (fn [{:keys [from to to-module contract-fns]}]
                      (str "  " from "\n"
                           "    calls " to "\n"
                           "    which is not exported by " to-module "\n"
                           "    Exported: " (str/join ", " contract-fns)))
                    violations))]
    (str header body)))
