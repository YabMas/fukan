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

;; (`primer-counts-operations-generated-laws` was retired at the essential-correspond cutover: the
;;  generated `:corresponds/*` demand laws — whose count the primer pointed at — dissolved into readings.
;;  The seam is now viewed through `correspondence-card` / `(correspondence)` and the per-Structure
;;  external render `correspondence-form` — all tested below.)

(deftest correspondence-card-renders-authored-form-and-live-readings
  (testing "the card is registry-direct (authored head/match/map) + db-direct (live coverage
            readings) — Task 5 rebuilds it over `s/all-corresponds`: no more generated
            `:corresponds/*` demand keys, coverage is a READING now"
    (let [db   (pipeline/build-model nil)
          card (g/correspondence-card db)]
      (is (str/includes? card "━━ CORRESPONDENCE") "the card still renders its header")
      (is (str/includes? card "[Operation ?op Fn ?fn]")
          "the head renders as a VECTOR — the only shape `correspond` accepts, so this is
           re-authorable syntax, not just descriptive text")
      (is (str/includes? card ":delegates") "the authored realization map renders (e.g. :delegates)")
      (is (str/includes? card "unrealized") "the live unrealized-count reading renders")
      (is (not (str/includes? card ":corresponds/Operation")) "no generated demand keys — they dissolved")
      (is (not (str/includes? card "unaccounted-public"))
          "the card stays VOCAB-GENERIC — it never names the `public` predicate; that reading is
           dev/user.clj's business, appended after the card"))))

(deftest print-dual-keeps-operation-slots-free-of-correspondence
  (testing "the defstructure render carries only forms its parser accepts — Operation's slots are plain,
            never polluted with correspondence options (correspondence is EXTERNAL). The external
            correspondence renders SEPARATELY through `correspondence-form`, its own re-authorable dual."
    (let [db   (pipeline/build-model nil)
          eid  (ffirst (cq/q '[:find ?s :where [?s :structure/of :fukan.canvas.core.reflect/Structure]
                               [?s :val/tag ":fukan.common.vocab.code.operation/Operation"]] db))
          form (g/structure-form db eid)
          slots (first (filter map? form))]
      (is (not-any? #(and (seq? %) (= 'corresponds (first %))) form)
          "defstructure contains only forms its parser accepts")
      (let [cf (g/correspondence-form db eid)]
        (is (= 'correspond (first cf))
            "the design Structure's external correspondence renders as a top-level (correspond …) form")
        (is (= '[Operation ?op Fn ?fn] (second cf))
            "the head is the re-authorable VECTOR of short sort symbols + the author's head vars")
        (is (= '[:cat :calls [:* [:cat [:not public] :calls]]] (:delegates (nth cf 3)))
            "the realization map renders its authored code-graph paths verbatim"))
      (is (= '[:* Operation] (:delegates slots))
          ":delegates is a plain slot — no correspondence options, and no :transitive (closures are the compiler's)")
      (is (not (map? (second (:performs slots))))
          ":performs is a plain identity slot now — no correspondence options in its props position"))))
