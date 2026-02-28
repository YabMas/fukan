(ns fukan.test-support.invariants.cli
  "CLI invariant predicates derived from cli.allium.
   Each predicate takes relevant inputs and returns true
   on success or a descriptive map on violation."
  (:require [clojure.java.io :as io]
            [fukan.cli.commands :as commands]))

;; ---------------------------------------------------------------------------
;; ResponseShape: every response has :ok (boolean) and :command (keyword).

(defn response-shape?
  "Verify that a response map has the required :ok and :command fields."
  [response]
  (cond
    (not (map? response))
    {:violation :response-shape
     :reason "response is not a map"
     :response response}

    (not (contains? response :ok))
    {:violation :response-shape
     :reason "response missing :ok field"
     :response response}

    (not (boolean? (:ok response)))
    {:violation :response-shape
     :reason ":ok is not a boolean"
     :ok (:ok response)}

    (not (contains? response :command))
    {:violation :response-shape
     :reason "response missing :command field"
     :response response}

    (not (keyword? (:command response)))
    {:violation :response-shape
     :reason ":command is not a keyword"
     :command (:command response)}

    :else true))

;; ---------------------------------------------------------------------------
;; CommandPure: same inputs → same outputs.

(defn command-pure?
  "Verify that dispatching the same command twice produces identical results.
   Ignores state-update (a function) — compares only response maps."
  [model state parsed]
  (let [result1 (commands/dispatch model state parsed)
        result2 (commands/dispatch model state parsed)]
    (if (= (:response result1) (:response result2))
      true
      {:violation :command-pure
       :reason "same inputs produced different responses"
       :parsed parsed
       :response-1 (:response result1)
       :response-2 (:response result2)})))

;; ---------------------------------------------------------------------------
;; CdPushesHistory: successful cd pushes old view-id onto history.

(defn cd-pushes-history?
  "Verify that a successful cd pushes the effective view-id onto history.
   The effective view-id resolves nil to the root node ID."
  [model state target-id]
  (let [;; Resolve effective view-id same way commands/current-view-id does
        effective-view-id (or (:view-id state)
                              (:id (first (filter #(nil? (:parent %))
                                                  (vals (:nodes model))))))
        parsed {:command "cd" :args [target-id]}
        {:keys [response state-update]} (commands/dispatch model state parsed)]
    (if-not (:ok response)
      true ;; error responses don't modify history — not a violation
      (let [new-state (state-update state)
            new-history (:history new-state)]
        (if (= effective-view-id (peek new-history))
          true
          {:violation :cd-pushes-history
           :reason "successful cd did not push effective view-id onto history"
           :effective-view-id effective-view-id
           :new-history new-history})))))

;; ---------------------------------------------------------------------------
;; BackPopsHistory: successful back pops last history entry.

(defn back-pops-history?
  "Verify that a successful back pops the last history entry."
  [model state]
  (let [old-history (:history state)
        parsed {:command "back" :args []}
        {:keys [response state-update]} (commands/dispatch model state parsed)]
    (if-not (:ok response)
      true ;; error responses (empty history) — not a violation
      (let [new-state (state-update state)
            expected-view (peek old-history)
            expected-history (pop old-history)]
        (cond
          (not= expected-view (:view-id new-state))
          {:violation :back-pops-history
           :reason "view-id not set to popped history entry"
           :expected expected-view
           :actual (:view-id new-state)}

          (not= expected-history (:history new-state))
          {:violation :back-pops-history
           :reason "history not correctly popped"
           :expected expected-history
           :actual (:history new-state)}

          :else true)))))

;; ---------------------------------------------------------------------------
;; StateConsistency: view-id is nil or a valid container in the model.

(defn state-consistency?
  "Verify that view-id is nil or points to a valid container in the model."
  [model state]
  (let [view-id (:view-id state)]
    (if (nil? view-id)
      true
      (let [node (get-in model [:nodes view-id])]
        (cond
          (nil? node)
          {:violation :state-consistency
           :reason "view-id points to non-existent node"
           :view-id view-id}

          (not= :container (:kind node))
          {:violation :state-consistency
           :reason "view-id points to non-container node"
           :view-id view-id
           :kind (:kind node)}

          :else true)))))

;; ---------------------------------------------------------------------------
;; ExpandToggle: expand flips membership in expanded set.

(defn expand-toggles?
  "Verify that expand flips the container's membership in the expanded set."
  [model state container-id]
  (let [was-expanded? (contains? (:expanded state) container-id)
        parsed {:command "expand" :args [container-id]}
        {:keys [response state-update]} (commands/dispatch model state parsed)]
    (if-not (:ok response)
      true ;; error responses don't modify expanded — not a violation
      (let [new-state (state-update state)
            is-expanded? (contains? (:expanded new-state) container-id)]
        (if (= was-expanded? (not is-expanded?))
          true
          {:violation :expand-toggles
           :reason "expand did not flip membership"
           :container-id container-id
           :was-expanded? was-expanded?
           :is-expanded? is-expanded?})))))

;; ---------------------------------------------------------------------------
;; ProjectionDelegation: commands.clj doesn't import model internals.

(defn projection-delegation?
  "Static check that commands.clj only requires fukan.projection.api,
   not any fukan.model.* namespaces."
  []
  (let [source (slurp (io/resource "fukan/cli/commands.clj"))
        has-model-require? (re-find #"fukan\.model\." source)]
    (if has-model-require?
      {:violation :projection-delegation
       :reason "commands.clj imports model internals"
       :match (str has-model-require?)}
      true)))
