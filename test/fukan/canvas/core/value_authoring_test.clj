(ns fukan.canvas.core.value-authoring-test
  (:require [clojure.test :refer [deftest is]]
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

;; Top-level vocab for the entity-constructor test: defstructure* is a macro so the
;; generated constructor macros (Attr, Ent) must be defined at compile-time (top level)
;; for later forms in the same compilation unit to use them.
(s/defstructure* Attr "an attribute" (slot :required (one :Bool)))
(s/defstructure* Ent  "an entity"
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
;; defstructure* and instance forms must be at top level (macro defined and used
;; in the same compilation unit requires top-level definitions).

(s/defstructure* Sym  "symbol")
(s/defstructure* Prod "production" (slot :rhs (ordered Sym)))
(s/defstructure* Lnk  "link" (slot :to (one Sym)))

(def t3-x (Sym "x"))
(def t3-y (Sym "y"))
(def t3-p (Prod "p" (rhs [t3-x t3-y])))
(def t3-l (Lnk "l" (to [edge t3-y])))

(deftest ordered-and-labelled-clauses
  ;; ordered vector splices in order — :targets holds both elements as vars
  (is (= [#'t3-x #'t3-y] (:targets (first (:clauses t3-p)))))
  ;; [label target] form → :label key on the clause map
  (is (= "edge" (:label (first (:clauses t3-l)))))
  ;; and the single target is captured as a var
  (is (= [#'t3-y] (:targets (first (:clauses t3-l))))))

;; ── Task 4: ^:value structures — anonymous, content-identified ───────────────

(s/defstructure* ^:value Shp "a shape"
  (slot :kind (one :String))
  (slot :of (many Shp)))

(def t4-s1 (Shp (kind "leaf")))
(def t4-s2 (Shp (kind "leaf")))

(deftest value-structures-dedupe-by-content
  (is (:value? t4-s1))
  ;; equal content → equal computed id (the assembler will stamp :entity/id from this)
  (is (= (s/value-content-key t4-s1) (s/value-content-key t4-s2))))
