(ns fukan.canvas.projection.instance
  "The INSTANCE print-dual — `structure-form`'s sibling one stratum down: a model
   node renders back as the def-emitting form that authored it,

     (Tag name \"doc\"? {slot → value})

   so the model is readable at authoring altitude, not just queryable as datoms.
   The same parts feed three renders:
     `instance-form`    — the faithful DATA form (payload code-forms unquoted,
                          `^{:name …}` recovered when the entity name differs from
                          its var; anonymous values render as expression forms).
     `focus-text`       — a focus (datalog clauses or an eid set) rendered as its
                          authored forms — the textual model explorer.
     `violations-text`  — `check` output with each offender QUOTED as its form,
                          so the fix is visually adjacent to the law that fired.

   Pure projection: model db → form / string."
  (:require [clojure.string :as str]
            [datascript.core :as d]
            [fukan.canvas.core.lens :as lens]
            [fukan.canvas.core.structure :as s]
            [fukan.dialect.malli :as malli]))

;; ── parts: one node → its authoring ingredients ──────────────────────────────

(defn- rels-by-kind
  "The node's reified relations grouped by kind: {kind [{:to :label} …]} in
   :rel/order order (orderless relations sort stably by target id)."
  [db eid]
  (->> (d/q '[:find ?k ?to ?l ?o ?tid :in $ ?e
              :where [?r :rel/from ?e] [?r :rel/kind ?k] [?r :rel/to ?to]
                     [(get-else $ ?r :rel/label ::none) ?l]
                     [(get-else $ ?r :rel/order -1) ?o]
                     [(get-else $ ?to :entity/id "") ?tid]]
            db eid)
       (sort-by (fn [[_ _ _ o tid]] [o tid]))
       (reduce (fn [m [k to l _ _]]
                 (update m k (fnil conj []) {:to to :label (when (not= ::none l) l)}))
               {})))

(declare instance-form)

