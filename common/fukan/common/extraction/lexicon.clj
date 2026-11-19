(ns fukan.common.extraction.lexicon
  "The LEXICON producer: a project's domain vocabulary, read out of its source into the model.

   Lexicon is a library a project declares its domain in — entities as Malli maps carrying a few
   `:lexicon/*` properties, and VIEWS, the selections of attributes one use requires. Those
   declarations are plain data sitting in source, so they are read as forms, never evaluated:
   nothing of the adopter's is loaded, which is the rule the Clojure extractor beside this one
   already keeps.

   What it adds to the model is facts and nothing else — a model producer adds facts, never
   rules or laws of its own making:

     a shape a vocabulary lists  → Entity, or Value for one with no identity
     each of its map entries     → Attribute  (stored / ref / many / derived, what it refers to,
                                               the value shapes it embeds)
     each name of a `views` call → View       (the attributes it selects, the namespace declaring it)
     a function naming a view    → ViewUse

   WHY A VIEW AND NOT A KEYWORD. An attribute's identity is its qualified keyword, and the obvious
   reading — every mention of `:group/name` in the codebase is a use of it — was measured on a
   real adopter and is wrong: of 1,977 mentions of vocabulary keywords, 1,234 sat in namespaces
   that never use Lexicon, older code whose keys merely coincide. Inside a declared view the
   keyword IS the attribute, by construction; anywhere else it is a guess. So a view is the only
   place this producer reads a use of an attribute from. A view's own name is another matter: it
   is no key of any data, so a mention of it in a function's body is that function asking for it.

   NOTHING IS CONFIGURED. There is one way to declare a vocabulary (`lexicon.core/vocabulary`
   over a literal vector of shapes) and one way to name a view (`lexicon.core/views` over a
   literal map), and both are calls of the library, recognisable through a file's `ns` form. A
   project wrapping the library in makers of its own is read as declaring nothing."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [fukan.canvas.core.structure :as s :refer [defstructure defrelation]]
            [fukan.canvas.core.substrate :as sub]
            [fukan.common.extraction.clojure.module :refer [Ns]]
            [fukan.common.extraction.core :as extraction]
            [fukan.common.typing.malli :refer [NamedType]]
            [fukan.cozo.query :as cq]))

;; ── the FACT vocabulary ──────────────────────────────────────────────────────

(defstructure Attribute
  "One attribute of a domain vocabulary: a qualified keyword with one meaning wherever it appears.
   Its name is the keyword without the colon (`group/name`).

   `:kind` is what the declaration says the attribute IS, in Lexicon's own four words: `stored`
   state; a `ref` to one other entity; `many`, the inverse of a reference some other entity
   holds; or `derived` from attributes of the same entity. `:refers-to` is the entity on the
   other end of a `ref` or a `many`, and is absent for the other two. `:embeds` are the value
   shapes a stored attribute's schema nests: a shape with no identity passes under the name of
   the attribute holding it."
  {:kind      [:enum "stored" "ref" "many" "derived"]
   :refers-to [:? Entity]             ; a same-ns forward reference
   :embeds    [:* Value]}             ; the value shapes its schema nests
  (sub NamedType))                    ; a signature may name it: `:order/id` where one value passes

(defstructure Entity
  "One entity of a domain vocabulary — what MAY be known about a thing the domain names. It owns
   its attributes, and holds as `:part` the entities that declare it their parent.

   `:part` is stated on the container although the source states it on the member
   (`:lexicon/parent true`, on the member's reference to its container): ownership is the
   owner's to hold here, as it is for a Module and its members, and one direction is what lets a
   drawing nest by it."
  {:attribute [:* Attribute]
   :part      [:* Entity]})

(defstructure Value
  "One value shape of a domain vocabulary: attributes with no identity of their own (an address,
   a ledger line). Nothing refers to it; it is contained by being nested in an attribute, which
   `:embeds` it."
  {:attribute [:* Attribute]})

