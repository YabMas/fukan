(ns fukan.canvas.projection.reference-test
  "The SELECTION REFERENCE, held to its word: what it prints runs.

   The model is in a vocabulary of this file's own — two sorts and one stored relation, none of
   them fukan's — because a reference that could only exemplify the shipped grammar would be
   documentation of fukan and not of the model it was asked about."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.lens :as lens]
            [fukan.canvas.core.structure :refer [defstructure]]
            [fukan.canvas.projection.reference :as reference]
            [fukan.cozo.build :as build]
            [fukan.common.vocab.code.kind :as code]
            ;; side-effect: registers the malli dialect + Clojure extractor + check engine
            [fukan.infra.model]))

(defstructure RefCrate "A crate holding jars." {:rholds [:* RefJar]})
(defstructure RefJar "A jar." {})

(RefJar jam)
(RefJar honey)
(RefJar salt)
(RefCrate pantry {:rholds [jam honey]})
(RefCrate cellar {:rholds [salt]})

;; a sort sharing its SHORT name with `fukan.common.vocab.code.kind/Kind`
(defstructure Kind "A local kind, named like the shipped one." {})

(Kind ^{:name "local"} local-kind)
(code/Kind ^{:name "fromlib"} lib-kind)

(defn- db [vars] (build/with-grammar (build/vars->cozo vars) nil))

(def ^:private printed
  "A selection as the reference prints one: alone on its line, with what it selected."
  #"(?m)^    (\[.*\])  ; (\d+) nodes?$")

(defn- section
  "The text under the heading `title`, up to the next heading."
  [text title]
  (second (re-find (re-pattern (str "(?s)### " title "\n(.*?)(?=\n### |\\z)")) text)))

(def ^:private kinds
  ["One sort" "A union of sorts" "A grouping with its members" "Nodes by id prefix, with their members"])

(deftest the-reference-prints-only-selections-that-run
  (let [model (db [#'jam #'honey #'salt #'pantry #'cellar])
        text  (reference/selection-reference model)
        found (re-seq printed text)]
    (testing "a worked selection of each of the four kinds"
      (doseq [kind kinds]
        (is (re-find printed (str (section text kind))) (str kind " has a selection"))))
    (testing "every selection in the text is read back and evaluated by the selection engine"
      (is (<= 10 (count found)))
      (doseq [[_ selection n] found]
        (is (<= (parse-long n) (count (lens/focus-nodes model (edn/read-string selection))))
            (str selection " runs, and selects at least what was printed"))))
    (testing "the examples are this model's own"
      (is (str/includes? (section text "One sort") "RefJar"))
      (is (str/includes? (section text "A grouping with its members") "rholds"))
      (is (str/includes? (section text "A grouping with its members") "/pantry") "the fullest grouping")
      (is (re-find #"; 3 nodes" (section text "A grouping with its members")) "pantry and its two jars"))
    (testing "the forms, the attributes and the edge rule are stated"
      (doseq [said ["or-join" "not-join" "(is ?n" "(path ?from" ":entity/id" ":entity/name" ":structure/of"
                    "only between two selected nodes"]]
        (is (str/includes? text said))))))

(deftest a-model-that-cannot-supply-an-example-is-told-so
  (let [text (reference/selection-reference (db [#'jam #'honey]))]
    (is (re-find printed (section text "One sort")) "one sort is still exemplified")
    (doseq [kind (rest kinds)]
      (is (str/includes? (section text kind) "no such example exists for this model"))
      (is (not (re-find printed (section text kind)))))))

(deftest two-sorts-sharing-a-short-name-each-exemplify
  (let [model (db [#'local-kind #'lib-kind])
        text  (reference/selection-reference model)]
    (is (re-find #"; 1 node$" (str/trim (section text "One sort"))) "one sort selects one node, not both")
    (is (re-find #"(?m); 2 nodes$" (section text "A union of sorts")) "the two sorts are a union")
    (doseq [[_ selection n] (re-seq printed text)]
      (is (<= (parse-long n) (count (lens/focus-nodes model (edn/read-string selection))))
          (str selection " runs")))))
