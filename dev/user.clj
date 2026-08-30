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
            [fukan.common.vocab.code.subsystem :as code-subsystem]
            [fukan.common.extraction.clojure.module :as clj-module]))

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
     (println (gram/vocabulary-primer m vocab-name nil))
     (println "No model loaded yet. Use (go) first."))))

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
        (println (str "unaccounted-public: "
                      (count (law/violations-of m :correspondence/public-unaccounted)))))
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
  "Model↔code drift in the held (unified) model: what the design CLAIMS that the code does not
   have, at both altitudes — modules no namespace realizes, and operations no function realizes.

   A PRINTER over two laws, addressed by their stable keys. It was an inline query until the
   correspondence grew teeth (2026-08-29), which meant two definitions of drift free to drift
   from each other; now `(check)` and this report the same thing by construction, and a retired
   law makes this THROW rather than quietly report nothing forever.
   (Build with a code-root — `(go)` defaults to \"src\" — so the held model carries the code.)"
  []
  (if-let [m (infra-model/get-model)]
    (let [modules (law/violation-rows m :correspondence/module-unrealized)
          ops     (law/violation-rows m :correspondence/operation-unrealized)]
      (if (and (empty? modules) (empty? ops))
        (println "No drift — every modelled Module and Operation is realized in code.")
        (do (when (seq modules)
              (println "Drift —" (count modules) "modelled Module(s) with no realizing namespace:")
              (doseq [[mn] (sort modules)] (println "  " mn)))
            (when (seq ops)
              (println "Drift —" (count ops) "modelled Operation(s) with no realizing function:")
              (doseq [[on mn] (sort-by (juxt second first) ops)]
                (println (format "   %-42s in %s" on mn)))))))
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
  "The ENCAPSULATION worklist: PUBLIC extracted functions inside an ADOPTED namespace that no
   authored Operation models — each an undeclared public surface demanding a decision (model it
   as intent, or make it `defn-`). Grouped by code namespace. Empty ⇔ every unmodelled function
   in the CLAIMED region is genuinely private; namespaces the model has not claimed are not a
   gap here — they are `(frontier)`/`(leaves)`' business.

   A PRINTER over the law keyed `:correspondence/public-unaccounted`. The law carries the
   namespace in its offender row, which is what lets this group without a second query."
  []
  (if-let [m (infra-model/get-model)]
    (let [rows (law/violation-rows m :correspondence/public-unaccounted)]
      (if (empty? rows)
        (println "Fully encapsulated — every unmodelled function is private.")
        (let [by-ns (group-by second rows)]
          (println "Encapsulation worklist —" (count rows) "public functions with no model twin:")
          (doseq [[nn fns] (sort-by key by-ns)]
            (println (format "  %-42s %s" nn (str/join ", " (sort (map first fns)))))))))
    (println "No model loaded yet. Use (go) first.")))

(defn leaves
  "The ADOPTION CANDIDATES: unadopted namespaces that depend on no other namespace in the project,
   ranked by fan-in. These are where leaf-upward adoption starts — modelling one drags nothing else
   in (`:delegates` can only target an authored Operation, so the adopted set is downward-closed by
   construction), and the highest fan-in unblocks the most callers for the next step.
   Needs no authored model at all: pure extraction, readable on a codebase with an empty spec dir."
  []
  (if-let [m (infra-model/get-model)]
    (let [cands (clj-module/adoption-candidates m)]
      (if (empty? cands)
        (println "No adoption candidates — every unadopted namespace depends on another.")
        (do (println "Adoption candidates —" (count cands) "unadopted leaf namespace(s), most depended-upon first:")
            (doseq [[n d] cands] (println (format "  %-52s fan-in %d" n d))))))
    (println "No model loaded yet. Use (go) first.")))

(defn frontier
  "The ADOPTION FRONTIER: calls from ADOPTED code out into code the model does not yet claim, grouped
   by the unadopted callee namespace and ranked by how many adopted namespaces reach it.

   The blind spot leaf-upward adoption has no other way to see: such a call carries no `:delegates`
   edge (unauthorable — the slot may only target an authored Operation) and no `realized-delegates`
   (needs both ends paired), so the model silently asserts the operation delegates to nothing.
   Empty ⇔ the adopted region really is closed — every dependency it has is modelled. Otherwise this
   is both the correction to that silence and the ranked worklist for what to adopt next."
  []
  (if-let [m (infra-model/get-model)]
    (let [edges (clj-module/adoption-frontier m)]
      (if (empty? edges)
        (println "Closed frontier — the adopted region depends on nothing unmodelled.")
        (let [by-callee (group-by second edges)]
          (println "Adoption frontier —" (count edges) "call(s) from adopted code into"
                   (count by-callee) "unclaimed namespace(s):")
          (doseq [[callee es] (sort-by (juxt (comp - count val) key) by-callee)]
            (println (format "  %-52s ← %s" callee (str/join ", " (sort (map first es)))))))))
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
  "TYPE adherence (model↔code): paired Operations whose signature DISAGREES with the code's
   `:malli/schema`. A twin missing an `:out` the design declares, or an `:in`/`:out` type node
   present on one side and not the other, is an offender.

   A PRINTER over the law keyed `:correspondence/signature-disagrees`. ⚠ Its strength is the
   law's: `:in`/`:out` compare by SET-EQUALITY of type nodes, NOT `:rel/order`, so a pure REORDER
   of same-typed args reads as adhering. Order lives on the edge and reaching it needs an
   edge-level relation the vocabulary does not have yet."
  []
  (if-let [m (infra-model/get-model)]
    (let [rows (law/violation-rows m :correspondence/signature-disagrees)]
      (println "ADHERENCE — modelled signature disagrees with the code's :malli/schema:")
      (if (empty? rows)
        (println "  (none — every code signature exactly adheres to its modelled type)")
        (doseq [[on mn] (sort-by (juxt second first) rows)]
          (println (format "   %-42s in %s" on mn)))))
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
  (leaves)
  (frontier)
  (purity)
  (correspondence)
  (status))