(defstructure View
  "One declared view: the attributes ONE USE of the vocabulary requires, and the namespace that
   declares it. Its name is the qualified keyword it is declared under, without the colon
   (`checkout/receipt`) — its whole identity, as an attribute's is, so two uses may each have a
   `summary`. A view declared with no name (`lexicon.core/view`, held in a var or spliced into
   another) is not in the model: nothing outside its scope could name it.

   `:selects` holds every vocabulary attribute the declaration mentions — what it reads out, and
   what a condition inside it (a `:where`, a `:holds`) reads to decide. Both are things the use
   depends on, and telling them apart is the library's business, not a structural reading's. A
   view built from something other than a literal selection selects nothing here; it is still
   emitted, since a view nobody could read is worth seeing as one."
  {:selects     [:set Attribute]
   :declared-in Ns}
  (sub NamedType))                    ; a signature may name it: `:checkout/receipt` where a shape passes

(defstructure ViewUse
  "One function naming one view in its body (the view's keyword) — where code actually ASKS for a shape, as against
   where the shape is declared or what a signature says of it. The function is held by its
   namespace and its NAME rather than pointed at: what the source calls a function (a macro
   that defines one, a form the Clojure extractor does not count) need not be one in the model,
   and a use by something the model lacks is then simply a use nothing answers to."
  {:use-of     View
   :use-within Ns
   :use-by     :string})

;; ── derived readings, at domain altitude ─────────────────────────────────────

(defrelation :entity-references
  "?e holds a reference to ?f, in either spelling: a `ref` attribute of ?e naming ?f, or a `many`
   attribute of ?e whose members are ?f. The domain's own graph, with containment a part of it."
  [?e ?f]
  [(attribute ?e ?a) (refers-to ?a ?f)])

(defrelation :view-reads
  "View ?v selects at least one attribute of ?e, an entity or a value shape."
  [?v ?e]
  [(selects ?v ?a) (attribute ?e ?a)])

(defrelation :ns-reads
  "Namespace ?ns declares a view reading entity ?e — where the domain's language meets the code's
   structure. Read against where namespaces sit (a Region, a Band, a Module), it is the context
   map: which part of the system depends on knowing which part of the domain."
  [?ns ?e]
  [(declared-in ?v ?ns) (view-reads ?v ?e)])

;; ── what a function actually reads ───────────────────────────────────────────

(defrelation :fn-uses
  "Function ?f names view ?v in its body."
  [?f ?v]
  [(use-of ?u ?v) (use-within ?u ?ns) [?u :val/use-by ?n] (child ?ns ?f) [?f :entity/name ?n]])

(defrelation :fn-reads
  "Function ?f itself reads entity ?e: it names a view selecting one of ?e's attributes."
  [?f ?e]
  [(fn-uses ?f ?v) (view-reads ?v ?e)])

;; The step leads with the recursive call on purpose: the functions reading anything themselves
;; are few, and walking the call graph BACKWARD from them touches only their callers. Stated
;; forward (`calls+` then `fn-reads`) it closes the whole call graph first.
(defrelation :reaches-entity
  "Function ?f reads entity ?e itself, or calls — at any depth — a function that does. A
   function's footprint in the domain."
  [?f ?e]
  [(fn-reads ?f ?e)]
  [(reaches-entity ?g ?e) (calls ?f ?g)])

;; ── signatures written in the domain's language ──────────────────────────────
;; A signature may NAME an attribute (`:order/id`, where one value passes) or a view
;; (`:checkout/receipt`, where a shape does), on the design side and in the code alike — the same expression at both
;; altitudes, so the ordinary signature law already compares them. These read what such a
;; signature says in the domain's terms. They hold of an authored Operation and of an extracted
;; function without telling the two apart: both carry a `:signature`.

(defrelation :speaks-of
  "Schema ?sch is about attribute ?a: it names it, names a view selecting it, or is built —
   a collection, an optional, a union, at any depth — from a schema that does."
  [?sch ?a]
  [(names-type ?sch ?a) (is ?a ::Attribute)]
  [(names-type ?sch ?v) (selects ?v ?a)]
  [(of+ ?sch ?c) (names-type ?c ?a) (is ?a ::Attribute)]
  [(of+ ?sch ?c) (names-type ?c ?v) (selects ?v ?a)])

(defrelation :takes-attribute
  "?x's signature takes attribute ?a, at some parameter."
  [?x ?a]
  [(signature ?x ?sig) (signature-in ?sig ?t) (speaks-of ?t ?a)])

