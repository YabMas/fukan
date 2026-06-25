(ns fukan.cozo.mirror
  "The Cozo substrate WRITE layer — `[e a v]` triples → typed EAV stored relations. Datoms are
   partitioned by VALUE TYPE into three set-relations, each keyed on the whole `(e, a, v)` triple
   (a datom is unique by its triple):
     t_int  {e: Int, a: String, v: Int}
     t_str  {e: Int, a: String, v: String}   ; strings, stringified keywords,
                                              ; and pr-str'd compound/other values
     t_bool {e: Int, a: String, v: Bool}
   The attribute keyword is stored as a plain string with no leading colon
   (`:rel/from` → \"rel/from\"), matching how the CozoScript reads it. (Named `mirror` from when
   it reflected a datascript db; now the native build's only write path — renamed in the -cozo sweep.)"
  (:require [fukan.cozo.db :as db]))

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

(defn- triple->row
  "An `[e a v]` triple (a = attribute keyword) as the `[bucket [e a-str v]]` it
   loads to."
  [[e a v]]
  (let [[bucket cv] (classify v)]
    [bucket [e (attr->str a) cv]]))

(def relations
  "The three typed EAV relations, by bucket: stored name + Cozo value type."
  {:int  {:name "t_int"  :type "Int"}
   :str  {:name "t_str"  :type "String"}
   :bool {:name "t_bool" :type "Bool"}})

(defn- datoms->buckets
  "Group `[e a v]` triples into `{:int #{[e a v]…} :str #{…} :bool #{…}}` by value
   type — the engine-neutral partition both build paths share."
  [triples]
  (reduce (fn [acc t]
            (let [[bucket row] (triple->row t)]
              (update acc bucket conj row)))
          {:int #{} :str #{} :bool #{}}
          triples))

(defn load-datoms
  "Open a fresh Cozo db and load the `[e a v]` triples into the typed EAV relations;
   returns the open db (caller closes). The substrate WRITE the native build assembles into."
  [triples]
  (let [buckets (datoms->buckets triples)
        cdb     (db/open)]
    (doseq [[bucket {:keys [name type]}] relations]
      (db/q cdb (str ":create " name " {e: Int, a: String, v: " type "}"))
      (when-let [rows (seq (get buckets bucket))]
        (db/q cdb (str "?[e, a, v] <- $rows :put " name " {e, a, v}")
              {:rows (vec rows)})))
    cdb))

(defn insert-datoms
  "INSERT `[e a v]` triples into an ALREADY-OPEN Cozo db (`:put`, not `:create`) — the additive
   analog of `load-datoms`, for grounding extra datoms (the native grammar reflection) onto an
   existing substrate. Returns `cdb`."
  [cdb triples]
  (let [buckets (datoms->buckets triples)]
    (doseq [[bucket {:keys [name]}] relations]
      (when-let [rows (seq (get buckets bucket))]
        (db/q cdb (str "?[e, a, v] <- $rows :put " name " {e, a, v}") {:rows (vec rows)}))))
  cdb)
