(ns fukan.canvas.projection.view
  "The VIEW projection: a project's declared design, or one plane of what its producers read, as a
   graph a person can look at.

   A view spec says what to draw — the relations whose edges NEST their target inside their
   source, the relations drawn as EDGES, optionally the PLANE drawn instead of the declared design,
   and an optional selection narrowing that universe.
   Relations are named, never listed here: a name is resolved through the vocabulary's rules, so
   a stored relation and a derived one are asked for the same way, and a model in a vocabulary
   this namespace has never seen is drawn by the same code.

   The decisions below are this namespace's own, because a drawing forces them and a document
   does not:

   - A VIEW HAS ONE UNIVERSE. The declared design when the spec names no plane, the nodes the
     model labels with the plane it names otherwise (`universe`). Every node drawn, every pair a
     relation contributes and every judgement of placement stays inside it, so a view of one plane
     never shows a node of another, nor an edge to one. Which planes exist is the model's: this
     namespace names none.
   - AN OCCURRENCE IS A PATH. A node held by two containers appears once under each, so what is
     drawn is identified by the entity ids from a root down to it. Entity ids, not eids: an eid
     is the engine's and changes with every build.
   - BELOW THE ROOT LEVEL, VISIBLE IS WHAT WAS OPENED. The roots are the selected nodes no
     selected node nests; a node with a container appears only as a direct member of a visible
     open path.
   - A NODE THE NESTING SAYS NOTHING ABOUT IS COUNTED, NOT DRAWN. A node of the universe that the
     nesting relations neither put inside anything nor put anything inside is UNPLACED. It is
     left out of the root level and reported by sort, because a design in which most nodes
     float is a gap in the model, and drawing them all would bury the structure there is under
     it. A spec asks for them with `:unplaced true`.
   - A VIEW MAY BE ROOTED IN ONE NODE. `:root`, an entity id, makes that node's direct members
     the root level, so a viewer can stand inside a box instead of reading it among its
     siblings. What lies outside the root is not drawn, and neither is an edge to it.
   - AN EDGE IS CARRIED, NOT DROPPED. An end that is not visible is stood in for by the collapsed
     occurrence it sits inside, so closing a box never hides that something in it is related."
  (:require [fukan.canvas.core.lens :as lens]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.projection.design :as design]
            [fukan.cozo.query :as cq]))

(def ^:private relation-tag
  "The reflection meta-grammar's sort for a relation — fukan's own constant."
  :fukan.canvas.core.reflect/Relation)

