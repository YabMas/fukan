(ns fukan.canvas.core.structure-test
  "Step-1 tests for the defstructure primitive: instantiation emits Node +
   reified slot Relations; slot cardinality + target-type compile to laws;
   `check` runs them, including a recursive free law.

   Instances are top-level def-emitting forms assembled with `assemble-vars`
   (the var-capture surface); references between them are ordinary var refs."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.canvas.core.substrate :as sub]
            [fukan.canvas.core.typing :as typing]
            [lib.type.malli]))

;; The core is dialect-BLIND: slot targets (both plain scalar keywords and refined
;; vector forms) are checked through whatever :valid? the project registered. This
;; test brings its own minimal dialect that handles enum membership (vector forms)
;; AND the basic scalar types that were previously in the kernel's predicate map —
;; registered around the run, restoring whatever was there before.
(defn- enum-only-valid? [form v]
  (cond
    (= :int form)     (integer? v)
    (= :string form)  (string? v)
    (= :boolean form) (boolean? v)
    (vector? form)    (if (= :enum (first form))
                        (contains? (set (rest form)) v)
                        (throw (ex-info "test dialect handles only basic scalars and [:enum …]" {:form form})))
    :else (throw (ex-info "test dialect handles only basic scalars and [:enum …]" {:form form}))))

(use-fixtures :once
  (fn [t]
    (let [saved (typing/registered-dialect)]
      (typing/register-type-dialect! {:valid? enum-only-valid?})
      (try (t)
           (finally
             (typing/clear-type-dialect!)
             (when saved (typing/register-type-dialect! saved)))))))

;; ── structures under test ───────────────────────────────────────────────────
;; The primitive's tests own their fixtures (the base vocab was evicted from core;
;; the registry is global-by-tag, so these Type/Function tags are the test's now).

(defstructure Type
  "Test fixture: a named atomic type (a slot target).")

(defstructure Function
  "Test fixture: takes typed inputs, gives exactly one typed output."
  {:takes [:* Type]
   :gives Type})

