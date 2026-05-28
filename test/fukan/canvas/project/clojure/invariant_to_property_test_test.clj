(ns fukan.canvas.project.clojure.invariant-to-property-test-test
  "Unit tests for the Phase 8 Sprint 5 invariant→property-test projection.
   Covers the Layer A skeleton — template shape, address derivation,
   carrier-metadata audit trail, prose envelope, and `clojure.test.check`
   idiom recognition. The implementing-LLM Sprint 6 fills in the actual
   generator + property body; here we lock the skeleton's structural
   contract."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fukan.canvas.project.core :as core]
            [fukan.canvas.project.clojure :as _clojure-lens]
            [fukan.project-layer.defaults :as defaults]))

(def ^:private registry (defaults/fukan-on-fukan))

;; Ground-truth: canvas/distributed/cluster :: MajorityRequiredForLeadership.
;; The Phase 7 trial closed this via the predicate path (now orphaned in
;; src/fukan/distributed/cluster.clj); Sprint 5 reroutes the projection
;; to the test-side `defspec` skeleton.
(def ^:private majority-required
  {:model-element-kind :Affordance
   :canvas-role        :canvas/invariant
   :stable-id          "distributed.cluster/invariant/MajorityRequiredForLeadership"
   :entity-name        "MajorityRequiredForLeadership"
   :module-coord       "distributed.cluster"
   :doc                "A node becomes leader only with majority vote grants. The cluster ensures liveness only when a strict majority of nodes participate in every leadership transition."
   :holds-that         "no node is leader for term T without > N/2 grants for T"})

(deftest produces-valid-projection
  (let [p (core/project :clojure majority-required {:registry registry})]
    (is (core/valid-projection? p))
    (is (= [] (core/validate-projection p)))))

(deftest projection-kind-and-routing
  (let [p (core/project :clojure majority-required {:registry registry})]
    (is (= :clojure/invariant-to-property-test (:projection-kind p)))
    (is (= :Affordance (:model-element-kind p)))))

(deftest target-derivation-uses-test-side-address
  (let [p (core/project :clojure majority-required {:registry registry})]
    (is (= "fukan.distributed.cluster-test" (-> p :target :namespace))
        "ns gets the -test suffix; mirrors :projection-kind/test convention")
    (is (= "majority-required-for-leadership-property" (-> p :target :symbol))
        "symbol is <kebab(entity-name)>-property; '-property' names the kind of test, not the test itself")
    (is (= "test/fukan/distributed/cluster_test.clj" (-> p :target :path))
        "path is prefixed test/ rather than src/")))

(deftest template-defspec-shape
  (let [p (core/project :clojure majority-required {:registry registry})
        t (:template p)]
    (is (str/includes? t "(defspec majority-required-for-leadership-property 100"))
    (is (str/includes? t "(prop/for-all [model (gen/return ::placeholder)]"))
    (is (str/includes? t "(throw (ex-info"))))

