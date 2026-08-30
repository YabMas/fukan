(ns fukan.canvas.projection.design-test
  "The DESIGN projection — a project's declared design as one document, and the selection that
   narrows it.

   The property worth pinning is not that a selection filters instances; it is that the CONCEPTS
   narrow WITH them. A selected document is small, its grammar is the larger half of it, and
   handing a reader who asked for the architecture the whole element vocabulary as well would
   answer a question nobody asked."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :refer [defstructure]]
            [fukan.canvas.projection.design :as design]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.cozo.build :as build]
            ;; side-effect: registers the malli dialect + Clojure extractor + check engine
            [fukan.infra.model]))

;; ── two vocabularies, so a selection has something to narrow away ────────────

(defstructure DStratum "A stratum of the system." {:label :string})
(defstructure DPart    "A part of it."            {:label :string})

(DStratum floor {:label "the floor"})
(DStratum attic {:label "the attic"})
(DPart    beam  {:label "a beam"})

;; a genuinely SEPARATE vocabulary, so the narrowing has a boundary to respect
(Module   shed  "A module from another vocabulary entirely.")

(def ^:private fixture-vars [#'floor #'attic #'beam #'shed])

(defn- db
  "`with-grammar` because the concepts section reads the REFLECTED grammar — a db built from
   instances alone has the nodes and none of the structures that give them shape."
  []
  (build/with-grammar (build/vars->cozo fixture-vars) nil))

(deftest the-whole-design-carries-every-instance-and-every-concept-used
  (let [t (design/design-text (db) :prose)]
    (is (str/includes? t "### DStratum"))
    (is (str/includes? t "### DPart"))
    (doseq [n ["**floor**" "**attic**" "**beam**"]]
      (is (str/includes? t n)))))

(deftest a-selection-narrows-the-instances
  (let [t (design/design-text (db) :prose '[(DStratum ?n)])]
    (is (str/includes? t "**floor**"))
    (is (str/includes? t "**attic**"))
    (is (not (str/includes? t "**beam**")))))

(deftest a-selection-narrows-the-CONCEPTS-with-the-instances
  (testing "the vocabularies are derived from what the SELECTED nodes instantiate, so asking for
            one sort does not hand back the grammar of unrelated ones — which for a small
            selection is the larger half of the document"
    (let [t (design/design-text (db) :prose '[(DStratum ?n)])]
      (is (str/includes? t "### DStratum"))
      (is (not (str/includes? t "### Module"))
          "a vocabulary the selection instantiates nothing from is gone")
      (testing "but the narrowing is per-VOCABULARY, not per-structure, and that is deliberate:
                a structure names its slot targets by concept, so rendering `Band` without
                `NsPrefix` would describe a shape in terms the document never defines"
        (is (str/includes? t "### DPart")
            "DPart shares a vocabulary with DStratum, so it comes along")))))

(deftest a-selection-matching-nothing-yields-a-document-with-no-concepts
  (testing "empty is not an error — a project may legitimately declare nothing of a sort, and a
            reader asking about it should be told so rather than handed everything"
    (let [t (design/design-text (db) :prose '[(DPart ?n) [?n :val/label "no such beam"]])]
      (is (not (str/includes? t "**beam**")))
      (is (not (str/includes? t "### DPart"))))))

(deftest instances-group-by-sort-not-by-name
  (testing "sorted by name alone a container lands between two of its own members and the
            document reads as an index; the forms register has always grouped by (tag, name)"
    (let [t     (design/design-text (db) :prose)
          sorts (->> (str/split-lines t)
                     (keep #(second (re-matches #"\*\*.+?\*\* \((\w+)\)" %)))
                     dedupe)]
      (is (= ["DPart" "DStratum" "Module"] sorts)
          "each sort appears as one contiguous block"))))

(deftest both-registers-take-a-selection
  (let [t (design/design-text (db) :forms '[(DStratum ?n)])]
    (is (str/includes? t "floor"))
    (is (not (str/includes? t "beam")))))

;; ── the way in ───────────────────────────────────────────────────────────────

(deftest the-index-names-every-sort-with-a-count
  (let [t (design/design-index (db))]
    (is (str/includes? t "DStratum"))
    (is (str/includes? t "DPart"))
    (is (str/includes? t "Module"))
    (is (re-find #"2\s+DStratum" t) "two stratum instances, counted")))

(deftest the-index-teaches-the-selection-that-fetches-each-sort
  (testing "a selection is useless without knowing what is selectable, and until the index the
            only thing that said a `DStratum` exists was the whole document — the very thing a
            reader asks for a selection to avoid"
    (let [t (design/design-index (db))]
      (is (str/includes? t "--select '[(DStratum ?n)]'"))
      (is (str/includes? t "--select '[(DPart ?n)]'")))))

(deftest the-index-reports-what-reading-everything-would-cost
  (testing "the first useful question is whether the whole design is affordable at all"
    (let [d (db)
          t (design/design-index d)]
      (is (str/includes? t (str (count (design/design-text d :prose))))
          "the size it reports is the size the document actually is"))))

(deftest the-index-is-far-cheaper-than-the-document-it-indexes
  (let [d (db)]
    (is (< (count (design/design-index d)) (count (design/design-text d :prose)))
        "an index that cost what the document costs would not be a way in")))
