(ns fukan.canvas.core.structure-test
  "Step-1 tests for the defstructure primitive: instantiation emits Node +
   reified slot Relations; slot cardinality + target-type compile to laws;
   `check` runs them, including a recursive free law."
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            ;; Use the real vocabulary (Type/Function) rather than redefining it —
            ;; the structure registry is global-by-tag, so test redefinitions would
            ;; collide with fukan.canvas.structures. Tree is test-only (unique tag).
            [fukan.canvas.structures :refer [Type Function]]))

;; ── structures under test ───────────────────────────────────────────────────

(defstructure Tree
  "A self-referential structure."
  (slot :child (many Tree))
  (law "no cycle through :child"
    :rules '[[(reaches ?a ?b) [?r :rel/from ?a] [?r :rel/kind :child] [?r :rel/to ?b]]
             [(reaches ?a ?b) [?r :rel/from ?a] [?r :rel/kind :child] [?r :rel/to ?m]
                              (reaches ?m ?b)]]
    :offenders '[?t]
    :where '[(reaches ?t ?t)]))

;; ── helpers ─────────────────────────────────────────────────────────────────

(defn- names-of [db tag]
  (set (map first (d/q '[:find ?n :in $ ?tag
                         :where [?e :structure/of ?tag] [?e :entity/name ?n]]
                       db tag))))

(defn- rels-of [db from-name kind]
  (set (d/q '[:find ?to-name ?label
              :in $ ?fn ?kind
              :where [?f :entity/name ?fn]
                     [?r :rel/from ?f] [?r :rel/kind ?kind]
                     [?r :rel/to ?t] [?t :entity/name ?to-name]
                     [(get-else $ ?r :rel/label "") ?label]]
            db from-name kind)))

(defn- laws-firing [db tag]
  (set (map :law (filter #(= tag (:structure %)) (s/check db)))))

;; ── tests ───────────────────────────────────────────────────────────────────

(deftest instantiation-emits-node-and-reified-relations
  (testing "an instance is a tagged Node; slot values are reified relations with labels"
    (let [db (s/with-structures
               (s/within-module "demo"
                 (Type "Int")
                 (Type "Str")
                 (Function "f"
                   (takes [x Int] [y Str])
                   (gives Int))))]
      (is (= #{"Int" "Str"} (names-of db :Type)))
      (is (= #{"f"} (names-of db :Function)))
      (is (= #{["Int" "x"] ["Str" "y"]} (rels-of db "f" :takes))
          "two labelled :takes relations to the resolved Type nodes")
      (is (= #{["Int" ""]} (rels-of db "f" :gives))
          "one :gives relation to Int, no label"))))

(deftest well-formed-function-passes-check
  (testing "exactly one gives, all targets Types → no violations"
    (let [db (s/with-structures
               (s/within-module "demo"
                 (Type "Int")
                 (Function "f" (takes [x Int]) (gives Int))))]
      (is (empty? (laws-firing db :Function))))))

(deftest cardinality-one-catches-zero-and-several
  (testing "gives (one Type): zero and several are both violations"
    (let [db (s/with-structures
               (s/within-module "demo"
                 (Type "Int")
                 (Type "Str")
                 (Function "none")                        ; zero gives
                 (Function "several" (gives Int Str))))    ; two gives
          firing (laws-firing db :Function)]
      (is (contains? firing "Function.gives requires exactly one (found none)"))
      (is (contains? firing "Function.gives requires exactly one (found several)")))))

(deftest target-type-law-catches-wrong-target
  (testing "a gives target that is not a Type is a violation"
    (let [db (s/with-structures
               (s/within-module "demo"
                 (Type "Int")
                 (Function "callee" (gives Int))
                 (Function "bad" (gives callee))))]        ; gives a Function, not a Type
      (is (contains? (laws-firing db :Function)
                     "Function.gives target must be a Type")))))

(deftest acyclic-tree-passes-via-instantiation
  (testing "a forward-resolved child chain (leaf←mid←root) has no cycle violation"
    (let [db (s/with-structures
               (s/within-module "demo"
                 (Tree "leaf")
                 (Tree "mid"  (child leaf))
                 (Tree "root" (child mid))))]
      (is (= #{"leaf" "mid" "root"} (names-of db :Tree)))
      (is (empty? (laws-firing db :Tree))
          "the recursive no-cycle law runs clean on an acyclic chain"))))

(deftest recursive-law-catches-cycle
  (testing "the recursive no-cycle law detects a cycle in a hand-built db"
    ;; Cycles aren't authorable under forward-only name resolution, so build the
    ;; cyclic substrate directly — this exercises the recursive rule + offender.
    (let [tree-db (fn [nodes edges]
                    (-> (s/create)
                        (d/db-with (for [n nodes]
                                     {:entity/id n :entity/name (name n) :structure/of :Tree}))
                        (d/db-with (for [[from to] edges]
                                     {:rel/id (str from "->" to)
                                      :rel/from [:entity/id from] :rel/kind :child
                                      :rel/to [:entity/id to]}))))
          acyclic (tree-db [:a :b :c] [[:a :b] [:b :c]])
          two     (tree-db [:x :y]    [[:x :y] [:y :x]])
          self    (tree-db [:s]       [[:s :s]])]
      (is (empty? (laws-firing acyclic :Tree)))
      (is (contains? (laws-firing two :Tree) "no cycle through :child")
          "a 2-cycle x→y→x is caught")
      (is (contains? (laws-firing self :Tree) "no cycle through :child")
          "a self-loop s→s is caught"))))
