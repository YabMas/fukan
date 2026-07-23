(ns fukan.canvas.core.reflect-test
  "Grammar reflection: the registry projected into the model. A fixture vocab
   exercises every slot shape; `with-grammar` must reify it as Structure nodes,
   `:slot/<card>` edges, Schema value targets (content-deduped with each other),
   Law nodes, and Vocabulary grouping — and the reflected db must satisfy every
   law (meta-integrity), including the meta-grammar's own."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            ;; loaded for its side-effect: registers the Cozo check engine so (law/check db) dispatches to it
            [fukan.cozo.law :as law]
            ;; loaded for its side-effect: registers the malli type dialect, so scalar slots reflect
            ;; as Schema value nodes even when this namespace runs standalone
            [fukan.common.typing.malli]
            ;; the live-model signature test builds the real model (composition root + pipeline)
            [fukan.infra.model]
            [fukan.model.pipeline :as pipeline]
            [fukan.canvas.core.structure :as s :refer [defstructure]]))

;; ── fixture vocab: every cardinality, scalar + refined targets, a law ────────

(defstructure Leaf "A leaf target.")

(defstructure Node
  "Fixture: one slot of every shape."
  {:one-ref Leaf
   :opt-ref [:? Leaf]
   :seq-ref [:* Leaf]
   :set-ref [:set Leaf]
   :title   :string
   :mode    [:enum "a" "b"]}
  (law "no node may be titled \"bad\""
    {:offenders [?n]
     :where [[?n :val/title "bad"]]}))

;; ── correspondence fixtures: a design/fact pair, a bridge, two derived relations ──
;; `m-`-prefixed to keep the GLOBAL (unqualified) relation-rule namespace collision-free.

(defstructure MFact "Fixture codomain." {:mcalls [:* MFact]})
(defstructure MSrc  "Fixture domain."   {:mdel   [:* MSrc]})

(s/defrelation :m-public "fixture sub-sort: every MFact"
  [?x] [(is ?x MFact)])
(s/defrelation :m-link "fixture derived: a direct mcalls edge"
  [?a ?b] [(mcalls ?a ?b)])
(s/defrelation :m-twin "fixture carrier: exact-name design/fact pairs"
  [?a ?b]
  [(is ?a MSrc) (design ?a) (is ?b MFact) (fact ?b)
   (named ?a ?name) (named ?b ?name)])

(s/correspond-legacy MSrc [MFact :m-public]
  {:carrier :m-twin :coverage :both}
  (:mdel :sub :m-link))

(Leaf ^{:name "l"} t-leaf)
(Node ^{:name "n"} t-node {:one-ref t-leaf :title "x" :mode "a"})

(defn- reflected []
  (build/with-grammar (build/vars->cozo [#'t-leaf #'t-node]) nil))

(defn- struct-node [db tag-str]
  (ffirst (cq/q '[:find ?s :in $ ?t
                 :where [?s :structure/of :fukan.canvas.core.reflect/Structure] [?s :val/tag ?t]]
               db tag-str)))

(deftest structures-reify-as-nodes
  (let [db (reflected)
        n  (struct-node db ":fukan.canvas.core.reflect-test/Node")]
    (is (some? n) "Node gets a Structure node keyed by its tag")
    (is (= "Node" (:entity/name (cq/entity db n))))
    (is (= "Fixture: one slot of every shape." (:entity/doc (cq/entity db n))))))

(deftest slots-reify-as-card-kinded-labeled-edges
  (let [db (reflected)
        n  (struct-node db ":fukan.canvas.core.reflect-test/Node")]
    (is (= {"one-ref" :slot/one, "opt-ref" :slot/optional, "seq-ref" :slot/many,
            "set-ref" :slot/set, "title" :slot/one, "mode" :slot/one}
           ;; the Cozo mirror stringifies :rel/kind → re-keywordize the cell
           (into {} (map (fn [[l k]] [l (keyword k)]))
                 (cq/q '[:find ?l ?k :in $ ?n
                         :where [?r :rel/from ?n] [?r :rel/kind ?k] [?r :rel/label ?l]]
                       db n)))
        "cardinality rides the rel kind, the slot name the label")
    (is (= (range 6)
           (->> (cq/q '[:find ?l ?o :in $ ?n
                       :where [?r :rel/from ?n] [?r :rel/label ?l] [?r :rel/order ?o]]
                     db n)
                (map second) sort))
        "declaration order rides :rel/order")
    (is (= (struct-node db ":fukan.canvas.core.reflect-test/Leaf")
           (ffirst (cq/q '[:find ?t :in $ ?n
                          :where [?r :rel/from ?n] [?r :rel/label "one-ref"] [?r :rel/to ?t]]
                        db n)))
        "a relation slot's edge targets the reified target Structure")))

