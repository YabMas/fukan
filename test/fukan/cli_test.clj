(ns fukan.cli-test
  "The exit code is the CLI's whole answer to a program that never loaded the model, and its
   three values exist to separate a design that is VIOLATED from a checker that could not
   DECIDE. These pin that separation at the place it is easiest to lose — the render, which
   runs for the first time exactly when a check first goes red."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :refer [defstructure]]
            [fukan.canvas.projection.instance :as inst]
            [fukan.cli :as cli]
            [fukan.cozo.build :as build]
            ;; loaded for its side-effect: registers the Cozo check engine so `law/check` dispatches
            [fukan.cozo.law]
            [fukan.infra.model :as infra-model]))

(defstructure Card "Test fixture: a structure whose refined slot gives us a law that fires."
  {:card [:enum "one" "many"]})

(Card ^{:name "Bad"}  card-bad  {:card "lots"})
(Card ^{:name "Good"} card-good {:card "one"})

(defn- run-on
  "`run` over a canned model. `load-model` is the only thing stubbed — the check, the offender
   naming and the render are the real ones, which is the point: the render is what is on trial."
  [db args]
  (with-redefs [infra-model/load-model (fn [_] db)]
    (#'cli/run args)))

(deftest a-violated-model-is-unsatisfied
  (testing "a law fired: exit 1, with the offending form on stdout"
    (let [{:keys [code out]} (run-on (build/vars->cozo [#'card-bad]) ["check" "--format" "text"])]
      (is (= 1 code))
      (is (re-find #"Bad" out) "the offender is named, so 1 is a verdict a reader can act on"))))

(deftest a-clean-model-is-satisfied
  (testing "no law fired: exit 0"
    (is (= 0 (:code (run-on (build/vars->cozo [#'card-good]) ["check" "--format" "text"]))))))

(deftest a-render-that-throws-is-undecidable-not-unsatisfied
  (testing "the checker could not produce its report, so it exits 2 — reporting 1 would announce
            a violation nobody computed, with no offenders on stdout to show for it"
    (with-redefs [inst/violations-text (fn [& _] (throw (ex-info "render blew up" {})))]
      (let [{:keys [code error out]} (run-on (build/vars->cozo [#'card-bad])
                                             ["check" "--format" "text"])]
        (is (= 2 code))
        (is (= "render blew up" error))
        (is (re-find #":undecidable true" out))))))

(deftest an-edn-report-that-will-not-print-is-undecidable-too
  (testing "the same holds for the edn report — an offender whose form cannot be printed is a
            failure to decide, not a failure to conform"
    (let [db (build/vars->cozo [#'card-bad])]
      (with-redefs [cli/findings (fn [& _] {:ok false :violations [(reify Object
                                                                     (toString [_]
                                                                       (throw (ex-info "unprintable" {}))))]})]
        (is (= 2 (:code (run-on db ["check"]))))))))

;; ── which format pays for what ───────────────────────────────────────────────

(defn- counting-findings
  "`cli/findings` wrapped so a test can see whether the offender-naming pass ran at all. The
   original is captured OUTSIDE the redef — deref'ing the var inside would call the wrapper."
  [called]
  (let [orig cli/findings]
    (fn [& args] (swap! called inc) (apply orig args))))

(deftest text-format-does-not-pay-to-name-offenders
  (testing "`--format text` quotes each offender as its authored FORM and reads nothing `findings`
            computes. Running it anyway was a discarded pass — 111s of a 900-namespace project's
            run, naming 4,924 cells nobody printed."
    (let [called (atom 0)
          db     (build/vars->cozo [#'card-bad])]
      (with-redefs [cli/findings (counting-findings called)]
        (let [{:keys [code out]} (run-on db ["check" "--format" "text"])]
          (is (= 1 code) "precondition: the law still fires")
          (is (re-find #"Bad" out) "…and the offender is still on stdout, as its form")
          (is (zero? @called) "the text report never names the offenders"))))))

(deftest edn-format-does-pay-to-name-offenders
  (testing "the other half of the branch: `--format edn` IS the named-offender report, so it must
            still compute one"
    (let [called (atom 0)
          db     (build/vars->cozo [#'card-bad])]
      (with-redefs [cli/findings (counting-findings called)]
        (let [{:keys [code out]} (run-on db ["check"])]
          (is (= 1 code))
          (is (= 1 @called))
          (is (re-find #"Bad" out) "the eid is resolved to a name a consumer can act on"))))))
