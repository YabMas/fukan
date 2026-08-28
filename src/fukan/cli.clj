(ns fukan.cli
  "The non-REPL entry: what the REPL cockpit does, for a PROGRAM reading it.

   The REPL's helpers print for a human who has the model open. A consuming project has neither
   — an agent harness composing a briefing, a landing gate, a review stage — and every one of
   them would otherwise reach past this into `law/check` and re-derive what fukan already says.

   Two verbs, because a reader arrives with two different questions:

     describe   what has this project DECLARED?    — the design, as its authored forms
     check      does the code still OBEY it?       — the violations, as data

   Usage:
     clojure -M:fukan -m fukan.cli describe [--spec-dirs canvas]
     clojure -M:fukan -m fukan.cli check --src src [--spec-dirs canvas] [--format edn|text]

   `describe` takes no `--src` and that is the point: a declared design is what the project
   SAID, and extraction is what the code turned out to be. Skipping it is not an optimisation
   (though it is the difference between 40ms and 8s) — a design document that changed when the
   code changed would not be a declaration.

   Three exit codes, because a consumer must distinguish two failures that look alike from
   the outside:

     0  satisfied — every law holds
     1  UNSATISFIED — laws fired, and the offenders are on stdout
     2  UNDECIDABLE — a law would not compile, the specs would not load, extraction blew up.
        Fukan is fail-closed about this (`law/check` throws rather than returning a green
        list), and so is this: a harness that read 2 as 0 would wave through exactly the
        branch that broke the checker."
  (:require [clojure.pprint :as pp]
            [fukan.canvas.ingestion.canvas-source :as canvas-source]
            [fukan.canvas.projection.design :as design]
            [fukan.canvas.projection.instance :as inst]
            [fukan.cozo.law :as law]
            [fukan.cozo.query :as cq]
            [fukan.infra.model :as infra-model]
            [fukan.model.pipeline :as pipeline]))

(defn- parse-args
  "The flags, as a map. Unknown flags are an ERROR rather than a shrug: a harness that
   misspells `--spec-dirs` would otherwise silently check the default directory and report a
   clean model it never looked at."
  [args]
  (loop [args args, out {:src "src" :spec-dirs ["canvas"] :format :edn}]
    (if-let [[flag value & more] (seq args)]
      (case flag
        "--src"       (recur more (assoc out :src value))
        "--spec-dirs" (recur more (assoc out :spec-dirs (vec (.split ^String value ","))))
        "--format"    (recur more (assoc out :format (keyword value)))
        (throw (ex-info (str "unknown flag " flag) {:flag flag})))
      out)))

(defn- offender-name
  "Name one offender eid. A named entity answers with its `:entity/name`; a `^:value` node has
   none, so it answers with its eid — enough to correlate, and honest that there is no name."
  [db eid]
  (or (:entity/name (cq/entity db eid)) (str eid)))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
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
                        true (assoc :offenders (mapv #(mapv (partial offender-name db) %)
                                                     offenders)))))})

(defn- check-verb
  "Build the model under `spec-dirs` from `src` and check it — the whole fallible half, so
   `-main` is left with nothing but rendering and an exit code."
  [{:keys [src spec-dirs]}]
  ;; stdout is the REPORT; everything the build narrates (`load-model`'s summary line, an
  ;; extractor's warning) goes to stderr, or a consumer parsing stdout reads prose where it
  ;; expected data.
  (binding [canvas-source/*spec-dirs* spec-dirs, *out* *err*]
    (let [db         (infra-model/load-model src)
          violations (law/check db)]
      (assoc (findings db violations) :db db :raw violations))))

(defn- describe-verb
  "Render the project's declared design. No code root: a declaration is what the project SAID,
   and a design document that moved when the code moved would not be one."
  [{:keys [spec-dirs]}]
  (binding [canvas-source/*spec-dirs* spec-dirs]
    {:ok true :text (design/design-text (pipeline/build-model nil))}))

(defn ^{:malli/schema [:=> [:cat [:sequential :string]] :nil]}
  -main
  [& args]
  (let [[verb & flags] args
        opts   (try (parse-args flags) (catch Throwable t {:failed t}))
        result (cond
                 (:failed opts)
                 {:undecidable true :error (.getMessage ^Throwable (:failed opts))}

                 (not (#{"check" "describe"} verb))
                 {:undecidable true
                  :error (str "unknown verb " (pr-str verb) " — expected `check` or `describe`")}

                 :else
                 (try (if (= "describe" verb) (describe-verb opts) (check-verb opts))
                      (catch Throwable t
                        {:undecidable true
                         :error       (.getMessage t)
                         :because     (mapv :law (:unsupported (ex-data t)))})))
        report (dissoc result :db :raw :text)]
    (cond
      (:undecidable result)
      (do (binding [*out* *err*] (println "fukan UNDECIDABLE:" (:error result)))
          (pp/pprint report)
          (System/exit 2))

      (:text result)
      (do (println (:text result)) (System/exit 0))

      (= :text (:format opts))
      (do (println (inst/violations-text (:db result) (:raw result)))
          (System/exit (if (:ok result) 0 1)))

      :else
      (do (pp/pprint report)
          (System/exit (if (:ok result) 0 1))))))
