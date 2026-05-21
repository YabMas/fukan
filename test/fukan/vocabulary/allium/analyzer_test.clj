(ns fukan.vocabulary.allium.analyzer-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.vocabulary.allium.analyzer :as analyzer]
            [fukan.libs.allium.parser :as parser]
            [fukan.model.build :as build]))

(defn- ast [text]
  (parser/parse-allium (str "-- allium: 1\n" text)))

(deftest module-container-from-empty-file
  (testing "an empty .allium file produces one module-Container"
    (let [model (build/empty-model)
          a     (ast "")
          model (analyzer/analyze-file model a "test/module")]
      (is (some? (build/get-primitive model "test/module")))
      (is (= :primitive/container
             (:kind (build/get-primitive model "test/module"))))
      (let [tag-app (->> (:tag-apps model)
                         (filter #(= "Module" (-> % :tag :name)))
                         first)]
        (is (some? tag-app) "Allium::Module tag applied")
        (is (= "test/module" (-> tag-app :target :id)))))))

(deftest module-container-multiple-files
  (testing "analyze-file is composable across multiple files"
    (let [model (-> (build/empty-model)
                    (analyzer/analyze-file (ast "") "auth")
                    (analyzer/analyze-file (ast "") "billing"))]
      (is (some? (build/get-primitive model "auth")))
      (is (some? (build/get-primitive model "billing")))
      (is (= 2 (count (filter #(= "Module" (-> % :tag :name))
                              (:tag-apps model))))))))

(deftest module-container-label-defaults-to-coordinate
  (let [model (analyzer/analyze-file (build/empty-model) (ast "") "fukan/web/views")
        c (build/get-primitive model "fukan/web/views")]
    (is (= "fukan/web/views" (:label c)))))

(deftest entity-declaration
  (testing "entity becomes Container with Allium::Entity tag"
    (let [a (ast "entity Order { id: String, total: Integer }")
          model (analyzer/analyze-file (build/empty-model) a "shop")]
      (let [c (build/get-primitive model "shop::Order")]
        (is (some? c))
        (is (= :primitive/container (:kind c)))
        (is (= 2 (count (:fields c))))
        (is (= "id" (-> c :fields first :name))))
      (let [tag-app (->> (:tag-apps model)
                         (filter #(and (= "Entity" (-> % :tag :name))
                                       (= "shop::Order" (-> % :target :id))))
                         first)]
        (is (some? tag-app))))))

(deftest value-declaration
  (testing "value becomes Container with Allium::Value tag"
    (let [a (ast "value Money { amount: Integer, currency: String }")
          model (analyzer/analyze-file (build/empty-model) a "shop")]
      (is (some? (build/get-primitive model "shop::Money")))
      (is (some? (->> (:tag-apps model)
                      (filter #(and (= "Value" (-> % :tag :name))
                                    (= "shop::Money" (-> % :target :id))))
                      first))))))

(deftest variant-declaration
  (testing "variant becomes Container with Allium::Variant tag and specialises edge"
    (let [a (ast "entity Node { id: String }\nvariant ModuleNode : Node { doc: String }")
          model (analyzer/analyze-file (build/empty-model) a "model")]
      (is (some? (build/get-primitive model "model::ModuleNode")))
      (is (some? (->> (:tag-apps model)
                      (filter #(and (= "Variant" (-> % :tag :name))
                                    (= "model::ModuleNode" (-> % :target :id))))
                      first)))
      (let [spec-edges (filter #(= :relation/specialises (:kind %))
                               (:edges model))]
        (is (= 1 (count spec-edges)))
        (is (= "model::ModuleNode" (-> spec-edges first :from :id)))
        (is (= "model::Node" (-> spec-edges first :to :id)))))))

(deftest variant-field-collision
  (testing "variant declaring a field name already on its parent throws"
    (is (thrown-with-msg?
          Exception #"(?i)field.*collision|collision"
          (let [a (ast "entity Node { name: String }\nvariant Mod : Node { name: Integer }")]
            (analyzer/analyze-file (build/empty-model) a "model"))))))

(deftest module-children-populated
  (testing "module-Container's :children includes all declared Containers"
    (let [a (ast "entity Order {}\nvalue Money {}\n")
          model (analyzer/analyze-file (build/empty-model) a "shop")
          module-c (build/get-primitive model "shop")]
      (is (contains? (:children module-c) "shop::Order"))
      (is (contains? (:children module-c) "shop::Money")))))

