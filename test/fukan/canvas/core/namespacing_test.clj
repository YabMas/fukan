(ns fukan.canvas.core.namespacing-test
  "Regression for ns-qualified structure tags. A structure's identity is its defining namespace +
   name (a qualified keyword mirroring its constructor var), so two structures that share a SHORT
   name but live in different namespaces COEXIST: distinct registry entries (neither silently
   overwrites the other and drops its laws) and distinct `:structure/of` on their instances.

   Before qualification this collided — the three collisions that drove the change were a `Type`
   test fixture vs the self-model, the `grammar` demo's `Grammar` vs the subject demo's, and
   `target.correspondence/Realization` vs the new subject seam (which silently disabled a law)."
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [lib.code :as code]))

;; a LOCAL structure named `Kind` — same short name as `lib.code/Kind`, different namespace
(defstructure Kind
  "Test fixture sharing the short name `Kind` with lib.code/Kind."
  (slot :note (optional :String)))

(def local-kind (Kind "local"))
(def lib-kind   (code/Kind "fromlib"))

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
             (set (map first (d/q '[:find ?n :where [?e :structure/of :lib.code/Kind] [?e :entity/name ?n]] db))))))))