(deftest scalar-and-refined-targets-are-schema-values
  (let [db (reflected)
        n  (struct-node db ":fukan.canvas.core.reflect-test/Node")
        target-kind (fn [label]
                      (ffirst (cq/q '[:find ?k :in $ ?n ?l
                                     :where [?r :rel/from ?n] [?r :rel/label ?l] [?r :rel/to ?t]
                                            [?t :val/kind ?k]]
                                   db n label)))]
    (is (= "string" (target-kind "title")) ":string reifies as ⟨Schema :string⟩")
    (is (= "enum" (target-kind "mode")) "[:enum …] reifies as its Schema subgraph")
    (is (= #{"a" "b"}
           (set (cq/q '[:find [?v ...] :in $ ?n
                       :where [?r :rel/from ?n] [?r :rel/label "mode"] [?r :rel/to ?t]
                              [?c :rel/from ?t] [?c :rel/kind :choice] [?c :rel/to ?ch]
                              [?ch :val/value ?v]]
                     db n)))
        "the enum's members are queryable choices")
    (is (= 1 (count (cq/q '[:find ?t :where [?t :structure/of :fukan.common.typing.malli/Schema]
                                           [?t :val/kind "string"]] db)))
        "every :string slot in the model shares ONE content-deduped ⟨Schema :string⟩")))

(deftest laws-reify-with-their-datalog-payload
  (let [db (reflected)
        n  (struct-node db ":fukan.canvas.core.reflect-test/Node")
        [law] (first (cq/q '[:find ?l :in $ ?n
                            :where [?r :rel/from ?n] [?r :rel/kind :law] [?r :rel/to ?l]] db n))
        e  (cq/entity db law)]
    (is (= "no node may be titled \"bad\"" (:val/desc e)))
    (is (= '[[?n :val/title "bad"]] (:where (edn/read-string (:val/form e))))   ; payload pr-str'd in the mirror → read back
        "the law's datalog rides as a queryable form payload")))

(deftest vocabulary-groups-a-namespace
  (let [db (reflected)]
    (is (= #{"Leaf" "Node" "MFact" "MSrc"}
           (set (cq/q '[:find [?n ...]
                       :where [?v :structure/of :fukan.canvas.core.reflect/Vocabulary]
                              [?v :entity/name "fukan.canvas.core.reflect-test"]
                              [?r :rel/from ?v] [?r :rel/kind :child] [?r :rel/to ?c]
                              [?c :entity/name ?n]]
                     db))))))

(deftest the-reflection-self-reifies
  (testing "the strange loop: fukan.canvas.core.reflect's own Structure gets a Structure node"
    (let [db (reflected)]
      (is (some? (struct-node db ":fukan.canvas.core.reflect/Structure")))
      (is (some? (struct-node db ":fukan.canvas.core.reflect/Law"))))))

