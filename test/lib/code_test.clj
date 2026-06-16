(ns lib.code-test
  "Module roles + module-dependency readings on the lib.code grammar."
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [lib.code :as code]))

;; a fixture abstract concept (a portrait — no instances), realized by a module
(defstructure FxConcept "A fixture abstract concept (portrait).")

(code/Module ^{:name "fx-impl"}  t-fx-impl  {:realizes FxConcept})
(code/Module ^{:name "fx-infra"} t-fx-infra "a module that realizes no concept")

(deftest realizes-resolves-symbol-to-qualified-tag
  (testing "the :realizes symbol is rewritten to the concept's qualified tag string"
    (let [db (a/assemble-vars [#'t-fx-impl])
          m  (ffirst (d/q '[:find ?m :where [?m :entity/name "fx-impl"]] db))]
      (is (= ":lib.code-test/FxConcept" (:val/realizes (d/entity db m)))
          "the hook resolves FxConcept → its defining-ns+name tag"))))