(defn- var-simple-name
  "The binding var's simple name recovered from a var-style `:entity/id`
   (\"ns/var-name\"), or nil for non-var ids (extracted / reflected / value nodes)."
  [e]
  (let [id (:entity/id e)]
    (when (and (string? id) (re-matches #"[^:#\s]+/[^:#/\s]+" id))
      (subs id (inc (str/last-index-of id "/"))))))

(defn- target-expr
  "A relation target → its authoring value expression: a Schema value → its malli
   literal (the dialect render IS the reader's dual); any other anonymous value →
   its inline expression form (recursive); a named entity → the VAR symbol the
   author referenced (falling back to the entity name for non-var nodes)."
  [db to]
  (let [e (d/entity db to)]
    (cond
      (= :lib.type.malli/Schema (:structure/of e)) (malli/render db to)
      (nil? (:entity/name e))                      (instance-form db to)
      :else (symbol (or (var-simple-name e) (:entity/name e))))))

(defn- element-expr [db {:keys [to label]}]
  (let [t (target-expr db to)]
    (if label [(symbol label) t] t)))

(defn- slot-entries
  "The node's {slot → value} map entries, declared slots first (in declaration
   order, schema-driven bare-vs-vector), then any relations/leaves the registry
   doesn't declare (reflected or extracted extras) — nothing the node carries is
   silently dropped."
  [db eid]
  (let [e      (d/entity db eid)
        sdef   (s/structure-by-tag (:structure/of e))
        rels   (rels-by-kind db eid)
        vals*  (into {} (filter #(= "val" (namespace (key %)))) (d/touch e))
        scalar-entry (fn [{:keys [rel payload]}]
                       (let [vk (keyword "val" (name rel))
                             pk (when payload (keyword "val" (name payload)))]
                         (when (contains? vals* vk)
                           {:entry [rel (if (and pk (contains? vals* pk))
                                          [(get vals* vk) (get vals* pk)]
                                          (get vals* vk))]
                            :consumed (cond-> #{vk} pk (conj pk))})))
        rel-entry    (fn [{:keys [rel card]}]
                       (when-let [es (get rels rel)]
                         {:entry [rel (let [xs (mapv #(element-expr db %) es)]
                                        (if (#{:many :some :set} card) xs (first xs)))]
                          :rel rel}))
        scalar?  s/scalar-slot?
        declared (keep (fn [slot]
                         (if (scalar? slot) (scalar-entry slot) (rel-entry slot)))
                       (:slots sdef))
        consumed-vals (into #{} (mapcat :consumed) declared)
        consumed-rels (into #{} (keep :rel) declared)
        extra-rels (for [[k es] (sort-by (comp str key) rels)
                         :when (not (consumed-rels k))]
                     [k (let [xs (mapv #(element-expr db %) es)]
                          (if (= 1 (count xs)) (first xs) xs))])
        extra-vals (for [[k v] (sort-by (comp str key) vals*)
                         :when (not (consumed-vals k))]
                     [(keyword (name k)) v])]
    (concat (map :entry declared) extra-rels extra-vals)))

(defn- name-sym
  "The name-position symbol: the binding var's simple name (recovered from the
   var-id), carrying `^{:name …}` metadata when the entity name differs — the
   print-dual of the authoring override. Non-var ids (extracted/reflected nodes)
   render their entity name plainly."
  [db eid]
  (let [e    (d/entity db eid)
        nm   (:entity/name e)
        var* (var-simple-name e)]
    (if (and var* (not= var* nm))
      (with-meta (symbol var*) {:name nm})
      (symbol nm))))

;; ── the data form (the authored surface, recovered) ──────────────────────────

(defn instance-form
  "The node at `eid` rendered back as its authored instance form — the def-emitting
   `(Tag name \"doc\"? {slot → value})` for a named entity, the anonymous expression
   `(Tag \"doc\"? {…})` for a value/unnamed node. The print-dual of the instance
   surface: what `(grammar)` is to the language, this is to the model."
  [db eid]
  (let [e       (d/entity db eid)
        tag     (:structure/of e)
        entries (slot-entries db eid)]
    (concat [(symbol (name tag))]
            (when (:entity/name e) [(name-sym db eid)])
            (when (:entity/doc e) [(:entity/doc e)])
            (when (seq entries) [(apply array-map (mapcat identity entries))]))))

;; ── the text renders ──────────────────────────────────────────────────────────

(defn- fmt-meta-sym [sym]
  (if-let [nm (:name (meta sym))] (str "^{:name " (pr-str nm) "} " sym) (str sym)))

(defn- fmt-value
  "A slot value, wrapping a long vector one element per line under its key
   (indented to `col`); everything else prints flat."
  [v col]
  (let [flat (pr-str v)]
    (if (and (vector? v) (> (count flat) 60) (> (count v) 1))
      (str "[" (str/join (str "\n" (apply str (repeat (inc col) " ")))
                         (map pr-str v)) "]")
      flat)))

(defn instance-text
  "`instance-form`, formatted like the authored source: head line (tag, name,
   doc), then the slot map with aligned keys."
  [db eid]
  (let [[tag & more] (instance-form db eid)
        [nm more]    (if (symbol? (first more)) [(first more) (rest more)] [nil more])
        [doc m]      (if (string? (first more)) [(first more) (second more)] [nil (first more)])
        head         (str "(" tag (when nm (str " " (fmt-meta-sym nm))))
        w            (when (seq m) (apply max (map (comp count str key) m)))
        body         (when (seq m)
                       (str "  {"
                            (str/join "\n   "
                                      (map (fn [[k v]]
                                             (str/trimr (format (str "%-" w "s %s") (str k)
                                                                (fmt-value v (+ 4 w)))))
                                           m))
                            "}"))]
    (str (->> (concat [head]
                      (when doc [(str "  " (pr-str doc))])
                      (when body [body]))
              (str/join "\n"))
         ")")))

(defn focus-text
  "A FOCUS rendered as its authored forms — the textual model explorer. `focus` is
   either datalog `:where` clauses (binding `?n`; evaluated with the vocab rules)
   or a collection of eids. Forms sort by (tag, name)."
  [db focus]
  (let [eids (if (and (coll? focus) (every? number? focus)) focus (lens/focus-nodes db focus))
        sorted (sort-by (fn [eid] (let [e (d/entity db eid)]
                                    [(str (:structure/of e)) (or (:entity/name e) "")]))
                        eids)]
    (str/join "\n\n" (map #(instance-text db %) sorted))))

;; ── violations, quoting the offending forms ───────────────────────────────────

(defn- offender-text [db x]
  (if (and (number? x) (:structure/of (d/entity db x)))
    (instance-text db x)
    (pr-str x)))

(defn violations-text
  "`check` output rendered with each offender QUOTED as its authored form — the
   law that fired and the instance that fired it, side by side."
  [db violations]
  (if (empty? violations)
    "No violations — every law holds."
    (str/join "\n\n"
              (for [{:keys [structure law offenders timed-out? message]} violations]
                (if timed-out?
                  (str "✗ " law "  [" (name structure) "]\n  " message)
                  (str "✗ " law "  [" (name structure) "]\n"
                       (str/join "\n"
                                 (for [row offenders x row]
                                   (str/replace (offender-text db x) #"(?m)^" "  ")))))))))
