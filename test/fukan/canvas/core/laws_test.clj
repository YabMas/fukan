(ns fukan.canvas.core.laws-test
  "Law combinators: each shape's positive + negative case, including negation over a
   WHOLLY-EMPTY relation — the case datascript's inline not-join got wrong (forcing the
   old hand-rolled negation rules) and Cozo's stratified not-join handles directly."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            ;; loaded for its side-effect: registers the Cozo check engine so s/check dispatches to it
            [fukan.cozo.law]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            ;; loaded for side-effects: registers TrustBoundary (+ totality law) and op-twin
            [canvas.vocab.code.module]
            [canvas.principles.parse-dont-validate]))

(defn- laws [db] (set (map :law (s/check db))))

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

;; ── law :key + violations-of ─────────────────────────────────────────────────

(deftest law-keys-flow-into-violations-and-violations-of
  (testing "a law's stable :key rides its violations; violations-of filters by it"
    ;; the totality law (on TrustBoundary) carries :key :totality after this task — this is
    ;; correspondence_test's totality offender fixture (trusted reader whose twin throws), inlined.
    (let [db (build/tx-maps->cozo
              [{:db/id -10 :structure/of :canvas.vocab.code.effect/Effect :val/name "throws"}
               {:db/id -20 :structure/of :canvas.vocab.code.kind/Kind :entity/name "TrustDb"}
               {:db/id -21 :structure/of :canvas.principles.parse-dont-validate/TrustBoundary}
               {:rel/id "tb|kind|k" :rel/from -21 :rel/kind :kind :rel/to -20}
               {:db/id -1 :structure/of :canvas.vocab.code.module/Module :entity/name "m"}
               {:db/id -2 :structure/of :canvas.vocab.code.operation/Operation :entity/name "reader"}
               {:rel/id "m|exposes|reader" :rel/from -1 :rel/kind :exposes :rel/to -2}
               {:db/id -22 :structure/of :canvas.vocab.type/Schema :val/kind "ref"}
               {:rel/id "sch|names|k" :rel/from -22 :rel/kind :names :rel/to -20}
               {:rel/id "reader|in|sch" :rel/from -2 :rel/kind :in :rel/to -22}
               {:db/id -3 :structure/of :canvas.vocab.code.module/Module :entity/name "fukan.m" :val/extracted true}
               {:db/id -4 :structure/of :canvas.vocab.code.operation/Operation :entity/name "reader" :val/extracted true}
               {:rel/id "km|child|reader" :rel/from -3 :rel/kind :child :rel/to -4}
               {:rel/id "twin|performs|throws" :rel/from -4 :rel/kind :performs :rel/to -10}])
          vio (->> (s/check db) (filter #(= :totality (:key %))) first)]
      (is (some? vio) "the keyed law fired and its violation carries the :key")
      (is (= (set (map first (:offenders vio)))
             (s/violations-of db :totality))
          "violations-of returns exactly the first-offender eids of the keyed law"))))

;; ── generated correspondence demand laws ─────────────────────────────────────

(deftest generated-realized-law-matches-the-dissolved-realization
  (testing "an authored op with no twin is an offender iff any code is extracted (the guard)"
    (let [mk (fn [with-code?]
               (build/tx-maps->cozo
                (cond-> [{:db/id -1 :structure/of :canvas.vocab.code.module/Module :entity/id "m" :entity/name "m"}
                         {:db/id -2 :structure/of :canvas.vocab.code.operation/Operation :entity/name "lonely"}
                         {:rel/id "m|exposes|lonely" :rel/from -1 :rel/kind :exposes :rel/to -2}]
                  with-code? (conj {:db/id -3 :structure/of :canvas.vocab.code.operation/Operation
                                    :entity/name "other" :val/extracted true}))))
          guarded (mk true)]
      (is (contains? (set (map #(:entity/name (cq/entity guarded %))
                               (s/violations-of guarded :corresponds/Operation.realized)))
                     "lonely"))
      (is (empty? (s/violations-of (mk false) :corresponds/Operation.realized))
          "no code extracted → the realized demand is vacuous (the guard)"))))

;; ── container-altitude relation demands (delegates-realized / delegates-faithful) ──

(defn- names
  "Entity names for a set of offender eids — maps `violations-of` eids → entity names via `cq/entity`."
  [db eids]
  (set (map #(:entity/name (cq/entity db %)) eids)))

;; The COMMON BASE (raw tx-maps): design modules s,t with s-op→(delegates)→t-op, code modules
;; fukan.s/fukan.t with twins s-op/t-op. The five dbs derive from it by stated deltas.
(def ^:private container-base
  [{:db/id -1 :structure/of :canvas.vocab.code.module/Module :entity/name "s"}
   {:db/id -2 :structure/of :canvas.vocab.code.module/Module :entity/name "t"}
   {:db/id -3 :structure/of :canvas.vocab.code.operation/Operation :entity/name "s-op"}
   {:db/id -4 :structure/of :canvas.vocab.code.operation/Operation :entity/name "t-op"}
   {:rel/id "s|exposes|s-op" :rel/from -1 :rel/kind :exposes :rel/to -3}
   {:rel/id "t|exposes|t-op" :rel/from -2 :rel/kind :exposes :rel/to -4}
   {:rel/id "s-op|delegates|t-op" :rel/from -3 :rel/kind :delegates :rel/to -4}
   {:db/id -5 :structure/of :canvas.vocab.code.module/Module :entity/name "fukan.s" :val/extracted true}
   {:db/id -6 :structure/of :canvas.vocab.code.module/Module :entity/name "fukan.t" :val/extracted true}
   {:db/id -7 :structure/of :canvas.vocab.code.operation/Operation :entity/name "s-op" :val/extracted true}
   {:db/id -8 :structure/of :canvas.vocab.code.operation/Operation :entity/name "t-op" :val/extracted true}
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

;; undeclared-call-base: base MINUS delegates rel, MINUS orig -7, PLUS renamed ext-s-op (-7), PLUS :calls edge
(def ^:private undeclared-call-base
  (-> (filterv #(and (not= "s-op|delegates|t-op" (:rel/id %))
                     (not= -7 (:db/id %)))
               container-base)
      (conj {:db/id -7 :structure/of :canvas.vocab.code.operation/Operation :entity/name "ext-s-op" :val/extracted true})
      (conj {:rel/id "call" :rel/from -7 :rel/kind :calls :rel/to -8})))

;; undeclared-call-db: undeclared-call-base as a db (faithful offender: ext-s-op)
(def ^:private undeclared-call-db (build/tx-maps->cozo undeclared-call-base))

;; declared-call-db: base + :calls edge (same as with-call-db — faithful green)
(def ^:private declared-call-db with-call-db)

;; unclaimed-container-db: undeclared-call-base MINUS design module -2/-4 and their rels
;; (callee container has no design twin → NOT claimed → no faithful fire)
(def ^:private unclaimed-container-db
  (build/tx-maps->cozo
   (filterv #(and (not= -2 (:db/id %))
                  (not= -4 (:db/id %))
                  (not= "t|exposes|t-op" (:rel/id %)))
            undeclared-call-base)))

(deftest delegates-realized-fires-without-a-backing-call
  (testing "a cross-container design delegation with twinned containers but no fact call between them"
    (is (= #{"s-op"} (names no-call-db (s/violations-of no-call-db :corresponds/Operation.delegates-realized))))
    (is (empty? (s/violations-of with-call-db :corresponds/Operation.delegates-realized)))))

(deftest delegates-realized-fires-when-the-container-has-no-twin-at-all
  (testing "OLD CallRealization semantics preserved: a delegation from a module with NO extracted
            twin is an offender once ANY root-kind fact exists (the not-join is vacuously false)"
    (is (= #{"s-op"} (names untwinned-module-db (s/violations-of untwinned-module-db :corresponds/Operation.delegates-realized))))))

(deftest delegates-faithful-fires-on-an-undeclared-call-between-claimed-containers
  (testing "a fact call between twinned containers with no design delegation covering it"
    (is (= #{"ext-s-op"} (names undeclared-call-db (s/violations-of undeclared-call-db :corresponds/Operation.delegates-faithful))))
    (is (empty? (s/violations-of declared-call-db :corresponds/Operation.delegates-faithful)))
    (is (empty? (s/violations-of unclaimed-container-db :corresponds/Operation.delegates-faithful))
        "a call into an UNMODELLED container is a coverage signal (uncovered-calls), not a fidelity violation")))
