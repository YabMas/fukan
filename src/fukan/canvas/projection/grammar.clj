(ns fukan.canvas.projection.grammar
  "The PRINT-DUAL of the authoring surface: a reified `Structure` node renders back
   as its map-form `defstructure`. The reader/syntax hooks compress authoring INTO
   the graph; this projection decompresses the graph back into the same concise
   surface — so the grammar primer is live, derived, and can never drift from the
   registry it reflects.

   Three renders from the same parts:
     `structure-form`      — the faithful DATA form (laws carry their datalog,
                             unquoted; `^:value` rides the name symbol's metadata).
     `grammar-primer`      — the reference-card STRING: every Vocabulary in the
                             model, each structure with aligned slots, first doc
                             line, law descs (datalog elided as `…`).
     `correspondence-card` — the design↔fact seam as one card: twin ladder and
                             every demand with its stable law key and desc.
                             Registry-direct: reads (s/correspondence) and
                             (s/laws-of), no model db required.

   The first two are model db → form / string; the card is registry-direct."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.core.typing :as typing]))

;; ── parts: one reified Structure → its authoring ingredients ─────────────────

(def ^:private malli->scalar {:string :string, :int :int, :boolean :boolean})

(defn- ord
  "A `:rel/order` cell as a long for sorting — `cq/q` returns leaf cells natively (absent → -1)."
  [o] (if (nil? o) -1 (long o)))

(defn- payload-form
  "A `:val/form` / payload value as its CODE-FORM — pr-str'd to a string in the Cozo mirror, read back."
  [v] (cond-> v (string? v) edn/read-string))

(defn- struct-tag
  "A node's `:structure/of` as a keyword — Cozo stringifies it (no colon); `keyword` is
   idempotent if it ever arrives a keyword."
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
  ;; the optional :rel/payload and :rel/props are read separately and merged (no get-else on Cozo);
  ;; :rel/kind is a keyword the mirror stringifies, so re-keywordize it for the slot/* filter + the quantifier map
  (let [base  (cq/q '[:find ?r ?k ?l ?o ?t :in $ ?s
                      :where [?r :rel/from ?s] [?r :rel/kind ?k] [?r :rel/label ?l]
                             [?r :rel/order ?o] [?r :rel/to ?t]] db s)
        pays  (into {} (cq/q '[:find ?r ?p :in $ ?s
                               :where [?r :rel/from ?s] [?r :rel/payload ?p]] db s))
        propm (into {} (cq/q '[:find ?r ?p :in $ ?s
                               :where [?r :rel/from ?s] [?r :rel/props ?p]] db s))]
    (->> base
         (filter #(= "slot" (namespace (keyword (nth % 1)))))
         (sort-by #(ord (nth % 3)))
         (mapv (fn [[r k l _ t]]
                 (let [char-props (some-> (propm r) payload-form)
                       pay-props  (when-let [p (pays r)] {:payload (keyword p)})
                       props      (not-empty (merge char-props pay-props))]
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

(defn- parts [db s]
  (let [e (cq/entity db s)]
    {:name       (symbol (:entity/name e))
     :doc        (:entity/doc e)
     :value?     (boolean (:val/value e))
     :realizes   (when (:val/realizes e) (payload-form (:val/form e)))
     :corresponds (some-> (:val/corresponds e) payload-form)
     :slots      (slots-of db s)
     :laws       (laws-of db s)}))

;; ── the data form (round-trip) ────────────────────────────────────────────────

(defn- law-form [{:keys [desc scope rules offenders where src]}]
  (if src
    (list 'law desc src)   ; authored through a combinator — render the combinator back
    (concat ['law desc]
            (when scope [:scope scope])
            (when rules [:rules rules])
            [:offenders offenders :where where])))

(defn- demand-form
  "A corresponds demand map → its authored sub-form: `(realized {opts})` or `(realized)`. Nil for a
   DERIVED demand (the identity map) — it is a kernel consequence, not an authored form, so the
   print-dual omits it (it still counts toward the generated-law total). Nil-valued option entries
   are pruned so the rendered form is minimal."
  [{:keys [demand derived] :as d}]
  (when-not derived
    (let [opts (not-empty (into {} (remove (comp nil? val)) (dissoc d :demand)))]
      (if opts (list (symbol (name demand)) opts) (list (symbol (name demand)))))))

(defn ^{:malli/schema [:=> [:cat :StructureDb :Eid] :Form]}
  structure-form
  "The reified Structure at `eid` rendered back as its `defstructure` data form —
   the print-dual of the authoring surface. Laws carry their datalog unquoted
   (this is the PARSED form); `^:value` rides the name symbol's metadata.
   A `(corresponds …)` declaration restores the correspondence seam and its demand
   sub-forms; slot props (characters + demand options) render in the slots map's props position."
  [db eid]
  (let [{:keys [name doc value? slots corresponds realizes laws]} (parts db eid)]
    (concat ['defstructure (if value? (with-meta name {:value true}) name)]
            (when doc [doc])
            (when (seq slots) [(apply array-map (mapcat identity slots))])
            (when corresponds
              [(concat ['corresponds]
                       (when-let [b (:bridge corresponds)] [(list 'bridge b)])
                       (keep demand-form (:demands corresponds)))])
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

(defn- slot-props-map
  "Extract the props map (if any) from a rendered slot value: `[quantifier props target]`
   or `[props target]` (one-card with props). Returns nil when no props map is present."
  [sv]
  (when (vector? sv)
    (let [f (first sv) s (second sv)]
      (cond
        (map? f) f          ; [props target] — one-card with props
        (map? s) s          ; [quantifier props target] — quantifier + props
        :else nil))))

(defn- count-relation-demands
  "The number of demand laws generated from relation-demand prop-maps (inline slot props OR the
   external correspondence `:rel-demands`): a relation-map `(rel incl E)` contributes 1 (`:sub`/`:sup`)
   or 2 (`:eq`); a legacy `:realized-by` contributes 1 (+1 when `:faithful`); a legacy `:covered-from`
   contributes 1."
  [prop-maps]
  (reduce (fn [n pm]
            (+ n
               (case (:incl pm) :eq 2 (:sub :sup) 1 0)
               (if (:covered-from pm) 1 0)
               (if (:realized-by pm) (+ 1 (if (:faithful pm) 1 0)) 0)))
          0 prop-maps))

(defn- fmt-structure [db s]
  (let [{:keys [name doc value? slots corresponds realizes laws]} (parts db s)
        n-generated (when corresponds
                      (+ (count (:demands corresponds))
                         (count-relation-demands (keep (fn [[_ sv]] (slot-props-map sv)) slots))
                         (count-relation-demands (:rel-demands corresponds))))]
    (->> (concat
          [(str "(defstructure " (when value? "^:value ") name)]
          (when doc [(str "  " (pr-str (first-line doc)))])
          (when (seq slots) [(fmt-slots slots)])
          (when corresponds
            [(str "  (corresponds …)  ; ⇒ " n-generated " generated laws")])
          (when realizes [(str "  (realized-as '" (pr-str realizes) ")")])
          (map #(str "  (law " (pr-str (:desc %)) " …)") laws))
         (str/join "\n")
         (#(str % ")")))))

(defn ^{:malli/schema [:=> [:cat :StructureDb :VocabName] :Primer]}
  vocabulary-primer
  "One vocabulary (a grammar namespace) rendered as its defstructure forms."
  [db vocab-name]
  (let [members (->> (cq/q '[:find ?c ?n :in $ ?vn
                             :where [?v :structure/of :fukan.canvas.core.reflect/Vocabulary]
                                    [?v :entity/name ?vn]
                                    [?r :rel/from ?v] [?r :rel/kind :child] [?r :rel/to ?c]
                                    [?c :entity/name ?n]]
                           db vocab-name)
                     (sort-by second)
                     (map first))]
    (str/join "\n"
              (concat [(str "━━ " vocab-name " — " (count members) " structures ━━") ""]
                      (interpose "" (map #(fmt-structure db %) members))))))

(defn ^{:malli/schema [:=> [:cat :StructureDb] :Primer]}
  grammar-primer
  "The full GRAMMAR PRIMER: every vocabulary in the model, rendered live from the
   reified grammar — the canvas's language reference, derived not maintained."
  [db]
  (let [vocabs (sort (cq/q '[:find [?n ...]
                             :where [?v :structure/of :fukan.canvas.core.reflect/Vocabulary]
                                    [?v :entity/name ?n]] db))]
    (str/join "\n\n" (map #(vocabulary-primer db %) vocabs))))

(defn ^{:malli/schema [:=> [:cat] :Primer]}
  correspondence-card
  "The correspondence SEAM rendered as one card — the collected design↔fact morphism: the twin
   ladder (root kinds with their bridges, nested kinds), then every demand with its stable law
   KEY and desc. The descs come from the GENERATED laws (via laws-of), so the laws the demand
   declarations generate — invisible in the per-structure grammar view — are all visible here,
   each attributed to its declaration. Registry-direct (no db): renders the same seam
   (s/correspondence) returns as data."
  []
  (let [{:keys [kinds relations]} (s/correspondence)
        key->desc (into {} (for [sd (s/all-structures), law (s/laws-of sd)
                                 :when (:key law)] [(:key law) (:desc law)]))
        short     (fn [tag] (name tag))
        kind-line (fn [[tag {:keys [bridge]}]]
                    (if bridge
                      (format "  %-12s twins by-name via %s  (ROOT)" (short tag) bridge)
                      (format "  %-12s twins by-name within twinned containers" (short tag))))
        demand-lines (for [[_tag {:keys [demands]}] (sort-by (comp str key) kinds), d demands]
                       (format "  %-46s %s" (str (:key d)) (or (key->desc (:key d)) (:desc d))))
        ;; desc non-nil by construction — the seam↔laws-of key invariant: every key in (:keys r)
        ;; was generated by correspondence-laws, and key->desc is built from the same laws-of pass.
        rel-lines (for [r (sort-by (comp str :owner) relations), k (:keys r)]
                    (format "  %-46s %s" (str k) (key->desc k)))]
    (str/join "\n"
              (concat ["━━ CORRESPONDENCE — design ↔ fact ━━" ""]
                      (map kind-line (sort-by (comp str key) kinds))
                      ["" "node demands:"] demand-lines
                      ["" "relation demands:"] rel-lines))))

;; ── grammar drift (the dead-vocabulary reading) ───────────────────────────────

(defn ^{:malli/schema [:=> [:cat :StructureDb] [:vector :string]]} unused-structures
  "The grammar-drift reading: reified Structures no instance inhabits — dead
   vocabulary. Excludes the Any wildcard and derivation-inhabited concepts:
   realized-as. Sorted structure names. (A reading to reason with, not a gate — law-hosts and
   not-yet-spoken grammar are legitimate; the human interprets.)"
  [db]
  ;; set-filtering in Clojure (this is a reader, not a law) — a plain membership test over
  ;; the in-use tags, no datalog negation needed for the no-realized-as case.
  ;; `:structure/of` tags are KEYWORDS the mirror stringifies WITHOUT the colon, but `:val/tag` is
  ;; stored WITH it — normalize the in-use tags through `keyword` so the membership test lines up.
  (let [in-use   (into #{} (map (comp str keyword first))
                       (cq/q '[:find ?t :where [_ :structure/of ?t]] db))
        realized (into #{} (map first)
                       (cq/q '[:find ?s :where [?s :val/realizes _]] db))]
    (->> (cq/q '[:find ?s ?n ?t
                 :where [?s :structure/of :fukan.canvas.core.reflect/Structure]
                        [?s :entity/name ?n] [?s :val/tag ?t]]
               db)
         (remove (fn [[s _ t]] (or (realized s) (= ":Any" t) (in-use t))))
         (map second) sort vec)))
