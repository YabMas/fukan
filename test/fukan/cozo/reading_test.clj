(ns fukan.cozo.reading-test
  "The first P2 oracle: a reader ported onto Cozo must agree with its datascript
   twin. module-dependencies (calls ∪ data-adoption) over the real held model."
  (:require [clojure.test :refer [deftest is testing]]
            ;; composition root — registers the extractor so build-model "src"
            ;; merges the extracted code graph onto the design graph
            [fukan.infra.model]
            [fukan.model.pipeline :as pipeline]
            [fukan.cozo.db :as db]
            [fukan.cozo.mirror :as mirror]
            [fukan.canvas.core.assemble :as a]
            [fukan.cozo.reading :as reading]
            [canvas.vocab.code.operation :as operation]
            [canvas.vocab.code.module :as code-module]
            [canvas.vocab.code.subsystem :as subsystem]
            [canvas.vocab.code.effect :as effect]))

(deftest module-dependencies-matches-datascript
  (testing "the Cozo port agrees with the datascript reader on the real model"
    (let [ds  (pipeline/build-model "src")
          cdb (mirror/mirror ds)]
      (try
        (let [expected (code-module/module-dependencies ds)
              actual   (reading/module-dependencies cdb)]
          (is (seq expected) "precondition: the real model has module dependencies")
          (is (= expected actual)
              "cozo == datascript for the full module-dependency graph"))
        (finally (db/close cdb))))))

(defn- norm-lb
  "Normalize a latent-boundaries result to {module #{{:ops #{…} :clientele #{…}}}}
   so the comparison is robust to bundle order and within-tie ordering."
  [lb]
  (into {} (map (fn [[m bs]]
                  [m (set (map (fn [b] {:ops (set (:ops b)) :clientele (set (:clientele b))}) bs))]))
        lb))

(deftest latent-boundaries-matches-datascript
  (testing "the compositional Cozo latent-boundaries agrees with the datascript reader on the real model"
    (let [ds  (pipeline/build-model "src")
          cdb (mirror/mirror ds)]
      (try
        (let [expected (subsystem/latent-boundaries ds)
              actual   (reading/latent-boundaries cdb)]
          (is (seq expected) "precondition: the real model has a latent boundary")
          (is (= (norm-lb expected) (norm-lb actual))
              "cozo == datascript for the boundary bundles"))
        (finally (db/close cdb))))))

(deftest throw-spread-matches-datascript
  (testing "the Cozo throw-spread agrees with the datascript reader on the real model"
    (let [ds  (pipeline/build-model "src")
          cdb (mirror/mirror ds)]
      (try
        (let [expected (effect/throw-spread ds)]
          (is (seq (:direct expected)) "precondition: the real model has direct throwers")
          (is (= expected (reading/throw-spread cdb))))
        (finally (db/close cdb))))))

(deftest effect-drift-matches-datascript
  (testing "the Cozo effect-drift agrees with the datascript reader on the real model"
    (let [ds  (pipeline/build-model "src")
          cdb (mirror/mirror ds)]
      (try
        (is (= (effect/effect-drift ds) (reading/effect-drift cdb)))
        (finally (db/close cdb))))))

;; ── uncovered-calls: an extracted cross-module call with no intended delegation ──
(declare t-ucb)
(operation/Operation ^{:name "uca"} t-uca {:extracted true :calls [t-ucb]})
(operation/Operation ^{:name "ucb"} t-ucb {:extracted true})
(code-module/Module ^{:name "fukan.uca"} t-uca-mod {:extracted true :child [t-uca]})
(code-module/Module ^{:name "fukan.ucb"} t-ucb-mod {:extracted true :child [t-ucb]})

(deftest uncovered-calls-matches-datascript
  (testing "synthetic uncovered call + the real (covered) model"
    (let [ds (a/assemble-vars [#'t-uca #'t-ucb #'t-uca-mod #'t-ucb-mod])]
      (is (= #{["fukan.uca" "fukan.ucb"]} (code-module/uncovered-calls ds)) "precondition")
      (is (= (code-module/uncovered-calls ds) (reading/uncovered-calls (mirror/mirror ds)))))
    (let [ds (pipeline/build-model "src"), cdb (mirror/mirror ds)]
      (try (is (= (code-module/uncovered-calls ds) (reading/uncovered-calls cdb)))
           (finally (db/close cdb))))))

;; ── unrealized-dispatch: an authored cross-module delegation whose twins aren't connected ──
(declare t-pb)
(operation/Operation ^{:name "pa"} t-pa {:delegates [t-pb]})
(operation/Operation ^{:name "pb"} t-pb "authored callee")
(code-module/Module ^{:name "pm"} t-pm {:exposes [t-pa]})
(code-module/Module ^{:name "qm"} t-qm {:exposes [t-pb]})
(operation/Operation ^{:name "pa"} t-epa {:extracted true})   ; twin of pa — calls nothing
(operation/Operation ^{:name "pb"} t-epb {:extracted true})
(code-module/Module ^{:name "fukan.pm"} t-code-pm {:extracted true :child [t-epa]})
(code-module/Module ^{:name "fukan.qm"} t-code-qm {:extracted true :child [t-epb]})

(deftest unrealized-dispatch-matches-datascript
  (testing "synthetic unrealized delegation + the real (realized) model"
    (let [ds (a/assemble-vars [#'t-pa #'t-pb #'t-pm #'t-qm #'t-epa #'t-epb #'t-code-pm #'t-code-qm])]
      (is (= #{"pa"} (code-module/unrealized-dispatch ds)) "precondition")
      (is (= (code-module/unrealized-dispatch ds) (reading/unrealized-dispatch (mirror/mirror ds)))))
    (let [ds (pipeline/build-model "src"), cdb (mirror/mirror ds)]
      (try (is (= (code-module/unrealized-dispatch ds) (reading/unrealized-dispatch cdb)))
           (finally (db/close cdb))))))
