(ns fukan.canvas.core.refinement-test
  "Refinement: a sort declares it is a KIND OF another, and everything scoped to the genus sees it.

   Membership is answered by RULE, not by identity. A species' instance carries exactly one stored
   tag — its own — and the genus's kind rule gains one body per species, so the substrate stores
   what it always stored and the genus is answered by a rule the lowering already knew how to
   emit. What a refinement may DECLARE follows from its own membership: with the tag stored on its
   instances it is an ordinary sort that happens to be a species, and with `(realized-as …)`
   beside it its members are whatever its rule derives among those the genus already admits."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.query :as cq]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            ;; the malli dialect: the generated value-slot laws route every scalar check through it
            [fukan.common.typing.malli]
            [fukan.canvas.core.structure :as s :refer [defstructure]]))

;; ── the genus and two species ───────────────────────────────────────────────

(defstructure Routine
  "Fixture genus: anything with a body that runs."
  {:sig [:? :string]})

(defstructure Op
  "Fixture species, stored membership: inherits :sig, adds one of its own."
  {:note [:? :string]}
  (sub Routine))

(defstructure Other
  "Fixture: the same shape as a species, refining nothing — what a genus must NOT see."
  {:sig [:? :string]})

(defstructure Holder
  "Fixture: a slot typed to the GENUS, which is what a species has to be admitted by."
  {:uses [:* Routine]})

(defstructure RoutineAudit
  "Fixture: a law scoped to the genus, flagging every member."
  (law "routine-flag" {:scope ::Routine :offenders [?c] :where []}))

;; a DERIVED species: its body would derive Other's instance too, and the genus confines it
(defstructure Flagged
  "Fixture species, derived membership: the flagged Routines."
  (realized-as [[?e :val/sig "flag"]])
  (sub Routine)
  (law "flagged-flag" {:offenders [?f] :where []}))

(Routine ^{:name "bare"}  bare-callable {:sig "b"})
(Op       ^{:name "op"}    an-op         {:sig "flag" :note "n"})
(Other    ^{:name "other"} an-other      {:sig "flag"})
(Holder   ^{:name "held"}  a-holder      {:uses [an-op]})

