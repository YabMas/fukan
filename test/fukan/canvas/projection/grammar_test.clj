(ns fukan.canvas.projection.grammar-test
  "The print-dual: reflect a fixture grammar, render it back, and compare against
   what was authored — the round-trip is the test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.structure :refer [defstructure]]
            [fukan.canvas.projection.grammar :as g]
            ;; side-effect: registers fukan's malli dialect + Clojure extractor + check engine
            [fukan.infra.model]
            [fukan.model.pipeline :as pipeline]))

;; ── the authored grammar the render must reproduce ───────────────────────────

(defstructure PLeaf "A leaf.")

(defstructure PNode
  "A node fixture.
   Second doc line, elided in the primer."
  {:one-ref PLeaf
   :opt-ref [:? PLeaf]
   :seq-ref [:* PLeaf]
   :set-ref [:set PLeaf]
   :title   :string
   :mode    [:enum "a" "b"]}
  (law "no bad title"
    {:offenders [?n]
     :where [[?n :val/title "bad"]]})
  (law "every node references a leaf"
    (has :one-ref)))

(defstructure ^:value PVal
  "A value fixture."
  {:v :int})

(defstructure PCarry
  "A payload fixture: companion code-forms on one- and optional-card scalars."
  {:focus [{:payload :query} :string]
   :note  [:? {:payload :extra} :string]})

(PLeaf ^{:name "l"} p-leaf)

(defn- reflected [] (build/with-grammar (build/vars->cozo [#'p-leaf]) nil))

(defn- struct-node [db tag-str]
  (ffirst (cq/q '[:find ?s :in $ ?t
                  :where [?s :structure/of :fukan.canvas.core.reflect/Structure] [?s :val/tag ?t]]
                db tag-str)))

(deftest structure-form-round-trips-the-authoring
  (let [db (reflected)
        f  (g/structure-form db (struct-node db ":fukan.canvas.projection.grammar-test/PNode"))]
    (is (= '(defstructure PNode
              "A node fixture.\n   Second doc line, elided in the primer."
              {:one-ref PLeaf
               :opt-ref [:? PLeaf]
               :seq-ref [:* PLeaf]
               :set-ref [:set PLeaf]
               :title   :string
               :mode    [:enum "a" "b"]}
              (law "no bad title"
                {:offenders [?n]
                 :where [[?n :val/title "bad"]]})
              (law "every node references a leaf"
                (has :one-ref)))
           f)
        "the rendered form IS the authored form (raw laws unquoted — the parsed
         form; combinator laws render their authored combinator back)")))

(deftest payload-options-round-trip
  (let [db (reflected)
        f  (g/structure-form db (struct-node db ":fukan.canvas.projection.grammar-test/PCarry"))]
    (is (= '(defstructure PCarry
              "A payload fixture: companion code-forms on one- and optional-card scalars."
              {:focus [{:payload :query} :string]
               :note  [:? {:payload :extra} :string]})
           f)
        "a slot's :payload opt rides the reified edge and renders back in props position")))

(deftest value-structures-carry-the-value-meta
  (let [db (reflected)
        f  (g/structure-form db (struct-node db ":fukan.canvas.projection.grammar-test/PVal"))]
    (is (= '(defstructure PVal "A value fixture." {:v :int}) f))
    (is (true? (:value (meta (second f)))) "^:value rides the name symbol's metadata")))

(deftest primer-renders-the-reference-card
  (let [db (reflected)
        p  (g/vocabulary-primer db "fukan.canvas.projection.grammar-test")]
    (testing "header + every structure"
      (is (str/includes? p "fukan.canvas.projection.grammar-test — 4 structures"))
      (is (str/includes? p "(defstructure PLeaf"))
      (is (str/includes? p "(defstructure ^:value PVal")))
    (testing "slots aligned in one map, refined scalars as their forms"
      (is (str/includes? p "{:one-ref PLeaf"))
      (is (str/includes? p ":mode    [:enum \"a\" \"b\"]}")))
    (testing "doc truncated to its first line, laws elided to their desc"
      (is (str/includes? p "\"A node fixture. …\""))
      (is (str/includes? p "(law \"no bad title\" …)"))
      (is (not (str/includes? p "Second doc line")))
      (is (not (str/includes? p ":offenders"))))))

(deftest unused-structures-reads-the-dead-vocabulary
  (testing "the grammar-drift reading: structures with no instances are reported;
            inhabited (PLeaf) and self-reifying (Structure) ones are not"
    (let [unused (set (g/unused-structures (reflected)))]
      (is (every? unused ["PNode" "PVal" "PCarry"])
          "the grammar-only fixtures (never instantiated) are dead vocabulary here")
      (is (not (unused "PLeaf")) "an instantiated structure is spoken")
      (is (not (unused "Structure")) "the meta-grammar inhabits itself (reified nodes)"))))

(deftest primer-covers-the-strange-loop
  (testing "the full primer includes the meta-grammar describing itself"
    (let [db (reflected)
          p  (g/grammar-primer db)]
      (is (str/includes? p "━━ fukan.canvas.core.reflect — "))
      (is (str/includes? p "(defstructure Structure")))))

(deftest primer-counts-operations-generated-laws
  (testing "the primer's corresponds pointer agrees with the generator (3 node [realized, covered, the
            derived agrees] + 1 delegates [:sub] + 1 performs [:sup] = 5; the demands + relation-maps
            come from the EXTERNAL (correspond Operation …), the identity map derived)"
    (let [db (pipeline/build-model nil)]
      (is (str/includes? (g/vocabulary-primer db "fukan.common.vocab.code.operation")
                         "⇒ 5 generated laws")))))

(deftest correspondence-card-shows-the-seam-and-its-generated-laws
  (testing "the card renders named carriers, coverage, and every generated demand with its stable key"
    (let [card (g/correspondence-card)]
      (is (str/includes? card ":module-twin") "the root carrier is an ordinary named relation")
      (is (str/includes? card "coverage :both"))
      (is (str/includes? card ":corresponds/Operation.total"))
      (is (str/includes? card ":corresponds/Operation.delegates-realized"))
      (is (str/includes? card ":corresponds/Operation.performs-covered"))
      (is (str/includes? card "the carrier is left-total")
          "descs come from the generated laws — the invisible laws become visible"))))

(deftest print-dual-round-trips-the-correspondence-seam
  (testing "defstructure and external correspondence render as two valid canonical forms"
    (let [db   (pipeline/build-model nil)
          eid  (ffirst (cq/q '[:find ?s :where [?s :structure/of :fukan.canvas.core.reflect/Structure]
                               [?s :val/tag ":fukan.common.vocab.code.operation/Operation"]] db))
          form (g/structure-form db eid)
          corr (g/correspondence-form db eid)
          slots (first (filter map? form))]
      (is (not-any? #(and (seq? %) (= 'corresponds (first %))) form)
          "defstructure contains only forms its parser accepts")
      (is (= '(correspond Operation [Fn :public]
                {:carrier :operation-twin :coverage :both}
                (:delegates :sub :public-call)
                (:performs :sup [:cat [:* :calls] :performs]))
             corr)
          "external correspondence round-trips as valid canonical input")
      (is (= '[:* Operation] (:delegates slots))
          ":delegates is a plain slot — no correspondence options, and no :transitive (closures are the compiler's)")
      (is (not (map? (second (:performs slots))))
          ":performs is a plain identity slot now — no correspondence options in its props position"))))
