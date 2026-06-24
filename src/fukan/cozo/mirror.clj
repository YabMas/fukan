(ns fukan.cozo.mirror
  "Reflect a datascript db into a Cozo db as typed EAV stored relations — the
   migration's bridge. datascript stays the source of truth; every datom is
   mirrored into Cozo so laws and readers can be ported and checked against it
   (the `cozo == datascript` oracle).

   Datoms are partitioned by VALUE TYPE into three set-relations, each keyed on
   the whole `(e, a, v)` triple (a datom is unique by its triple):
     t_int  {e: Int, a: String, v: Int}
     t_str  {e: Int, a: String, v: String}   ; strings, stringified keywords,
                                              ; and pr-str'd compound/other values
     t_bool {e: Int, a: String, v: Bool}
   The attribute keyword is stored as a plain string with no leading colon
   (`:rel/from` → \"rel/from\"), matching how the ported CozoScript reads it.

   This namespace and its relations are TRANSITIONAL — deleted at cut-over (P5),
   when Cozo becomes the substrate of record rather than a mirror."
  (:require [datascript.core :as d]
            [fukan.cozo.db :as db]))

(defn- attr->str
  "A datom attribute keyword as a plain string, no leading colon: :rel/from →
   \"rel/from\". (`namespace`/`name` to keep the slash.)"
  [a]
  (subs (str a) 1))

(defn- classify
  "Bucket a datom value for the typed EAV mirror: returns `[bucket coerced-v]`
   where bucket is `:int`/`:str`/`:bool`. Keywords are stringified (no colon) and
   any other compound value is `pr-str`'d — both land in the `:str` bucket (the
   catch-all), so the mirror never drops a datom."
  [v]
  (cond
    (boolean? v) [:bool v]
    (integer? v) [:int v]
    (string? v)  [:str v]
    (keyword? v) [:str (subs (str v) 1)]
    :else        [:str (pr-str v)]))

(defn- datom->row
  "A datom as the `[bucket [e a v]]` it mirrors to."
  [datom]
  (let [[bucket v] (classify (:v datom))]
    [bucket [(:e datom) (attr->str (:a datom)) v]]))

(def relations
  "The three typed EAV relations, by bucket: stored name + Cozo value type."
  {:int  {:name "t_int"  :type "Int"}
   :str  {:name "t_str"  :type "String"}
   :bool {:name "t_bool" :type "Bool"}})

(defn rows-by-bucket
  "The datoms of `ds-db` grouped into `{:int #{[e a v]…} :str #{…} :bool #{…}}` —
   the expected mirror contents, computed on the datascript side (also the oracle's
   reference for the parity test)."
  [ds-db]
  (reduce (fn [acc datom]
            (let [[bucket row] (datom->row datom)]
              (update acc bucket conj row)))
          {:int #{} :str #{} :bool #{}}
          (d/datoms ds-db :eavt)))

(defn mirror
  "Open a fresh Cozo db and load every datom of `ds-db` into the typed EAV
   relations. Returns the open Cozo db (the caller closes it). Pure reflection —
   no schema interpretation."
  [ds-db]
  (let [buckets (rows-by-bucket ds-db)
        cdb     (db/open)]
    (doseq [[bucket {:keys [name type]}] relations]
      (db/q cdb (str ":create " name " {e: Int, a: String, v: " type "}"))
      (when-let [rows (seq (get buckets bucket))]
        (db/q cdb (str "?[e, a, v] <- $rows :put " name " {e, a, v}")
              {:rows (vec rows)})))
    cdb))
