(ns fukan.vocabulary.allium.pipeline-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.vocabulary.allium.pipeline :as pipeline]
            [fukan.model.build :as build]
            [malli.core :as m]))

(deftest pipeline-loads-fukan-corpus
  (testing "loading src/ produces a validated Model covering all 62 fukan modules"
    (let [model (pipeline/load-source "src")]
      (is (m/validate build/Model model)
          "loaded Model validates against fukan.model.build/Model schema")

      (testing "every .allium file gets an Allium::Module tag"
        (let [module-tag-apps (filter (fn [ta]
                                        (= {:namespace "Allium" :name "Module"}
                                           (:tag ta)))
                                      (:tag-apps model))
              module-ids       (set (map (comp :id :target) module-tag-apps))]
          ;; The fukan corpus has 45 .allium files: infra/model, infra/server, web/handler,
          ;; web/views/shell, web/views/graph, web/views/sidebar, web/views/cytoscape,
          ;; web/views/breadcrumb, web/views/projection (stub), model/spec, model/pipeline,
          ;; project_layer/registry, project_layer/defaults, agent/api, agent/system,
          ;; agent/sci, agent/query, agent/edb, agent/views_loader, libs/coordinate,
          ;; libs/allium/parser, libs/boundary/parser, target/clojure/address,
          ;; target/clojure/source, target/clojure/types, target/clojure/blueprint,
          ;; target/clojure/projector, target/clojure/analyzer, constraint/ast,
          ;; constraint/builtins, constraint/derivations, constraint/derivations_extra,
          ;; constraint/evaluator, constraint/sort, constraint/well_known,
          ;; constraint/phase5, validation/violation, validation/phase4,
          ;; validation/rules_4a, validation/rules_4b, validation/rules_4c,
          ;; validation/rules_4d, validation/rules_4e, validation/rules_4f,
          ;; validation/rules_4g, vocabulary/allium/tags, vocabulary/allium/renderers,
          ;; vocabulary/allium/expression, vocabulary/allium/effect_canonicalise,
          ;; vocabulary/allium/analyzer, vocabulary/allium/pipeline,
          ;; vocabulary/boundary/tags, vocabulary/boundary/analyzer,
          ;; vocabulary/boundary/pipeline.
          (is (= 62 (count module-tag-apps))
              "Allium::Module tag applied to each .allium file in src/")
          (is (= #{"fukan/infra/model"
                   "fukan/model/build"
                   "fukan/model/primitives"
                   "fukan/model/relations"
                   "fukan/model/artifact"
                   "fukan/model/type"
                   "fukan/model/expression"
                   "fukan/model/effect"
                   "fukan/model/vocabulary"
                   "fukan/infra/server"
                   "fukan/web/handler"
                   "fukan/web/views/shell"
                   "fukan/web/views/graph"
                   "fukan/web/views/sidebar"
                   "fukan/web/views/cytoscape"
                   "fukan/web/views/breadcrumb"
                   "fukan/web/views/projection"
                   "fukan/model/spec"
                   "fukan/model/pipeline"
                   "fukan/project_layer/registry"
                   "fukan/project_layer/defaults"
                   "fukan/agent/api"
                   "fukan/agent/system"
                   "fukan/agent/sci"
                   "fukan/agent/query"
                   "fukan/agent/edb"
                   "fukan/agent/views_loader"
                   "fukan/libs/coordinate"
                   "fukan/libs/allium/parser"
                   "fukan/libs/boundary/parser"
                   "fukan/target/clojure/address"
                   "fukan/target/clojure/source"
                   "fukan/target/clojure/types"
                   "fukan/target/clojure/blueprint"
                   "fukan/target/clojure/projector"
                   "fukan/target/clojure/analyzer"
                   "fukan/constraint/ast"
                   "fukan/constraint/builtins"
                   "fukan/constraint/derivations"
                   "fukan/constraint/derivations_extra"
                   "fukan/constraint/evaluator"
                   "fukan/constraint/sort"
                   "fukan/constraint/well_known"
                   "fukan/constraint/phase5"
                   "fukan/validation/violation"
                   "fukan/validation/phase4"
                   "fukan/validation/rules_4a"
                   "fukan/validation/rules_4b"
                   "fukan/validation/rules_4c"
                   "fukan/validation/rules_4d"
                   "fukan/validation/rules_4e"
                   "fukan/validation/rules_4f"
                   "fukan/validation/rules_4g"
                   "fukan/vocabulary/allium/tags"
                   "fukan/vocabulary/allium/renderers"
                   "fukan/vocabulary/allium/expression"
                   "fukan/vocabulary/allium/effect_canonicalise"
                   "fukan/vocabulary/allium/analyzer"
                   "fukan/vocabulary/allium/pipeline"
                   "fukan/vocabulary/boundary/tags"
                   "fukan/vocabulary/boundary/analyzer"
                   "fukan/vocabulary/boundary/pipeline"}
                 module-ids)
              "module-Container ids are the canonical root-relative coordinates")))

      ;; Cross-module path canonicalisation is exercised by the
      ;; `corpus-cross-module-refs-resolve` test below, which walks the entire
      ;; loaded model and asserts no composite-named ref carries a stale `./`,
      ;; `../`, or `.allium` segment. The previous AnalysisResult-specific
      ;; assertion targeted a legacy fukan/model/pipeline::AnalysisResult
      ;; Container that was removed when pipeline.allium was rewritten against
      ;; the kernel substrate.
      )))

