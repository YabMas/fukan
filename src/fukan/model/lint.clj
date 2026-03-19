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
   [:contract-fns {:description "Functions the target module does export."} [:vector :string]]])

(def ^:schema LintReport
  [:map {:description "Contract compliance report: violations and aggregate statistics."}
   [:violations {:description "Cross-module calls that target unexported functions."} [:vector :LintViolation]]
   [:stats {:description "Edge counts for the checked model."}
    [:map
     [:total-edges {:description "Total edges in the model."} :int]
     [:cross-module-edges {:description "Edges crossing module boundaries."} :int]
     [:violations {:description "Cross-module edges targeting unexported functions."} :int]]]])

;; -----------------------------------------------------------------------------
;; Core linter

(defn check-contracts
  "Check all cross-module edges against declared contracts.
   Uses each function's direct parent module for ownership. A call is
   cross-module when the caller's parent differs from the callee's parent.
   The callee's parent boundary is checked — if it has no boundary
   functions, the call is not subject to contract checking.
   Returns a lint report with violations and stats."
  {:malli/schema [:=> [:cat :Model] :LintReport]}
  [{:keys [nodes edges]}]
  (let [;; 1. Build contract index: {module-id -> #{node-id ...}}
        ;;    from boundary functions that have :id populated
        contract-index
        (->> (vals nodes)
             (filter #(= :module (:kind %)))
             (reduce (fn [acc mod]
                       (let [fns (get-in mod [:data :boundary :functions])
                             ids (->> fns (keep :id) set)]
                         (if (seq ids)
                           (assoc acc (:id mod) ids)
                           acc)))
                     {}))

        ;; 2. Each leaf node's module is its direct :parent
        node->module (fn [nid] (:parent (get nodes nid)))

        ;; 3. Filter to function-targeting cross-module edges, check contracts
        fn-edges (filter #(and (= :function-call (:kind %))
                                (= :function (:kind (get nodes (:to %)))))
                         edges)

        violations
        (->> fn-edges
             (keep (fn [{:keys [from to]}]
                     (let [from-mod (node->module from)
                           to-mod (node->module to)]
                       (when (and from-mod to-mod
                                  (not= from-mod to-mod)
                                  (contains? contract-index to-mod)
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
                       (let [fm (node->module from)
                             tm (node->module to)]
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
