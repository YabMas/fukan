(ns fukan.common.extraction.clojure.correspondence-law-test
  "The design↔code correspondence's TEETH — the two laws that make an adopted module's
   specification load-bearing rather than decorative.

   Until these existed, coverage was a set of READINGS reachable only from a REPL. A project
   could rename a function out from under an Operation that claimed it and every gate stayed
   green, which meant element-level adoption bought a document and no guarantee.

   What has to hold: each law fires on its own cause, EXACTLY ONE law fires per cause (the two
   are scoped so a whole unrealized module is reported once, at the module, not once per
   operation inside it), and both are vacuous in a design-only build — a model built with no
   code root pairs nothing, and a law that reported every Module in such a project would be
   describing the checker's inputs rather than the project."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.cozo.query :as cq]
            ;; the composition root — registers the Clojure FACT extractor and the Cozo check engine
            [fukan.infra.model]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.extraction.clojure.module :as clj-module]
            [fukan.common.extraction.clojure.operation :as clj-op]))

(defn- offenders
  "The offender rows of the law keyed `k`, each resolved to names. Addressed by KEY rather than
   by matching the description, so a reworded law breaks nothing and a RETIRED one throws here
   instead of reporting an empty worklist forever."
  [db k]
  (->> (law/check db)
       (filter #(= k (:key %)))
       (mapcat :offenders)
       (map (fn [row] (mapv #(:entity/name (cq/entity db %)) row)))
       set))

;; ── the design side: one module, two operations ──────────────────────────────
(Operation ^{:name "read-edn"}  t-op-read)
(Operation ^{:name "write-edn"} t-op-write)
(Module ^{:name "core-thing"} t-mod {:child [t-op-read t-op-write]})

;; ── the fact side: the namespace and functions that realize them ─────────────
;; A canvas short-name is a separator-agnostic dotted SUFFIX of the code namespace, so
;; `core-thing` twins with `app.core.thing` — the root pairing every operation nests within.
(clj-op/Fn ^{:name "read-edn"}  t-fn-read)
(clj-op/Fn ^{:name "write-edn"} t-fn-write)
(clj-module/Ns ^{:name "app.core.thing"} t-ns {:child [t-fn-read t-fn-write]})

(def ^:private design-vars [#'t-op-read #'t-op-write #'t-mod])

(deftest a-fully-realized-module-offends-neither-law
  (let [db (build/vars->cozo (into design-vars [#'t-fn-read #'t-fn-write #'t-ns]))]
    (is (empty? (offenders db :correspondence/module-unrealized)))
    (is (empty? (offenders db :correspondence/operation-unrealized)))))

;; ── an operation the code does not have ──────────────────────────────────────
;; The module still pairs; one of its operations does not. This is the rename case: the design
;; goes on claiming a capability after the function that provided it is gone.
(clj-module/Ns ^{:name "app.core.thing"} t-ns-partial {:child [t-fn-read]})

(deftest an-unrealized-operation-is-named-with-the-module-it-was-claimed-in
  (let [db (build/vars->cozo (into design-vars [#'t-fn-read #'t-ns-partial]))]
    (is (= #{["write-edn" "core-thing"]} (offenders db :correspondence/operation-unrealized))
        "the offender carries the module too — an operation name alone leaves the reader to
         find where it was claimed")
    (testing "and the module itself is fine: it pairs"
      (is (empty? (offenders db :correspondence/module-unrealized))))))

;; ── a module the code does not have ──────────────────────────────────────────
;; The namespace was renamed, so nothing suffix-matches `core-thing` any more.
(clj-module/Ns ^{:name "app.core.other"} t-ns-renamed {:child [t-fn-read t-fn-write]})

(deftest an-unrealized-module-is-reported-once-at-the-module
  (testing "ONE cause, ONE finding. The operation law is scoped to modules that pair, so an
            unpaired module takes its operations out of that law's view and this law names the
            module instead. Unscoped, the same rename would report the module AND every
            operation inside it — three findings for one edit, and the two that name operations
            would send a reader to look at code that is not wrong."
    (let [db (build/vars->cozo (into design-vars [#'t-fn-read #'t-fn-write #'t-ns-renamed]))]
      (is (= #{["core-thing"]} (offenders db :correspondence/module-unrealized)))
      (is (empty? (offenders db :correspondence/operation-unrealized))))))

;; ── the gate: a design-only build ────────────────────────────────────────────

(deftest both-laws-are-vacuous-when-no-code-was-extracted
  (testing "`describe` builds with no code root, and so does any project modelling ahead of its
            implementation. Neither law may fire there: nothing has been contradicted, there is
            simply nothing yet to contradict it. The module law is gated on a namespace
            existing; the operation law needs no gate of its own — being scoped to paired
            modules already makes it vacuous."
    (let [db (build/vars->cozo design-vars)]
      (is (empty? (offenders db :correspondence/module-unrealized)))
      (is (empty? (offenders db :correspondence/operation-unrealized))))))

(deftest the-law-keys-are-addressable
  (testing "keys are the named surface these laws are read through — `violations-of` throws on
            an unknown one, so a reader that outlives its law fails loudly instead of going
            quiet. A law without a key can only be addressed by its description, which is prose
            and moves."
    (let [db (build/vars->cozo design-vars)]
      (is (set? (law/violations-of db :correspondence/module-unrealized)))
      (is (set? (law/violations-of db :correspondence/operation-unrealized)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no law keyed"
                            (law/violations-of db :correspondence/nonexistent))))))

;; ── the other direction: surface the design never claimed ────────────────────
;; `helper` is public and inside the adopted namespace, and no Operation models it.

(clj-op/Fn ^{:name "helper"}  t-fn-helper)
(clj-op/Fn ^{:name "hidden"}  t-fn-hidden  {:private true})
(clj-op/Fn ^{:name "wired"}   t-fn-wired   {:export true})
(clj-module/Ns ^{:name "app.core.thing"} t-ns-wide
  {:child [t-fn-read t-fn-write t-fn-helper t-fn-hidden t-fn-wired]})

(deftest an-unmodelled-public-function-is-undeclared-surface
  (let [db (build/vars->cozo (into design-vars [#'t-fn-read #'t-fn-write #'t-fn-helper
                                                #'t-fn-hidden #'t-fn-wired #'t-ns-wide]))]
    (is (= #{["helper" "app.core.thing"]} (offenders db :correspondence/public-unaccounted))
        "the two ways out of this finding are the two honest ones — model it as intent, or
         make it `defn-`")
    (testing "a private function is settled by definition, and ^:export is a declaration in its
              own right — neither is undeclared surface"
      (is (not (contains? (offenders db :correspondence/public-unaccounted) ["hidden" "app.core.thing"])))
      (is (not (contains? (offenders db :correspondence/public-unaccounted) ["wired" "app.core.thing"]))))))

;; the same functions in a namespace no Module claims
(clj-module/Ns ^{:name "app.unclaimed.thing"} t-ns-unclaimed {:child [t-fn-helper]})

(deftest an-unadopted-namespace-has-no-coverage-gap-to-report
  (testing "unrelativized, this law asserts total coverage of the project's public surface —
            true only of a fully adopted codebase, and the premise incremental adoption denies.
            An unclaimed namespace is not a gap; it is the adoption frontier."
    (let [db (build/vars->cozo (into design-vars [#'t-fn-read #'t-fn-write #'t-ns
                                                  #'t-fn-helper #'t-ns-unclaimed]))]
      (is (empty? (offenders db :correspondence/public-unaccounted))))))


;; ── agreement: the pairing holds, the two halves disagree ────────────────────
;; Each of these three namespaces twins with the same design module — one at a time, so each
;; test builds a db holding exactly the fact side it is about.

(Operation ^{:name "parse"} t-op-typed {:signature [:=> [:catn [:s :string]] :string]})
(Module ^{:name "typed-thing"} t-mod-typed {:child [t-op-typed]})
(def ^:private typed-design-vars [#'t-op-typed #'t-mod-typed])

(clj-op/Fn ^{:name "parse"} t-fn-agreeing    {:signature [:=> [:cat :string] :string]})
(clj-op/Fn ^{:name "parse"} t-fn-disagreeing {:signature [:=> [:cat :string] :int]})
(clj-op/Fn ^{:name "parse"} t-fn-untyped)

(clj-module/Ns ^{:name "app.typed.thing"} t-ns-agree    {:child [t-fn-agreeing]})
(clj-module/Ns ^{:name "app.typed.thing"} t-ns-disagree {:child [t-fn-disagreeing]})
(clj-module/Ns ^{:name "app.typed.thing"} t-ns-untyped  {:child [t-fn-untyped]})

(deftest an-agreeing-signature-is-not-a-finding
  (let [db (build/vars->cozo (into typed-design-vars [#'t-fn-agreeing #'t-ns-agree]))]
    (is (empty? (offenders db :correspondence/signature-disagrees)))))

(deftest a-changed-return-type-is-the-drift-this-law-exists-for
  (testing "the pairing still holds — same name, corresponding containers — so none of the
            existence laws fire. Only agreement catches it."
    (let [db (build/vars->cozo (into typed-design-vars [#'t-fn-disagreeing #'t-ns-disagree]))]
      (is (= #{["parse" "typed-thing"]} (offenders db :correspondence/signature-disagrees)))
      (is (empty? (offenders db :correspondence/operation-unrealized)))
      (is (empty? (offenders db :correspondence/public-unaccounted))))))

(deftest a-declared-type-the-code-does-not-state-is-an-unverified-claim
  (testing "symmetric on purpose. Under \"every operation is typeable\" an absent type is not
            modesty but a gap — `:any` and `:nil` are the honest declarations for the cases that
            look untypeable."
    (let [db (build/vars->cozo (into typed-design-vars [#'t-fn-untyped #'t-ns-untyped]))]
      (is (= #{["parse" "typed-thing"]} (offenders db :correspondence/signature-disagrees))))))

;; ── the two disagreements a SET comparison cannot see ────────────────────────

(Operation ^{:name "swap"} t-op-swap {:signature [:=> [:catn [:a :string] [:b :int]] :any]})
(Operation ^{:name "pair"} t-op-pair {:signature [:=> [:catn [:x :any] [:y :any]] :any]})
(Module ^{:name "order-thing"} t-mod-order {:child [t-op-swap t-op-pair]})

(clj-op/Fn ^{:name "swap"} t-fn-swapped  {:signature [:=> [:cat :int :string] :any]}) ; same types, wrong order
(clj-op/Fn ^{:name "pair"} t-fn-one-arg  {:signature [:=> [:cat :any] :any]})         ; same type, wrong arity
(clj-module/Ns ^{:name "app.order.thing"} t-ns-order {:child [t-fn-swapped t-fn-one-arg]})

(deftest a-reordering-of-same-typed-parameters-is-a-disagreement
  (testing "`[:catn [:a :string] [:b :int]]` against `[:cat :int :string]`. As SETS these are
            equal — {string, int} both ways — which is why the law compares index for index."
    (let [db (build/vars->cozo [#'t-op-swap #'t-op-pair #'t-mod-order
                                #'t-fn-swapped #'t-fn-one-arg #'t-ns-order])]
      (is (contains? (offenders db :correspondence/signature-disagrees) ["swap" "order-thing"])))))

(deftest an-arity-difference-among-same-typed-parameters-is-a-disagreement
  (testing "the case that made this law positional rather than set-based, and the one the
            original framing missed: `#{:any}` is `#{:any}` whether there is one parameter or
            three, so a set comparison cannot see arity at all. `fukan.cli/findings` declared
            two parameters and annotated one, and passed."
    (let [db (build/vars->cozo [#'t-op-swap #'t-op-pair #'t-mod-order
                                #'t-fn-swapped #'t-fn-one-arg #'t-ns-order])]
      (is (contains? (offenders db :correspondence/signature-disagrees) ["pair" "order-thing"])))))

;; ── varargs, now that the dialect spells it ──────────────────────────────────

(Operation ^{:name "spit-all"} t-op-varargs
  {:signature [:=> [:catn [:path :string] [:rest [:* :any]]] :any]})
(Module ^{:name "varargs-thing"} t-mod-varargs {:child [t-op-varargs]})
(clj-op/Fn ^{:name "spit-all"} t-fn-varargs {:signature [:=> [:cat :string [:* :any]] :any]})
(clj-module/Ns ^{:name "app.varargs.thing"} t-ns-varargs {:child [t-fn-varargs]})

(deftest a-varargs-signature-agrees-across-the-two-strata
  (testing "malli's `[:* X]` is how a rest parameter is typed, and until the dialect accepted it
            every varargs function was untypeable — 110 of nido's public functions. Both strata
            reduce it to the same node, so the pair agrees."
    (let [db (build/vars->cozo [#'t-op-varargs #'t-mod-varargs #'t-fn-varargs #'t-ns-varargs])]
      (is (empty? (offenders db :correspondence/signature-disagrees))))))

;; ── multi-arity: the case that started this ──────────────────────────────────
;; `gates` has two arities, the shorter delegating to the longer. Until the signature became one
;; `Schema`, this was unmodellable: `Operation` carried a single flat `:in`/`:out`, so a function
;; with two shapes had no spelling — and inventing an arity vocabulary for it would have been
;; fukan restating what malli's `[:function …]` already says.

(Operation ^{:name "gates"} t-op-multi
  {:signature [:function [:=> [:catn [:project :string]] :any]
                         [:=> [:catn [:project :string] [:live-names :any]] :any]]})
(Module ^{:name "multi-thing"} t-mod-multi {:child [t-op-multi]})

;; the code's annotation: same two arities, POSITIONAL and in the opposite order
(clj-op/Fn ^{:name "gates"} t-fn-multi
  {:signature [:function [:=> [:cat :string :any] :any]
                         [:=> [:cat :string] :any]]})
;; …and a version that implements only one of them
(clj-op/Fn ^{:name "gates"} t-fn-half {:signature [:=> [:cat :string] :any]})

(clj-module/Ns ^{:name "app.multi.thing"} t-ns-multi {:child [t-fn-multi]})
(clj-module/Ns ^{:name "app.multi.thing"} t-ns-half  {:child [t-fn-half]})

(deftest a-multi-arity-signature-agrees-arity-for-arity
  (testing "arities match by SHAPE, not position — `[:function A B]` and `[:function B A]` declare
            the same function. Param names are ignored too, so a `[:catn …]` spec agrees with the
            `[:cat …]` annotation a developer would actually write."
    (let [db (build/vars->cozo [#'t-op-multi #'t-mod-multi #'t-fn-multi #'t-ns-multi])]
      (is (empty? (offenders db :correspondence/signature-disagrees))))))

(deftest a-function-missing-a-declared-arity-disagrees
  (testing "one arity against two is not the same declaration, and neither is an arrow against a
            multi-arity function — the kinds differ before the arities are even compared"
    (let [db (build/vars->cozo [#'t-op-multi #'t-mod-multi #'t-fn-half #'t-ns-half])]
      (is (= #{["gates" "multi-thing"]} (offenders db :correspondence/signature-disagrees))))))

;; ── the pairing must be one-to-one ───────────────────────────────────────────
;; Matching by NAME is what makes adoption cheap — no module names the namespace realizing it —
;; and a name can match twice. When it does, every law above reads a pairing that is no longer a
;; pairing, and reads it silently: a module counts as realized by code that was somebody else's,
;; and a namespace counts as adopted so the functions in it stop being unaccounted for.

;; One module, two namespaces: `views` is a dotted suffix of both.
(Module ^{:name "views"} t-mod-views)
(clj-module/Ns ^{:name "app.ui.views"}     t-ns-ui-views)
(clj-module/Ns ^{:name "app.notion.views"} t-ns-notion-views)

(deftest a-module-matching-two-namespaces-is-an-offender
  (testing "the offender carries both namespaces, because the edit is a name that picks one"
    (let [db (build/vars->cozo [#'t-mod-views #'t-ns-ui-views #'t-ns-notion-views])]
      (is (= #{["views" "app.notion.views" "app.ui.views"]}
             (offenders db :correspondence/module-ambiguous))
          "ordered by name, so one ambiguity is one finding and not the same pair twice")
      (testing "and neither namespace is claimed twice, so the other direction stays quiet"
        (is (empty? (offenders db :correspondence/namespace-ambiguous))))
      (testing "nor does the module read as unrealized — it pairs, twice over"
        (is (empty? (offenders db :correspondence/module-unrealized)))))))

;; Two modules, one namespace: `views` and `ui-views` are both dotted suffixes of `app.ui.views`.
(Module ^{:name "ui-views"} t-mod-ui-views)

(deftest a-namespace-matching-two-modules-is-an-offender
  (testing "the other direction, and a different edit — which of two design boundaries owns it"
    (let [db (build/vars->cozo [#'t-mod-views #'t-mod-ui-views #'t-ns-ui-views])]
      (is (= #{["app.ui.views" "ui-views" "views"]}
             (offenders db :correspondence/namespace-ambiguous)))
      (testing "each module matched exactly one namespace, so the first direction stays quiet"
        (is (empty? (offenders db :correspondence/module-ambiguous)))))))

(deftest an-unambiguous-pairing-offends-neither
  (let [db (build/vars->cozo (into design-vars [#'t-fn-read #'t-fn-write #'t-ns]))]
    (is (empty? (offenders db :correspondence/module-ambiguous)))
    (is (empty? (offenders db :correspondence/namespace-ambiguous)))))
