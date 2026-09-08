(ns fukan.cozo.law
  "The law engine on Cozo — compile a defstructure law's datalog (offenders + where, plus the
   rules it reads at) → CozoScript via the general query compiler (`fukan.cozo.query`), run it, and
   return offenders. THIS is `check`: it evaluates the laws the kernel DEFINES (`structure/laws-of`).
   Evaluation lives here (the engine) rather than as a hollow shell in the kernel — the kernel owns
   definition, the engine owns evaluation, and the dependency runs one way (engine → kernel), so
   there is no cycle and no registry to break one.

   HYBRID: the auto-generated scalar TYPE-CHECK laws don't compile — they validate a leaf value
   through the malli dialect (`typing/value-valid?`), which has no CozoScript form. So
   `check-structural` runs them split: Cozo finds each instance's leaf value (in its typed bucket),
   Clojure runs the malli check. Everything else compiles to pure CozoScript.

   `check` is the fail-closed violation view; `check-structural` is the full per-law roll-call
   (including coverage/`:unsupported`). `violations-of`/`violation-names`/`violation-rows` are the readers
   over `check`."
  (:require [clojure.string :as str]
            [fukan.canvas.core.structure :as structure]
            [fukan.canvas.core.typing :as typing]
            [fukan.cozo.db :as db]
            [fukan.cozo.query :as query]))

(defn- scope-tag
  "The structure tag a free law's first offender var is scoped to, or nil when nothing is to be
   prepended: `:global` declines the auto-scope, and a slot-derived law carries no owner because
   it already writes its owner's tag into its own body."
  [{:keys [scope owner]}]
  (case scope :global nil, nil owner, scope))

(defn ^{:malli/schema [:=> [:cat :any :any] :string]}
  compile-law
  "Compile a law's offender query → a CozoScript program: the vocab rules in its reference
   closure, its own `:rules`, helper rules, then the `?` entry. A non-global law's first offender
   var is bound by a prepended scope clause the ALGEBRA supplies (`structure/pin-clause`) — the
   same answer the query compiler's `(is …)` lowering gets, so a law and a query agree about what
   a sort's instances are. `index` is the `query/vocab-index`."
  [{:keys [offenders where rules] :as law} index]
  (let [st           (scope-tag law)
        ;; a scope naming no registered sort would pin nothing and let the law pass vacuously.
        ;; Refusing here makes it :unsupported instead, and `check` fails closed on that — an
        ;; unevaluated constraint cannot establish satisfaction.
        _            (when (and st (nil? (structure/structure-by-tag st)))
                       (throw (ex-info (str "law " (pr-str (:desc law)) " is scoped to " st
                                            ", which is not a registered sort")
                                       {:law (:desc law) :scope st})))
        scope-clause (when st (structure/pin-clause st (first offenders)))
        where*       (cond->> where scope-clause (cons scope-clause))
        [rule-lines body] (query/compile-body where* rules index offenders)]
    (str/join "\n" (concat rule-lines
                           [(str "?[" (str/join ", " (map query/cvar offenders)) "] := " body)]))))

(defn- all-laws
  "`[tag law]` for every law `check` would run — the same set, from the live registry."
  []
  (for [sdef (structure/all-structures), law (structure/laws-of sdef)]
    [(:tag sdef) law]))

;; ── the type-check hybrid: Cozo finds the leaf, Clojure runs malli ────────────
(defn- value-check-law
  "If `law` is an auto-generated scalar TYPE-CHECK law — its `:where` carries a
   `[(typing/value-valid? <target> ?v) ?ok]` clause (malli, not CozoScript-expressible) —
   return `{:tag :val-attr :target}`; else nil."
  [{:keys [where]}]
  (when-let [vv (some #(when (and (vector? %) (= 2 (count %)) (seq? (first %))
                                  (= 'fukan.canvas.core.typing/value-valid? (ffirst %)))
                         (first %))
                      where)]
    (let [vvar (nth vv 2)]
      {:target (second vv)
       :val-attr (some #(when (and (vector? %) (= 3 (count %)) (keyword? (nth % 1))
                                   (= vvar (nth % 2))) (nth % 1))
                       where)
       :tag (some #(when (and (vector? %) (= 3 (count %)) (= :structure/of (nth % 1))) (nth % 2))
                  where)})))