(deftest named-type-resolution-within-module
  (testing "a field's simple-name type-ref resolves to Composite(Named(...)) if declared in same module"
    (let [a (ast "value Money { amount: Integer }\nentity Order { total: Money }")
          model (analyzer/analyze-file (build/empty-model) a "shop")
          order (build/get-primitive model "shop::Order")
          total-field (->> (:fields order) (filter #(= "total" (:name %))) first)]
      (is (= :type/composite (-> total-field :type-ref :case)))
      (is (= :shape/named (-> total-field :type-ref :shape :case)))
      (is (= "shop::Money" (-> total-field :type-ref :shape :container))))))

(deftest field-with-builtin-scalar
  (testing "fields with built-in scalar type-refs (String, Integer, etc.) get Scalar Type"
    (let [a (ast "entity X { name: String, count: Integer }")
          model (analyzer/analyze-file (build/empty-model) a "test")
          x (build/get-primitive model "test::X")
          name-field (->> (:fields x) (filter #(= "name" (:name %))) first)]
      (is (= :type/scalar (-> name-field :type-ref :case)))
      (is (= "String" (-> name-field :type-ref :name))))))

(deftest external-entity-declaration
  (testing "external entity becomes Container with Allium::ExternalEntity tag"
    (let [a (ast "external entity Foo {}")
          model (analyzer/analyze-file (build/empty-model) a "user")]
      (is (some? (build/get-primitive model "user::Foo")))
      (is (some? (->> (:tag-apps model)
                      (filter #(and (= "ExternalEntity" (-> % :tag :name))
                                    (= "user::Foo" (-> % :target :id))))
                      first))))))

(deftest actor-declaration-simple
  (testing "actor with identified_by only"
    (let [a (ast "actor Author { identified_by: User }")
          model (analyzer/analyze-file (build/empty-model) a "interviews")]
      (is (some? (build/get-primitive model "interviews::Author")))
      (let [actor (build/get-primitive model "interviews::Author")]
        (is (= :primitive/actor (:kind actor))))
      (let [tag-app (->> (:tag-apps model)
                         (filter #(and (= "Actor" (-> % :tag :name))
                                       (= "interviews::Author" (-> % :target :id))))
                         first)]
        (is (some? tag-app) "Allium::Actor tag applied")
        (is (= "User" (-> tag-app :payload :identified_by)))))))

(deftest actor-with-where-predicate
  (testing "actor with where predicate concatenates into identified_by text"
    (let [a (ast "actor Admin { identified_by: User where role = admin }")
          model (analyzer/analyze-file (build/empty-model) a "auth")
          tag-app (->> (:tag-apps model)
                       (filter #(= "Actor" (-> % :tag :name)))
                       first)]
      (is (= "User where role = admin" (-> tag-app :payload :identified_by))))))

(deftest actor-with-within
  (testing "actor with within clause populates within payload slot"
    (let [a (ast "actor Editor { identified_by: User within: Workspace }")
          model (analyzer/analyze-file (build/empty-model) a "docs")
          tag-app (->> (:tag-apps model)
                       (filter #(= "Actor" (-> % :tag :name)))
                       first)]
      (is (= "User" (-> tag-app :payload :identified_by)))
      (is (= "Workspace" (-> tag-app :payload :within))))))

(deftest surface-declaration-basic
  (testing "surface becomes Boundary-only Container with Allium::Surface tag"
    (let [a (ast "actor User { identified_by: U }\nsurface Login { facing actor: User }")
          model (analyzer/analyze-file (build/empty-model) a "auth")]
      (is (some? (build/get-primitive model "auth::Login")))
      (let [tag-app (->> (:tag-apps model)
                         (filter #(and (= "Surface" (-> % :tag :name))
                                       (= "auth::Login" (-> % :target :id))))
                         first)]
        (is (some? tag-app))
        (is (= {:role "actor" :type-ref-name "User"}
               (-> tag-app :payload :facing (select-keys [:role :type-ref-name]))))))))

(deftest surface-provides-emits-provides-edge
  (testing "provides: clauses emit provides Container -> Event edges with Allium::Provides edge-tag"
    (let [a (ast (str "surface API {\n"
                      "    facing actor: User\n"
                      "    provides:\n"
                      "        SubmitForm(payload: Payload)\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "web")
          edges (filter #(= :relation/provides (:kind %)) (:edges model))]
      (is (pos? (count edges)))
      ;; the provides edge connects the Surface to the Event (qualified
      ;; as <module>/<EventName> — Task 13 will retarget cross-module
      ;; events through use-aliases)
      (is (= "web::API" (-> edges first :from :id)))
      (let [provides-tag-apps (filter #(= "Provides" (-> % :tag :name)) (:tag-apps model))]
        (is (pos? (count provides-tag-apps)) "Allium::Provides edge-tag applied")))))

(deftest surface-exposes-emits-exposes-edge
  (testing "exposes: clauses emit exposes Container -> Field edges with Allium::Exposes edge-tag"
    (let [a (ast (str "entity Order { items: String, status: String }\n"
                      "surface ReadOrder {\n"
                      "    facing actor: User\n"
                      "    exposes: Order.items\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          edges (filter #(= :relation/exposes (:kind %)) (:edges model))]
      (is (pos? (count edges)))
      (let [exposes-tag-apps (filter #(= "Exposes" (-> % :tag :name)) (:tag-apps model))]
        (is (pos? (count exposes-tag-apps)) "Allium::Exposes edge-tag applied")))))

(deftest surface-contracts-fulfils-emits-realises
  (testing "contracts: fulfils X emits realises edge with Allium::Fulfils tag"
    (let [a (ast (str "contract OrderSubmission {}\n"
                      "surface Submit {\n"
                      "    facing actor: User\n"
                      "    contracts:\n"
                      "        fulfils OrderSubmission\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          realises-edges (filter #(= :relation/realises (:kind %)) (:edges model))]
      (is (pos? (count realises-edges)))
      (is (= "shop::Submit" (-> realises-edges first :from :id)))
      (is (= "shop::OrderSubmission" (-> realises-edges first :to :id)))
      (let [fulfils-tag-apps (filter #(= "Fulfils" (-> % :tag :name)) (:tag-apps model))]
        (is (pos? (count fulfils-tag-apps)) "Allium::Fulfils edge-tag applied")))))

(deftest surface-contracts-demands-emits-uses
  (testing "contracts: demands X emits uses edge with Allium::Demands tag"
    (let [a (ast (str "contract PaymentGateway {}\n"
                      "surface Checkout {\n"
                      "    facing actor: User\n"
                      "    contracts:\n"
                      "        demands PaymentGateway\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          uses-edges (filter #(= :relation/uses (:kind %)) (:edges model))]
      (is (pos? (count uses-edges)))
      (is (= "shop::Checkout" (-> uses-edges first :from :id)))
      (is (= "shop::PaymentGateway" (-> uses-edges first :to :id)))
      (let [demands-tag-apps (filter #(= "Demands" (-> % :tag :name)) (:tag-apps model))]
        (is (pos? (count demands-tag-apps)) "Allium::Demands edge-tag applied")))))

(deftest contract-declaration-basic
  (testing "contract becomes Container with Allium::Contract tag"
    (let [a (ast "contract Foo {}")
          model (analyzer/analyze-file (build/empty-model) a "shop")]
      (is (some? (build/get-primitive model "shop::Foo")))
      (let [tag-app (->> (:tag-apps model)
                         (filter #(and (= "Contract" (-> % :tag :name))
                                       (= "shop::Foo" (-> % :target :id))))
                         first)]
        (is (some? tag-app))))))

(deftest contract-with-operations
  (testing "contract operations become Operation primitives on the Boundary with Allium::Call tag"
    (let [a (ast (str "contract OrderSubmission {\n"
                      "    provides:\n"
                      "        submit(order: Order, key: String) -> Confirmation\n"
                      "        cancel(order: Order) -> Confirmation\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          contract (build/get-primitive model "shop::OrderSubmission")
          boundary (:boundary contract)
          op-ids (set (:operations boundary))]
      (is (= 2 (count op-ids)))
      (is (contains? op-ids "shop::OrderSubmission.submit"))
      (is (contains? op-ids "shop::OrderSubmission.cancel"))
      ;; Operations themselves should be in the Model
      (let [submit-op (build/get-primitive model "shop::OrderSubmission.submit")]
        (is (= :primitive/operation (:kind submit-op)))
        (is (= 2 (count (:parameters submit-op))))
        (is (= "order" (-> submit-op :parameters first :name)))
        (is (some? (:return-type submit-op))))
      ;; Each Operation gets an Allium::Call tag
      (let [call-tags (filter #(= "Call" (-> % :tag :name)) (:tag-apps model))]
        (is (= 2 (count call-tags)))))))

(deftest contract-fire-and-forget-operation
  (testing "operation with no return type has nil :return-type"
    (let [a (ast (str "contract X {\n"
                      "    provides:\n"
                      "        notify(payload: Payload)\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "test")
          op (build/get-primitive model "test::X.notify")]
      (is (some? op))
      (is (nil? (:return-type op))))))

(deftest rule-declaration-basic
  (testing "rule becomes Rule primitive with Allium::Rule tag"
    ;; Rule and event may share the same local name within a module — K16a's
    ;; slot-aware Event id-string (`<module>::events::<name>`) keeps them in
    ;; distinct kernel-id namespaces. This test exercises the equal-name case
    ;; head-on; see `module-with-rule-and-event-sharing-name` for the locking
    ;; assertion that both primitives coexist with distinct ids.
    (let [a (ast "rule ProcessOrder { when: ProcessOrder() ensures: Ok }")
          model (analyzer/analyze-file (build/empty-model) a "shop")]
      (let [rule (build/get-primitive model "shop::ProcessOrder")]
        (is (some? rule))
        (is (= :primitive/rule (:kind rule))))
      (is (some? (->> (:tag-apps model)
                      (filter #(and (= "Rule" (-> % :tag :name))
                                    (= "shop::ProcessOrder" (-> % :target :id))))
                      first))))))

(deftest rule-requires-clause
  (testing "requires: clause adds Bool Expression to intent.assertions with Allium::Requires tag"
    (let [a (ast (str "rule R {\n"
                      "    when: R(x: String)\n"
                      "    requires: x != null\n"
                      "    ensures: Ok\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "test")
          rule (build/get-primitive model "test::R")
          assertions (-> rule :intent :assertions)]
      (is (= 2 (count assertions))
          "two assertions: requires + ensures")
      ;; Allium::Requires tag applied to one of the assertions
      (is (some? (->> (:tag-apps model)
                      (filter #(= "Requires" (-> % :tag :name)))
                      first))))))

(deftest rule-let-clause
  (testing "let x = expr adds Definition to body.definitions with Allium::Let tag"
    (let [a (ast (str "rule R {\n"
                      "    when: R(x: String)\n"
                      "    let y = x.upper\n"
                      "    ensures: Ok\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "test")
          rule (build/get-primitive model "test::R")
          definitions (-> rule :body :definitions)]
      (is (= 1 (count definitions)))
      (is (= "y" (-> definitions first :name)))
      (is (some? (->> (:tag-apps model)
                      (filter #(= "Let" (-> % :tag :name)))
                      first))))))

(deftest rule-ensures-clause
  (testing "ensures: clause adds Bool Expression to intent.assertions with Allium::Ensures tag"
    (let [a (ast (str "rule R {\n"
                      "    when: R(x: Integer)\n"
                      "    ensures: x > 0\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "test")
          rule (build/get-primitive model "test::R")]
      (is (some? rule))
      (is (some? (->> (:tag-apps model)
                      (filter #(= "Ensures" (-> % :tag :name)))
                      first))))))

(deftest rule-multiple-assertions
  (testing "multiple requires/ensures clauses each get their own Expression + source tag"
    (let [a (ast (str "rule R {\n"
                      "    when: R(x: Integer)\n"
                      "    requires: x > 0\n"
                      "    requires: x < 100\n"
                      "    ensures: y = x * 2\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "test")
          rule (build/get-primitive model "test::R")
          assertions (-> rule :intent :assertions)
          requires-tags (filter #(= "Requires" (-> % :tag :name)) (:tag-apps model))
          ensures-tags (filter #(= "Ensures" (-> % :tag :name)) (:tag-apps model))]
      (is (= 3 (count assertions)))
      (is (= 2 (count requires-tags)))
      (is (= 1 (count ensures-tags))))))

;; ---------------------------------------------------------------------------
;; Task 10: Rule effects via canonicalisation
;; ---------------------------------------------------------------------------

(deftest rule-write-effect
  (testing "ensures: post.field = E produces Effect record (edge emission best-effort)"
    (let [a (ast (str "rule R {\n"
                      "    when: R()\n"
                      "    ensures: post.account.balance = 100\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "test")
          rule (build/get-primitive model "test::R")
          effects (-> rule :body :effects)]
      (is (>= (count effects) 1))
      (is (= :effect/write (-> effects first :kind))))))

(deftest rule-create-effect
  (testing "ensures: post.X = T.created(...) produces Effect record"
    (let [a (ast (str "rule PlaceOrder {\n"
                      "    when: P(total: Integer)\n"
                      "    ensures: post.order = Order.created(total)\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          rule (build/get-primitive model "shop::PlaceOrder")
          effects (-> rule :body :effects)]
      (is (>= (count effects) 1))
      (is (= :effect/create (-> effects first :kind))))))

(deftest rule-destroy-effect
  (testing "ensures: not exists post.X produces Effect record"
    (let [a (ast (str "rule CancelOrder {\n"
                      "    when: C()\n"
                      "    ensures: not exists post.order\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          rule (build/get-primitive model "shop::CancelOrder")
          effects (-> rule :body :effects)]
      (is (>= (count effects) 1))
      (is (= :effect/destroy (-> effects first :kind))))))

(deftest rule-emit-effect
  (testing "ensures: emitted(E, args) produces Effect record"
    (let [a (ast (str "rule Ship {\n"
                      "    when: S(order: Order)\n"
                      "    ensures: emitted(OrderShipped, order)\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          rule (build/get-primitive model "shop::Ship")
          effects (-> rule :body :effects)]
      (is (>= (count effects) 1))
      (is (= :effect/emit (-> effects first :kind))))))

(deftest rule-non-effect-ensures-no-effect
  (testing "ensures: x > 0 (Bool assertion, not effect-shaped) produces no Effect records"
    (let [a (ast (str "rule Validate {\n"
                      "    when: V(x: Integer)\n"
                      "    ensures: x > 0\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "test")
          rule (build/get-primitive model "test::Validate")
          effects (-> rule :body :effects)]
      (is (zero? (count effects)) "no Effect records — Bool assertion only"))))

;; ---------------------------------------------------------------------------
;; Task 11: Rule triggers — triggers/observes edges with Allium::Trigger tags
;; ---------------------------------------------------------------------------

(deftest rule-trigger-event-shaped
  (testing "when: EventCall(args) emits triggers: Event -> Rule edge with Allium::Trigger kind external_stimulus"
    (let [a (ast (str "rule ProcessOrder {\n"
                      "    when: OrderPlaced(order: Order)\n"
                      "    ensures: Ok\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          triggers-edges (filter #(= :relation/triggers (:kind %)) (:edges model))]
      (is (= 1 (count triggers-edges)))
      ;; Verify Allium::Trigger tag applied
      (let [trigger-tags (filter #(= "Trigger" (-> % :tag :name)) (:tag-apps model))]
        (is (>= (count trigger-tags) 1))
        (is (= "external_stimulus" (-> trigger-tags first :payload :kind)))))))

(deftest rule-trigger-transitions-to
  (testing "when: x: T.field transitions_to v emits observes edge with kind state_transition"
    (let [a (ast (str "entity Order { status: pending | shipped }\n"
                      "rule Ship {\n"
                      "    when: order: Order.status transitions_to shipped\n"
                      "    ensures: Ok\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          observes-edges (filter #(= :relation/observes (:kind %)) (:edges model))
          trigger-tags (filter #(= "Trigger" (-> % :tag :name)) (:tag-apps model))]
      (is (= 1 (count observes-edges)))
      (is (= "state_transition" (-> trigger-tags first :payload :kind))))))

(deftest rule-trigger-created
  (testing "when: x: T.created emits observes edge with kind creation"
    (let [a (ast (str "entity Order { id: String }\n"
                      "rule Index {\n"
                      "    when: order: Order.created\n"
                      "    ensures: Ok\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          observes-edges (filter #(= :relation/observes (:kind %)) (:edges model))
          trigger-tags (filter #(= "Trigger" (-> % :tag :name)) (:tag-apps model))]
      (is (= 1 (count observes-edges)))
      (is (= "creation" (-> trigger-tags first :payload :kind))))))

(deftest rule-trigger-derived
  (testing "when: x: T.derived_field emits observes edge with kind derived"
    (let [a (ast (str "rule R {\n"
                      "    when: o: Order.is_complete\n"
                      "    ensures: Ok\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          observes-edges (filter #(= :relation/observes (:kind %)) (:edges model))
          trigger-tags (filter #(= "Trigger" (-> % :tag :name)) (:tag-apps model))]
      (is (= 1 (count observes-edges)))
      (is (= "derived" (-> trigger-tags first :payload :kind))))))

(deftest rule-multiple-when-clauses
  (testing "rule with multiple when: clauses emits one edge per clause"
    (let [a (ast (str "rule R {\n"
                      "    when: OrderPlaced(order: Order)\n"
                      "    when: order: Order.created\n"
                      "    ensures: Ok\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          triggers-edges (filter #(= :relation/triggers (:kind %)) (:edges model))
          observes-edges (filter #(= :relation/observes (:kind %)) (:edges model))]
      (is (= 1 (count triggers-edges)))
      (is (= 1 (count observes-edges))))))

(deftest rule-trigger-on-facing-role-event
  (testing "when: <facing-role>.EventName resolves to a local event-id without embedding the role name"
    ;; Mirrors the views/spec.allium pattern:
    ;;   surface S { facing viewer: User; provides: Pick(x: Integer) }
    ;;   rule R   { when: viewer.Pick(x); ensures: Ok }
    ;; Before the fix, the rule's trigger event-id would be
    ;; `viewz::events::viewer.Pick` (latent garbage). After the fix,
    ;; it resolves to `viewz::events::Pick` and merges with the
    ;; surface's provides site under event synthesis.
    (let [a (ast (str "actor User { identified_by: String }\n"
                      "surface S {\n"
                      "    facing viewer: User\n"
                      "    provides:\n"
                      "        Pick(x: Integer)\n"
                      "}\n"
                      "rule R {\n"
                      "    when: viewer.Pick(x)\n"
                      "    ensures: Ok\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "viewz")
          triggers-edges (filter #(= :relation/triggers (:kind %)) (:edges model))
          trigger-event-ids (set (map #(-> % :from :id) triggers-edges))]
      (is (= 1 (count triggers-edges))
          "exactly one triggers edge emitted for the rule's when:call")
      (is (contains? trigger-event-ids "viewz::events::Pick")
          "trigger event-id is the local Pick event, not viewer.Pick")
      (is (not-any? (fn [id] (str/includes? id "viewer."))
                    trigger-event-ids)
          "no trigger event-id embeds the facing-role prefix"))))

;; ---------------------------------------------------------------------------
;; Task 12: Invariants + annotations → Bool Expressions + Clauses
;; ---------------------------------------------------------------------------

(deftest top-level-invariant
  (testing "top-level invariant lands as Bool Expression in module-Container's intent.assertions with Allium::Invariant tag"
    (let [a (ast (str "invariant TotalIsPositive {\n"
                      "    Order.total > 0\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          module-c (build/get-primitive model "shop")]
      (is (some? (:intent module-c)))
      (is (pos? (count (-> module-c :intent :assertions))))
      (let [invariant-tags (filter #(= "Invariant" (-> % :tag :name)) (:tag-apps model))]
        (is (= 1 (count invariant-tags)))
        (is (= "shop" (-> invariant-tags first :target :container)))))))

(deftest entity-level-invariant
  (testing "entity-level invariant lands as Bool Expression in entity-Container's intent.assertions"
    (let [a (ast (str "entity Order {\n"
                      "    total: Integer\n"
                      "    invariant TotalNonNeg {\n"
                      "        total >= 0\n"
                      "    }\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          order (build/get-primitive model "shop::Order")]
      (is (some? (:intent order)))
      (is (pos? (count (-> order :intent :assertions))))
      (let [invariant-tags (filter #(and (= "Invariant" (-> % :tag :name))
                                          (= "shop::Order" (-> % :target :container)))
                                    (:tag-apps model))]
        (is (= 1 (count invariant-tags)))))))

(deftest surface-guarantee-annotation
  (testing "@guarantee Name with prose body lands as Clause in surface boundary.intent.clauses with Allium::SurfaceGuarantee tag"
    (let [a (ast (str "surface S {\n"
                      "    facing actor: User\n"
                      "    @guarantee Idempotent\n"
                      "        -- repeated submissions yield identical state\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          surface (build/get-primitive model "shop::S")
          guarantees (-> surface :boundary :intent :clauses)]
      (is (= 1 (count guarantees)))
      (is (= "Idempotent" (-> guarantees first :label)))
      (is (.contains (-> guarantees first :body) "repeated submissions"))
      (is (some? (->> (:tag-apps model)
                      (filter #(= "SurfaceGuarantee" (-> % :tag :name)))
                      first))))))

(deftest contract-invariant-annotation
  (testing "@invariant Name on contract lands as Clause in contract intent.clauses with Allium::ContractInvariant tag"
    (let [a (ast (str "contract C {\n"
                      "    foo: () -> R\n"
                      "    @invariant Atomic\n"
                      "        -- foo is atomic w.r.t. concurrent calls\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          contract (build/get-primitive model "shop::C")
          invariants (-> contract :intent :clauses)]
      (is (= 1 (count invariants)))
      (is (= "Atomic" (-> invariants first :label)))
      (is (some? (->> (:tag-apps model)
                      (filter #(= "ContractInvariant" (-> % :tag :name)))
                      first))))))

(deftest guidance-annotation
  (testing "@guidance annotation lands as Clause with Allium::Guidance tag"
    (let [a (ast (str "surface S {\n"
                      "    facing actor: User\n"
                      "    @guidance\n"
                      "        -- prefer the contract path over surface internals\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          surface (build/get-primitive model "shop::S")
          guidance-clauses (-> surface :boundary :intent :clauses)]
      (is (= 1 (count guidance-clauses)))
      (is (some? (->> (:tag-apps model)
                      (filter #(= "Guidance" (-> % :tag :name)))
                      first))))))

;; ---------------------------------------------------------------------------
;; Task 13: Event synthesis from when/provides/emits sites
;; ---------------------------------------------------------------------------

(deftest event-synthesis-from-surface-provides
  (testing "Surface provides: clause synthesizes an Event with typed parameters"
    (let [a (ast (str "value Payload { data: String }\n"
                      "surface API {\n"
                      "    facing actor: User\n"
                      "    provides:\n"
                      "        SubmitForm(payload: Payload)\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "web")
          event (build/get-primitive model "web::events::SubmitForm")]
      (is (some? event) "Event primitive synthesized")
      (is (= :primitive/event (:kind event)))
      (is (= "SubmitForm" (:label event)))
      (is (= 1 (count (:parameters event))) "one typed parameter from provides")
      (is (= "payload" (-> event :parameters first :name)))
      ;; Allium::Event tag applied
      (let [event-tags (filter #(and (= "Event" (-> % :tag :name))
                                     (= "web::events::SubmitForm" (-> % :target :id)))
                               (:tag-apps model))]
        (is (= 1 (count event-tags)) "Allium::Event tag applied")
        (is (contains? (set (-> event-tags first :payload :declaration-sites))
                       "provides"))))))

(deftest event-synthesis-added-to-module-events
  (testing "synthesized Event id is added to module-Container's :events set"
    (let [a (ast (str "surface API {\n"
                      "    facing actor: User\n"
                      "    provides:\n"
                      "        Ping()\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "web")
          module-c (build/get-primitive model "web")]
      (is (contains? (:events module-c) "web::events::Ping")))))

(deftest event-synthesis-merges-provides-and-when-call
  (testing "provides: + when: EventCall on same name share one Event; params from provides"
    (let [a (ast (str "value Order { id: String }\n"
                      "surface API {\n"
                      "    facing actor: User\n"
                      "    provides:\n"
                      "        OrderPlaced(order: Order)\n"
                      "}\n"
                      "rule HandleOrder {\n"
                      "    when: OrderPlaced(order: Order)\n"
                      "    ensures: Ok\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          event (build/get-primitive model "shop::events::OrderPlaced")
          event-tags (filter #(and (= "Event" (-> % :tag :name))
                                   (= "shop::events::OrderPlaced" (-> % :target :id)))
                             (:tag-apps model))]
      (is (some? event))
      ;; Only one Event primitive
      (is (= 1 (count event-tags)) "exactly one Allium::Event tag")
      ;; Parameters from provides (typed)
      (is (= 1 (count (:parameters event))))
      (is (= "order" (-> event :parameters first :name)))
      ;; Declaration sites include both
      (let [sites (set (-> event-tags first :payload :declaration-sites))]
        (is (contains? sites "provides"))
        (is (contains? sites "when"))))))

(deftest event-synthesis-emit-target-retargeted
  (testing "ensures: emitted(E, ...) edges target the synthesized Event id, not the bare name"
    (let [a (ast (str "value Order { id: String }\n"
                      "surface API {\n"
                      "    facing actor: User\n"
                      "    provides:\n"
                      "        OrderShipped(order: Order)\n"
                      "}\n"
                      "rule Ship {\n"
                      "    when: ShipRequest(order: Order)\n"
                      "    ensures: emitted(OrderShipped, order)\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          emits-edges (filter #(= :relation/emits (:kind %)) (:edges model))]
      (is (= 1 (count emits-edges)))
      ;; Edge :to must point at the slot-aware Event id, not the bare name
      (is (= "shop::events::OrderShipped" (-> emits-edges first :to :id))))))

(deftest event-synthesis-from-emit-alone
  (testing "Rule emits: an Event with no provides/when synthesizes from the emit site"
    (let [a (ast (str "value Order { id: String }\n"
                      "rule Ship {\n"
                      "    when: ShipRequest(order: Order)\n"
                      "    ensures: emitted(OrderShipped, order)\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          event (build/get-primitive model "shop::events::OrderShipped")
          event-tags (filter #(and (= "Event" (-> % :tag :name))
                                   (= "shop::events::OrderShipped" (-> % :target :id)))
                             (:tag-apps model))
          emits-edges (filter #(= :relation/emits (:kind %)) (:edges model))]
      (is (some? event) "Event synthesized from emit site")
      (is (= 1 (count event-tags)))
      ;; Edge :to retargeted
      (is (= 1 (count emits-edges)))
      (is (= "shop::events::OrderShipped" (-> emits-edges first :to :id)))
      (is (contains? (set (-> event-tags first :payload :declaration-sites))
                     "emits")))))

(deftest event-synthesis-from-when-alone
  (testing "when: EventCall on its own synthesizes an Event"
    (let [a (ast (str "rule R {\n"
                      "    when: ExternalSignal(x: String)\n"
                      "    ensures: Ok\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "test")
          event (build/get-primitive model "test::events::ExternalSignal")
          triggers-edges (filter #(= :relation/triggers (:kind %)) (:edges model))]
      (is (some? event) "Event synthesized from when:call site")
      ;; Edge :from points at the slot-aware Event id
      (is (= 1 (count triggers-edges)))
      (is (= "test::events::ExternalSignal" (-> triggers-edges first :from :id))))))

(deftest event-synthesis-arity-mismatch-recorded
  (testing "two provides: declaring the same event with different arity are
    recorded in :phase4-state (Plan 2b carry-forward — analyzer no longer
    throws on shape mismatch; Plan 3c §4b surfaces the record as a Violation)"
    (let [a         (ast (str "value A {}\n"
                              "value B {}\n"
                              "surface S1 {\n"
                              "    facing actor: User\n"
                              "    provides:\n"
                              "        E(a: A)\n"
                              "}\n"
                              "surface S2 {\n"
                              "    facing actor: User\n"
                              "    provides:\n"
                              "        E(a: A, b: B)\n"
                              "}\n"))
          model     (analyzer/analyze-file (build/empty-model) a "test")
          mismatches (get-in model [:phase4-state :event-shape-mismatches])]
      (is (some? model) "analyzer no longer throws on shape mismatch")
      (is (pos? (count mismatches))
          "shape disagreement is recorded for Phase 4 to surface")
      (is (some #(= :arity-mismatch (:reason %)) mismatches))
      (is (every? #(= "test" (:module-coord %)) mismatches)))))

(deftest event-synthesis-typed-vs-untyped-arity-ok
  (testing "untyped sites only constrain arity, not types — single provides + matching-arity when is fine"
    (let [a (ast (str "value Order { id: String }\n"
                      "surface API {\n"
                      "    facing actor: User\n"
                      "    provides:\n"
                      "        OrderPlaced(order: Order)\n"
                      "}\n"
                      "rule R {\n"
                      "    when: OrderPlaced(o: Order)\n"
                      "    ensures: Ok\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "shop")
          event (build/get-primitive model "shop::events::OrderPlaced")]
      (is (some? event))
      (is (= 1 (count (:parameters event))))
      ;; Param name from provides
      (is (= "order" (-> event :parameters first :name))))))

;; ---------------------------------------------------------------------------
;; K16a: slot-aware Event id keeps rule/event names from colliding
;; ---------------------------------------------------------------------------

(deftest module-with-rule-and-event-sharing-name
  (testing "rule and Event with identical local names coexist as distinct primitives"
    ;; K16a: Events use `<module>::events::<name>`, Rules use `<module>::<name>`.
    ;; The pattern `rule X { when: X(...) ... }` mirrors web/views/graph.allium's
    ;; `rule SelectNode { ... when: viewer.SelectNode(...) ... }` shape.
    (let [a (ast (str "value NodeId { id: String }\n"
                      "actor User { identified_by: U }\n"
                      "surface Viewer {\n"
                      "    facing actor: User\n"
                      "    provides:\n"
                      "        SelectNode(node_id: NodeId)\n"
                      "}\n"
                      "rule SelectNode {\n"
                      "    when: SelectNode(node_id: NodeId)\n"
                      "    ensures: Ok\n"
                      "}\n"))
          model (analyzer/analyze-file (build/empty-model) a "views")
          rule  (build/get-primitive model "views::SelectNode")
          event (build/get-primitive model "views::events::SelectNode")]
      (is (some? rule) "Rule primitive at flat <module>::<name>")
      (is (= :primitive/rule (:kind rule)))
      (is (some? event) "Event primitive at slot-aware <module>::events::<name>")
      (is (= :primitive/event (:kind event)))
      ;; Distinct ids — the load-bearing assertion
      (is (not= (:id rule) (:id event)))
      ;; triggers edge :from points at the Event id, :to at the Rule id
      (let [t-edges (filter #(= :relation/triggers (:kind %)) (:edges model))]
        (is (= 1 (count t-edges)))
        (is (= "views::events::SelectNode" (-> t-edges first :from :id)))
        (is (= "views::SelectNode" (-> t-edges first :to :id)))))))
