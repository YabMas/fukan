(ns fukan.common.extraction.lexicon
  "The LEXICON producer: a project's domain vocabulary, read out of its source into the model.

   Lexicon is a library a project declares its domain in — entities as Malli maps carrying a few
   `:lexicon/*` properties, and VIEWS, the selections of attributes one use requires. Those
   declarations are plain data sitting in source, so they are read as forms, never evaluated:
   nothing of the adopter's is loaded, which is the rule the Clojure extractor beside this one
   already keeps.

   What it adds to the model is three sorts and nothing else — a model producer adds facts, never
   rules or laws of its own making:

     an entity declaration      → Entity     (with the entities it contains, as `:part`)
     each of its map entries    → Attribute  (stored / ref / many / derived, and what it refers to)
     a call of the view maker   → View       (the attributes it selects, the namespace declaring it)

   WHY A VIEW AND NOT A KEYWORD. An attribute's identity is its qualified keyword, and the obvious
   reading — every mention of `:group/name` in the codebase is a use of it — was measured on a
   real adopter and is wrong: of 1,977 mentions of vocabulary keywords, 1,234 sat in namespaces
   that never use Lexicon, older code whose keys merely coincide. Inside a declared view the
   keyword IS the attribute, by construction; anywhere else it is a guess. So a view is the only
   place this producer reads a use from, and a use the code makes without declaring a view is
   not in the model. That is a blind spot stated, not a claim that such uses do not exist.

   IT MUST BE TOLD WHERE TO LOOK (`*sources*`), because neither thing it reads is recognisable on
   sight. An entity is a `[:map …]` in a `def`, which is also what every other schema in a
   Clojure project is; and the view maker is a function the adopter usually closes over its own
   vocabulary, so it has the adopter's name, not the library's. Unconfigured, it reads nothing
   and adds nothing."
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
   other end of a `ref` or a `many`, and is absent for the other two."
  {:kind      [:enum "stored" "ref" "many" "derived"]
   :refers-to [:? Entity]}            ; a same-ns forward reference
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

(defstructure View
  "One declared view: the attributes ONE USE of the vocabulary requires, and the namespace that
   declares it. Its name is the var the declaration is bound to.

   `:selects` holds every vocabulary attribute the declaration mentions — what it reads out, and
   what a condition inside it (a `:where`, a `:holds`) reads to decide. Both are things the use
   depends on, and telling them apart is the library's business, not a structural reading's. A
   view built from something other than a literal selection selects nothing here; it is still
   emitted, since a view nobody could read is worth seeing as one."
  {:selects     [:set Attribute]
   :declared-in Ns}
  (sub NamedType))                    ; a signature may name it: `Summary` where a shape passes

;; ── derived readings, at domain altitude ─────────────────────────────────────

(defrelation :entity-references
  "?e holds a reference to ?f, in either spelling: a `ref` attribute of ?e naming ?f, or a `many`
   attribute of ?e whose members are ?f. The domain's own graph, with containment a part of it."
  [?e ?f]
  [(attribute ?e ?a) (refers-to ?a ?f)])

(defrelation :view-reads
  "View ?v selects at least one attribute of entity ?e."
  [?v ?e]
  [(selects ?v ?a) (attribute ?e ?a)])

(defrelation :ns-reads
  "Namespace ?ns declares a view reading entity ?e — where the domain's language meets the code's
   structure. Read against where namespaces sit (a Region, a Band, a Module), it is the context
   map: which part of the system depends on knowing which part of the domain."
  [?ns ?e]
  [(declared-in ?v ?ns) (view-reads ?v ?e)])

;; ── signatures written in the domain's language ──────────────────────────────
;; A signature may NAME an attribute (`:order/id`, where one value passes) or a view (`Summary`,
;; where a shape does), on the design side and in the code alike — the same expression at both
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

;; ── configuration ────────────────────────────────────────────────────────────

(def ^:dynamic *sources*
  "Where a project's Lexicon declarations are, or nil for a project that has none:

     {:vocabulary [\"shop.domain.lexicon\"]        ; namespaces (each also a prefix) declaring entities
      :view       #{shop.domain.lexicon/view}}   ; the qualified symbols that make a view

   Bound by whoever composes the build, as `*spec-dirs*` is."
  nil)

;; ── reading the vocabulary ───────────────────────────────────────────────────

(defn- source-files [root]
  (->> (file-seq (io/file root))
       (filter #(and (.isFile ^java.io.File %)
                     (re-find #"\.cljc?$" (.getName ^java.io.File %))))))

(defn- ns-form [forms]
  (some #(when (and (seq? %) (= 'ns (first %)) (symbol? (second %))) %) forms))

(defn- under?
  "Is namespace name `n` one of `prefixes`, or beneath one?"
  [prefixes n]
  (some #(or (= n %) (str/starts-with? n (str % "."))) prefixes))

(defn- entity-declaration
  "`form` read as an entity declaration — `{:entity :doc :entries}` — or nil.

   An entity is a top-level `def` of a Malli `:map` whose keys are qualified, share ONE namespace
   (the entity's name) and include `<name>/id`. All three are Lexicon's own definition, and all
   three are needed: a namespace holding entities also holds ordinary schemas, and a map with
   keys from two namespaces or no id is one of those."
  [form]
  (when (and (seq? form) (= 'def (first form)) (symbol? (second form)))
    (let [body (last form)
          doc  (when (and (= 4 (count form)) (string? (nth form 2))) (nth form 2))]
      (when (and (vector? body) (= :map (first body)))
        (let [entries (filter #(and (vector? %) (keyword? (first %))) (rest body))
              spaces  (set (map (comp namespace first) entries))
              entity  (first spaces)]
          (when (and (seq entries) (= 1 (count spaces)) entity
                     (some #(= (keyword entity "id") (first %)) entries))
            {:entity entity :doc doc :entries entries}))))))

(defn- attribute-fact
  "One map entry `[k props? schema]` as `{:key :kind :target :parent?}`. `:target` is the entity a
   `ref` names, or the one a `many` reaches — the namespace of the reference it inverts."
  [[k & more]]
  (let [props (when (map? (first more)) (first more))
        ref   (:lexicon/ref props)
        many  (:lexicon/many props)]
    (cond-> {:key k
             :kind (cond ref "ref" many "many" (:lexicon/derived props) "derived" :else "stored")}
      ref                       (assoc :target (name ref))
      (qualified-keyword? many) (assoc :target (namespace many))
      (and ref (:lexicon/parent props)) (assoc :parent? true))))

(defn- attribute-id [k] (str k))
(defn- entity-id [n] (str ":" n))

(defn- vocabulary-roots
  "The Entity and Attribute roots of the entity declarations `decls`, and the set of attribute
   keywords they declare. A reference to an entity the vocabulary does not declare keeps its
   kind and loses its target: the model has no node to point it at, and Lexicon's own check
   refuses such a vocabulary before any code runs."
  [decls]
  (let [declared (set (map :entity decls))
        facts    (into {} (map (fn [d] [(:entity d) (mapv attribute-fact (:entries d))])) decls)
        parts    (reduce (fn [m [e fs]]
                           (reduce (fn [m {:keys [parent? target]}]
                                     (if (and parent? (declared target))
                                       (update m target (fnil conj []) e)
                                       m))
                                   m fs))
                         {} facts)]
    {:attributes (set (mapcat #(map :key %) (vals facts)))
     :roots
     (concat
      (for [{:keys [entity doc]} decls]
        [(entity-id entity)
         (sub/->InstanceValue
          ::Entity entity doc nil
          (cond-> [{:rk :attribute :card :many
                    :targets (mapv (comp sub/->Ref attribute-id :key) (facts entity))}]
            (seq (parts entity))
            (conj {:rk :part :card :many
                   :targets (mapv (comp sub/->Ref entity-id) (sort (distinct (parts entity))))}))
          false)])
      (for [fs (vals facts), {:keys [key kind target]} fs]
        [(attribute-id key)
         (sub/->InstanceValue
          ::Attribute (subs (str key) 1) nil {:val/kind kind}
          (cond-> []
            (declared target)
            (conj {:rk :refers-to :card :optional :targets [(sub/->Ref (entity-id target))]}))
          false)]))}))

;; ── reading the views ────────────────────────────────────────────────────────

(defn- require-map
  "What the symbols of a file's `ns` form resolve to: `{:alias {a ns} :refer {sym ns}}`, from its
   `:require` libspecs. Prefix lists are not followed — a spelling long out of use, and missing
   one costs a view, never invents one."
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

(defn- inside
  "What a walk of read source descends into — the wrappers a permissive read leaves in place of a
   reader conditional or a tagged literal are not collections, and carry what they wrap on `:form`."
  [form]
  (cond
    (reader-conditional? form) [(:form form)]
    (tagged-literal? form)     [(:form form)]
    (coll? form)               (seq form)))

(defn- view-calls
  "Every call form inside `form` whose head resolves, through `requires`, to one of the `makers`.
   A maker passed as a value makes no view anybody can read here, and is not a call."
  [makers {:keys [alias refer]} this-ns form]
  (let [resolved (fn [head]
                   (when (symbol? head)
                     (if-let [a (namespace head)]
                       (symbol (str (get alias (symbol a) a)) (name head))
                       (symbol (str (get refer head this-ns)) (name head)))))]
    (->> (tree-seq inside inside form)
         (filter #(and (seq? %) (makers (resolved (first %))))))))

(defn- selected
  "The vocabulary attributes a view call mentions, in name order."
  [attributes call]
  (->> (tree-seq inside inside (rest call))
       (filter attributes)
       distinct
       (sort-by str)))

(defn- view-roots
  "The View roots declared in the file whose forms are `forms`. A view is named for the top-level
   var its call sits in; a second call in the same form, or a call in a form that binds no var,
   is told apart by the line it starts on, since a root needs an id of its own."
  [makers attributes forms]
  (when-let [nsf (ns-form forms)]
    (let [this-ns  (second nsf)
          requires (require-map nsf)]
      (for [form forms
            :when (seq? form)
            :let  [bound (when (and (symbol? (second form))
                                    (str/starts-with? (str (first form)) "def"))
                           (second form))
                   calls (view-calls makers requires this-ns form)]
            [i call] (map-indexed vector calls)
            :let  [line  (:line (meta call))
                   vname (cond (and bound (zero? i)) (str bound)
                               bound                 (str bound "@" line)
                               :else                 (str this-ns "@" line))]]
        [(str "view:" this-ns "/" vname)
         (sub/->InstanceValue
          ::View vname nil nil
          [{:rk :selects :card :set
            :targets (mapv (comp sub/->Ref attribute-id) (selected attributes call))}
           {:rk :declared-in :card :one :targets [(sub/->Ref (str this-ns))]}]
          false)]))))

;; ── the producer ─────────────────────────────────────────────────────────────

(defn extract-roots
  "The Lexicon FACTS under `paths`, as `{:roots [[id InstanceValue]…]}` — or no roots when
   `*sources*` names none. Its views point at namespaces by name, so these roots are merged with
   the Clojure extractor's over the same paths (`fukan.infra.model` does that).

   Only files that could hold a declaration are read: one whose text names a vocabulary
   namespace or the namespace of a view maker. A VOCABULARY file that will not read throws —
   an entity missing from the model would make every view of it read as selecting nothing, and
   nothing would say why. A file that merely might declare a view is skipped instead, as the
   Clojure extractor skips a method body it cannot bound: the loss is a view, stated here."
  [paths]
  (let [{:keys [vocabulary view]} *sources*
        vocabulary (mapv str vocabulary)
        makers     (set view)]
    (if (and (empty? vocabulary) (empty? makers))
      {:roots []}
      (let [mentions (distinct (concat vocabulary (map namespace makers)))
            read     (for [path paths
                           f    (source-files path)
                           :when (let [text (slurp f)] (some #(str/includes? text %) mentions))
                           :let  [forms (extraction/read-forms (str f))
                                  n     (some-> forms ns-form second str)]]
                       {:file (str f) :forms forms :ns n})
            lost     (filter #(and (nil? (:forms %))
                                   (some (fn [v] (str/includes? (:file %) (str/replace (str/replace v "." "/") "-" "_")))
                                         vocabulary))
                             read)
            _        (when (seq lost)
                       (throw (ex-info (str "the Lexicon producer could not read "
                                            (str/join ", " (map :file lost)))
                                       {:unreadable (mapv :file lost)})))
            decls    (for [{:keys [forms ns]} read
                           :when (and ns (under? vocabulary ns))
                           form  forms
                           :let  [d (entity-declaration form)]
                           :when d]
                       d)
            {:keys [attributes roots]} (vocabulary-roots decls)]
        {:roots (vec (concat roots
                             (mapcat #(view-roots makers attributes (:forms %)) read)))}))))

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

(defn attribute-readers
  "The views selecting the attribute named `attribute` (`\"group/name\"`), as a set of
   `[namespace view]` name pairs — where one piece of the domain's knowledge is asked for."
  [db attribute]
  (set (cq/q '[:find ?nn ?vn :in $ % ?an
               :where (is ?a ::Attribute) [?a :entity/name ?an]
                      (selects ?v ?a) (declared-in ?v ?ns)
                      [?v :entity/name ?vn] [?ns :entity/name ?nn]]
             db (s/vocab-rules) attribute)))
