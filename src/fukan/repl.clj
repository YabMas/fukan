(ns fukan.repl
  "The COCKPIT — the shipped reader commands a project drives its model with.

   This is the consumer surface: an external project depends on fukan, requires this
   namespace, and calls `(go {:src \"path/to/its/core\"})`. Everything else defaults for a
   project whose specs live in `canvas/` at its root, so a consumer needs no files beyond
   `canvas/` itself.

   fukan-on-itself is the FIRST consumer, not a privileged one — `dev/user.clj` is a thin
   wrapper over this namespace supplying fukan's own defaults. That is deliberate: the
   consumer surface is exercised on every REPL start, so it cannot rot."
  (:require [clojure.string :as str]
            [clj-reload.core :as reload]
            [fukan.canvas.ingestion.canvas-source :as canvas-source]
            [fukan.cozo.query :as cq]
            [fukan.cozo.law :as law]
            [fukan.canvas.projection.grammar :as gram]
            [fukan.canvas.projection.instance :as inst]
            [fukan.canvas.projection.architecture :as arch]
            [fukan.canvas.core.structure :as s]
            [fukan.common.vocab.code.subsystem :as code-subsystem]
            [fukan.common.extraction.clojure.module :as clj-module]
            [fukan.infra.model :as infra-model]))

(defonce ^:private cockpit (atom nil))

(defn ^{:malli/schema [:=> [:cat] :any]} config
  "The held cockpit config — {:src :title :spec-dirs :reload-dirs} — or nil before `go`."
  []
  @cockpit)

