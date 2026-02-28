(ns fukan.cli.commands-test
  "Generative + example-based + integration tests for CLI commands.
   Verifies CLI invariants from cli.allium hold across randomly
   generated models, specific scenarios, and Fukan's own model."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as tgen]
            [clojure.test.check.properties :as prop]
            [fukan.cli.commands :as commands]
            [fukan.model.build :as build]
            [fukan.model.analyzers.implementation.languages.clojure :as clj-lang]
            [fukan.projection.api :as proj]
            [fukan.test-support.generators :as gen]
            [fukan.test-support.invariants.cli :as inv]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- make-state
  "Create a minimal session state."
  ([] {:view-id nil :history [] :expanded #{} :src "test"})
  ([overrides] (merge (make-state) overrides)))

(defn- root-id [model]
  (:id (proj/find-root model)))

(defn- first-module-child
  "Find the first module child of view-id in the model."
  [model view-id]
  (->> (vals (:nodes model))
       (filter #(and (= :module (:kind %))
                     (= view-id (:parent %))))
       first))

(defn- first-leaf-child
  "Find the first function child of view-id in the model."
  [model view-id]
  (->> (vals (:nodes model))
       (filter #(and (= :function (:kind %))
                     (= view-id (:parent %))))
       first))

;; ---------------------------------------------------------------------------
;; Generative: response shape holds for all commands

(defspec response-shape-holds 100
  (prop/for-all [model (gen/gen-model)]
    (let [state (make-state)
          cmds [{:command "ls" :args []}
                {:command "overview" :args []}
                {:command "back" :args []}
                {:command "info" :args ["nonexistent"]}
                {:command "find" :args ["test"]}
                {:command "expand" :args ["nonexistent"]}
                {:command "bogus" :args []}]]
      (every? (fn [parsed]
                (let [{:keys [response]} (commands/dispatch model state parsed)]
                  (true? (inv/response-shape? response))))
              cmds))))

;; ---------------------------------------------------------------------------
;; Generative: command purity

(defspec commands-are-pure 50
  (prop/for-all [model (gen/gen-model)]
    (let [state (make-state)
          cmds [{:command "ls" :args []}
                {:command "overview" :args []}
                {:command "find" :args ["a"]}]]
      (every? (fn [parsed]
                (true? (inv/command-pure? model state parsed)))
              cmds))))

;; ---------------------------------------------------------------------------
;; Generative: state consistency after navigation

(defspec state-consistency-after-cd 50
  (prop/for-all [model (gen/gen-model)]
    (let [rid (root-id model)
          state (make-state)
          ;; Find a module child to cd into
          child (first-module-child model rid)]
      (if child
        (let [parsed {:command "cd" :args [(:id child)]}
              {:keys [state-update]} (commands/dispatch model state parsed)]
          (if state-update
            (true? (inv/state-consistency? model (state-update state)))
            true))
        true))))

;; ---------------------------------------------------------------------------
;; Example-based: ls at root

(deftest ls-at-root
  (testing "ls returns children of root"
    (let [model (tgen/generate (gen/gen-model))
          state (make-state)
          {:keys [response]} (commands/dispatch model state {:command "ls" :args []})]
      (is (:ok response))
      (is (= :ls (:command response)))
      (is (vector? (:children response)))
      (is (vector? (:path response))))))

;; ---------------------------------------------------------------------------
;; Example-based: cd into module, then back

(deftest cd-and-back
  (testing "cd into a module pushes history, back restores it"
    (let [model (tgen/generate (gen/gen-model {:min-modules 3}))
          rid (root-id model)
          state (make-state)
          child (first-module-child model rid)]
      (when child
        ;; cd into child
        (let [{:keys [response state-update]}
              (commands/dispatch model state {:command "cd" :args [(:id child)]})]
          (is (:ok response))
          (is (= :cd (:command response)))
          (when state-update
            (let [new-state (state-update state)]
              ;; history check
              (is (true? (inv/cd-pushes-history? model state (:id child))))
              ;; back
              (is (true? (inv/back-pops-history? model new-state))))))))))

;; ---------------------------------------------------------------------------
;; Example-based: cd into leaf fails

(deftest cd-into-leaf-fails
  (testing "cd into a function returns error"
    (let [model (tgen/generate (gen/gen-model))
          rid (root-id model)
          leaf (first-leaf-child model (or (some-> (first-module-child model rid) :id)
                                           rid))]
      (when leaf
        (let [{:keys [response]}
              (commands/dispatch model (make-state) {:command "cd" :args [(:id leaf)]})]
          (is (not (:ok response)))
          (is (string? (:error response))))))))

;; ---------------------------------------------------------------------------
;; Example-based: cd into nonexistent entity fails

(deftest cd-nonexistent-fails
  (testing "cd into nonexistent entity returns error"
    (let [model (tgen/generate (gen/gen-model))
          {:keys [response]}
          (commands/dispatch model (make-state) {:command "cd" :args ["nonexistent"]})]
      (is (not (:ok response)))
      (is (string? (:error response))))))

;; ---------------------------------------------------------------------------
;; Example-based: cd .. at root fails

(deftest cd-parent-at-root-fails
  (testing "cd .. at root returns error"
    (let [model (tgen/generate (gen/gen-model))
          {:keys [response]}
          (commands/dispatch model (make-state) {:command "cd" :args [".."]})]
      (is (not (:ok response)))
      (is (= "Already at root." (:error response))))))

;; ---------------------------------------------------------------------------
;; Example-based: back with empty history fails

