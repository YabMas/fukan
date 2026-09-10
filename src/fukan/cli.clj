(ns fukan.cli
  "The non-REPL entry: what the REPL cockpit does, for a PROGRAM reading it.

   The REPL's helpers print for a human who has the model open. A consuming project has neither
   — an agent harness composing a briefing, a landing gate, a review stage — and every one of
   them would otherwise reach past this into `law/check` and re-derive what fukan already says.

   Three verbs, because a reader arrives with three different questions:

     describe   what has this project DECLARED?    — the design, as its authored forms
     check      does the code still OBEY it?       — the violations, as data
     report     how far from obeying is this part? — the counts, and never a verdict

   Usage:
     clojure -M:fukan -m fukan.cli describe [--spec-dirs canvas] [--format index|prose|forms]
                                            [--select '[(Band ?n)]']
     clojure -M:fukan -m fukan.cli check --src src [--spec-dirs canvas] [--format edn|text]
     clojure -M:fukan -m fukan.cli report --src src [--spec-dirs canvas]
                                          [--format count|edn|text] [--select '[(Band ?n)]']

   `check` and `report` ask the same question of the same whole-model check and do opposite things
   with the answer, and the split is the point. You may scope a report; you may not scope a
   verdict. A `check` narrowed to a region would read green while violations sat outside it, so
   `check` takes no `--select` and `report` never answers 1 — a project mid-migration can run it
   in CI, print the number, and not fail the build until the number is zero.

   `report` partitions violation ROWS three ways — in scope, out of scope, and the rows a
   node-selection cannot adjudicate — and the three sum to the whole. A count that quietly dropped
   what it could not decide would be a scoped verdict wearing a report's clothes.

   A selection names the OFFENDERS, which for most laws are the elements rather than the grouping
   they sit in. So scoping to a region means selecting that region's CONTENTS — the same
   composition `describe --select` takes, and the reason `path` is in the language:

     --select '[(path ?m [:+ :child] ?n) (Module ?m) (named ?m \"infra-model\")]'

   `[(Module ?n) (named ?n \"infra-model\")]` selects the module NODE, and no law reports a module
   as its offender, so it counts nothing.

   `describe --format index` is the way IN: the sorts, their counts, and the selection that
   fetches each. A reader who has to read everything to find out what to read has not been given
   one, and `--select '[(Band ?n)]'` presupposes knowing a Band exists.

   `describe` takes `--select` — datalog `:where` clauses binding `?n` — because a whole design
   is the right answer only while a project has a small one. Asking for `[(Band ?n)]` yields the
   architecture without the element detail underneath it, and the concepts section narrows with
   it. A consumer that truncates instead is not selecting, it is losing the end of the document.

   `describe` takes no `--src` and that is the point: a declared design is what the project
   SAID, and extraction is what the code turned out to be. Skipping it is not an optimisation
   (though it is the difference between 40ms and 8s) — a design document that changed when the
   code changed would not be a declaration. Handing it one is refused rather than ignored: which
   flags a verb can consume is declared in `verb-flags`, and a flag with no reader is a question
   the answer was never going to address.

   That is also where `check` gets its refusal of `--select`. A scoped verdict is the failure the
   three exit codes exist to prevent — a harness handed a green model for a region it never
   checked — and it is not a special case, it is `check` having no reader for the flag.

   Three exit codes, because a consumer must distinguish two failures that look alike from
   the outside:

     0  satisfied — every law holds
     1  UNSATISFIED — laws fired, and the offenders are on stdout
     2  UNDECIDABLE — a law would not compile, the specs would not load, extraction blew up,
        the report would not render. Fukan is fail-closed about this (`law/check` throws
        rather than returning a green list), and so is this: a harness that read 2 as 0 would
        wave through exactly the branch that broke the checker.

   That last case is why the render sits inside `run` and not in `-main`: a throw on the way
   out escapes to the JVM, whose own exit code is 1 — a violation announced by a checker that
   never found one."
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [fukan.canvas.ingestion.canvas-source :as canvas-source]
            [fukan.canvas.projection.design :as design]
            [fukan.canvas.core.lens :as lens]
            [fukan.canvas.projection.instance :as inst]
            [fukan.cozo.law :as law]
            [fukan.infra.model :as infra-model]
            [fukan.model.pipeline :as pipeline]))

(def ^:private flag-spelling
  "Every flag, keyword to the argv token — the one place a flag's name is written down, so a
   refusal can name it back to the caller in the form they typed."
  {:src "--src" :spec-dirs "--spec-dirs" :format "--format" :select "--select"})