(defn- ensure-reload-init!
  "Initialise clj-reload for `reload-dirs`, but only when they differ from the dirs already
   initialised. Re-initialising resets clj-reload's change baseline, which would make a
   pending spec edit invisible to the next `refresh` — so `go` must not do it blindly."
  [reload-dirs]
  (when (not= reload-dirs (:reload-dirs @cockpit))
    (reload/init {:dirs reload-dirs :no-reload '#{user}})))

(defn ^{:malli/schema [:=> [:cat :any] :StructureDb]} go
  "Build (or rebuild) the held model and hold the cockpit config.

   Options — all optional:
     :src          the code root to extract. nil, or a path that does not exist yet, yields a
                   DESIGN-ONLY build (`pipeline.clj:28`) — the model-first workflow, where every
                   authored Operation is drift until realised.
     :title        the project's name, as `(architecture)` banners it. nil omits it.
     :spec-dirs    classpath dirs scanned for specs. Defaults to `*spec-dirs*` — i.e. [\"canvas\"].
     :reload-dirs  dirs clj-reload watches. Defaults to :spec-dirs. A consumer edits only specs;
                   fukan also edits its own source and so widens this.

   `go` IS the configure step — there is no separate configure! call: it holds the config
   every other command reads, so the commands themselves stay bare."
  [{:keys [src title spec-dirs reload-dirs]}]
  (let [spec-dirs   (or spec-dirs canvas-source/*spec-dirs*)
        reload-dirs (or reload-dirs spec-dirs)]
    (alter-var-root #'canvas-source/*spec-dirs* (constantly spec-dirs))
    ;; MUST precede the reset! — it compares reload-dirs against the PREVIOUSLY held config.
    (ensure-reload-init! reload-dirs)
    (reset! cockpit {:src src :title title :spec-dirs spec-dirs :reload-dirs reload-dirs})
    (infra-model/load-model src)))

(defn- reload-code!
  "`:only :changed` (the default) — reload exactly what changed. NOT `:only :loaded`:
   that reloads every currently-loaded namespace in one pass, and the defstructure
   registry cannot survive that ordering once spec dirs are tracked (clj-reload's
   static dependency graph can't see edges that cross an untracked dir like `common/`,
   so a full reload can schedule a canvas self-spec before the src it macro-depends on
   — `Failed to load namespace: canvas.architecture.cozo.db`). `:only :loaded` was safe
   in the old dev/user.clj ONLY because canvas was untracked there; now that tracking
   canvas is the whole point, `:only :loaded` is never safe again.

   `:changed` narrows the crash window; it does NOT close it. The missing `common/` edge
   is still missing, so a `src/` edit AND a dependent canvas-spec edit landing in the SAME
   changed set can still be mis-ordered and throw the same way. The only safe recovery is
   restarting the REPL — do NOT reload in two passes (`src/` first, then specs). That was
   tried: it does not throw, it silently produces a SMALLER model with spurious violations
   (338 structures/897 relations/2 violations, verified against a correct 509/1511/0) —
   worse than the crash it looks like it avoids. This is fukan-on-itself's exposure only —
   an external consumer's `reload-dirs` are their spec dirs alone, and they never touch
   fukan's `src/`; canvas-only reload is verified correct (23 namespaces reloaded, model
   intact)."
  []
  (let [result (reload/reload)]
    (when (seq (:loaded result))
      (println "Reloaded:" (count (:loaded result)) "namespaces")
      (doseq [ns-sym (:loaded result)] (println " " ns-sym)))
    (when (seq (:unloaded result)) (println "Unloaded:" (:unloaded result)))
    result))

(defn ^{:malli/schema [:=> [:cat] :Unit]} reset
  "Reload changed code, then rebuild the held model from the held config."
  []
  (reload-code!)
  (if-let [c @cockpit]
    (infra-model/load-model (:src c))
    (println "No model loaded yet. Use (go) first.")))

(defn ^{:malli/schema [:=> [:cat] :Unit]} refresh
  "Reload changed code + rebuild the held model. Use after editing a spec.

   Gated on the held CONFIG, not on a non-nil src — a design-only model (src nil) is a
   legitimate state a consumer sits in for as long as the code does not exist yet."
  []
  (reload-code!)
  (if-let [c @cockpit]
    (do (infra-model/load-model (:src c))
        (println "Refreshed."))
    (println "No model loaded yet. Use (go) first.")))

(defn ^{:malli/schema [:=> [:cat] :Unit]} status
  []
  (if-let [m (infra-model/get-model)]
    (println "Model:"
             (count (cq/q '[:find ?e :where [?e :structure/of _]] m)) "structures,"
             (count (cq/q '[:find ?r :where [?r :rel/kind _]] m)) "relations"
             (str "(src: " (infra-model/get-src) ")"))
    (println "Model: not loaded")))

(defn ^{:malli/schema [:=> [:cat] :Unit]} architecture
  "Print the projected SYSTEM MAP — the canvas's front door: the project's code-side
   architecture, its subsystems, their modules, and the :may-depend DAG, derived live from
   the held model. Read this instead of `ls canvas/` to grasp the modelled system's shape.
   The banner names the system with the `:title` held by `go` — `(go {:src \"…\" :title \"BRIAN\"})`."
  []
  (if-let [m (infra-model/get-model)]
    (println (arch/architecture-overview m (:title @cockpit)))
    (println "No model loaded yet. Use (go) first.")))

(defn ^{:malli/schema [:=> [:cat :string] :Unit]} grammar
  "Print the GRAMMAR PRIMER — every vocabulary in the held model rendered back as
   its map-form defstructures, live from the reified grammar (the print-dual).
   Pass a namespace string for one vocabulary: (grammar \"lib.code\")."
  ([] (if-let [m (infra-model/get-model)]
        (println (gram/grammar-primer m))
        (println "No model loaded yet. Use (go) first.")))
  ([vocab-name]
   (if-let [m (infra-model/get-model)]
     (println (gram/vocabulary-primer m vocab-name nil))
     (println "No model loaded yet. Use (go) first."))))

(defn ^{:malli/schema [:=> [:cat] :Unit]} correspondence
  "Print the CORRESPONDENCE CARD: every registered essential `(correspond …)` — its authored
   head/match/map form (registry-direct) — plus its live VOCAB-GENERIC coverage readings
   (unrealized/ambiguous), computed over the held model's `corresponds`/`realized-*` rules. Appends
   the unaccounted-public count — the same coverage-gap SET `encapsulation` reports as a worklist —
   since the card itself is kernel-tier and stays vocab-agnostic (it never names the `public`
   predicate; that is the consuming project's business)."
  []
  (if-let [m (infra-model/get-model)]
    (do (println (gram/correspondence-card m))
        (println (str "unaccounted-public: "
                      (count (law/violations-of m :correspondence/public-unaccounted)))))
    (println "No model loaded yet. Use (go) first.")))

(defn ^{:malli/schema [:=> [:cat [:or :string :symbol]] :Unit]} show
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

(defn ^{:malli/schema [:=> [:cat [:vector :any]] :Unit]} focus
  "Evaluate datalog `clauses` (binding ?n, with the vocab rules) over the held
   model and print the focused nodes as their authored forms — the textual model
   explorer: (focus '[(Operation ?n) (within ?n \"core-structure\")])."
  [clauses]
  (if-let [m (infra-model/get-model)]
    (let [out (inst/focus-text m clauses)]
      (println (if (str/blank? out) "Empty focus." out)))
    (println "No model loaded yet. Use (go) first.")))

(defn ^{:malli/schema [:=> [:cat] :Unit]} check
  "Run every law over the held model and print the violations with each offender
   QUOTED as its authored form — the law that fired and the instance that fired
   it, side by side."
  []
  (if-let [m (infra-model/get-model)]
    (println (inst/violations-text m (law/check m)))
    (println "No model loaded yet. Use (go) first.")))

(defn ^{:malli/schema [:=> [:cat] :Unit]} drift
  "Model↔code drift in the held (unified) model: what the design CLAIMS that the code does not
   have, at both altitudes — modules no namespace realizes, and operations no function realizes.

   A PRINTER over two laws, addressed by their stable keys, so `(check)` and this report the same
   thing by construction — and a retired law makes this THROW rather than quietly report nothing
   forever. (Build with a code-root — `(go {:src \"…\"})` — so the held model carries the code.)"
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

(defn ^{:malli/schema [:=> [:cat] :Unit]} undeclared-code-dependencies
  "Cross-subsystem dependencies present in CODE — `realized-delegates` (which holds for ANY
   corresponded op pair whose fact witnesses connect through non-public interior `:calls` — the
   transported reading of the delegation graph, NOT contingent on an authored `:delegates` edge),
   rolled up through `contains`/`in-subsystem` to subsystem altitude — that cross a subsystem
   boundary with NO declared `:may-depend` edge covering it. The otherwise-invisible drift: the
   `:may-depend` conformance law reads `module-depends`, built from `delegates` (design intent),
   not `:calls` (code fact), so a design delegation that follows a declared edge can still be
   realized by a path that crosses an UNDECLARED boundary, unseen.
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

(defn ^{:malli/schema [:=> [:cat] :Unit]} encapsulation
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

(defn ^{:malli/schema [:=> [:cat] :Unit]} leaves
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

(defn ^{:malli/schema [:=> [:cat] :Unit]} frontier
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

(defn ^{:malli/schema [:=> [:cat] :Unit]} deps
  "Print the project's complete module→module dependency graph (calls ∪ data-adoption), one edge per line —
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

(defn ^{:malli/schema [:=> [:cat] :Unit]} purity
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

(defn ^{:malli/schema [:=> [:cat] :Unit]} type-drift
  "TYPE adherence (model↔code): paired Operations whose signature DISAGREES with the code's
   `:malli/schema`. A twin missing an `:out` the design declares, or an `:in`/`:out` type node
   present on one side and not the other, is an offender.

   A PRINTER over the law keyed `:correspondence/signature-disagrees`. ⚠ Its strength is the
   law's: `:in`/`:out` compare by SET-EQUALITY of type nodes, NOT `:rel/order`, so a pure REORDER
   of same-typed args reads as adhering."
  []
  (if-let [m (infra-model/get-model)]
    (let [rows (law/violation-rows m :correspondence/signature-disagrees)]
      (println "ADHERENCE — modelled signature disagrees with the code's :malli/schema:")
      (if (empty? rows)
        (println "  (none — every code signature exactly adheres to its modelled type)")
        (doseq [[on mn] (sort-by (juxt second first) rows)]
          (println (format "   %-42s in %s" on mn)))))
    (println "No model loaded yet. Use (go) first.")))
