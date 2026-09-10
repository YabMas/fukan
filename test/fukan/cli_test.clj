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
            ;; also loaded for its side-effect: registering the Cozo check engine, so `law/check`
            ;; dispatches
            [fukan.cozo.law :as law]
            [fukan.cozo.query :as cq]
            [fukan.infra.model :as infra-model]))

(defstructure Card "Test fixture: a structure whose refined slot gives us a law that fires."
  {:card [:enum "one" "many"]})

(Card ^{:name "Bad"}  card-bad  {:card "lots"})
(Card ^{:name "Good"}  card-good  {:card "one"})
(Card ^{:name "Worse"} card-worse {:card "heaps"})

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

;; ── which flags a verb can consume ───────────────────────────────────────────

(deftest a-flag-a-verb-does-not-take-is-refused
  (testing "`describe --src` was accepted and ignored, which reads as a declaration checked against
            that source root. A declaration is what the project SAID and describe never opens the
            code, so the flag has no reader and the invocation is refused."
    (let [{:keys [code error]} (#'cli/run ["describe" "--src" "somewhere"])]
      (is (= 2 code))
      (is (= "`--src` is not a flag `describe` takes" error)
          "and the message names the flag in the form the caller typed it"))))

(deftest a-scoped-verdict-is-refused-whatever-the-selection-reads-as
  (testing "the refusal turns on the flag being PRESENT, not on its value. `--select nil` is a
            request to scope, and keying off truthiness handed it a whole-model verdict — the
            belief the three exit codes exist to prevent."
    (doseq [v ["nil" "[(Module ?n)]"]]
      (let [{:keys [code error]} (#'cli/run ["check" "--select" v])]
        (is (= 2 code) (str "check --select " v))
        (is (= "`--select` is not a flag `check` takes" error))))))

(deftest a-format-refuses-what-its-answer-cannot-consume
  (testing "the index describes the WHOLE design however narrow the question that follows, so a
            selection it silently widened would be a promise it cannot keep"
    (let [{:keys [code error]} (#'cli/run ["describe" "--format" "index" "--select" "[(Module ?n)]"])]
      (is (= 2 code))
      (is (= "`--select` is not a flag `--format index` can consume" error)))))

(deftest an-unknown-verb-names-the-verbs-there-are
  (let [{:keys [code error]} (#'cli/run ["frobnicate"])]
    (is (= 2 code))
    (is (re-find #"`check` or `describe`" error))))

(deftest a-flag-a-verb-does-take-still-reaches-the-verb
  (testing "the table refuses; it does not narrow what a legal invocation gets. A check with the
            flags check reads still decides the whole model."
    (let [db (build/vars->cozo [#'card-bad])]
      (is (= 1 (:code (run-on db ["check" "--src" "src" "--format" "text"])))))))

;; ── the report is a function of the model, not of iteration order ────────────

(deftest an-unnameable-offender-is-named-by-a-key-that-survives-a-rebuild
  (testing "the label used to fall back to the eid, which the build that produced the db minted.
            Two builds of one model named the same node differently, so no report could be diffed
            against the previous run's."
    (let [db  (build/vars->cozo [#'card-bad])
          eid (ffirst (mapcat :offenders (law/check db)))]
      (is (some? eid) "precondition: the fixture law fired")
      (is (= "Bad" (law/offender-label db eid))
          "a named node still answers with its name")
      (is (some? (:entity/id (cq/entity db eid)))
          "and the key it would fall back to is one the assembler resolved it by, not the eid"))))

(deftest two-runs-over-one-model-print-the-same-bytes
  (testing "a diff of two reports IS the count delta only if nothing else moves between them.
            Cozo returns rows in engine order and the law registry in load order — neither is
            news, and a reader diffing two runs would read both as news."
    (doseq [fmt ["edn" "text"]]
      (let [out #(:out (run-on (build/vars->cozo [#'card-bad #'card-worse]) ["check" "--format" fmt]))]
        (is (= (out) (out)) (str "--format " fmt " is stable across runs"))))))

(defn- reversed-rows
  "`check` output with every offender row list reversed — a stand-in for the engine handing the
   same rows back in a different order, which is the thing the sort exists to absorb. The fixture
   alone cannot show it: two builds of one model assign eids the same way, so a report can look
   stable across runs while still being ordered by the engine."
  [violations]
  (mapv #(update % :offenders (comp vec reverse)) violations))

(deftest row-order-out-says-nothing-about-row-order-in
  (let [db  (build/vars->cozo [#'card-bad #'card-worse])
        raw (law/check db)]
    (is (< 1 (count (mapcat :offenders raw))) "precondition: more than one row to order")
    (testing "the edn report sorts on the names it prints"
      (is (= (cli/findings db raw) (cli/findings db (reversed-rows raw)))))
    (testing "the text report sorts on the forms it prints"
      (is (= (inst/violations-text db raw)
             (inst/violations-text db (reversed-rows raw)))))))

(deftest laws-are-ordered-by-the-pair-that-identifies-them
  (testing "`:key` is optional and most laws carry none, so the identifier across runs is the
            (structure, description) pair — and that is what orders the report"
    (let [db  (build/vars->cozo [#'card-bad #'card-worse])
          ord (mapv (juxt (comp str :structure) :law) (law/check db))]
      (is (= ord (sort ord))))))

;; ── report: measures, never decides ──────────────────────────────────────────

(defn- eid-of
  "The eid of the offender named `nm` in `violations` — what a selection would have to contain
   for that row to count as in scope."
  [db violations nm]
  (first (for [v violations, row (:offenders v), x row
               :when (and (int? x) (= nm (law/offender-label db x)))]
           x)))

(deftest a-report-counts-without-gating
  (testing "a project mid-migration runs this in CI to print the number, not to fail the build.
            Exit 1 would make it a gate, which is what `check` is for."
    (let [db (build/vars->cozo [#'card-bad #'card-worse])
          {:keys [code out]} (run-on db ["report"])]
      (is (= 0 code) "violations counted, and still exit 0")
      (is (re-find #"2 violations in the whole model" out)))))

(deftest an-undecidable-law-stops-a-report-whatever-the-scope
  (testing "an unevaluated law's offenders cannot be known to fall outside a scope, so a scoped
            report is no more decidable than an unscoped one"
    (with-redefs [law/check (fn [& _] (throw (ex-info "nope" {:unsupported [{:law "a law"}]})))]
      (doseq [args [["report"] ["report" "--select" "[(Module ?n)]"]]]
        (is (= 2 (:code (run-on (build/vars->cozo [#'card-good]) args)))
            (str args))))))

(deftest the-three-scope-counts-sum-to-the-whole
  (testing "totality is the property that lets a zero be read as a zero. A partition that dropped
            what it could not adjudicate would be a scoped verdict wearing a report's clothes."
    (let [db  (build/vars->cozo [#'card-bad #'card-worse])
          raw (law/check db)]
      (doseq [focus [nil #{} #{(eid-of db raw "Bad")} #{-1}]]
        (let [{:keys [counts laws]} (cli/tally raw focus)]
          (is (= (:total counts) (+ (:in counts) (:out counts) (:unadjudicable counts)))
              (str "focus " (pr-str focus)))
          (doseq [l laws]
            (is (= (:total l) (+ (:in l) (:out l) (:unadjudicable l)))
                (str "per-law, focus " (pr-str focus)))))))))

(deftest a-selection-narrows-the-count-and-nothing-else
  (let [db  (build/vars->cozo [#'card-bad #'card-worse])
        raw (law/check db)
        bad (eid-of db raw "Bad")]
    (is (= {:in 2 :out 0 :unadjudicable 0 :total 2} (:counts (cli/tally raw nil)))
        "no selection is the whole model, not an empty one")
    (is (= {:in 1 :out 1 :unadjudicable 0 :total 2} (:counts (cli/tally raw #{bad})))
        "and a selection moves rows between buckets without changing the total")))

(deftest a-row-no-selection-can-adjudicate-is-counted-as-such
  (testing "a law may bind a VALUE as its offender — an unresolvable type-reference reports the
            name it could not resolve, because the Schema carrying it has no eid anyone can act
            on. No node-selection can claim or disclaim such a row."
    (let [raw [{:structure :S :law "l" :offenders [["a-bare-name"] [7]]}]]
      (is (= {:in 0 :out 1 :unadjudicable 1 :total 2} (:counts (cli/tally raw #{})))
          "the value row is unadjudicable; the node row is merely out")
      (is (= {:in 2 :out 0 :unadjudicable 0 :total 2} (:counts (cli/tally raw nil)))
          "with no selection there is nothing to adjudicate against, so both are simply in"))))

(deftest report-names-offenders-only-for-the-rows-it-prints
  (testing "the naming pass is proportional to what is printed, not to the model — the same claim
            `--format text` already makes for check"
    (let [called (atom 0)
          db     (build/vars->cozo [#'card-bad #'card-worse])]
      (with-redefs [cli/findings (counting-findings called)]
        (is (= 0 (:code (run-on db ["report"]))) "the default format prints no offenders")
        (is (zero? @called) "…so it never names one")))))

(deftest report-refuses-nothing-check-refuses-and-check-still-refuses-it
  (testing "you may scope a report; you may not scope a verdict"
    (is (= 0 (:code (run-on (build/vars->cozo [#'card-bad]) ["report" "--select" "[(Module ?n)]"]))))
    (is (= 2 (:code (#'cli/run ["check" "--select" "[(Module ?n)]"]))))))
