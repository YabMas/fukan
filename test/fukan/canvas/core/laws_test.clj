(ns fukan.canvas.core.laws-test
  "Law combinators: each shape's positive + negative case, including negation over a
   WHOLLY-EMPTY relation — the case datascript's inline not-join got wrong (forcing the
   old hand-rolled negation rules) and Cozo's stratified not-join handles directly."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            ;; loaded for its side-effect: registers the Cozo check engine so law/check dispatches to it
            [fukan.cozo.law :as law]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            ;; loaded for their side-effect: register the correspondence demand laws + the Fn/Ns codomain
            ;; structures the self-model correspondence maps Operation/Module INTO
            [fukan.common.extraction.clojure.operation]
            [fukan.common.extraction.clojure.module]))

(defn- laws [db] (set (map :law (law/check db))))

;; ── fixtures ──────────────────────────────────────────────────────────────────

(defstructure LDoc
  "matched-by subject: every flagged doc must be approved by a Reviewer."
  {:flag [:? :boolean]}
  (law "every flagged doc is approved by a reviewer"
    (matched-by :approves :from LReviewer :when {:flag true})))

(defstructure LReviewer "Its approvals count."     {:approves [:* LDoc]})
(defstructure LBot      "Its approvals do NOT."    {:approves [:* LDoc]})

(defstructure LRef
  "has subject: a \"ref\"-kinded instance must carry :names."
  {:kind  :string
   :names [:? LDoc]}
  (law "a ref names a target" (has :names :when {:kind "ref"})))

(defstructure LProj
  "has-any subject: maps or wraps — never neither."
  {:maps  [:* LDoc]
   :wraps [:? LProj]}
  (law "a proj maps or wraps" (has-any :maps :wraps)))

(defstructure LSrc "target-condition target." {:polarity :string})
(defstructure LLift
  "target subject: may only lift code-up sources."
  {:lifts LSrc}
  (law "lifts only code-up" (target :lifts {:polarity "code-up"})))

(defstructure LOwned
  "at-most-one subject: a unique owner."
  (law "at most one owner" (at-most-one :owns)))
(defstructure LOwner "owner" {:owns [:* LOwned]})

;; ── matched-by ────────────────────────────────────────────────────────────────

(LDoc ^{:name "orphan"} mb-orphan {:flag true})   ; flagged, NO approvals anywhere

(LDoc ^{:name "ok"} mb-ok-doc {:flag true})
(LReviewer ^{:name "rev"} mb-rev {:approves [mb-ok-doc]})

(LDoc ^{:name "botted"} mb-bot-doc {:flag true})
(LBot ^{:name "bot"} mb-bot {:approves [mb-bot-doc]})

(LDoc ^{:name "plain"} mb-plain)              ; unflagged, unapproved — fine

