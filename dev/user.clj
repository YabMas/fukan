(ns user
  "Development helpers for REPL-driven workflow.

   The HTTP server + web explorer are PAUSED during the lean-kernel rebuild
   (parked under .paused/), so the server-lifecycle helpers are gone. The
   kernel feedback loop is now: build a store with `with-canvas`, query it
   with `d/q`, run constraints — all in-process. `refresh` reloads code and
   rebuilds the held model; `status` reports the model."
  (:require [clojure.string :as str]
            [clj-reload.core :as reload]
            [datascript.core :as d]
            [fukan.infra.model :as infra-model]
            [fukan.canvas.core.structure :as structure]
            [fukan.canvas.projection.finding :as pf]
            [fukan.canvas.projection.grammar :as gram]
            [fukan.canvas.projection.instance :as inst]
            [fukan.canvas.projection.overview :as overview]
            [fukan.canvas.projection.architecture :as arch]
            [fukan.canvas.projection.probes :as probe]
            [fukan.model.materialize :as mat]
            ;; loads the model↔code correspondence laws into the dev session so a
            ;; `check`/`(drift)` over the unified held model surfaces drift
            [fukan.target.correspondence :as corr]
            [fukan.descent :as descent]
            [lib.code :as code]))

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

(defn overview
  "Print the projected SYSTEM OVERVIEW — the canvas's front door: the faculty map
   (each tagged with the modules that realize it) + the flow loop, derived live from
   the held model. Read this instead of `ls canvas/` to grasp fukan's shape."
  []
  (if-let [m (infra-model/get-model)]
    (println (overview/system-overview m))
    (println "No model loaded yet. Use (go) first.")))

(defn architecture
  "Print fukan's code-side architecture: subsystems, their modules (faculty-annotated), and the
   :may-depend DAG — the design-aid companion to (overview)'s subject view."
  []
  (if-let [m (infra-model/get-model)]
    (println (arch/architecture-overview m))
    (println "No model loaded yet. Use (go) first.")))

