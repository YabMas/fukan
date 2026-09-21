(ns fukan.common.vocab.code.region-test
  "`Region` — namespaces claimed by prefix, membership resolved to a PARTITION, evidence the
   extracted call graph. What has to hold: every namespace sits in exactly one region even where
   prefixes overlap, so an undeclared dependency is one finding; and every law is silent in a
   project that declares no region."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.cozo.query :as cq]
            ;; the composition root — registers the Clojure FACT extractor and the Cozo check engine
            [fukan.infra.model]
            [fukan.common.vocab.code.region :as region]
            [fukan.common.extraction.clojure.module :as clj-module]
            [fukan.common.extraction.clojure.operation :as clj-op]))

(defn- law-desc [substr]
  (->> (:laws (s/structure-by-tag ::region/Region))
       (map :desc) (filter #(str/includes? % substr)) first))

(defn- offenders [db substr]
  (let [desc (law-desc substr)]
    (->> (law/check db) (filter #(= desc (:law %)))
         (mapcat :offenders)
         (map (fn [row] (mapv #(law/offender-label db %) row)))
         set)))

(defn- membership [db]
  (set (cq/q '[:find ?nsn ?rn :in $ %
               :where (in-region ?ns ?r) [?ns :entity/name ?nsn] [?r :entity/name ?rn]]
             db (s/vocab-rules))))

;; ── six namespaces whose names overlap the way a real region model's do ──────
;; `app.server` is a string prefix of `app.server-components.sse` and `app.serverless.fn` without
;; being the parent of either, and `app.db.execute.run` sits inside `app.db`'s subtree while
;; belonging to a region of its own.

(declare r-boot-fn r-sse-fn r-handler-fn r-core-fn r-run-fn)
(clj-op/Fn ^{:name "r-boot-fn"}       r-boot-fn       {:calls [r-handler-fn]})
(clj-op/Fn ^{:name "r-sse-fn"}        r-sse-fn        {:calls [r-boot-fn]})
(clj-op/Fn ^{:name "r-handler-fn"}    r-handler-fn    {:calls [r-core-fn]})
(clj-op/Fn ^{:name "r-core-fn"}       r-core-fn       {:calls [r-run-fn]})
(clj-op/Fn ^{:name "r-run-fn"}        r-run-fn        {:calls [r-core-fn]})
(clj-op/Fn ^{:name "r-serverless-fn"} r-serverless-fn)

(clj-module/Ns ^{:name "app.server"}                r-ns-boot       {:child [r-boot-fn]})
(clj-module/Ns ^{:name "app.server-components.sse"} r-ns-sse        {:child [r-sse-fn]})
(clj-module/Ns ^{:name "app.web.handler"}           r-ns-handler    {:child [r-handler-fn]})
(clj-module/Ns ^{:name "app.db.core"}               r-ns-core       {:child [r-core-fn]})
(clj-module/Ns ^{:name "app.db.execute.run"}        r-ns-run        {:child [r-run-fn]})
(clj-module/Ns ^{:name "app.serverless.fn"}         r-ns-serverless {:child [r-serverless-fn]})

(def ^:private fact-vars
  [#'r-boot-fn #'r-sse-fn #'r-handler-fn #'r-core-fn #'r-run-fn #'r-serverless-fn
   #'r-ns-boot #'r-ns-sse #'r-ns-handler #'r-ns-core #'r-ns-run #'r-ns-serverless])

;; ── the declaration: Boot → Web → Persistence → Execute; Live and App reach nothing ─
(declare r-web r-persistence r-execute)
(region/Region ^{:name "TestApp"}         r-app         {:prefix ["app"]})
(region/Region ^{:name "TestBoot"}        r-boot        {:prefix ["app.server"] :may-depend [r-web]})
(region/Region ^{:name "TestLive"}        r-live        {:prefix ["app.server-components"]})
(region/Region ^{:name "TestWeb"}         r-web         {:prefix ["app.web."] :may-depend [r-persistence]})
(region/Region ^{:name "TestPersistence"} r-persistence {:prefix ["app.db"] :may-depend [r-execute]})
(region/Region ^{:name "TestExecute"}     r-execute     {:prefix ["app.db.execute"]})

(def ^:private region-vars [#'r-app #'r-boot #'r-live #'r-web #'r-persistence #'r-execute])

(deftest membership-is-a-partition-where-prefixes-overlap
  (let [db (build/vars->cozo (into fact-vars region-vars))]
    (testing "a prefix claims at a `.` boundary: `app.server` claims neither `app.server-components.sse`
              nor `app.serverless.fn`, so the latter falls to the shorter `app` rather than to the
              longer prefix it merely spells the start of. And the longest claim wins:
              `app.db.execute.run` is in Execute alone although `app.db` and `app` claim it too. No
              membership edge was authored."
      (is (= #{["app.server"                "TestBoot"]
               ["app.server-components.sse" "TestLive"]
               ["app.serverless.fn"         "TestApp"]
               ["app.web.handler"           "TestWeb"]
               ["app.db.core"               "TestPersistence"]
               ["app.db.execute.run"        "TestExecute"]}
             (membership db))))
    (testing "and so every namespace is covered and no prefix is doubly claimed"
      (is (empty? (offenders db "belongs to a region")))
      (is (empty? (offenders db "at most one region"))))))

(deftest an-undeclared-dependency-is-one-finding
  (testing "Live reaching Boot and Execute reaching Persistence are undeclared, and each is ONE row.
            Were `app.server-components.sse` also in Boot, or `app.db.execute.run` also in
            Persistence, the same edge would come back once per pairing of its ends' regions"
    (let [db (build/vars->cozo (into fact-vars region-vars))]
      (is (= #{["app.server-components.sse" "app.server"  "TestLive"    "TestBoot"]
               ["app.db.execute.run"        "app.db.core" "TestExecute" "TestPersistence"]}
             (offenders db "cross-region"))))))

;; the namespace a dot-terminated prefix must NOT claim: `app.web.` claims below `app.web` only
(clj-module/Ns ^{:name "app.web"} r-ns-web-root)

(deftest the-cozo-lowering-agrees-with-the-clojure-predicate
  (testing "laws only ever run the CozoScript lowering, so it is checked against the Clojure
            definition over real names"
    (let [db    (build/vars->cozo (conj fact-vars #'r-ns-web-root))
          names (set (cq/q '[:find [?n ...]
                             :where [?e :structure/of :fukan.common.extraction.clojure.module/Ns]
                                    [?e :entity/name ?n]]
                           db))]
      (is (contains? names "app.web"))
      (doseq [p ["app.server" "app.server." "app.db" "app.db.execute" "app.web." "app.web" "app"]]
        (is (= (set (filter #(region/segment-prefix? % p) names))
               (set (cq/q [:find '[?n ...]
                           :where '[?e :structure/of :fukan.common.extraction.clojure.module/Ns]
                                  '[?e :entity/name ?n]
                                  [(list `region/segment-prefix? '?n p)]]
                          db)))
            (str "prefix " (pr-str p)))))))

(deftest segment-prefix-reads-a-trailing-dot-as-the-boundary
  (is (region/segment-prefix? "app.server" "app.server"))
  (is (region/segment-prefix? "app.server.http" "app.server"))
  (is (not (region/segment-prefix? "app.server-components.sse" "app.server")))
  (is (region/segment-prefix? "app.web.handler" "app.web."))
  (is (not (region/segment-prefix? "app.web" "app.web.")) "a trailing dot claims only below"))

;; ── coverage ─────────────────────────────────────────────────────────────────

(clj-op/Fn ^{:name "r-orphan-fn"} r-orphan-fn)
(clj-module/Ns ^{:name "elsewhere.orphan"} r-ns-orphan {:child [r-orphan-fn]})

(deftest an-unclaimed-namespace-is-an-offender-once-a-region-exists
  (let [db (build/vars->cozo (into fact-vars (concat region-vars [#'r-orphan-fn #'r-ns-orphan])))]
    (is (= #{["elsewhere.orphan"]} (offenders db "belongs to a region")))))

(deftest a-project-that-declares-no-region-asserts-nothing
  (testing "every law is vacuous without a Region, or merely loading `fukan.common` would turn every
            consumer's check red"
    (let [db (build/vars->cozo (into fact-vars [#'r-orphan-fn #'r-ns-orphan]))]
      (is (empty? (offenders db "belongs to a region")))
      (is (empty? (offenders db "cross-region")))
      (is (empty? (offenders db "at most one region")))
      (is (empty? (offenders db "acyclic"))))))

;; ── the tie longest-wins cannot break ────────────────────────────────────────

(region/Region ^{:name "TestDupA"} r-dup-a {:prefix ["dup.x"]})
(region/Region ^{:name "TestDupB"} r-dup-b {:prefix ["dup.x" "dup.y"]})

(deftest one-prefix-claimed-by-two-regions-is-one-finding
  (let [db (build/vars->cozo [#'r-dup-a #'r-dup-b])]
    (is (= #{["TestDupA" "TestDupB" "dup.x"]} (offenders db "at most one region")))))

;; ── reach ────────────────────────────────────────────────────────────────────

(declare r-cyc-y)
(region/Region ^{:name "TestCycX"} r-cyc-x {:prefix ["x."] :may-depend [r-cyc-y]})
(region/Region ^{:name "TestCycY"} r-cyc-y {:prefix ["y."] :may-depend [r-cyc-x]})

(deftest a-region-that-reaches-itself-is-incoherent-intent
  (let [db (build/vars->cozo [#'r-cyc-x #'r-cyc-y])]
    (is (= #{["TestCycX"] ["TestCycY"]} (offenders db "acyclic")))))

(deftest regions-neither-of-which-reaches-the-other-are-not-ordered
  (testing "Live and Web reach neither each other nor anything in common, and nothing asks them to:
            reach is a partial order, not a ranking"
    (let [db (build/vars->cozo (into fact-vars region-vars))]
      (is (empty? (offenders db "acyclic"))))))

;; ── sealing: the rule a drawing states by dashing a node ─────────────────────
;; `:may-depend` can say who may depend on a region; it cannot say that a region admits nothing
;; that was not licensed. A region says `:sealed` of itself; a region held as another's `:interior`
;; is sealed too, and additionally licenses its owner's subtree. The cases below are the whole of
;; that difference. Two have already been got wrong once and are pinned rather than left to be
;; rediscovered: resolving the licence through ANCESTRY lets an edge onto the OWNER license
;; reaching the owner's interior (0 offenders on brian where the answer was 201), and reporting an
;; edge against every seal it breaches rather than the tightest double-counts it (148 on brian).

(declare s-dom-fn s-dom-fn2 s-out-fn s-out2-fn s-core-fn s-run-fn)
(clj-op/Fn ^{:name "s-run-fn"}   s-run-fn)
(clj-op/Fn ^{:name "s-core-fn"}  s-core-fn  {:calls [s-run-fn]})   ; owner → its own interior
(clj-op/Fn ^{:name "s-dom-fn"}   s-dom-fn   {:calls [s-run-fn]})   ; licensed onto the OWNER only
(clj-op/Fn ^{:name "s-dom-fn2"}  s-dom-fn2  {:calls [s-core-fn]})  ; licensed, and stays outside
(clj-op/Fn ^{:name "s-out-fn"}   s-out-fn   {:calls [s-run-fn]})   ; unclaimed → the interior
(clj-op/Fn ^{:name "s-out2-fn"}  s-out2-fn  {:calls [s-core-fn]})  ; unclaimed → the outer seal

(clj-module/Ns ^{:name "app.db.core"}        s-ns-core {:child [s-core-fn]})
(clj-module/Ns ^{:name "app.db.execute.run"} s-ns-run  {:child [s-run-fn]})
(clj-module/Ns ^{:name "app.dom.core"}       s-ns-dom  {:child [s-dom-fn s-dom-fn2]})
(clj-module/Ns ^{:name "app.other.x"}        s-ns-out  {:child [s-out-fn]})
(clj-module/Ns ^{:name "app.other.y"}        s-ns-out2 {:child [s-out2-fn]})

(declare s-execute s-persistence)
(region/Region ^{:name "SExecute"}     s-execute     {:prefix ["app.db.execute"]})
(region/Region ^{:name "SPersistence"} s-persistence {:prefix     ["app.db"]
                                                     :sealed     true
                                                     :interior   [s-execute]
                                                     :may-depend [s-execute]})
(region/Region ^{:name "SDomain"}      s-domain      {:prefix ["app.dom"]
                                                     :may-depend [s-persistence]})

(def ^:private seal-vars
  [#'s-run-fn #'s-core-fn #'s-dom-fn #'s-dom-fn2 #'s-out-fn #'s-out2-fn
   #'s-ns-core #'s-ns-run #'s-ns-dom #'s-ns-out #'s-ns-out2
   #'s-execute #'s-persistence #'s-domain])

(deftest a-seal-admits-only-what-something-licensed
  (let [db (build/vars->cozo seal-vars)]
    (testing "`app.other.*` is claimed by no region — nothing declares a prefix over it"
      (is (= #{["app.db.core"        "SPersistence"]
               ["app.db.execute.run" "SExecute"]
               ["app.dom.core"       "SDomain"]}
             (membership db))))
    (testing "three crossings offend and three do not. The owner reaches its own interior freely;
              a region licensed onto the owner reaches the OWNER freely — and its reach stops
              there, so the same region touching the interior is an offence. A caller in no region
              offends against whichever seal it entered, which is what keeps this law meaningful
              while most of a codebase is undeclared."
      (is (= #{["app.dom.core" "app.db.execute.run" "SExecute"]
               ["app.other.x" "app.db.execute.run" "SExecute"]
               ["app.other.y" "app.db.core"        "SPersistence"]}
             (offenders db "sealed region"))))))

(deftest a-crossing-is-reported-against-the-tightest-seal-it-breaches
  ;; `app.other.x` → the interior breaches BOTH seals: it is licensed onto neither. It is reported
  ;; once, against the inner one. `app.dom.core` → the interior breaches only the inner seal, being
  ;; licensed onto the outer — so suppression has to read an actual breach and not mere nesting,
  ;; or that row would vanish with it.
  (let [db   (build/vars->cozo seal-vars)
        rows (offenders db "sealed region")
        into-interior (filter #(= "app.db.execute.run" (second %)) rows)]
    (is (= 2 (count into-interior)))
    (is (= #{"SExecute"} (set (map #(nth % 2) into-interior))))
    (is (= 1 (count (filter #(= "app.other.x" (first %)) rows))))))

(deftest a-region-that-seals-nothing-is-silent
  ;; `:sealed` absent is not `:sealed false`: a region that says nothing admits everything, and the
  ;; law is vacuous over a model where no region seals and none is anybody's interior.
  (let [db (build/vars->cozo (into fact-vars region-vars))]
    (is (empty? (offenders db "sealed region")))))
