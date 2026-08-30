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
            [fukan.canvas.core.lens :as lens]
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

(defn ^{:malli/schema [:=> [:cat [:sequential :any]] [:vector :string]]}
  vocabularies-of
  "The namespaces of the vocabularies `nodes` INSTANTIATE, sorted.

   Derived from the instances rather than from the registry, so a vocabulary that was merely
   loaded — fukan's shipped code grammar in a project that models nothing with it — does not
   appear in a document about what this project declared.

   Takes the nodes rather than the db because a SELECTED document must narrow with them: asking
   for the bands and being handed the Operation grammar as well would be answering a question
   nobody asked, and the concepts section is the larger half of a small selection."
  [nodes]
  (->> nodes
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

(defn ^{:malli/schema [:=> [:cat :StructureDb :keyword [:? [:maybe :any]]] :Text]}
  design-text
  "The declared design as one document, in one of two registers, optionally narrowed.

   `:forms` renders the authored declarations — what was written, and what you would write to
   change it. `:prose` renders the same declarations as sentences, for a reader whose question
   is what the rules ARE rather than how they are spelled; an agent asked to obey a design
   should not have to learn an authoring notation to find out what it says.

   Either way the concepts come before the instances: an instance is a shape filled in, and a
   reader who has not seen the shape is reading a list of maps.

   `select` is datalog `:where` clauses binding `?n` — `[(Band ?n)]` for the architecture alone.
   A whole design is the right answer for a project that has one; it stops being the right answer
   at the size a real one reaches, and truncating it is not selection, it is losing the end.
   The concepts narrow WITH the instances, because a selection's grammar is the larger half of
   a small document and the vocabularies are derived from what was instantiated."
  ([db register] (design-text db register nil))
  ([db register select]
   (let [focus  (when select (lens/focus-nodes db select))
         nodes  (cond->> (declared-nodes db)
                  focus (filterv (comp focus first)))
         vocabs (vocabularies-of nodes)
         ;; (tag, name), not name alone: sorted by name a Module lands between two of its own
         ;; Operations, and the document reads as an index rather than as a design. The forms
         ;; register has always grouped this way — this is prose catching up.
         eids   (->> nodes
                     (sort-by (fn [[e t]] [(str t) (str (:entity/name (cq/entity db e)))]))
                     (mapv first))]
     (if (= :prose register)
       ;; No document TITLE: this renders into whatever the caller is composing — a briefing that
       ;; already announced the section, a file, a terminal — and a projection that titled itself
       ;; would be duplicating a heading its only readers have already written.
       (str/join "\n"
                 (concat ["## The concepts\n"]
                         (map #(prose/structure-prose (gram/structure-form db %))
                              (structures-of db vocabs))
                         ["\n## What it declares\n"]
                         (map #(prose/instance-prose (inst/instance-form db %)) eids)))
       (str/join "\n\n"
                 (concat (map #(gram/vocabulary-primer db % {:full? true}) vocabs)
                         (when (seq eids) [(inst/focus-text db eids)])))))))

(defn ^{:malli/schema [:=> [:cat :StructureDb] :Text]}
  design-index
  "The design's TABLE OF CONTENTS: every sort the project declared something of, how many, which
   vocabulary it comes from, and the selection that fetches it.

   What a selection is useless without. `--select '[(Band ?n)]'` presupposes knowing that a Band
   exists, and until now the only thing that said so was the whole document — the very thing a
   reader asks for a selection to avoid. A reader who must read everything to find out what to
   read has not been given a way in.

   So it teaches the selection language by example rather than describing it, and it reports the
   full document's size, because the first useful question is whether reading all of it is
   affordable at all. The cheapest thing this projection can answer, and the first thing an agent
   exploring an unfamiliar design should ask."
  [db]
  (let [rows  (->> (declared-nodes db)
                   (group-by second)
                   (map (fn [[t es]]
                          (let [k (keyword (str/replace (str t) #"^:" ""))]
                            {:sort (name k) :vocab (namespace k) :n (count es)})))
                   (sort-by (juxt :vocab :sort)))
        width (apply max 4 (map (comp count :sort) rows))
        line  (str "  %4d  %-" width "s  %s")]
    (if (empty? rows)
      ;; A project that declares nothing is the COMMON case, not a degenerate one — most projects
      ;; are not modelled, and this is the first thing an agent runs to find out which kind it is
      ;; in. Answering with an empty table and an example selection built from a sort that does
      ;; not exist told it there was something here and handed it a malformed query to find it.
      (str "## What this project declares\n\n"
           "  Nothing — this project declares no design.\n\n"
           "There is no model here to read, to select from, or to check code against.\n")
      (str "## What this project declares\n\n"
           (str/join "\n"
                     (for [{:keys [sort vocab n]} rows]
                       (str (format line n sort vocab)
                            (format "\n        --select '[(%s ?n)]'" sort))))
           "\n\nThe whole design is " (count (design-text db :prose)) " characters."
           "\nAsk for one sort to get less of it, or narrow by name:"
           "\n        --select '[(" (:sort (first rows)) " ?n) (named ?n \"...\")]'\n"))))