(defn grammar
  "Print the GRAMMAR PRIMER — every vocabulary in the held model rendered back as
   its map-form defstructures, live from the reified grammar (the print-dual).
   Pass a namespace string for one vocabulary: (grammar \"canvas.subject\")."
  ([] (if-let [m (infra-model/get-model)]
        (println (gram/grammar-primer m))
        (println "No model loaded yet. Use (go) first.")))
  ([vocab-name]
   (if-let [m (infra-model/get-model)]
     (println (gram/vocabulary-primer m vocab-name))
     (println "No model loaded yet. Use (go) first."))))

(defn show
  "Print every model node named `n` (a string or symbol) as its AUTHORED form —
   the instance print-dual. The model talks back in the language you wrote it in:
   (show 'probe) → (Act probe \"…\" {:reads model …})."
  [n]
  (if-let [m (infra-model/get-model)]
    (let [eids (map first (d/q '[:find ?e :in $ ?n :where [?e :entity/name ?n]]
                               m (name n)))]
      (if (empty? eids)
        (println "No node named" (pr-str (name n)))
        (println (inst/focus-text m eids))))
    (println "No model loaded yet. Use (go) first.")))

(defn focus
  "Evaluate datalog `clauses` (binding ?n, with the vocab rules) over the held
   model and print the focused nodes as their authored forms — the textual model
   explorer: (focus '[(Operation ?n) (in-module ?n \"materialize\")])."
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
    (println (inst/violations-text m (structure/check m)))
    (println "No model loaded yet. Use (go) first.")))

(defn drift
  "Model↔code drift in the held (unified) model: modelled Operations with no realizing
   function of the same name. Empty ⇔ the implementation fully realizes every
   modelled capability. (Build with a code-root — `(go)` defaults to \"src\" — so
   the held model carries the extracted code.)"
  []
  (if-let [m (infra-model/get-model)]
    (let [d (corr/drifted-operations m)]
      (if (empty? d)
        (println "No drift — every modelled Operation is realized in code.")
        (println "Drift —" (count d) "modelled Operation(s) with no realizing function:" (sort d))))
    (println "No model loaded yet. Use (go) first.")))

(defn encapsulation
  "The ENCAPSULATION worklist (the privacy-coverage iteration): PUBLIC extracted functions with no
   authored Operation twin — each an undeclared public surface demanding a decision (model it as
   intent, or make it `defn-`). Empty ⇔ every unmodelled function is genuinely private. Grouped by
   code module. (The private half of the coverage gap is settled by definition.)"
  []
  (if-let [m (infra-model/get-model)]
    (let [w (corr/uncovered-public-operations m)]
      (if (empty? w)
        (println "Fully encapsulated — every unmodelled function is private.")
        (let [by-mod (->> (d/q '[:find ?on ?kmn
                                 :where [?o :structure/of :lib.code/Operation] [?o :val/extracted true]
                                        [?o :entity/name ?on] (not [?o :val/private true])
                                        [?kr :rel/kind :child] [?kr :rel/from ?km] [?kr :rel/to ?o] [?km :entity/name ?kmn]]
                               m)
                          (filter (fn [[on _]] (contains? w on)))
                          (group-by second))]
          (println "Encapsulation worklist —" (count w) "public functions with no model twin:")
          (doseq [[mn ops] (sort-by key by-mod)]
            (println (format "  %-42s %s" mn (str/join ", " (sort (map first ops)))))))))
    (println "No model loaded yet. Use (go) first.")))

(defn witnesses
  "The generative readings of the Source in-fold descent over the held model. Prints three lines:
   the carved design space (required polarities), the witness gap (slice 1 — every polarity
   realized), and the convergence gap (slice 2 — :into Model verifiably unifies every polarity).
   Empty gaps ⇔ the in-fold is fully realized."
  []
  (if-let [m (infra-model/get-model)]
    (let [req  (descent/required-witnesses m)
          wgap (descent/unwitnessed-polarities m)
          cgap (descent/unconverged-polarities m)]
      (println "Source in-fold — required (carve):" (sort req))
      (if (empty? wgap)
        (println "Source in-fold — witness gap: none — every polarity realized.")
        (println "Source in-fold — witness gap:" (sort wgap)))
      (if (empty? cgap)
        (println "Source in-fold — convergence gap: none — :into Model unifies every polarity.")
        (println "Source in-fold — convergence gap:" (sort cgap))))
    (println "No model loaded yet. Use (go) first.")))

(defn roles
  "Print fukan's modules grouped by realized subject concept (the design-aid role view): which
   modules realize each faculty, and which are infrastructure (no role)."
  []
  (if-let [m (infra-model/get-model)]
    (doseq [[role mods] (sort-by (comp str key) (code/modules-by-role m))]
      (println (format "%-32s %s" (str role) (str/join ", " (sort mods)))))
    (println "No model loaded yet. Use (go) first.")))

(defn deps
  "Print fukan's complete module→module dependency graph (calls ∪ data-adoption), one edge per line —
   the objective backbone to reason a clean organization against."
  []
  (if-let [m (infra-model/get-model)]
    (doseq [[a b] (sort (code/module-dependencies m))]
      (println (format "%-24s ⟶ %s" a b)))
    (println "No model loaded yet. Use (go) first.")))

(defn dispatch
  "Print fukan's modelled DISPATCH POINTS (Operations with a :dispatches-to fan-out) and the handlers
   each routes to — the explicit indirection seams, derived live from the held model."
  []
  (if-let [m (infra-model/get-model)]
    (doseq [[dpn hs] (->> (d/q '[:find ?dpn ?hn
                                 :where [?r :rel/kind :dispatches-to] [?r :rel/from ?dp] [?r :rel/to ?h]
                                        (not [?dp :val/extracted true])
                                        [?dp :entity/name ?dpn] [?h :entity/name ?hn]]
                               m)
                          (group-by first) (sort-by key))]
      (println (format "%-16s ⟶ %s" dpn (str/join ", " (sort (map second hs))))))
    (println "No model loaded yet. Use (go) first.")))

(defn probes
  "Run the implemented probes against the held model, printing each finding."
  []
  (if-let [m (infra-model/get-model)]
    (doseq [[nm finding] (probe/run-all m)]
      (println (str "── probe " nm " (gating " (:gating finding) ") ──"))
      (let [lines (pf/finding->text finding)]
        (if (empty? lines)
          (println "  (nothing)")
          (doseq [l lines] (println "  " l)))))
    (println "No model loaded yet. Use (go) first.")))

(defn materialize
  "Project module `module-name` from the held model under `projection` (default
   \"Blueprint\" — implementation specs; \"Docs\" — reference documentation): compose
   the render of every Stage that module owns. E.g. (materialize \"target.clojure\")
   or (materialize \"target.clojure\" \"Docs\")."
  ([module-name] (materialize module-name "Blueprint"))
  ([module-name projection]
   (if-let [m (infra-model/get-model)]
     (let [spec (mat/materialize-module m projection module-name)]
       (if (str/blank? spec)
         (println "No Stages found in module" (pr-str module-name))
         (println spec)))
     (println "No model loaded yet. Use (go) first."))))

(defn status []
  (if-let [m (infra-model/get-model)]
    (println "Model:"
             (count (d/q '[:find ?e :where [?e :structure/of _]] m)) "structures,"
             (count (d/q '[:find ?r :where [?r :rel/kind _]] m)) "relations"
             (str "(src: " (infra-model/get-src) ")"))
    (println "Model: not loaded")))

(comment
  (go {})
  (reset)
  (refresh)
  (overview)
  (grammar)
  (grammar "canvas.subject")
  (show 'probe)
  (focus '[(Operation ?n) (in-module ?n "materialize")])
  (check)
  (drift)
  (witnesses)
  (probes)
  (dispatch)
  (materialize "target.clojure")
  (status))
