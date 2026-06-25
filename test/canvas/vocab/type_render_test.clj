(ns canvas.vocab.type-render-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [fukan.cozo.query :as cq]
            [fukan.cozo.build :as build]
            [fukan.cozo.law]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.canvas.core.typing :as typing]
            ;; canvas.vocab.type: its render fn (registered per-test below) + Schema (the clj-kondo hook)
            [canvas.vocab.type :as malli :refer [Schema]]
            ;; Kind — the named type a `ref` schema points at via :names.
            [canvas.vocab.code.kind :refer [Kind]]))

(use-fixtures :each
  (fn [t] (typing/register-type-dialect! {:render malli/render}) (t)))

(defstructure RenderHolder
  "Test fixture: carries one Schema so the reader expands native malli literals."
  {:schema Schema})

(Kind Socket)

(RenderHolder h-port  {:schema [:int {:min 1 :max 65535}]})
(RenderHolder h-addr  {:schema [:map [:street :string] [:zip {:optional true} :string]]})
(RenderHolder h-color {:schema [:enum :red :green :blue]})
(RenderHolder h-tags  {:schema [:vector :keyword]})
(RenderHolder h-kw    {:schema :keyword})
(RenderHolder h-ref   {:schema Socket})
(RenderHolder h-or    {:schema [:or :int :string]})
(RenderHolder h-tup   {:schema [:tuple :string :int :keyword]})

(defn- db [] (build/vars->cozo [#'Socket #'h-port #'h-addr #'h-color #'h-tags #'h-kw #'h-ref #'h-or #'h-tup]))
(defn- root [db kind] (ffirst (cq/q '[:find ?s :in $ ?k :where [?s :val/kind ?k]] db kind)))

(deftest renders-scalar-with-constraints
  (let [d*  (db)
        ;; pick the constrained int specifically — h-or contributes a bare :int
        eid (ffirst (cq/q '[:find ?s :where [?s :val/kind "int"] [?s :val/min _]] d*))]
    (is (= [:int {:min 1 :max 65535}] (typing/render-type d* eid)))))

(deftest renders-collection
  (let [d* (db)]
    (is (= [:vector :keyword] (typing/render-type d* (root d* "vector"))))))

(deftest renders-map-with-optional
  (let [d*   (db)
        form (typing/render-type d* (root d* "map"))]
    (testing "map renders to a :map head"
      (is (= :map (first form))))
    (testing "fields (an unordered `many` slot) compared as a set"
      (is (= #{[:street :string] [:zip {:optional true} :string]} (set (rest form)))))))

(deftest renders-enum
  (let [d*   (db)
        form (typing/render-type d* (root d* "enum"))]
    (testing "enum renders to an :enum head"
      (is (= :enum (first form))))
    (testing "choices (an unordered `many` slot) compared as a set"
      (is (= #{:red :green :blue} (set (rest form)))))))

(RenderHolder h-kw-x  {:schema [:enum :x]})
(RenderHolder h-str-x {:schema [:enum "x"]})

(deftest enum-member-type-is-stored-and-round-trips
  (let [d* (build/vars->cozo [#'h-kw-x #'h-str-x])
        of-holder (fn [n] (ffirst (cq/q '[:find ?s :in $ ?n
                                         :where [?h :entity/name ?n]
                                                [?r :rel/from ?h] [?r :rel/kind :schema] [?r :rel/to ?s]]
                                       d* n)))]
    (testing "keyword and string members render back as authored"
      (is (= [:enum :x]  (typing/render-type d* (of-holder "h-kw-x"))))
      (is (= [:enum "x"] (typing/render-type d* (of-holder "h-str-x")))))
    (testing "[:enum :x] and [:enum \"x\"] are DISTINCT values — member type enters identity"
      (is (not= (of-holder "h-kw-x") (of-holder "h-str-x"))))))

(deftest scalar-without-constraints-renders-bare
  (let [d* (db)]
    (is (= :keyword (typing/render-type d* (root d* "keyword"))))))

(deftest renders-ref
  (let [d* (db)]
    (is (= :Socket (typing/render-type d* (root d* "ref"))))))

(deftest renders-or
  (let [d* (db)]
    (is (= [:or :int :string] (typing/render-type d* (root d* "or"))))))

(deftest renders-tuple
  ;; :of is ordered and now deterministic — the tuple must round-trip EXACTLY,
  ;; element order preserved (a buggy reader scrambled/collapsed these).
  (let [d* (db)]
    (is (= [:tuple :string :int :keyword] (typing/render-type d* (root d* "tuple"))))))
