(ns fukan.canvas.projection.design
  "The DESIGN projection: a project's own declared design, rendered as one document.

   The other two print-duals answer narrower questions — `grammar` renders the vocabularies,
   `instance` renders nodes. This one answers the question a reader actually arrives with:
   *what has this project declared?* It is the two of them composed, plus the only part that
   needs deciding — what counts as the project's own.

   Two things are excluded, and neither is a heuristic.

   The META-GRAMMAR is excluded. Reflection mints a `Structure`/`Law`/`Vocabulary`/`Relation`/
   `Correspondence` node for every declaration in the model, always, so those nodes are present
   in every project and authored by none. They are the model describing itself, which is not
   what anyone means by their design.

   ANONYMOUS `^:value` nodes are excluded. They have no name because they are not addressable —
   the instance dual already renders them inline inside the slot that holds them, so listing
   them again at top level says the same thing twice and severs it from its owner.

   Everything else is the project's, and no namespace is inspected to decide it: a vocabulary
   appears here because the project INSTANTIATED something from it. A grammar nobody used is not
   part of a project's declared design, however loudly it was loaded."
  (:require [clojure.string :as str]
            [fukan.canvas.projection.grammar :as gram]
            [fukan.canvas.projection.instance :as inst]
            [fukan.canvas.projection.prose :as prose]
            [fukan.cozo.query :as cq]))

(def ^:private meta-grammar-ns
  "The reflection meta-grammar's namespace. Fukan's own constant, not a guess about what a
   project might call things."
  "fukan.canvas.core.reflect")

(defn ^{:malli/schema [:=> [:cat :StructureDb] :any]}
  declared-nodes
  "The eids of the project's own declared instances — named, and not the meta-grammar's —
   paired with their structure tag."
  [db]
  (->> (cq/q '[:find ?e ?t :where [?e :entity/name _] [?e :structure/of ?t]] db)
       (remove (fn [[_ t]] (str/starts-with? (str t) meta-grammar-ns)))
       vec))

(defn ^{:malli/schema [:=> [:cat :StructureDb] [:vector :string]]}
  declared-vocabularies
  "The namespaces of the vocabularies the project actually INSTANTIATED, sorted.

   Derived from the instances rather than from the registry, so a vocabulary that was merely
   loaded — fukan's shipped code grammar in a project that models nothing with it — does not
   appear in a document about what this project declared."
  [db]
  (->> (declared-nodes db)
       (map (fn [[_ t]] (namespace (keyword (str/replace (str t) #"^:" "")))))
       (remove nil?)
       distinct
       sort
       vec))

(defn- structures-of
  "The reified Structure nodes for the vocabularies `vocabs`, in the order the primer uses."
  [db vocabs]
  (vec (for [v vocabs
             s (->> (cq/q '[:find ?c ?n :in $ ?vn
                            :where [?v :structure/of :fukan.canvas.core.reflect/Vocabulary]
                                   [?v :entity/name ?vn]
                                   [?r :rel/from ?v] [?r :rel/kind :child] [?r :rel/to ?c]
                                   [?c :entity/name ?n]]
                          db v)
                    (sort-by second)
                    (map first))]
         s)))

(defn ^{:malli/schema [:=> [:cat :StructureDb :keyword] :Text]}
  design-text
  "The declared design as one document, in one of two registers.

   `:forms` renders the authored declarations — what was written, and what you would write to
   change it. `:prose` renders the same declarations as sentences, for a reader whose question
   is what the rules ARE rather than how they are spelled; an agent asked to obey a design
   should not have to learn an authoring notation to find out what it says.

   Either way the concepts come before the instances: an instance is a shape filled in, and a
   reader who has not seen the shape is reading a list of maps."
  [db register]
  (let [vocabs (declared-vocabularies db)
        eids   (mapv first (declared-nodes db))]
    (if (= :prose register)
      ;; No document TITLE: this renders into whatever the caller is composing — a briefing that
      ;; already announced the section, a file, a terminal — and a projection that titled itself
      ;; would be duplicating a heading its only readers have already written.
      (str/join "\n"
                (concat ["## The concepts\n"]
                        (map #(prose/structure-prose (gram/structure-form db %))
                             (structures-of db vocabs))
                        ["\n## What it declares\n"]
                        (map #(prose/instance-prose (inst/instance-form db %))
                             (sort-by #(:entity/name (cq/entity db %)) eids))))
      (str/join "\n\n"
                (concat (map #(gram/vocabulary-primer db % {:full? true}) vocabs)
                        (when (seq eids) [(inst/focus-text db eids)]))))))
