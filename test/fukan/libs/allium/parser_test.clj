(ns fukan.libs.allium.parser-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.libs.allium.parser :as parser]
            [instaparse.core :as insta]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- parse
  "Parse a snippet with the allium header prepended."
  [text]
  (parser/parse-allium (str "-- allium: 1\n" text)))

(defn- first-decl [text]
  (first (:declarations (parse text))))

;; ---------------------------------------------------------------------------
;; Unit tests — one per construct type
;; ---------------------------------------------------------------------------

(deftest header-test
  (testing "version number is extracted"
    (let [result (parser/parse-allium "-- allium: 1\n")]
      (is (= "1" (:allium-version result)))
      (is (= [] (:declarations result))))))

(deftest use-decl-test
  (testing "use with path and alias"
    (let [d (first-decl "use \"./model.allium\" as model\n")]
      (is (= :use (:type d)))
      (is (= "./model.allium" (:path d)))
      (is (= "model" (:alias d))))))

(deftest given-block-test
  (testing "single binding"
    (let [d (first-decl "given {\n    model: Model\n}\n")]
      (is (= :given (:type d)))
      (is (= 1 (count (:bindings d))))
      (is (= "model" (-> d :bindings first :name)))
      (is (= {:kind :simple :name "Model"}
             (-> d :bindings first :type-ref)))))

  (testing "qualified type in binding"
    (let [d (first-decl "given {\n    model: ns/Model\n}\n")]
      (is (= {:kind :qualified :ns "ns" :name "Model"}
             (-> d :bindings first :type-ref))))))

(deftest enum-decl-test
  (testing "pipe-separated values"
    (let [d (first-decl "enum EdgeType { code_flow | schema_flow | data_flow }\n")]
      (is (= :enum (:type d)))
      (is (= "EdgeType" (:name d)))
      (is (= ["code_flow" "schema_flow" "data_flow"] (:values d))))))

(deftest external-entity-test
  (testing "empty external entity"
    (let [d (first-decl "external entity Foo {\n}\n")]
      (is (= :external-entity (:type d)))
      (is (= "Foo" (:name d)))))

  (testing "external entity with comment body"
    (let [d (first-decl "external entity Bar {\n    -- some description\n}\n")]
      (is (= :external-entity (:type d)))
      (is (= "Bar" (:name d))))))