(deftest back-empty-history-fails
  (testing "back with no history returns error"
    (let [model (tgen/generate (gen/gen-model))
          {:keys [response]}
          (commands/dispatch model (make-state) {:command "back" :args []})]
      (is (not (:ok response)))
      (is (string? (:error response))))))

;; ---------------------------------------------------------------------------
;; Example-based: info for existing entity

(deftest info-returns-details
  (testing "info for a known entity returns details"
    (let [model (tgen/generate (gen/gen-model))
          rid (root-id model)
          child (first-module-child model rid)]
      (when child
        (let [{:keys [response]}
              (commands/dispatch model (make-state)
                                {:command "info" :args [(:id child)]})]
          (is (:ok response))
          (is (= :info (:command response)))
          (is (= (:id child) (:entity-id response))))))))

;; ---------------------------------------------------------------------------
;; Example-based: find returns matches

(deftest find-returns-matches
  (testing "find returns results for a matching pattern"
    (let [model (tgen/generate (gen/gen-model))
          ;; Pick a label from the model to search for
          some-node (first (vals (:nodes model)))
          pattern (subs (:label some-node) 0 (min 3 (count (:label some-node))))
          {:keys [response]}
          (commands/dispatch model (make-state) {:command "find" :args [pattern]})]
      (is (:ok response))
      (is (= :find (:command response)))
      (is (pos? (:count response))))))

;; ---------------------------------------------------------------------------
;; Example-based: overview returns stats

(deftest overview-returns-stats
  (testing "overview returns model statistics"
    (let [model (tgen/generate (gen/gen-model))
          {:keys [response]}
          (commands/dispatch model (make-state) {:command "overview" :args []})]
      (is (:ok response))
      (is (= :overview (:command response)))
      (is (pos? (:total-nodes response)))
      (is (map? (:by-kind response))))))

;; ---------------------------------------------------------------------------
;; Example-based: expand toggles on/off

(deftest expand-toggles
  (testing "expand toggles module in expanded set"
    (let [model (tgen/generate (gen/gen-model))
          rid (root-id model)
          child (first-module-child model rid)]
      (when child
        ;; First expand — should add to expanded
        (is (true? (inv/expand-toggles? model (make-state) (:id child))))
        ;; Second expand with already-expanded state — should remove
        (is (true? (inv/expand-toggles?
                     model
                     (make-state {:expanded #{(:id child)}})
                     (:id child))))))))

;; ---------------------------------------------------------------------------
;; Example-based: expand on leaf fails

(deftest expand-leaf-fails
  (testing "expand on a function returns error"
    (let [model (tgen/generate (gen/gen-model))
          rid (root-id model)
          module (first-module-child model rid)
          leaf (when module (first-leaf-child model (:id module)))]
      (when leaf
        (let [{:keys [response]}
              (commands/dispatch model (make-state)
                                {:command "expand" :args [(:id leaf)]})]
          (is (not (:ok response)))
          (is (string? (:error response))))))))

;; ---------------------------------------------------------------------------
;; Example-based: unknown command fails

(deftest unknown-command-fails
  (testing "unknown command returns error"
    (let [model (tgen/generate (gen/gen-model))
          {:keys [response]}
          (commands/dispatch model (make-state) {:command "bogus" :args []})]
      (is (not (:ok response)))
      (is (string? (:error response))))))

;; ---------------------------------------------------------------------------
;; Example-based: parse-input

(deftest parse-input-works
  (testing "parse-input handles various inputs"
    (is (nil? (commands/parse-input nil)))
    (is (nil? (commands/parse-input "")))
    (is (nil? (commands/parse-input "   ")))
    (is (= {:command "ls" :args []} (commands/parse-input "ls")))
    (is (= {:command "cd" :args ["foo"]} (commands/parse-input "cd foo")))
    (is (= {:command "find" :args ["hello" "world"]}
           (commands/parse-input "find hello world")))))

;; ---------------------------------------------------------------------------
;; Integration: Fukan's own model

(deftest fukan-cli-integration
  (testing "CLI commands work against Fukan's own model"
    (let [contrib (clj-lang/contribution "src")
          schema-data (clj-lang/discover-schema-data)
          model (build/build-model contrib
                  {:type-nodes-fn (fn [ns-index]
                                    (clj-lang/build-schema-nodes ns-index schema-data))})
          state (make-state {:src "src"})
          rid (root-id model)]
      ;; ls at root
      (let [{:keys [response]} (commands/dispatch model state {:command "ls" :args []})]
        (is (:ok response))
        (is (pos? (count (:children response)))))

      ;; overview
      (let [{:keys [response]} (commands/dispatch model state {:command "overview" :args []})]
        (is (:ok response))
        (is (pos? (:total-nodes response)))
        (is (pos? (:total-edges response))))

      ;; find
      (let [{:keys [response]} (commands/dispatch model state {:command "find" :args ["build"]})]
        (is (:ok response))
        (is (pos? (:count response))))

      ;; cd into a module child
      (let [child (first-module-child model rid)]
        (when child
          (let [{:keys [response state-update]}
                (commands/dispatch model state {:command "cd" :args [(:id child)]})]
            (is (:ok response))
            (when state-update
              (let [new-state (state-update state)
                    {back-response :response} (commands/dispatch model new-state {:command "back" :args []})]
                (is (:ok back-response))))))))))

;; ---------------------------------------------------------------------------
;; Integration: static check — no model imports

(deftest projection-delegation-holds
  (testing "commands.clj does not import model internals"
    (is (true? (inv/projection-delegation?)))))
