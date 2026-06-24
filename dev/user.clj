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
            [fukan.cozo.query :as cq]
            [fukan.infra.model :as infra-model]
            [fukan.canvas.core.structure :as structure]
            [fukan.canvas.projection.finding :as pf]
            [fukan.canvas.projection.grammar :as gram]
            [fukan.canvas.projection.instance :as inst]
            [fukan.canvas.projection.architecture :as arch]
            [fukan.canvas.projection.probes :as probe]
            [fukan.model.materialize :as mat]
            ;; loads the model↔code correspondence laws into the dev session so a
            ;; `check`/`(drift)` over the unified held model surfaces drift
            [canvas.vocab.code.operation :as operation]
            [canvas.vocab.fukan :as fukan]
            [canvas.vocab.code.module :as code-module]
            [canvas.vocab.code.effect :as code-effect]
            [canvas.vocab.code.subsystem :as la]))

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
   Pass a namespace string for one vocabulary: (grammar \"lib.code\")."
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
  (if-let [m (infra-model/get-cozo)]
    (let [d (operation/drifted-operations m)]
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
  (if-let [m (infra-model/get-cozo)]
    (let [w (operation/uncovered-public-operations m)]
      (if (empty? w)
        (println "Fully encapsulated — every unmodelled function is private.")
        (let [by-mod (->> (cq/q '[:find ?on ?kmn
                                 :where [?o :structure/of :canvas.vocab.code.operation/Operation] [?o :val/extracted true]
                                        [?o :entity/name ?on] (not [?o :val/private true])
                                        [?kr :rel/kind :child] [?kr :rel/from ?km] [?kr :rel/to ?o] [?km :entity/name ?kmn]]
                               m)
                          (filter (fn [[on _]] (contains? w on)))
                          (group-by second))]
          (println "Encapsulation worklist —" (count w) "public functions with no model twin:")
          (doseq [[mn ops] (sort-by key by-mod)]
            (println (format "  %-42s %s" mn (str/join ", " (sort (map first ops)))))))))
    (println "No model loaded yet. Use (go) first.")))

(defn deps
  "Print fukan's complete module→module dependency graph (calls ∪ data-adoption), one edge per line —
   the objective backbone to reason a clean organization against. (Reads the Cozo model — migrated.)"
  []
  (if-let [c (infra-model/get-cozo)]
    (doseq [[a b] (sort (code-module/module-dependencies c))]
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

(defn boundaries
  "Bottom-up latent-boundary DISCOVERY (interface segregation, Parnas/ISP made mechanical): code modules
   whose PUBLIC surface has split into ≥2 consumer-DISJOINT clienteles — a sub-interface that has
   crystallized with its own external clientele but that no formal contract names. For each, the
   discovered sub-interface(s) and the clientele each is captured by. A SIGNAL for judgment (it detects
   the crystallized seam; you decide whether it deserves a formal split), count-invariant — a clientele
   can grow (a 2nd dialect) and the seam stays visible. Empty ⇔ no module's public surface has split."
  []
  (if-let [m (infra-model/get-model)]
    (let [lb (la/latent-boundaries m)]
      (if (empty? lb)
        (println "No latent boundaries — no module's public surface splits into disjoint clienteles.")
        (doseq [[km bundles] lb]
          (println km)
          (doseq [b bundles]
            (println (format "  ⊣ sub-interface: %s" (str/join ", " (:ops b))))
            (println (format "    clientele:     %s" (str/join ", " (:clientele b))))))))
    (println "No model loaded yet. Use (go) first.")))

(defn purity
  "The EFFECT SURFACE: extracted operations that DIRECTLY perform a consequential effect
   (:io/:state/:require — logging excluded), grouped by code module. Cross-reference (architecture)
   for each module's region: a consequential effect in a meant-to-be-pure region is the
   design-attention signal. (The `purity` lens's read, printed by region.)"
  []
  (if-let [m (infra-model/get-model)]
    (let [rows (d/q '[:find ?mn ?on ?en
                      :where [?o :structure/of :canvas.vocab.code.operation/Operation] [?o :val/extracted true] [?o :entity/name ?on]
                             [?pr :rel/from ?o] [?pr :rel/kind :performs] [?pr :rel/to ?e] [?e :val/name ?en]
                             [(not= ?en "throws")]
                             [?cr :rel/kind :child] [?cr :rel/from ?md] [?cr :rel/to ?o] [?md :entity/name ?mn]]
                    m)]
      (if (empty? rows)
        (println "No effect surface — no extracted op performs a consequential effect.")
        (let [by-mod (group-by first rows)]
          (println "Effect surface —" (count (set (map (juxt first second) rows)))
                   "effectful op(s) in" (count by-mod) "module(s):")
          (doseq [[mn rs] (sort-by key by-mod)]
            (println (format "  %s" mn))
            (doseq [[on ers] (sort-by key (group-by second rs))]
              (println (format "    %-30s %s" on (str/join " " (sort (set (map #(nth % 2) ers)))))))))))
    (println "No model loaded yet. Use (go) first.")))

(defn totality
  "The TOTALITY worklist — trusted-core READER operations (their modelled `:in` is the Model /
   StructureDb) whose realizing code is PARTIAL (performs :throws). Parse-don't-validate says the
   trusted core must be total. Empty ⇔ the trusted core is total — the property the enforced
   `Totality` law asserts."
  []
  (if-let [m (infra-model/get-cozo)]
    (let [w (fukan/totality-violations m)]
      (if (empty? w)
        (println "Trusted core is total — no modelled reader's code throws.")
        (do (println "Totality worklist —" (count w) "trusted-core reader(s) whose code throws:")
            (doseq [on (sort w)] (println "  " on)))))
    (println "No model loaded yet. Use (go) first.")))

(defn effect-drift
  "EFFECT-LANGUAGE DRIFT: per modelled op, where authored :performs disagrees with the extracted twin's
   TRANSITIVE effect profile. :undeclared (code reaches it, design silent — the enforced law direction)
   is listed first; then :phantom (declared, not reached — soft: taxonomy gap or stale intent)."
  []
  (if-let [m (infra-model/get-cozo)]
    (let [drift (code-effect/effect-drift m)
          u (filter (fn [[_ d]] (seq (:undeclared d))) drift)
          p (filter (fn [[_ d]] (seq (:phantom d))) drift)]
      (println "UNDECLARED — code reaches an effect the design never declared (the law worklist):")
      (doseq [[on d] (sort-by key u)] (println (format "  %-26s %s" on (sort (:undeclared d)))))
      (println "\nPHANTOM — design declares an effect the code doesn't reach (taxonomy gap or stale):")
      (doseq [[on d] (sort-by key p)] (println (format "  %-26s %s" on (sort (:phantom d))))))
    (println "No model loaded yet. Use (go) first.")))

(defn throw-spread
  "Partiality spread: ops that THROW directly (mostly ① parse-edge validators) vs ops that reach :throws
   only TRANSITIVELY via a call (the ② propagation surface that containment would collapse)."
  []
  (if-let [m (infra-model/get-cozo)]
    (let [{:keys [direct transitive-only]} (code-effect/throw-spread m)]
      (println "DIRECT throwers (" (count direct) "):" (str/join " " (sort direct)))
      (println "\nTRANSITIVE-only (" (count transitive-only) "):" (str/join " " (sort transitive-only))))
    (println "No model loaded yet. Use (go) first.")))

(defn probes
  "Run the implemented probes against the held model, printing each finding."
  []
  (if-let [m (infra-model/get-model)]
    (doseq [[nm finding] (probe/run-all m)]
      (println (str "── probe " nm " ──"))
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
  (grammar)
  (grammar "lib.code")
  (show 'probe)
  (focus '[(Operation ?n) (in-module ?n "materialize")])
  (check)
  (drift)
  (probes)
  (dispatch)
  (materialize "target.clojure")
  (status))