(deftest template-is-legal-clojure
  (testing "the rendered template reads as a single legal Clojure form"
    (let [p (core/project :clojure majority-required {:registry registry})
          form (read-string (:template p))]
      (is (seq? form))
      (is (= 'defspec (first form)))
      (is (= 'majority-required-for-leadership-property (second form)))
      (is (= 100 (nth form 2))
          "iteration count rides as the third element of the defspec form"))))

(deftest template-carries-clojure-test-check-imports-cue
  (testing "the template references the conventional clojure.test.check namespaces"
    (let [p (core/project :clojure majority-required {:registry registry})
          t (:template p)]
      (is (str/includes? t "gen/return")
          "generator placeholder uses gen/ namespace alias")
      (is (str/includes? t "prop/for-all")
          "property body uses prop/ namespace alias"))))

(deftest holds-that-prose-rides-in-template-as-comment-and-ex-info
  (let [p (core/project :clojure majority-required {:registry registry})
        t (:template p)]
    (is (str/includes? t ";; What must hold: no node is leader for term T without > N/2 grants for T.")
        "holds-that prose carried verbatim in an inline comment")
    (is (str/includes? t ":holds-that       \"no node is leader for term T without > N/2 grants for T\"")
        "holds-that prose also carried in the ex-info audit-trail payload")))

(deftest ex-info-payload-carries-canvas-id-name-iteration-count
  (let [p (core/project :clojure majority-required {:registry registry})
        t (:template p)]
    (is (str/includes? t ":canvas-id        \"distributed.cluster/invariant/MajorityRequiredForLeadership\""))
    (is (str/includes? t ":invariant-name   \"MajorityRequiredForLeadership\""))
    (is (str/includes? t ":iteration-count  100"))))

(deftest placeholder-generator-is-deliberately-broken
  (testing "gen/return ::placeholder is the audit-trail's broken generator — mechanical copies fail on first generation"
    (let [p (core/project :clojure majority-required {:registry registry})
          t (:template p)]
      (is (str/includes? t "(gen/return ::placeholder)"))
      (is (str/includes? t "not yet implemented")))))

(deftest template-frames-skeleton-as-audit-trail-closure-marker
  (testing "Sprint 6 — template comments tell the implementing-LLM to LEAVE the placeholder + throw intact (audit-trail skeleton); do not encourage generator authorship here"
    (let [p (core/project :clojure majority-required {:registry registry})
          t (:template p)]
      (is (str/includes? t "AUDIT-TRAIL CLOSURE MARKER")
          "template's inline comment names the placeholder as the audit-trail closure marker")
      (is (str/includes? t "Leave them intact")
          "template's inline comment tells the implementing-LLM to leave the skeleton intact")
      (is (not (str/includes? t "Replace the placeholder generator"))
          "no prose that asks the implementing-LLM to replace the placeholder"))))

(deftest prose-envelope-frames-property-test-not-predicate
  (let [p (core/project :clojure majority-required {:registry registry})
        pr (:prose p)]
    (is (str/starts-with? pr "Invariant: MajorityRequiredForLeadership."))
    (is (str/includes? pr "What must hold: no node is leader for term T without > N/2 grants for T."))
    (is (str/includes? pr "clojure.test.check"))
    (is (str/includes? pr "property test, not a predicate"))))

(deftest prose-envelope-frames-skeleton-as-audit-trail-closure-marker
  (testing "Sprint 6 — prose envelope frames the placeholder+throw as the audit-trail closure marker, NOT as something to replace with a generator"
    (let [p (core/project :clojure majority-required {:registry registry})
          pr (:prose p)]
      (is (str/includes? pr "audit-trail closure marker")
          "prose names the skeleton as the audit-trail closure marker")
      (is (str/includes? pr "Leave the")
          "prose tells the implementing-LLM to leave the skeleton intact")
      (is (str/includes? pr "land this skeleton verbatim")
          "prose frames the job as landing the skeleton verbatim")
      (is (not (str/includes? pr "should replace the placeholder generator"))
          "prose no longer asks the implementing-LLM to replace the placeholder generator")
      (is (not (str/includes? pr "replace the audit-trail `throw` body with"))
          "prose no longer asks the implementing-LLM to replace the throw body with the real assertion"))))

(deftest context-marks-projection-as-test-side
  (let [p (core/project :clojure majority-required {:registry registry})]
    (is (= "MajorityRequiredForLeadership" (-> p :context :invariant-name)))
    (is (= "no node is leader for term T without > N/2 grants for T"
           (-> p :context :holds-that)))
    (is (= "canvas/distributed/cluster.clj"
           (-> p :context :canvas-source-ref)))
    (is (= :projection-kind/property-test
           (-> p :context :projection-kind))
        "context carries the projection-kind so Layer B can detect test-side findings without re-deriving from the path")
    (is (true? (-> p :context :test-side?))
        "test-side? convenience flag mirrors the projection-kind discriminator")))

(deftest holds-that-absent-still-produces-valid-skeleton
  (testing "missing holds-that does not crash the projection; symbol is still derived from entity-name"
    (doseq [val [nil ""]]
      (let [el (assoc majority-required :holds-that val)
            p  (core/project :clojure el {:registry registry})]
        (is (core/valid-projection? p))
        (is (= "majority-required-for-leadership-property"
               (-> p :target :symbol)))
        (is (str/includes? (:template p)
                           "(defspec majority-required-for-leadership-property 100"))))))
