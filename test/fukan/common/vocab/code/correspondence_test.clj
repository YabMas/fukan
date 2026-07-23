(ns fukan.common.vocab.code.correspondence-test
  "The design↔fact correspondence as READINGS over the ambient `corresponds`/`realized-*` rules the
   essential `(correspond …)` lowers to (2026-07-23). The generated `:corresponds/*` demand LAWS
   dissolved at the seam cutover — coverage/adherence are now readings (the shape dev/user.clj's
   `drift`/`encapsulation`/`type-drift` run), not law keys. These tests reuse the same fixtures and assert
   the reading queries; `law/check` gates self-model parity separately (0 violations)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            [fukan.cozo.law :as law]
            ;; loading infra.model is the composition root — it registers fukan's malli dialect AND its
            ;; Clojure (fact) extractor, so build-model "src" runs the build
            [fukan.infra.model]
            [fukan.model.pipeline :as pipeline]
            [fukan.canvas.core.structure :as s]
            [fukan.common.typing.malli :as malli]
            [fukan.canvas.core.typing :as typing]
            ;; the vocab elements register the design structures + the tags in the fixtures; the Clojure
            ;; extraction plugins register the Fn/Ns codomains AND the essential Operation↦Fn / Module↦Ns
            ;; correspondences (the `corresponds`/`realized-*` rules the readings below query).
            [fukan.common.vocab.code.module]
            [fukan.common.vocab.code.operation]
            [fukan.common.vocab.code.effect]
            [fukan.common.extraction.clojure.operation]
            [fukan.common.extraction.clojure.module]))

;; register the project dialect's :render for the operation-sig / render-type path used below
;; — per-test, since dialect registration is global mutable state other namespaces touch.
(use-fixtures :each
  (fn [t] (typing/register-type-dialect! {:render malli/render}) (t)))