(def ^:private verb-flags
  "What each verb can CONSUME. A flag outside its verb's set is refused, because a verb that
   silently ignored one would answer a question nobody asked: `describe --src …` reads as a
   declaration checked against that source root, and it is not — a declaration is what the
   project SAID, and describe never opens the code."
  {"describe" #{:spec-dirs :format :select}
   "check"    #{:src :spec-dirs :format}
   "report"   #{:src :spec-dirs :format :select}})

(def ^:private format-flags
  "Flags the answer a `--format` names cannot consume, whatever its verb takes. The index
   describes the WHOLE design however narrow the question that follows — an index of a selection
   could not tell you what you had not already asked for — so a selection it silently widened
   would be a promise it cannot keep."
  {:index #{:select}})

(defn- parse-args
  "The flags GIVEN, as a map — no defaults, so a verb can tell a flag it was handed from one it
   was not. Unknown flags are an ERROR rather than a shrug: a harness that misspells
   `--spec-dirs` would otherwise silently check the default directory and report a clean model it
   never looked at."
  [args]
  (loop [args args, out {}]
    (if-let [[flag value & more] (seq args)]
      (case flag
        "--src"       (recur more (assoc out :src value))
        "--spec-dirs" (recur more (assoc out :spec-dirs (vec (.split ^String value ","))))
        "--format"    (recur more (assoc out :format (keyword value)))
        "--select"    (recur more (assoc out :select (edn/read-string value)))
        (throw (ex-info (str "unknown flag " flag) {:flag flag})))
      out)))

(defn- unconsumable
  "The message refusing the first flag `verb` cannot consume, or nil when every flag given has a
   reader. Two ways to be unconsumable, and the caller cannot tell them apart from the outside:
   the verb never takes the flag, or the answer its `--format` names has no use for it.

   Presence is what counts, never the VALUE — `--select nil` reads as a request to scope and is
   refused as one. Keying off truthiness let it through to a whole-model verdict, which is the
   belief this refusal exists to prevent."
  [verb opts]
  (let [takes (verb-flags verb)
        fmt   (:format opts)]
    (or (when-let [k (first (remove takes (keys opts)))]
          (str "`" (flag-spelling k) "` is not a flag `" verb "` takes"))
        (when-let [k (first (filter (get format-flags fmt #{}) (keys opts)))]
          (str "`" (flag-spelling k) "` is not a flag `--format " (name fmt) "` can consume")))))

(def ^:private default-format
  "What a verb answers with when the caller names no `--format`. `report` answers with the counts
   alone, because that is what it is for and because naming offenders is the expensive half — a
   verb whose common use is a number in CI should not resolve every offender to get there."
  {"describe" :edn, "check" :edn, "report" :count})

(defn- with-defaults
  "The flags a verb reads, with what it was not given filled in. Defaults live HERE and not in the
   parser because the parser's job is to record what the caller typed: a `--src` seeded before
   anyone asked is indistinguishable from a `--src` the caller passed, and the refusal above
   turns on exactly that difference."
  [verb opts]
  (merge {:src "src" :spec-dirs ["canvas"] :format (default-format verb)} opts))

(defn- offender-name
  "Name one offender cell. An eid answers with its entity's name; a `^:value` node has none, so it
   answers with its eid — enough to correlate, and honest that there is no name. A cell that is
   not an eid at all is already its own name and answers with itself.

   That last case is not hypothetical: a law may bind any var as an offender, and the useful one
   is sometimes a VALUE. `every type-reference resolves to a modelled Kind` reports the NAME it
   could not resolve, because the anonymous Schema carrying it has an eid nobody can act on.
   Assuming every cell was an eid turned that finding into a Cozo error."
  [db x]
  (law/offender-label db x))

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  findings
  "The violations of the model held in `db`, as data: one entry per law that fired, its
   offenders NAMED rather than left as eids.

   Offenders stay TUPLES. A law whose offender var list is `[?a ?b]` — a band's undeclared
   dependency, say — carries the whole edge in each row, and flattening it to the first var
   would throw away the half that says what to do about it. `:vars` travels with them so a
   consumer can label the columns instead of printing four names and leaving the reader to
   guess which is which; a law that names its offender vars well is legible downstream."
  [db violations]
  {:ok         (empty? violations)
   :violations (vec (for [{:keys [structure law key vars offenders]} violations]
                      (cond-> {:structure structure :law law}
                        key  (assoc :key key)
                        vars (assoc :vars (mapv str vars))
                        ;; sorted by what is PRINTED, not by eid: Cozo returns rows in engine
                        ;; order and an eid does not survive a rebuild, so neither could carry a
                        ;; reader from one run's report to the next
                        true (assoc :offenders (->> offenders
                                                    (mapv #(mapv (partial offender-name db) %))
                                                    (sort-by vec)
                                                    vec)))))})

(defn- row-scope
  "Which of a report's three buckets an offender ROW falls in, given `focus` (nil = the whole
   model). The FIRST cell decides, because that is the offender — the law engine pins a law's
   scope to its first offender var and the worklist readers take that column; the rest of the row
   is the context saying what to do about it.

   `:unadjudicable` is the row whose first cell is not a node at all. A law may bind any var as an
   offender and the useful one is sometimes a VALUE — an unresolvable type-reference reports the
   NAME it could not resolve, because the anonymous Schema carrying it has no eid anyone can act
   on. No node-selection can claim or disclaim such a row, and dropping it would let a scoped
   count read lower than the truth with nothing on stdout saying so."
  [focus row]
  (let [x (first row)]
    (cond (nil? focus)      :in
          (not (int? x))    :unadjudicable
          (contains? focus x) :in
          :else             :out)))

(defn ^{:malli/schema [:=> [:catn [:violations :any] [:focus [:maybe [:vector :Eid]]]] :any]}
  tally
  "`check`'s violations as COUNTS, per law and in total, partitioned against `focus` — a set of
   eids, or nil for the whole model. Three buckets that SUM to the whole: in scope, out of scope,
   and the rows a node-selection cannot adjudicate at all. Totality is the point. A scoped count
   that silently dropped what it could not decide would read green for a region while violations
   sat outside it, which is the failure fukan's three exit codes exist to prevent, one level down.

   Laws are identified by the (structure tag, description) PAIR and ordered by it. `:key` is the
   handle designed for this and it is optional: 8 of the 107 laws that run against fukan's own
   model carry one, so a tally grouped on it would address a fraction of what ran. The key still
   travels for the laws that have it."
  [violations focus]
  (let [rows (fn [v] (frequencies (map (partial row-scope focus) (:offenders v))))
        zero {:in 0 :out 0 :unadjudicable 0}
        laws (->> violations
                  (map (fn [{:keys [structure law key offenders]}]
                         (let [f (merge zero (rows {:offenders offenders}))]
                           (cond-> (assoc f :structure structure :law law
                                            :total (count offenders))
                             key (assoc :key key)))))
                  (sort-by (juxt (comp str :structure) :law))
                  vec)]
    {:counts (reduce (fn [acc l] (merge-with + acc (select-keys l [:in :out :unadjudicable :total])))
                     (assoc zero :total 0)
                     laws)
     :laws   laws}))

(defn- tally-text
  "A tally as the lines that go in a pull request — the totals, then one line per law that fired.
   Every count a reader might quote is on stdout without them re-deriving it from a row list."
  [{:keys [counts laws]} scoped?]
  (str/join "\n"
            (concat [(if scoped?
                       (format "%d in scope, %d outside, %d unadjudicable — %d in the whole model"
                               (:in counts) (:out counts) (:unadjudicable counts) (:total counts))
                       (format "%d %s in the whole model" (:total counts)
                               (if (= 1 (:total counts)) "violation" "violations")))]
                    (for [{:keys [structure law in total]} laws]
                      (format "  %4d/%-4d %s  [%s]" in total law (name structure))))))

(defn- check-verb
  "Build the model under `spec-dirs` from `src` and check it, leaving the render to `render`.

   Naming the offenders is the EDN report's OWN work, so the text format does not do it.
   `--format text` quotes each offender as its authored form and reads nothing `findings`
   computes; running it anyway was a wholly discarded pass — 111 seconds of a 900-namespace
   project's run, naming 4,924 cells that were never printed. The two paths stay honest about
   which one pays."
  [{:keys [src spec-dirs format]}]
  ;; stdout is the REPORT; everything the build narrates (`load-model`'s summary line, an
  ;; extractor's warning) goes to stderr, or a consumer parsing stdout reads prose where it
  ;; expected data.
  (binding [canvas-source/*spec-dirs* spec-dirs, *out* *err*]
    (let [db         (infra-model/load-model src)
          violations (law/check db)]
      (if (= :text format)
        {:ok (empty? violations) :db db :raw violations}
        (assoc (findings db violations) :db db :raw violations)))))

(defn- report-verb
  "Build the model under `spec-dirs` from `src`, check it, and MEASURE — the same whole-model
   check `check` runs, partitioned against the selection rather than decided by it.

   The check is whole-model and cannot be otherwise: no law takes a focus, so narrowing is
   presentation and never less work. That is what keeps today's scoped number and next month's
   the same measurement — a scope that changed which CozoScript ran would make the two
   incomparable, which is the one property a countdown needs."
  [{:keys [src spec-dirs select]}]
  (binding [canvas-source/*spec-dirs* spec-dirs, *out* *err*]
    (let [db         (infra-model/load-model src)
          violations (law/check db)
          focus      (when select (lens/focus-nodes db select))
          in-scope   (vec (for [v violations
                                :let [kept (filterv #(= :in (row-scope focus %)) (:offenders v))]
                                :when (seq kept)]
                            (assoc v :offenders kept)))]
      ;; the tally counts every row; only the in-scope ones go on to be named or quoted, which is
      ;; what keeps the naming pass proportional to what is printed rather than to the model
      {:tally    (tally violations focus)
       :scoped?  (some? select)
       :db       db
       :in-scope in-scope})))

(defn- describe-verb
  "Render the project's declared design. No code root: a declaration is what the project SAID,
   and a design document that moved when the code moved would not be one."
  [{:keys [spec-dirs format select]}]
  (binding [canvas-source/*spec-dirs* spec-dirs]
    (let [db (pipeline/build-model nil)]
      {:ok true
       :text (if (= :index format)
               ;; the index is a way IN, so it describes the WHOLE design however narrow the
               ;; question that follows — an index of a selection could not tell you what you
               ;; had not already asked for
               (design/design-index db)
               (design/design-text db (if (= :forms format) :forms :prose) select))})))

(defn- render
  "The verb's answer as the exact text to put on stdout, and whether the model holds:
   `{:ok :out}`. `:out` carries its own trailing newline.

   Under `run`'s `try` for the reason the ns docstring gives. What makes that a live risk rather
   than a theoretical one: naming an offender and quoting its authored form are reads over the
   model db, and they run for the FIRST time exactly when a check first goes red — so a project
   adopting laws meets this path at the moment it most needs the verdict to be true."
  [verb {:keys [format] :as opts}]
  (cond
    (= "describe" verb)
    {:ok true :out (str (:text (describe-verb opts)) "\n")}

    ;; a report is not a verdict, so it is `:ok` however many violations it counted. Only a law
    ;; that could not be evaluated stops it, and that arrives as a throw, not as a false bit.
    (= "report" verb)
    (let [{:keys [tally scoped? db in-scope]} (report-verb opts)
          head (tally-text tally scoped?)]
      {:ok  true
       :out (case format
              :count (str head "\n")
              :text  (str head "\n\n" (inst/violations-text db in-scope) "\n")
              (with-out-str (pp/pprint (assoc tally :violations (:violations (findings db in-scope))))))})

    :else
    (let [{:keys [ok db raw] :as result} (check-verb opts)]
      {:ok  ok
       :out (if (= :text format)
              (str (inst/violations-text db raw) "\n")
              ;; rendered to a string rather than streamed, so a report that fails half-way
              ;; leaves stdout clean instead of a truncated one under an exit 2
              (with-out-str (pp/pprint (dissoc result :db :raw))))})))

(defn- run
  "One invocation, decided but not yet printed: `{:code :out :error}`.

   Everything fallible lives here — parsing, the build, the check, and the render — so that
   every way this can fail lands on the same answer, and `-main` is left with a string, a
   stream, and an exit code."
  [args]
  (let [[verb & flags] args
        opts   (try (parse-args flags) (catch Throwable t {:failed t}))
        result (cond
                 (:failed opts)
                 {:undecidable true :error (.getMessage ^Throwable (:failed opts))}

                 (not (verb-flags verb))
                 {:undecidable true
                  :error (str "unknown verb " (pr-str verb) " — expected "
                              (str/join " or " (map #(str "`" % "`") (sort (keys verb-flags)))))}

                 :else
                 (if-let [refusal (unconsumable verb opts)]
                   {:undecidable true :error refusal}
                   (try (render verb (with-defaults verb opts))
                        (catch Throwable t
                          {:undecidable true
                           :error       (.getMessage t)
                           :because     (mapv :law (:unsupported (ex-data t)))}))))]
    (if (:undecidable result)
      {:code 2 :error (:error result) :out (with-out-str (pp/pprint result))}
      {:code (if (:ok result) 0 1) :out (:out result)})))

(defn ^{:malli/schema [:=> [:cat [:sequential :string]] :nil]}
  -main
  [& args]
  (let [{:keys [code out error]} (run args)]
    ;; the sentence goes to stderr and the report to stdout: a consumer parsing stdout must find
    ;; data there whatever happened, and a human wants the reason without reading edn for it
    (when error (binding [*out* *err*] (println "fukan UNDECIDABLE:" error)))
    (print out)
    ;; `print` does not flush the way `println` does, and `System/exit` skips the flush
    ;; `clojure.main` performs on the way out — an unflushed report is a verdict with no
    ;; evidence under it
    (flush)
    (System/exit code)))