(defn- db [] (build/vars->cozo [#'bare-callable #'an-op #'an-other #'a-holder]))

(defn- names [d q] (set (map first (cq/q q d))))

(defn- refuses?
  "True when macroexpanding `form` throws with a message matching `re`. A defstructure refuses at
   macroexpansion, so the form has to be `eval`'d — and `eval` wraps the ex-info in a
   CompilerException, which is why this walks the cause chain rather than reading one message."
  [re form]
  (try (eval form) false
       (catch Exception e
         (boolean (some #(re-find re (or (.getMessage %) ""))
                        (take-while some? (iterate #(.getCause %) e)))))))

(defn- offenders-of
  "The entity names a law flagged, by its description — `check-structural` rather than `check`, so
   an unrelated unsupported law elsewhere in the registry cannot decide this test."
  [d desc]
  (->> (law/check-structural d)
       (filter #(= desc (:law %)))
       (mapcat :offenders)
       (map (comp :entity/name #(cq/entity d %) first))
       set))

;; ── membership ──────────────────────────────────────────────────────────────

(deftest a-species-instance-carries-one-tag
  (testing "the substrate stores what it always stored: the species' own tag, and nothing else"
    (let [d (db)]
      (is (= #{"fukan.canvas.core.refinement-test/Op"}
             (set (map second (cq/q '[:find ?e ?t :where [?e :entity/name "op"] [?e :structure/of ?t]] d))))
          "one :structure/of value — the genus is answered by a rule, never by a second tag"))))

(deftest a-genus-holds-its-species
  (testing "(is ?c Routine) reaches the species without naming it"
    (let [d (db)]
      (is (= #{"bare" "op"}
             (names d '[:find ?n :where (is ?c :fukan.canvas.core.refinement-test/Routine)
                                        [?c :entity/name ?n]]))
          "the genus's own instance and its species', and not the same-shaped Other"))))

(deftest a-genus-scoped-law-sees-a-species
  (testing "a law scoped to the genus fires on a species instance without naming the species"
    (is (= #{"bare" "op"} (offenders-of (db) "routine-flag")))))

(deftest a-genus-typed-slot-admits-a-species
  (testing "the generated target check asks the algebra, so a genus-typed slot takes a species"
    ;; the check is written as a `not` per admissible target: with a literal triple it would have
    ;; refused `op`, whose only stored tag is the species'. The two halves of one idea — what a
    ;; genus-scoped law sees, and what a genus-typed slot admits — have to agree.
    (is (empty? (offenders-of (db) "Holder.uses target must be a Routine")))))

;; ── what a species may declare ──────────────────────────────────────────────

(deftest a-species-inherits-and-adds
  (testing "the genus's slots are the species' too, and it may add its own"
    (let [d (db)]
      (is (= #{"flag"} (names d '[:find ?v :where [?e :entity/name "op"] [?e :val/sig ?v]]))
          "an inherited slot is authored and stored like any other")
      (is (= #{"n"} (names d '[:find ?v :where [?e :entity/name "op"] [?e :val/note ?v]]))
          "and the species' own slot beside it")))
  (testing "the declaration keeps its own slots apart from what it inherits"
    (is (= [:note] (mapv :rel (s/authored-slots (s/structure-by-tag ::Op)))))
    (is (= [:sig :note] (mapv :rel (:slots (s/structure-by-tag ::Op))))
        "effective slots are inherited-then-own, so lowering and law generation are complete")))

(deftest a-species-generates-laws-for-what-it-inherits
  (testing "one law per species, under the species' own concrete tag"
    ;; not redundancy: a generated law names the sort it is scoped to, and a species' instances
    ;; carry the species' tag, so this is what checking each species costs.
    (is (some #(= "Op.sig value must satisfy :string" (:desc %))
              (s/laws-of (s/structure-by-tag ::Op))))))

;; ── a derived refinement is confined to what the genus admits ───────────────

(deftest a-derived-species-cannot-conjure-members
  (testing "its body is conjoined with the genus's admissible stored tags"
    (let [d (db)]
      ;; the body `[?e :val/sig "flag"]` matches `other` too. Unconjoined, `other` would become a
      ;; member of Routine — seen by genus-scoped laws and admitted by genus-typed slots — while
      ;; carrying none of the genus's slots and generating none of its laws.
      (is (= #{"op"} (names d '[:find ?n :where (is ?e :fukan.canvas.core.refinement-test/Flagged)
                                                [?e :entity/name ?n]])))
      (is (= #{"bare" "op"}
             (names d '[:find ?n :where (is ?c :fukan.canvas.core.refinement-test/Routine)
                                        [?c :entity/name ?n]]))
          "so the genus stays what it was — `other` never enters through the derived species"))))

(deftest a-derived-species-law-scopes-through-its-membership-rule
  (testing "its own law fires on its members, which is the whole of what it has to scope by"
    (is (= #{"op"} (offenders-of (db) "flagged-flag")))))

(deftest a-derived-species-declares-laws-and-no-slots
  (is (refuses? #"may not also"
                '(fukan.canvas.core.structure/defstructure BadDerivedSpecies "d"
                   (realized-as [[?e :val/sig "x"]])
                   (sub :fukan.canvas.core.refinement-test/Routine)
                   {:extra [:? :string]}))
      "nothing carries a derived sort's tag, so nothing could hold a slot's value"))

;; ── what the parse refuses ──────────────────────────────────────────────────

(deftest restating-an-inherited-slot-is-refused
  (is (refuses? #"already declared by"
                '(fukan.canvas.core.structure/defstructure Restater "d"
                   {:sig [:? :string]}
                   (sub :fukan.canvas.core.refinement-test/Routine)))
      "restating a genus slot is the duplication refinement removes, and permitting it would let a
       species silently weaken a constraint its genus states"))

(deftest a-refinement-cycle-is-refused-at-parse
  (testing "the slot-inheritance walk cannot terminate on a cycle, so it refuses one"
    ;; registered directly: reaching a cycle through defstructure alone would need the genus to be
    ;; redefined under a species that already names it, which is a REPL move, not a file.
    (s/register-structure! {:tag ::CycA :ns (str *ns*) :slots [] :laws [] :sub ::CycB})
    (s/register-structure! {:tag ::CycB :ns (str *ns*) :slots [] :laws [] :sub ::CycA})
    (is (refuses? #"refinement cycle"
                  '(fukan.canvas.core.structure/defstructure Descendant "d"
                     {}
                     (sub :fukan.canvas.core.refinement-test/CycA)))
        "refused where the chain is walked — before any seam can read an incoherent registry")))

(deftest a-genus-must-name-a-sort-that-resolves
  (is (refuses? #"no structure named"
                '(fukan.canvas.core.structure/defstructure NoSuchGenus "d" {} (sub Nowhere)))))