(defn- read-names
  "Run a datalog READING (auto-injecting the vocab rules) → the set of names bound to ?n."
  [db clauses]
  (set (cq/q (into '[:find [?n ...] :in $ % :where] clauses) db (s/vocab-rules))))

;; ── the pairing (`corresponds`) — reading (a) ────────────────────────────────

;; Tiny model: authored A.op-a :delegates B.op-b; when `wired?`, the extracted MULTI-HOP call path
;; op-a -> mid(private) -> op-b. "Same module name" authored/extracted pairs make the
;; :qualified-suffix match trivial ("A" is a suffix of "A" — the exact case).
(defn- delegation-fixture
  "Authored A.op-a :delegates B.op-b; when `wired?`, the extracted MULTI-HOP call path
   op-a -> mid -> op-b (exercising the roll-up over `:calls` through PRIVATE interior)."
  [wired?]
  (build/tx-maps->cozo (cond-> [{:db/id -1 :structure/of :fukan.common.vocab.code.module/Module :entity/id "A" :entity/name "A"}
                {:db/id -2 :structure/of :fukan.common.vocab.code.module/Module :entity/id "B" :entity/name "B"}
                {:db/id -3 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "op-a"}
                {:db/id -4 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "op-b"}
                {:rel/id "A|child|op-a" :rel/from -1 :rel/kind :child :rel/to -3}
                {:rel/id "B|child|op-b" :rel/from -2 :rel/kind :child :rel/to -4}
                {:rel/id "op-a|delegates|op-b" :rel/from -3 :rel/kind :delegates :rel/to -4}
                ;; the extracted (code) modules are SEPARATE nodes from the design modules — distinct
                ;; :entity/id so they don't merge — bridged by `:qualified-suffix` (same name here).
                {:db/id -5 :structure/of :fukan.common.extraction.clojure.module/Ns :entity/id "Ax" :entity/name "A" :val/extracted true}
                {:db/id -6 :structure/of :fukan.common.extraction.clojure.module/Ns :entity/id "Bx" :entity/name "B" :val/extracted true}
                {:db/id -7  :structure/of :fukan.common.extraction.clojure.operation/Fn :entity/name "op-a" :val/extracted true}
                {:db/id -8  :structure/of :fukan.common.extraction.clojure.operation/Fn :entity/name "op-b" :val/extracted true}
                {:rel/id "Ax|child|op-a" :rel/from -5 :rel/kind :child :rel/to -7}
                {:rel/id "Bx|child|op-b" :rel/from -6 :rel/kind :child :rel/to -8}]
         ;; mid is PRIVATE: the roll-up `calls·(¬public·calls)*` realizes a delegation only through
         ;; ¬public interior — routing through a PUBLIC op would be two delegations, not one.
         wired? (into [{:db/id -11 :structure/of :fukan.common.extraction.clojure.operation/Fn :entity/name "mid" :val/extracted true :val/private true}
                       {:rel/id "Ax|child|mid" :rel/from -5 :rel/kind :child :rel/to -11}
                       {:rel/id "op-a|calls|mid" :rel/from -7  :rel/kind :calls :rel/to -11}
                       {:rel/id "mid|calls|op-b" :rel/from -11 :rel/kind :calls :rel/to -8}]))))

(deftest corresponds-pairs-a-design-op-with-its-fn
  (testing "reading (a): the `corresponds` pairing joins a design op to its extracted Fn — same name
            inside corresponding containers (the module pairing nests the op pairing)"
    (let [pairs (set (cq/q '[:find ?dn ?fn :in $ %
                             :where (corresponds ?d ?f) (design ?d) (fact ?f)
                                    (is ?d :fukan.common.vocab.code.operation/Operation)
                                    [?d :entity/name ?dn] [?f :entity/name ?fn]]
                           (delegation-fixture true) (s/vocab-rules)))]
      (is (contains? pairs ["op-a" "op-a"]) "op-a's design op pairs with its extracted Fn")
      (is (contains? pairs ["op-b" "op-b"]) "and op-b likewise"))))

(deftest corresponds-green-on-the-self-model
  (testing "fukan-on-itself: build-model unifies the authored self-model (canvas/) with the code
            extracted from src/ on one graph; every modelled Operation pairs with a realizing Fn (the
            drift reading is empty)"
    (let [model (pipeline/build-model "src")]
      (is (seq (cq/q '[:find ?s :where [?s :structure/of :fukan.common.vocab.code.operation/Operation]] model)) "model has design Operations")
      (is (seq (cq/q '[:find ?o :where [?o :structure/of :fukan.common.extraction.clojure.operation/Fn]] model)) "build-model extracted code into Fns")
      (is (empty? (read-names model '[(is ?op :fukan.common.vocab.code.operation/Operation) (design ?op)
                                      (is ?_g :fukan.common.extraction.clojure.operation/Fn)
                                      (not-join [?op] (corresponds ?op ?_t))
                                      [?op :entity/name ?n]]))
          "every modelled Operation is realized in code — the drift reading is clean"))))

;; ── delegation realization (`realized-delegates`) — reading (b) ──────────────

(deftest realized-delegates-holds-through-a-public-call-path
  (testing "reading (b): `realized-delegates` holds between two design ops whose fns reach each other
            through the public-call graph (op-a -> mid[private] -> op-b realizes op-a ⇒ op-b)"
    (let [reached (set (cq/q '[:find ?an ?bn :in $ %
                               :where (realized-delegates ?a ?b)
                                      [?a :entity/name ?an] [?b :entity/name ?bn]]
                             (delegation-fixture true) (s/vocab-rules)))]
      (is (contains? reached ["op-a" "op-b"])
          "op-a's fn reaches op-b's fn through the private interior — realized"))))

;; the delegation-realization DRIFT reading: a design delegation whose endpoints both pair but whose
;; fns never reach each other along the public-call path.
(defn- delegate-drift [db]
  (read-names db '[(delegates ?a ?b)
                   (corresponds ?a ?_fa) (corresponds ?b ?_fb)
                   (not-join [?a ?b] (realized-delegates ?a ?b))
                   [?a :entity/name ?n]]))

(deftest realized-delegates-absent-without-a-backing-call
  (testing "an authored cross-module delegation whose twins never reach each other through the code's
            call graph is a realization-drift offender; the multi-hop path clears it"
    (is (contains? (delegate-drift (delegation-fixture false)) "op-a")
        "no :calls path op-a -> op-b → drift")
    (is (empty? (delegate-drift (delegation-fixture true)))
        "op-a -> mid -> op-b realizes the delegation transitively → clean")))

;; ── unpaired ops / undeclared surface — readings (c) + encapsulation ─────────

(deftest unpaired-design-op-appears-in-a-drift-reading
  (testing "reading (c): a design Operation with no `corresponds` twin surfaces in a not-join reading"
    (let [db (build/tx-maps->cozo
              [{:db/id -1 :structure/of :fukan.common.vocab.code.module/Module :entity/id "m" :entity/name "m"}
               {:db/id -2 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "lonely"}
               {:rel/id "m|child|lonely" :rel/from -1 :rel/kind :child :rel/to -2}
               {:db/id -3 :structure/of :fukan.common.extraction.clojure.operation/Fn :entity/name "other" :val/extracted true}])]
      (is (contains? (read-names db '[(is ?op :fukan.common.vocab.code.operation/Operation) (design ?op)
                                      (not-join [?op] (corresponds ?op ?_t))
                                      [?op :entity/name ?n]])
                     "lonely")))))

(deftest encapsulation-reading-fires-on-an-undeclared-public-operation
  (testing "the encapsulation reading (ex-:corresponds/Operation.surjective): a PUBLIC extracted op
            with no model twin is unaccounted; private/export/test-support are exempt (¬public)"
    (let [db (build/tx-maps->cozo [{:db/id -1 :structure/of :fukan.common.extraction.clojure.module/Ns :entity/id "fukan.m" :entity/name "fukan.m" :val/extracted true}
                   {:db/id -2 :structure/of :fukan.common.extraction.clojure.operation/Fn :entity/name "leaked"   :val/extracted true}                      ; public, unmodelled → offender
                   {:db/id -3 :structure/of :fukan.common.extraction.clojure.operation/Fn :entity/name "hidden"   :val/extracted true :val/private true}      ; exempt: internal
                   {:db/id -4 :structure/of :fukan.common.extraction.clojure.operation/Fn :entity/name "exported" :val/extracted true :val/export true}       ; exempt: mechanism
                   {:db/id -5 :structure/of :fukan.common.extraction.clojure.operation/Fn :entity/name "for-test" :val/extracted true :val/test-support true} ; exempt: test-support
                   {:rel/id "m|child|leaked"   :rel/from -1 :rel/kind :child :rel/to -2}
                   {:rel/id "m|child|hidden"   :rel/from -1 :rel/kind :child :rel/to -3}
                   {:rel/id "m|child|exported" :rel/from -1 :rel/kind :child :rel/to -4}
                   {:rel/id "m|child|for-test" :rel/from -1 :rel/kind :child :rel/to -5}])]
      (is (= #{"leaked"} (read-names db '[(is ?fn :fukan.common.extraction.clojure.operation/Fn) (public ?fn)
                                          (not-join [?fn] (corresponds ?_op ?fn))
                                          [?fn :entity/name ?n]]))
          "only the public, non-exempt, unmodelled op is unaccounted"))))

(deftest encapsulation-green-on-the-self-model
  (testing "the self-model's entire public surface is covered by the model or deliberately exempt"
    (is (empty? (read-names (pipeline/build-model "src")
                            '[(is ?fn :fukan.common.extraction.clojure.operation/Fn) (public ?fn)
                              (not-join [?fn] (corresponds ?_op ?fn))
                              [?fn :entity/name ?n]]))
        "every public function is modelled, private, exported, or test-support")))

;; ── signature adherence (the type-drift reading) ─────────────────────────────

(defn- type-drift [db]
  (read-names db '[(is ?op :fukan.common.vocab.code.operation/Operation) (design ?op)
                   (corresponds ?op ?fn)
                   (or-join [?op ?fn]
                     (and (out ?op ?o) (not-join [?fn ?o] (out ?fn ?o)))
                     (and (out ?fn ?o) (not-join [?op ?o] (out ?op ?o)))
                     (and (in ?op ?s)  (not-join [?fn ?s] (in ?fn ?s)))
                     (and (in ?fn ?s)  (not-join [?op ?s] (in ?op ?s))))
                   [?op :entity/name ?n]]))

(deftest type-drift-reading-detects-a-signature-mismatch
  (testing "the type-drift reading (ex-:corresponds/Operation.agrees): a paired op whose twin's :in/:out
            type nodes disagree by eid. ⚠ set-equality (no :rel/order) — a missing/differing :out or a
            dropped :in arg is caught; a pure REORDER of same-typed args reads as adhering (deferred)."
    (let [mk (fn [twin-out-eid extra]
               (build/tx-maps->cozo
                (concat
                 [{:db/id -1 :structure/of :fukan.common.vocab.code.module/Module :entity/name "m"}
                  {:db/id -2 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "f"}
                  {:rel/id "m|child|f" :rel/from -1 :rel/kind :child :rel/to -2}
                  {:db/id -5 :structure/of :fukan.common.typing.malli/Schema :val/kind "nil"}   ; the MODELLED :out type node
                  {:rel/id "f|out|s5" :rel/from -2 :rel/kind :out :rel/to -5}
                  {:db/id -3 :structure/of :fukan.common.extraction.clojure.module/Ns :entity/name "fukan.m" :val/extracted true}
                  {:db/id -4 :structure/of :fukan.common.extraction.clojure.operation/Fn :entity/name "f" :val/extracted true}
                  {:rel/id "tf|out" :rel/from -4 :rel/kind :out :rel/to twin-out-eid}
                  {:rel/id "km|child|f" :rel/from -3 :rel/kind :child :rel/to -4}]
                 extra)))
          match    (mk -5 [])                                                              ; twin :out → the SAME node
          mismatch (mk -6 [{:db/id -6 :structure/of :fukan.common.typing.malli/Schema :val/kind "any"}])]  ; twin :out → a DIFFERENT node
      (is (= #{"f"} (type-drift mismatch)) "a twin whose :out is a different type node disagrees")
      (is (empty? (type-drift match)) "a twin whose :out is the identical node adheres → clean"))))

(deftest type-drift-reading-catches-a-dropped-in-arg
  (testing "adherence over :in is caught by set-equality when an arg is DROPPED (a present-on-one-side
            type node); a reorder of same-typed args is NOT caught (order dropped — see the ⚠ above)"
    (let [base (fn [twin-in]   ; twin-in: seq of [order type-eid]
                 (build/tx-maps->cozo
                  (concat
                   [{:db/id -1 :structure/of :fukan.common.vocab.code.module/Module :entity/name "m"}
                    {:db/id -2 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "f"}
                    {:rel/id "m|child|f" :rel/from -1 :rel/kind :child :rel/to -2}
                    {:db/id -10 :structure/of :fukan.common.typing.malli/Schema :val/kind "nil"}      ; type A
                    {:db/id -11 :structure/of :fukan.common.typing.malli/Schema :val/kind "any"}      ; type B
                    {:db/id -12 :structure/of :fukan.common.typing.malli/Schema :val/kind "boolean"}  ; type C (shared :out)
                    {:rel/id "f|in0" :rel/from -2 :rel/kind :in :rel/order 0 :rel/to -10}     ; design :in = [A B]
                    {:rel/id "f|in1" :rel/from -2 :rel/kind :in :rel/order 1 :rel/to -11}
                    {:rel/id "f|out"  :rel/from -2 :rel/kind :out :rel/to -12}
                    {:db/id -3 :structure/of :fukan.common.extraction.clojure.module/Ns :entity/name "fukan.m" :val/extracted true}
                    {:db/id -4 :structure/of :fukan.common.extraction.clojure.operation/Fn :entity/name "f" :val/extracted true}
                    {:rel/id "tf|out" :rel/from -4 :rel/kind :out :rel/to -12}                ; twin :out = C
                    {:rel/id "km|child|f" :rel/from -3 :rel/kind :child :rel/to -4}]
                   (map-indexed (fn [i [ord eid]]
                                  {:rel/id (str "tf|in" i) :rel/from -4 :rel/kind :in :rel/order ord :rel/to eid})
                                twin-in))))
          match (base [[0 -10] [1 -11]])   ; twin :in = {A B} — identical set
          short (base [[0 -10]])]          ; twin :in = {A}   — B missing → set disagreement
      (is (empty? (type-drift match)) "an identical :in set adheres → clean")
      (is (= #{"f"} (type-drift short)) "a dropped :in arg is a set disagreement"))))

(deftest type-adherence-green-on-the-self-model
  (testing "the three infra functions annotated with :malli/schema adhere to their modelled types, so
            the type-drift reading EXCLUDES them (asserting these three specifically rather than global
            emptiness, which is fragile as more functions get annotated)"
    (let [drifted (type-drift (pipeline/build-model "src"))]
      (is (not (contains? drifted "load-model")) (str "load-model adheres; drifted: " drifted))
      (is (not (contains? drifted "get-model")) (str "get-model adheres; drifted: " drifted))
      (is (not (contains? drifted "refresh-model")) (str "refresh-model adheres; drifted: " drifted))
      (is (not (contains? drifted "focus-nodes"))
          "focus-nodes's multi-arg annotation matches its modelled ordered signature"))))

;; ── effect coverage (`realized-performs`) ────────────────────────────────────

;; the effect-coverage reading: a design op whose twin REACHES an effect (the `calls*·performs` roll-up)
;; that the design op does NOT itself declare. Identity is by the shared content-deduped Effect node.
(defn- effect-drift [db]
  (read-names db '[(realized-performs ?a ?e)
                   (not-join [?a ?e] (performs ?a ?e))
                   [?a :entity/name ?n]]))

(deftest effect-coverage-fires-on-an-undeclared-transitive-effect
  (testing "an authored op whose twin TRANSITIVELY reaches an effect it doesn't declare is flagged
            (the ex-performs-covered reading); declaring the effect on the authored op clears it"
    (let [io     {:db/id -10 :structure/of :fukan.common.vocab.code.effect/Effect :val/name "io"}
          common [{:db/id -1 :structure/of :fukan.common.vocab.code.module/Module :entity/name "m"}
                  {:db/id -2 :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "f"}                          ; authored — declares nothing
                  {:rel/id "m|child|f" :rel/from -1 :rel/kind :child :rel/to -2}
                  {:db/id -3 :structure/of :fukan.common.extraction.clojure.module/Ns :entity/name "fukan.m" :val/extracted true}   ; code module (corresponds to "m")
                  {:db/id -4 :structure/of :fukan.common.extraction.clojure.operation/Fn :entity/name "f" :val/extracted true}       ; twin of f
                  {:db/id -5 :structure/of :fukan.common.extraction.clojure.operation/Fn :entity/name "g" :val/extracted true}       ; f calls g
                  {:rel/id "km|child|f" :rel/from -3 :rel/kind :child :rel/to -4}
                  {:rel/id "km|child|g" :rel/from -3 :rel/kind :child :rel/to -5}
                  {:rel/id "f|calls|g"  :rel/from -4 :rel/kind :calls :rel/to -5}                          ; f → g (transitive reach)
                  io
                  {:rel/id "g|performs|io" :rel/from -5 :rel/kind :performs :rel/to -10}]                  ; g performs :io
          undeclared-db (build/tx-maps->cozo common)
          declared-db   (build/tx-maps->cozo (conj common {:rel/id "af|performs|io" :rel/from -2 :rel/kind :performs :rel/to -10}))]
      (is (= #{"f"} (effect-drift undeclared-db))
          "f's twin transitively reaches :io (via g), but f declares nothing → under-declaration")
      (is (empty? (effect-drift declared-db))
          "declaring :io on the authored f satisfies the coverage reading"))))

(deftest effect-vocab-does-not-name-transitive-reachability
  (testing "effect reachability is expressed by composing :calls* and :performs at the consuming layer"
    (is (not (contains? (ns-publics 'fukan.common.vocab.code.effect) 'reached-effects)))))

(deftest effect-coverage-green-on-the-self-model
  (testing "the merged self-model declares every effect its code reaches"
    (is (empty? (effect-drift (pipeline/build-model "src")))
        "0 undeclared effects — design and extraction speak one effect language, to call-graph depth")))

;; ── parity: the merged self-model checks clean ───────────────────────────────

(deftest slice-1-self-model-is-clean
  (testing "with :calls grounded, delegation realized (over the roll-up), and membership scoped, the
            merged design+code self-model has zero LAW violations (the parity gate)"
    (let [model (pipeline/build-model "src")]
      (is (empty? (delegate-drift model)) "delegation realization is green")
      (is (empty? (law/check model))
          (str "no law violations on the merged self-model; got: " (mapv :law (law/check model)))))))
