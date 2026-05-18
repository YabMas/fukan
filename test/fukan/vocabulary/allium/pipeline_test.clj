(ns fukan.vocabulary.allium.pipeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.vocabulary.allium.pipeline :as pipeline]
            [fukan.model.build :as build]
            [malli.core :as m]))

(deftest pipeline-loads-fukan-corpus
  (testing "loading src/ produces a validated Model with the 5 fukan module-Containers"
    (let [model (pipeline/load-source "src")]
      (is (m/validate build/Model model)
          "loaded Model validates against fukan.model.build/Model schema")

      (testing "5 module-Containers exist"
        (let [module-containers (filter (fn [[_ p]]
                                          (= :primitive/container (:kind p)))
                                        (:primitives model))]
          ;; coordinates are: fukan/infra, fukan/web, fukan/web/views,
          ;;                  fukan/model, fukan/model/pipeline
          (is (>= (count module-containers) 5)
              "at least one Container per .allium file")))

      (testing "every module has an Allium::Module tag"
        (let [module-tag-apps (filter (fn [ta]
                                        (= {:namespace "Allium" :name "Module"}
                                           (:tag ta)))
                                      (:tag-apps model))]
          (is (>= (count module-tag-apps) 5)
              "Allium::Module tag applied to each module-Container"))))))

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
