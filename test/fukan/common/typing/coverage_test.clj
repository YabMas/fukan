(ns fukan.common.typing.coverage-test
  "The malli dialect reads ALL of malli. Two things hold that, and they are different claims:

   - COVERAGE is asked of the LIBRARY, at test time: every key of malli's registries is one the
     dialect covers. A builtin malli gains is then a failing test, where before it was a bare
     keyword quietly read as a reference to a type nobody modelled — `:qualified-keyword`
     reported by `:typing/reference-unresolved` is what this file exists to make impossible.
   - ROUND-TRIP is asked of FORMS, one or more per schema type: the form is a schema malli
     itself accepts, the dialect stores it, and renders back the form it was given — or, where
     the rendering is deliberately another spelling of the same schema, exactly the spelling
     `render`'s docstring says.

   A schema type with no sample here fails too, so the two cannot drift apart."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.util :as mu]
            [malli.experimental.time :as mt]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.canvas.core.substrate :as sub]
            [fukan.common.extraction.core :as extraction]
            [fukan.common.typing.malli :as malli]
            [fukan.common.vocab.code.kind :refer [Kind]]
            ;; Kind's ownership law names Module by tag; without it `check` cannot decide
            [fukan.common.vocab.code.module]
            [clojure.walk :as walk]))

(defstructure Typed
  "Test fixture: a named node carrying one Schema."
  {:schema malli/Schema})

;; ── the harness: a malli form → stored nodes → the form rendered back ───────────────────────

(defn- holder [i form]
  [(str "h" i) (sub/->InstanceValue ::Typed (str "h" i) nil {}
                                    [{:rk :schema :card :one
                                      :targets [(s/value-literal->iv ::malli/Schema form)]}]
                                    false)])

(defn- store
  "A db holding each of `forms` as the `:schema` of a node named `h<index>`."
  [forms]
  (build/instances->cozo (vec (map-indexed holder forms))))

(defn- schema-of [db i]
  (ffirst (cq/q '[:find ?s :in $ ?n :where [?h :entity/name ?n] [?r :rel/from ?h]
                  [?r :rel/kind :schema] [?r :rel/to ?s]] db (str "h" i))))

(defn- round-trips
  "Each of `forms` as `[form rendered]`."
  [forms]
  (let [db (store forms)]
    (vec (map-indexed (fn [i form] [form (malli/render db (schema-of db i))]) forms))))

(def ^:private registry
  "What a sample is checked against as genuine malli: the default registry, `malli.util`'s
   schemas, and the two named types the reference samples mention."
  (merge (m/default-schemas) (mu/schemas)
         {:Claim [:map [:id :int]] :order/id :int "Claim" :int}))

