(ns fukan.canvas.projection.reference
  "The SELECTION REFERENCE: what a selection may say, written for the model it will be run
   against.

   A selection — the datalog clauses binding `?n` that narrow a view, `describe`, `report` and
   `elements` — had no account outside the source of the namespaces that evaluate it. This one
   is generated, and from the model rather than from fukan's own vocabulary: every example is
   built from a sort, a relation or an id the model at hand declares, so a project in a
   vocabulary fukan does not ship is shown selections that run for it.

   EVERY SELECTION PRINTED WAS RUN. An example is evaluated by the selection engine before it is
   written, and is written with the number of declared nodes it selected. One the engine refuses
   is not printed; the reference says in its place that this model affords no such example.

   A selection is always printed alone on a line, indented four spaces and followed by
   `; <count> node(s)` — the one spelling, so a reader (and the test that holds this text to its
   word) can lift every selection back out."
  (:require [clojure.string :as str]
            [fukan.canvas.core.lens :as lens]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.projection.design :as design]
            [fukan.canvas.projection.view :as view]
            [fukan.cozo.query :as cq]))

(defn- short-name [qualified]
  (subs qualified (inc (or (str/last-index-of qualified "/") -1))))

(defn- members-by
  "The declared nodes `rel` relates, as `{container-id [member-id …]}` — asked of the engine by
   name, so a derived relation answers as a stored one does. Nil when the engine refuses the
   relation or it relates no two declared nodes."
  [db declared rel]
  (try
    (->> (cq/q (vec (concat '[:find ?g ?m :in $ % :where] [(list (symbol rel) '?g '?m)]))
               db (s/vocab-rules))
         (keep (fn [[g m]] (when (and (declared g) (declared m) (not= g m)) [(declared g) (declared m)])))
         (reduce (fn [acc [g m]] (update acc g (fnil conj []) m)) {})
         not-empty)
    (catch Exception _ nil)))

(defn- grouping
  "A relation of this model that gives some declared node members, with the node it gives the
   most: `{:relation :id :members}`, or nil when no relation the model offers does.

   Relations with stored edges are asked first because they answer at once and a derived one may
   not — but which relation groups is decided by what the engine answers, never by that count: a
   derived relation with members is used when no stored one has any."
  [db declared]
  (some (fn [{:keys [name]}]
          (when-let [groups (members-by db declared name)]
            (let [[id members] (first (sort-by (fn [[id ms]] [(- (count ms)) id]) groups))]
              {:relation name :id id :members (count members)})))
        (sort-by (juxt (comp - :edges) :name) (:relations (view/views db)))))

(defn- common-prefix
  "The id prefix most declared nodes share: what comes before the last `/` of an id (or, for ids
   without one, before the last `.`), as the commonest such prefix."
  [ids]
  (let [cut (fn [id] (some #(when-let [i (str/last-index-of id %)] (subs id 0 (inc i))) ["/" "."]))]
    (->> (keep cut ids) frequencies (sort-by (fn [[p n]] [(- n) p])) ffirst)))

(defn ^{:malli/schema [:=> [:cat :StructureDb] :Text]}
  selection-reference
  "The selection reference for `db`, as text: the clause forms a selection may use, each with an
   example that ran; the attributes this model's declared nodes carry; its sorts and the
   relations it offers; the rule that a view draws a link only between two selected nodes; and
   four worked selections built from the model's own names — one sort, a union of sorts, a
   grouping with its members, nodes by id prefix with their members.

   An example this model cannot supply — it declares one sort, or no relation with members, or
   the engine refuses the form — is replaced by a line saying so. Evaluates every selection it
   prints; a model with no stored relation is asked each derived one in turn, which can be slow."
  [db]
  (let [nodes    (design/declared-nodes db)
        ids      (into {} (cq/q '[:find ?e ?id :where [?e :entity/id ?id]] db))
        declared (into {} (keep (fn [[e _]] (when-let [id (ids e)] [e id]))) nodes)
        sorts    (->> (frequencies (map (comp str second) nodes))
                      (sort-by (fn [[t n]] [(- n) t])))
        ;; a sort is chosen and written by its FULL tag: two sorts may share a short name, and
        ;; each still exemplifies on its own and in a union with the other
        [a b]    (map first sorts)
        of       (fn [v t] (list 'is v (keyword t)))
        attrs    (->> nodes
                      (mapcat (fn [[e _]] (keys (dissoc (cq/entity db e) :db/id))))
                      frequencies
                      (sort-by (comp str key)))
        {:keys [relation id]} (grouping db declared)
        rel      (some-> relation symbol)
        prefix   (common-prefix (vals declared))
        ran      (fn [selection]
                   ;; the count is of DECLARED nodes: what a view or a description would keep
                   (try (count (filter declared (lens/focus-nodes db selection)))
                        (catch Exception _ nil)))
        example  (fn [selection]
                   (if-let [n (when selection (ran selection))]
                     (str "    " (pr-str selection) "  ; " n (if (= 1 n) " node" " nodes"))
                     "    (no such example exists for this model)"))
        form     (fn [spelling meaning selection] [(str "  " spelling) (str "      " meaning) (example selection) ""])
        worked   (fn [title meaning selection] [(str "### " title) meaning "" (example selection) ""])
        named    (some->> (vals declared) sort first)]
    (str/join
     "\n"
     (concat
      ["# Selections"
       ""
       "A selection is an EDN vector of datalog clauses that together bind ?n, the selected node."
       "It narrows the declared design: `--select` on the CLI, `select` in an explorer address."
       "Clauses are joined: a node is selected when every clause holds for it. Every selection"
       "below was run against this model; the count beside it is the declared nodes it selects."
       ""
       "## Clause forms"
       ""]
      (form "[?n :attribute value]"
            "A stored attribute of a node. The value may be a literal, a ?variable, or _ for any."
            (when named [['?n :entity/id named]]))
      (form "(Sort ?n)"
            "Every node of a sort, by the sort's short name."
            (when a [(list (symbol (short-name a)) '?n)]))
      (form "(is ?n :namespace/Sort)"
            "The same, by the sort's full name — for when two sorts share a short one."
            (when a [(of '?n a)]))
      (form "(relation ?a ?b)"
            "Two nodes a relation joins, stored or derived. Either may be ?n, a ?variable or _."
            (when rel [(list rel '?n '_)]))
      (form "(not clause)"
            "Negation. Every variable in it must be bound by a positive clause written before it."
            (when (and a rel) [(of '?n a) (list 'not (list rel '?n '_))]))
      (form "(not-join [?vars] clause clause …)"
            "Negation of several clauses at once; only ?vars are shared with the rest."
            (when (and a b rel) [(of '?n a) (list 'not-join ['?n] (list rel '?n '?m) (of '?m b))]))
      (form "(or-join [?vars] clause (and clause clause …) …)"
            "Alternatives. Each binds ?vars; `and` groups several clauses into one alternative."
            (when (and a b) [(list 'or-join ['?n] (of '?n a) (of '?n b))]))
      (form "[(predicate ?var \"literal\")]"
            "A test on bound values: =, not=, <, >, <=, >=, and clojure.string/starts-with?."
            (when prefix [['?n :entity/id '?id] [(list 'clojure.string/starts-with? '?id prefix)]]))
      (form "(path ?from [:+ :relation] ?to)"
            "A path of relations: :+ one or more, :* zero or more, :? optional, :cat in sequence."
            (when rel [['?g :entity/id id] (list 'path '?g [:+ (keyword rel)] '?n)]))
      ["## Attributes"
       ""
       "What this model's declared nodes carry, and how many of them carry it:"
       ""]
      (for [[k n] attrs] (str "  " k "  (" n ")"))
      [""
       "## Sorts"
       ""]
      (for [[t n] sorts] (str "  " t "  (" n ")"))
      [""
       "## Relations"
       ""
       "Usable in a clause, and as `nest` or `link` in a view. The number is the stored edges"
       "between declared nodes; a relation with none is derived, or nothing uses it."
       ""]
      (for [{:keys [name edges]} (:relations (view/views db))] (str "  " name "  (" edges ")"))
      [""
       "## Edges in a view"
       ""
       "A view draws a link edge only between two selected nodes. An edge with an end the"
       "selection leaves out is not drawn, so to see the edges between two sorts, select both."
       ""
       "## Worked selections"
       ""]
      (worked "One sort"
              "Every node of one sort."
              (when a [(of '?n a)]))
      (worked "A union of sorts"
              "Nodes of either sort — what a view needs to draw the edges between them."
              (when (and a b) [(list 'or-join ['?n] (of '?n a) (of '?n b))]))
      (worked "A grouping with its members"
              (if rel
                (str "One node and everything `" rel "` puts directly in it.")
                "One node and everything a relation puts directly in it.")
              (when rel [(list 'or-join ['?n]
                               ['?n :entity/id id]
                               (list 'and ['?g :entity/id id] (list rel '?g '?n)))]))
      (worked "Nodes by id prefix, with their members"
              "Every node whose id starts with a prefix, and what those nodes hold."
              (when (and rel prefix)
                (let [starts [(list 'clojure.string/starts-with? '?id prefix)]]
                  [(list 'or-join ['?n]
                         (list 'and ['?n :entity/id '?id] starts)
                         (list 'and ['?g :entity/id '?id] starts (list rel '?g '?n)))])))))))
