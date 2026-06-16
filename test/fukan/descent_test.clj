(ns fukan.descent-test
  "The generative-descent Source slice: the structural-witness law read three ways."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.core.assemble :as a]
            [fukan.model.pipeline :as pipeline]
            [lib.grammar :as g]
            [lib.code :as code]
            ;; side-effect load: registers canvas.subject's structures (incl. Source) so the
            ;; red-case `reflected` helper can reflect them via with-grammar's extra-seed.
            [canvas.subject]
            [canvas.descent.source :refer [SourceRealizer ConvergenceEdge]]
            [fukan.descent :as descent]))

(defn- law-desc
  "The description of the (single) law on the structure tagged `tag` — the single source of truth,
   so tests never hardcode a law string. (Each descent structure carries exactly one law.)"
  [tag]
  (-> (s/structure-by-tag tag) :laws first :desc))

(defn- fired?
  "True when the law described by `desc` reports any violation over `db`. (`s/check` returns
   violation maps carrying `{:law <desc> …}`.)"
  [db desc]
  (boolean (some #(= desc (:law %)) (s/check db))))

;; fixtures for the partial-witness (red) cases — reflect the real Source via an extra-seed,
;; declare only some realizers, and watch the obligation surface the gap.
(code/Module ^{:name "dummy"} t-mod "a throwaway module for :by")
(SourceRealizer ^{:name "wd"} t-wd {:witnesses "design-down" :by t-mod})

(defn- reflected
  "Reflect canvas.subject (so Source is present) over the given fixture vars."
  [vars]
  (g/with-grammar (a/assemble-vars vars) ["canvas.subject"]))

(deftest full-model-witnesses-both-polarities
  (testing "the real reflected model witnesses both polarities — verify/carve/gap all agree"
    (let [db (pipeline/build-model nil)]
      (is (= #{"design-down" "code-up"} (descent/required-witnesses db))
          "UP (carve): the Source portrait declares both flavours")
      (is (empty? (descent/unwitnessed-polarities db))
          "GAP (prompt): nothing unwitnessed — the in-fold is fully realized")
      (is (not (fired? db (law-desc :canvas.descent.source/SourceRealizer)))
          "DOWN (verify): the structural-witness law passes on the real model"))))

(deftest one-polarity-unwitnessed-fires
  (testing "only design-down declared → code-up is the gap worklist and the law fires"
    (let [db (reflected [#'t-mod #'t-wd])]
      (is (= #{"design-down" "code-up"} (descent/required-witnesses db)))
      (is (= #{"code-up"} (descent/unwitnessed-polarities db))
          "code-up has no realizer")
      (is (fired? db (law-desc :canvas.descent.source/SourceRealizer))
          "the witness law fires on the unwitnessed polarity"))))

(deftest zero-witnesses-fires-on-both
  (testing "no realizer at all → both polarities are offenders (rule-routed negation handles the
            empty-relation case)"
    (let [db (reflected [#'t-mod])]
      (is (= #{"design-down" "code-up"} (descent/required-witnesses db))
          "carve precondition: Source reflected with both flavours")
      (is (= #{"design-down" "code-up"} (descent/unwitnessed-polarities db)))
      (is (fired? db (law-desc :canvas.descent.source/SourceRealizer))))))

(deftest full-model-converges-both-polarities
  (testing "the real model's :into convergence verifiably unifies both polarities"
    (let [db (pipeline/build-model nil)]
      (is (= #{"design-down" "code-up"} (descent/converged-polarities db))
          "build-model actually delegates to a producer for each polarity")
      (is (empty? (descent/unconverged-polarities db))
          "GAP: nothing unconverged — :into Model unifies both sides")
      (is (not (fired? db (law-desc :canvas.descent.source/ConvergenceEdge)))
          "the convergence law passes on the real model"))))

;; convergence fixtures — an orchestrator that delegates ONLY to the design-down producer, so a
;; declared code-up edge is NOT verified (it has no real :delegates edge to its :via producer).
(code/Operation ^{:name "prod-d"} t-prod-d "a design-down producer")
(code/Operation ^{:name "prod-c"} t-prod-c "a code-up producer")
(code/Operation ^{:name "orch"}   t-orch   {:delegates [t-prod-d]})
(ConvergenceEdge ^{:name "ced"} t-ce-design {:realizer t-orch :polarity "design-down" :via t-prod-d})
(ConvergenceEdge ^{:name "cec"} t-ce-code   {:realizer t-orch :polarity "code-up"     :via t-prod-c})

(deftest convergence-totality-fires
  (testing "only the design-down convergence edge declared → code-up is unconverged, law fires"
    (let [db (reflected [#'t-prod-d #'t-orch #'t-ce-design])]
      (is (= #{"design-down"} (descent/converged-polarities db)))
      (is (= #{"code-up"} (descent/unconverged-polarities db))
          "code-up has no convergence edge")
      (is (fired? db (law-desc :canvas.descent.source/ConvergenceEdge))))))

(deftest convergence-verified-composition-fires
  (testing "a code-up edge whose realizer does NOT delegate to its :via producer is still
            unconverged — the law checks real wiring, not declaration"
    (let [db (reflected [#'t-prod-d #'t-prod-c #'t-orch #'t-ce-design #'t-ce-code])]
      (is (= #{"design-down"} (descent/converged-polarities db))
          "code-up's edge is declared but orch doesn't delegate to prod-c")
      (is (= #{"code-up"} (descent/unconverged-polarities db)))
      (is (fired? db (law-desc :canvas.descent.source/ConvergenceEdge))))))

(deftest convergence-zero-edges-fires-on-both
  (testing "no convergence edge → both polarities unconverged (rule-routed negation, empty relation)"
    (let [db (reflected [#'t-prod-d])]
      (is (= #{"design-down" "code-up"} (descent/required-witnesses db))
          "carve precondition: Source reflected with both flavours")
      (is (= #{"design-down" "code-up"} (descent/unconverged-polarities db)))
      (is (fired? db (law-desc :canvas.descent.source/ConvergenceEdge))))))
