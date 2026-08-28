(ns fukan.check
  "The non-REPL check entry: build a project's model and report its violations as DATA.

   `(check)` at the REPL prints for a human reading it. This prints for a PROGRAM reading it —
   an agent harness that wants to tell an agent it just broke the design, a landing gate, a
   review stage. Same laws, same `law/check`; only the rendering differs.

   Usage: clojure -M:fukan -m fukan.check --src src [--spec-dirs canvas] [--format edn|text]

   Three exit codes, because a consumer must distinguish two failures that look alike from
   the outside:

     0  every law holds
     1  the model is UNSATISFIED — laws fired, and the offenders are on stdout
     2  the check could not be DECIDED — a law would not compile, the specs would not load,
        extraction blew up. Fukan is fail-closed about this (`law/check` throws rather than
        returning a green list), and so is this: a harness that read 2 as 0 would wave through
        exactly the branch that broke the checker."
  (:require [clojure.pprint :as pp]
            [fukan.canvas.ingestion.canvas-source :as canvas-source]
            [fukan.canvas.projection.instance :as inst]
            [fukan.cozo.law :as law]
            [fukan.cozo.query :as cq]
            [fukan.infra.model :as infra-model]))

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
   would throw away the half that says what to do about it."
  [db violations]
  {:ok         (empty? violations)
   :violations (vec (for [{:keys [structure law key offenders]} violations]
                      (cond-> {:structure structure :law law}
                        key (assoc :key key)
                        true (assoc :offenders (mapv #(mapv (partial offender-name db) %)
                                                     offenders)))))})

(defn- run
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

(defn ^{:malli/schema [:=> [:cat [:sequential :string]] :nil]}
  -main
  [& args]
  (let [opts   (try (parse-args args) (catch Throwable t {:failed t}))
        result (cond
                 (:failed opts) {:undecidable true :error (.getMessage ^Throwable (:failed opts))}
                 :else (try (run opts)
                            (catch Throwable t
                              {:undecidable true
                               :error       (.getMessage t)
                               :because     (mapv :law (:unsupported (ex-data t)))})))
        report (dissoc result :db :raw)]
    (cond
      (:undecidable result)
      (do (binding [*out* *err*] (println "fukan check UNDECIDABLE:" (:error result)))
          (pp/pprint report)
          (System/exit 2))

      (= :text (:format opts))
      (do (println (inst/violations-text (:db result) (:raw result)))
          (System/exit (if (:ok result) 0 1)))

      :else
      (do (pp/pprint report)
          (System/exit (if (:ok result) 0 1))))))