(defrelation :gives-attribute
  "?x's signature gives attribute ?a, in its result."
  [?x ?a]
  [(signature ?x ?sig) (signature-out ?sig ?t) (speaks-of ?t ?a)])

(defrelation :hands-to
  "?x gives the view ?v and ?y takes it: one shape, named at both ends. It says the two agree on
   a shape, not that one calls the other."
  [?x ?v ?y]
  [(signature ?x ?s1) (signature-out ?s1 ?t) (names-type ?t ?v) (is ?v ::View)
   (signature ?y ?s2) (signature-in ?s2 ?u) (names-type ?u ?v)])

;; ── finding the declarations ────────────────────────────────────────────────
;; Nothing is configured. A project declares its vocabulary with `lexicon.core/vocabulary` and
;; its views with `lexicon.core/views`, and a call of either is recognisable on sight: the head
;; resolves, through the file's own `ns` form, to the library.

(def ^:private library "lexicon.core")

(defn- source-files [root]
  (->> (file-seq (io/file root))
       (filter #(and (.isFile ^java.io.File %)
                     (re-find #"\.cljc?$" (.getName ^java.io.File %))))))

(defn- ns-form [forms]
  (some #(when (and (seq? %) (= 'ns (first %)) (symbol? (second %))) %) forms))

(defn- require-map
  "What the symbols of a file's `ns` form resolve to: `{:alias {a ns} :refer {sym ns}}`, from its
   `:require` libspecs. Prefix lists are not followed — a spelling long out of use, and missing
   one costs a declaration, never invents one."
  [ns-form]
  (let [specs (->> ns-form
                   (filter #(and (seq? %) (= :require (first %))))
                   (mapcat rest)
                   (filter #(and (vector? %) (symbol? (first %)))))]
    (reduce (fn [m [lib & opts]]
              (let [{:keys [as refer]} (apply hash-map (if (even? (count opts)) opts (butlast opts)))]
                (cond-> m
                  (symbol? as)        (assoc-in [:alias as] lib)
                  (sequential? refer) (update :refer into (map (fn [r] [r lib])) refer))))
            {:alias {} :refer {}}
            specs)))

(defn- read-file
  "The source file `f` as `{:path :forms :ns :requires}`, or nil when it will not read or
   declares no namespace."
  [f]
  (when-let [forms (extraction/read-forms (str f))]
    (when-let [nsf (ns-form forms)]
      {:path (str f) :forms forms :ns (second nsf) :requires (require-map nsf)})))

(defn- qualify
  "What symbol `sym` names in `file`, as `[namespace name]` strings: through an alias, a referred
   name, or the file's own namespace."
  [{:keys [requires ns]} sym]
  (when (symbol? sym)
    (if-let [a (namespace sym)]
      [(str (get (:alias requires) (symbol a) a)) (name sym)]
      [(str (get (:refer requires) sym ns)) (name sym)])))

(defn- inside
  "What a walk of read source descends into — the wrappers a permissive read leaves in place of a
   reader conditional or a tagged literal are not collections, and carry what they wrap on `:form`."
  [form]
  (cond
    (reader-conditional? form) [(:form form)]
    (tagged-literal? form)     [(:form form)]
    (coll? form)               (seq form)))

(defn- calls-of
  "Every call in `file` of the library's function named `target`. The function passed as a
   value declares nothing anybody can read here, and is not a call."
  [target file]
  (->> (tree-seq inside inside (:forms file))
       (filter #(and (seq? %) (= [library target] (qualify file (first %)))))))

(defn- unreadable [file call what]
  (throw (ex-info (str "the Lexicon producer reads " what " — " (:path file) ":" (:line (meta call))
                       " gives " (pr-str call))
                  {:file (:path file) :form call})))

;; ── reading the vocabulary ───────────────────────────────────────────────────

(defn- ns-file
  "The source file of the namespace named `n` under `paths`, by the path a namespace has."
  [paths n]
  (let [rel (-> n (str/replace "." "/") (str/replace "-" "_"))]
    (first (for [p paths, ext [".clj" ".cljc"]
                 :let [f (io/file p (str rel ext))]
                 :when (.isFile f)]
             f))))

(defn- shape-declaration
  "The `def` named `sym-name` in `file`, read as an entity or value shape — `{:shape :value? :doc
   :entries :file :var}` — or nil when it is no literal `[:map …]`. A shape is named by the one
   namespace its keys share; whether that holds, and everything else Lexicon demands of a
   vocabulary, is Lexicon's check to make when the project loads, and is not repeated here."
  [file sym-name]
  (some (fn [form]
          (when (and (seq? form) (= 'def (first form)) (= sym-name (str (second form))))
            (let [body (last form)]
              (when (and (vector? body) (= :map (first body)))
                (let [props   (when (map? (second body)) (second body))
                      entries (filter #(and (vector? %) (keyword? (first %))) (rest body))]
                  (when-let [n (some-> (first entries) first namespace)]
                    {:shape   n
                     :value?  (contains? props :lexicon/value)
                     :doc     (when (and (= 4 (count form)) (string? (nth form 2))) (nth form 2))
                     :entries entries
                     :file    file
                     :var     [(str (:ns file)) sym-name]}))))))
        (:forms file)))

(defn- attribute-fact
  "One map entry `[k props? schema]` as `{:key :kind :target :parent? :embeds}`. `:target` is the
   entity a `ref` names, or the one a `many` reaches — the namespace of the reference it inverts.
   `:embeds` are the value shapes its schema nests: the shapes of `shape-of` (var → shape name)
   the schema names."
  [shape-of file [k & more]]
  (let [props (when (map? (first more)) (first more))
        ref   (:lexicon/ref props)
        many  (:lexicon/many props)]
    (cond-> {:key k
             :kind (cond ref "ref" many "many" (:lexicon/derived props) "derived" :else "stored")
             :embeds (->> (tree-seq inside inside (last more))
                          (keep #(shape-of (qualify file %)))
                          distinct sort vec)}
      ref                       (assoc :target (name ref))
      (qualified-keyword? many) (assoc :target (namespace many))
      (and ref (:lexicon/parent props)) (assoc :parent? true))))

(defn- attribute-id [k] (str k))
(defn- shape-id [n] (str ":" n))
(defn- view-id [k] (str "view" k))

(defn- vocabulary-roots
  "The Entity, Value and Attribute roots of the shape declarations `decls`, and the set of
   attribute keywords they declare. A reference to an entity the vocabulary does not declare
   keeps its kind and loses its target: the model has no node to point it at, and Lexicon's own
   check refuses such a vocabulary before any code runs."
  [decls]
  (let [shape-of (into {} (map (juxt :var :shape)) decls)
        entity?  (set (map :shape (remove :value? decls)))
        value?   (set (map :shape (filter :value? decls)))
        facts    (into {} (map (fn [d] [(:shape d) (mapv #(attribute-fact shape-of (:file d) %)
                                                        (:entries d))]))
                       decls)
        parts    (reduce (fn [m [e fs]]
                           (reduce (fn [m {:keys [parent? target]}]
                                     (if (and parent? (entity? target))
                                       (update m target (fnil conj []) e)
                                       m))
                                   m fs))
                         {} facts)]
    {:attributes (set (mapcat #(map :key %) (vals facts)))
     :roots
     (concat
      (for [{:keys [shape doc value?]} decls]
        [(shape-id shape)
         (sub/->InstanceValue
          (if value? ::Value ::Entity) shape doc nil
          (cond-> [{:rk :attribute :card :many
                    :targets (mapv (comp sub/->Ref attribute-id :key) (facts shape))}]
            (seq (parts shape))
            (conj {:rk :part :card :many
                   :targets (mapv (comp sub/->Ref shape-id) (sort (distinct (parts shape))))}))
          false)])
      (for [fs (vals facts), {:keys [key kind target embeds]} fs]
        [(attribute-id key)
         (sub/->InstanceValue
          ::Attribute (subs (str key) 1) nil {:val/kind kind}
          (cond-> []
            (entity? target)
            (conj {:rk :refers-to :card :optional :targets [(sub/->Ref (shape-id target))]})
            (seq (filter value? embeds))
            (conj {:rk :embeds :card :many
                   :targets (mapv (comp sub/->Ref shape-id) (filter value? embeds))}))
          false)]))}))

;; ── reading the views ────────────────────────────────────────────────────────

(defn- view-roots
  "The View roots `file` declares: one per name of each `views` call's map. The attributes a
   view selects are the vocabulary keywords its selection mentions — a selection that is not a
   literal (a var, a call building one) selects nothing here, and the view is still emitted."
  [attributes file]
  (for [call (calls-of "views" file)
        :let [named (nth call 2 nil)
              _     (when-not (map? named)
                      (unreadable file call "named views from a literal map of name to selection"))]
        [k selection] named
        :when (qualified-keyword? k)]
    [(view-id k)
     (sub/->InstanceValue
      ::View (subs (str k) 1) nil nil
      [{:rk :selects :card :set
        :targets (->> (tree-seq inside inside selection)
                      (filter #(and (keyword? %) (attributes %)))
                      distinct (sort-by str) (mapv (comp sub/->Ref attribute-id)))}
       {:rk :declared-in :card :one :targets [(sub/->Ref (str (:ns file)))]}]
      false)]))

(defn- function-body
  "The body forms of a top-level function definition `form`, every arity's: what is left once
   the name, docstring, attribute map, parameter vectors and inline `:-` annotations are set
   aside. A view named in any of those is the function's SIGNATURE speaking, not its body."
  [form]
  (let [skip    (fn [xs] (drop-while #(or (string? %) (map? %)) xs))
        typed   (fn [xs] (if (= :- (first xs)) (drop 2 xs) xs))
        more    (-> (drop 2 form) skip typed skip)
        arities (if (vector? (first more)) [more] (filter seq? more))]
    (mapcat (fn [[_params & body]] (typed body)) arities)))

(defn- use-roots
  "The ViewUse roots of `file`: per top-level function, each of the `views` (their names, as
   keywords) its body mentions."
  [views file]
  (for [form (:forms file)
        :when (and (seq? form) (symbol? (first form)) (symbol? (second form))
                   (str/starts-with? (name (first form)) "defn"))
        :let  [fname (str (second form))]
        k     (->> (tree-seq inside inside (function-body form))
                   (filter #(and (keyword? %) (views %)))
                   distinct)]
    [(str "use:" (:ns file) "/" fname ">" k)
     (sub/->InstanceValue
      ::ViewUse (str fname ">" (subs (str k) 1)) nil {:val/use-by fname}
      [{:rk :use-of     :card :one :targets [(sub/->Ref (view-id k))]}
       {:rk :use-within :card :one :targets [(sub/->Ref (str (:ns file)))]}]
      false)]))

;; ── the producer ─────────────────────────────────────────────────────────────

(defn extract-roots
  "The Lexicon FACTS under `paths`, as `{:roots [[id InstanceValue]…]}` — none for a project that
   does not use the library. Its views point at namespaces by name, so these roots are merged
   with the Clojure extractor's over the same paths (`fukan.infra.model` does that).

   Only files that could hold a declaration are read: one whose text names the library, then
   the namespaces a vocabulary lists its shapes from, then — for the uses — one whose text
   names a declared view. What cannot be read THROWS rather than being left out: a vocabulary
   listed some other way than as a literal vector of names, a name that is no literal `[:map …]`,
   a `views` call whose map is not a literal. A shape missing from the model would make every
   view of it read as selecting nothing, and nothing would say why; and Lexicon's own rule is
   that a declaration is a literal form readable without loading the code."
  [paths]
  (let [sources  (vec (mapcat source-files paths))
        text     (memoize (fn [f] (slurp f)))
        read     (memoize (fn [f] (read-file f)))
        files    (for [f sources :when (str/includes? (text f) library)]
                   (or (read f)
                       (throw (ex-info (str "the Lexicon producer could not read " f)
                                       {:unreadable (str f)}))))
        by-ns    (into {} (map (juxt (comp str :ns) identity)) files)
        file-of  (fn [n] (or (by-ns n) (some-> (ns-file paths n) read)))
        decls    (->> (for [file files
                            call (calls-of "vocabulary" file)
                            :let [listed (second call)
                                  _      (when-not (and (vector? listed) (every? symbol? listed))
                                           (unreadable file call "a vocabulary from a literal vector of shape names"))]
                            sym  listed
                            :let [[n nm] (qualify file sym)]]
                        (or (some-> (file-of n) (shape-declaration nm))
                            (unreadable file call (str "each shape from a literal `[:map …]` in a def, and `"
                                                       sym "` is none it can find"))))
                      (reduce (fn [acc d] (if (some #(= (:var d) (:var %)) acc) acc (conj acc d))) []))
        {:keys [attributes roots]} (vocabulary-roots decls)
        views    (vec (mapcat #(view-roots attributes %) files))
        names    (set (map (fn [[_ iv]] (keyword (:name iv))) views))
        spelled  (map str names)
        users    (when (seq names)
                   (for [f sources
                         :when (some #(str/includes? (text f) %) spelled)
                         :let  [file (read f)]
                         :when file]
                     file))]
    {:roots (vec (concat roots views (mapcat #(use-roots names %) users)))}))

;; ── readings ─────────────────────────────────────────────────────────────────

(defn domain-map
  "The domain as the vocabulary declares it: `{:entities #{name} :parts #{[container member]}
   :references #{[from to]}}`, references including the containing ones."
  [db]
  (let [rules (s/vocab-rules)]
    {:entities   (set (cq/q '[:find [?n ...] :in $ % :where (is ?e ::Entity) [?e :entity/name ?n]]
                            db rules))
     :parts      (set (cq/q '[:find ?cn ?mn :in $ %
                              :where (part ?c ?m) [?c :entity/name ?cn] [?m :entity/name ?mn]]
                            db rules))
     :references (set (cq/q '[:find ?en ?fn :in $ %
                              :where (entity-references ?e ?f)
                                     [?e :entity/name ?en] [?f :entity/name ?fn]]
                            db rules))}))

(defn context-map
  "Which namespaces declare views reading which entities, as a set of `[namespace entity]` name
   pairs. Grouped by whatever a project groups its namespaces by, it is that project's context
   map — derived, with nothing authored to keep in step."
  [db]
  (set (cq/q '[:find ?nn ?en :in $ %
               :where (ns-reads ?ns ?e) [?ns :entity/name ?nn] [?e :entity/name ?en]]
             db (s/vocab-rules))))

(defn footprint
  "Which namespaces hold a function REACHING which entity — reading it through a view, itself or
   through what it calls — as a set of `[namespace entity]` name pairs. `context-map` says where
   the domain's words are written down; this says how far they travel."
  [db]
  (set (cq/q '[:find ?nn ?en :in $ %
               :where (reaches-entity ?f ?e) (child ?ns ?f) (is ?ns :fukan.common.extraction.clojure.module/Ns)
                      [?ns :entity/name ?nn] [?e :entity/name ?en]]
             db (s/vocab-rules))))

(defn unread-claims
  "Functions whose signature GIVES an attribute of an entity they neither reach nor are handed
   any attribute of, as a set of `[namespace function entity]` name triples: the signature says
   the function knows something of the entity, and nothing it reads or takes says how. A
   reading, not a law — a function may build a value from nothing (a constructor does)."
  [db]
  (set (cq/q '[:find ?nn ?fn ?en :in $ %
               :where (gives-attribute ?f ?a) (attribute ?e ?a) (child ?ns ?f) (is ?ns :fukan.common.extraction.clojure.module/Ns)
                      (not (reaches-entity ?f ?e))
                      (not-join [?f ?e] (takes-attribute ?f ?b) (attribute ?e ?b))
                      [?ns :entity/name ?nn] [?f :entity/name ?fn] [?e :entity/name ?en]]
             db (s/vocab-rules))))

(defn attribute-readers
  "The views selecting the attribute named `attribute` (`\"group/name\"`), as a set of
   `[namespace view]` name pairs — where one piece of the domain's knowledge is asked for."
  [db attribute]
  (set (cq/q '[:find ?nn ?vn :in $ % ?an
               :where (is ?a ::Attribute) [?a :entity/name ?an]
                      (selects ?v ?a) (declared-in ?v ?ns)
                      [?v :entity/name ?vn] [?ns :entity/name ?nn]]
             db (s/vocab-rules) attribute)))
