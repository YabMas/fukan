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

(defstructure NonEmpty
  "Exercises the (some Type) cardinality — at least one."
  (slot :item (some Type)))

(defstructure Plain
  "A plain structure with fields (no laws)."
  (slot :field (many Type) :label-as :field-name))

(defstructure Tagged
  "A free law scoped (by default) to its own instances."
  (slot :field (many Type) :label-as :field-name)
  (law "no field labelled secret"
    :offenders '[?x]
    :where '[[?r :rel/from ?x] [?r :rel/kind :field] [?r :rel/label "secret"]]))

(defstructure Auditor
  "A free law whose subject is a *different* structure, via :scope."
  (law "no Plain has a secret field"
    :scope :Plain
    :offenders '[?p]
    :where '[[?r :rel/from ?p] [?r :rel/kind :field] [?r :rel/label "secret"]]))

;; (A pathological "rule-calls-rule" law can't be written via defstructure — the
;;  detector rejects it; see rule-calls-rule-recursion-is-rejected. The runtime
;;  guard is exercised by registering such a law directly — see
;;  pathological-recursive-law-times-out-rather-than-hangs.)

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

(deftest doc-clause-sets-instance-doc
  (testing "(doc ...) is a universal built-in clause → :entity/doc, not a slot"
    (let [db (s/with-structures
               (s/within-module "demo"
                 (Type "Int")
                 (Function "f" (doc "Builds the thing.") (gives Int))))]
      (is (= "Builds the thing."
             (ffirst (d/q '[:find ?d :where [?e :entity/name "f"] [?e :entity/doc ?d]] db))))
      (is (empty? (laws-firing db :Function))
          "the doc clause does not register as a slot or trip any law"))))

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

(deftest forward-references-and-cycles-resolve
  (testing "two-pass within-module resolves forward references and cycles between instances"
    (let [db (s/with-structures
               (s/within-module "demo"
                 (Tree "a" (child b))     ; forward reference — b declared below
                 (Tree "b" (child a))))]  ; back reference — together an a→b→a cycle
      (is (= 2 (count (d/q '[:find ?r :where [?r :rel/kind :child]] db)))
          "both :child relations resolved (the forward ref is no longer skipped)")
      (is (contains? (laws-firing db :Tree) "no cycle through :child")
          "the authored cycle is real — caught by the no-cycle law"))))

(deftest some-cardinality-requires-at-least-one
  (testing "(some Type): zero is a violation, one or more is clean"
    (let [zero (s/with-structures
                 (s/within-module "demo" (Type "Int") (NonEmpty "z")))
          one  (s/with-structures
                 (s/within-module "demo" (Type "Int") (NonEmpty "o" (item Int))))]
      (is (contains? (laws-firing zero :NonEmpty)
                     "NonEmpty.item requires at least one (found none)"))
      (is (empty? (laws-firing one :NonEmpty))))))

(deftest free-law-is-scoped-to-its-owning-structure
  (testing "a free law on Tagged flags only Tagged instances, not a Plain with the same data"
    (let [db (s/with-structures
               (s/within-module "demo"
                 (Type "Str")
                 (Plain  "p" (field [secret Str]))    ; matches the law's pattern but is NOT Tagged
                 (Tagged "t" (field [secret Str]))))  ; the genuine offender
          secret (filter #(= "no field labelled secret" (:law %)) (s/check db))]
      (is (= [:Tagged] (vec (distinct (map :structure secret)))))
      (is (= 1 (reduce + (map (comp count :offenders) secret)))
          "auto-scoping excludes the Plain; only the Tagged instance is flagged"))))

(deftest law-scope-can-target-another-structure
  (testing ":scope <tag> aims a free law's subject at a different structure"
    (let [db (s/with-structures
               (s/within-module "demo"
                 (Type "Str")
                 (Auditor "guard")
                 (Plain "p" (field [secret Str]))))]
      (is (some #(= "no Plain has a secret field" (:law %)) (s/check db))
          "the Auditor law, scoped to :Plain, flags the Plain instance"))))

(deftest rule-calls-rule-recursion-is-rejected
  (testing "defstructure rejects a self-recursive rule that calls a helper rule"
    (let [msg (try (let [_ (macroexpand
                            '(fukan.canvas.core.structure/defstructure BadRec "d"
                               (law "diverges"
                                 :offenders '[?a]
                                 :where '[(reaches ?a ?a)]
                                 :rules '[[(step ?x ?y) [?x :e ?y]]
                                          [(reaches ?x ?y) (step ?x ?y)]
                                          [(reaches ?x ?y) (step ?x ?z) (reaches ?z ?y)]])))]
                     "no throw")
                   (catch Throwable e
                     (loop [t e] (if-let [c (ex-cause t)] (recur c) (ex-message t)))))]
      (is (re-find #"rule-calls-rule recursion" msg)))))

(deftest pathological-recursive-law-times-out-rather-than-hangs
  (testing "a divergent recursive law is bounded by *law-timeout-ms* and reported, not hung"
    ;; Register the divergent law DIRECTLY — defstructure would reject this
    ;; rule-calls-rule shape (see test above), so we bypass it to exercise the
    ;; runtime guard against shapes the static check can't catch.
    (s/register-structure!
     {:tag :Diverge :doc "test-only" :slots []
      :laws [{:desc "pathological reachability" :scope :global
              :offenders '[?a ?b] :where '[(hreach ?a ?b)]
              :rules '[[(hstep ?a ?b)
                        [?r1 :rel/from ?e] [?r1 :rel/kind :h-from] [?r1 :rel/to ?a]
                        [?r2 :rel/from ?e] [?r2 :rel/kind :h-to]   [?r2 :rel/to ?b]]
                       [(hreach ?a ?b) (hstep ?a ?b)]
                       [(hreach ?a ?b) (hstep ?a ?z) (hreach ?z ?b)]]}]})
    ;; n1 -> n2 -> n1 as an INDIRECT graph (edges via e1/e2 reified rels), the
    ;; shape that diverges under a helper-rule recursion.
    (let [db (-> (s/create)
                 (d/db-with [{:entity/id "e1"} {:entity/id "e2"}
                             {:entity/id "n1"} {:entity/id "n2"}])
                 (d/db-with [{:rel/id "1" :rel/from [:entity/id "e1"] :rel/kind :h-from :rel/to [:entity/id "n1"]}
                             {:rel/id "2" :rel/from [:entity/id "e1"] :rel/kind :h-to   :rel/to [:entity/id "n2"]}
                             {:rel/id "3" :rel/from [:entity/id "e2"] :rel/kind :h-from :rel/to [:entity/id "n2"]}
                             {:rel/id "4" :rel/from [:entity/id "e2"] :rel/kind :h-to   :rel/to [:entity/id "n1"]}]))]
      (binding [s/*law-timeout-ms* 300]
        (is (some :timed-out? (s/check db))
            "the divergent Hang law is reported as timed-out and check still returns")))))

(deftest unknown-body-form-is-rejected
  (testing "defstructure rejects an unrecognized body form at macro-expansion"
    ;; macroexpand wraps the macro's ex-info in a CompilerException; walk to root.
    (let [msg (try (let [_ (macroexpand '(fukan.canvas.core.structure/defstructure Bad "d"
                                           (slt :x)))]
                     "no throw")
                   (catch Throwable e
                     (loop [t e] (if-let [c (ex-cause t)] (recur c) (ex-message t)))))]
      (is (re-find #"unknown body form" msg)))))
