(ns fukan.canvas.projection.prose
  "The design in PROSE — the same declarations the form duals render, written as sentences.

   A form dual renders the AUTHOR's view: `{:may-depend [:* Band]}` is exactly what was written
   and exactly what you would write to change it. A reader who has to OBEY the design wants the
   other view — what the rule is — and making them infer it from a quantifier vector is asking
   them to learn an authoring notation to answer a question about their own code. That reader is
   usually an agent now, and an inference it makes cheaply is still an inference it can make
   wrongly.

   So: same declarations, different register. Nothing here queries the model — it is a second
   rendering of the forms `structure-form` and `instance-form` already produce, which is what
   keeps the two views from drifting into two different designs.

   The forms view remains the one to reach for when the declaration itself is what is being
   changed; a rule is easier to read as a sentence, and easier to edit as a form."
  (:require [clojure.string :as str]))

;; ── slot types, as phrases ───────────────────────────────────────────────────

(defn- names [targets]
  (let [ss (map str targets)]
    (case (count ss)
      1 (first ss)
      2 (str (first ss) " or " (second ss))
      (str (str/join ", " (butlast ss)) " or " (last ss)))))

(defn- drop-props
  "A slot's type expression without its options map — `[:? {:payload :q} :string]` is a
   quantifier over `:string`, and the payload is authoring detail, not part of the rule."
  [xs]
  (remove map? xs))

(defn ^{:malli/schema [:=> [:cat :any] :string]}
  type-phrase
  "One slot's type expression as a phrase: what may fill it, and how many.

   An unrecognised shape renders as itself rather than as an approximation. A refined scalar is
   a malli form and stays one — `[:enum \"a\" \"b\"]` says what it means, and paraphrasing the
   type dialect here would put a second, worse type language in the projection layer."
  [t]
  (cond
    (symbol? t)  (str "exactly one " t)
    (keyword? t) (str "exactly one " t)
    (vector? t)
    (let [[q & more] t
          more (drop-props more)]
      (case q
        :?    (str "at most one " (names more) " (optional)")
        :*    (str "any number of " (names more))
        :+    (str "one or more " (names more))
        ;; ordering is the default; the unordered case is what is worth remarking on
        :set  (str "any number of " (names more) ", unordered")
        ;; not a quantifier: a refined scalar, verbatim
        (str "a value matching " (pr-str t))))
    :else (pr-str t)))

;; ── a structure, as a paragraph ──────────────────────────────────────────────

(defn- doc-lines
  "A docstring re-indented under `indent`, its own continuation indent stripped so a
   paragraph reads as a paragraph wherever it is placed."
  [doc indent]
  (->> (str/split-lines doc)
       (map #(let [t (str/trim %)] (if (str/blank? t) "" (str indent t))))
       (str/join "\n")))

(defn ^{:malli/schema [:=> [:cat :any] :string]}
  structure-prose
  "One `defstructure` data form as prose: what the concept is, what every instance of it
   carries, and the rules that must hold of it.

   Law DESCRIPTIONS, not law bodies. A law's description is the rule stated for a reader; the
   datalog under it is how the rule is decided, which is the checker's business."
  [form]
  (let [[_ nm & more]  form
        [doc more]     (if (string? (first more)) [(first more) (rest more)] [nil more])
        slots          (first (filter map? more))
        laws           (filter #(and (seq? %) (= 'law (first %))) more)]
    (str "### " nm "\n"
         (when doc (str "\n" (doc-lines doc "") "\n"))
         (when (seq slots)
           (str "\nEvery " nm " carries:\n"
                (str/join "\n" (for [[k t] slots]
                                 (str "  - " (name k) " — " (type-phrase t))))
                "\n"))
         (when (seq laws)
           (str "\nThese must hold of every " nm ":\n"
                (str/join "\n" (for [[_ desc] laws] (str "  - " desc)))
                "\n")))))

;; ── an instance, as an entry ─────────────────────────────────────────────────

(defn- type-form?
  "A vector whose head is a KEYWORD is a type form — the dialect's — not a list of targets.

   The distinction is not cosmetic. A plural slot's value and a malli schema are both vectors, so
   without it a signature renders as its own elements joined by commas: `[:=> [:catn [:db
   CozoDb]] :any]` came out as \"signature: :=>, :catn, :db, CozoDb, :any\", which is not prose,
   it is a type form with the structure removed. Targets are named entities, labelled pairs, or
   literals; a keyword-headed vector is only ever written by the type dialect."
  [v]
  (and (vector? v) (keyword? (first v))))

(defn- value-phrase
  "One slot value as text. A labelled target `[label target]` keeps its label; an anonymous
   `^:value` node with a single slot renders as that slot's value, because a one-slot value node
   IS its value and naming its wrapper adds a word and no information.

   A type form renders VERBATIM. Paraphrasing it — \"takes a string and an optional map\" —
   would put a second, worse type language in the projection layer, which is the same reason
   `type-phrase` leaves a refined scalar alone."
  [v]
  (cond
    (and (seq? v) (map? (second v)) (= 1 (count (second v))))
    (pr-str (val (first (second v))))

    (seq? v)      (pr-str v)
    (type-form? v) (pr-str v)
    (vector? v)   (str/join ", " (map value-phrase v))
    :else         (pr-str v)))

(defn ^{:malli/schema [:=> [:cat :any] :string]}
  instance-prose
  "One instance data form as an entry: its name, what it is for, and what it declares."
  [form]
  (let [[tag nm & more] form
        [doc more]      (if (string? (first more)) [(first more) (rest more)] [nil more])
        slots           (first (filter map? more))]
    (str "**" nm "** (" tag ")\n"
         (when doc (str (doc-lines doc "  ") "\n\n"))
         (when (seq slots)
           (str (str/join "\n" (for [[k v] slots]
                                 (str "  " (name k) ": " (value-phrase v))))
                "\n")))))
