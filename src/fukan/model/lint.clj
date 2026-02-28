(ns fukan.model.lint
  "Cross-module contract compliance checker.
   Verifies that every cross-module function call targets a function
   declared in the target module's contract."
  (:require [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Helpers

(defn- descendant-ids
  "Collect all descendant node IDs of a container by walking :children recursively."
  [nodes container-id]
  (loop [queue (vec (:children (get nodes container-id)))
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
  {:malli/schema [:=> [:cat :Model] :map]}
  [{:keys [nodes edges]}]
  (let [;; 1. Collect modules: containers whose contract has :source :declared
        modules (->> (vals nodes)
                     (filter #(and (= :container (:kind %))
                                   (= :declared (get-in % [:data :contract :source]))))
                     (map :id)
                     set)

        ;; 2. Build contract index: {module-id -> #{node-id ...}}
        contract-index
        (into {}
              (map (fn [mod-id]
                     (let [fns (get-in nodes [mod-id :data :contract :functions])
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
                                              (get-in nodes [to-mod :data :contract :functions]))}))))
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
  {:malli/schema [:=> [:cat :map] :string]}
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
