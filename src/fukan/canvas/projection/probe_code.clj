(ns fukan.canvas.projection.probe-code
  "Cut-1 projector for the executable agent: reads the agent model (the structure db)
   and emits Clojure forms for a probe — the probe fn and its contract predicate. Pure
   (db → forms); persisting to src/ + build-wiring is a later cut. Handles the composing
   probe shape (a probe that `:calls` a modelled capability) with a list-of-Str finding."
  (:require [datascript.core :as d]))

;; Model→Code bridges (cut-1 minimal; generalize by deriving from the model later).
(def ^:private capability->var
  "Modelled capability name → the real var the projected code calls."
  {"check" 'fukan.canvas.core.structure/check})

(defn- probe-lens
  "The name of the lens a probe reads through."
  [db probe-name]
  (ffirst (d/q '[:find ?ln :in $ ?pn
                 :where [?p :entity/name ?pn] [?p :structure/of :Probe]
                        [?r :rel/from ?p] [?r :rel/kind :through] [?r :rel/to ?l]
                        [?l :structure/of :Lens] [?l :entity/name ?ln]]
               db probe-name)))

(defn- probe-gating
  "Whether the probe's yielded finding gates (a trust Signal)."
  [db probe-name]
  (ffirst (d/q '[:find ?g :in $ ?pn
                 :where [?p :entity/name ?pn] [?p :structure/of :Probe]
                        [?r :rel/from ?p] [?r :rel/kind :yields] [?r :rel/to ?f]
                        [?f :structure/of :Finding] [?f :val/gating ?g]]
               db probe-name)))

(defn- probe-capability
  "The modelled capability name a composing probe :calls (or nil for a fresh probe).
   Cut 1 supports a single :calls edge."
  [db probe-name]
  (let [results (d/q '[:find ?cn :in $ ?pn
                       :where [?p :entity/name ?pn] [?p :structure/of :Probe]
                              [?r :rel/from ?p] [?r :rel/kind :calls] [?r :rel/to ?c]
                              [?c :structure/of :Stage] [?c :entity/name ?cn]]
                     db probe-name)]
    (when (> (count results) 1)
      (throw (ex-info "cut-1 projector handles a single :calls edge only"
                      {:probe probe-name :capabilities (mapv first results)})))
    (ffirst results)))

(defn project-probe
  "Project the named probe to Clojure forms: {:fn-form … :contract-form …}.
   Cut 1: handles a composing probe (one that :calls a modelled capability) whose
   finding payload is a list of strings."
  [db probe-name]
  (let [lens (probe-lens db probe-name)
        gate (probe-gating db probe-name)
        cap  (probe-capability db probe-name)
        cap-var (capability->var cap)]
    (when-not cap-var
      (throw (ex-info (str "cut-1 projector handles composing probes only; no known capability for "
                           (pr-str probe-name))
                      {:probe probe-name :capability cap})))
    {:fn-form (list 'fn '[target-db]
                    {:lens lens
                     :gating gate
                     :finding (list 'mapv 'str (list cap-var 'target-db))})}))