(defn- value-offenders
  "Run a type-check law as the hybrid: query each typed bucket for the instances of `tag`
   carrying a `val-attr` leaf (so the leaf keeps its real type), keep the ones whose value
   fails the malli `target`, and return their eids as offender rows."
  [cdb {:keys [tag val-attr target]}]
  (let [tag-s  (subs (str tag) 1)
        attr-s (subs (str val-attr) 1)
        validate-value (fn [v]
                         ;; The mirror stores keyword scalar values in t_str without the
                         ;; leading colon, matching query literal compilation. Rehydrate for
                         ;; the hybrid type-law check so :keyword slots validate the authored
                         ;; value rather than the storage encoding.
                         (if (= :keyword target) (keyword v) v))
        rows   (mapcat (fn [bucket]
                         (db/q cdb (str "?[x, v] := *" bucket "[x, '" attr-s "', v], "
                                        "*t_str[x, 'structure/of', '" tag-s "']")))
                       ["t_int" "t_str" "t_bool"])]
    (->> rows
         (filter (fn [[_ v]] (false? (typing/value-valid? target (validate-value v)))))
         (mapv (fn [[x _]] [x])))))

(def ^:dynamic *law-budget-ms*
  "The wall-clock ONE law may take before `check` stops waiting for it. Nil removes the bound.

   A law that overruns is reported as OVER-BUDGET, which `check` treats exactly as it treats a law
   that would not compile: an unevaluated sentence is neither satisfied nor refuted, so the model
   is undecidable and the report names the law. Without a bound, one pathological law is
   indistinguishable from a slow project — a cross-band conformance law that inlined a filtered
   generator held a 900-namespace run for 28 of its 40 law-seconds, and finding that took a
   profiling session rather than reading the exit report.

   120s is deliberately far above any law's honest cost (the slowest on a 900-namespace project is
   ~4s) — this bounds a pathology, it does not police a budget."
  120000)

(defn- bounded
  "Run `f` on a daemon thread, giving it `*law-budget-ms*`; `::over-budget` if it does not finish
   in time, and `f`'s own throw is re-thrown on this thread. The thread is INTERRUPTED and
   abandoned rather than waited on: a Cozo query is a native call that may not observe the
   interrupt, and the point of a budget is that the caller stops paying. Daemon, so an abandoned
   one cannot hold the JVM open at exit."
  [f]
  (if-let [ms *law-budget-ms*]
    (let [p (promise)
          t (doto (Thread. #(deliver p (try {:value (f)} (catch Throwable e {:thrown e})))
                           "fukan-law")
              (.setDaemon true)
              (.start))]
      (if-let [r (deref p ms nil)]
        (if (contains? r :thrown) (throw (:thrown r)) (:value r))
        (do (.interrupt t) ::over-budget)))
    (f)))

