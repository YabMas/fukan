(ns fukan.validation.rules-4e-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.validation.rules-4e :as r4e]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]))

(defn- with-module
  "Add a module-Container at coord, optionally with Boundary::ModuleApi exports."
  [model coord exports]
  (cond-> (-> model
              (build/add-primitive (p/make-container {:id coord :label coord}))
              (build/add-tag-application
                (v/make-tag-application
                  {:tag {:namespace "Allium" :name "Module"}
                   :target {:case :target/primitive :id coord}})))
    exports
    (build/add-tag-application
      (v/make-tag-application
        {:tag {:namespace "Boundary" :name "ModuleApi"}
         :target {:case :target/primitive :id coord}
         :payload {:exported exports}}))))

(defn- with-subsystem
  "Add a subsystem composite at coord with given children + exports list."
  [model coord name children exports]
  (-> model
      (build/add-primitive
        (p/make-container {:id coord :label name :children (set children)}))
      (build/add-tag-application
        (v/make-tag-application
          {:tag {:namespace "Boundary" :name "Subsystem"}
           :target {:case :target/primitive :id coord}
           :payload {:name name}}))
      (build/add-tag-application
        (v/make-tag-application
          {:tag {:namespace "Boundary" :name "Exports"}
           :target {:case :target/primitive :id coord}
           :payload {:exported exports}}))))

(deftest subsystem-exports-unresolved-alias-is-error
  (let [model (-> (build/empty-model)
                  (with-module "m" nil)
                  (with-subsystem "sub/auth" "Auth" ["m"] ["wrong-alias/Foo"]))
        violations (r4e/check model)
        relevant (filter #(= :4e/subsystem-exports-unresolved (:kind %)) violations)]
    (is (= 1 (count relevant)))
    (is (= :error (-> relevant first :severity)))))

(deftest subsystem-exports-from-closed-module-private-is-error
  (let [model (-> (build/empty-model)
                  ;; Module m is CLOSED with exports ["PublicSurface"]:
                  (with-module "m" ["PublicSurface"])
                  ;; Subsystem exports m.PrivateThing — NOT in m's API:
                  (with-subsystem "sub/auth" "Auth" ["m"] ["m.PrivateThing"]))
        violations (r4e/check model)
        relevant (filter #(= :4e/subsystem-exports-private (:kind %)) violations)]
    (is (= 1 (count relevant)))
    (is (= :error (-> relevant first :severity)))))

(deftest subsystem-exports-from-open-module-not-flagged
  ;; Module m is OPEN (no ModuleApi tag). Subsystem can export anything from it.
  (let [model (-> (build/empty-model)
                  (with-module "m" nil)
                  (with-subsystem "sub/auth" "Auth" ["m"] ["m.SomethingFromOpenModule"]))
        violations (r4e/check model)
        relevant (filter #(= :4e/subsystem-exports-private (:kind %)) violations)]
    (is (zero? (count relevant)) "open module exports aren't restricted by 4e")))

(deftest clean-subsystem-produces-no-4e-errors
  ;; Module m is closed with PublicSurface exported; subsystem exports m.PublicSurface
  (let [model (-> (build/empty-model)
                  (with-module "m" ["PublicSurface"])
                  (with-subsystem "sub/auth" "Auth" ["m"] ["m.PublicSurface"]))
        errors (filter #(= :error (:severity %)) (r4e/check model))]
    (is (empty? errors))))
