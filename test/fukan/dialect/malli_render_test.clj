(ns fukan.dialect.malli-render-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.model.typing :as typing]
            ;; fukan.dialect.malli referred for its render fn (registered per-test below)
            [fukan.dialect.malli :as malli]
            ;; Schema referred so clj-kondo resolves its instance-macro hook
            [canvas.dialects.malli :refer [Schema]]
            ;; Kind — the named type a `ref` schema points at via :names.
            [canvas.materialize.vocab :refer [Kind]]))

(use-fixtures :each
  (fn [t] (typing/register-type-dialect! {:render malli/render}) (t)))

(defstructure RenderHolder
  "Test fixture: carries one Schema so the reader expands native malli literals."
  (slot :schema (one Schema)))

(def Socket (Kind "Socket"))

(def h-port  (RenderHolder "h-port"  (schema [:int {:min 1 :max 65535}])))
(def h-addr  (RenderHolder "h-addr"  (schema [:map [:street :string] [:zip {:optional true} :string]])))
(def h-color (RenderHolder "h-color" (schema [:enum :red :green :blue])))
(def h-tags  (RenderHolder "h-tags"  (schema [:vector :keyword])))
(def h-kw    (RenderHolder "h-kw"    (schema :keyword)))
(def h-ref   (RenderHolder "h-ref"   (schema Socket)))
(def h-or    (RenderHolder "h-or"    (schema [:or :int :string])))
(def h-tup   (RenderHolder "h-tup"   (schema [:tuple :string :int :keyword])))

(defn- db [] (a/assemble-vars [#'Socket #'h-port #'h-addr #'h-color #'h-tags #'h-kw #'h-ref #'h-or #'h-tup]))
(defn- root [db kind] (ffirst (d/q '[:find ?s :in $ ?k :where [?s :val/kind ?k]] db kind)))

(deftest renders-scalar-with-constraints
  (let [d*  (db)
        ;; pick the constrained int specifically — h-or contributes a bare :int
        eid (ffirst (d/q '[:find ?s :where [?s :val/kind "int"] [?s :val/min _]] d*))]
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