(deftest matched-by-fires-on-the-wholly-empty-relation
  (testing "THE gotcha case: no :approves relation exists at all — must still fire"
    (is (contains? (laws (build/vars->cozo [#'mb-orphan]))
                   "every flagged doc is approved by a reviewer"))))

(deftest matched-by-satisfied-by-the-right-counterpart
  (is (not (contains? (laws (build/vars->cozo [#'mb-ok-doc #'mb-rev]))
                      "every flagged doc is approved by a reviewer"))))

(deftest matched-by-from-filters-the-counterpart-kind
  (testing "an approval from a Bot does not satisfy :from LReviewer"
    (is (contains? (laws (build/vars->cozo [#'mb-bot-doc #'mb-bot]))
                   "every flagged doc is approved by a reviewer"))))

(deftest matched-by-when-filters-the-subjects
  (is (not (contains? (laws (build/vars->cozo [#'mb-plain]))
                      "every flagged doc is approved by a reviewer"))))

;; ── has ───────────────────────────────────────────────────────────────────────

(LRef ^{:name "bare"} h-bare {:kind "ref"})                ; ref without names
(LDoc ^{:name "t"} h-named-t)
(LRef ^{:name "named"} h-named {:kind "ref" :names h-named-t})
(LRef ^{:name "plain"} h-plain {:kind "plain"})            ; not a ref — exempt

(deftest has-fires-when-the-relation-is-absent
  (is (contains? (laws (build/vars->cozo [#'h-bare])) "a ref names a target")))

(deftest has-satisfied-and-when-scoped
  (let [db (build/vars->cozo [#'h-named #'h-named-t #'h-plain])]
    (is (not (contains? (laws db) "a ref names a target")))))

;; ── has-any ───────────────────────────────────────────────────────────────────

(LProj ^{:name "neither"} ha-neither)
(LDoc ^{:name "d"} ha-doc)
(LProj ^{:name "mapper"} ha-mapper {:maps [ha-doc]})
(LProj ^{:name "wrapper"} ha-wrapper {:wraps ha-mapper})

(deftest has-any-fires-only-when-every-alternative-is-absent
  (is (contains? (laws (build/vars->cozo [#'ha-neither])) "a proj maps or wraps"))
  (let [db (build/vars->cozo [#'ha-doc #'ha-mapper #'ha-wrapper])]
    (is (not (contains? (laws db) "a proj maps or wraps")))))

;; ── target ────────────────────────────────────────────────────────────────────

(LSrc ^{:name "up"}   t-up   {:polarity "code-up"})
(LSrc ^{:name "down"} t-down {:polarity "design-down"})
(LLift ^{:name "good"} t-good {:lifts t-up})
(LLift ^{:name "bad"}  t-bad  {:lifts t-down})

(deftest target-checks-the-relations-targets
  (is (not (contains? (laws (build/vars->cozo [#'t-up #'t-good])) "lifts only code-up")))
  (is (contains? (laws (build/vars->cozo [#'t-down #'t-bad])) "lifts only code-up")))

;; ── at-most-one ───────────────────────────────────────────────────────────────

(LOwned ^{:name "x"} amo-x)
(LOwner ^{:name "a"} amo-a {:owns [amo-x]})
(LOwner ^{:name "b"} amo-b {:owns [amo-x]})

(LOwned ^{:name "y"} amo-y)
(LOwner ^{:name "c"} amo-c {:owns [amo-y]})

(deftest at-most-one-fires-on-a-second-incoming
  (is (contains? (laws (build/vars->cozo [#'amo-x #'amo-a #'amo-b])) "at most one owner"))
  (is (not (contains? (laws (build/vars->cozo [#'amo-y #'amo-c])) "at most one owner"))))

;; ── qualified :when / exempting :unless (datalog + scalar-map) ────────────────

(defstructure LGate
  "has with a DATALOG :when (a relation-presence qualifier) AND a DATALOG :unless — an
   instance targeted by a :holds edge, unless it is skipped, must carry :names."
  {:skip  [:? :boolean]
   :names [:? LDoc]}
  (law "a held, non-skipped gate names a target"
    (has :names
         :when   '[[?hr :rel/kind :holds] [?hr :rel/to ?x]]
         :unless '[[?x :val/skip true]])
    :key :held-gate))
(defstructure LHolder "holds gates" {:holds [:* LGate]})

(LGate ^{:name "unheld"} qg-unheld)                        ; no :holds edge → :when excludes it
(LGate ^{:name "held-bare"} qg-held-bare)
(LHolder ^{:name "h1"} qg-h1 {:holds [qg-held-bare]})      ; held, no :names → offends
(LDoc  ^{:name "gt"} qg-target)
(LGate ^{:name "held-named"} qg-held-named {:names qg-target})
(LHolder ^{:name "h2"} qg-h2 {:holds [qg-held-named]})     ; held, has :names → fine
(LGate ^{:name "held-skip"} qg-held-skip {:skip true})
(LHolder ^{:name "h3"} qg-h3 {:holds [qg-held-skip]})      ; held, no :names, skipped → exempt

(deftest has-with-datalog-when-and-unless
  (testing "a datalog :when qualifies subjects by a relation clause; a datalog :unless exempts them"
    (is (contains? (laws (build/vars->cozo [#'qg-held-bare #'qg-h1]))
                   "a held, non-skipped gate names a target"))
    (is (not (contains? (laws (build/vars->cozo [#'qg-unheld]))
                        "a held, non-skipped gate names a target")))
    (is (not (contains? (laws (build/vars->cozo [#'qg-held-named #'qg-h2 #'qg-target]))
                        "a held, non-skipped gate names a target")))
    (is (not (contains? (laws (build/vars->cozo [#'qg-held-skip #'qg-h3]))
                        "a held, non-skipped gate names a target")))))

(defstructure LMember
  "matched-by with a scalar-map :unless — every member must be held by a Group, except a
   synthetic one (exempt by :tag)."
  {:tag [:? :string]}
  (law "every member is grouped except the synthetic"
    (matched-by :holds-member :from LGroup :unless {:tag "synthetic"})))
(defstructure LGroup "groups members" {:holds-member [:* LMember]})

(LMember ^{:name "loose"} em-loose)                        ; not held → offends
(LMember ^{:name "kept"} em-kept)
(LGroup  ^{:name "g"} em-g {:holds-member [em-kept]})      ; held → fine
(LMember ^{:name "synthetic"} em-synth {:tag "synthetic"}) ; not held but exempt

(deftest matched-by-with-scalar-unless
  (testing "a scalar-map :unless exempts matching subjects"
    (is (contains? (laws (build/vars->cozo [#'em-loose]))
                   "every member is grouped except the synthetic"))
    (is (not (contains? (laws (build/vars->cozo [#'em-kept #'em-g]))
                        "every member is grouped except the synthetic")))
    (is (not (contains? (laws (build/vars->cozo [#'em-synth]))
                        "every member is grouped except the synthetic")))))

(deftest combinator-key-rides-a-combinator-law
  (testing "a trailing :key on a combinator law flows into violations-of"
    (let [db (build/vars->cozo [#'qg-held-bare #'qg-h1])]
      (is (seq (law/violations-of db :held-gate))))))

;; ── surface errors ────────────────────────────────────────────────────────────

(deftest unknown-combinator-is-rejected-at-expansion
  (let [msg (try (let [_ (macroexpand
                          '(fukan.canvas.core.structure/defstructure BadComb "d"
                             (law "nope" (frobnicate :x))))]
                   "no throw")
                 (catch Throwable e
                   (loop [t e] (if-let [c (ex-cause t)] (recur c) (ex-message t)))))]
    (is (re-find #"unknown law combinator" msg))))

(deftest combinator-laws-keep-their-authored-src
  (testing "the authored combinator form survives parsing (the print-dual renders it)"
    (let [law (first (:laws (s/structure-by-tag ::LDoc)))]
      (is (= '(matched-by :approves :from LReviewer :when {:flag true}) (:src law))))))

;; (The law :key + violations-of coverage rode the TrustBoundary `:totality` law, retired with the
;; principles layer. The generated correspondence demand laws below carry stable `:corresponds/*`
;; keys and exercise the same keyed-law path.)

;; ── generated correspondence demand laws ─────────────────────────────────────

(deftest generated-realized-law-matches-the-dissolved-realization
  (testing "an authored op with no twin is an offender iff any code is extracted (the guard)"
    (let [mk (fn [with-code?]
               (build/tx-maps->cozo
                (cond-> [{:db/id -1 :structure/of :fukan.common.vocab.code.module/Module :entity/id "m" :entity/name "m"}
                         {:db/id -2 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "lonely"}
                         {:rel/id "m|exposes|lonely" :rel/from -1 :rel/kind :exposes :rel/to -2}]
                  with-code? (conj {:db/id -3 :structure/of :fukan.common.extraction.clojure.operation/Fn
                                    :entity/name "other" :val/extracted true}))))
          guarded (mk true)]
      (is (contains? (set (map #(:entity/name (cq/entity guarded %))
                               (law/violations-of guarded :corresponds/Operation.total)))
                     "lonely"))
      (is (empty? (law/violations-of (mk false) :corresponds/Operation.total))
          "no code extracted → the realized demand is vacuous (the guard)"))))

;; ── op-level delegates realization (the roll-up public-call graph, :sub / preserve) ──
;; The FIDELITY (faithful) direction was retired 2026-07-20: it is an architectural concern enforced by
;; Subsystem `:may-depend` conformance, not op-by-op. delegates keeps only realization (⊑ preserve).

(defn- names
  "Entity names for a set of offender eids — maps `violations-of` eids → entity names via `cq/entity`."
  [db eids]
  (set (map #(:entity/name (cq/entity db %)) eids)))

;; The COMMON BASE (raw tx-maps): design modules s,t with s-op→(delegates)→t-op, code modules
;; fukan.s/fukan.t with twins s-op/t-op. The five dbs derive from it by stated deltas.
(def ^:private container-base
  [{:db/id -1 :structure/of :fukan.common.vocab.code.module/Module :entity/name "s"}
   {:db/id -2 :structure/of :fukan.common.vocab.code.module/Module :entity/name "t"}
   {:db/id -3 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "s-op"}
   {:db/id -4 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "t-op"}
   {:rel/id "s|exposes|s-op" :rel/from -1 :rel/kind :exposes :rel/to -3}
   {:rel/id "t|exposes|t-op" :rel/from -2 :rel/kind :exposes :rel/to -4}
   {:rel/id "s-op|delegates|t-op" :rel/from -3 :rel/kind :delegates :rel/to -4}
   {:db/id -5 :structure/of :fukan.common.extraction.clojure.module/Ns :entity/name "fukan.s" :val/extracted true}
   {:db/id -6 :structure/of :fukan.common.extraction.clojure.module/Ns :entity/name "fukan.t" :val/extracted true}
   {:db/id -7 :structure/of :fukan.common.extraction.clojure.operation/Fn :entity/name "s-op" :val/extracted true}
   {:db/id -8 :structure/of :fukan.common.extraction.clojure.operation/Fn :entity/name "t-op" :val/extracted true}
   {:rel/id "ks|child|s" :rel/from -5 :rel/kind :child :rel/to -7}
   {:rel/id "kt|child|t" :rel/from -6 :rel/kind :child :rel/to -8}])

;; no-call-db: base as-is (delegation, twins, NO :calls edge → realized offender s-op)
(def ^:private no-call-db (build/tx-maps->cozo container-base))

;; with-call-db: base + :calls edge between code twins → realized green
(def ^:private with-call-db
  (build/tx-maps->cozo (conj container-base {:rel/id "call" :rel/from -7 :rel/kind :calls :rel/to -8})))

;; untwinned-module-db: base MINUS -5/-7 and their rels (module s has NO twin;
;; keep -6/-8 so a root-kind fact exists → realized offender s-op)
(def ^:private untwinned-module-db
  (build/tx-maps->cozo
   (filterv #(and (not= -5 (:db/id %))
                  (not= -7 (:db/id %))
                  (not= "ks|child|s" (:rel/id %)))
            container-base)))

(deftest delegates-realized-fires-without-a-backing-call
  (testing "op-level: a cross-module design delegation whose endpoint twins never reach each other
            through the fact call graph is an offender; adding the :calls edge clears it"
    (is (= #{"s-op"} (names no-call-db (law/violations-of no-call-db :corresponds/Operation.delegates-realized))))
    (is (empty? (law/violations-of with-call-db :corresponds/Operation.delegates-realized)))))

(deftest delegates-realized-ignores-a-delegation-whose-endpoint-has-no-twin
  (testing "op-altitude: a design endpoint with NO extracted twin is OUT OF SCOPE for realization —
            its very existence is the plain `realized` demand's concern, not this one"
    (is (empty? (law/violations-of untwinned-module-db :corresponds/Operation.delegates-realized)))))


;; ── performs reflect (⊒) — the path relation map (ex-EffectCorrespondence) ──────────

;; The COMMON BASE (raw tx-maps): one shared io Effect node (-10), design module m (-1),
;; authored op f (-2) exposed by m; extracted module fukan.m (-3), extracted twin f (-4)
;; calls extracted g (-5), g performs io (-10). Both strata share the SAME Effect node
;; (content-deduped ^:value) — identity semantics.
(def ^:private effect-base
  [{:db/id -10 :structure/of :fukan.common.vocab.code.effect/Effect :val/name "io"}
   {:db/id -1 :structure/of :fukan.common.vocab.code.module/Module :entity/name "m"}
   {:db/id -2 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "f"}
   {:rel/id "m|exposes|f" :rel/from -1 :rel/kind :exposes :rel/to -2}
   {:db/id -3 :structure/of :fukan.common.extraction.clojure.module/Ns :entity/name "fukan.m" :val/extracted true}
   {:db/id -4 :structure/of :fukan.common.extraction.clojure.operation/Fn :entity/name "f" :val/extracted true}
   {:db/id -5 :structure/of :fukan.common.extraction.clojure.operation/Fn :entity/name "g" :val/extracted true}
   {:rel/id "km|child|f" :rel/from -3 :rel/kind :child :rel/to -4}
   {:rel/id "km|child|g" :rel/from -3 :rel/kind :child :rel/to -5}
   {:rel/id "f|calls|g"  :rel/from -4 :rel/kind :calls :rel/to -5}
   {:rel/id "g|performs|io" :rel/from -5 :rel/kind :performs :rel/to -10}])
;; undeclared-db = effect-base (twin reaches io via g; design f declares nothing → offender f)
;; declared-db   = base + authored f performs same io value node → green (identity semantics)
(def ^:private undeclared-db  (build/tx-maps->cozo effect-base))
(def ^:private declared-db
  (build/tx-maps->cozo (conj effect-base {:rel/id "af|performs|io" :rel/from -2 :rel/kind :performs :rel/to -10})))

;; direct-effect-db: fresh minimal pair — design op "d" exposed by "m2", extracted twin "d"
;; performs io DIRECTLY (no call hops), no design :performs → offender d (the reflexive base)
(def ^:private direct-effect-db
  (build/tx-maps->cozo
   [{:db/id -10 :structure/of :fukan.common.vocab.code.effect/Effect :val/name "io"}
    {:db/id -1 :structure/of :fukan.common.vocab.code.module/Module :entity/name "m2"}
    {:db/id -2 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "d"}
    {:rel/id "m2|exposes|d" :rel/from -1 :rel/kind :exposes :rel/to -2}
    {:db/id -3 :structure/of :fukan.common.extraction.clojure.module/Ns :entity/name "fukan.m2" :val/extracted true}
    {:db/id -4 :structure/of :fukan.common.extraction.clojure.operation/Fn :entity/name "d" :val/extracted true}
    {:rel/id "km2|child|d" :rel/from -3 :rel/kind :child :rel/to -4}
    {:rel/id "d|performs|io" :rel/from -4 :rel/kind :performs :rel/to -10}]))

(deftest performs-covered-fires-on-a-transitively-reached-undeclared-effect
  (testing "the twin reaches io via a call chain; the design op declares nothing → offender;
            declaring io (same value node) → green"
    (is (= #{"f"} (names undeclared-db (law/violations-of undeclared-db :corresponds/Operation.performs-covered))))
    (is (empty? (law/violations-of declared-db :corresponds/Operation.performs-covered)))))

(deftest performs-covered-includes-the-twin-s-DIRECT-effects
  (testing "the reflexive base: an effect the twin performs directly (zero call hops) must be declared"
    (is (= #{"d"} (names direct-effect-db (law/violations-of direct-effect-db :corresponds/Operation.performs-covered))))))

;; ── seam↔generator key invariant ─────────────────────────────────────────────

(deftest seam-keys-equal-generated-law-keys
  (testing "the seam's key index and the generated laws agree exactly — neither can drift"
    (is (= (set (keys (:keys (s/correspondence))))
           (into #{} (comp (mapcat s/laws-of) (keep :key)
                           (filter #(= "corresponds" (namespace %))))
                 (s/all-structures))))))

;; ── the out↦out FORWARD map subsumes coverage (ledgered dedicated offender test) ──

(deftest generated-agrees-fires-on-a-twin-missing-a-modelled-out
  (testing "the derived `out↦out` identity map is FORWARD: a public modelled op that DECLARES an :out
            whose twin declares NO :out is an offender (the folded-in type-coverage failure mode); a
            twin carrying the same :out is green. The dedicated missing-out offender test for
            :corresponds/Operation.agrees (differing-out + :in order/arity live in correspondence-test)."
    (let [mk (fn [twin-datoms]
               (build/tx-maps->cozo
                (concat
                 [{:db/id -1 :structure/of :fukan.common.vocab.code.module/Module :entity/name "m"}
                  {:db/id -2 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "f"}
                  {:rel/id "m|exposes|f" :rel/from -1 :rel/kind :exposes :rel/to -2}
                  {:db/id -5 :structure/of :fukan.common.typing.malli/Schema :val/kind "nil"}  ; design f's modelled :out
                  {:rel/id "f|out|s5" :rel/from -2 :rel/kind :out :rel/to -5}
                  {:db/id -3 :structure/of :fukan.common.extraction.clojure.module/Ns :entity/name "fukan.m" :val/extracted true}
                  {:db/id -4 :structure/of :fukan.common.extraction.clojure.operation/Fn :entity/name "f" :val/extracted true}
                  {:rel/id "km|child|f" :rel/from -3 :rel/kind :child :rel/to -4}]
                 twin-datoms)))
          no-sig   (mk [])                                                    ; twin declares no :out → forward fail
          with-sig (mk [{:rel/id "tf|out" :rel/from -4 :rel/kind :out :rel/to -5}])] ; twin :out = the SAME node → green
      (is (= #{"f"} (names no-sig (law/violations-of no-sig :corresponds/Operation.agrees)))
          "design declares an :out, twin declares none → forward out↦out offender")
      (is (empty? (law/violations-of with-sig :corresponds/Operation.agrees))
          "the same twin carrying the modelled :out → green"))))

;; ── the relation-map primitive (rel incl E): expression lowering + law generation ──

(deftest relation-map-expression-lowering
  (testing "`E` (regular relations) lowers to the flat path-segment vector the `path` builtin reads"
    (is (= [:calls]            (#'s/expr->path-segments :calls))           "an atom is a one-hop segment")
    (is (= [:calls+]           (#'s/expr->path-segments [:+ :calls]))      ":+ → the transitive-closure suffix")
    (is (= [:calls*]           (#'s/expr->path-segments [:* :calls]))      ":* → the reflexive-transitive suffix")
    (is (= [:calls* :performs] (#'s/expr->path-segments [:cat [:* :calls] :performs])) ":cat concatenates segments"))
  (testing "forms that need the derived-rule compiler (not yet built) throw at lowering, naming the expr"
    (is (thrown? Exception (#'s/expr->path-segments [:* [:cat :a :b]])) "closure over a compound")
    (is (thrown? Exception (#'s/expr->path-segments [:alt :a :b]))      "union")
    (is (thrown? Exception (#'s/expr->path-segments [:? :a]))           "optional")))

(deftest relation-map-generates-directional-laws
  (testing "the inclusion direction picks which homomorphism law(s) generate, keyed by direction"
    (let [keys-of (fn [incl] (mapv :key (:laws (#'s/relation-map-decl :T {:rel :dep :incl incl :expr [:+ :link]}))))]
      (is (= [:corresponds/T.dep-realized] (keys-of :sub)) ":sub (⊑ preserve) → the realized law only")
      (is (= [:corresponds/T.dep-covered]  (keys-of :sup)) ":sup (⊒ reflect)  → the covered law only")
      (is (= [:corresponds/T.dep-realized :corresponds/T.dep-covered] (keys-of :eq)) ":eq (≡) → both")))
  (testing "the preserve law binds BOTH endpoints positively via twin (an untwinned endpoint is totality's concern)"
    (let [preserve (first (:laws (#'s/relation-map-decl :T {:rel :dep :incl :sub :expr [:+ :link]})))]
      (is (some #{'(twin ?a ?ea)} (:where preserve)))
      (is (some #{'(twin ?b ?eb)} (:where preserve))))))

(deftest roll-up-compiles-to-a-guarded-transitive-closure-rule
  (testing "the roll-up `R·(P·R)*` (public call graph) compiles to a recursive derived rule: `R+`
            restricted to P-guarded interior — base `(R a b)` binds both ends (no unsafe reflexive head).
            The interior test is the SAME `public` predicate, complemented (`[:not :public]` → not-public)."
    (let [{:keys [terms]} (#'s/relation-map-decl :T {:rel :dep :incl :sub
                                                     :expr [:cat :link [:* [:cat [:test [:not :public]] :link]]]})]
      (is (= 2 (count terms)) "two rule clauses: the base and the guarded-recursive step")
      (is (= '(dep-reach ?a ?b) (ffirst terms)) "the reach rule is named <rel>-reach")
      (is (= '[(dep-reach ?a ?b) (link ?a ?b)] (first terms)) "base: a direct link")
      (is (= '[(dep-reach ?a ?b) (link ?a ?m) (not (public ?m)) (dep-reach ?m ?b)] (second terms))
          "step: a link to a ¬public (interior) node, then continue"))))

;; ── (agrees {:by …}): the correspondence comparator SPI + pair-hybrid ──────────
(defstructure LTwin
  "agrees subject: a nested-corresponding kind whose fact twin must AGREE on its `:n` leaf via a
   registered comparator — exercises `register-comparator!` + the pair-hybrid law path end-to-end.
   Pure identity; correspondence hooks in externally via `(correspond LTwin …)` below."
  {:n [:? :int]})

(s/correspond LTwin :eq LTwin (agrees {:by :ltwin-eq}))
(s/register-comparator! :ltwin-eq
  (fn [db a b] (= (:val/n (cq/entity db a)) (:val/n (cq/entity db b)))))

(deftest agrees-demand-runs-the-registered-comparator-over-twin-pairs
  (testing "a fact twin whose :n DISAGREES with the design is an offender; an agreeing twin is green.
            The design LTwin `x` nests in module `m`, its fact twin in the corresponding code module
            `fukan.m` (:qualified-suffix), so the two twin — and the :ltwin-eq comparator decides."
    (let [mk (fn [design-n fact-n]
               (build/tx-maps->cozo
                [{:db/id -1 :structure/of :fukan.common.vocab.code.module/Module :entity/name "m"}
                 {:db/id -2 :structure/of ::LTwin :entity/name "x" :val/n design-n}
                 {:rel/id "m|child|x" :rel/from -1 :rel/kind :child :rel/to -2}
                 {:db/id -3 :structure/of :fukan.common.extraction.clojure.module/Ns :entity/name "fukan.m" :val/extracted true}
                 {:db/id -4 :structure/of ::LTwin :entity/name "x" :val/n fact-n :val/extracted true}
                 {:rel/id "fm|child|x" :rel/from -3 :rel/kind :child :rel/to -4}]))
          drift (mk 1 2)
          match (mk 1 1)]
      (is (= #{"x"} (names drift (law/violations-of drift :corresponds/LTwin.agrees)))
          "a disagreeing fact twin is an offender")
      (is (empty? (law/violations-of match :corresponds/LTwin.agrees))
          "an agreeing fact twin is green"))))