(deftest instances-join-their-structure
  (testing "an instance joins its reified grammar Structure by tag"
    ;; The instance→Structure JOIN, asserted directly: an instance's :structure/of tag
    ;; (mirror-stringified, no colon) names the Structure whose :val/tag is its colon-prefixed form.
    (let [db   (reflected)
          itag (ffirst (cq/q '[:find ?t :where [?i :entity/name "n"] [?i :structure/of ?t]] db))]
      (is (= "Node"
             (ffirst (cq/q '[:find ?sn :in $ ?vt
                             :where [?s :structure/of :fukan.canvas.core.reflect/Structure]
                                    [?s :val/tag ?vt] [?s :entity/name ?sn]]
                           db (str ":" itag))))))))

(deftest the-correspondence-reflects-as-a-node
  (testing "one Correspondence node per registered `(correspond …)` — the carrier statement decomposed,
            not a payload blob on the design Structure"
    (let [db (reflected)
          m  (ffirst (cq/q '[:find ?m
                             :where [?m :structure/of :fukan.canvas.core.reflect/Correspondence]
                                    [?m :entity/name "MSrc↦MFact"]] db))
          e  (cq/entity db m)]
      (is (some? m))
      (is (= ":m-twin" (:val/carrier e)) "the ordinary carrier relation is queryable")
      (is (= ":both" (:val/coverage e)) "coverage is a separate queryable field")
      (is (= ":m-public" (:val/restrict e)) "so is the codomain sub-sort restriction")
      (is (nil? (:val/corresponds (cq/entity db (struct-node db ":fukan.canvas.core.reflect-test/MSrc"))))
          "the design Structure no longer carries the corresponds blob")
      (is (= (struct-node db ":fukan.canvas.core.reflect-test/MSrc")
             (ffirst (cq/q '[:find ?t :in $ ?m
                             :where [?r :rel/from ?m] [?r :rel/kind :from] [?r :rel/to ?t]] db m)))
          ":from targets the reified domain Structure")
      (is (= (struct-node db ":fukan.canvas.core.reflect-test/MFact")
             (ffirst (cq/q '[:find ?t :in $ ?m
                             :where [?r :rel/from ?m] [?r :rel/kind :to] [?r :rel/to ?t]] db m)))
          ":to targets the reified codomain Structure")
      (is (= #{[":mdel" ":sub" ":m-link"]}
             (set (cq/q '[:find ?rel ?incl ?expr :in $ ?m
                          :where [?r :rel/from ?m] [?r :rel/kind :map] [?r :rel/to ?rm]
                                 [?rm :val/rel ?rel] [?rm :val/incl ?incl] [?rm :val/expr ?expr]]
                        db m)))
          "each relation map is a RelationMap child with rel/incl/expr decomposed"))))

(deftest derived-relations-carry-their-rule
  (testing "a derived defrelation's defining datalog rides its Relation node — a definitional
            extension's body is data, like a Law's"
    (let [db (reflected)
          e  (cq/entity db (ffirst (cq/q '[:find ?r
                                           :where [?r :structure/of :fukan.canvas.core.reflect/Relation]
                                                  [?r :entity/name "m-link"]] db)))]
      (is (= '{:head [?a ?b] :bodies [[(mcalls ?a ?b)]]}
             (edn/read-string (:val/rule e))))
      (is (= "fixture derived: a direct mcalls edge" (:entity/doc e))
          "the element's doc rides along"))))

(deftest vocabularies-are-signatures
  (testing "a Vocabulary owns its declared relation elements — ownership rides the element's
            recorded :ns (an unqualified relation tag cannot carry its namespace)"
    (let [db (reflected)]
      (is (= #{"m-link" "m-public" "m-twin"}
             (set (cq/q '[:find [?n ...]
                          :where [?v :structure/of :fukan.canvas.core.reflect/Vocabulary]
                                 [?v :entity/name "fukan.canvas.core.reflect-test"]
                                 [?r :rel/from ?v] [?r :rel/kind :relation] [?r :rel/to ?rel]
                                 [?rel :entity/name ?n]]
                        db))))))
  (testing "imports are DERIVED from actual use, never authored — the signature's inclusions"
    (let [db (pipeline/build-model nil)
          imports (fn [vn]
                    (set (cq/q '[:find [?n ...] :in $ ?vn
                                 :where [?v :structure/of :fukan.canvas.core.reflect/Vocabulary]
                                        [?v :entity/name ?vn]
                                        [?r :rel/from ?v] [?r :rel/kind :imports] [?r :rel/to ?i]
                                        [?i :entity/name ?n]]
                               db vn)))]
      (is (contains? (imports "fukan.common.vocab.code.kind") "fukan.common.vocab.grouping")
          "Kind's ownership law rule-calls the `contains` genus grouping declares — a rule-call import")
      (is (contains? (imports "fukan.common.vocab.code.subsystem") "fukan.common.vocab.code.module")
          "Subsystem's :child slot targets Module — a slot-target import")
      (is (not (contains? (imports "fukan.common.vocab.grouping") "fukan.common.vocab.code.module"))
          "the primitive vocabulary reaches nothing above it — inclusions point up the ladder"))))

(deftest reflected-model-satisfies-every-law
  (testing "meta-integrity: reflection adds no violations (the meta-grammar's own
            slot laws run over the reified nodes — Correspondence and RelationMap included)"
    (is (empty? (law/check (reflected))))))
