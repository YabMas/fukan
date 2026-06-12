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
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [lib.code :as code]))

;; a LOCAL structure named `Kind` — same short name as `lib.code/Kind`, different namespace —
;; carrying a free law that flags ALL its instances (to probe scope precision)
(defstructure Kind
  "Test fixture sharing the short name `Kind` with lib.code/Kind."
  {:note [:? :String]}
  (law "local-kind-flag" :offenders '[?k] :where '[]))

(Kind ^{:name "local"} local-kind)
(code/Kind ^{:name "fromlib"} lib-kind)

(deftest same-short-name-different-ns-coexist
  (testing "two `Kind`s from different namespaces keep distinct identities and instances"
    ;; the registry keeps BOTH defs under their qualified tags — neither overwrites the other
    (is (= ::Kind          (:tag (s/structure-by-tag ::Kind))))
    (is (= :lib.code/Kind  (:tag (s/structure-by-tag :lib.code/Kind))))
    (is (not= (s/structure-by-tag ::Kind) (s/structure-by-tag :lib.code/Kind))
        "distinct definitions (the local Kind has a :note slot; lib.code/Kind has none)")
    ;; co-loaded in ONE db, instances carry distinct :structure/of and are separately queryable
    (let [db (a/assemble-vars [#'local-kind #'lib-kind])]
      (is (= #{"local"}
             (set (map first (d/q '[:find ?n :where [?e :structure/of ::Kind] [?e :entity/name ?n]] db)))))
      (is (= #{"fromlib"}
             (set (map first (d/q '[:find ?n :where [?e :structure/of :lib.code/Kind] [?e :entity/name ?n]] db)))))))

(deftest law-scope-is-ns-precise
  (testing "a free law self-scoped to the local Kind flags only ::Kind instances — not lib.code/Kind"
    (let [db      (a/assemble-vars [#'local-kind #'lib-kind])
          flagged (->> (s/check db)
                       (filter #(= "local-kind-flag" (:law %)))
                       (mapcat :offenders)
                       (map (comp :entity/name #(d/entity db %) first))
                       set)]
      (is (contains? flagged "local") "the local law fires on its own ::Kind instance")
      (is (not (contains? flagged "fromlib"))
          "and NOT on lib.code/Kind — ns-precise scoping (pre-fix the shared short name cross-scoped)")))))
