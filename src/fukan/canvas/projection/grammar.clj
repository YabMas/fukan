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

   Caveat (storage, not render): an enum's member TYPE is not stored — read-malli
   names members on the way in — so enum members render as strings, the slot-
   refinement convention. Slot options (`:payload`) are not yet reified, so they
   do not render.

   Pure projection: model db → form / string."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [datascript.core :as d]
            [fukan.dialect.malli :as malli]))

;; ── parts: one reified Structure → its authoring ingredients ─────────────────

(def ^:private malli->scalar {:string :String, :int :Int, :boolean :Bool})

(defn- de-keyword-enums
  "Enum members render keywordized by the dialect (member type is not stored);
   slot refinements author strings — map them back."
  [form]
  (walk/postwalk
   (fn [x]
     (if (and (vector? x) (= :enum (first x)))
       (into [:enum] (map #(if (keyword? %) (name %) %)) (rest x))
       x))
   form))

(defn- target-expr
  "A slot edge's target → its authoring type expression: a reified Structure → its
   name symbol; a Schema value → its malli form (bare scalars map back to
   :String/:Int/:Bool)."
  [db t]
  (let [e (d/entity db t)]
    (if (= :lib.type.malli/Schema (:structure/of e))
      (let [f (de-keyword-enums (malli/render db t))]
        (get malli->scalar f f))
      (symbol (:entity/name e)))))

(defn- slot-expr [kind target]
  (case kind
    :slot/one      target
    :slot/optional [:? target]
    :slot/many     [:* target]
    :slot/some     [:+ target]
    :slot/set      [:set target]))

(defn- slots-of [db s]
  (->> (d/q '[:find ?k ?l ?o ?t :in $ ?s
              :where [?r :rel/from ?s] [?r :rel/kind ?k] [?r :rel/label ?l]
                     [?r :rel/order ?o] [?r :rel/to ?t]] db s)
       (filter #(= "slot" (namespace (first %))))
       (sort-by #(nth % 2))
       (mapv (fn [[k l _ t]] [(keyword l) (slot-expr k (target-expr db t))]))))

(defn- laws-of [db s]
  (->> (d/q '[:find ?l ?id :in $ ?s
              :where [?r :rel/from ?s] [?r :rel/kind :law] [?r :rel/to ?l]
                     [?l :entity/id ?id]] db s)
       (sort-by second)
       (mapv (fn [[l _]]
               (let [e (d/entity db l)]
                 (merge {:desc (:val/desc e)
                         :scope (some-> (:val/scope e) edn/read-string)}
                        (:val/form e)))))))

(defn- includes-of [db s]
  (->> (d/q '[:find [?n ...] :in $ ?s
              :where [?r :rel/from ?s] [?r :rel/kind :includes] [?r :rel/to ?t]
                     [?t :entity/name ?n]] db s)
       sort (mapv symbol)))

(defn- parts [db s]
  (let [e (d/entity db s)]
    {:name     (symbol (:entity/name e))
     :doc      (:entity/doc e)
     :value?   (boolean (:val/value e))
     :realizes (when (:val/realizes e) (:val/form e))
     :slots    (slots-of db s)
     :includes (includes-of db s)
     :laws     (laws-of db s)}))

;; ── the data form (round-trip) ────────────────────────────────────────────────

(defn- law-form [{:keys [desc scope rules offenders where]}]
  (concat ['law desc]
          (when scope [:scope scope])
          (when rules [:rules rules])
          [:offenders offenders :where where]))

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
  (let [members (->> (d/q '[:find ?c ?n :in $ ?vn
                            :where [?v :structure/of :lib.grammar/Vocabulary]
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
  (let [vocabs (sort (d/q '[:find [?n ...]
                            :where [?v :structure/of :lib.grammar/Vocabulary]
                                   [?v :entity/name ?n]] db))]
    (str/join "\n\n" (map #(vocabulary-primer db %) vocabs))))
