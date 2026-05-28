(ns fukan.model.artifact-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.model.artifact :as a]
            [malli.core :as m]))

(deftest code-function
  (let [art (a/make-code-function "clojure" "fukan.auth/process-submission"
                                  {:file "src/fukan/auth.clj" :line 42})]
    (is (= :artifact/code (:case art)))
    (is (= "clojure" (:language art)))
    (is (= :code/function (get-in art [:sub :case])))
    (is (= "fukan.auth/process-submission" (get-in art [:sub :qualified-name])))
    (is (m/validate a/Artifact art))))

(deftest code-data-structure
  (let [art (a/make-code-data-structure "clojure" "fukan.orders/Order")]
    (is (= :code/data-structure (get-in art [:sub :case])))
    (is (m/validate a/Artifact art))))

(deftest identity-per-case
  (let [a1 (a/make-code-function "clojure" "fukan.auth/x" {:file "a.clj"})
        a2 (a/make-code-function "clojure" "fukan.auth/x" {:file "b.clj"})]
    (is (= (a/artifact-identity a1) (a/artifact-identity a2))
        "source-location is non-identity")))

(deftest identity-discriminates-by-case
  (let [f  (a/make-code-function       "clojure" "ns/X" {:file "x.clj"})
        ds (a/make-code-data-structure "clojure" "ns/X")]
    (is (not= (a/artifact-identity f) (a/artifact-identity ds))
        "Function vs DataStructure share qualified-name but differ on case")))

(deftest identity-discriminates-by-language
  (let [a (a/make-code-function "clojure"    "ns/X")
        b (a/make-code-function "typescript" "ns/X")]
    (is (m/validate a/Artifact a) "no source-location still validates")
    (is (not= (a/artifact-identity a) (a/artifact-identity b)))))

(deftest projection-kinds-enum
  ;; Phase 8 Sprint 5 added :projection-kind/property-test for the
  ;; invariant-as-clojure.test.check-property idiom; siblings unchanged.
  (is (= #{:projection-kind/rule :projection-kind/operation
            :projection-kind/invariant :projection-kind/schema
            :projection-kind/test :projection-kind/property-test}
         a/projection-kinds)))
