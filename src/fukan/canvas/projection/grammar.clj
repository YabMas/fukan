(ns fukan.canvas.projection.grammar
  "The PRINT-DUAL of the authoring surface: a reified `Structure` node renders back
   as its map-form `defstructure`. The reader/syntax hooks compress authoring INTO
   the graph; this projection decompresses the graph back into the same concise
   surface — so the grammar primer is live, derived, and can never drift from the
   registry it reflects.

   Four renders from the same parts:
     `structure-form`      — the faithful DATA form (laws carry their datalog,
                             unquoted; `^:value` rides the name symbol's metadata).
     `correspondence-form` — the external `(correspond …)` data form for a design Structure,
                             registry-direct (the authored head/match/map), or nil when no
                             correspondence has that design sort.
     `grammar-primer`      — the reference-card STRING: every Vocabulary in the
                             model, each structure with aligned slots, first doc
                             line, law descs (datalog elided as `…`).
     `correspondence-card` — the design↔fact seam as one card: every registered essential
                             `correspond` (registry-direct — the authored head/match/map) plus its
                             live VOCAB-GENERIC coverage READINGS (unrealized/ambiguous, computed
                             over the model db's `corresponds`/`realized-*` rules — the kernel
                             demolition (Task 4) retired the demand-law seam this used to render;
                             coverage is read off the pairing rules, not generated laws). The unaccounted-public
                             count needs the fact sort's own `public` predicate — vocabulary this
                             kernel tier must not name — so it is `dev/user.clj`'s business,
                             appended after this card, not rendered here.

   The first three are model db → form / string; the card is registry-direct + db-direct."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.core.typing :as typing]
            ;; aliased for its meta-grammar sorts (::reflect/Structure …) — the print-dual
            ;; renders exactly the nodes reflection mints, so the edge is honest
            [fukan.canvas.core.reflect :as reflect]
            ;; for `doc-text` only: rendering a docstring the way it was authored is one rule,
            ;; and the node dual is where it lives. Stating it twice is how the two duals drift.
            [fukan.canvas.projection.instance :as inst]))

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
  "Card + optional props (payload) + targets → the slot's type expression. Props
   take malli's position after a quantifier; a props-only one-card leads with the
   props map. Several targets (a UNION slot) list as alternatives after the
   quantifier/props, mirroring the authoring form `[:* A B C]`."
  [kind props targets]
  (let [quantifier ({:slot/optional :?, :slot/many :*, :slot/some :+, :slot/set :set} kind)
        single     (when (= 1 (count targets)) (first targets))]
    (cond
      quantifier (into (if props [quantifier props] [quantifier]) targets)
      props      (into [props] targets)
      single     single
      :else      (into [{}] targets))))

