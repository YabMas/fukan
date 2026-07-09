(ns fukan.canvas.core.composition-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.query :as cq]
            [fukan.cozo.build :as build]
            [fukan.cozo.law]
            [fukan.canvas.core.lens :as lens]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [canvas.vocab.code.module]
            [canvas.vocab.code.operation :refer [Operation]]))

(defn- offenders-of [db law-desc]
  (->> (s/check db)
       (filter #(= law-desc (:law %)))
       (mapcat :offenders) (map first)
       (map #(:entity/name (cq/entity db %)))
       set))

;; ── realization fixtures ────────────────────────────────────────────────────
(defstructure Note
  "A base concept with a Bool discriminant."
  {:flag :boolean})

(defstructure Flagged
  "A realized concept: a Note whose flag is true — derived membership, NO constructor."
  (realized-as '[(Note ?e) [?e :val/flag true]]))

(deftest realized-as-parsed-and-no-constructor
  (testing "(realized-as …) registers the where-clauses and emits no constructor var"
    (is (= '[(Note ?e) [?e :val/flag true]]
           (:realized-as (s/structure-by-tag ::Flagged))))
    (is (not (contains? (ns-interns 'fukan.canvas.core.composition-test) 'Flagged))
        "a realized concept defines no constructor (it is never instantiated)")))

(Note nt-on  {:flag true})
(Note nt-off {:flag false})

(deftest realized-membership-is-derived
  (testing "(Flagged ?e) returns exactly the flag=true Notes"
    (let [db    (build/vars->cozo [#'nt-on #'nt-off])
          names (set (cq/q '[:find [?nm ...] :in $ %
                            :where (Flagged ?e) [?e :entity/name ?nm]]
                          db (s/vocab-rules)))]
      (is (= #{"nt-on"} names)))))

;; compound: a realized concept whose rule references another realized rule (the Inspect shape)
(defstructure Holder
  "A base concept holding a Note."
  {:holds Note})
(defstructure FlaggedHolder
  "Realized: a Holder holding a Flagged Note — a rule that references another realized rule."
  (realized-as '[(Holder ?e) [?r :rel/from ?e] [?r :rel/kind :holds] [?r :rel/to ?n] (Flagged ?n)]))

(Holder hold-on  {:holds nt-on})
(Holder hold-off {:holds nt-off})

(deftest realized-rule-references-another-realized-rule
  (testing "FlaggedHolder realizes via Flagged — the inspect-over-signal composition"
    (let [db    (build/vars->cozo [#'nt-on #'nt-off #'hold-on #'hold-off])
          names (set (cq/q '[:find [?nm ...] :in $ %
                            :where (FlaggedHolder ?e) [?e :entity/name ?nm]]
                          db (s/vocab-rules)))]
      (is (= #{"hold-on"} names)))))

;; ── coproduct fixtures: a closed sum + a totality law ───────────────────────
(defstructure Sum
  "A closed coproduct discriminated by :kind; totality asserted by a law."
  {:kind :string}
  (law "every Sum is a VariantA or a VariantB"
    :offenders '[?s]
    :where '[(not (VariantA ?s)) (not (VariantB ?s))]))
(defstructure VariantA "Realized variant: kind = a." (realized-as '[(Sum ?e) [?e :val/kind "a"]]))
(defstructure VariantB "Realized variant: kind = b." (realized-as '[(Sum ?e) [?e :val/kind "b"]]))

(Sum sum-a {:kind "a"})
(Sum sum-b {:kind "b"})
(Sum sum-c {:kind "c"})   ; neither variant → totality violation

(deftest closed-sum-totality-has-teeth
  (testing "a Sum matching no variant is caught by the totality law"
    (let [db (build/vars->cozo [#'sum-a #'sum-b #'sum-c])]
      (is (= #{"sum-c"} (offenders-of db "every Sum is a VariantA or a VariantB"))))))

;; ── realized-concept guard ──────────────────────────────────────────────────

(defn- throws-realized-msg?
  "True when evaluating `form` throws an exception (possibly wrapped in a
   CompilerException) whose cause chain contains a message matching #\"realized\"."
  [form]
  (try (eval form) false
       (catch Exception e
         (boolean (some #(re-find #"realized" (or (.getMessage %) ""))
                        (take-while some? (iterate #(.getCause %) e)))))))

(deftest realized-concept-rejects-extra-clauses
  (testing "(realized-as …) may not be combined with slots/laws/reader/^:value"
    (is (throws-realized-msg?
          '(fukan.canvas.core.structure/defstructure BadRealized "d"
             (realized-as '[(Note ?e)])
             {:x :boolean}))
        "realized-as + slot is rejected")
    (is (throws-realized-msg?
          '(fukan.canvas.core.structure/defstructure BadRealized2 "d"
             (realized-as '[(Note ?e)])
             (law "nope" :offenders '[?e] :where '[[?e :x 1]])))
        "realized-as + law is rejected")
    (is (throws-realized-msg?
          '(fukan.canvas.core.structure/defstructure BadRealized3 "d"
             (realized-as '[(Note ?e)])
             (realized-as '[(Note ?e)])))
        "multiple realized-as is rejected")))

;; ── transitive closure fixtures: a delegates chain a → b → c ────────────────

(declare op-b op-c)
(Operation ^{:name "comp-a"} op-a {:delegates [op-b]})
(Operation ^{:name "comp-b"} op-b {:delegates [op-c]})
(Operation ^{:name "comp-c"} op-c {:performs [:io]})
(Operation ^{:name "comp-throws"} op-throws {:performs [:throws]})

(defn- names [db eids] (set (map #(:entity/name (cq/entity db %)) eids)))

(deftest delegates-closure-rule-is-generated
  (testing "marking :delegates :transitive yields a delegates+ transitive-closure rule"
    (let [db    (build/vars->cozo [#'op-a #'op-b #'op-c])
          reach (fn [n] (set (cq/q '[:find [?b ...] :in $ % ?an
                                     :where [?a :entity/name ?an] (delegates+ ?a ?b)]
                                   db (s/vocab-rules) n)))]
      (is (= #{"comp-b" "comp-c"} (names db (reach "comp-a"))) "a reaches b and c transitively")
      (is (= #{"comp-c"}          (names db (reach "comp-b"))) "b reaches c")
      (is (empty? (reach "comp-c")) "c delegates to nothing"))))

(deftest effectful-is-a-property-of-directly-effectful-ops
  (testing "the effectful defrelation selects ops that directly perform any effect"
    (let [db  (build/vars->cozo [#'op-a #'op-b #'op-c #'op-throws])
          eff (set (cq/q '[:find [?o ...] :in $ % :where (effectful ?o)] db (s/vocab-rules)))]
      (is (= #{"comp-c" "comp-throws"} (names db eff))
          ":throws is an effect; consumers that only care about a subset filter downstream"))))

(deftest via-composes-a-property-along-a-transitive-relation
  (testing "(via :delegates Operation effectful) = ops that transitively delegate to a directly-effectful op"
    (let [db    (build/vars->cozo [#'op-a #'op-b #'op-c])
          focus (lens/focus-nodes db '[(via :delegates Operation effectful)])]
      (is (= #{"comp-a" "comp-b"} (names db focus))
          "a and b reach the effectful c through delegation; c reaches no effectful op via delegation"))))

(deftest the-extracted-calls-relation-marked-transitive-earns-its-closure
  (testing "the :calls slot (extracted-actuals, never authored) marked :transitive gives `terms-of` a calls+ closure"
    (let [db (build/maps->cozo
              [{:entity/id "ca" :structure/of :canvas.vocab.code.operation/Operation :entity/name "ca"}
               {:entity/id "cb" :structure/of :canvas.vocab.code.operation/Operation :entity/name "cb"}
               {:entity/id "cc" :structure/of :canvas.vocab.code.operation/Operation :entity/name "cc"}]
              [{:rel/id "r-ab" :rel/from [:entity/id "ca"] :rel/kind :calls :rel/to [:entity/id "cb"]}
               {:rel/id "r-bc" :rel/from [:entity/id "cb"] :rel/kind :calls :rel/to [:entity/id "cc"]}])
          reach (fn [n] (set (map first (cq/q '[:find ?bn :in $ % ?an
                                                :where [?a :entity/name ?an] (calls+ ?a ?b) [?b :entity/name ?bn]]
                                              db (s/vocab-rules) n))))]
      (is (= #{"cb" "cc"} (reach "ca")) "ca reaches cb and cc transitively via the calls graph")
      (is (= #{"cc"} (reach "cb")) "cb reaches cc")
      (is (empty? (reach "cc")) "cc calls nothing"))))

(deftest path-clause-composes-relations-with-star-closure
  (testing "(path ?op [:calls* :performs] ?effect) composes zero-or-more calls with performs"
    (let [db (build/maps->cozo
              [{:entity/id "pa" :structure/of :canvas.vocab.code.operation/Operation :entity/name "pa"}
               {:entity/id "pb" :structure/of :canvas.vocab.code.operation/Operation :entity/name "pb"}
               {:entity/id "pc" :structure/of :canvas.vocab.code.operation/Operation :entity/name "pc"}
               {:entity/id "io" :structure/of :canvas.vocab.code.effect/Effect :val/name "io"}]
              [{:rel/id "p-ab" :rel/from [:entity/id "pa"] :rel/kind :calls :rel/to [:entity/id "pb"]}
               {:rel/id "p-bc" :rel/from [:entity/id "pb"] :rel/kind :calls :rel/to [:entity/id "pc"]}
               {:rel/id "p-cio" :rel/from [:entity/id "pc"] :rel/kind :performs :rel/to [:entity/id "io"]}])
          reached (set (cq/q (vec (concat '[:find [?opn ...] :in $ % :where]
                                           (s/expand-clauses '[(path ?op [:calls* :performs] ?effect)
                                                               [?op :entity/name ?opn]
                                                               [?effect :val/name "io"]])))
                              db (s/vocab-rules)))]
      (is (= #{"pa" "pb" "pc"} reached)
          ":calls* includes the zero-hop operation that directly performs the effect"))))

(deftest query-expands-path-after-parameter-substitution
  (testing "a path endpoint can be supplied through :in without corrupting the or-join helper"
    (let [db (build/maps->cozo
              [{:entity/id "qa" :structure/of :canvas.vocab.code.operation/Operation :entity/name "qa"}
               {:entity/id "qb" :structure/of :canvas.vocab.code.operation/Operation :entity/name "qb"}
               {:entity/id "qio" :structure/of :canvas.vocab.code.effect/Effect :val/name "io"}]
              [{:rel/id "q-ab" :rel/from [:entity/id "qa"] :rel/kind :calls :rel/to [:entity/id "qb"]}
               {:rel/id "q-bio" :rel/from [:entity/id "qb"] :rel/kind :performs :rel/to [:entity/id "qio"]}])
          qa (ffirst (cq/q '[:find ?o :where [?o :entity/name "qa"]] db))]
      (is (= #{"io"}
             (set (cq/q '[:find [?en ...] :in $ ?op
                          :where (path ?op [:calls* :performs] ?effect)
                                 [?effect :val/name ?en]]
                        db qa)))))))

(deftest lens-focus-expands-path-clauses
  (testing "lens selections can compose a path without minting a named reaches-effect relation"
    (let [db (build/vars->cozo [#'op-a #'op-b #'op-c])
          focus (lens/focus-nodes db '[(Operation ?n)
                                       (path ?n [:delegates* :performs] ?effect)
                                       [?effect :val/name "io"]])]
      (is (= #{"comp-a" "comp-b" "comp-c"} (names db focus))
          "delegates* includes the directly effectful operation as the zero-hop case"))))

(defstructure PathMark
  "A test-only path target."
  {:kind :string})

(defstructure PathNode
  "A test-only node with a transitive edge and a terminal mark."
  {:next [:* {:transitive true} PathNode]
   :marks [:* PathMark]})

(PathMark pmark-io {:kind "io"})
(declare pnode-b pnode-c)
(PathNode pnode-a {:next [pnode-b]})
(PathNode pnode-b {:next [pnode-c]})
(PathNode pnode-c {:marks [pmark-io]})

(defstructure PathAudit
  "Flags test nodes that reach a mark through relation composition."
  (law "operation reaches io"
    :scope ::PathNode
    :offenders '[?o]
    :where '[(path ?o [:next* :marks] ?mark)
             [?mark :val/kind "io"]]))

(deftest laws-expand-path-clauses
  (testing "laws use the same path composition authoring layer as readings"
    (let [db (build/vars->cozo [#'pmark-io #'pnode-a #'pnode-b #'pnode-c])
          offenders (->> (s/check db)
                         (filter #(= "operation reaches io" (:law %)))
                         (mapcat :offenders)
                         (map first)
                         (names db)
                         set)]
      (is (= #{"pnode-a" "pnode-b" "pnode-c"} offenders)))))
