(ns user
  "Development helpers for REPL-driven workflow.

   The HTTP server + web explorer are PAUSED during the lean-kernel rebuild
   (parked under .paused/), so the server-lifecycle helpers are gone. The
   kernel feedback loop is now: build the model with `(go)`, query it
   with `cq/q`, run constraints — all in-process. `refresh` reloads code and
   rebuilds the held model; `status` reports the model."
  (:require [clojure.string :as str]
            [clj-reload.core :as reload]
            [fukan.cozo.query :as cq]
            [fukan.cozo.law :as law]
            [fukan.infra.model :as infra-model]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.projection.grammar :as gram]
            [fukan.canvas.projection.instance :as inst]
            [fukan.canvas.projection.architecture :as arch]
            [fukan.common.vocab.code.subsystem :as code-subsystem]))

(defonce ^:private _reload-init
  (reload/init {:dirs ["src" "dev"], :no-reload '#{user}}))

(defn- reload-code! []
  (let [result (reload/reload {:only :loaded})]
    (when (seq (:loaded result))
      (println "Reloaded:" (count (:loaded result)) "namespaces")
      (doseq [ns-sym (:loaded result)] (println " " ns-sym)))
    (when (seq (:unloaded result)) (println "Unloaded:" (:unloaded result)))
    result))

(defn go
  "Build the held model headlessly. Option: :src (default \"src\").
   (The web explorer is paused — parked under .paused/.)"
  [{:keys [src] :or {src "src"}}]
  (infra-model/load-model src))

(defn reset
  "Reload changed code, then rebuild the held model from the last src."
  []
  (reload-code!)
  (if (infra-model/get-src)
    (infra-model/refresh-model)
    (println "No model loaded yet. Use (go) first.")))

(defn refresh
  "Reload changed code + rebuild the held model. Use after editing a spec."
  []
  (reload-code!)
  (if (infra-model/get-src)
    (do (infra-model/refresh-model)
        (println "Refreshed."))
    (println "No model loaded yet. Use (go) first.")))

(defn architecture
  "Print the projected SYSTEM MAP — the canvas's front door: fukan's code-side architecture,
   its subsystems, their modules, and the :may-depend DAG, derived live from the held model.
   Read this instead of `ls canvas/` to grasp fukan's shape."
  []
  (if-let [m (infra-model/get-model)]
    (println (arch/architecture-overview m))
    (println "No model loaded yet. Use (go) first.")))

(defn grammar
  "Print the GRAMMAR PRIMER — every vocabulary in the held model rendered back as
   its map-form defstructures, live from the reified grammar (the print-dual).
   Pass a namespace string for one vocabulary: (grammar \"fukan.common.vocab.grouping\")."
  ([] (if-let [m (infra-model/get-model)]
        (println (gram/grammar-primer m))
        (println "No model loaded yet. Use (go) first.")))
  ([vocab-name]
   (if-let [m (infra-model/get-model)]
     (println (gram/vocabulary-primer m vocab-name))
     (println "No model loaded yet. Use (go) first."))))

(defn correspondence
  "Print the CORRESPONDENCE CARD — the design↔fact seam as one object: the twin ladder and every
   demand with its stable law key (the generated laws, visible and attributed). Registry-direct."
  []
  (println (gram/correspondence-card)))

(defn show
  "Print every model node named `n` (a string or symbol) as its AUTHORED form —
   the instance print-dual. The model talks back in the language you wrote it in:
   (show 'kernel) → (Subsystem kernel \"…\" {:child […] :may-depend []})."
  [n]
  (if-let [m (infra-model/get-model)]
    (let [eids (map first (cq/q '[:find ?e :in $ ?n :where [?e :entity/name ?n]]
                                m (name n)))]
      (if (empty? eids)
        (println "No node named" (pr-str (name n)))
        (println (inst/focus-text m eids))))
    (println "No model loaded yet. Use (go) first.")))

(defn focus
  "Evaluate datalog `clauses` (binding ?n, with the vocab rules) over the held
   model and print the focused nodes as their authored forms — the textual model
   explorer: (focus '[(Operation ?n) (within ?n \"core-structure\")])."
  [clauses]
  (if-let [m (infra-model/get-model)]
    (let [out (inst/focus-text m clauses)]
      (println (if (str/blank? out) "Empty focus." out)))
    (println "No model loaded yet. Use (go) first.")))

(defn check
  "Run every law over the held model and print the violations with each offender
   QUOTED as its authored form — the law that fired and the instance that fired
   it, side by side."
  []
  (if-let [m (infra-model/get-model)]
    (println (inst/violations-text m (law/check m)))
    (println "No model loaded yet. Use (go) first.")))

(defn drift
  "Model↔code drift in the held (unified) model: modelled Operations with no realizing
   function of the same name. Empty ⇔ the implementation fully realizes every
   modelled capability. (Build with a code-root — `(go)` defaults to \"src\" — so
   the held model carries the extracted code.)"
  []
  (if-let [m (infra-model/get-model)]
    (let [d (law/violation-names m :corresponds/Operation.total)]
      (if (empty? d)
        (println "No drift — every modelled Operation is realized in code.")
        (println "Drift —" (count d) "modelled Operation(s) with no realizing function:" (sort d))))
    (println "No model loaded yet. Use (go) first.")))

(defn encapsulation
  "The ENCAPSULATION worklist (the privacy-coverage iteration): PUBLIC extracted functions with no
   authored Operation twin — each an undeclared public surface demanding a decision (model it as
   intent, or make it `defn-`). Empty ⇔ every unmodelled function is genuinely private. Grouped by
   code namespace. (The private half of the coverage gap is settled by definition.)
   Named surfaces only: the `:corresponds/Operation.surjective` law key + the `public`/`within`
   defrelations — the fact stratum is the `Fn` theory, never a provenance flag on Operation."
  []
  (if-let [m (infra-model/get-model)]
    (let [w (law/violation-names m :corresponds/Operation.surjective)]
      (if (empty? w)
        (println "Fully encapsulated — every unmodelled function is private.")
        (let [by-ns (->> (cq/q '[:find ?on ?nn :in $ %
                                 :where (public ?o) (named ?o ?on) (within ?o ?nn)]
                               m (s/vocab-rules))
                         (filter (fn [[on _]] (contains? w on)))
                         (group-by second))]
          (println "Encapsulation worklist —" (count w) "public functions with no model twin:")
          (doseq [[nn ops] (sort-by key by-ns)]
            (println (format "  %-42s %s" nn (str/join ", " (sort (map first ops)))))))))
    (println "No model loaded yet. Use (go) first.")))

(defn deps
  "Print fukan's complete module→module dependency graph (calls ∪ data-adoption), one edge per line —
   the objective backbone to reason a clean organization against — then any DECLARED subsystem
   :may-depend edges the code does NOT realize (over-declaration: intended headroom or stale intent)."
  []
  (if-let [c (infra-model/get-model)]
    (do (doseq [[a b] (sort (code-subsystem/module-dependencies c))]
          (println (format "%-24s ⟶ %s" a b)))
        (let [unreal (code-subsystem/unrealized-dependencies c)]
          (if (empty? unreal)
            (println "\nEvery declared :may-depend edge is realized by code.")
            (do (println "\nDECLARED :may-depend edges NOT realized by code (over-declared — headroom/stale):")
                (doseq [[a b] (sort unreal)] (println (format "  %-16s ⇢ %s" a b)))))))
    (println "No model loaded yet. Use (go) first.")))

(defn purity
  "The EFFECT SURFACE: extracted functions that DIRECTLY perform a consequential effect
   (`:throws` excluded — partiality reads separately), grouped by code namespace.
   Cross-reference (architecture) for each module's region: a consequential effect in a
   meant-to-be-pure region is the design-attention signal. Named surfaces: the `Fn` kind
   rule + the `performs`/`within` relations (the effect's name is a scalar leaf)."
  []
  (if-let [m (infra-model/get-model)]
    (let [rows (cq/q '[:find ?nn ?on ?en :in $ %
                       :where (Fn ?o) (named ?o ?on) (performs ?o ?e)
                              [?e :val/name ?en] [(not= ?en "throws")]
                              (within ?o ?nn)]
                     m (s/vocab-rules))]
      (if (empty? rows)
        (println "No effect surface — no extracted function performs a consequential effect.")
        (let [by-ns (group-by first rows)]
          (println "Effect surface —" (count (set (map (juxt first second) rows)))
                   "world-effect function(s) in" (count by-ns) "namespace(s):")
          (doseq [[nn rs] (sort-by key by-ns)]
            (println (format "  %s" nn))
            (doseq [[on ers] (sort-by key (group-by second rs))]
              (println (format "    %-30s %s" on (str/join " " (sort (set (map #(nth % 2) ers)))))))))))
    (println "No model loaded yet. Use (go) first.")))

(defn type-drift
  "TYPE correspondence (model↔code): the ADHERENCE offenders — modelled Operations whose realizing
   code signature does NOT exactly match the modelled type. A GATED demand, so this also fires in
   (check); shown here as a focused worklist. The single `out↦out` map subsumes coverage: a twin that
   declares NO out where the design does is an offender too (the old type-coverage demand folded in)."
  []
  (if-let [m (infra-model/get-model)]
    (let [drifted (law/violation-names m :corresponds/Operation.agrees)]
      (println "ADHERENCE — modelled signature disagrees with the code's :malli/schema:")
      (if (empty? drifted)
        (println "  (none — every code signature exactly adheres to its modelled type)")
        (doseq [on (sort drifted)] (println "  " on))))
    (println "No model loaded yet. Use (go) first.")))

(defn status []
  (if-let [m (infra-model/get-model)]
    (println "Model:"
             (count (cq/q '[:find ?e :where [?e :structure/of _]] m)) "structures,"
             (count (cq/q '[:find ?r :where [?r :rel/kind _]] m)) "relations"
             (str "(src: " (infra-model/get-src) ")"))
    (println "Model: not loaded")))

(comment
  (go {})
  (reset)
  (refresh)
  (grammar)
  (grammar "fukan.common.vocab.grouping")
  (show 'kernel)
  (check)
  (drift)
  (encapsulation)
  (purity)
  (correspondence)
  (status))
