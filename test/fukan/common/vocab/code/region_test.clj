(ns fukan.common.vocab.code.region-test
  "`Region` — namespaces claimed by prefix, membership resolved to a PARTITION, evidence the
   extracted call graph. What has to hold: every namespace sits in exactly one region even where
   prefixes overlap, so an undeclared dependency is one finding; and every law is silent in a
   project that declares no region."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.cozo.query :as cq]
            ;; the composition root — registers the Clojure FACT extractor and the Cozo check engine
            [fukan.infra.model]
            [fukan.common.vocab.code.region :as region]
            [fukan.common.vocab.code.module :as vocab-module]
            [fukan.common.extraction.clojure.module :as clj-module]
            [fukan.common.extraction.clojure.operation :as clj-op]))

(defn- offenders
  "Offender rows of the law keyed `k`, each cell resolved to its name. Addressed by KEY, not by a
   substring of the description: an unknown key throws, where a description that has been reworded
   upstream silently matches nothing — and a test asserting `empty?` would then pass for the wrong
   reason, which is the failure this whole surface exists to prevent."
  [db k]
  (law/violation-rows db k))

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
      (is (empty? (offenders db :region/namespace-unclaimed)))
      (is (empty? (offenders db :region/prefix-ambiguous))))))

(deftest an-undeclared-dependency-is-one-finding
  (testing "Live reaching Boot and Execute reaching Persistence are undeclared, and each is ONE row.
            Were `app.server-components.sse` also in Boot, or `app.db.execute.run` also in
            Persistence, the same edge would come back once per pairing of its ends' regions"
    (let [db (build/vars->cozo (into fact-vars region-vars))]
      (is (= #{["app.server-components.sse" "app.server"  "TestLive"    "TestBoot"]
               ["app.db.execute.run"        "app.db.core" "TestExecute" "TestPersistence"]}
             (offenders db :region/undeclared-dependency))))))

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
    (is (= #{["elsewhere.orphan"]} (offenders db :region/namespace-unclaimed)))))

(deftest a-project-that-declares-no-region-asserts-nothing
  (testing "every law is vacuous without a Region, or merely loading `fukan.common` would turn every
            consumer's check red"
    (let [db (build/vars->cozo (into fact-vars [#'r-orphan-fn #'r-ns-orphan]))]
      (is (empty? (offenders db :region/namespace-unclaimed)))
      (is (empty? (offenders db :region/undeclared-dependency)))
      (is (empty? (offenders db :region/prefix-ambiguous)))
      (is (empty? (offenders db :region/may-depend-cyclic))))))

;; ── the tie longest-wins cannot break ────────────────────────────────────────

(region/Region ^{:name "TestDupA"} r-dup-a {:prefix ["dup.x"]})
(region/Region ^{:name "TestDupB"} r-dup-b {:prefix ["dup.x" "dup.y"]})

(deftest one-prefix-claimed-by-two-regions-is-one-finding
  (let [db (build/vars->cozo [#'r-dup-a #'r-dup-b])]
    (is (= #{["TestDupA" "TestDupB" "dup.x"]} (offenders db :region/prefix-ambiguous)))))

;; ── reach ────────────────────────────────────────────────────────────────────

(declare r-cyc-y)
(region/Region ^{:name "TestCycX"} r-cyc-x {:prefix ["x."] :may-depend [r-cyc-y]})
(region/Region ^{:name "TestCycY"} r-cyc-y {:prefix ["y."] :may-depend [r-cyc-x]})

(deftest a-region-that-reaches-itself-is-incoherent-intent
  (let [db (build/vars->cozo [#'r-cyc-x #'r-cyc-y])]
    (is (= #{["TestCycX"] ["TestCycY"]} (offenders db :region/may-depend-cyclic)))))

(deftest regions-neither-of-which-reaches-the-other-are-not-ordered
  (testing "Live and Web reach neither each other nor anything in common, and nothing asks them to:
            reach is a partial order, not a ranking"
    (let [db (build/vars->cozo (into fact-vars region-vars))]
      (is (empty? (offenders db :region/may-depend-cyclic))))))

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

(region/Region ^{:name "SExecute"}     s-execute     {:prefix ["app.db.execute"]})
;; no `:may-depend [s-execute]`: containment licenses the owner's reach into what it holds, and
;; declaring it as well is the redundancy the hygiene law reports.
(region/Region ^{:name "SPersistence"} s-persistence {:prefix   ["app.db"]
                                                     :sealed   true
                                                     :interior [s-execute]})
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
             (offenders db :region/seal-breached))))))

(deftest a-crossing-is-reported-against-the-tightest-seal-it-breaches
  ;; `app.other.x` → the interior breaches BOTH seals: it is licensed onto neither. It is reported
  ;; once, against the inner one. `app.dom.core` → the interior breaches only the inner seal, being
  ;; licensed onto the outer — so suppression has to read an actual breach and not mere nesting,
  ;; or that row would vanish with it.
  (let [db   (build/vars->cozo seal-vars)
        rows (offenders db :region/seal-breached)
        into-interior (filter #(= "app.db.execute.run" (second %)) rows)]
    (is (= 2 (count into-interior)))
    (is (= #{"SExecute"} (set (map #(nth % 2) into-interior))))
    (is (= 1 (count (filter #(= "app.other.x" (first %)) rows))))))

(deftest a-region-that-seals-nothing-is-silent
  ;; `:sealed` absent is not `:sealed false`: a region that says nothing admits everything, and the
  ;; law is vacuous over a model where no region seals and none is anybody's interior.
  (let [db (build/vars->cozo (into fact-vars region-vars))]
    (is (empty? (offenders db :region/seal-breached)))))

;; ── containment implies reach, downward and only downward ────────────────────
;; A whole that may not touch its own parts describes nothing anybody builds, so a region reaches
;; what it contains without declaring an edge onto it. The converse is an ordinary crossing: a part
;; reaching its container is declared or it is reported. Before this, fukan's two code-graph laws
;; disagreed — `seal-licensed` licensed the owner's subtree into its interior while `declared-dep`
;; denied the same edge — and the disagreement was paid by the author, in a `:may-depend` line
;; written to silence it (55 rows on brian).

(declare c-inner-fn c-outer-fn)
(clj-op/Fn ^{:name "c-inner-fn"} c-inner-fn {:calls [c-outer-fn]})  ; the part reaching its whole
(clj-op/Fn ^{:name "c-outer-fn"} c-outer-fn {:calls [c-inner-fn]})  ; the whole reaching its part

(clj-module/Ns ^{:name "app.pack.core"}      c-ns-outer {:child [c-outer-fn]})
(clj-module/Ns ^{:name "app.pack.part.impl"} c-ns-inner {:child [c-inner-fn]})

(region/Region ^{:name "CPart"}  c-part  {:prefix ["app.pack.part"]})
(region/Region ^{:name "CWhole"} c-whole {:prefix ["app.pack"] :child [c-part]})

(deftest a-region-reaches-what-it-contains-and-not-the-other-way
  (testing "the whole's call into its part follows from containment; the part's call back up is a
            crossing like any other, and stays reported until somebody declares it"
    (let [db (build/vars->cozo [#'c-inner-fn #'c-outer-fn #'c-ns-outer #'c-ns-inner
                                #'c-part #'c-whole])]
      (is (= #{["app.pack.part.impl" "app.pack.core" "CPart" "CWhole"]}
             (offenders db :region/undeclared-dependency))))))

;; ── the corollary: an edge containment already licenses says nothing ─────────
;; Which matters because containment ARRIVED LATE. Every canvas authored while the laws disagreed
;; wrote that edge as a workaround, and without a law saying so the workaround outlives the bug in
;; every file that needed it.

(region/Region ^{:name "HPart"}  h-part  {:prefix ["app.hp.part"]})
(region/Region ^{:name "HWhole"} h-whole {:prefix ["app.hp"] :child [h-part] :may-depend [h-part]})

(deftest an-edge-containment-already-licenses-is-reported
  (testing "one finding naming both ends, whose fix is deleting one line"
    (let [db (build/vars->cozo [#'h-part #'h-whole])]
      (is (= #{["HWhole" "HPart"]} (offenders db :region/redundant-may-depend))))))

;; the same shape with the member SEALED, where the edge is the opposite of redundant
(declare hs-core-fn hs-part-fn)
(clj-op/Fn ^{:name "hs-part-fn"} hs-part-fn)
(clj-op/Fn ^{:name "hs-core-fn"} hs-core-fn {:calls [hs-part-fn]})

(clj-module/Ns ^{:name "app.hs.core"}      hs-ns-core {:child [hs-core-fn]})
(clj-module/Ns ^{:name "app.hs.part.impl"} hs-ns-part {:child [hs-part-fn]})

(region/Region ^{:name "HSealed"} h-sealed {:prefix ["app.hs.part"] :sealed true})
(region/Region ^{:name "HOwner"}  h-owner  {:prefix ["app.hs"] :child [h-sealed] :may-depend [h-sealed]})

(deftest an-edge-a-seal-needs-is-not-redundant
  (testing "a sealed member admits a crossing only from a caller something licensed, and for its
            own container that licence IS this edge — so it is doing work, and deleting it would
            turn every owner→member crossing into a breach. `:child` + `:sealed` is how an author
            demands the owner's reach be stated rather than implied."
    (let [db (build/vars->cozo [#'hs-core-fn #'hs-part-fn #'hs-ns-core #'hs-ns-part
                                #'h-sealed #'h-owner])]
      (is (empty? (offenders db :region/redundant-may-depend)))
      (is (empty? (offenders db :region/seal-breached))
          "the owner's crossing into the sealed member is licensed by the edge"))))

;; ── a member may be a Module ─────────────────────────────────────────────────
;; Which is how one boundary says both of the things a boundary has: a Region claims a POSITION,
;; a Module claims CONTENTS through its pairing with code. Holding a Module is the only way a
;; Module is sealed or hidden at all — `:sealed` is a Region slot and stays one.

(declare m-run-fn m-pool-fn)
(clj-op/Fn ^{:name "m-run-fn"}  m-run-fn)
(clj-op/Fn ^{:name "m-pool-fn"} m-pool-fn)
(clj-op/Fn ^{:name "m-core-fn"} m-core-fn {:calls [m-run-fn]})   ; the owner reaching its interior
(clj-op/Fn ^{:name "m-out-fn"}  m-out-fn  {:calls [m-run-fn]})   ; licensed onto the OWNER only
(clj-op/Fn ^{:name "m-deep-fn"} m-deep-fn {:calls [m-pool-fn]})  ; …and reaching into its subtree

(clj-module/Ns ^{:name "mm.db.core"}          m-ns-core {:child [m-core-fn]})
(clj-module/Ns ^{:name "mm.db.execute"}       m-ns-exec {:child [m-run-fn]})
(clj-module/Ns ^{:name "mm.db.execute.pools"} m-ns-pool {:child [m-pool-fn]})
(clj-module/Ns ^{:name "mm.dom.use"}          m-ns-out  {:child [m-out-fn]})
(clj-module/Ns ^{:name "mm.dom.deep"}         m-ns-deep {:child [m-deep-fn]})

;; `execute` pairs with `mm.db.execute` by qualified suffix and `pools` with
;; `mm.db.execute.pools`: a boundary spanning several namespaces is a Module holding sub-Modules,
;; each paired 1:1, because the pairing is bijective by law.
(declare m-pools)
(vocab-module/Module ^{:name "execute"} m-execute {:child [m-pools]})
(vocab-module/Module ^{:name "pools"}   m-pools)

(def ^:private module-facts
  [#'m-run-fn #'m-pool-fn #'m-core-fn #'m-out-fn #'m-deep-fn
   #'m-ns-core #'m-ns-exec #'m-ns-pool #'m-ns-out #'m-ns-deep
   #'m-execute #'m-pools])

;; PLACED: the Module is the region's interior.
(region/Region ^{:name "MPersistence"} m-persistence {:prefix   ["mm.db"]
                                                      :sealed   true
                                                      :interior [m-execute]})
(region/Region ^{:name "MDomain"}      m-domain      {:prefix     ["mm.dom"]
                                                      :may-depend [m-persistence]})

;; UNPLACED: the same regions, with nobody containing the Module.
(region/Region ^{:name "UPersistence"} u-persistence {:prefix ["mm.db"] :sealed true})
(region/Region ^{:name "UDomain"}      u-domain      {:prefix     ["mm.dom"]
                                                      :may-depend [u-persistence]})

(deftest a-modules-pairing-outranks-the-prefix-that-covers-it
  (testing "the more specific claim wins, and a pairing names ONE namespace where a prefix names a
            subtree — so a paired namespace leaves the region whose prefix reaches it while the
            rest of that subtree stays"
    (let [db (build/vars->cozo (concat module-facts [#'m-persistence #'m-domain]))]
      (is (= #{["mm.db.core"          "MPersistence"]
               ["mm.db.execute"       "execute"]
               ["mm.db.execute.pools" "pools"]
               ["mm.dom.use"          "MDomain"]
               ["mm.dom.deep"         "MDomain"]}
             (membership db)))
      (is (empty? (offenders db :region/namespace-unclaimed))
          "a namespace a placed Module claims is covered — through the Module, not through a prefix")
      (is (empty? (offenders db :region/module-uncontained))
          "and nothing is ambiguous: the Module sits inside the region whose prefix reaches it"))))

(deftest a-module-held-as-an-interior-is-sealed
  (testing "MDomain holds a declared edge onto MPersistence and still may not reach what
            MPersistence hides — the sentence a seal exists to say, now said about a Module. Both
            crossings are reported against `execute`: `pools` is contained but seals nothing of its
            own, so the seal drawn around the boundary covers its whole subtree."
    (let [db (build/vars->cozo (concat module-facts [#'m-persistence #'m-domain]))]
      (is (= #{["mm.dom.use"  "mm.db.execute"       "execute"]
               ["mm.dom.deep" "mm.db.execute.pools" "execute"]}
             (offenders db :region/seal-breached))))))

(deftest the-owner-reaches-the-module-it-contains
  (testing "containment implies reach for a Module member exactly as for a region one — the owner's
            own call into its interior is neither a breach nor an undeclared dependency"
    (let [db (build/vars->cozo (concat module-facts [#'m-persistence #'m-domain]))
          rows (offenders db :region/undeclared-dependency)]
      (is (empty? (filter #(= "mm.db.core" (first %)) rows))))))

(deftest an-unplaced-module-claims-nothing
  (testing "declaring a Module is inert for membership until somebody contains it, so adopting a
            module and moving a boundary are two changes and the first moves no number. The
            crossing MDomain's edge licenses stays licensed — placing the Module is exactly what
            takes that licence away."
    (let [db (build/vars->cozo (concat module-facts [#'u-persistence #'u-domain]))]
      (is (= #{["mm.db.core"          "UPersistence"]
               ["mm.db.execute"       "UPersistence"]
               ["mm.db.execute.pools" "UPersistence"]
               ["mm.dom.use"          "UDomain"]
               ["mm.dom.deep"         "UDomain"]}
             (membership db))
          "every namespace stays where the prefixes put it")
      (is (empty? (offenders db :region/seal-breached)))
      (is (empty? (offenders db :region/module-uncontained)))
      (is (empty? (offenders db :region/namespace-unclaimed))))))

;; ── the one case specificity cannot settle ───────────────────────────────────

(clj-op/Fn ^{:name "hz-fn"} hz-fn)
(clj-module/Ns ^{:name "hz.db.hzexec"} hz-ns  {:child [hz-fn]})
(clj-module/Ns ^{:name "hz.dom.app"}   hz-ns2)
(vocab-module/Module ^{:name "hzexec"} hz-module)
(declare hz-persistence)
(region/Region ^{:name "HzDomain"}      hz-domain      {:prefix ["hz.dom"] :child [hz-module]})
(region/Region ^{:name "HzPersistence"} hz-persistence {:prefix ["hz.db"]})

(deftest a-module-placed-outside-the-region-whose-prefix-claims-it-is-reported
  (testing "the Module takes `hz.db.hzexec` out of HzPersistence's claim while sitting under
            HzDomain — a hole punched in one region by a declaration in another. Both ends are in
            the finding because either can be the wrong one: contain the Module where its code
            already sits, or narrow the prefix that reaches across it."
    (let [db (build/vars->cozo [#'hz-fn #'hz-ns #'hz-ns2 #'hz-module #'hz-domain #'hz-persistence])]
      (is (= #{["hzexec" "HzPersistence"]}
             (offenders db :region/module-uncontained))))))

