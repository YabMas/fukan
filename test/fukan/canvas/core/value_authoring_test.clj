(ns fukan.canvas.core.value-authoring-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s]))

(deftest instance-value-record-holds-composition
  (let [iv (s/->InstanceValue :Box "A" nil {} [] false)]
    (is (= :Box (:tag iv)))
    (is (= "A" (:name iv)))
    (is (s/instance-value? iv))))

(def ^:private sample-iv (s/->InstanceValue :Box "A" nil {} [] false))

(deftest var-id-is-fully-qualified
  (is (= "fukan.canvas.core.value-authoring-test/sample-iv"
         (s/var-id #'sample-iv))))

;; Top-level vocab for the entity-constructor test: defstructure is a macro so the
;; generated constructor macros (Attr, Ent) must be defined at compile-time (top level)
;; for later forms in the same compilation unit to use them.
(s/defstructure Attr "an attribute" (slot :required (one :Bool)))
(s/defstructure Ent  "an entity"
  (slot :attr (many Attr))
  (slot :links (one Ent)))

;; Forward declaration needed for the cyclic ref before the var exists.
(declare ev-test-User)

(def ev-test-name (Attr "name" (required true)))
(def ev-test-User (Ent "User" (attr ev-test-name) (links ev-test-User)))

(deftest entity-constructor-returns-value-with-var-refs
  (is (s/instance-value? ev-test-User))
  (is (= :Ent (:tag ev-test-User)))
  (is (= {:val/required true} (:scalars ev-test-name)))
  ;; the :links target is captured as the var, not deref'd yet
  (is (= [#'ev-test-User] (:targets (first (filter #(= :links (:rk %)) (:clauses ev-test-User)))))))

;; ── Task 3: ordered slots and [label target] clauses ─────────────────────────
;; defstructure and instance forms must be at top level (macro defined and used
;; in the same compilation unit requires top-level definitions).

(s/defstructure Sym  "symbol")
(s/defstructure Prod "production" (slot :rhs (ordered Sym)))
(s/defstructure Lnk  "link" (slot :to (one Sym)))

(def t3-x (Sym "x"))
(def t3-y (Sym "y"))
(def t3-p (Prod "p" (rhs [t3-x t3-y])))
(def t3-l (Lnk "l" (to [edge t3-y])))

(deftest ordered-and-labelled-clauses
  ;; ordered vector splices in order — :targets holds both elements as vars
  (is (= [#'t3-x #'t3-y] (:targets (first (:clauses t3-p)))))
  ;; [label target] form → per-target :labels on the clause map
  (is (= ["edge"] (:labels (first (:clauses t3-l)))))
  ;; and the single target is captured as a var
  (is (= [#'t3-y] (:targets (first (:clauses t3-l))))))

;; ── ordered slot WITH per-element labels: order + label both carried ──────────
;; reuses Prod (slot :rhs (ordered Sym)) — exercises an ordered slot both ways.

;; bare ordered: order only, no labels
(def op-bare (Prod "bare" (rhs [t3-x t3-y])))
;; labelled ordered: each element is a [label target]
(def op-lbl  (Prod "lbl"  (rhs [[a t3-x] [b t3-y]])))

(deftest ordered-slot-carries-order-and-labels
  ;; the labelled ordered clause parses each [label target] → parallel :labels + :targets
  (is (= ["a" "b"] (:labels (first (:clauses op-lbl)))))
  (is (= [#'t3-x #'t3-y] (:targets (first (:clauses op-lbl)))))
  ;; bare ordered → no labels on the clause (existing behaviour pinned)
  (is (nil? (:labels (first (:clauses op-bare)))))
  (is (= [#'t3-x #'t3-y] (:targets (first (:clauses op-bare)))))
  ;; assembled: each reified :rhs relation carries :rel/order (0,1 in authoring
  ;; order) AND the matching :rel/label
  (let [db   (a/assemble-vars [#'op-lbl #'t3-x #'t3-y])
        rels (->> (d/q '[:find ?ord ?lbl ?tn :in $ ?from
                         :where [?r :rel/from ?from] [?r :rel/kind :rhs]
                                [?r :rel/order ?ord] [?r :rel/label ?lbl]
                                [?r :rel/to ?to] [?to :entity/name ?tn]]
                       db [:entity/id (s/var-id #'op-lbl)])
                  (sort-by first))]
    (is (= [[0 "a" "x"] [1 "b" "y"]] rels)))
  ;; bare ordered assembles with :rel/order but no :rel/label
  (let [db (a/assemble-vars [#'op-bare #'t3-x #'t3-y])
        rels (->> (d/q '[:find ?ord ?tn :in $ ?from
                         :where [?r :rel/from ?from] [?r :rel/kind :rhs]
                                [?r :rel/order ?ord]
                                [?r :rel/to ?to] [?to :entity/name ?tn]]
                       db [:entity/id (s/var-id #'op-bare)])
                  (sort-by first))
        labels (d/q '[:find ?lbl :in $ ?from
                      :where [?r :rel/from ?from] [?r :rel/kind :rhs] [?r :rel/label ?lbl]]
                    db [:entity/id (s/var-id #'op-bare)])]
    (is (= [[0 "x"] [1 "y"]] rels))
    (is (empty? labels) "bare ordered slot carries no :rel/label")))

;; ── entity name defaults to the binding var's simple name ────────────────────
(s/defstructure Named "n" (slot :doc (optional :String)))

(def nm-derived  (Named))             ; no name → derived from the var
(def nm-override (Named "explicit"))  ; explicit string → overrides the var

(deftest entity-without-a-name-takes-the-vars-simple-name
  (is (nil? (:name nm-derived)) "the InstanceValue carries no name until assembly")
  (let [db (a/assemble-vars [#'nm-derived #'nm-override])]
    (is (= "nm-derived"
           (:entity/name (d/entity db [:entity/id (s/var-id #'nm-derived)])))
        "the node is named after its binding var")
    (is (= "explicit"
           (:entity/name (d/entity db [:entity/id (s/var-id #'nm-override)])))
        "an explicit string still overrides — for dotted module names / renamed vars")))

;; ── Task 4: ^:value structures — anonymous, content-identified ───────────────

(s/defstructure ^:value Shp "a shape"
  (slot :kind (one :String))
  (slot :of (many Shp)))

(def t4-s1 (Shp (kind "leaf")))
(def t4-s2 (Shp (kind "leaf")))

(deftest value-structures-dedupe-by-content
  (is (:value? t4-s1))
  ;; equal content → equal computed id (the assembler will stamp :entity/id from this)
  (is (= (s/value-content-key t4-s1) (s/value-content-key t4-s2))))

;; ── Task 7b: reader-slot expansion ───────────────────────────────────────────
;; A ^:value structure that declares a (reader fn) allows slot args to be
;; authored as data literals (symbol / vector / map). The constructor macro
;; expands them at macroexpansion time via the reader and inlines the result as
;; a nested value-form — so the outermost var-refs are resolved by normal rules.

(defn t7b-read-shape [data]
  (cond
    (symbol? data) [(list 'kind "type") (list 'type data)]
    (vector? data) [(list 'kind "list") (list 'of (first data))]
    (map?    data) (into [(list 'kind "record")]
                         (map (fn [[k v]] (list 'of [(symbol (name k)) v])) data))))

(s/defstructure RKind "kind")
(s/defstructure ^:value RShape "shape"
  (slot :kind (one :String))
  (slot :of   (many RShape))
  (slot :type (optional RKind))
  (reader t7b-read-shape))
(s/defstructure SHolder "h" (slot :shape (one RShape)))

(def Db  (RKind "Db"))
(def Foo (RKind "Foo"))

;; symbol literal → "type" shape referencing the RKind named Db
(def t7b-leaf (SHolder "leaf" (shape Db)))
;; vector literal → "list" shape whose child is a "type" shape naming Db
(def t7b-list (SHolder "list" (shape [Db])))
;; map literal → "record" shape with field f naming Foo
(def t7b-rec  (SHolder "rec"  (shape {:f Foo})))

(deftest reader-slot-symbol-literal
  ;; (shape Db) → inline RShape value-form with {:val/kind "type"} and :type → RKind "Db"
  (testing "shape slot accepts a symbol literal and expands via reader"
    ;; The InstanceValue for t7b-leaf should have a :shape clause whose target
    ;; is itself an InstanceValue (RShape), not a Var.
    (let [shape-clause (first (filter #(= :shape (:rk %)) (:clauses t7b-leaf)))
          shape-iv     (first (:targets shape-clause))]
      (is (some? shape-clause) "SHolder must have a :shape clause")
      (is (s/instance-value? shape-iv) "the :shape target must be an inline InstanceValue (not a Var)")
      (is (= :RShape (:tag shape-iv)) "the inline value must be tagged :RShape")
      (is (= {:val/kind "type"} (:scalars shape-iv)) "kind scalar must be \"type\"")
      ;; the :type rel must be a var-ref to the RKind var Db
      (let [type-clause (first (filter #(= :type (:rk %)) (:clauses shape-iv)))]
        (is (some? type-clause) "RShape must have a :type clause")
        (is (= [#'Db] (:targets type-clause)) ":type target must be captured as (var Db)")))))

(deftest reader-slot-vector-literal
  ;; (shape [Db]) → "list" shape whose :of child is a reader-expanded "type" shape
  (testing "shape slot accepts a vector literal and expands via reader"
    (let [shape-clause (first (filter #(= :shape (:rk %)) (:clauses t7b-list)))
          shape-iv     (first (:targets shape-clause))]
      (is (s/instance-value? shape-iv))
      (is (= {:val/kind "list"} (:scalars shape-iv)) "kind must be \"list\"")
      ;; the :of clause's target should be an inline RShape with kind "type" naming Db
      (let [of-clause (first (filter #(= :of (:rk %)) (:clauses shape-iv)))
            of-iv     (first (:targets of-clause))]
        (is (some? of-clause) "the list shape must have an :of clause")
        (is (s/instance-value? of-iv) ":of child must be an inline InstanceValue")
        (is (= {:val/kind "type"} (:scalars of-iv)) "child kind must be \"type\"")
        (let [type-clause (first (filter #(= :type (:rk %)) (:clauses of-iv)))]
          (is (= [#'Db] (:targets type-clause)) "child :type must be (var Db)"))))))

(deftest reader-slot-map-literal
  ;; (shape {:f Foo}) → "record" shape with labelled :of child naming Foo
  (testing "shape slot accepts a map literal and expands via reader"
    (let [shape-clause (first (filter #(= :shape (:rk %)) (:clauses t7b-rec)))
          shape-iv     (first (:targets shape-clause))]
      (is (s/instance-value? shape-iv))
      (is (= {:val/kind "record"} (:scalars shape-iv)) "kind must be \"record\"")
      ;; the :of clause must have label "f" and its target is a "type" shape naming Foo
      (let [of-clause (first (filter #(= :of (:rk %)) (:clauses shape-iv)))
            of-iv     (first (:targets of-clause))]
        (is (some? of-clause))
        (is (= ["f"] (:labels of-clause)) "record field label must be \"f\"")
        (is (s/instance-value? of-iv))
        (is (= {:val/kind "type"} (:scalars of-iv)))
        (let [type-clause (first (filter #(= :type (:rk %)) (:clauses of-iv)))]
          (is (= [#'Foo] (:targets type-clause)) "field type must be (var Foo)"))))))

;; ── Change 1: wildcard Any slot ───────────────────────────────────────────────
;; A slot declared (many Any) accepts nodes of any structure — no target-type
;; constraint, only cardinality laws.

(s/defstructure WA "a")
(s/defstructure WB "b")
(s/defstructure Grp "group" (slot :child (many Any)))

(def wa (WA "a-node"))
(def wb (WB "b-node"))
(def grp (Grp "g" (child wa) (child wb)))

(deftest wildcard-slot-accepts-any-type
  (let [db (a/assemble-vars [#'wa #'wb #'grp])]
    (is (empty? (s/check db))
        "no target-type violation when child members are of heterogeneous structures")
    (is (= 2 (count (d/q '[:find ?e
                            :where [?r :rel/kind :child]
                                   [?r :rel/from ?g] [?g :entity/name "g"]
                                   [?r :rel/to ?e]]
                          db)))
        "two heterogeneous members are present")))

;; ── Change 2: :payload support in build-instance-form ─────────────────────────
;; A scalar slot with :payload stores a sibling :val/<payload> leaf alongside
;; the primary :val/<slot> leaf when a 3rd clause element is present.

(s/defstructure Doc "doc" (slot :note (one :String) :payload :extra))

(def t-doc (Doc "d" (note "hello" [:a :b :c])))

(deftest payload-stores-sibling-leaf
  (is (= "hello" (:val/note (:scalars t-doc)))
      "primary scalar leaf is present")
  (is (= [:a :b :c] (:val/extra (:scalars t-doc)))
      "payload sibling leaf is stored alongside the primary"))

;; A payload carries a CODE-FORM (a datalog query, a predicate) — stored as DATA,
;; not evaluated, so unbound symbols like ?n survive instead of resolving at runtime.
(s/defstructure FocusT "f" (slot :focus (one :String) :payload :query))
(s/defstructure HoldT  "h" (slot :holds (one :String) :payload :holds-pred))

(def t-focus (FocusT "X" (focus "the whole model" '[[?n :structure/of _]])))
(def t-hold  (HoldT  "Y" (holds "no violations" (fn [result _db] (empty? (:observations result))))))

(deftest payload-stores-code-form-as-data
  (is (= '[[?n :structure/of _]] (:val/query (:scalars t-focus)))
      "a quoted datalog query payload is stored verbatim as data (not evaluated)")
  (let [pred (:val/holds-pred (:scalars t-hold))]
    (is (seq? pred) "a predicate payload is stored as a form")
    (is (= 'fn (first pred)) "it is a (fn …) form, not an evaluated function"))
  ;; and it survives assembly into the db unchanged
  (let [db (a/assemble-vars [#'t-focus])]
    (is (= '[[?n :structure/of _]]
           (:val/query (d/entity db [:entity/id (s/var-id #'t-focus)])))
        "the query form round-trips through assembly")))

;; ── Change 3: generic in-module rule ─────────────────────────────────────────
;; (in-module ?e ?mname) now resolves via :child relations, not :Module tag.
;; The Grp + wa + wb assembled above: grp is named "g" and has :child rels to wa and wb.

(deftest in-module-via-child-relation
  (let [db      (a/assemble-vars [#'wa #'wb #'grp])
        members (d/q '[:find [?e ...]
                        :in $ %
                        :where (in-module ?e "g")]
                     db (s/vocab-rules))]
    (is (= 2 (count members))
        "(in-module ?e \"g\") via :child relation finds both heterogeneous members")))
