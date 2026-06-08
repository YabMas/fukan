(ns fukan.dialect.malli-vocab-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            ;; SchemaField/SchemaChoice are referred (though only Schema is called
            ;; directly) so clj-kondo resolves their instance-macro hooks.
            [canvas.dialects.malli :refer [Schema SchemaField SchemaChoice]]))

;; A thin holder lets us exercise reader-expansion (Schema has a reader, so
;; when it's a slot target, native malli literals are expanded at macroexpand time).
(defstructure SchemaHolder
  "Test fixture: a named node that carries one Schema for reader-expansion tests."
  (slot :schema (one Schema)))

;; ── reader-expanded instances (native malli literals auto-expanded via read-malli) ──

(def port  (SchemaHolder "port"  (schema [:int {:min 1 :max 65535}])))
(def email (SchemaHolder "email" (schema [:string {:re "@"}])))
(def tags  (SchemaHolder "tags"  (schema [:vector :keyword])))
(def addr  (SchemaHolder "addr"  (schema [:map [:street :string] [:zip {:optional true} :string]])))
(def color (SchemaHolder "color" (schema [:enum :red :green :blue])))
;; Socket is a reader literal (a symbol passed to read-malli → :ref "Socket"), not a var.
;; Declare it to suppress the clj-kondo unresolved-symbol false positive.
(declare Socket)
(def ref-k (SchemaHolder "ref-k" (schema Socket)))  ; bare symbol -> :ref schema naming "Socket"
(def combo (SchemaHolder "combo" (schema [:or :int :string])))
(def tup   (SchemaHolder "tup"   (schema [:tuple :int :int :string])))

(defn- build []
  (a/assemble-vars [#'port #'email #'tags #'addr #'color #'ref-k #'combo #'tup]))

;; A ref Schema built INLINE (an `(Schema …)` seq bypasses the reader, so no :to
;; is produced) — exercises the "ref must name a target" law.
(def bad (SchemaHolder "bad" (schema (Schema (kind "ref")))))

(deftest schemas-are-valid
  (is (empty? (s/check (build))) "no law violations"))

(deftest scalar-constraints-are-datoms
  (let [db (build)]
    (testing "int min/max land as queryable leaves"
      (is (= #{[1 65535]}
             (set (d/q '[:find ?min ?max :where
                         [?s :val/kind "int"] [?s :val/min ?min] [?s :val/max ?max]]
                       db)))))
    (testing "the > 60000 query from the spec works"
      (is (= #{[65535]}
             (set (d/q '[:find ?max :where
                         [?s :val/kind "int"] [?s :val/max ?max] [(> ?max 60000)]]
                       db)))))
    (testing "regex is a leaf"
      (is (= #{["@"]}
             (set (d/q '[:find ?re :where [?s :val/kind "string"] [?s :val/regex ?re]] db)))))))

(deftest collection-element-is-a-ref
  (let [db (build)]
    (is (= #{["keyword"]}
           (set (d/q '[:find ?ek :where
                       [?s :val/kind "vector"]
                       [?r :rel/from ?s] [?r :rel/kind :of] [?r :rel/to ?e]
                       [?e :val/kind ?ek]]
                     db))))))

(deftest map-fields-are-queryable
  (let [db (build)]
    (testing "every field key on the addr map"
      (is (= #{["street"] ["zip"]}
             (set (d/q '[:find ?k :where
                         [?s :val/kind "map"]
                         [?r :rel/from ?s] [?r :rel/kind :field] [?r :rel/to ?f]
                         [?f :val/key ?k]]
                       db)))))
    (testing "the optional flag is on the field"
      (is (= #{["zip" true]}
             (set (d/q '[:find ?k ?opt :where
                         [?f :val/key ?k] [?f :val/optional ?opt] [(= ?opt true)]]
                       db)))))))

(deftest enum-choices-are-queryable
  (let [db (build)]
    (is (= #{["red"] ["green"] ["blue"]}
           (set (d/q '[:find ?c :where
                       [?s :val/kind "enum"]
                       [?r :rel/from ?s] [?r :rel/kind :choice] [?r :rel/to ?ch]
                       [?ch :val/value ?c]]
                     db))))))

(deftest bare-symbol-is-a-ref
  (let [db (build)]
    (is (= #{["Socket"]}
           (set (d/q '[:find ?to :where [?s :val/kind "ref"] [?s :val/to ?to]] db))))))

(deftest or-alternatives-are-ordered-children
  (let [db (build)]
    (is (= #{"int" "string"}
           (set (map first
                     (d/q '[:find ?k :where
                            [?s :val/kind "or"]
                            [?r :rel/from ?s] [?r :rel/kind :of] [?r :rel/to ?alt]
                            [?alt :val/kind ?k]]
                          db)))))))

(deftest tuple-children-are-ordered-and-not-collapsed
  ;; [:tuple :int :int :string] — order must be preserved (:of is ordered) AND the
  ;; repeated :int must NOT content-collapse: there must be 3 children, not 2.
  (let [db   (build)
        rows (d/q '[:find ?ord ?k :where
                    [?t :val/kind "tuple"]
                    [?r :rel/from ?t] [?r :rel/kind :of] [?r :rel/to ?c]
                    [?r :rel/order ?ord] [?c :val/kind ?k]]
                  db)]
    (is (= ["int" "int" "string"] (map second (sort-by first rows))))))

(deftest ref-without-target-violates-law
  ;; An inline (Schema (kind "ref")) bypasses the reader, so no :to is produced;
  ;; the "ref must name a target" law must fire.
  (let [db (a/assemble-vars [#'bad])]
    (is (seq (s/check db)) "ref schema lacking :to is caught by check")))