(defn ^{:malli/schema [:=> [:cat :CozoDb] :any]}
  check-structural
  "Run every law over the Cozo db `cdb`, returning `[{:structure :law :offenders}]` (offenders
   = matched eid tuples, native handles) for laws that fire, `{:structure :law :unsupported true}`
   for laws whose form (or a vocab rule they read) isn't compiled yet, and
   `{:structure :law :over-budget true}` for one that outran `*law-budget-ms*`. A type-check law
   runs the hybrid (`value-offenders`); everything else compiles to CozoScript and runs.

   The vocab index is compiled INSIDE the bucket binding, with the same map every law then
   compiles against: a rule compiled with no bucket index in force reads a three-way union helper
   for every attribute instead of the one stored relation that holds it — and the index compiled
   outside would also miss the memo the laws go on to hit."
  [cdb]
  (let [buckets (query/buckets-of cdb)
        index   (binding [query/*attr-buckets* buckets] (query/vocab-index))
        fired   (fn [tag law rows]
                  (cond-> {:structure tag :law (:desc law) :vars (vec (:offenders law))}
                    (:key law)  (assoc :key (:key law))
                    (seq rows)  (assoc :offenders (vec rows))))]
    (vec (for [[tag law] (all-laws)]
           (cond
             (value-check-law law)
             (let [offs (bounded #(value-offenders cdb (value-check-law law)))]
               (if (= offs ::over-budget)
                 {:structure tag :law (:desc law) :over-budget true}
                 (fired tag law offs)))

             :else
             (let [program (try (binding [query/*attr-buckets* buckets]
                                  (compile-law law index))
                                (catch clojure.lang.ExceptionInfo _ ::unsupported))]
               (if (= program ::unsupported)
                 {:structure tag :law (:desc law) :unsupported true}
                 (let [rows (try (bounded #(db/q cdb program))
                                 (catch clojure.lang.ExceptionInfo _ ::unsupported))]
                   (case rows
                     ::unsupported {:structure tag :law (:desc law) :unsupported true}
                     ::over-budget {:structure tag :law (:desc law) :over-budget true}
                     (fired tag law rows))))))))))

(defn ^{:malli/schema [:=> [:cat :CozoDb] :any]}
  check
  "Run every law over the Cozo db `cdb` and return its VIOLATIONS — `[{:structure :law :vars
   :offenders}]` (offenders are eid TUPLES, native handles; `:vars` is the law's offender var
   list, so a consumer rendering a multi-var row can say which column is which rather than
   printing four names in a line and leaving the reader to guess). THE check: it runs the same laws the kernel
   DEFINES (`structure/laws-of`/`all-structures`), which is why check lives here in the engine and
   not as a hollow shell in the kernel — evaluation is the engine's job, the kernel's is definition.
   Satisfaction is FAIL-CLOSED: if any law cannot be compiled, cannot be evaluated, or outran
   `*law-budget-ms*`, throws with every undecided law in ex-data. An unevaluated sentence is
   neither satisfied nor refuted, so returning a green violation list would be a false claim —
   and a law that outran its budget is unevaluated in exactly that sense, which is why it lands
   here rather than being rendered as a violation of the design."
  [cdb]
  (let [results     (check-structural cdb)
        undecided   (vec (filter (some-fn :unsupported :over-budget) results))]
    (when (seq undecided)
      (throw (ex-info (str "cannot decide model satisfaction: " (count undecided)
                           " law(s) could not be evaluated"
                           (when-let [over (seq (filter :over-budget undecided))]
                             (str " — " (count over) " over the " *law-budget-ms*
                                  "ms per-law budget")))
                      {:unsupported undecided})))
    (vec (for [r results :when (:offenders r)]
           (select-keys r [:structure :law :key :vars :offenders])))))

(defn- known-law-keys
  "Every `:key` across the laws `check` runs — the addressable worklist surface, from the
   same registry source (`all-laws`) check evaluates."
  []
  (into #{} (keep (comp :key second)) (all-laws)))

(defn- offender-rows
  "The raw offender ROWS of the law keyed `k` — the one place the key is resolved against what
   `check` actually ran.

   An UNKNOWN key throws (naming the known keys): a reader addressing a retired or misspelled
   law must fail the moment it runs, not report an empty worklist forever — the reader-side
   symmetric of the duplicate-law-key registration guard."
  [cdb k]
  (let [known (known-law-keys)]
    (when-not (contains? known k)
      (throw (ex-info (str "no law keyed " k " — known keys: " (str/join ", " (sort known)))
                      {:key k :known known})))
    (->> (check cdb) (filter #(= k (:key %))) (mapcat :offenders))))

(defn ^{:malli/schema [:=> [:cat :CozoDb :keyword] :any]}
  violations-of
  "The offender eids of the law keyed `k` — the generic reader behind every law-specific worklist
   fn (filter `check` by the law's stable `:key`, first offender var). Returns a set of eids;
   callers name them through `violation-names`, or take whole rows through `violation-rows`.
   An unknown key throws, in `offender-rows`."
  [cdb k]
  (set (map first (offender-rows cdb k))))

(defn ^{:malli/schema [:=> [:cat :CozoDb :keyword] [:set :string]]}
  violation-names
  "The `:entity/name` of every offender of the law keyed `k` — `violations-of` (offender eids)
   resolved through `query/entity`. The one home for the recurring worklist-reader shape."
  [cdb k]
  (set (map #(:entity/name (query/entity cdb %)) (violations-of cdb k))))

(defn ^{:malli/schema [:=> [:cat :CozoDb :any] :any]}
  offender-label
  "One offender cell as text. An eid answers with its entity's name; anything else IS its own
   name already and answers with itself.

   An offender does not have to be a node. A law may bind any var it likes, and the useful one is
   sometimes a VALUE — a type-reference reports the NAME it could not resolve, because the
   `^:value` Schema carrying it is anonymous and its eid says nothing anyone can act on. Resolving
   every cell as an eid turned that into a Cozo error against a name used as an entity id."
  [cdb x]
  (if (int? x)
    (or (:entity/name (query/entity cdb x)) (str x))
    (str x)))

(defn ^{:malli/schema [:=> [:cat :CozoDb :keyword] [:set [:vector :any]]]}
  violation-rows
  "Every offender ROW of the law keyed `k`, each cell resolved to its `:entity/name`.

   `violation-names` answers with the first offender var alone, which was enough while a law
   named one thing. A law that binds an EDGE — a band's undeclared dependency, an operation and
   the module it was claimed in — carries the whole finding in the row, and keeping only the
   first column throws away the half that says what to do about it."
  [cdb k]
  (set (map (fn [row] (mapv #(offender-label cdb %) row)) (offender-rows cdb k))))
