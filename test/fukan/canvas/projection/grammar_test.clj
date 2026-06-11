(ns fukan.canvas.projection.grammar-test
  "The print-dual: reflect a fixture grammar, render it back, and compare against
   what was authored — the round-trip is the test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :refer [defstructure]]
            [fukan.canvas.projection.grammar :as g]
            [lib.grammar :as grammar]))

;; ── the authored grammar the render must reproduce ───────────────────────────

(defstructure PLeaf "A leaf.")

(defstructure PNode
  "A node fixture.
   Second doc line, elided in the primer."
  {:one-ref PLeaf
   :opt-ref [:? PLeaf]
   :seq-ref [:* PLeaf]
   :set-ref [:set PLeaf]
   :title   :String
   :mode    [:enum "a" "b"]}
  (law "no bad title"
    :offenders '[?n]
    :where '[[?n :val/title "bad"]]))

(defstructure ^:value PVal
  "A value fixture."
  {:v :Int})

(def p-leaf (PLeaf "l"))

(defn- reflected [] (grammar/with-grammar (a/assemble-vars [#'p-leaf])))

(defn- struct-node [db tag-str]
  (ffirst (d/q '[:find ?s :in $ ?t
                 :where [?s :structure/of :lib.grammar/Structure] [?s :val/tag ?t]]
               db tag-str)))

(deftest structure-form-round-trips-the-authoring
  (let [db (reflected)
        f  (g/structure-form db (struct-node db ":fukan.canvas.projection.grammar-test/PNode"))]
    (is (= '(defstructure PNode
              "A node fixture.\n   Second doc line, elided in the primer."
              {:one-ref PLeaf
               :opt-ref [:? PLeaf]
               :seq-ref [:* PLeaf]
               :set-ref [:set PLeaf]
               :title   :String
               :mode    [:enum "a" "b"]}
              (law "no bad title"
                :offenders [?n]
                :where [[?n :val/title "bad"]]))
           f)
        "the rendered form IS the authored form (laws unquoted — the parsed form)")))

(deftest value-structures-carry-the-value-meta
  (let [db (reflected)
        f  (g/structure-form db (struct-node db ":fukan.canvas.projection.grammar-test/PVal"))]
    (is (= '(defstructure PVal "A value fixture." {:v :Int}) f))
    (is (true? (:value (meta (second f)))) "^:value rides the name symbol's metadata")))

(deftest primer-renders-the-reference-card
  (let [db (reflected)
        p  (g/vocabulary-primer db "fukan.canvas.projection.grammar-test")]
    (testing "header + every structure"
      (is (str/includes? p "fukan.canvas.projection.grammar-test — 3 structures"))
      (is (str/includes? p "(defstructure PLeaf"))
      (is (str/includes? p "(defstructure ^:value PVal")))
    (testing "slots aligned in one map, refined scalars as their forms"
      (is (str/includes? p "{:one-ref PLeaf"))
      (is (str/includes? p ":mode    [:enum \"a\" \"b\"]}")))
    (testing "doc truncated to its first line, laws elided to their desc"
      (is (str/includes? p "\"A node fixture. …\""))
      (is (str/includes? p "(law \"no bad title\" …)"))
      (is (not (str/includes? p "Second doc line")))
      (is (not (str/includes? p ":offenders"))))))

(deftest primer-covers-the-strange-loop
  (testing "the full primer includes the meta-grammar describing itself"
    (let [db (reflected)
          p  (g/grammar-primer db)]
      (is (str/includes? p "━━ lib.grammar — "))
      (is (str/includes? p "(defstructure Structure")))))