(defstructure Tree
  "A self-referential structure."
  {:child [:* Tree]}
  (law "no cycle through :child"
    :rules '[[(reaches ?a ?b) [?r :rel/from ?a] [?r :rel/kind :child] [?r :rel/to ?b]]
             [(reaches ?a ?b) [?r :rel/from ?a] [?r :rel/kind :child] [?r :rel/to ?m]
                              (reaches ?m ?b)]]
    :offenders '[?t]
    :where '[(reaches ?t ?t)]))

(defstructure NonEmpty
  "Exercises the [:+ Type] cardinality — at least one."
  {:item [:+ Type]})

(defstructure Plain
  "A plain structure with fields (no laws)."
  {:field [:* Type]})

(defstructure Tagged
  "A free law scoped (by default) to its own instances."
  {:field [:* Type]}
  (law "no field labelled secret"
    :offenders '[?x]
    :where '[[?r :rel/from ?x] [?r :rel/kind :field] [?r :rel/label "secret"]]))

(defstructure Auditor
  "A free law whose subject is a *different* structure, via :scope."
  (law "no Plain has a secret field"
    :scope ::Plain
    :offenders '[?p]
    :where '[[?r :rel/from ?p] [?r :rel/kind :field] [?r :rel/label "secret"]]))

(defstructure Box
  "Exercises value slots: a required Bool, an optional String, a required Int.
   The positivity constraint is a free law — proving predicates ride the engine."
  {:open  :boolean
   :label [:? :string]
   :size  :int}
  (law "size must be positive"
    :offenders '[?x]
    :where '[[?x :val/size ?s] [(<= ?s 0)]]))

;; value-identity test vocab: Pair/Boxed are ^:value (content-deduped, inline,
;; anonymous); Holder is an entity that holds them.
(defstructure ^:value Pair
  "A value-typed pair of ints — content-identified."
  {:fst :int
   :snd :int})

(defstructure ^:value Boxed
  "A value wrapping a Pair (nested value) and pointing at a Type entity."
  {:inner Pair
   :ty    Type})

(defstructure Holder
  "An entity that holds value-typed Pairs and Boxeds inline."
  {:pair [:* Pair]
   :box  [:* Boxed]})

;; data-literal reader: a bare Int authors a Wrapped value (exercises the generic
;; per-structure :reader — a literal arg is expanded to construction clauses).
(defstructure ^:value Wrapped
  "A value with a data-literal reader: the literal `5` authors `(v 5)`."
  {:v :int}
  (reader (fn [n] [(list 'v n)])))

(defstructure Holder2
  "An entity holding Wrapped values authored via the Int literal."
  {:w [:* Wrapped]})

;; sequence composition: multi-slots are ordered by default ([:*]/[:+]); a [:set]
;; slot opts out — no recorded order, duplicates collapse, identity ignores order.
(defstructure Seq2
  "An entity with a sequence slot (zero or more Types, in authoring order)."
  {:items [:* Type]})

(defstructure ^:value OrdVal
  "A value whose sequence slot makes order part of its identity."
  {:xs [:* Type]})

(defstructure ^:value SetVal
  "A value whose :set slot keeps order OUT of its identity."
  {:xs [:set Type]})

(defstructure HolderO
  "An entity holding sequence values inline."
  {:v [:* OrdVal]})

(defstructure HolderS
  "An entity holding set values inline, and a :set slot of its own."
  {:v    [:* SetVal]
   :tags [:set Type]})

(defstructure Carry
  "Test fixture: a scalar slot with a :payload companion."
  {:text [:? {:payload :extra} :string]})

(defstructure Gate
  "Test fixture: refined slot targets — scalars checked through the type dialect."
  {:state [:enum "open" "closed"]
   :note  [:? [:enum "a" "b"]]})

;; (A pathological "rule-calls-rule" law can't be written via defstructure — the
;;  detector rejects it; see rule-calls-rule-recursion-is-rejected. The runtime
;;  guard is exercised by registering such a law directly — see
;;  pathological-recursive-law-times-out-rather-than-hangs.)

;; ── helpers ─────────────────────────────────────────────────────────────────

;; tags are ns-qualified; tests pass a short handle and match by its name (unique within a test db)
(defn- names-of [db tag]
  (->> (d/q '[:find ?n ?t :where [?e :structure/of ?t] [?e :entity/name ?n]] db)
       (filter (fn [[_ t]] (= (name tag) (name t)))) (map first) set))

(defn- rels-of [db from-name kind]
  (set (d/q '[:find ?to-name ?label
              :in $ ?fn ?kind
              :where [?f :entity/name ?fn]
                     [?r :rel/from ?f] [?r :rel/kind ?kind]
                     [?r :rel/to ?t] [?t :entity/name ?to-name]
                     [(get-else $ ?r :rel/label "") ?label]]
            db from-name kind)))

(defn- laws-firing [db tag]
  (set (map :law (filter #(= (name tag) (some-> (:structure %) name)) (s/check db)))))

(defn- count-of [db tag]
  (->> (d/q '[:find ?e ?t :where [?e :structure/of ?t]] db)
       (filter (fn [[_ t]] (= (name tag) (name t)))) count))

;; ── instances under test (top-level def-emitting forms, assembled per test) ──
;; `^{:name "…"}` overrides where the entity name repeats across cases (one var
;; per ns, many same-named fixtures).

(Type ^{:name "Int"} ie-Int)
(Type ^{:name "Str"} ie-Str)
(Function ^{:name "f"} ie-f {:takes [[x ie-Int] [y ie-Str]] :gives ie-Int})

(Type ^{:name "Int"} wf-Int)
(Function ^{:name "f"} wf-f {:takes [[x wf-Int]] :gives wf-Int})

(Type ^{:name "Int"} dc-Int)
(Function ^{:name "f"} dc-f "Builds the thing." {:gives dc-Int})

(Type ^{:name "Int"} co-Int)
(Type ^{:name "Str"} co-Str)
(Function ^{:name "none"} co-none)                  ; zero gives
;; two targets on a one-card slot is deliberately ill-formed — the map surface
;; can't author it, so build the violating instance through the internal IR
(def co-several (sub/->InstanceValue ::Function "several" nil {}
                                   [{:rk :gives :card :one :targets [#'co-Int #'co-Str]}] false))

(Type ^{:name "Int"} tt-Int)
(Function ^{:name "callee"} tt-callee {:gives tt-Int})
(Function ^{:name "bad"} tt-bad {:gives tt-callee})  ; gives a Function, not a Type

(Tree ^{:name "leaf"} at-leaf)
(Tree ^{:name "mid"}  at-mid  {:child [at-leaf]})
(Tree ^{:name "root"} at-root {:child [at-mid]})

(declare frc-b)
(Tree ^{:name "a"} frc-a {:child [frc-b]})   ; forward reference — frc-b declared below
(Tree ^{:name "b"} frc-b {:child [frc-a]})   ; back reference — together an a→b→a cycle

(Box ^{:name "b"} vs-b {:open true :label "hi" :size 3})
(Box ^{:name "b"} vf-b {:open false :size 3})

(Type ^{:name "Int"} sc-z-Int)
(NonEmpty ^{:name "z"} sc-z)                       ; zero items
(Type ^{:name "Int"} sc-o-Int)
(NonEmpty ^{:name "o"} sc-o {:item [sc-o-Int]})    ; one item

(Type ^{:name "Str"} fl-Str)
(Plain  ^{:name "p"} fl-p {:field [[secret fl-Str]]})   ; matches the pattern but is NOT Tagged
(Tagged ^{:name "t"} fl-t {:field [[secret fl-Str]]})   ; the genuine offender

(Type ^{:name "Str"} ls-Str)
(Auditor ^{:name "guard"} ls-guard)
(Plain ^{:name "p"} ls-p {:field [[secret ls-Str]]})

(Box ^{:name "b"} vw-b {:open true :size 3})     ; label optional, omitted
(Box ^{:name "b"} vt-b {:open "yes" :size 3})    ; open is a String, not a Bool
(Box ^{:name "b"} vo-b {:size 3})                ; open absent
(Box ^{:name "b"} fv-b {:open true :size 0})     ; size 0 violates positivity

(Holder2 ^{:name "h"} dl-h {:w [5 5 6]})         ; 5 authored twice

(Type ^{:name "Int"} os-Int)
(Type ^{:name "Str"} os-Str)
(Seq2 ^{:name "s"} os-s {:items [os-Int os-Str os-Int]})   ; Int repeats at 0 and 2

(Type ^{:name "Int"} o2-Int)
(Type ^{:name "Str"} o2-Str)
;; two clause GROUPS for one slot can no longer be authored (the map has one entry
;; per slot) but still arise internally (nested routing) — build via the IR to keep
;; the assembler's cross-group index covered
(def o2-s (sub/->InstanceValue ::Seq2 "s" nil {}
                             [{:rk :items :card :many :targets [#'o2-Int]}
                              {:rk :items :card :many :targets [#'o2-Str]}] false))

(Type ^{:name "Int"} ov-Int)
(Type ^{:name "Str"} ov-Str)
(HolderO ^{:name "h"} ov-h
  {:v [(OrdVal {:xs [ov-Int ov-Str]})
       (OrdVal {:xs [ov-Str ov-Int]})     ; reversed → distinct value
       (OrdVal {:xs [ov-Int ov-Str]})]})  ; same as first → dedup

(Type ^{:name "Int"} sv-Int)
(Type ^{:name "Str"} sv-Str)
(HolderS ^{:name "h"} sv-h
  {:v    [(SetVal {:xs [sv-Int sv-Str]})
          (SetVal {:xs [sv-Str sv-Int]})]          ; reversed → SAME value (set identity)
   :tags [sv-Int sv-Int sv-Str]})                  ; duplicate target collapses

(Holder ^{:name "h"} ev-h
  {:pair [(Pair {:fst 1 :snd 2})
          (Pair {:fst 1 :snd 2})]})   ; same content

(Holder ^{:name "h"} dv-h
  {:pair [(Pair {:fst 1 :snd 2})
          (Pair {:fst 3 :snd 4})]})

(Holder ^{:name "h"} vn-h {:pair [(Pair {:fst 1 :snd 2})]})

(Type ^{:name "Int"} vd-Int)
(Holder ^{:name "h"} vd-h
  {:box [(Boxed {:inner (Pair {:fst 1 :snd 2}) :ty vd-Int})
         (Boxed {:inner (Pair {:fst 1 :snd 2}) :ty vd-Int})]})

(Type ^{:name "Int"} de-Int)
(Type ^{:name "Str"} de-Str)
(Holder ^{:name "h"} de-h
  {:box [(Boxed {:inner (Pair {:fst 1 :snd 2}) :ty de-Int})
         (Boxed {:inner (Pair {:fst 1 :snd 2}) :ty de-Str})]})

(Holder ^{:name "h"} lr-bad-scalar-h {:pair [(Pair {:fst "no" :snd 2})]})   ; fst not an Int
(Plain ^{:name "p"} lr-p)
(Holder ^{:name "h"} lr-bad-target-h
  {:box [(Boxed {:inner (Pair {:fst 1 :snd 2})
                 :ty    lr-p})]})                                           ; ty targets a Plain, not a Type

(Carry ^{:name "c1"} pl-c1 {:text ["hi" [:a :b]]})
(Carry ^{:name "c2"} pl-c2 {:text "solo"})
(Carry ^{:name "c3"} pl-c3 {:text ["hi" '(fn [x] x)]})

;; ── positional body via (syntax …) — Task 1 ──────────────────────────────────
(defstructure PBody
  "A test structure whose syntax hook lifts a positional body into its one slot."
  {:thing [:? :int]}
  (syntax (fn [b] (if (map? b) b {:thing b}))))

(PBody pb-pos 42)                  ; def-emitting, positional body
(def pb-expr (PBody 42))           ; expression position, positional body
(PBody pb-map {:thing 7})          ; the map form still works

(defstructure PNoSyntax {:thing [:? :int]})   ; no hook → positional body must error

(Gate ^{:name "ok"}   en-ok {:state "open" :note "a"})
(Gate ^{:name "bad"}  en-bad {:state "ajar"})          ; not a member
(Gate ^{:name "miss"} en-miss)                         ; required state absent

;; ── tests ───────────────────────────────────────────────────────────────────

(deftest instantiation-emits-node-and-reified-relations
  (testing "an instance is a tagged Node; slot values are reified relations with labels"
    (let [db (a/assemble-vars [#'ie-Int #'ie-Str #'ie-f])]
      (is (= #{"Int" "Str"} (names-of db :Type)))
      (is (= #{"f"} (names-of db :Function)))
      (is (= #{["Int" "x"] ["Str" "y"]} (rels-of db "f" :takes))
          "two labelled :takes relations to the resolved Type nodes")
      (is (= #{["Int" ""]} (rels-of db "f" :gives))
          "one :gives relation to Int, no label"))))

(deftest well-formed-function-passes-check
  (testing "exactly one gives, all targets Types → no violations"
    (let [db (a/assemble-vars [#'wf-Int #'wf-f])]
      (is (empty? (laws-firing db :Function))))))

(deftest docstring-sets-instance-doc
  (testing "the docstring position → :entity/doc, not a slot"
    (let [db (a/assemble-vars [#'dc-Int #'dc-f])]
      (is (= "Builds the thing."
             (ffirst (d/q '[:find ?d :where [?e :entity/name "f"] [?e :entity/doc ?d]] db))))
      (is (empty? (laws-firing db :Function))
          "the docstring does not register as a slot or trip any law"))))

(deftest cardinality-one-catches-zero-and-several
  (testing "gives (one Type): zero and several are both violations"
    (let [db (a/assemble-vars [#'co-Int #'co-Str #'co-none #'co-several])
          firing (laws-firing db :Function)]
      (is (contains? firing "Function.gives requires exactly one (found none)"))
      (is (contains? firing "Function.gives requires exactly one (found several)")))))

(deftest target-type-law-catches-wrong-target
  (testing "a gives target that is not a Type is a violation"
    (let [db (a/assemble-vars [#'tt-Int #'tt-callee #'tt-bad])]
      (is (contains? (laws-firing db :Function)
                     "Function.gives target must be a Type")))))

(deftest acyclic-tree-passes-via-instantiation
  (testing "a forward-resolved child chain (leaf←mid←root) has no cycle violation"
    (let [db (a/assemble-vars [#'at-leaf #'at-mid #'at-root])]
      (is (= #{"leaf" "mid" "root"} (names-of db :Tree)))
      (is (empty? (laws-firing db :Tree))
          "the recursive no-cycle law runs clean on an acyclic chain"))))

(deftest recursive-law-catches-cycle
  (testing "the recursive no-cycle law detects a cycle in a hand-built db"
    ;; Cycles between named instances ARE authorable now (declare + var-capture), but
    ;; build the cyclic substrate directly here to exercise the recursive rule + offender
    ;; in isolation, decoupled from authoring.
    (let [tree-db (fn [nodes edges]
                    (-> (sub/create)
                        (d/db-with (for [n nodes]
                                     {:entity/id n :entity/name (name n) :structure/of ::Tree}))
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
  (testing "var-capture (declare) resolves forward references and cycles between instances"
    (let [db (a/assemble-vars [#'frc-a #'frc-b])]
      (is (= 2 (count (d/q '[:find ?r :where [?r :rel/kind :child]] db)))
          "both :child relations resolved (the forward ref is no longer skipped)")
      (is (contains? (laws-firing db :Tree) "no cycle through :child")
          "the authored cycle is real — caught by the no-cycle law"))))

(deftest unresolved-reference-is-a-compile-error
  (testing "a slot reference to an undefined var is a compile error (not a silent skip)"
    ;; var-capture means a typo'd reference fails to compile — the whole point of the
    ;; reframe. `:gives totally-undefined-xyz` expands to `(var totally-undefined-xyz)`.
    (is (thrown? clojure.lang.Compiler$CompilerException
          (eval '(fukan.canvas.core.structure-test/Function
                   {:gives totally-undefined-xyz}))))))

(deftest value-slot-stores-leaf-datom
  (testing "a scalar-typed slot stores a leaf :val/<key> datom on the node, not a relation"
    (let [db (a/assemble-vars [#'vs-b])]
      (is (= true  (ffirst (d/q '[:find ?v :where [?x :entity/name "b"] [?x :val/open ?v]] db))))
      (is (= "hi"  (ffirst (d/q '[:find ?v :where [?x :entity/name "b"] [?x :val/label ?v]] db))))
      (is (= 3     (ffirst (d/q '[:find ?v :where [?x :entity/name "b"] [?x :val/size ?v]] db))))
      (is (empty? (d/q '[:find ?r :where [?x :entity/name "b"] [?r :rel/from ?x]] db))
          "value slots emit no reified relations")
      (is (empty? (laws-firing db :Box))
          "a valid value-slot instance trips no law"))))

(deftest value-false-is-stored-not-absent
  (testing "a stored false Bool is a present value (no none-law), distinct from absent"
    (let [db (a/assemble-vars [#'vf-b])]
      (is (= false (ffirst (d/q '[:find ?v :where [?x :entity/name "b"] [?x :val/open ?v]] db)))
          "false is stored as a real value, not dropped")
      (is (not (contains? (laws-firing db :Box) "Box.open requires exactly one (found none)"))
          "a present false value does not trip the required none-law"))))

(deftest some-cardinality-requires-at-least-one
  (testing "(some Type): zero is a violation, one or more is clean"
    (let [zero (a/assemble-vars [#'sc-z-Int #'sc-z])
          one  (a/assemble-vars [#'sc-o-Int #'sc-o])]
      (is (contains? (laws-firing zero :NonEmpty)
                     "NonEmpty.item requires at least one (found none)"))
      (is (empty? (laws-firing one :NonEmpty))))))

(deftest free-law-is-scoped-to-its-owning-structure
  (testing "a free law on Tagged flags only Tagged instances, not a Plain with the same data"
    (let [db (a/assemble-vars [#'fl-Str #'fl-p #'fl-t])
          secret (filter #(= "no field labelled secret" (:law %)) (s/check db))]
      (is (= [::Tagged] (vec (distinct (map :structure secret)))))
      (is (= 1 (reduce + (map (comp count :offenders) secret)))
          "auto-scoping excludes the Plain; only the Tagged instance is flagged"))))

(deftest law-scope-can-target-another-structure
  (testing ":scope <tag> aims a free law's subject at a different structure"
    (let [db (a/assemble-vars [#'ls-Str #'ls-guard #'ls-p])]
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
    (let [db (-> (sub/create)
                 (d/db-with [{:entity/id "e1"} {:entity/id "e2"}
                             {:entity/id "n1"} {:entity/id "n2"}])
                 (d/db-with [{:rel/id "1" :rel/from [:entity/id "e1"] :rel/kind :h-from :rel/to [:entity/id "n1"]}
                             {:rel/id "2" :rel/from [:entity/id "e1"] :rel/kind :h-to   :rel/to [:entity/id "n2"]}
                             {:rel/id "3" :rel/from [:entity/id "e2"] :rel/kind :h-from :rel/to [:entity/id "n2"]}
                             {:rel/id "4" :rel/from [:entity/id "e2"] :rel/kind :h-to   :rel/to [:entity/id "n1"]}]))]
      (binding [s/*law-timeout-ms* 300]
        (is (some :timed-out? (s/check db))
            "the divergent Hang law is reported as timed-out and check still returns")))))

(deftest value-slots-well-formed-passes-check
  (testing "valid scalar values (and an absent optional) trip no law"
    (let [db (a/assemble-vars [#'vw-b])]
      (is (empty? (laws-firing db :Box))))))

(deftest value-type-law-catches-wrong-type
  (testing "a value whose literal fails its declared scalar type is caught"
    (let [db (a/assemble-vars [#'vt-b])]
      (is (contains? (laws-firing db :Box) "Box.open value must satisfy :boolean")))))

(deftest value-one-cardinality-catches-missing
  (testing "a required (one :T) value that is absent trips the none-law"
    (let [db (a/assemble-vars [#'vo-b])]
      (is (contains? (laws-firing db :Box) "Box.open requires exactly one (found none)")))))

(deftest free-law-over-a-value-fires
  (testing "an author free law can bind a stored value directly (predicates ride the engine)"
    (let [db (a/assemble-vars [#'fv-b])]
      (is (contains? (laws-firing db :Box) "size must be positive")))))

(deftest unknown-body-form-is-rejected
  (testing "defstructure rejects an unrecognized body form at macro-expansion"
    ;; macroexpand wraps the macro's ex-info in a CompilerException; walk to root.
    (let [msg (try (let [_ (macroexpand '(fukan.canvas.core.structure/defstructure Bad "d"
                                           (slt :x)))]
                     "no throw")
                   (catch Throwable e
                     (loop [t e] (if-let [c (ex-cause t)] (recur c) (ex-message t)))))]
      (is (re-find #"unknown body form" msg)))))

(deftest scalar-slot-rejects-multi-quantifiers
  (testing "a scalar-typed slot may only be bare (one) or [:? ...] (optional)"
    (let [msg (try (let [_ (macroexpand
                            '(fukan.canvas.core.structure/defstructure BadVal "d"
                               {:xs [:* :int]}))]
                     "no throw")
                   (catch Throwable e
                     (loop [t e] (if-let [c (ex-cause t)] (recur c) (ex-message t)))))]
      (is (re-find #"must be bare \(one\) or \[:\? \.\.\.\]" msg)))))

(deftest refined-slot-accepts-a-valid-value
  (testing "values the dialect accepts trip no law (required and optional refined slots)"
    (let [db (a/assemble-vars [#'en-ok])]
      (is (= "open" (ffirst (d/q '[:find ?v :where [?x :entity/name "ok"] [?x :val/state ?v]] db)))
          "a refined slot stores a plain :val leaf, like any scalar")
      (is (empty? (laws-firing db :Gate))))))

(deftest refined-slot-rejects-an-invalid-value
  (testing "a value the dialect rejects trips the refinement law"
    (let [db (a/assemble-vars [#'en-bad])]
      (is (contains? (laws-firing db :Gate)
                     "Gate.state value must satisfy [:enum \"open\" \"closed\"]")))))

(deftest refined-slot-one-cardinality-catches-missing
  (testing "a refined slot keeps the ordinary (one …) none-law"
    (let [db (a/assemble-vars [#'en-miss])]
      (is (contains? (laws-firing db :Gate)
                     "Gate.state requires exactly one (found none)")))))

(deftest refined-slot-rejects-multi-quantifiers
  (testing "a refined slot is a scalar — bare (one) or [:? ...] (optional) only"
    (let [msg (try (let [_ (macroexpand
                            '(fukan.canvas.core.structure/defstructure BadRefined "d"
                               {:xs [:* [:enum "a" "b"]]}))]
                     "no throw")
                   (catch Throwable e
                     (loop [t e] (if-let [c (ex-cause t)] (recur c) (ex-message t)))))]
      (is (re-find #"must be bare \(one\) or \[:\? \.\.\.\]" msg)))))

(deftest data-literal-reader-expands-and-dedups
  (testing "a value structure's :reader expands a literal arg into clauses; equal literals dedup"
    (let [db (a/assemble-vars [#'dl-h])]
      (is (= 2 (count-of db :Wrapped)) "5, 5, 6 → two Wrapped nodes (the two 5s dedup)")
      (is (= #{5 6} (set (map first (d/q '[:find ?n
                                           :where [?x :structure/of ::Wrapped] [?x :val/v ?n]]
                                         db))))))))

(deftest sequence-slot-captures-position
  (testing "a [:* T] slot records :rel/order from authoring position, recovering the sequence"
    (let [db (a/assemble-vars [#'os-Int #'os-Str #'os-s])]
      (is (= ["Int" "Str" "Int"]
             (->> (d/q '[:find ?o ?n
                         :where [?s :entity/name "s"] [?r :rel/from ?s] [?r :rel/kind :items]
                                [?r :rel/order ?o] [?r :rel/to ?t] [?t :entity/name ?n]]
                       db)
                  (sort-by first) (mapv second)))
          "sorting the :items relations by :rel/order recovers [Int Str Int]"))))

(deftest sequence-order-runs-across-clause-groups
  (testing "two IR clause groups for one slot order like one — the index runs across groups"
    (let [db (a/assemble-vars [#'o2-Int #'o2-Str #'o2-s])]
      (is (= [["Int" 0] ["Str" 1]]
             (->> (d/q '[:find ?n ?o :where [?s :entity/name "s"]
                                            [?r :rel/from ?s] [?r :rel/kind :items]
                                            [?r :rel/order ?o] [?r :rel/to ?t] [?t :entity/name ?n]] db)
                  (sort-by second) (mapv vec)))))))

(deftest sequence-value-identity-respects-order
  (testing "order is part of a sequence value's identity: [Int Str] ≠ [Str Int], and equals itself"
    (let [db (a/assemble-vars [#'ov-Int #'ov-Str #'ov-h])]
      (is (= 2 (count-of db :OrdVal))))))

(deftest set-slot-ignores-order-and-collapses-duplicates
  (testing "[:set T]: identity ignores order (reversed SetVals dedup) and duplicate targets collapse"
    (let [db (a/assemble-vars [#'sv-Int #'sv-Str #'sv-h])]
      (is (= 1 (count-of db :SetVal)) "(xs Int Str) and (xs Str Int) are ONE set value")
      (is (= 2 (count (d/q '[:find ?r :where [?h :entity/name "h"]
                                             [?r :rel/from ?h] [?r :rel/kind :tags]] db)))
          "(tags Int Int Str) collapses the duplicate Int to one relation")
      (is (empty? (d/q '[:find ?o :where [?r :rel/kind :tags] [?r :rel/order ?o]] db))
          "a :set slot records no :rel/order"))))

;; ── value-identity (content-deduped, inline-anonymous value nodes) ───────────

(deftest equal-value-instances-dedup-to-one-node
  (testing "two inline value instances with identical composition collapse to one node"
    (let [db (a/assemble-vars [#'ev-h])]
      (is (= 1 (count-of db :Pair)) "the two structurally-equal Pairs are one node"))))

(deftest distinct-value-instances-are-distinct-nodes
  (testing "value instances with different composition are different nodes"
    (let [db (a/assemble-vars [#'dv-h])]
      (is (= 2 (count-of db :Pair))))))

(deftest value-nodes-are-anonymous-and-ownerless
  (testing "a value node carries no :entity/name and is not a :child of any module"
    (let [db (a/assemble-vars [#'vn-h])
          pair-id (ffirst (d/q '[:find ?e :where [?e :structure/of ::Pair]] db))]
      (is (empty? (d/q '[:find ?n :in $ ?e :where [?e :entity/name ?n]] db pair-id))
          "no :entity/name")
      (is (empty? (d/q '[:find ?r :in $ ?e :where [?r :rel/kind :child] [?r :rel/to ?e]] db pair-id))
          "not a :child of any module"))))

(deftest value-dedup-folds-in-entity-identity-and-nesting
  (testing "Boxed over the same inner Pair + same Type entity dedups (recursively)"
    (let [db (a/assemble-vars [#'vd-Int #'vd-h])]
      (is (= 1 (count-of db :Boxed)) "the two Boxeds collapse")
      (is (= 1 (count-of db :Pair))  "their inner Pairs collapse too"))))

(deftest distinct-entity-target-makes-distinct-value
  (testing "same scalar composition but a different entity target → distinct value nodes"
    (let [db (a/assemble-vars [#'de-Int #'de-Str #'de-h])]
      (is (= 2 (count-of db :Boxed)) "different :ty entity → different Boxed")
      (is (= 1 (count-of db :Pair))  "but the identical inner Pairs still share"))))

(deftest laws-run-over-value-nodes
  (testing "slot laws fire on deduped value nodes (type-check + relation target-type)"
    (let [bad-scalar (a/assemble-vars [#'lr-bad-scalar-h])
          bad-target (a/assemble-vars [#'lr-p #'lr-bad-target-h])]
      (is (contains? (set (map :law (s/check bad-scalar))) "Pair.fst value must satisfy :int"))
      (is (contains? (set (map :law (s/check bad-target))) "Boxed.ty target must be a Type")))))

(deftest programmatic-emission-builds-a-db
  (testing "assemble-instances lets code emit instances from runtime data"
    (let [db (a/assemble-instances
               (for [n ["a" "b"]]
                 [n (sub/->InstanceValue ::Carry n nil {:val/text n} [] false)]))]
      (is (= #{"a" "b"}
             (set (map first (d/q '[:find ?n :where [?e :structure/of ::Carry] [?e :entity/name ?n]] db))))
          "both instances were emitted programmatically")
      (is (= "a" (:val/text (d/entity db (ffirst (d/q '[:find ?e :where [?e :entity/name "a"]] db)))))
          "scalar slot value stored"))))

(deftest payload-slot-stores-companion-data
  (testing "a scalar slot's 2nd clause arg is stored under its :payload attr"
    (let [db (a/assemble-vars [#'pl-c1 #'pl-c2 #'pl-c3])
          e1 (d/entity db (ffirst (d/q '[:find ?e :where [?e :entity/name "c1"]] db)))
          e2 (d/entity db (ffirst (d/q '[:find ?e :where [?e :entity/name "c2"]] db)))
          e3 (d/entity db (ffirst (d/q '[:find ?e :where [?e :entity/name "c3"]] db)))]
      (is (= "hi" (:val/text e1)))
      (is (= [:a :b] (:val/extra e1)) "the 2nd arg is captured as the payload")
      (is (= "solo" (:val/text e2)))
      (is (nil? (:val/extra e2)) "no 2nd arg → no payload")
      (is (= '(fn [x] x) (:val/extra e3))
          "a payload code-form is stored as data (the quoted form)"))))

(deftest positional-body-routes-through-syntax
  (testing "a syntax-bearing structure accepts a lone non-map body (both surfaces)"
    (let [db (a/assemble-vars [#'pb-pos #'pb-expr #'pb-map])
          thing-of (fn [nm] (ffirst (d/q '[:find ?v :in $ ?nm
                                           :where [?e :entity/name ?nm] [?e :val/thing ?v]] db nm)))]
      (is (= 42 (thing-of "pb-pos")))
      (is (= 42 (thing-of "pb-expr")))
      (is (= 7  (thing-of "pb-map"))))))

(deftest positional-body-without-syntax-errors
  (testing "a structure with no syntax hook still rejects a non-map body"
    (let [msg (try (let [_ (macroexpand '(fukan.canvas.core.structure-test/PNoSyntax pn 42))]
                     "no throw")
                   (catch Throwable e
                     (loop [t e] (if-let [c (ex-cause t)] (recur c) (ex-message t)))))]
      (is (re-find #"an instance is" msg)))))

(deftest value-literal-expands-to-deduped-instancevalue
  (testing "the kernel value-literal primitive builds a ^:value InstanceValue from a reader literal"
    (let [iv (s/value-literal->iv :lib.type.malli/Schema [:map [:a :string] [:b :int]])]
      (is (sub/instance-value? iv) "returns an InstanceValue")
      (is (= :lib.type.malli/Schema (:tag iv)))
      (is (= (sub/value-content-key iv)
             (sub/value-content-key (s/value-literal->iv :lib.type.malli/Schema [:map [:a :string] [:b :int]])))
          "structurally-equal literals get equal content keys (dedup)"))))

;; ── syntactic type-form classification (Phase 3 / Task 3.1) ─────────────────
;; The kernel classifies a slot target purely syntactically: a symbol → structure-ref;
;; a keyword or vector → a type form routed through the dialect. The kernel holds NO
;; scalar-predicate map; EVERY value-slot check goes through value-valid?.

(defstructure SyntClass
  "Test fixture: a structure with a plain scalar slot (:int) and a ref slot."
  {:n   [:? :int]
   :ref [:? PBody]})

(SyntClass ^{:name "Bad"} sc-bad {:n "not-an-int"})

(deftest scalar-checked-through-dialect-not-kernel
  (testing "a value-slot is checked via the dialect even though the kernel knows no scalar predicates"
    (let [bad (a/assemble-vars [#'sc-bad])]
      (is (contains? (set (map :law (s/check bad)))
                     "SyntClass.n value must satisfy :int")
          "the generated law routes :int through value-valid?, not a kernel predicate"))))
