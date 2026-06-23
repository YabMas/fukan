(ns fukan.target.clojure
  "A Clojure code-structure extractor — fukan parsing its OWN `src/`.

   This is the PL-specific half of the extraction seam: it is the only place
   that knows Clojure. It reads clj-kondo's `:analysis` output and maps Clojure
   constructs onto fukan's architecture-level code vocabulary:

     ns                      → Module      (a cohesion boundary)
     defn / defn- / defmulti → Operation   (a named unit of computation — defmulti is a dispatch point; `:extracted true`)

   The extractor OWNS no vocabulary — `Operation`/`Module` are defined in
   `lib.code`; this plug-point only EMITS instances of them by tag
   (stamping `:extracted true` so an extracted Operation is distinguished from an
   authored one), and they meet that grammar at merge. Swap a different language's
   extractor in (Python `def`/module, …) and the same vocab plus the same cross-layer
   correspondence law still hold; the Clojure-ness stays confined here. clj-kondo is
   the wheel we don't reinvent."
  (:require [clj-kondo.core :as kondo]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as assemble]
            [fukan.canvas.core.substrate :as s]))

(def ^:private fn-defining
  "clj-kondo `:defined-by` values that denote a computation unit (an Operation). `defn`/`defn-`
   are functions; `defmulti` is a DISPATCH POINT — also an Operation (callers depend on it; its
   handler fan-out is authored intent, see `lib.code/Operation :dispatches-to`). `def`, `defmacro`,
   `defmethod`, … stay excluded — `defmethod` defines no var."
  #{'clojure.core/defn 'clojure.core/defn- 'clojure.core/defmulti})

(defn- analyze
  "Run clj-kondo over `paths` and return its `:analysis` — namespace + var
   definitions. Reads source (and writes clj-kondo's cache); deterministic output."
  [paths]
  (:analysis (kondo/run! {:lint (vec paths)
                          :config {:output {:analysis {:var-definitions {:meta true}
                                                       :var-usages true}}}})))

(defn- op-eid
  "Eid of the extracted Operation named `fn-str` that is a :child of the Module whose
   :entity/id is `ns-str`, or nil (callee is not one of our `defn`s)."
  [db ns-str fn-str]
  (ffirst (d/q '[:find ?op :in $ ?ns ?fn
                 :where [?m :structure/of :lib.code/Module] [?m :entity/id ?ns]
                        [?r :rel/from ?m] [?r :rel/kind :child] [?r :rel/to ?op]
                        [?op :structure/of :lib.code/Operation] [?op :entity/name ?fn]]
               db ns-str fn-str)))

(defn- add-calls
  "Transact a `:calls` rel for every resolvable cross-op var-usage. Both endpoints must be
   extracted Operations we emitted; self-calls and core/library callees (unresolved) drop out.
   This is the FACTS layer — intent-free; the model<->design interpretation lives in
   `lib.code.correspondence`."
  [db var-usages]
  (let [pairs (->> var-usages
                   (keep (fn [{:keys [from from-var to name]}]
                           (when (and from-var to name)
                             (let [c (op-eid db (str from) (str from-var))
                                   e (op-eid db (str to)   (str name))]
                               (when (and c e (not= c e)) [c e])))))
                   distinct)]
    (d/db-with db (map-indexed (fn [n [c e]]
                                 {:rel/id   (str c "|calls|" n "|" e)
                                  :rel/from c :rel/kind :calls :rel/to e :rel/order n})
                               pairs))))

;; ── effect grounding (the I/O analog of :calls) ──────────────────────────────
;; The FACTS layer for effects: classify a callee, attribute its effect to the calling op (direct
;; effects only; transitive reach is the reading's job). CONSEQUENTIAL effects (:io/:state/:require)
;; are the `(purity)` surface; logging/monitoring is deliberately NOT an effect (observational, not a
;; hazard). `throw` is classified as PARTIALITY (:throws) — kept OUT of the consequential surface, read
;; by the `(totality)` trust-line worklist.

(def ^:private effect-by-callee
  "Fully-qualified callee var → the effect it performs — CONSEQUENTIAL (:io/:state/:require) or
   PARTIALITY (:throws, kept out of the consequential `(purity)` surface; read by `(totality)`).
   Logging/monitoring (println/print/prn/pr/printf/flush, clojure.tools.logging, tap>) is
   deliberately ABSENT — observational, not a hazard, per the purity carve-out."
  (merge
   (zipmap '[clojure.core/slurp clojure.core/spit clojure.core/line-seq clojure.core/file-seq
             clj-kondo.core/run!]                  ; the analyzer's file I/O (reads source, writes its cache)
           (repeat :io))
   (zipmap '[clojure.core/swap! clojure.core/reset! clojure.core/swap-vals! clojure.core/reset-vals!
             clojure.core/alter clojure.core/alter-var-root clojure.core/ref-set clojure.core/vreset!
             clojure.core/commute clojure.core/send clojure.core/send-off
             datascript.core/transact! datascript.core/reset-conn!]
           (repeat :state))
   (zipmap '[clojure.core/require clojure.core/use clojure.core/load clojure.core/load-file
             clojure.core/load-string clojure.core/requiring-resolve clojure.core/resolve
             clojure.core/ns-resolve clojure.core/find-ns clojure.core/the-ns]
           (repeat :require))
   ;; partiality — `throw` is a special form, but clj-kondo resolves it as clojure.core/throw.
   ;; An op that throws is partial; classified so its partiality is queryable by the `(totality)`
   ;; trust-line worklist. NOT a consequential world-effect → excluded from the `(purity)` surface.
   (zipmap '[clojure.core/throw] (repeat :throws))))

(def ^:private effect-by-ns
  "Callee NAMESPACE → effect, for whole namespaces that are effectful regardless of the var."
  {"clojure.java.io"    :io
   "clojure.java.shell" :io})

(defn- callee-effect
  "The effect a callee — namespace symbol `to`, name symbol `nm` — performs, or nil.
   A specific-var classification wins over the namespace-wide one."
  [to nm]
  (or (effect-by-callee (symbol (str to) (str nm)))
      (effect-by-ns (str to))))

(defn- effect-iv
  "A value-identified Effect InstanceValue for effect keyword `kw` — content-identical to an
   authored `(Effect :kw)`, so extracted and authored effects collapse to one node."
  [kw]
  (s/->InstanceValue :lib.code/Effect nil nil {:val/name (name kw)} [] true))

(defn- op-effects
  "Map {[caller-ns-str caller-fn-str] #{effect-kw …}} from clj-kondo var-usages — every resolvable
   call to a classified-effectful callee attributes that effect to the CALLING op (direct effects
   only; transitive reach is the reading's job, and is deferred)."
  [var-usages]
  (reduce (fn [acc {:keys [from from-var to name]}]
            (if-let [eff (and from from-var to name (callee-effect to name))]
              (update acc [(str from) (str from-var)] (fnil conj #{}) eff)
              acc))
          {} var-usages))

(defn extract
  "Extract a structure-db of Modules + the Operations they define from the Clojure source under
   `paths`, then ground the actual call graph as `:calls` rels. Operations are stamped with their
   DIRECT effects (`:performs`, logging excluded; `throw` as `:throws`); Modules are stamped
   `:val/extracted true` (provenance, like Operations)."
  [& paths]
  (let [{:keys [namespace-definitions var-definitions var-usages]} (analyze paths)
        ops-by-ns    (group-by :ns (filter #(fn-defining (:defined-by %)) var-definitions))
        module-names (distinct (concat (map :name namespace-definitions)
                                       (keys ops-by-ns)))
        op-effs      (op-effects var-usages)
        db (assemble/assemble-instances
            (for [mname module-names
                  :let [ops (for [v (ops-by-ns mname)
                                  :let [effs (get op-effs [(str mname) (str (:name v))])]]
                              (s/->InstanceValue :lib.code/Operation (str (:name v)) nil
                                                 (cond-> {:val/private (boolean (:private v))
                                                          :val/extracted true}
                                                   (:export (:meta v))
                                                   (assoc :val/export true)
                                                   (:test-support (:meta v))
                                                   (assoc :val/test-support true)
                                                   (:malli/schema (:meta v))
                                                   (assoc :val/sig (pr-str (:malli/schema (:meta v)))))
                                                 (cond-> []
                                                   (seq effs)
                                                   (conj {:rk :performs :card :many
                                                          :targets (mapv effect-iv (sort effs))}))
                                                 false))]]
              [(str mname)
               (s/->InstanceValue :lib.code/Module (str mname) nil {:val/extracted true}
                                  [{:rk :child :card :many :targets (vec ops)}] false)]))]
    (add-calls db var-usages)))
