(ns fukan.canvas.projection.grammar
  "The PRINT-DUAL of the authoring surface: a reified `Structure` node renders back
   as its map-form `defstructure`. The reader/syntax hooks compress authoring INTO
   the graph; this projection decompresses the graph back into the same concise
   surface — so the grammar primer is live, derived, and can never drift from the
   registry it reflects.

   Two renders from the same parts:
     `structure-form`    — the faithful DATA form (laws carry their datalog,
                           unquoted; `^:value` rides the name symbol's metadata).
     `grammar-primer`    — the reference-card STRING: every Vocabulary in the
                           model, each structure with aligned slots, first doc
                           line, law descs (datalog elided as `…`).

   Pure projection: model db → form / string."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.typing :as typing]))

;; ── parts: one reified Structure → its authoring ingredients ─────────────────

(def ^:private malli->scalar {:string :string, :int :int, :boolean :boolean})

(defn- ord
  "A `:rel/order` cell as a long for sorting — `cq/q` returns strings over Cozo, ints over datascript."
  [o] (cond (nil? o) -1, (string? o) (Long/parseLong o), :else (long o)))

(defn- payload-form
  "A `:val/form` / payload value as its CODE-FORM — pr-str'd to a string in the Cozo mirror, read back."
  [v] (cond-> v (string? v) edn/read-string))

(defn- struct-tag
  "A node's `:structure/of` as a keyword — the Cozo mirror stringifies it (no colon); idempotent on
   the datascript keyword."
  [e] (some-> (:structure/of e) keyword))

(defn- target-expr
  "A slot edge's target → its authoring type expression: a reified Structure → its
   name symbol; a reflected type value → its code-form via the dialect render through the
   plug-point (faithful — enum member types are stored); bare scalars map back to
   :string/:int/:boolean."
  [db t]
  (let [e (cq/entity db t)]
    (if (= (typing/dialect-type-tag) (struct-tag e))
      (let [f (typing/render-type db t)]
        (get malli->scalar f f))
      (symbol (:entity/name e)))))

(defn- slot-expr
  "Card + optional props (payload) + target → the slot's type expression. Props
   take malli's position after a quantifier; a props-only one-card leads with the
   props map."
  [kind props target]
  (let [quantifier ({:slot/optional :?, :slot/many :*, :slot/some :+, :slot/set :set} kind)]
    (cond
      quantifier (if props [quantifier props target] [quantifier target])
      props      [props target]
      :else      target)))

(defn- slots-of [db s]
  ;; the optional :rel/payload is read separately and merged (no get-else on Cozo); :rel/kind is a
  ;; keyword the mirror stringifies, so re-keywordize it for the slot/* filter + the quantifier map
  (let [base (cq/q '[:find ?r ?k ?l ?o ?t :in $ ?s
                     :where [?r :rel/from ?s] [?r :rel/kind ?k] [?r :rel/label ?l]
                            [?r :rel/order ?o] [?r :rel/to ?t]] db s)
        pays (into {} (cq/q '[:find ?r ?p :in $ ?s
                              :where [?r :rel/from ?s] [?r :rel/payload ?p]] db s))]
    (->> base
         (filter #(= "slot" (namespace (keyword (nth % 1)))))
         (sort-by #(ord (nth % 3)))
         (mapv (fn [[r k l _ t]]
                 (let [props (when-let [p (pays r)] {:payload (keyword p)})]
                   [(keyword l) (slot-expr (keyword k) props (target-expr db t))]))))))

(defn- laws-of [db s]
  (->> (cq/q '[:find ?l ?id :in $ ?s
               :where [?r :rel/from ?s] [?r :rel/kind :law] [?r :rel/to ?l]
                      [?l :entity/id ?id]] db s)
       (sort-by second)
       (mapv (fn [[l _]]
               (let [e (cq/entity db l)]
                 (merge {:desc (:val/desc e)
                         :scope (some-> (:val/scope e) edn/read-string)}
                        (payload-form (:val/form e))))))))

(defn- includes-of [db s]
  (->> (cq/q '[:find [?n ...] :in $ ?s
               :where [?r :rel/from ?s] [?r :rel/kind :includes] [?r :rel/to ?t]
                      [?t :entity/name ?n]] db s)
       sort (mapv symbol)))

(defn- parts [db s]
  (let [e (cq/entity db s)]
    {:name     (symbol (:entity/name e))
     :doc      (:entity/doc e)
     :value?   (boolean (:val/value e))
     :realizes (when (:val/realizes e) (payload-form (:val/form e)))
     :slots    (slots-of db s)
     :includes (includes-of db s)
     :laws     (laws-of db s)}))

;; ── the data form (round-trip) ────────────────────────────────────────────────

(defn- law-form [{:keys [desc scope rules offenders where src]}]
  (if src
    (list 'law desc src)   ; authored through a combinator — render the combinator back
    (concat ['law desc]
            (when scope [:scope scope])
            (when rules [:rules rules])
            [:offenders offenders :where where])))

(defn structure-form
  "The reified Structure at `eid` rendered back as its `defstructure` data form —
   the print-dual of the authoring surface. Laws carry their datalog unquoted
   (this is the PARSED form); `^:value` rides the name symbol's metadata."
  [db eid]
  (let [{:keys [name doc value? slots includes realizes laws]} (parts db eid)]
    (concat ['defstructure (if value? (with-meta name {:value true}) name)]
            (when doc [doc])
            (when (seq slots) [(apply array-map (mapcat identity slots))])
            (when (seq includes) [(cons 'includes includes)])
            (when realizes [(list 'realized-as realizes)])
            (map law-form laws))))

;; ── the primer (reference-card string) ───────────────────────────────────────

(defn- first-line [doc]
  (let [[l & more] (str/split-lines doc)
        l (str/trim l)]
    (if (seq more) (str l " …") l)))

(defn- fmt-slots [slots]
  (let [w (apply max (map (comp count str first) slots))]
    (str "  {"
         (str/join "\n   "
                   (map (fn [[k v]]
                          (str/trimr (format (str "%-" w "s %s") (str k) (pr-str v))))
                        slots))
         "}")))

(defn- fmt-structure [db s]
  (let [{:keys [name doc value? slots includes realizes laws]} (parts db s)]
    (->> (concat
          [(str "(defstructure " (when value? "^:value ") name)]
          (when doc [(str "  " (pr-str (first-line doc)))])
          (when (seq slots) [(fmt-slots slots)])
          (when (seq includes) [(str "  (includes " (str/join " " includes) ")")])
          (when realizes [(str "  (realized-as '" (pr-str realizes) ")")])
          (map #(str "  (law " (pr-str (:desc %)) " …)") laws))
         (str/join "\n")
         (#(str % ")")))))

(defn vocabulary-primer
  "One vocabulary (a grammar namespace) rendered as its defstructure forms."
  [db vocab-name]
  (let [members (->> (cq/q '[:find ?c ?n :in $ ?vn
                             :where [?v :structure/of :canvas.vocab.grammar/Vocabulary]
                                    [?v :entity/name ?vn]
                                    [?r :rel/from ?v] [?r :rel/kind :child] [?r :rel/to ?c]
                                    [?c :entity/name ?n]]
                           db vocab-name)
                     (sort-by second)
                     (map first))]
    (str/join "\n"
              (concat [(str "━━ " vocab-name " — " (count members) " structures ━━") ""]
                      (interpose "" (map #(fmt-structure db %) members))))))

(defn grammar-primer
  "The full GRAMMAR PRIMER: every vocabulary in the model, rendered live from the
   reified grammar — the canvas's language reference, derived not maintained."
  [db]
  (let [vocabs (sort (cq/q '[:find [?n ...]
                             :where [?v :structure/of :canvas.vocab.grammar/Vocabulary]
                                    [?v :entity/name ?n]] db))]
    (str/join "\n\n" (map #(vocabulary-primer db %) vocabs))))

;; ── grammar drift (the dead-vocabulary reading) ───────────────────────────────

(defn ^{:malli/schema [:=> [:cat :StructureDb] [:vector :string]]} unused-structures
  "The grammar-drift reading: reified Structures no instance inhabits — dead
   vocabulary. Excludes the Any wildcard and derivation-inhabited concepts:
   realized-as, and facets reached via includes. Sorted structure names. (A
   reading to reason with, not a gate — law-hosts and not-yet-spoken grammar are
   legitimate; the human interprets.)"
  [db]
  ;; set-filtering, not datalog negation — inline (not …) over a pattern with NO
  ;; matches anywhere (e.g. a model with no realized-as concepts) mis-fires in
  ;; datascript (the wholly-empty-relation gotcha the law combinators encapsulate)
  ;; `:structure/of` tags are KEYWORDS the mirror stringifies WITHOUT the colon, but `:val/tag` is
  ;; stored WITH it — normalize the in-use tags through `keyword` so the membership test lines up.
  (let [in-use   (into #{} (map (comp str keyword first))
                       (cq/q '[:find ?t :where [_ :structure/of ?t]] db))
        realized (into #{} (map first)
                       (cq/q '[:find ?s :where [?s :val/realizes _]] db))
        included (into #{} (map first)
                       (cq/q '[:find ?t :where [?r :rel/kind :includes] [?r :rel/to ?t]] db))]
    (->> (cq/q '[:find ?s ?n ?t
                 :where [?s :structure/of :canvas.vocab.grammar/Structure]
                        [?s :entity/name ?n] [?s :val/tag ?t]]
               db)
         (remove (fn [[s _ t]] (or (realized s) (included s) (= ":Any" t) (in-use t))))
         (map second) sort vec)))