(defn- plain
  "A form with each regex as its pattern text — two `#\"a\"` are not `=`."
  [form]
  (walk/postwalk #(if (instance? java.util.regex.Pattern %) (str %) %) form))

(defn- malli? [form] (some? (m/schema form {:registry registry})))

;; ── the samples: every schema type, as malli writes it ───────────────────────────────────────
;; `[form]` renders back as itself; `[form rendered]` is one of render's stated normalisations.

(def ^:private samples
  {;; type schemas
   :any [[:any]] :some [[:some]] :nil [[:nil]] :boolean [[:boolean]] :uuid [[:uuid]]
   :keyword [[:keyword]] :symbol [[:symbol]]
   :qualified-keyword [[:qualified-keyword] [[:qualified-keyword {:namespace :order}]]]
   :qualified-symbol [[:qualified-symbol]]
   :string [[:string] [[:string {:min 1 :max 80}]] [[:string {:title "a name"}]]]
   :int    [[:int] [[:int {:min 0}]] [[:int {:max 9 :gen/max 5}]]]
   :double [[:double] [[:double {:min 0.5 :max 1.5}]]]
   :float  [[:float] [[:float {:min 0.5}]]]
   ;; comparators
   :>  [[[:> 5]]] :>= [[[:>= 0]]] :< [[[:< 1.5]]] :<= [[[:<= 10]]]
   :=  [[[:= :open]] [[:= "open"]] [[:= 3]]]
   :not= [[[:not= nil nil]]]            ; malli: the first nil is the property position
   ;; sequence schemas
   :*  [[[:* :int]]] :+ [[[:+ :string]]] :? [[[:? :int]]]
   :repeat [[[:repeat {:min 2 :max 4} :int]]]
   :cat  [[[:cat :int :string]] [[:cat :int [:* :string]]] [:cat [:cat]]]
   :catn [[[:catn [:n :int] [:s :string]]]]
   :alt  [[[:alt :int :string]]]
   :altn [[[:altn [:n :int] [:s :string]]]]
   ;; base schemas
   :and [[[:and :int [:> 0]]]]
   :or  [[[:or :int :string]]]
   :orn [[[:orn [:n :int] [:s :string]]] [[:orn [:n {:title "a number"} :int] [:s :string]]]]
   :not [[[:not :int]]]
   :map [[:map] [[:map] :map]
         [[:map [:id :int] [:name {:optional true} :string]]]
         [[:map {:closed true} [:id :int]]]
         [[:map [:n {:title "count" :optional true} :int] [:order/id :int] ["k" :string]]
          [:map ["k" :string] [:order/id :int] [:n {:title "count" :optional true} :int]]]
         [[:map [:id {:optional false} :int]] [:map [:id :int]]]
         [[:map :order/id] [:map [:order/id :order/id]]]
         [[:map [:malli.core/default [:map-of :keyword :any]] [:id :int]]]]
   :map-of [[[:map-of :keyword :int]] [[:map-of {:min 1} :string [:vector :int]]]]
   :vector [[[:vector :int]] [[:vector {:min 1 :max 3} :int]]]
   :sequential [[[:sequential :int]]]
   :set [[[:set :keyword]]]
   :seqable [[[:seqable :int]]]
   :every [[[:every :int]]]
   :tuple [[[:tuple :int :string :keyword]]]
   :maybe [[[:maybe :string]]]
   :enum [[[:enum :a :b]] [[:enum "a" "b"]] [[:enum 1 2.5 true]] [[:enum 'a 'b/c :d/e]]
          [[:enum {:title "state"} :open :closed]] [[:enum nil nil 1]]]
   :multi [[[:multi {:dispatch :type}
             [:human [:map [:name :string] [:type :keyword]]]
             [:malli.core/default :any]]]
           ['[:multi {:dispatch first} ["a" [:tuple :string :int]] [1 [:tuple :int :int]]]]]
   :re [[[:re "^a+$"]] [[:re #"^a+$"] [:re "^a+$"]] [[:re {:title "as"} "a+"]]]
   :fn [['[:fn (fn [x] (pos? x))]] ['[:fn {:error/message "positive"} pos?]]]
   :ref [[[:ref "Claim"] :Claim] [[:ref :order/id] :order/id]
         [[:schema {:registry {::cons [:maybe [:tuple :int [:ref ::cons]]]}} [:ref ::cons]]]
         [[:schema {:registry {::pair [:tuple :int :int]}} ::pair]
          [:schema {:registry {::pair [:tuple :int :int]}} [:ref ::pair]]]
         [[:schema {:registry {"pair" [:tuple :int :int]}} [:vector "pair"]]
          [:schema {:registry {"pair" [:tuple :int :int]}} [:vector [:ref "pair"]]]]]
   :schema [[[:schema :int]] [[:schema {:title "wrapped"} [:vector :int]]]]
   :malli.core/schema [[[:malli.core/schema :int]]]
   :=> [[[:=> [:cat :int :string] :boolean]]
        [[:=> [:catn [:n :int] [:rest [:* :any]]] :any]]
        [[:=> :cat :int] [:=> [:cat] :int]]
        [[:=> [:cat] :nil]]
        [[:=> [:cat {:title "args"} :int] :int] [:=> [:cat [:cat {:title "args"} :int]] :int]]
        [[:=> [:catn [:n {:title "count"} :int]] :int] [:=> [:cat [:catn [:n {:title "count"} :int]]] :int]]
        [[:=> [:cat :int [:? :string]] [:maybe :uuid]]]
        ['[:=> [:cat :int :int] :int [:fn (fn [[[a b] r]] (= r (+ a b)))]]]]
   :-> [[[:-> :int :string :boolean]] [[:-> :nil]] ['[:-> {:guard (fn [[[a] r]] (< a r))} :int :int]]]
   :function [[[:function [:=> [:cat :int] :int] [:=> [:cat :int :int] :int]]]
              [[:function [:-> :int :int] [:-> :int :int :int]]]]
   ;; malli.util's opt-in schemas
   :merge [[[:merge [:map [:a :int]] [:map [:b :string]]]]]
   :union [[[:union [:map [:a :int]] [:map [:a :string]]]]]
   :select-keys [[[:select-keys [:map [:a :int] [:b :string]] [:a]]]]
   ;; malli 0.18+; read on any version, so its sample is checked for round-trip only where the
   ;; malli on the classpath does not know it
   :andn [[[:andn [:n :int] [:pos [:> 0]]]]]})

(def ^:private reference-samples
  "References — not registry keys, so they sit outside the enumeration."
  [['Claim :Claim] ['some.ns/Claim :Claim] [:Claim] [:order/id] ["Claim" :Claim]
   ['[:vector (var Claim)] [:vector :Claim]]
   [[:schema {:title "the claim"} :Claim]]])

(defn- registry-keys []
  (concat (keys (m/default-schemas)) (keys (mu/schemas))))

;; ── coverage ─────────────────────────────────────────────────────────────────────────────────

(deftest every-schema-malli-registers-is-covered
  (testing "the registries are what the test reads — a builtin malli gains fails here"
    (is (= [] (vec (remove malli/covers? (registry-keys))))
        "a malli schema the dialect does not read: give it a line in `op-shapes`"))
  (testing "and each keyword type has a form that is exercised below"
    (is (= #{} (into #{} (comp (filter keyword?) (remove samples)) (registry-keys)))
        "a covered schema with no round-trip sample: add one to `samples`"))
  (testing "the enumeration is the size it is believed to be, so a registry that changed shape
            (keys no longer keywords, symbols and fns) cannot pass by being skipped"
    (is (<= 50 (count (filter keyword? (registry-keys)))))
    (is (<= 48 (count (filter symbol? (registry-keys)))))
    (is (<= 48 (count (filter fn? (registry-keys)))))))

(deftest every-builtin-malli-registers-by-default-is-unqualified
  (testing "the keyword partition rests on this: a QUALIFIED keyword is always a reference by its
            whole name, which is safe only while malli's own types are unqualified. The one
            exception is internal and only ever a form's head."
    (is (= #{:malli.core/schema}
           (into #{} (filter #(and (keyword? %) (namespace %))) (registry-keys))))))

(deftest the-opt-in-time-schemas-are-references
  (testing "`malli.experimental.time` registers QUALIFIED types. They are not builtins here: the
            namespace is the domain's to use, so each reads as a reference by its whole name —
            which a project that opts into them resolves by modelling a type of that name."
    (let [forms (vec (sort (keys (mt/schemas))))]
      (is (seq forms))
      (is (every? namespace forms))
      (is (not-any? malli/covers? forms))
      (doseq [[form rendered] (round-trips forms)]
        (is (= form rendered)))
      (is (every? #(= [(list 'kind "ref") (list 'ref (subs (str %) 1))] (malli/read-malli %)) forms)))))

;; ── round trip ───────────────────────────────────────────────────────────────────────────────

(defn- sample-rows []
  (for [[type rows] samples, [form expected :as row] rows]
    {:type type :form form :expected (if (= 2 (count row)) expected form)}))

(deftest every-sample-is-a-schema-malli-accepts
  (doseq [{:keys [type form]} (sample-rows)
          :when (contains? registry type)]
    (is (malli? form) (str "not valid malli: " (pr-str form)))))

(deftest every-schema-type-round-trips
  (let [rows (vec (sample-rows))
        out  (round-trips (mapv :form rows))]
    (doseq [[{:keys [type expected]} [form rendered]] (map vector rows out)]
      (is (= (plain expected) (plain rendered)) (str type " — " (pr-str form))))
    (testing "what is rendered is still malli, and reads back to the node it was rendered from"
      (let [rendered (mapv second out)
            again    (round-trips rendered)]
        (doseq [[{:keys [type]} form] (map vector rows rendered)
                :when (contains? registry type)]
          (is (malli? form) (str "rendered form is not valid malli: " (pr-str form))))
        (is (= (plain rendered) (plain (mapv second again))))))))

(deftest every-predicate-schema-round-trips
  (let [syms (vec (sort (filter symbol? (keys (m/predicate-schemas)))))
        fns  (vec (filter fn? (keys (m/predicate-schemas))))]
    (testing "as the symbol, which is all a schema read from source ever holds"
      (doseq [[form rendered] (round-trips syms)]
        (is (= form rendered))))
    (testing "with properties — a symbol-headed vector, which the reader hands on keyword-headed"
      (doseq [[form rendered] (round-trips (mapv (fn [s] [:vector [s {:title "t"}]]) syms))]
        (is (= form rendered))))
    (testing "as the function object, rendered as the symbol malli registers beside it"
      (is (= (set syms) (set (map second (round-trips fns))))))
    (testing "a predicate is not the type of the same name: `int?` and `:int` are two schemas"
      (let [db (store ['int? :int])]
        (is (not= (schema-of db 0) (schema-of db 1)))))))

(deftest references-round-trip
  (doseq [[[form expected :as row] [_ rendered]] (map vector reference-samples
                                                     (round-trips (mapv first reference-samples)))]
    (is (= (if (= 2 (count row)) expected form) rendered) (pr-str form))))

;; ── what is kept, and where ──────────────────────────────────────────────────────────────────

(deftest properties-are-kept
  (let [db (store [[:vector {:min 1 :max 3 :title "few"} :int]
                   [:double {:min 0.5}]
                   [:map {:closed true} [:id {:optional true :title "the id"} :int]]])
        ent #(cq/entity db (schema-of db %))]
    (testing "an integer bound is a leaf a law can compare, on any schema that takes one"
      (is (= [1 3] ((juxt :val/min :val/max) (ent 0)))))
    (testing "everything else is one opaque leaf, in one spelling"
      (is (= "{:title \"few\"}" (:val/props (ent 0))))
      (is (= "{:closed true}" (:val/props (ent 2)))))
    (testing "a bound that is not an integer stays with the rest, so the slot's type holds"
      (is (nil? (:val/min (ent 1))))
      (is (= "{:min 0.5}" (:val/props (ent 1)))))
    (testing "an entry's `:optional` is read; its other properties ride the entry"
      (is (= #{["id" true "{:title \"the id\"}"]}
             (set (cq/q '[:find ?k ?o ?p :where [?f :val/key ?k] [?f :val/optional ?o] [?f :val/props ?p]] db)))))
    (testing "and no generated slot law objects to any of it"
      (is (empty? (law/check db))))))

(deftest property-order-is-not-identity
  (let [db (store [[:int {:title "n" :description "d"}] [:int {:description "d" :title "n"}]])]
    (is (= (schema-of db 0) (schema-of db 1)))))

(deftest keys-keep-their-type
  (let [forms [[:map [:id :int]] [:map [:order/id :int]] [:map ["id" :int]] '[:map [id :int]]
               [:map [1 :int]] [:map [:true :int]] [:map [true :int]]]
        db    (store forms)]
    (testing "a simple keyword is its bare name — what a query for a field writes"
      (is (= #{"id" ":order/id" "\"id\"" "'id" "1" ":true" "true"}
             (set (map first (cq/q '[:find ?k :where [?f :val/key ?k]] db))))))
    (testing "and seven keys are seven fields"
      (is (= 7 (count (distinct (map #(schema-of db %) (range (count forms))))))))))

(deftest what-is-not-data-is-refused
  (testing "a function object has no text to store"
    (is (thrown? clojure.lang.ExceptionInfo (store [[:fn (fn [x] x)]])))
    (is (thrown? clojure.lang.ExceptionInfo (store [[:multi {:dispatch (fn [x] x)} [:a :int]]]))))
  (testing "an expression computes a schema; a static read does not run it"
    (is (thrown? clojure.lang.ExceptionInfo (store ['[:vector (mu/dissoc Claim :held)]])))
    (is (thrown? clojure.lang.ExceptionInfo (store ['[:=> [:cat (mu/keys Claim)] :any]]))))
  (testing "an op malli does not have is not read as one"
    (is (thrown? clojure.lang.ExceptionInfo (store [[:no-such-op :int]])))
    (is (thrown? clojure.lang.ExceptionInfo (store [[:Claim {:title "x"}]])))
    (is (thrown? clojure.lang.ExceptionInfo (store ['[Claim]]))))
  (testing "nor is a malformed form stored as if it were well formed"
    (is (thrown? clojure.lang.ExceptionInfo (store [[:vector]])))
    (is (thrown? clojure.lang.ExceptionInfo (store [[:map-of :int]])))
    (is (thrown? clojure.lang.ExceptionInfo (store [[:> 1 2]])))
    (is (thrown? clojure.lang.ExceptionInfo (store [:vector])))))

;; ── references and the law over them ─────────────────────────────────────────────────────────

(Kind Claim)

(defn- unresolved [forms]
  (let [db (build/instances->cozo
            (into [["Claim" @#'Claim]] (map-indexed holder forms)))]
    (set (map first (law/violation-rows db :typing/reference-unresolved)))))

(deftest a-builtin-is-not-a-reference
  (testing "the symptom this file exists for: no malli type is reported as an unmodelled name"
    (is (= #{}
           (unresolved (into [:qualified-keyword :qualified-symbol :uuid :some :float 'int? 'uuid?]
                             (for [[type rows] samples, [form] rows
                                   :when (not (#{:ref :map} type))]
                               form)))))))

(deftest a-name-still-is
  (is (= #{"Missing" "order/id" "time/instant"}
         (unresolved [[:vector :Missing] [:map [:id :order/id]] [:maybe :time/instant] 'Claim :Claim]))))

(deftest a-local-registry-name-is-not-held-to-resolve
  (testing "the registry's definitions are kept with the properties, opaque; a reference to a
            name it binds names nothing in the model"
    (is (= #{} (unresolved [[:schema {:registry {::cons [:maybe [:tuple :int [:ref ::cons]]]}} ::cons]
                            [:map {:registry {"pair" [:tuple :int :int]}} [:p "pair"] [:q [:ref "pair"]]]]))))
  (testing "a name the registry does not bind is still a reference, and still held"
    (is (= #{"Elsewhere"}
           (unresolved [[:schema {:registry {::cons :int}} [:tuple ::cons :Elsewhere]]]))))
  (testing "and only SCHEMA positions are local references — a key or an enum member spelling
            the same name is that key, that member"
    (is (= [:map {:registry {:a :int}} [:a [:ref :a]] [:b [:enum :a]]]
           (second (first (round-trips [[:map {:registry {:a :int}} [:a :a] [:b [:enum :a]]]])))))))

;; ── the relations that walk a schema see through every shape ─────────────────────────────────

(defn- mentions
  "The reference names schema `i` is made of, at any depth, through `schema-part`."
  [db i]
  (set (map first (cq/q '[:find ?n :in $ % ?s :where (schema-part+ ?s ?c) [?c :val/kind "ref"] [?c :val/ref ?n]]
                        db (s/vocab-rules) (schema-of db i)))))

(deftest a-schema-s-parts-are-reached-through-every-shape
  (let [forms [[:vector :A] [:maybe [:set :A]] [:or :A :B] [:tuple :A :B] [:map-of :A :B]
               [:map [:a :A] [:b {:optional true} [:vector :B]]]
               [:orn [:a :A] [:b :B]] [:altn [:a :A] [:b :B]] [:catn [:a :A] [:b :B]]
               [:multi {:dispatch :t} [:a :A] [:b [:map [:x :B]]]]
               [:=> [:cat :A] :B] [:-> :A :B] [:function [:=> [:cat :A] :B] [:=> [:cat :A :A] :B]]
               '[:=> [:cat :A] :any [:fn {:about :B} (fn [_] true)]]
               [:merge [:map [:a :A]] [:map [:b :B]]] [:select-keys [:map [:a :A] [:b :B]] [:a]]
               [:schema [:and :A [:not :B]]] [:repeat {:min 1} [:alt :A [:cat :B :B]]]]
        db    (store forms)]
    (doseq [[i form] (map-indexed vector forms)]
      (is (contains? #{#{"A" "B"} #{"A"}} (mentions db i)) (pr-str form))
      (is (contains? (mentions db i) "A") (pr-str form)))
    (testing "both, wherever both are written as schemas"
      (is (= #{"A" "B"} (mentions db 5) (mentions db 9) (mentions db 12) (mentions db 15))))))

(deftest signatures-compare-across-spellings
  (let [forms [[:=> [:cat :int :string] :boolean]                       ; 0
               [:-> :int :string :boolean]                              ; 1  the same function, flat
               [:=> [:catn [:n :int] [:s :string]] :boolean]            ; 2  the same, named
               [:-> :int :boolean]                                      ; 3  one parameter fewer
               [:function [:-> :int :int] [:=> [:cat :int :int] :int]]  ; 4
               [:function [:=> [:cat :int :int] :int] [:=> [:cat :int] :int]] ; 5 the same arities
               [:=> [:cat :int] :int]                                   ; 6
               '[:=> [:cat :int] :int [:fn (fn [[_ r]] (pos? r))]]      ; 7  guarded
               [:=> [:cat [:orn [:n :int] [:s :string]]] :int]          ; 8
               [:=> [:cat [:orn [:n :int] [:s :keyword]]] :int]]        ; 9  an entry differs
        db    (store forms)
        differs? (fn [a b]
                   (boolean (seq (cq/q '[:find ?k :in $ % ?a ?b :where (signature-differs ?a ?b) [?a :val/kind ?k]]
                                       db (s/vocab-rules) (schema-of db a) (schema-of db b)))))]
    (testing "`:->` is `:=>` written flat, and a parameter's name is not what is compared"
      (is (not (differs? 0 1)))
      (is (not (differs? 1 2)))
      (is (not (differs? 1 0))))
    (testing "an arity difference is seen from either spelling"
      (is (differs? 1 3))
      (is (differs? 0 3)))
    (testing "arities are matched by shape, in any order and either spelling"
      (is (not (differs? 4 5))))
    (testing "one arity against several differs"
      (is (differs? 6 4))
      (is (differs? 4 6)))
    (testing "a guard is part of what a function accepts"
      (is (differs? 6 7))
      (is (differs? 7 6)))
    (testing "and a parameter differing anywhere inside it is a different parameter"
      (is (differs? 8 9)))))

;; ── the authored surface: the same forms through the instance macro ──────────────────────────
;; Everything above goes through `value-literal->iv`, the extractor's path. An AUTHORED schema
;; goes through macro expansion, where a clause argument is a code form — so a list would be
;; evaluated and a symbol-headed pair taken for a label unless the reader hands on data.

(Typed a-pred    {:schema [:map [:n pos-int?] [:m [int? {:min 1}]]]})
(Typed a-fn      {:schema [:and :int [:fn {:error/message "even"} (fn [x] (even? x))]]})
(Typed a-re      {:schema [:or [:re #"^a+$"] #"^b+$"]})
(Typed a-enum    {:schema [:enum 1 nil :a "b" [1 2]]})
(Typed a-multi   {:schema [:multi {:dispatch :type} [:a [:map [:type [:= :a]]]] [::m/default :any]]})
(Typed a-arrow   {:schema [:function [:-> :uuid :qualified-keyword] [:=> [:cat :uuid [:* Claim]] :some]]})
(Typed a-local   {:schema [:schema {:registry {::tree [:orn [:leaf :int] [:node [:vector [:ref ::tree]]]]}} ::tree]})

(deftest authored-forms-read-as-extracted-ones-do
  (let [vars [#'Claim #'a-pred #'a-fn #'a-re #'a-enum #'a-multi #'a-arrow #'a-local]
        db   (build/vars->cozo vars)
        of   (fn [n] (malli/render db (ffirst (cq/q '[:find ?s :in $ ?n :where [?h :entity/name ?n] [?r :rel/from ?h]
                                                      [?r :rel/kind :schema] [?r :rel/to ?s]] db n))))]
    (is (= '[:map [:m [int? {:min 1}]] [:n pos-int?]] (of "a-pred")))
    (is (= '[:and :int [:fn {:error/message "even"} (fn [x] (even? x))]] (of "a-fn")))
    (is (= [:or [:re "^a+$"] [:re "^b+$"]] (of "a-re")))
    (is (= [:enum 1 nil :a "b" [1 2]] (of "a-enum")))
    (is (= [:multi {:dispatch :type} [:a [:map [:type [:= :a]]]] [::m/default :any]] (of "a-multi")))
    (is (= [:function [:-> :uuid :qualified-keyword] [:=> [:cat :uuid [:* :Claim]] :some]] (of "a-arrow")))
    (is (= [:schema {:registry {::tree [:orn [:leaf :int] [:node [:vector [:ref ::tree]]]]}} [:ref ::tree]]
           (of "a-local")))
    (is (empty? (law/check db)))))

;; ── the extractor: the same surface, as clj-kondo hands an annotation over ───────────────────

(deftest annotations-in-code-are-read
  (let [db   (build/model->cozo [] (extraction/extract-roots ["test/fixtures/malli_surface"]))
        sigs (into {} (for [[n sig] (cq/q '[:find ?n ?sig :in $ % :where (signature ?f ?sig) [?f :entity/name ?n]]
                                          db (s/vocab-rules))]
                        [n (malli/render db sig)]))]
    (testing "types that used to read as references to nothing"
      (is (= [:=> [:cat :qualified-keyword :uuid] [:maybe :some]] (sigs "types"))))
    (testing "predicate schemas, bare and with properties"
      (is (= '[:=> [:cat pos-int? [string? {:min 1}]] boolean?] (sigs "predicates"))))
    (testing "literal values, a regex LITERAL (clj-kondo hands it over as `(re-pattern …)`) and a
              predicate body, in malli's flat arrow"
      (is (= '[:-> [:enum :open :closed 1 nil] [:re "^a+$"] [:fn (fn [x] (pos? x))] :nil] (sigs "values"))))
    (testing "entry schemas, map properties, qualified keys, a comparator, several arities"
      (is (= [:function
              [:=> [:cat [:orn [:id :int] [:name :string]]]
               [:map {:closed true} [:order/total {:optional true} [:> 0]] [:id :int]]]
              [:=> [:cat :int [:multi {:dispatch :type} [:a [:map [:type [:= :a]]]] [:malli.core/default :any]]]
               [:tuple :int [:set :keyword]]]]
             (sigs "entries"))))
    (testing "a guard"
      (is (= '[:=> [:cat :int :int] :int [:fn (fn [[[a b] r]] (= r (+ a b)))]] (sigs "guarded"))))
    (testing "inline annotations"
      (is (= [:=> [:cat [:and :int [:>= 0]] [:* [:alt :keyword :string]]]
              [:map-of :keyword [:sequential [:or :int :double]]]]
             (sigs "inline"))))
    (testing "a recursive schema: its local name is no reference to a modelled type"
      (is (contains? sigs "recursive"))
      (is (= #{} (law/violation-rows db :typing/reference-unresolved))))
    (testing "what is left unread is what is not data — a computed schema — and it is marked"
      (is (= #{"computed"}
             (set (cq/q '[:find [?n ...] :where [?f :val/signature-unread true] [?f :entity/name ?n]] db)))))))

;; ── the refined-slot bridge is no narrower than what is stored ───────────────────────────────

(deftest valid-reads-what-the-dialect-reads
  (is (malli/valid? [:merge [:map [:a :int]] [:map [:b :string]]] {:a 1 :b "x"}))
  (is (not (malli/valid? [:merge [:map [:a :int]] [:map [:b :string]]] {:a 1 :b 2})))
  (is (malli/valid? [:select-keys [:map [:a :int] [:b :string]] [:a]] {:a 1}))
  (is (malli/valid? :qualified-keyword :a/b))
  (is (not (malli/valid? :qualified-keyword :b)))
  (is (malli/valid? 'pos-int? 3)))
