(ns fukan.canvas.core.namespacing-test
  "Regression for ns-qualified structure tags. A structure's identity is its defining namespace +
   name (a qualified keyword mirroring its constructor var), so two structures that share a SHORT
   name but live in different namespaces COEXIST: distinct registry entries (neither silently
   overwrites the other and drops its laws) and distinct `:structure/of` on their instances.

   Before qualification this collided — the three collisions that drove the change were a `Type`
   test fixture vs the self-model, the `grammar` demo's `Grammar` vs the subject demo's, and
   `target.correspondence/Realization` vs the new subject seam (which silently disabled a law).

   LAW SCOPING is ns-precise too: a free law self-scopes to its structure via `[?o :structure/of
   <qualified-tag>]`, so a law on one `Kind` never ranges over the other's instances (the edge that
   surfaced when the subject re-grammar re-stated `Projection` at two altitudes)."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.query :as cq]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.common.vocab.code.kind :as code]))

;; a LOCAL structure named `Kind` — same short name as `fukan.common.vocab.code.kind/Kind`, different namespace —
;; carrying a free law that flags ALL its instances (to probe scope precision)
(defstructure Kind
  "Test fixture sharing the short name `Kind` with fukan.common.vocab.code.kind/Kind."
  {:note [:? :string]}
  (law "local-kind-flag" {:offenders [?k] :where []}))

(Kind ^{:name "local"} local-kind)
(code/Kind ^{:name "fromlib"} lib-kind)

;; a GLOBAL law whose sort guard is `(is ?k Kind)` — declaration-site resolution picks THIS
;; namespace's Kind (the var), so the law reads at domain altitude yet stays ns-precise even
;; under :scope :global. The bare rule call `(Kind ?k)` remains the deliberate co-load union.
(defstructure KindAudit
  "Fixture: a global law over the ns-precisely pinned local Kind."
  (law "is-pins-local-kind"
    {:scope :global
     :offenders [?k]
     :where [(is ?k Kind)]}))

(deftest same-short-name-different-ns-coexist
  (testing "two `Kind`s from different namespaces keep distinct identities and instances"
    ;; the registry keeps BOTH defs under their qualified tags — neither overwrites the other
    (is (= ::Kind          (:tag (s/structure-by-tag ::Kind))))
    (is (= :fukan.common.vocab.code.kind/Kind  (:tag (s/structure-by-tag :fukan.common.vocab.code.kind/Kind))))
    (is (not= (s/structure-by-tag ::Kind) (s/structure-by-tag :fukan.common.vocab.code.kind/Kind))
        "distinct definitions (the local Kind has a :note slot; fukan.common.vocab.code.kind/Kind has none)")
    ;; co-loaded in ONE db, instances carry distinct :structure/of and are separately queryable
    (let [db (build/vars->cozo [#'local-kind #'lib-kind])]
      (is (= #{"local"}
             (set (map first (cq/q '[:find ?n :where [?e :structure/of ::Kind] [?e :entity/name ?n]] db)))))
      (is (= #{"fromlib"}
             (set (map first (cq/q '[:find ?n :where [?e :structure/of :fukan.common.vocab.code.kind/Kind] [?e :entity/name ?n]] db)))))))

(deftest law-scope-is-ns-precise
  (testing "a free law self-scoped to the local Kind flags only ::Kind instances — not fukan.common.vocab.code.kind/Kind"
    (let [db      (build/vars->cozo [#'local-kind #'lib-kind])
          flagged (->> (law/check db)
                       (filter #(= "local-kind-flag" (:law %)))
                       (mapcat :offenders)
                       (map (comp :entity/name #(cq/entity db %) first))
                       set)]
      (is (contains? flagged "local") "the local law fires on its own ::Kind instance")
      (is (not (contains? flagged "fromlib"))
          "and NOT on fukan.common.vocab.code.kind/Kind — ns-precise scoping (pre-fix the shared short name cross-scoped)")))))

(deftest is-pins-a-sort-ns-precisely-where-the-rule-call-unions
  (testing "(is ?k Kind) resolved the LOCAL Kind at declaration; the bare rule call is the union"
    (let [db      (build/vars->cozo [#'local-kind #'lib-kind])
          flagged (->> (law/check db)
                       (filter #(= "is-pins-local-kind" (:law %)))
                       (mapcat :offenders)
                       (map (comp :entity/name #(cq/entity db %) first))
                       set)]
      (is (= #{"local"} flagged)
          "despite :scope :global, (is ?k Kind) ranges over ::Kind only — not the co-loaded lib Kind")
      (is (= #{"local" "fromlib"}
             (set (map first (cq/q '[:find ?n :in $ % :where (Kind ?k) [?k :entity/name ?n]]
                                   db (s/vocab-rules)))))
          "the short kind rule stays the deliberate co-load union — both Kinds"))))

(deftest relation-name-collisions-are-loud
  (testing "a relation element's UNQUALIFIED tag is global presentation identity: re-declaring the same
            relation from a DIFFERENT namespace throws at registration (the registry keys by tag,
            so it would otherwise silently replace the first declaration — and every law over the
            name would read only the survivor); same-ns re-registration (a REPL reload) replaces"
    (let [decl {:tag :nt-collide :doc "d" :ns "vocab.a" :slots [] :laws []
                :derived-rule {:head '[?x] :bodies ['[[?x :structure/of :vocab.a/T]]]}}]
      (is (= :nt-collide (s/register-structure! decl)))
      (is (= :nt-collide (s/register-structure! (assoc decl :doc "reloaded")))
          "the declaring namespace may re-register (REPL reload)")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already declared by vocab\.a"
                            (s/register-structure! (assoc decl :ns "vocab.b")))
          "a second namespace claiming the name is a collision, caught at declaration"))))
