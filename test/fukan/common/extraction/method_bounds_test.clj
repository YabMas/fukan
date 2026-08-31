(ns fukan.common.extraction.method-bounds-test
  "Where positional attribution of a `defmethod` body has to work harder than the ordinary
   spelling: the reader literals it has to survive, the nesting it has to see through, and the
   `.cljc` arms it must not choose between. They are gathered here because they fail identically
   — the satisfier node is emitted and only its calls go missing — and identically invisibly: no
   error, no empty result, just a smaller graph than the code."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.common.extraction.core :as tc]))

(def ^:private nested-fixture "test/fixtures/target/nested.clj")

(def ^:private cljc-fixture "test/fixtures/target/conditional.cljc")

(defn- method-calls
  "The `:calls` of every satisfier root extracted from `paths`, as `{method-id #{callee-id…}}`.
   Read off the FACTS rather than a built db: what is under test is attribution, which is decided
   before anything is assembled."
  [& paths]
  (into {}
        (for [[id v] (:roots (tc/extract-roots (vec paths)))
              :when  (str/includes? id "[")]                 ; the bracket is the satisfier key
          [id (into #{}
                    (comp (filter (comp #{:calls} :rk)) (mapcat :targets) (map :id))
                    (:clauses v))])))

(deftest a-method-body-survives-reader-literals-the-extractor-cannot-resolve
  (testing "the bounds come from a READ, and the file it reads is the adopter's: its aliases name
            namespaces this JVM has never loaded, so `::p/plain` and `#::p{…}` — ordinary Clojure
            — resolve to nothing here. A read that throws returns no bounds for the whole file,
            which drops every method body in it, so the permissiveness is not about the literal:
            it is about everything written after it.

            The fixture asks TWO things of that permissiveness, because answering only the first
            is what a reader gets wrong. Every alias has to resolve — and they have to resolve
            APART, or `{::p/id 1 ::s/id 2}` is one key written twice and the reader rejects it.
            The second failure is the worse one: the file was readable, and the extractor is what
            made it not."
    (is (= #{"nested/tag"} (get (method-calls nested-fixture) "nested/shade[:light]@nested"))
        "the plain top-level method's body call is attributed, in a file the reader had to be
         permissive — and permissive in both ways — to finish")))

(deftest a-nested-method-owns-its-own-body
  (testing "a defmethod inside a top-level `do` is legal, and the satisfier node is emitted for
            it either way. On top-level rows alone its enclosing row is the `do`'s, which matches
            no marker — so the node would stand there claiming a body whose calls had all been
            dropped. The marker's own row is the bound that reaches it, and the next marker's is
            what keeps one nested method's body out of the next."
    (let [calls (method-calls nested-fixture)]
      (is (= #{"nested/label"} (get calls "nested/shade[:dark]@nested"))
          "the first nested method's body call lands on it")
      (is (= #{"nested/tag"} (get calls "nested/shade[:grey]@nested"))
          "and the second's on the second — the first's body stops where this one starts"))))

(deftest a-method-in-either-arm-of-a-reader-conditional-keeps-its-body
  (testing "a `.cljc` is analysed for `:clj` AND for `:cljs`, so a defmethod in either arm emits a
            marker and a satisfier — while `:read-cond :allow` SELECTS one arm however many
            features it is handed, and reads the other away. That is a PARTIAL read rather than a
            failed one, which is the whole distinction: the fail-safe here is the whole-file drop,
            and a file that reads without error never reaches it.

            The two arms fail differently, and the second is the worse one. A plain conditional's
            unselected method simply stands with an empty body. A SPLICED one's marker still has
            to land in some extent, and with its own gone the smallest containing it is the
            enclosing container — so it claims that container's other members, which is the
            invented edge the extent discipline exists to refuse."
    (let [calls (method-calls cljc-fixture)]
      (is (= #{"conditional/tag"} (get calls "conditional/shade[:on-jvm]@conditional"))
          "the selected arm's method body is attributed, as it was before")
      (is (= #{"conditional/label"} (get calls "conditional/shade[:on-js]@conditional"))
          "and so is the other arm's — the branch a selecting read discarded")
      (is (= #{"conditional/tag"} (get calls "conditional/shade[:spliced-jvm]@conditional"))
          "the same through `#?@`, whose arms are vectors and whose container is a nesting one")
      (is (= #{"conditional/label"} (get calls "conditional/shade[:spliced-js]@conditional"))
          "and its unselected arm owns its own body EXACTLY — not the `(register! shade)` sitting
           beside it in the same `do`, which a selecting read hands it along with the container"))))