(defn- slots-of [db s]
  ;; the optional :rel/payload and :rel/props are read separately and merged (no get-else on Cozo);
  ;; :rel/kind is a keyword the mirror stringifies, so re-keywordize it for the slot/* filter + the
  ;; quantifier map. A UNION slot reflects as several edges sharing label + order (alt position on
  ;; the id suffix) — regrouped here into one entry with the alternatives in order.
  (let [base  (cq/q '[:find ?r ?k ?l ?o ?t :in $ ?s
                      :where [?r :rel/from ?s] [?r :rel/kind ?k] [?r :rel/label ?l]
                             [?r :rel/order ?o] [?r :rel/to ?t]] db s)
        pays  (into {} (cq/q '[:find ?r ?p :in $ ?s
                               :where [?r :rel/from ?s] [?r :rel/payload ?p]] db s))
        propm (into {} (cq/q '[:find ?r ?p :in $ ?s
                               :where [?r :rel/from ?s] [?r :rel/props ?p]] db s))]
    (->> base
         (filter #(= "slot" (namespace (keyword (nth % 1)))))
         (sort-by (fn [[r _ _ o _]] [(ord o) (str r)]))
         (partition-by (fn [[_ k l _ _]] [k l]))
         (mapv (fn [rows]
                 (let [[r k l _ _] (first rows)
                       char-props (some-> (propm r) payload-form)
                       pay-props  (when-let [p (pays r)] {:payload (keyword p)})
                       props      (not-empty (merge char-props pay-props))
                       targets    (mapv (fn [[_ _ _ _ t]] (target-expr db t)) rows)]
                   [(keyword l) (slot-expr (keyword k) props targets)]))))))

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
    {:name     (symbol (:entity/name e))
     :doc      (:entity/doc e)
     :value?   (boolean (:val/value e))
     :realizes (when (:val/realizes e) (payload-form (:val/form e)))
     :slots    (slots-of db s)
     :laws     (laws-of db s)}))

;; ── the data form (round-trip) ────────────────────────────────────────────────

(defn- law-form [{:keys [desc scope rules offenders where src]}]
  (if src
    (list 'law desc src)   ; authored through a combinator — render the combinator back
    (list 'law desc
          (cond-> (array-map)
            scope (assoc :scope scope)
            rules (assoc :rules rules)
            true  (assoc :offenders offenders :where where)))))

(defn ^{:malli/schema [:=> [:cat :StructureDb :Eid] :Form]}
  structure-form
  "The reified Structure at `eid` rendered back as its `defstructure` data form —
   the print-dual of the authoring surface. Laws carry their datalog unquoted
   (this is the PARSED form); `^:value` rides the name symbol's metadata. External correspondence
   deliberately does not appear inside this form; use `correspondence-form` for its valid top-level dual."
  [db eid]
  (let [{:keys [name doc value? slots realizes laws]} (parts db eid)]
    (concat ['defstructure (if value? (with-meta name {:value true}) name)]
            (when doc [doc])
            (when (seq slots) [(apply array-map (mapcat identity slots))])
            (when realizes [(list 'realized-as realizes)])
            (map law-form laws))))

(declare corr-head)

(defn ^:export ^{:malli/schema [:=> [:cat :StructureDb :Eid] :any]}
  correspondence-form
  "Render the external `(correspond …)` whose DESIGN Structure is `eid` as canonical, re-authorable
   data — `(correspond [Design ?d Fact ?f] match realization-map)` — or nil when no correspondence has
   that design sort. Registry-direct: the reflected Structure at `eid` carries the design `:val/tag`,
   and the matching config in `s/all-corresponds` supplies the authored head (`corr-head`), the pairing
   MATCH, and the realization MAP verbatim (the same parts `correspondence-card` renders). The dual of
   `structure-form` for the bridge declaration that `structure-form` deliberately omits from a
   defstructure form (correspondence is EXTERNAL)."
  [db eid]
  (let [tag (ffirst (cq/q '[:find ?t :in $ ?s :where [?s :val/tag ?t]] db eid))]
    (when-let [c (first (filter #(= tag (str (:design %))) (s/all-corresponds)))]
      (list 'correspond (corr-head c) (:match c) (:map c)))))

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

(defn- fmt-structure
  "One structure as its `defstructure` form. `full?` keeps the whole docstring — a design
   DOCUMENT wants the concept explained, where a reference card wants one line and the rest of
   the page. Law bodies stay elided either way: the description states the rule, and the datalog
   under it is the mechanism."
  [db s full?]
  (let [{:keys [name doc value? slots realizes laws]} (parts db s)]
    (->> (concat
          [(str "(defstructure " (when value? "^:value ") name)]
          (when doc [(str "  " (if full? (inst/doc-text doc) (pr-str (first-line doc))))])
          (when (seq slots) [(fmt-slots slots)])
          (when realizes [(str "  (realized-as " (pr-str realizes) ")")])
          (map #(str "  (law " (pr-str (:desc %)) " …)") laws))
         (str/join "\n")
         (#(str % ")")))))

(defn ^{:malli/schema [:=> [:cat :StructureDb :VocabName [:or :map :nil]] :Primer]}
  vocabulary-primer
  "One vocabulary (a grammar namespace) rendered as its defstructure forms.

   `{:full? true}` keeps whole docstrings — the difference between a reference card, which is
   what the REPL primer wants, and a design document, which is what a reader arriving at the
   project wants. `opts` is required rather than an arity, so the modelled signature is one
   signature: the type dialect has no `:maybe`, and a fn with two shapes has two."
  [db vocab-name {:keys [full?]}]
  (let [members (->> (cq/q '[:find ?c ?n :in $ ?vn
                             :where (is ?v ::reflect/Vocabulary)
                                    [?v :entity/name ?vn]
                                    [?r :rel/from ?v] [?r :rel/kind :child] [?r :rel/to ?c]
                                    [?c :entity/name ?n]]
                           db vocab-name)
                      (sort-by second)
                      (map first))]
    (str/join "\n"
              (concat [(str "━━ " vocab-name " — " (count members) " structures ━━") ""]
                      (interpose "" (map #(fmt-structure db % full?) members))))))

(defn ^{:malli/schema [:=> [:cat :StructureDb] :Primer]}
  grammar-primer
  "The full GRAMMAR PRIMER: every vocabulary in the model, rendered live from the
   reified grammar — the canvas's language reference, derived not maintained."
  [db]
  (let [vocabs (sort (cq/q '[:find [?n ...]
                             :where (is ?v ::reflect/Vocabulary)
                                    [?v :entity/name ?n]] db))]
    (str/join "\n\n" (map #(vocabulary-primer db % nil) vocabs))))

(defn- corr-head
  "One registry config's authored `[Design ?d Fact ?f]` head — a VECTOR (the only shape the
   `correspond` macro accepts, so the render is re-authorable syntax, not just descriptive text) of
   short (unqualified) sort symbols, the same convention `target-expr` renders a slot target with;
   `dvar`/`fvar` ride the config verbatim (the exact symbols the author wrote, captured at
   macroexpansion)."
  [{:keys [design fact dvar fvar]}]
  [(symbol (name design)) dvar (symbol (name fact)) fvar])

(defn- reading-count
  "Run a collection-find count query binding `v`, over `where` (extra `is`/`corresponds`-shaped
   clauses) + the live vocab rules — the shared shape every coverage reading below refines."
  [db v where]
  (count (cq/q (into [:find [v '...] :in '$ '% :where] where) db (s/vocab-rules))))

(defn- unrealized-count
  "Design elements of one correspondence's design sort with no `corresponds` partner — the
   `(design dvar)` provenance clause + the unbound gate var of the fact sort mirror dev/user.clj's
   `drift` exactly (byte-parity between the two mirrored readings): design elements are authored by
   construction (a design tag never carries `:val/extracted`), so `(design dvar)` is a defensive
   restatement, not new selectivity; the gate keeps this 0, not a false backlog, when the fact sort
   has no instances yet (extraction hasn't run)."
  [db {:keys [design fact dvar]}]
  (reading-count db dvar
                 [(list 'is dvar design)
                  (list 'design dvar)
                  (list 'is '?_gate fact)
                  (list 'not-join [dvar] (list 'corresponds dvar '?_f))]))

(defn- ambiguous-count
  "Design elements paired with MORE THAN ONE distinct fact partner."
  [db {:keys [design dvar]}]
  (reading-count db dvar
                 [(list 'is dvar design)
                  (list 'corresponds dvar '?_f1)
                  (list 'corresponds dvar '?_f2)
                  [(list 'not= '?_f1 '?_f2)]]))

(defn- fmt-correspond
  "One registered essential correspondence → its card entry: the authored `(correspond …)` data
   form (head/match/map, `pr-str`'d — mirroring `fmt-structure`'s pretty-print) followed by its live
   VOCAB-GENERIC coverage readings (unrealized/ambiguous — computed from the registry + the
   `corresponds` rule alone, no vocabulary knowledge needed). The unaccounted-public count is
   deliberately NOT here: it requires naming the fact sort's own `public` predicate, which this
   kernel tier (ships no domain vocabulary) must not do — `dev/user.clj`'s `(correspondence)`
   appends it after the card, reading the public-unaccounted law by key."
  [db {:keys [match] rmap :map :as c}]
  (let [unrealized (unrealized-count db c)
        ambiguous  (ambiguous-count db c)]
    (str/join "\n"
              [(str "(correspond " (pr-str (corr-head c)))
               (str "  " (pr-str match))
               (str "  " (pr-str rmap) ")")
               (str "  unrealized: " unrealized "  ambiguous: " ambiguous)])))

(defn ^{:malli/schema [:=> [:cat :StructureDb] :Primer]}
  correspondence-card
  "The correspondence SEAM rendered as one card: every registered essential `correspond` — its
   authored head/match/map data form (registry-direct) — followed by its live VOCAB-GENERIC coverage
   READINGS over `db` (unrealized/ambiguous, computed over the `corresponds`/`realized-*` rules
   `terms-of` emits). Coverage is a READING here, not a law — the kernel demolition (Task 4) retired
   the demand-law generators this card used to describe; the essential `correspond` construct
   generates none to show. The unaccounted-public count (which needs the fact sort's own `public`
   predicate — vocabulary this kernel tier must not name) is `dev/user.clj`'s business, appended
   after this card."
  [db]
  (let [cs (sort-by (comp name :design) (s/all-corresponds))]
    (str/join "\n\n"
              (into ["━━ CORRESPONDENCE — design ↔ fact ━━"]
                    (map #(fmt-correspond db %) cs)))))

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
                 :where (is ?s ::reflect/Structure)
                        [?s :entity/name ?n] [?s :val/tag ?t]]
               db)
         (remove (fn [[s _ t]] (or (realized s) (= ":Any" t) (in-use t))))
         (map second) sort vec)))
