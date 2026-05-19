(ns fukan.libs.boundary.parser-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.libs.boundary.parser :as parser]
            [instaparse.core :as insta]))

(defn- parse [text]
  (parser/parse-boundary text))

(deftest header-parses
  (testing "the version header is recognised and surfaced on the result"
    (let [result (parse "-- boundary: 1\n")]
      (is (not (insta/failure? result)) "parse produced an AST, not a failure")
      (is (= 1 (:boundary-version result))))))

(deftest header-required
  (testing "a file without the header fails to parse"
    (let [result (parse "use \"x.allium\" as x\n")]
      (is (insta/failure? result) "expected failure"))))

(deftest use-decl-basic
  (testing "a single-segment use declaration parses"
    (let [result (parse "-- boundary: 1\nuse \"x.allium\" as x\n")]
      (is (= [{:type :use :path "x.allium" :alias "x"}]
             (:declarations result))))))

(deftest use-decl-relative
  (testing "relative paths are preserved verbatim in :path"
    (let [result (parse "-- boundary: 1\nuse \"../model/spec.allium\" as model\n")]
      (is (= [{:type :use :path "../model/spec.allium" :alias "model"}]
             (:declarations result))))))

(deftest use-decl-multiple
  (testing "multiple use declarations parse in order"
    (let [result (parse (str "-- boundary: 1\n"
                             "use \"a.allium\" as a\n"
                             "use \"b.allium\" as b\n"
                             "use \"c.allium\" as c\n"))]
      (is (= [{:type :use :path "a.allium" :alias "a"}
              {:type :use :path "b.allium" :alias "b"}
              {:type :use :path "c.allium" :alias "c"}]
             (:declarations result))))))

(deftest fn-decl-signature-only
  (testing "a fn declaration with simple param types and return type"
    (let [result (parse (str "-- boundary: 1\n"
                             "fn render_app_shell() -> Html\n"))]
      (is (= [{:type :fn
               :form :declare-new
               :name "render_app_shell"
               :params []
               :return-type {:kind :simple :name "Html"}
               :prose nil
               :body nil}]
             (:declarations result))))))

(deftest fn-decl-with-params
  (testing "a fn declaration with multiple typed params"
    (let [result (parse (str "-- boundary: 1\n"
                             "fn render_graph(p: Projection, state: EditorState) -> CytoscapeGraph\n"))]
      (is (= [{:type :fn
               :form :declare-new
               :name "render_graph"
               :params [{:name "p" :type-ref {:kind :simple :name "Projection"}}
                        {:name "state" :type-ref {:kind :simple :name "EditorState"}}]
               :return-type {:kind :simple :name "CytoscapeGraph"}
               :prose nil
               :body nil}]
             (:declarations result))))))

(deftest fn-decl-no-return
  (testing "a fn with no return type (implicit Unit)"
    (let [result (parse (str "-- boundary: 1\n"
                             "fn stop_server()\n"))]
      (is (= [{:type :fn
               :form :declare-new
               :name "stop_server"
               :params []
               :return-type nil
               :prose nil
               :body nil}]
             (:declarations result))))))

(deftest fn-decl-optional-return
  (testing "a fn whose return type is optional (Type?)"
    (let [result (parse (str "-- boundary: 1\n"
                             "fn get_port() -> Integer?\n"))]
      (is (= [{:type :fn
               :form :declare-new
               :name "get_port"
               :params []
               :return-type {:kind :optional
                             :inner {:kind :simple :name "Integer"}}
               :prose nil
               :body nil}]
             (:declarations result))))))

(deftest fn-decl-with-prose
  (testing "a -- comment line directly under a fn becomes its prose"
    (let [result (parse (str "-- boundary: 1\n"
                             "fn render_app_shell() -> Html\n"
                             "  -- Render the initial HTML shell.\n"))]
      (is (= "Render the initial HTML shell."
             (-> result :declarations first :prose))))))

