(ns canvas.vocab.code.extractors.clojure.effect
  "Clojure grounding for the generic code `Effect` vocabulary.

   This namespace is the PL-specific classifier: given clj-kondo var-usages, it
   attributes direct effects to caller Operations. The generic `Effect` structure
   and direct `effectful` predicate live in `canvas.vocab.code.effect`; consumers
   express transitive reach with path composition."
  (:refer-clojure :exclude []))

(def ^:private effect-by-callee
  "Fully-qualified callee var -> the effect it performs.
   CONSEQUENTIAL effects: :io/:state/:require. PARTIALITY: :throws.
   Logging/monitoring (println/print/prn/pr/printf/flush, clojure.tools.logging,
   tap>) is deliberately absent: observational, not a hazard."
  (merge
   (zipmap '[clojure.core/slurp clojure.core/spit clojure.core/line-seq clojure.core/file-seq
             clj-kondo.core/run!]
           (repeat :io))
   (zipmap '[clojure.core/swap! clojure.core/reset! clojure.core/swap-vals! clojure.core/reset-vals!
             clojure.core/alter clojure.core/alter-var-root clojure.core/ref-set clojure.core/vreset!
             clojure.core/commute clojure.core/send clojure.core/send-off]
           (repeat :state))
   (zipmap '[clojure.core/require clojure.core/use clojure.core/load clojure.core/load-file
             clojure.core/load-string clojure.core/requiring-resolve clojure.core/resolve
             clojure.core/ns-resolve clojure.core/find-ns clojure.core/the-ns]
           (repeat :require))
   (zipmap '[clojure.core/throw] (repeat :throws))))

(def ^:private effect-by-ns
  "Callee namespace -> effect, for whole namespaces that are effectful regardless of var."
  {"clojure.java.io"    :io
   "clojure.java.shell" :io})

(defn- callee-effect
  "The effect a callee, namespace symbol `to` and name symbol `nm`, performs."
  [to nm]
  (or (effect-by-callee (symbol (str to) (str nm)))
      (effect-by-ns (str to))))

(defn op-effects
  "Map {[caller-ns-str caller-fn-str] #{effect-kw ...}} from clj-kondo var-usages.
   Every resolvable call to a classified-effectful callee attributes that effect to
   the calling op. These are direct effects; transitive reach is a graph reading."
  [var-usages]
  (reduce (fn [acc {:keys [from from-var to name]}]
            (if-let [eff (and from from-var to name (callee-effect to name))]
              (update acc [(str from) (str from-var)] (fnil conj #{}) eff)
              acc))
          {} var-usages))
