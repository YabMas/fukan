(ns fukan.common.extraction.clojure.fulfilment-law-test
  "The supply side's TEETH — the two laws that make a declared fulfilment load-bearing, and the
   two ways a method is deliberately invisible.

   What has to hold: each direction fires on its own cause; a CO-OWNED method is an offender of
   nothing and authorable by nobody (supply inside one module is implementation, not intent); an
   unpaired carrier yields the carrier law's finding and not a fulfilment finding on top of it;
   and a `Method` is invisible to the laws that define what a SURFACE is — it is not an `Fn`,
   so it neither pairs with an authored Operation nor reads as unaccounted-for surface."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.structure :as s]
            ;; the composition root — registers the Clojure FACT extractor and the Cozo check engine
            [fukan.infra.model]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.patterns.fulfilment :refer [Fulfilment]]
            [fukan.common.extraction.clojure.method :as clj-method]
            [fukan.common.extraction.clojure.module :as clj-module]
            [fukan.common.extraction.clojure.operation :as clj-op]))

(defn- offenders
  "The offender rows of the law keyed `k`, each cell resolved to a name."
  [db k]
  (->> (law/check db)
       (filter #(= k (:key %)))
       (mapcat :offenders)
       (map (fn [row] (mapv #(:entity/name (cq/entity db %)) row)))
       set))

;; ── the design: `reporting` owns the surface, `charts` supplies it ───────────
(Operation ^{:name "render"} t-op-render "the surface — a dispatch point reporting owns")
(Module ^{:name "reporting"} t-mod-reporting {:child [t-op-render]})
(Operation ^{:name "chart"} t-op-chart)
(Module ^{:name "charts"} t-mod-charts {:child [t-op-chart]})

;; ── the code: `app.charts` writes a method for `app.reporting`'s multimethod ──
(clj-op/Fn ^{:name "render"} t-fn-render)
(clj-module/Ns ^{:name "app.reporting"} t-ns-reporting {:child [t-fn-render]})

(clj-op/Fn ^{:name "chart"} t-fn-chart)
(clj-method/Method ^{:name "app.reporting/render[:bar]@app.charts"} t-meth-bar {:fulfils t-fn-render})
(clj-method/Method ^{:name "app.reporting/render[:pie]@app.charts"} t-meth-pie {:fulfils t-fn-render})
(clj-module/Ns ^{:name "app.charts"} t-ns-charts      {:child [t-fn-chart t-meth-bar]})
(clj-module/Ns ^{:name "app.charts"} t-ns-charts-bare {:child [t-fn-chart]})
(clj-module/Ns ^{:name "app.charts"} t-ns-charts-two  {:child [t-fn-chart t-meth-bar t-meth-pie]})

(Fulfilment ^{:name "charts-supplies-render"} t-ful
  {:satisfier t-mod-charts :surface t-op-render})

(def ^:private design-vars
  [#'t-op-render #'t-mod-reporting #'t-op-chart #'t-mod-charts])
(def ^:private surface-vars [#'t-fn-render #'t-ns-reporting])

(deftest a-declared-fulfilment-the-code-realizes-offends-neither-law
  (let [db (build/vars->cozo (concat design-vars surface-vars
                                     [#'t-fn-chart #'t-meth-bar #'t-ns-charts #'t-ful]))]
    (is (empty? (offenders db :correspondence/fulfilment-unrealized)))
    (is (empty? (offenders db :correspondence/fulfilment-undeclared)))))

(deftest one-declaration-covers-every-method-that-realizes-it
  (testing "the pairing is one-to-MANY, and that is the intent rather than an ambiguity to
            resolve: a declaration says WHICH module supplies WHICH surface, and however many
            dispatch values that takes is the implementation's business. (`Module ↦ Ns` has two
            laws demanding it be one-to-one; this pairing deliberately has none.)"
    (let [db (build/vars->cozo (concat design-vars surface-vars
                                       [#'t-fn-chart #'t-meth-bar #'t-meth-pie
                                        #'t-ns-charts-two #'t-ful]))]
      (is (empty? (offenders db :correspondence/fulfilment-undeclared)))
      (is (empty? (offenders db :correspondence/fulfilment-unrealized))))))

(deftest a-declaration-no-method-realizes-is-a-finding
  (testing "the rename case for the supply side — the satisfier moved or the dispatch was
            deleted, and the declaration goes on asserting an inversion that is gone"
    (let [db (build/vars->cozo (concat design-vars surface-vars
                                       [#'t-fn-chart #'t-ns-charts-bare #'t-ful]))]
      (is (= #{["charts-supplies-render" "charts" "render"]}
             (offenders db :correspondence/fulfilment-unrealized))
          "the row carries the satisfier and the surface — the declaration's own name is an
           authored symbol, legible to whoever wrote it and opaque to a reader meeting it cold"))))

(deftest a-cross-module-method-nothing-declares-is-a-finding
  (testing "left undeclared, this dependency is invisible to :may-depend conformance — a module
            could reach into any subsystem it liked as long as it did so through a dispatch table"
    (let [db (build/vars->cozo (concat design-vars surface-vars
                                       [#'t-fn-chart #'t-meth-bar #'t-ns-charts]))]
      (is (= #{["app.reporting/render[:bar]@app.charts" "app.charts"]}
             (offenders db :correspondence/fulfilment-undeclared))))))

;; ── the co-owned case: supply inside one module ──────────────────────────────
;; The method sits in the SAME namespace as the multimethod it implements. There is no dependency
;; to state — a module cannot depend on itself — so this is degenerate in exactly the way a plain
;; `defn` is, and it must cost an adopting project nothing at all.
(clj-method/Method ^{:name "app.reporting/render[:text]@app.reporting"} t-meth-coowned
  {:fulfils t-fn-render})
(clj-module/Ns ^{:name "app.reporting"} t-ns-reporting-coowned
  {:child [t-fn-render t-meth-coowned]})

(deftest a-co-owned-method-is-authorable-by-nobody-and-an-offender-of-nothing
  (testing "demanding a declaration here would be demanding that a module declare it depends on
            itself — which is also why both dependency relations already drop self-edges"
    (let [db (build/vars->cozo (concat design-vars
                                       [#'t-fn-render #'t-meth-coowned #'t-ns-reporting-coowned]))]
      (is (empty? (offenders db :correspondence/fulfilment-undeclared)))
      (is (empty? (offenders db :correspondence/fulfilment-unrealized))))))

;; ── the paired-carrier gates ─────────────────────────────────────────────────
;; A fulfilment rides two carriers, and can have a counterpart on the other stratum only where
;; BOTH have paired. Where one has not, the absence is already named at that carrier's own
;; altitude; a fulfilment finding there would diagnose the consequence beside its stated cause.

(clj-module/Ns ^{:name "app.graphs"} t-ns-graphs {:child [t-fn-chart]})

(deftest an-unpaired-satisfier-module-is-the-module-law-s-finding-alone
  (testing "the namespace was renamed, so `charts` pairs with nothing. ONE cause, ONE finding —
            the same scoping that keeps operation-unrealized quiet inside an unpaired module."
    (let [db (build/vars->cozo (concat design-vars surface-vars
                                       [#'t-fn-chart #'t-ns-graphs #'t-ful]))]
      (is (= #{["charts"]} (offenders db :correspondence/module-unrealized)))
      (is (empty? (offenders db :correspondence/fulfilment-unrealized))))))

(clj-method/Method ^{:name "app.reporting/render[:bar]@app.plotting"} t-meth-unclaimed
  {:fulfils t-fn-render})
(clj-module/Ns ^{:name "app.plotting"} t-ns-plotting {:child [t-meth-unclaimed]})

(deftest a-method-in-an-unadopted-namespace-has-no-coverage-gap-to-report
  (testing "relativized as public-unaccounted is: unrelativized, this law would assert that every
            cross-module method in the project is declared — true only of a fully adopted
            codebase, and the premise incremental adoption denies"
    (let [db (build/vars->cozo (concat design-vars surface-vars
                                       [#'t-meth-unclaimed #'t-ns-plotting]))]
      (is (empty? (offenders db :correspondence/fulfilment-undeclared))))))

;; ── a Method is not a surface ────────────────────────────────────────────────
;; The name below is deliberately the one that WOULD collide: matching is name-only inside
;; corresponding containers, so if a method were an `Fn` this node would pair with the authored
;; Operation `render` and would answer for it. Its SORT is what keeps it out, which is why the
;; extractor gives a method a sort of its own rather than a flag on `Fn`.
(clj-method/Method ^{:name "render"} t-meth-named-like-a-surface {:fulfils t-fn-render})
(clj-module/Ns ^{:name "app.reporting"} t-ns-colliding
  {:child [t-fn-render t-meth-named-like-a-surface]})

(deftest a-method-neither-pairs-with-an-operation-nor-reads-as-undeclared-surface
  (let [db (build/vars->cozo (concat design-vars
                                     [#'t-fn-render #'t-meth-named-like-a-surface
                                      #'t-ns-colliding]))]
    (testing "the design Operation `render` pairs with the function, and only the function"
      ;; the mirror stores a keyword value without its colon, so the tag reads back as a string
      (is (= #{["fukan.common.extraction.clojure.operation/Fn"]}
             (set (cq/q '[:find ?tag :in $ %
                          :where [?op :entity/name "render"] (design ?op)
                                 (corresponds ?op ?t) [?t :structure/of ?tag]]
                        db (s/vocab-rules))))))
    (testing "and the method is not unaccounted-for surface — `public` is an Fn predicate"
      (is (empty? (offenders db :correspondence/public-unaccounted))))))
