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

(defn- unaccounted-public
  "PUBLIC extracted Clojure functions with no authored Operation twin — the coverage-gap SET
   `encapsulation` reports as a worklist and `correspondence` appends as a count after the card
   (factored here so neither duplicates the query — kernel-tier `correspondence-card` deliberately
   stays vocab-agnostic and never names `public` itself)."
  [m]
  (set (cq/q '[:find [?n ...] :in $ %
               :where
               (is ?fn :fukan.common.extraction.clojure.operation/Fn) (public ?fn)
               (not-join [?fn] (corresponds ?_op ?fn))
               [?fn :entity/name ?n]]
             m (s/vocab-rules))))

(defn correspondence
  "Print the CORRESPONDENCE CARD: every registered essential `(correspond …)` — its authored
   head/match/map form (registry-direct) — plus its live VOCAB-GENERIC coverage readings
   (unrealized/ambiguous), computed over the held model's `corresponds`/`realized-*` rules. Appends
   the unaccounted-public count — the same coverage-gap SET `encapsulation` reports as a worklist —
   since the card itself is kernel-tier and stays vocab-agnostic (it never names the `public`
   predicate; that is this project's business)."
  []
  (if-let [m (infra-model/get-model)]
    (do (println (gram/correspondence-card m))
        (println (str "unaccounted-public: " (count (unaccounted-public m)))))
    (println "No model loaded yet. Use (go) first.")))

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
   function — a READING of the `corresponds` pairing join (the generated `:corresponds/*`
   demand laws dissolved with the essential `correspond`). Empty ⇔ the implementation fully
   realizes every modelled capability; vacuous when no code is extracted (the `Fn` gate).
   (Build with a code-root — `(go)` defaults to \"src\" — so the held model carries the code.)"
  []
  (if-let [m (infra-model/get-model)]
    (let [unrealized (cq/q '[:find [?n ...] :in $ %
                             :where
                             (is ?op :fukan.common.vocab.code.operation/Operation) (design ?op)
                             (is ?_g :fukan.common.extraction.clojure.operation/Fn)   ; gate: extraction ran
                             (not-join [?op] (corresponds ?op ?_t))
                             [?op :entity/name ?n]]
                           m (s/vocab-rules))]
      (if (empty? unrealized)
        (println "No drift — every modelled Operation is realized in code.")
        (println "Drift —" (count unrealized) "modelled Operation(s) with no realizing function:" (sort unrealized))))
    (println "No model loaded yet. Use (go) first.")))

(defn undeclared-code-dependencies
  "Cross-subsystem dependencies present in CODE — `realized-delegates` (which holds for ANY corresponded
   op pair whose fact witnesses connect through non-public interior `:calls` — the transported reading of
   the delegation graph, NOT contingent on an authored `:delegates` edge; those authored edges are what a
   future law would COMPARE against this), rolled up through
   `contains`/`in-subsystem` to subsystem altitude — that cross a subsystem boundary with NO
   declared `:may-depend` edge covering it. THE previously-invisible drift: since the morphism arc
   retired op-level reflect, no live law or reading saw the CODE's module-dependency graph —
   Subsystem's `:may-depend` conformance law reads `module-depends`, which is built from `delegates`
   (design intent), not `:calls` (code fact) — so a design delegation that follows a declared edge
   could still be realized in code by a path that crosses an UNDECLARED subsystem boundary, unseen.
   A SIGNAL, not a law — the law layer decides teeth if this ever grows one."
  []
  (if-let [m (infra-model/get-model)]
    (let [rows (->> (cq/q '[:find ?sn ?tn ?an ?bn :in $ %
                            :where
                            (realized-delegates ?a ?b)
                            (contains ?ma ?a) (contains ?mb ?b) [(not= ?ma ?mb)]
                            (in-subsystem ?ma ?s) (in-subsystem ?mb ?t) [(not= ?s ?t)]
                            (not-join [?s ?t] (may-depend ?s ?t))
                            [?s :entity/name ?sn] [?t :entity/name ?tn]
                            [?a :entity/name ?an] [?b :entity/name ?bn]]
                          m (s/vocab-rules))
                    sort vec)]
      (if (empty? rows)
        (println "No undeclared code dependencies — every cross-subsystem code call the model can see follows a declared :may-depend edge.")
        (do (println "Undeclared code dependencies —" (count rows) "cross-subsystem call(s) with no declared :may-depend edge:")
            (doseq [[sn tn an bn] rows]
              (println (format "  %-16s ⟶ %-16s   (%s → %s)" sn tn an bn))))))
    (println "No model loaded yet. Use (go) first.")))

(defn encapsulation
  "The ENCAPSULATION worklist (the privacy-coverage iteration): PUBLIC extracted functions with no
   authored Operation twin — each an undeclared public surface demanding a decision (model it as
   intent, or make it `defn-`). Empty ⇔ every unmodelled function is genuinely private. Grouped by
   code namespace. (The private half of the coverage gap is settled by definition.)
   A READING of the `corresponds` join (the `public` scope lives in the reading now — a public `Fn`
   paired by nothing; the `:corresponds/Operation.surjective` law dissolved with the essential
   `correspond`) via the `public`/`within` defrelations — the fact stratum is the `Fn` theory."
  []
  (if-let [m (infra-model/get-model)]
    (let [w (unaccounted-public m)]
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
  "TYPE correspondence (model↔code): paired Operations whose signature DISAGREES with the code's
   `:malli/schema` — a READING over the `corresponds` join (the generated `:corresponds/*` agrees
   demand dissolved with the essential `correspond`). A twin missing an `:out` the design declares, or
   an `:in`/`:out` type node present on one side and not the other, is an offender. ⚠ current strength:
   this reading compares `:in`/`:out` type nodes by SET-EQUALITY (eid), NOT `:rel/order` — a pure
   REORDER of same-typed args reads as adhering here (the old structural comparator checked order;
   restoring order-sensitivity waits on the authored agrees law's return)."
  []
  (if-let [m (infra-model/get-model)]
    (let [drifted (cq/q '[:find [?n ...] :in $ %
                          :where
                          (is ?op :fukan.common.vocab.code.operation/Operation) (design ?op)
                          (corresponds ?op ?fn)
                          (or-join [?op ?fn]
                            (and (out ?op ?o) (not-join [?fn ?o] (out ?fn ?o)))
                            (and (out ?fn ?o) (not-join [?op ?o] (out ?op ?o)))
                            (and (in ?op ?s)  (not-join [?fn ?s] (in ?fn ?s)))
                            (and (in ?fn ?s)  (not-join [?op ?s] (in ?op ?s))))
                          [?op :entity/name ?n]]
                        m (s/vocab-rules))]
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
  (undeclared-code-dependencies)
  (encapsulation)
  (purity)
  (correspondence)
  (status))