(deftest fn-decl-qualified-param-type
  (testing "param types can be alias-qualified (alias/Type)"
    (let [result  (parse (str "-- boundary: 1\n"
                              "use \"../projection/spec.allium\" as projection\n"
                              "fn render_graph(p: projection/Projection) -> CytoscapeGraph\n"))
          fn-decl (->> (:declarations result) (filter #(= :fn (:type %))) first)]
      (is (= {:kind :qualified :ns "projection" :name "Projection"}
             (-> fn-decl :params first :type-ref))))))

(deftest fn-decl-generic-param-type
  (testing "param types can be generic (e.g. Set<X>)"
    (let [result          (parse (str "-- boundary: 1\n"
                                      "fn load_model(src: FilePath, analyzers: Set<AnalyzerKey>) -> Model\n"))
          fn-decl         (->> (:declarations result) (filter #(= :fn (:type %))) first)
          analyzers-param (-> fn-decl :params second)]
      (is (= "analyzers" (:name analyzers-param)))
      (is (= :generic (-> analyzers-param :type-ref :kind)))
      (is (= "Set" (-> analyzers-param :type-ref :name))))))

(deftest fn-body-triggers-only
  (testing "fn with body containing just a triggers: clause"
    (let [result  (parse (str "-- boundary: 1\n"
                              "fn select_node(node_id: NodeId) {\n"
                              "    triggers: SelectNode\n"
                              "}\n"))
          fn-decl (->> (:declarations result) (filter #(= :fn (:type %))) first)]
      (is (= {:triggers {:kind :local :name "SelectNode"}
              :returns nil}
             (:body fn-decl))))))

(deftest fn-body-returns-only
  (testing "fn with body containing just a returns: clause (no triggers)"
    (let [result  (parse (str "-- boundary: 1\n"
                              "fn get_view_state() -> ViewState {\n"
                              "    returns: current_view_state\n"
                              "}\n"))
          fn-decl (->> (:declarations result) (filter #(= :fn (:type %))) first)]
      (is (= {:triggers nil
              :returns "current_view_state"}
             (:body fn-decl))))))

(deftest fn-body-both-clauses
  (testing "fn with body containing both triggers: and returns:"
    (let [result  (parse (str "-- boundary: 1\n"
                              "fn submit_order(order: Order) -> SubmissionReceipt {\n"
                              "    triggers: ProcessOrder\n"
                              "    returns: SubmissionReceipt(order.id, post.order.created_at)\n"
                              "}\n"))
          fn-decl (->> (:declarations result) (filter #(= :fn (:type %))) first)]
      (is (= {:kind :local :name "ProcessOrder"}
             (-> fn-decl :body :triggers)))
      (is (= "SubmissionReceipt(order.id, post.order.created_at)"
             (-> fn-decl :body :returns))))))

(deftest fn-body-empty
  (testing "fn with empty body { } parses as :body {:triggers nil :returns nil}"
    (let [result  (parse (str "-- boundary: 1\n"
                              "fn render_app_shell() -> Html { }\n"))
          fn-decl (->> (:declarations result) (filter #(= :fn (:type %))) first)]
      (is (= {:triggers nil :returns nil} (:body fn-decl))))))

(deftest fn-body-triggers-qualified
  (testing "triggers: can reference a foreign rule via alias"
    (let [result  (parse (str "-- boundary: 1\n"
                              "use \"../other.allium\" as other\n"
                              "fn local_fn(x: X) -> Y {\n"
                              "    triggers: other/SomeRule\n"
                              "}\n"))
          fn-decl (->> (:declarations result) (filter #(= :fn (:type %))) first)]
      (is (= {:kind :qualified :ns "other" :name "SomeRule"}
             (-> fn-decl :body :triggers))))))

(deftest fn-local-attach
  (testing "fn Contract.op { ... } attaches behaviour to a local Allium Operation"
    (let [result  (parse (str "-- boundary: 1\n"
                              "fn OrderSubmission.submit {\n"
                              "    triggers: ProcessOrder\n"
                              "    returns: Confirmation(post.order.id)\n"
                              "}\n"))
          fn-decl (->> (:declarations result) (filter #(= :fn (:type %))) first)]
      (is (= :local-attach (:form fn-decl)))
      (is (= "OrderSubmission" (:contract fn-decl)))
      (is (= "submit" (:op fn-decl)))
      (is (= {:kind :local :name "ProcessOrder"}
             (-> fn-decl :body :triggers)))
      (is (= "Confirmation(post.order.id)"
             (-> fn-decl :body :returns))))))

(deftest fn-foreign-attach
  (testing "fn alias/Contract.op { ... } attaches to a foreign Allium Operation"
    (let [result  (parse (str "-- boundary: 1\n"
                              "use \"../c.allium\" as c\n"
                              "fn c/PaymentRequested.charge {\n"
                              "    triggers: HandleCharge\n"
                              "    returns: Receipt(amount, post.txn.id)\n"
                              "}\n"))
          fn-decl (->> (:declarations result) (filter #(= :fn (:type %))) first)]
      (is (= :foreign-attach (:form fn-decl)))
      (is (= "c" (:alias fn-decl)))
      (is (= "PaymentRequested" (:contract fn-decl)))
      (is (= "charge" (:op fn-decl))))))

(deftest fn-attach-discriminated-by-absence-of-parens
  (testing "the parser distinguishes attach (no parens) from declare-new (parens)"
    (let [r1 (parse "-- boundary: 1\nfn submit(o: Order) -> R\n")]
      (is (= :declare-new (-> r1 :declarations first :form))))
    (let [r2 (parse "-- boundary: 1\nfn Sub.submit { triggers: R }\n")]
      (is (= :local-attach (-> r2 :declarations first :form))))))

(deftest exports-simple
  (testing "exports: with bare-name entries"
    (let [result (parse (str "-- boundary: 1\n"
                             "exports:\n"
                             "    ViewState\n"
                             "    NavigationState\n"
                             "    CytoscapeGraph\n"))]
      (is (= [{:type :exports
               :entries ["ViewState" "NavigationState" "CytoscapeGraph"]}]
             (:declarations result))))))

(deftest exports-mixed
  (testing "exports: with bare-name and Contract.op entries"
    (let [result (parse (str "-- boundary: 1\n"
                             "exports:\n"
                             "    OrderSubmission.submit\n"
                             "    Order\n"
                             "    OrderConfirmed\n"))]
      (is (= [{:type :exports
               :entries ["OrderSubmission.submit" "Order" "OrderConfirmed"]}]
             (:declarations result))))))

(deftest exports-after-fn-declarations
  (testing "exports: can follow fn declarations in the same file"
    (let [result  (parse (str "-- boundary: 1\n"
                              "fn render_graph() -> CytoscapeGraph\n"
                              "exports:\n"
                              "    ViewState\n"))
          exports (->> (:declarations result)
                       (filter #(= :exports (:type %))))]
      (is (= [{:type :exports :entries ["ViewState"]}] exports)))))

(deftest subsystem-basic
  (testing "subsystem block with contains: and exports:"
    (let [result (parse (str "-- boundary: 1\n"
                             "subsystem Auth {\n"
                             "    contains:\n"
                             "        ./oauth/spec.allium\n"
                             "        ./password/spec.allium\n"
                             "\n"
                             "    exports:\n"
                             "        oauth/OAuthLogin\n"
                             "        password/PasswordLogin\n"
                             "}\n"))]
      (is (= [{:type :subsystem
               :name "Auth"
               :contains ["./oauth/spec.allium" "./password/spec.allium"]
               :exports ["oauth/OAuthLogin" "password/PasswordLogin"]
               :rules []}]
             (:declarations result))))))

(deftest subsystem-with-rules
  (testing "subsystem with optional rules: clause"
    (let [result (parse (str "-- boundary: 1\n"
                             "subsystem Auth {\n"
                             "    contains:\n"
                             "        ./oauth/spec.allium\n"
                             "        ./password/spec.allium\n"
                             "\n"
                             "    exports:\n"
                             "        oauth/OAuthLogin\n"
                             "\n"
                             "    rules:\n"
                             "        no_dependency(from: oauth, to: password)\n"
                             "}\n"))
          sub    (-> result :declarations first)]
      (is (= "Auth" (:name sub)))
      (is (= 1 (count (:rules sub))))
      (is (= "no_dependency" (-> sub :rules first :name)))
      (is (= [{:key "from" :value "oauth"}
              {:key "to" :value "password"}]
             (-> sub :rules first :args))))))

(deftest subsystem-name-stem-alias-in-exports
  (testing "exports: inside subsystem use the contains-entry filename stem as alias"
    (let [result (parse (str "-- boundary: 1\n"
                             "subsystem Auth {\n"
                             "    contains:\n"
                             "        ./oauth/spec.allium\n"
                             "\n"
                             "    exports:\n"
                             "        oauth/OAuthLogin\n"
                             "}\n"))]
      ;; The parser doesn't resolve aliases — it just captures the string.
      ;; Plan 3b's analyzer resolves the stem alias.
      (is (= ["oauth/OAuthLogin"] (-> result :declarations first :exports))))))

(deftest subsystem-nested-boundary-reference
  (testing "contains: can reference nested subsystem .boundary files"
    (let [result (parse (str "-- boundary: 1\n"
                             "subsystem Outer {\n"
                             "    contains:\n"
                             "        ./inner.boundary\n"
                             "\n"
                             "    exports:\n"
                             "        inner/InnerSurface\n"
                             "}\n"))]
      (is (= [{:type :subsystem
               :name "Outer"
               :contains ["./inner.boundary"]
               :exports ["inner/InnerSurface"]
               :rules []}]
             (:declarations result))))))
