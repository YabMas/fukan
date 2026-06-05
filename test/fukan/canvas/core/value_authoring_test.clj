(ns fukan.canvas.core.value-authoring-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.canvas.core.structure :as s]))

(deftest instance-value-record-holds-composition
  (let [iv (s/->InstanceValue :Box "A" nil {} [] false)]
    (is (= :Box (:tag iv)))
    (is (= "A" (:name iv)))
    (is (s/instance-value? iv))))

(def ^:private sample-iv (s/->InstanceValue :Box "A" nil {} [] false))

(deftest var-id-is-fully-qualified
  (is (= "fukan.canvas.core.value-authoring-test/sample-iv"
         (s/var-id #'sample-iv))))

;; Top-level vocab for the entity-constructor test: defstructure* is a macro so the
;; generated constructor macros (Attr, Ent) must be defined at compile-time (top level)
;; for later forms in the same compilation unit to use them.
(s/defstructure* Attr "an attribute" (slot :required (one :Bool)))
(s/defstructure* Ent  "an entity"
  (slot :attr (many Attr))
  (slot :links (one Ent)))

;; Forward declaration needed for the cyclic ref before the var exists.
(declare ev-test-User)

(def ev-test-name (Attr "name" (required true)))
(def ev-test-User (Ent "User" (attr ev-test-name) (links ev-test-User)))

(deftest entity-constructor-returns-value-with-var-refs
  (is (s/instance-value? ev-test-User))
  (is (= :Ent (:tag ev-test-User)))
  (is (= {:val/required true} (:scalars ev-test-name)))
  ;; the :links target is captured as the var, not deref'd yet
  (is (= [#'ev-test-User] (:targets (first (filter #(= :links (:rk %)) (:clauses ev-test-User)))))))
