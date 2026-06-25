(ns canvas.vocab.grammar-test
  "Grammar reflection: the registry projected into the model. A fixture vocab
   exercises every slot shape; `with-grammar` must reify it as Structure nodes,
   `:slot/<card>` edges, Schema value targets (content-deduped with each other),
   Law nodes, and Vocabulary grouping — and the reflected db must satisfy every
   law (meta-integrity), including the meta-grammar's own."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            ;; loaded for its side-effect: registers the Cozo check engine so (s/check db) dispatches to it
            [fukan.cozo.law]
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
    :offenders '[?n]
    :where '[[?n :val/title "bad"]]))

(Leaf ^{:name "l"} t-leaf)
(Node ^{:name "n"} t-node {:one-ref t-leaf :title "x" :mode "a"})

(defn- reflected []
  (build/with-grammar-cozo (build/vars->cozo [#'t-leaf #'t-node]) nil))

(defn- struct-node [db tag-str]
  (ffirst (cq/q '[:find ?s :in $ ?t
                 :where [?s :structure/of :canvas.vocab.grammar/Structure] [?s :val/tag ?t]]
               db tag-str)))

(deftest structures-reify-as-nodes
  (let [db (reflected)
        n  (struct-node db ":canvas.vocab.grammar-test/Node")]
    (is (some? n) "Node gets a Structure node keyed by its tag")
    (is (= "Node" (:entity/name (cq/entity db n))))
    (is (= "Fixture: one slot of every shape." (:entity/doc (cq/entity db n))))))

(deftest slots-reify-as-card-kinded-labeled-edges
  (let [db (reflected)
        n  (struct-node db ":canvas.vocab.grammar-test/Node")]
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
    (is (= (struct-node db ":canvas.vocab.grammar-test/Leaf")
           (ffirst (cq/q '[:find ?t :in $ ?n
                          :where [?r :rel/from ?n] [?r :rel/label "one-ref"] [?r :rel/to ?t]]
                        db n)))
        "a relation slot's edge targets the reified target Structure")))

(deftest scalar-and-refined-targets-are-schema-values
  (let [db (reflected)
        n  (struct-node db ":canvas.vocab.grammar-test/Node")
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
    (is (= 1 (count (cq/q '[:find ?t :where [?t :structure/of :canvas.vocab.type/Schema]
                                           [?t :val/kind "string"]] db)))
        "every :string slot in the model shares ONE content-deduped ⟨Schema :string⟩")))

(deftest laws-reify-with-their-datalog-payload
  (let [db (reflected)
        n  (struct-node db ":canvas.vocab.grammar-test/Node")
        [law] (first (cq/q '[:find ?l :in $ ?n
                            :where [?r :rel/from ?n] [?r :rel/kind :law] [?r :rel/to ?l]] db n))
        e  (cq/entity db law)]
    (is (= "no node may be titled \"bad\"" (:val/desc e)))
    (is (= '[[?n :val/title "bad"]] (:where (edn/read-string (:val/form e))))   ; payload pr-str'd in the mirror → read back
        "the law's datalog rides as a queryable form payload")))

(deftest vocabulary-groups-a-namespace
  (let [db (reflected)]
    (is (= #{"Leaf" "Node"}
           (set (cq/q '[:find [?n ...]
                       :where [?v :structure/of :canvas.vocab.grammar/Vocabulary]
                              [?v :entity/name "canvas.vocab.grammar-test"]
                              [?r :rel/from ?v] [?r :rel/kind :child] [?r :rel/to ?c]
                              [?c :entity/name ?n]]
                     db))))))

(deftest the-reflection-self-reifies
  (testing "the strange loop: canvas.vocab.grammar's own Structure gets a Structure node"
    (let [db (reflected)]
      (is (some? (struct-node db ":canvas.vocab.grammar/Structure")))
      (is (some? (struct-node db ":canvas.vocab.grammar/Law"))))))

(deftest instances-join-their-structure
  (testing "an instance joins its reified grammar Structure by tag"
    ;; The instance→Structure JOIN, asserted directly: an instance's :structure/of tag
    ;; (mirror-stringified, no colon) names the Structure whose :val/tag is its colon-prefixed form.
    (let [db   (reflected)
          itag (ffirst (cq/q '[:find ?t :where [?i :entity/name "n"] [?i :structure/of ?t]] db))]
      (is (= "Node"
             (ffirst (cq/q '[:find ?sn :in $ ?vt
                             :where [?s :structure/of :canvas.vocab.grammar/Structure]
                                    [?s :val/tag ?vt] [?s :entity/name ?sn]]
                           db (str ":" itag))))))))

(deftest reflected-model-satisfies-every-law
  (testing "meta-integrity: reflection adds no violations (the meta-grammar's own
            slot laws run over the reified nodes)"
    (is (empty? (s/check (reflected))))))