(deftest surface-decl-test
  (testing "surface with fields"
    (let [d (first-decl "surface PublicAPI {\n    facing: external\n    guarantees: idempotent\n}\n")]
      (is (= :surface (:type d)))
      (is (= "PublicAPI" (:name d)))
      (is (= 2 (count (:fields d))))))

  (testing "surface with description"
    (let [d (first-decl "surface PublicAPI \"The public boundary.\" {\n    facing: external\n}\n")]
      (is (= :surface (:type d)))
      (is (= "The public boundary." (:description d)))))

  (testing "surface with provides block"
    (let [d (first-decl (str "surface API {\n"
                             "    provides:\n"
                             "        navigate(model: Model, opts: Opts) -> Result -- Navigate.\n"
                             "        inspect(model: Model, id: String) -> Details? -- Inspect.\n"
                             "}\n"))]
      (is (= :surface (:type d)))
      (let [provides (->> (:fields d)
                          (filter #(= :provides-block (:field-kind %)))
                          first
                          :entries)]
        (is (= 2 (count provides)))
        (is (= "navigate" (:name (first provides))))
        (is (= "inspect" (:name (second provides))))
        (is (= "Navigate." (:description (first provides))))
        (is (some? (:return (second provides)))
            "optional return type (Details?) should parse"))))

  (testing "provides entry names accept lowercase"
    (let [d (first-decl (str "surface API {\n"
                             "    provides:\n"
                             "        find_root(m: Model) -> Node\n"
                             "}\n"))]
      (is (= :surface (:type d)))
      (is (= "find_root"
             (->> (:fields d)
                  (filter #(= :provides-block (:field-kind %)))
                  first :entries first :name))))))

(deftest entity-decl-test
  (testing "entity with fields"
    (let [d (first-decl "entity Node {\n    kind: String\n    label: String\n}\n")]
      (is (= :entity (:type d)))
      (is (= "Node" (:name d)))
      (is (= 2 (count (:fields d))))
      (is (= "kind" (-> d :fields first :name)))))

  (testing "entity with description string"
    (let [d (first-decl "entity Node \"An entity in the system model.\" {\n    kind: String\n}\n")]
      (is (= :entity (:type d)))
      (is (= "Node" (:name d)))
      (is (= "An entity in the system model." (:description d)))
      (is (= 1 (count (:fields d)))))))

(deftest value-decl-test
  (testing "value with fields"
    (let [d (first-decl "value Edge {\n    from: String\n    to: String\n}\n")]
      (is (= :value (:type d)))
      (is (= "Edge" (:name d)))
      (is (= 2 (count (:fields d))))))

  (testing "single-line comma-separated fields"
    (let [d (first-decl "value DepInfo { count: Integer, label: String }\n")]
      (is (= :value (:type d)))
      (is (= 2 (count (:fields d))))
      (is (= "count" (-> d :fields first :name)))
      (is (= "label" (-> d :fields second :name))))))

(deftest variant-decl-test
  (testing "variant with base type"
    (let [d (first-decl "variant Module : Node {\n    doc: String?\n}\n")]
      (is (= :variant (:type d)))
      (is (= "Module" (:name d)))
      (is (= {:kind :simple :name "Node"} (:base d)))
      (is (= 1 (count (:fields d))))))

  (testing "variant with description string"
    (let [d (first-decl "variant Module \"A module.\" : Node {\n    doc: String?\n}\n")]
      (is (= :variant (:type d)))
      (is (= "A module." (:description d)))
      (is (= {:kind :simple :name "Node"} (:base d))))))

(deftest rule-decl-test
  (testing "simple rule with when and ensures"
    (let [d (first-decl "rule BuildModel {\n    when: BuildModel(data: String)\n    ensures: Model.created()\n}\n")]
      (is (= :rule (:type d)))
      (is (= "BuildModel" (:name d)))
      (is (= 2 (count (:clauses d))))
      (is (= :when (-> d :clauses first :clause-type)))
      (is (= :ensures (-> d :clauses second :clause-type)))))

  (testing "rule with let clause"
    (let [d (first-decl "rule Foo {\n    when: Foo(x: String)\n    let y = x.bar\n    ensures: Baz.created()\n}\n")]
      (is (= 3 (count (:clauses d))))
      (is (= :let (-> d :clauses second :clause-type)))
      (is (= "y = x.bar" (-> d :clauses second :body)))))

  (testing "rule with requires clause"
    (let [d (first-decl "rule Foo {\n    when: Foo(x: String)\n    requires: x != null\n    ensures: Ok.created()\n}\n")]
      (is (= :requires (-> d :clauses (nth 1) :clause-type))))))

(deftest surface-facing-test
  (testing "facing role binding"
    (let [d (first-decl "surface Login {\n    facing actor: User\n}\n")
          facing (->> (:fields d)
                      (filter #(= :facing (:field-kind %)))
                      first)]
      (is (= :facing (:field-kind facing)))
      (is (= "actor" (:role facing)))
      (is (= {:kind :simple :name "User"} (:type-ref facing)))))

  (testing "facing with qualified type"
    (let [d (first-decl "surface S {\n    facing actor: auth/User\n}\n")
          facing (->> (:fields d)
                      (filter #(= :facing (:field-kind %)))
                      first)]
      (is (= {:kind :qualified :ns "auth" :name "User"} (:type-ref facing))))))

(deftest surface-context-test
  (testing "context with entity binding"
    (let [d (first-decl "surface Editing {\n    context entity: Document\n}\n")
          ctx (->> (:fields d)
                   (filter #(= :context (:field-kind %)))
                   first)]
      (is (= :context (:field-kind ctx)))
      (is (= "entity" (:role ctx)))
      (is (= {:kind :simple :name "Document"} (:type-ref ctx))))))

(deftest surface-timeout-test
  (testing "timeout clause references a rule by name"
    (let [d (first-decl "surface Op {\n    timeout: ExpireSession\n}\n")
          t (->> (:fields d)
                 (filter #(= :timeout (:field-kind %)))
                 first)]
      (is (= :timeout (:field-kind t)))
      (is (= "ExpireSession" (:rule-name t))))))

(deftest surface-related-test
  (testing "related entries reference peer surfaces"
    (let [d (first-decl "surface Read {\n    related:\n        Write\n        AdminOps\n}\n")
          rel (->> (:fields d)
                   (filter #(= :related (:field-kind %)))
                   first)]
      (is (= :related (:field-kind rel)))
      (is (vector? (:entries rel)))
      (is (= 2 (count (:entries rel))))
      (is (= "Write" (-> rel :entries first :name)))))

  (testing "related entry with args"
    (let [d (first-decl "surface S {\n    related:\n        Write(async)\n}\n")
          rel (->> (:fields d)
                   (filter #(= :related (:field-kind %)))
                   first)
          entry (first (:entries rel))]
      (is (= "Write" (:name entry)))
      (is (= "async" (:args entry))))))

(deftest surface-let-test
  (testing "surface-internal let binding"
    (let [d (first-decl "surface S {\n    let admins = users.filter(role = admin)\n}\n")
          binding (->> (:fields d)
                       (filter #(= :let (:field-kind %)))
                       first)]
      (is (= :let (:field-kind binding)))
      (is (= "admins" (:name binding)))
      (is (= "users.filter(role = admin)" (:expr binding))))))

(deftest actor-decl-test
  (testing "actor with identified_by only"
    (let [d (first-decl "actor Author {\n    identified_by: User\n}\n")]
      (is (= :actor (:type d)))
      (is (= "Author" (:name d)))
      (is (= {:kind :simple :name "User"} (:identified-by d)))
      (is (nil? (:within d)))))

  (testing "actor with identified_by and within"
    (let [d (first-decl "actor Editor {\n    identified_by: User\n    within: Workspace\n}\n")]
      (is (= :actor (:type d)))
      (is (= {:kind :simple :name "User"} (:identified-by d)))
      (is (= {:kind :simple :name "Workspace"} (:within d)))))

  (testing "actor with qualified type in identified_by"
    (let [d (first-decl "actor Admin {\n    identified_by: auth/User\n}\n")]
      (is (= {:kind :qualified :ns "auth" :name "User"} (:identified-by d))))))

;; ---------------------------------------------------------------------------
;; Unit tests — field variants
;; ---------------------------------------------------------------------------

(deftest typed-field-test
  (testing "simple type"
    (let [f (-> (first-decl "value V { name: String }\n") :fields first)]
      (is (= :typed (:field-kind f)))
      (is (= {:kind :simple :name "String"} (:type-ref f))))))

(deftest optional-type-test
  (testing "optional simple type"
    (let [f (-> (first-decl "value V { doc: String? }\n") :fields first)]
      (is (= :typed (:field-kind f)))
      (is (= {:kind :optional :inner {:kind :simple :name "String"}}
             (:type-ref f))))))

(deftest generic-type-test
  (testing "single type parameter"
    (let [f (-> (first-decl "value V { items: List<String> }\n") :fields first)]
      (is (= :typed (:field-kind f)))
      (is (= {:kind :generic :name "List"
              :params [{:kind :simple :name "String"}]}
             (:type-ref f)))))

  (testing "multiple type parameters"
    (let [f (-> (first-decl "value V { m: Map<String, Node> }\n") :fields first)]
      (is (= {:kind :generic :name "Map"
              :params [{:kind :simple :name "String"}
                       {:kind :simple :name "Node"}]}
             (:type-ref f))))))

(deftest qualified-type-test
  (testing "qualified name"
    (let [f (-> (first-decl "value V { sig: model/FnSig }\n") :fields first)]
      (is (= :typed (:field-kind f)))
      (is (= {:kind :qualified :ns "model" :name "FnSig"}
             (:type-ref f))))))

(deftest union-type-test
  (testing "union of simple types"
    (let [f (-> (first-decl "value V { kind: A | B | C }\n") :fields first)]
      (is (= :typed (:field-kind f)))
      (is (= {:kind :union
              :members [{:kind :simple :name "A"}
                        {:kind :simple :name "B"}
                        {:kind :simple :name "C"}]}
             (:type-ref f))))))

(deftest inline-obj-type-test
  (testing "inline object type"
    (let [f (-> (first-decl "value V { parent: { id: String, label: String } }\n")
                :fields first)]
      (is (= :typed (:field-kind f)))
      (is (= :inline-obj (-> f :type-ref :kind)))
      (is (= 2 (count (-> f :type-ref :fields))))))

  (testing "optional inline object"
    (let [f (-> (first-decl "value V { parent: { id: String }? }\n")
                :fields first)]
      (is (= :optional (-> f :type-ref :kind)))
      (is (= :inline-obj (-> f :type-ref :inner :kind))))))

(deftest relationship-field-test
  (testing "relationship with constraint"
    (let [f (-> (first-decl "entity E {\n    children: Node with parent = this\n}\n")
                :fields first)]
      (is (= :relationship (:field-kind f)))
      (is (= {:kind :simple :name "Node"} (:type-ref f)))
      (is (= "parent = this" (:constraint f))))))

(deftest projection-field-test
  (testing "projection with predicate"
    (let [f (-> (first-decl "entity E {\n    modules: nodes where kind = Module\n}\n")
                :fields first)]
      (is (= :projection (:field-kind f)))
      (is (= "nodes" (:source f)))
      (is (= "kind = Module" (:predicate f))))))

(deftest derived-field-test
  (testing "derived value expression"
    (let [f (-> (first-decl "entity E {\n    is_leaf: children.count = 0\n}\n")
                :fields first)]
      (is (= :derived (:field-kind f)))
      (is (= "children.count = 0" (:expr f))))))

(deftest inline-comment-test
  (testing "inline comment on typed field"
    (let [f (-> (first-decl "value V {\n    from: String   -- NodeId\n}\n")
                :fields first)]
      (is (= :typed (:field-kind f)))
      (is (= "NodeId" (:comment f))))))

(deftest field-when-clause-test
  (testing "field with lifecycle when:"
    (let [d (first-decl
              "entity Order {\n    status: pending | shipped\n    shipped_at: DateTime when: status = shipped\n}\n")
          f2 (-> d :fields second)]
      (is (= :typed (:field-kind f2)))
      (is (= "shipped_at" (:name f2)))
      (is (= {:kind :simple :name "DateTime"} (:type-ref f2)))
      (is (= "status = shipped" (:when f2)))))

  (testing "optional field with when:"
    (let [d (first-decl
              "entity Order {\n    status: pending | shipped\n    shipped_at: DateTime? when: status = shipped\n}\n")
          f2 (-> d :fields second)]
      (is (= :typed (:field-kind f2)))
      (is (= "shipped_at" (:name f2)))
      (is (= {:kind :optional :inner {:kind :simple :name "DateTime"}} (:type-ref f2)))
      (is (= "status = shipped" (:when f2))))))

;; ---------------------------------------------------------------------------
;; Unit tests — nested variants
;; ---------------------------------------------------------------------------

(deftest nested-variant-test
  (testing "value with nested variant blocks"
    (let [d (first-decl "value V {\n    type: T\n\n    variant foo {\n        items: List<String>\n    }\n\n    variant bar {\n        items: List<Integer>\n        extra: String?\n    }\n}\n")]
      (is (= :value (:type d)))
      (is (= "V" (:name d)))
      ;; 3 fields: one typed + two nested variants
      (is (= 3 (count (:fields d))))
      (is (= :typed (-> d :fields first :field-kind)))
      (is (= :variant (-> d :fields second :field-kind)))
      (is (= "foo" (-> d :fields second :variant-name)))
      (is (= 1 (count (-> d :fields second :fields))))
      (is (= :variant (-> d :fields (nth 2) :field-kind)))
      (is (= "bar" (-> d :fields (nth 2) :variant-name)))
      (is (= 2 (count (-> d :fields (nth 2) :fields)))))))

;; ---------------------------------------------------------------------------
;; Unit tests — trigger expression
;; ---------------------------------------------------------------------------

(deftest trigger-params-test
  (testing "trigger with multiple params"
    (let [d (first-decl "rule R {\n    when: R(a: String, b: Set<String>)\n    ensures: Ok.done()\n}\n")
          trigger (-> d :clauses first :trigger)]
      (is (= :call (:kind trigger)))
      (is (= "R" (:name trigger)))
      (is (= 2 (count (:params trigger))))
      (is (= "a" (-> trigger :params first :name)))
      (is (= {:kind :simple :name "String"}
             (-> trigger :params first :type-ref)))
      (is (= {:kind :generic :name "Set"
              :params [{:kind :simple :name "String"}]}
             (-> trigger :params second :type-ref)))))

  (testing "trigger with optional param"
    (let [d (first-decl "rule R {\n    when: R(x: String?)\n    ensures: Ok()\n}\n")
          p (-> d :clauses first :trigger :params first)]
      (is (= {:kind :optional :inner {:kind :simple :name "String"}}
             (:type-ref p))))))

;; ---------------------------------------------------------------------------
;; Structural assertions
;; ---------------------------------------------------------------------------

(deftest structural-assertions-test
  (testing "every declaration has :type"
    (doseq [f ["src/fukan/model/spec.allium"
               "src/fukan/web/views/spec.allium"]]
      (let [result (parser/parse-file f)]
        (is (not (insta/failure? result)) (str "parse failed for " f))
        (doseq [d (:declarations result)]
          (is (contains? d :type)
              (str "missing :type in " f ": " (pr-str d)))))))

  (testing "every named declaration has :name"
    (doseq [f ["src/fukan/model/spec.allium"
               "src/fukan/web/views/spec.allium"]]
      (let [result (parser/parse-file f)]
        (doseq [d (:declarations result)
                :when (not (#{:given :use} (:type d)))]
          (is (string? (:name d))
              (str "missing :name in " f " for " (:type d)))))))

  (testing "every field has :name and :field-kind"
    ;; Block-shaped fields are containers — they carry :entries / :body /
    ;; :condition rather than a top-level :name. Only the named-field
    ;; variants are expected to have :name.
    (let [block-kinds #{:provides-block :exposes :contracts
                        :when-guard :related :facing :context :timeout :let}]
      (doseq [f ["src/fukan/model/spec.allium"
                 "src/fukan/web/views/spec.allium"]]
        (let [result (parser/parse-file f)]
          (doseq [d (:declarations result)
                  :when (:fields d)
                  field (:fields d)]
            (cond
              (= :variant (:field-kind field))
              ;; Nested variants use :variant-name instead of :name
              (is (string? (:variant-name field))
                  (str "missing :variant-name in nested variant: " (pr-str field)))

              (contains? block-kinds (:field-kind field))
              ;; Block fields don't carry :name
              nil

              :else
              (is (string? (:name field))
                  (str "missing :name in field: " (pr-str field))))
            (is (keyword? (:field-kind field))
                (str "missing :field-kind in field: " (pr-str field))))))))

  (testing "every type-ref has :kind"
    (doseq [f ["src/fukan/model/spec.allium"
               "src/fukan/web/views/spec.allium"]]
      (let [result (parser/parse-file f)
            type-refs (for [d (:declarations result)
                           :when (:fields d)
                           field (:fields d)
                           :when (:type-ref field)]
                       (:type-ref field))]
        (doseq [tr type-refs]
          (is (keyword? (:kind tr))
              (str "missing :kind in type-ref: " (pr-str tr))))))))

;; ---------------------------------------------------------------------------
;; Integration tests — full file acceptance
;; ---------------------------------------------------------------------------

(deftest model-allium-integration-test
  (testing "model.allium parses completely"
    (let [result (parser/parse-file "src/fukan/model/spec.allium")]
      (is (not (insta/failure? result)))
      (is (= "2" (:allium-version result)))
      (let [types (frequencies (map :type (:declarations result)))]
        (is (= 6 (:value types)))            ;; MapEntry, FunctionSignature, BoundaryFn, Guarantee, Invariant, Boundary
        (is (= 4 (:entity types)))           ;; Edge, Node, Model, TypeExpr
        (is (= 17 (:variant types)))
        (is (= 5 (:invariant types)))))))    ;; NoSelfEdge, LeafStrictness + 3 top-level


(deftest views-allium-integration-test
  (testing "views.allium parses completely"
    (let [result (parser/parse-file "src/fukan/web/views/spec.allium")]
      (is (not (insta/failure? result)))
      (let [types (frequencies (map :type (:declarations result)))]
        (is (= 1 (:use types)))
        (is (= 1 (:given types)))
        ;; ViewState, NavigationState, CytoscapeGraph, CytoscapeNode, CytoscapeEdge
        (is (= 5 (:value types)))
        ;; SelectNode, NavigateToNode, NavigateToAncestor
        (is (= 3 (:rule types)))
        ;; 1 surface (GraphViewer)
        (is (= 1 (:surface types)))))))

(deftest transitions-graph-test
  (testing "transitions graph with multiple edges"
    (let [d (first-decl
              (str "entity Order {\n"
                   "    status: pending | shipped\n"
                   "    transitions status {\n"
                   "        pending -> shipped\n"
                   "        pending -> cancelled\n"
                   "    }\n"
                   "}\n"))
          tx (->> (:fields d)
                  (filter #(= :transitions (:field-kind %)))
                  first)]
      (is (= :transitions (:field-kind tx)))
      (is (= "status" (:field tx)))
      (is (= 2 (count (:edges tx))))
      (is (= {:from "pending" :to "shipped"} (first (:edges tx))))
      (is (= {:from "pending" :to "cancelled"} (second (:edges tx)))))))

;; ---------------------------------------------------------------------------
;; Unit tests — structured trigger bindings (state/temporal operators)
;; ---------------------------------------------------------------------------

(deftest trigger-transitions-to-test
  (testing "transitions_to trigger"
    (let [d (first-decl
              "rule R {\n    when: order: Order.status transitions_to shipped\n    ensures: Ok.created()\n}\n")
          tr (-> d :clauses first :trigger)]
      (is (= :binding (:kind tr)))
      (is (= "order" (:var tr)))
      (is (= "Order.status" (:source tr)))
      (is (= "transitions_to" (:operator tr)))
      (is (= "shipped" (:operand tr))))))

(deftest trigger-becomes-test
  (testing "becomes trigger"
    (let [d (first-decl
              "rule R {\n    when: order: Order.is_paid becomes true\n    ensures: Ok.created()\n}\n")
          tr (-> d :clauses first :trigger)]
      (is (= "becomes" (:operator tr)))
      (is (= "true" (:operand tr))))))

(deftest trigger-created-test
  (testing "created trigger"
    (let [d (first-decl
              "rule R {\n    when: order: Order.created\n    ensures: Ok.created()\n}\n")
          tr (-> d :clauses first :trigger)]
      (is (= :binding (:kind tr)))
      (is (= "order" (:var tr)))
      (is (= "Order" (:source tr)))
      (is (= "created" (:operator tr)))
      (is (nil? (:operand tr))))))

(deftest trigger-temporal-test
  (testing "temporal trigger with `<= now`"
    (let [d (first-decl
              "rule R {\n    when: s: Session.expires_at <= now\n    ensures: Ok.created()\n}\n")
          tr (-> d :clauses first :trigger)]
      (is (= "<=" (:operator tr)))
      (is (= "now" (:operand tr))))))

;; ---------------------------------------------------------------------------
;; Unit tests — for iteration in ensures clauses
;; ---------------------------------------------------------------------------

(deftest ensures-for-iteration-test
  (testing "ensures: for x in coll: body"
    (let [d (first-decl
              (str "rule R {\n"
                   "    when: R(items: List<Item>)\n"
                   "    ensures: for item in items: item.touched_at = now()\n"
                   "}\n"))
          ens (-> d :clauses (nth 1))]
      (is (= :ensures (:clause-type ens)))
      (is (= :for-iteration (:kind ens)))
      (is (= "item" (:var ens)))
      (is (= "items" (:collection ens)))
      (is (= "item.touched_at = now()" (:body ens))))))

;; ---------------------------------------------------------------------------
;; Unit tests — for quantification in top-level invariants
;; ---------------------------------------------------------------------------

(deftest invariant-for-quantification-test
  (testing "top-level invariant with for-quantification"
    (let [d (first-decl
              (str "invariant LeafNoChildren {\n"
                   "    for n in Node where n.kind = leaf: n.children = []\n"
                   "}\n"))]
      (is (= :invariant (:type d)))
      (is (= "LeafNoChildren" (:name d)))
      (is (= :for-quantification (-> d :body :kind)))
      (is (= "n" (-> d :body :var)))
      (is (= "Node" (-> d :body :source)))
      (is (= "n.kind = leaf" (-> d :body :guard)))
      (is (= "n.children = []" (-> d :body :assertion))))))

(deftest invariant-expression-body-test
  (testing "invariant body without for-quantification stays as text"
    (let [d (first-decl
              "invariant TotalIsPositive {\n    Order.total > 0\n}\n")]
      (is (= :expression (-> d :body :kind)))
      (is (= "Order.total > 0" (-> d :body :text))))))

(deftest invariant-for-no-guard-test
  (testing "for-quantification without 'where' guard"
    (let [d (first-decl
              "invariant ChildrenOk {\n    for n in Node: n.children = []\n}\n")]
      (is (= :for-quantification (-> d :body :kind)))
      (is (= "n" (-> d :body :var)))
      (is (= "Node" (-> d :body :source)))
      (is (nil? (-> d :body :guard)))
      (is (= "n.children = []" (-> d :body :assertion))))))

;; ---------------------------------------------------------------------------
;; Corpus-regression — every .allium file in src/ must parse clean
;; ---------------------------------------------------------------------------

(def ^:private corpus-files
  ["src/fukan/infra/spec.allium"
   "src/fukan/web/spec.allium"
   "src/fukan/web/views/spec.allium"
   "src/fukan/model/spec.allium"
   "src/fukan/model/pipeline.allium"])

(deftest corpus-regression-test
  (testing "every .allium file in src/ parses without failure"
    (doseq [f corpus-files]
      (let [result (parser/parse-file f)]
        (is (not (insta/failure? result))
            (str "parse failed for " f ": " (pr-str result)))))))