(defn- pairs
  "The `[from to]` eid pairs of the relation named `rel`, asked through the vocabulary's rules.
   Throws what the query compiler throws for a name that is not a binary relation."
  [db rules rel]
  (cq/q (vec (concat '[:find ?a ?b :in $ % :where] [(list (symbol rel) '?a '?b)]))
        db rules))

(defn- plane-of
  "Each named, tagged node the model labels with a plane, eid → `[plane tag]`. A `^:value` node
   carries no name and is never a member of a plane's universe, as it is of no declared design."
  [db]
  (into {}
        (map (fn [[e p t]] [e [p t]]))
        (cq/q '[:find ?e ?p ?t :in $ % :where (plane ?e ?p) [?e :entity/name _] [?e :structure/of ?t]]
              db (s/vocab-rules))))

(defn ^{:malli/schema [:=> [:cat :StructureDb [:maybe :string]] [:vector [:tuple :int :keyword]]]}
  universe
  "What a view may contain, as `[eid tag]` pairs: the design projection's declared nodes when
   `plane` is nil, else the named nodes the model labels with `plane` — none for a plane it does
   not hold. The one definition every reading of a view starts from, so no view mixes planes."
  [db plane]
  (if (nil? plane)
    (design/declared-nodes db)
    (into [] (keep (fn [[e [p t]]] (when (= plane p) [e t]))) (plane-of db))))

(defn ^{:malli/schema [:=> [:cat :StructureDb] :ViewChoices]}
  views
  "What a model offers to view by: `{:relations [{:name :edges}] :planes [{:name :nodes}]}`.

   `:relations`, sorted by name, are the relations its reflected grammar declares that relate
   TWO things, since nesting and an edge each join two nodes. A property of one node and a
   relation of three are not offered. `:edges` counts the STORED edges of that kind whose two ends
   lie in one universe — both declared, or both on one plane — 0 for a derived relation and for
   one nothing instantiates, which is a hint for choosing and not a statement that the relation is
   empty. `:planes`, sorted by name, are the planes the model holds, each with how many named
   nodes carry its label: what a spec may name as its `:plane`.

   Binary is read off the relation's rules and not by evaluating it, which on a derived relation
   can take minutes. So a binary relation from a node to something that is not a node — a name —
   is still offered; it nests nothing and draws no edge."
  [db]
  (let [binary?  (->> (s/vocab-rules)
                      (group-by (comp str ffirst))
                      (into {} (map (fn [[n rules]] [n (every? #(= 2 (count (rest (first %)))) rules)]))))
        planes   (plane-of db)
        ;; which universe a node is in: ::declared, a plane's label, or none
        home     (merge (into {} (map (fn [[e _]] [e ::declared])) (universe db nil))
                        (update-vals planes first))
        stored   (->> (cq/q '[:find ?r ?k ?a ?b
                              :where [?r :rel/kind ?k] [?r :rel/from ?a] [?r :rel/to ?b]]
                            db)
                      (filter (fn [[_ _ a b]] (and (home a) (= (home a) (home b)))))
                      (map (fn [[_ k]] (name (keyword k))))
                      frequencies)
        names    (cq/q [:find '[?n ...]
                        :where ['?r :structure/of relation-tag] '[?r :entity/name ?n]]
                       db)]
    {:relations (->> names distinct (filter binary?) sort
                     (mapv (fn [n] {:name n :edges (get stored n 0)})))
     :planes    (->> (frequencies (map first (vals planes)))
                     (sort-by key)
                     (mapv (fn [[p n]] {:name p :nodes n})))}))

(defn- descendants-of
  "Every node below `e` under `members` (eid → child eids), `e` excluded. Guarded against a
   nesting cycle, which a relation chosen by name does not rule out."
  [members e]
  (loop [seen #{} todo (members e)]
    (if-let [[x & more] (seq todo)]
      (if (or (seen x) (= x e))
        (recur seen more)
        (recur (conj seen x) (concat more (members x))))
      seen)))

(defn ^{:malli/schema [:=> [:cat :StructureDb :ViewSpec [:set :Occurrence]] :ViewGraph]}
  view-graph
  "The visible graph of `spec` — `{:nest [relation names] :link [relation names] :plane label?
   :select clauses? :unplaced boolean? :root entity-id?}` — with the paths in `open` expanded:
   `{:nodes [{:path :sort :label :members :open}] :edges [{:from :to :relation :count}]
   :unplaced [{:sort :count}]}`, drawn from the spec's `universe`.

   `:unplaced` in the answer counts, by sort, the selected nodes the nesting relations say
   nothing about. It is the same whether or not the spec asked to draw them, so it stays a
   reading of the model and not of the drawing. Placement is judged over the whole universe and
   never over the selection: a module is placed by the operations it holds even when the
   selection keeps modules only.

   With `:root`, the root level is that node's direct members and paths start at a member, not
   at the root. The root need not be in the selection. A root the universe does not hold gives
   an empty graph rather than a refusal, for the reason a stale open path is ignored. Every
   node under a root is placed by it, so `:unplaced` is then empty.

   `:members` is how many direct members opening the occurrence would show. `:open` is true only
   for an occurrence that is both in `open` and has members. A path in `open` that is not
   visible — its node is gone, or an ancestor is closed — is ignored rather than refused, because
   an address written against one build must keep working against the next.

   An edge's `:count` is the number of edges of the universe it carries. One whose two ends are
   carried by the same occurrence is internal to a closed box and is not drawn.

   `spec` is trusted: its shape is the caller's to have established. A relation name that is not
   a binary relation, or a selection the engine cannot compile, throws."
  [db {:keys [nest link plane select unplaced root]} open]
  (let [rules    (s/vocab-rules)
        whole    (universe db plane)
        focus    (when select (lens/focus-nodes db select))
        sort-of  (into {} (if focus (filter (comp focus first)) identity) whole)
        in?      (fn [[a b]] (and (sort-of a) (sort-of b) (not= a b)))
        kinds    (into {} whole)
        nesting  (->> (mapcat #(pairs db rules %) nest)
                      (filter (fn [[a b]] (and (kinds a) (kinds b) (not= a b))))
                      distinct)
        placed   (into #{} cat nesting)
        floating (if root [] (remove placed (keys sort-of)))
        members  (->> nesting
                      (filter in?)
                      (reduce (fn [m [a b]] (update m a (fnil conj []) b)) {}))
        nested   (into #{} (mapcat val) members)
        ;; two scans rather than an entity read per node: `cq/entity` is several queries, and
        ;; every selected node needs its id before the first root can be named
        id       (into {} (cq/q '[:find ?e ?id :where [?e :entity/id ?id]] db))
        label    (into {} (cq/q '[:find ?e ?n :where [?e :entity/name ?n]] db))
        ;; visible occurrences, as path → eid of its last node
        root-e   (when root (some (fn [[e _]] (when (= root (id e)) e)) kinds))
        level    (if root
                   ;; from the unfiltered nesting: the root itself may be outside the selection
                   (for [[a b] nesting :when (and (= a root-e) (sort-of b))] [[(id b)] b [root-e b]])
                   (for [e (keys sort-of)
                         :when (and (not (nested e)) (or unplaced (placed e)))]
                     [[(id e)] e [e]]))
        visible  (loop [seen {}
                        todo level]
                   (if-let [[[path e chain] & more] (seq todo)]
                     (recur (assoc seen path e)
                            (cond-> more
                              (open path)
                              (concat (for [c (members e) :when (not (some #{c} chain))]
                                        [(conj path (id c)) c (conj chain c)]))))
                     seen))
        open?    (fn [path e] (boolean (and (open path) (seq (members e)))))
        ;; who stands for a node: its own occurrences, and every CLOSED occurrence it sits inside
        carriers (reduce (fn [m [path e]]
                           (reduce (fn [m x] (update m x (fnil conj #{}) path))
                                   m
                                   (cons e (when-not (open? path e) (descendants-of members e)))))
                         {} visible)
        edges    (->> (for [rel   link
                            [a b] (filter in? (pairs db rules rel))
                            from  (carriers a)
                            to    (carriers b)
                            :when (not= from to)]
                        {:from from :to to :relation rel})
                      frequencies
                      (map (fn [[edge n]] (assoc edge :count n)))
                      (sort-by (juxt :from :to :relation))
                      vec)]
    {:nodes (->> visible
                 (map (fn [[path e]]
                        {:path    path
                         :sort    (str (sort-of e))
                         :label   (str (label e))
                         :members (count (members e))
                         :open    (open? path e)}))
                 (sort-by :path)
                 vec)
     :edges edges
     :unplaced (->> (frequencies (map (comp str sort-of) floating))
                    (map (fn [[sort n]] {:sort sort :count n}))
                    (sort-by (juxt (comp - :count) :sort))
                    vec)}))
