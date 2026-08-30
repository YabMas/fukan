(ns fukan.canvas.projection.prose-test
  "The prose dual renders the same DATA FORMS the form duals produce, so it is testable as a
   pure function — which is also why the two views cannot drift into two different designs."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.canvas.projection.prose :as prose]))

(deftest a-slot-type-reads-as-what-may-fill-it-and-how-many
  (is (= "exactly one Band"                    (prose/type-phrase 'Band)))
  (is (= "exactly one :string"                 (prose/type-phrase :string)))
  (is (= "at most one Schema (optional)"       (prose/type-phrase '[:? Schema])))
  (is (= "any number of Band"                  (prose/type-phrase '[:* Band])))
  (is (= "one or more NsPrefix"                (prose/type-phrase '[:+ NsPrefix])))
  (is (= "any number of Module, unordered"     (prose/type-phrase '[:set Module])))
  (testing "a union names every member"
    (is (= "any number of Module, Operation or Kind" (prose/type-phrase '[:* Module Operation Kind]))))
  (testing "authoring options are not part of the rule"
    (is (= "at most one :string (optional)" (prose/type-phrase '[:? {:payload :q} :string]))))
  (testing "a refined scalar stays a malli form — paraphrasing the type dialect here would put
            a second, worse type language in the projection layer"
    (is (= "a value matching [:enum \"a\" \"b\"]" (prose/type-phrase [:enum "a" "b"])))))

(deftest a-structure-reads-as-the-concept-then-the-rules
  (let [out (prose/structure-prose
             '(defstructure Band
                "A stratum of the codebase."
                {:prefix [:+ NsPrefix] :may-depend [:* Band]}
                (law "the :may-depend graph is acyclic" {:offenders [?s] :where []})))]
    (is (str/includes? out "### Band"))
    (is (str/includes? out "A stratum of the codebase."))
    (is (str/includes? out "Every Band carries:"))
    (is (str/includes? out "  - prefix — one or more NsPrefix"))
    (is (str/includes? out "These must hold of every Band:"))
    (is (str/includes? out "  - the :may-depend graph is acyclic"))
    (testing "the law's description, never its body: the datalog is how the rule is decided,
              which is the checker's business and not the reader's"
      (is (not (str/includes? out ":offenders"))))))

(deftest an-instance-reads-as-what-it-is-and-what-it-declares
  (let [out (prose/instance-prose
             '(Band Review "The judgment loops." {:prefix [(NsPrefix {:value "nido.review."})]
                                                  :may-depend [Platform Session]}))]
    (is (str/includes? out "**Review** (Band)"))
    (is (str/includes? out "The judgment loops."))
    (is (str/includes? out "may-depend: Platform, Session"))
    (testing "a one-slot ^:value node IS its value — naming the wrapper adds a word and no
              information"
      (is (str/includes? out "prefix: \"nido.review.\""))
      (is (not (str/includes? out "NsPrefix"))))))

(deftest a-declaration-with-no-doc-or-slots-still-renders
  (is (str/includes? (prose/structure-prose '(defstructure PLeaf)) "### PLeaf"))
  (is (str/includes? (prose/instance-prose '(PLeaf p-leaf)) "**p-leaf** (PLeaf)")))

(deftest a-type-form-renders-verbatim-not-as-its-elements
  (testing "a plural slot's value and a malli schema are both VECTORS, so without the keyword-head
            discriminator a signature renders as its own elements joined by commas — `signature:
            :=>, :catn, :db, CozoDb, :any`, which is a type form with the structure taken out.
            This became reachable when a signature stopped being decomposed into `:in`/`:out`
            slots and became one `Schema` the dialect owns."
    (let [text (prose/instance-prose
                '(Operation q "Run a script."
                   {:signature [:=> [:catn [:db :CozoDb] [:script :string] [:params [:? :map]]]
                                [:vector :any]]
                    :delegates [compile-body entity]}))]
      (is (str/includes? text "signature: [:=> [:catn [:db :CozoDb] [:script :string] [:params [:? :map]]] [:vector :any]]")
          "the type form is rendered as authored")
      (testing "and a genuine target LIST still reads as a list"
        (is (str/includes? text "delegates: compile-body, entity"))))))