(deftest corpus-cross-module-refs-resolve
  (testing "no Composite-named ref in the loaded src/ Model carries a raw `.allium` suffix or `./`/`../` prefix"
    (let [model      (pipeline/load-source "src")
          composite-ids (atom #{})
          walk-type   (fn walk-type [t]
                        (when (map? t)
                          (when (= :type/composite (:case t))
                            (when-let [c (-> t :shape :container)]
                              (swap! composite-ids conj c)))
                          (when-let [of (:of t)] (walk-type of))
                          (when (= :type/union (:case t))
                            (doseq [x (:types t)] (walk-type x)))
                          (when-let [sem (:semantics t)]
                            (when (= :semantics/keyed (:case sem))
                              (walk-type (:key-type sem))))))]
      (doseq [[_ pr] (:primitives model)
              field (:fields pr)]
        (walk-type (:type-ref field)))
      (doseq [[_ pr] (:primitives model)
              param (:parameters pr)]
        (walk-type (:type-ref param)))
      (let [stale (filter (fn [c]
                            (or (str/starts-with? c "./")
                                (str/starts-with? c "../")
                                (str/ends-with? c ".allium")))
                          @composite-ids)]
        (is (empty? stale)
            (str "found stale composite-named container ids: " (pr-str stale)))))))

;; ---------------------------------------------------------------------------
;; Cross-module reference resolution (Task 14)
;; ---------------------------------------------------------------------------

(deftest cross-module-type-ref-resolves
  (testing "alias-qualified type refs resolve through the host file's use map"
    (let [model (pipeline/load-source "test/fixtures/allium/cross-module")
          ;; The target container in module `a`
          foo   (build/get-primitive model "a::Foo")
          ;; The source container in module `b` carrying the cross-module ref
          bar   (build/get-primitive model "b::Bar")
          foo-field (first (filter #(= "foo" (:name %)) (:fields bar)))]
      (is (some? foo) "module a's Foo Container exists")
      (is (= :primitive/container (:kind foo)))
      (is (some? bar) "module b's Bar Container exists")
      (is (some? foo-field) "Bar has a `foo` field")
      ;; The field type resolves to a Composite-named pointing at a::Foo,
      ;; NOT a Scalar carrying the literal "a/Foo".
      (is (= :type/composite (-> foo-field :type-ref :case))
          "cross-module ref resolved to Composite, not Scalar placeholder")
      (is (= "a::Foo" (-> foo-field :type-ref :shape :container))
          "Composite-named points at the correct resolved id"))))

;; ---------------------------------------------------------------------------
;; External-entity stub unification (§3.6)
;; ---------------------------------------------------------------------------

(defn- external-entity-tags-for
  "Helper: collect Allium::ExternalEntity tag applications targeting `id`."
  [model id]
  (filter (fn [ta]
            (and (= "Allium" (-> ta :tag :namespace))
                 (= "ExternalEntity" (-> ta :tag :name))
                 (= :target/primitive (-> ta :target :case))
                 (= id (-> ta :target :id))))
          (:tag-apps model)))

(deftest external-entity-stub-unifies
  (testing "external-entity stub merges with a unique real Container of the same local-name"
    (let [model (pipeline/load-source "test/fixtures/allium/stub-unify")
          real-user  (build/get-primitive model "users::User")
          stub-user  (build/get-primitive model "orders::User")
          order      (build/get-primitive model "orders::Order")
          customer   (first (filter #(= "customer" (:name %)) (:fields order)))]
      (is (some? real-user) "real users::User survives")
      ;; Real container keeps its full substrate (id + name fields).
      (is (= #{"id" "name"} (set (map :name (:fields real-user))))
          "real users::User keeps its substrate after merge")
      (is (nil? stub-user) "stub orders::User has been removed from primitives")
      ;; The Allium::ExternalEntity tag does not remain on the canonical.
      (is (empty? (external-entity-tags-for model "users::User"))
          "Allium::ExternalEntity tag dropped from canonical after merge")
      (is (empty? (external-entity-tags-for model "orders::User"))
          "no leftover ExternalEntity tag pointing at the dropped stub id")
      ;; The Order's customer field now resolves to the canonical users::User.
      (is (some? customer) "orders::Order has a customer field")
      (is (= :type/composite (-> customer :type-ref :case)))
      (is (= "users::User" (-> customer :type-ref :shape :container))
          "field type retargeted to canonical id"))))

(deftest external-entity-stub-stays-when-no-match
  (testing "external-entity stub with no real-Container match stays intact"
    (let [model    (pipeline/load-source "test/fixtures/allium/stub-no-match")
          stripe   (build/get-primitive model "payments::Stripe")
          ext-tags (external-entity-tags-for model "payments::Stripe")]
      (is (some? stripe) "stub Container remains in the Model")
      (is (= :primitive/container (:kind stripe)))
      (is (seq ext-tags) "Allium::ExternalEntity tag remains on the stub"))))

(deftest ambiguous-stub-stays-unresolved-with-warning
  (testing "external-entity stub with multiple real-Container matches stays unresolved"
    (let [stderr (java.io.StringWriter.)
          model  (binding [*err* stderr]
                   (pipeline/load-source "test/fixtures/allium/stub-ambiguous"))
          stub   (build/get-primitive model "audit::Logger")
          real-1 (build/get-primitive model "auth::Logger")
          real-2 (build/get-primitive model "billing::Logger")
          ext-tags (external-entity-tags-for model "audit::Logger")]
      (is (some? stub) "stub stays in the Model")
      (is (some? real-1) "auth::Logger stays in the Model")
      (is (some? real-2) "billing::Logger stays in the Model")
      (is (seq ext-tags) "Allium::ExternalEntity tag retained")
      (is (re-find #"ambiguous external-entity stub" (str stderr))
          "ambiguity warning was emitted to *err*"))))
