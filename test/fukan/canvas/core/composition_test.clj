(ns fukan.canvas.core.composition-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s :refer [defstructure]]))

;; ── product fixtures: a law-only facet + two includers ──────────────────────
(defstructure Linked
  "Facet (law-only): a node must participate in some directed relation."
  (law "no isolated node"
    :offenders '[?n]
    :where '[(not [?o :rel/from ?n]) (not [?i :rel/to ?n])]))

(defstructure Spoke
  "Includes the Linked facet; connected only by being pointed at."
  (includes Linked))

(defstructure Hub
  "Includes the Linked facet; points at Spokes."
  (includes Linked)
  (slot :spokes (many Spoke)))

(deftest includes-parsed-onto-sdef
  (testing "(includes …) collects facet tags onto the structure def"
    (is (= [::Linked] (:includes (s/structure-by-tag ::Hub))))
    (is (= [::Linked] (:includes (s/structure-by-tag ::Spoke))))
    (is (= []         (:includes (s/structure-by-tag ::Linked))) "no includes → empty")))

(deftest derives-inclusion-rules
  (testing "each (includes Facet) yields a rule (Facet ?e) ⇐ (Concept ?e)"
    (let [rs (s/vocab-rules)]
      (is (some #(= % '[(Linked ?e) (Hub ?e)])   rs) "Hub includer rule")
      (is (some #(= % '[(Linked ?e) (Spoke ?e)]) rs) "Spoke includer rule"))))

(def lk-spoke (Spoke))                  ; pointed at by lk-hub → has incoming → connected
(def lk-hub   (Hub (spokes lk-spoke)))  ; has outgoing → connected
(def lk-iso   (Hub))                    ; no edges → isolated → OFFENDER of Linked's law

(defn- offenders-of [db law-desc]
  (->> (s/check db)
       (filter #(= law-desc (:law %)))
       (mapcat :offenders) (map first)
       (map #(:entity/name (d/entity db %)))
       set))

(deftest facet-law-ranges-over-includers
  (testing "a law on the Linked facet fires over Hub AND Spoke instances, via the inclusion rule"
    (let [db (a/assemble-vars [#'lk-spoke #'lk-hub #'lk-iso])]
      (is (= #{"lk-iso"} (offenders-of db "no isolated node"))
          "only the edge-less Hub is isolated; the connected Hub and the pointed-at Spoke are not"))))

(deftest facet-membership-is-entailed
  (testing "(Linked ?e) entails over every includer instance"
    (let [db    (a/assemble-vars [#'lk-spoke #'lk-hub #'lk-iso])
          names (set (d/q '[:find [?nm ...] :in $ %
                            :where (Linked ?e) [?e :entity/name ?nm]]
                          db (s/vocab-rules)))]
      (is (= #{"lk-spoke" "lk-hub" "lk-iso"} names)))))

;; ── realization fixtures ────────────────────────────────────────────────────
(defstructure Note
  "A base concept with a Bool discriminant."
  (slot :flag (one :Bool)))

(defstructure Flagged
  "A realized concept: a Note whose flag is true — derived membership, NO constructor."
  (realized-as '[(Note ?e) [?e :val/flag true]]))

(deftest realized-as-parsed-and-no-constructor
  (testing "(realized-as …) registers the where-clauses and emits no constructor var"
    (is (= '[(Note ?e) [?e :val/flag true]]
           (:realized-as (s/structure-by-tag ::Flagged))))
    (is (not (contains? (ns-interns 'fukan.canvas.core.composition-test) 'Flagged))
        "a realized concept defines no constructor (it is never instantiated)")))

(def nt-on  (Note (flag true)))
(def nt-off (Note (flag false)))

(deftest realized-membership-is-derived
  (testing "(Flagged ?e) returns exactly the flag=true Notes"
    (let [db    (a/assemble-vars [#'nt-on #'nt-off])
          names (set (d/q '[:find [?nm ...] :in $ %
                            :where (Flagged ?e) [?e :entity/name ?nm]]
                          db (s/vocab-rules)))]
      (is (= #{"nt-on"} names)))))

;; compound: a realized concept whose rule references another realized rule (the Inspect shape)
(defstructure Holder
  "A base concept holding a Note."
  (slot :holds (one Note)))
(defstructure FlaggedHolder
  "Realized: a Holder holding a Flagged Note — a rule that references another realized rule."
  (realized-as '[(Holder ?e) [?r :rel/from ?e] [?r :rel/kind :holds] [?r :rel/to ?n] (Flagged ?n)]))

(def hold-on  (Holder (holds nt-on)))
(def hold-off (Holder (holds nt-off)))

(deftest realized-rule-references-another-realized-rule
  (testing "FlaggedHolder realizes via Flagged — the inspect-over-signal composition"
    (let [db    (a/assemble-vars [#'nt-on #'nt-off #'hold-on #'hold-off])
          names (set (d/q '[:find [?nm ...] :in $ %
                            :where (FlaggedHolder ?e) [?e :entity/name ?nm]]
                          db (s/vocab-rules)))]
      (is (= #{"hold-on"} names)))))

;; ── coproduct fixtures: a closed sum + a totality law ───────────────────────
(defstructure Sum
  "A closed coproduct discriminated by :kind; totality asserted by a law."
  (slot :kind (one :String))
  (law "every Sum is a VariantA or a VariantB"
    :offenders '[?s]
    :where '[(not (VariantA ?s)) (not (VariantB ?s))]))
(defstructure VariantA "Realized variant: kind = a." (realized-as '[(Sum ?e) [?e :val/kind "a"]]))
(defstructure VariantB "Realized variant: kind = b." (realized-as '[(Sum ?e) [?e :val/kind "b"]]))

(def sum-a (Sum (kind "a")))
(def sum-b (Sum (kind "b")))
(def sum-c (Sum (kind "c")))   ; neither variant → totality violation

(deftest closed-sum-totality-has-teeth
  (testing "a Sum matching no variant is caught by the totality law"
    (let [db (a/assemble-vars [#'sum-a #'sum-b #'sum-c])]
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
  (testing "(realized-as …) may not be combined with slots/laws/includes/reader/^:value"
    (is (throws-realized-msg?
          '(fukan.canvas.core.structure/defstructure BadRealized "d"
             (realized-as '[(Note ?e)])
             (slot :x (one :Bool))))
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
