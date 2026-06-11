(ns lib.grammar-test
  "Grammar reflection: the registry projected into the model. A fixture vocab
   exercises every slot shape; `with-grammar` must reify it as Structure nodes,
   `:slot/<card>` edges, Schema value targets (content-deduped with each other),
   Law nodes, and Vocabulary grouping — and the reflected db must satisfy every
   law (meta-integrity), including the meta-grammar's own."
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [lib.grammar :as g]))

;; ── fixture vocab: every cardinality, scalar + refined targets, a law ────────

(defstructure Leaf "A leaf target.")

(defstructure Node
  "Fixture: one slot of every shape."
  {:one-ref Leaf
   :opt-ref [:? Leaf]
   :seq-ref [:* Leaf]
   :set-ref [:set Leaf]
   :title   :String
   :mode    [:enum "a" "b"]}
  (law "no node may be titled \"bad\""
    :offenders '[?n]
    :where '[[?n :val/title "bad"]]))

(def t-leaf (Leaf "l"))
(def t-node (Node "n" (one-ref t-leaf) (title "x") (mode "a")))

(defn- reflected []
  (g/with-grammar (a/assemble-vars [#'t-leaf #'t-node])))

(defn- struct-node [db tag-str]
  (ffirst (d/q '[:find ?s :in $ ?t
                 :where [?s :structure/of :lib.grammar/Structure] [?s :val/tag ?t]]
               db tag-str)))

(deftest structures-reify-as-nodes
  (let [db (reflected)
        n  (struct-node db ":lib.grammar-test/Node")]
    (is (some? n) "Node gets a Structure node keyed by its tag")
    (is (= "Node" (:entity/name (d/entity db n))))
    (is (= "Fixture: one slot of every shape." (:entity/doc (d/entity db n))))))

(deftest slots-reify-as-card-kinded-labeled-edges
  (let [db (reflected)
        n  (struct-node db ":lib.grammar-test/Node")]
    (is (= {"one-ref" :slot/one, "opt-ref" :slot/optional, "seq-ref" :slot/many,
            "set-ref" :slot/set, "title" :slot/one, "mode" :slot/one}
           (into {} (d/q '[:find ?l ?k :in $ ?n
                           :where [?r :rel/from ?n] [?r :rel/kind ?k] [?r :rel/label ?l]]
                         db n)))
        "cardinality rides the rel kind, the slot name the label")
    (is (= (range 6)
           (->> (d/q '[:find ?l ?o :in $ ?n
                       :where [?r :rel/from ?n] [?r :rel/label ?l] [?r :rel/order ?o]]
                     db n)
                (map second) sort))
        "declaration order rides :rel/order")
    (is (= (struct-node db ":lib.grammar-test/Leaf")
           (ffirst (d/q '[:find ?t :in $ ?n
                          :where [?r :rel/from ?n] [?r :rel/label "one-ref"] [?r :rel/to ?t]]
                        db n)))
        "a relation slot's edge targets the reified target Structure")))

(deftest scalar-and-refined-targets-are-schema-values
  (let [db (reflected)
        n  (struct-node db ":lib.grammar-test/Node")
        target-kind (fn [label]
                      (ffirst (d/q '[:find ?k :in $ ?n ?l
                                     :where [?r :rel/from ?n] [?r :rel/label ?l] [?r :rel/to ?t]
                                            [?t :val/kind ?k]]
                                   db n label)))]
    (is (= "string" (target-kind "title")) ":String reifies as ⟨Schema :string⟩")
    (is (= "enum" (target-kind "mode")) "[:enum …] reifies as its Schema subgraph")
    (is (= #{"a" "b"}
           (set (d/q '[:find [?v ...] :in $ ?n
                       :where [?r :rel/from ?n] [?r :rel/label "mode"] [?r :rel/to ?t]
                              [?c :rel/from ?t] [?c :rel/kind :choice] [?c :rel/to ?ch]
                              [?ch :val/value ?v]]
                     db n)))
        "the enum's members are queryable choices")
    (is (= 1 (count (d/q '[:find ?t :where [?t :structure/of :lib.type.malli/Schema]
                                           [?t :val/kind "string"]] db)))
        "every :String slot in the model shares ONE content-deduped ⟨Schema :string⟩")))

(deftest laws-reify-with-their-datalog-payload
  (let [db (reflected)
        n  (struct-node db ":lib.grammar-test/Node")
        [law] (first (d/q '[:find ?l :in $ ?n
                            :where [?r :rel/from ?n] [?r :rel/kind :law] [?r :rel/to ?l]] db n))
        e  (d/entity db law)]
    (is (= "no node may be titled \"bad\"" (:val/desc e)))
    (is (= '[[?n :val/title "bad"]] (:where (:val/form e)))
        "the law's datalog rides as a queryable form payload")))

(deftest vocabulary-groups-a-namespace
  (let [db (reflected)]
    (is (= #{"Leaf" "Node"}
           (set (d/q '[:find [?n ...]
                       :where [?v :structure/of :lib.grammar/Vocabulary]
                              [?v :entity/name "lib.grammar-test"]
                              [?r :rel/from ?v] [?r :rel/kind :child] [?r :rel/to ?c]
                              [?c :entity/name ?n]]
                     db))))))

(deftest the-reflection-self-reifies
  (testing "the strange loop: lib.grammar's own Structure gets a Structure node"
    (let [db (reflected)]
      (is (some? (struct-node db ":lib.grammar/Structure")))
      (is (some? (struct-node db ":lib.grammar/Law"))))))

(deftest instances-join-their-structure
  (testing "the of-structure rule binds an instance to its reified grammar"
    (let [db (reflected)]
      (is (= "Node"
             (ffirst (d/q '[:find ?sn :in $ %
                            :where [?i :entity/name "n"] (of-structure ?i ?s) [?s :entity/name ?sn]]
                          db g/rules)))))))

(deftest reflected-model-satisfies-every-law
  (testing "meta-integrity: reflection adds no violations (the meta-grammar's own
            slot laws run over the reified nodes)"
    (is (empty? (s/check (reflected))))))
