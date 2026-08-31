(ns fukan.common.vocab.patterns.fulfilment-test
  "The SUPPLY half of a call, as authored intent: a Module declares that it fulfils a surface
   another Module owns.

   What has to hold: the declaration reads back as `fulfils` at domain altitude, both ends are
   constitutive (a half-written declaration is a finding, not a shrug), and a discriminator is
   UNAUTHORABLE — the vocabulary must not be able to become a second copy of the dispatch table."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.structure :as s]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.patterns.fulfilment :refer [Fulfilment]]))

(defn- laws-firing
  "The descriptions of every law that fires over `db` — the same shape the kernel tests use."
  [db]
  (set (map :law (law/check db))))

;; ── the shape: a renderer supplies a surface the reporting module owns ───────
(Operation ^{:name "render"} t-op-render "the surface — a dispatch point its owner defines")
(Module ^{:name "reporting"} t-mod-owner {:child [t-op-render]})
(Module ^{:name "charts"}    t-mod-charts "the satisfier — it implements `render`, and is not its owner")
(Fulfilment t-ful {:satisfier t-mod-charts :surface t-op-render})

(def ^:private vars [#'t-op-render #'t-mod-owner #'t-mod-charts #'t-ful])

(deftest a-fulfilment-reads-as-fulfils-at-domain-altitude
  (testing "the two slots are one relation from the outside — a law or a dependency graph asks
            `fulfils`, not `satisfier`-then-`surface`"
    (let [db (build/vars->cozo vars)]
      (is (= #{["charts" "render"]}
             (set (cq/q '[:find ?sn ?fn :in $ %
                          :where (fulfils ?s ?f) [?s :entity/name ?sn] [?f :entity/name ?fn]]
                        db (s/vocab-rules))))))))

(deftest a-complete-declaration-offends-nothing
  (let [db (build/vars->cozo vars)]
    (is (empty? (laws-firing db)))))

;; ── both ends are constitutive ───────────────────────────────────────────────
(Fulfilment t-ful-half {:satisfier t-mod-charts})

(deftest a-declaration-missing-its-surface-is-a-finding
  (testing "a fulfilment naming only one end states nothing. Cardinality one rather than
            optional is what makes that a finding instead of a silently inert node."
    (let [db (build/vars->cozo [#'t-op-render #'t-mod-owner #'t-mod-charts #'t-ful-half])]
      (is (contains? (laws-firing db)
                     "Fulfilment.surface requires exactly one (found none)")))))

;; ── the power the vocabulary deliberately lacks ──────────────────────────────

(deftest a-dispatch-value-is-not-authorable
  (testing "the surface's signature is the contract; WHICH values it dispatches on is the
            implementation's business. A slot for it would restate the dispatch table at
            vocabulary altitude, where the two copies would drift — so there is no slot, and
            authoring one fails closed rather than being silently dropped."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"discriminator.*is not a slot"
         (s/expand-instance :fukan.common.vocab.patterns.fulfilment/Fulfilment
                            '(f {:discriminator ":circle"}))))))
