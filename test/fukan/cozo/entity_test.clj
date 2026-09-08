(ns fukan.cozo.entity-test
  "`entity` — the eid → attribute-map read every offender render makes four times over.

   Two properties, and they are the same property twice: the eid belongs in the atom's KEY
   position. Put there it is a seek; left free and filtered it is a scan of the whole bucket. And
   because a bare NAME in key position is a VARIABLE to Cozo rather than a bad constant, an eid
   that is not an Int must be refused here — matching every row is not an error Cozo can raise."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :refer [defstructure]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]))

(defstructure EThing "Fixture: a named node with one leaf." {:size :int})

(EThing one {:size 1})
(EThing two {:size 2})

(defn- db [] (build/vars->cozo [#'one #'two]))

(deftest resolves-an-eid-to-its-typed-attributes
  (testing "values come back in their real types, from whichever bucket holds them"
    (let [d   (db)
          eid (ffirst (cq/q '[:find ?e :where [?e :entity/name "one"]] d))
          e   (cq/entity d eid)]
      (is (= "one" (:entity/name e)))
      (is (= 1 (:val/size e)) "the Int leaf keeps its type")
      (is (= eid (:db/id e)) "the handle comes back as the native Int it went in as"))))

(deftest an-unknown-eid-answers-nil
  (is (nil? (cq/entity (db) 999999))))

(deftest resolves-a-lookup-ref
  (let [d (db)]
    (is (= "two" (:entity/name (cq/entity d [:entity/name "two"]))))))

(deftest refuses-an-eid-that-is-not-an-int
  (testing "a law may bind any var as an offender, and the useful one is sometimes a bare NAME —
            a type-reference that resolved to no Kind. Lowered into key position that name is a
            VARIABLE: the atom matches every row and the caller is handed the whole db as one
            entity. Refusing is the only way that read can fail."
    (doseq [bad ["Clause" :Clause 'Clause]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Int eid"
                            (cq/entity (db) bad))
          (str "entity must refuse " (pr-str bad))))))
