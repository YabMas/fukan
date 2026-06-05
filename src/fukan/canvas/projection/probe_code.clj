(ns fukan.canvas.projection.probe-code
  "Projector for the executable probe surface: reads the probe model (the structure
   db) and emits, per probe, the artifacts to run it. Pure (db → forms/strings).
   - A COMPOSING probe (it `:calls` a modelled capability) → a mechanical fn-form that
     wraps the capability's output as violation observations, plus the uniform contract.
   - A FRESH probe (no `:calls`) → a projected Instruction for an implementing LLM to
     write the leaf, plus the uniform contract.

   The finding contract is now uniform (observations: {focus tag note}), so no
   per-finding shape is read — `:shape` retired from the Finding vocab."
  (:require [datascript.core :as d]))

(def ^:private capability->var
  "Modelled capability name → the real var the projected code calls."
  {"check" 'fukan.canvas.core.structure/check})

(defn- probe-lens [db probe-name]
  (ffirst (d/q '[:find ?ln :in $ ?pn
                 :where [?p :entity/name ?pn] [?p :structure/of :Probe]
                        [?r :rel/from ?p] [?r :rel/kind :through] [?r :rel/to ?l]
                        [?l :structure/of :Lens] [?l :entity/name ?ln]]
               db probe-name)))

(defn- probe-gating [db probe-name]
  (ffirst (d/q '[:find ?g :in $ ?pn
                 :where [?p :entity/name ?pn] [?p :structure/of :Probe]
                        [?r :rel/from ?p] [?r :rel/kind :yields] [?r :rel/to ?f]
                        [?f :structure/of :Finding] [?f :val/gating ?g]]
               db probe-name)))

(defn- probe-focus [db probe-name]
  (ffirst (d/q '[:find ?fc :in $ ?pn
                 :where [?p :entity/name ?pn] [?p :structure/of :Probe]
                        [?r :rel/from ?p] [?r :rel/kind :through] [?r :rel/to ?l]
                        [?l :structure/of :Lens] [?l :val/focus ?fc]]
               db probe-name)))

(defn- finding-holds [db probe-name]
  (ffirst (d/q '[:find ?h :in $ ?pn
                 :where [?p :entity/name ?pn] [?p :structure/of :Probe]
                        [?r :rel/from ?p] [?r :rel/kind :yields] [?r :rel/to ?f]
                        [?f :structure/of :Finding] [?f :val/holds ?h]]
               db probe-name)))

(defn- finding-holds-pred [db probe-name]
  (ffirst (d/q '[:find ?p :in $ ?pn
                 :where [?pr :entity/name ?pn] [?pr :structure/of :Probe]
                        [?r :rel/from ?pr] [?r :rel/kind :yields] [?r :rel/to ?f]
                        [?f :structure/of :Finding] [?f :val/holds-pred ?p]]
               db probe-name)))

(defn- probe-capability [db probe-name]
  (let [results (d/q '[:find ?cn :in $ ?pn
                       :where [?p :entity/name ?pn] [?p :structure/of :Probe]
                              [?r :rel/from ?p] [?r :rel/kind :calls] [?r :rel/to ?c]
                              [?c :structure/of :Stage] [?c :entity/name ?cn]]
                     db probe-name)]
    (when (> (count results) 1)
      (throw (ex-info "cut-1 projector handles a single :calls edge only"
                      {:probe probe-name :capabilities (mapv first results)})))
    (ffirst results)))

(defn- observations-contract
  "The uniform finding contract FORM: a result's :observations are a sequence of
   {:focus <set> :as <keyword> :note <string>} maps."
  []
  '(fn [result]
     (and (sequential? (:observations result))
          (every? (fn [o] (and (set? (:focus o))
                               (keyword? (:as o))
                               (string? (:note o))))
                  (:observations result)))))

(defn- instruction
  "A projected Instruction for an implementing LLM: assembled purely from modelled
   facts. The LLM writes only the leaf (the :observations computation)."
  [db probe-name]
  (let [lens  (probe-lens db probe-name)
        focus (probe-focus db probe-name)
        gate  (probe-gating db probe-name)
        holds (finding-holds db probe-name)]
    (str "Implement the fukan probe `probe-" probe-name "`.\n\n"
         "Focus: " focus "\n"
         "Signature: (probe-" probe-name " [target-db]) where target-db is a datascript model db.\n"
         "Return the finding {:lens \"" lens "\", :gating " gate ", :observations [...]}, "
         "where each observation is {:focus <a set of node eids>, :as <a keyword tag>, "
         ":note <a descriptor string>}.\n"
         "Invariant (holds): " holds)))

(defn project-probe
  "Project the named probe.
   Composing probe → {:fn-form <fn building violation observations> :contract-form <pred> :holds-check <form|nil>}.
   Fresh probe → {:instruction <spec> :contract-form <pred> :holds-check <form|nil>}."
  [db probe-name]
  (let [cap (probe-capability db probe-name)]
    (if cap
      (let [cap-var (capability->var cap)]
        (when-not cap-var
          (throw (ex-info (str "no known capability var for " (pr-str cap))
                          {:probe probe-name :capability cap})))
        {:fn-form (list 'fn '[target-db]
                        {:lens (probe-lens db probe-name)
                         :gating (probe-gating db probe-name)
                         :observations
                         (list 'mapv
                               (list 'fn '[v]
                                     {:focus (list 'into #{} '(mapcat identity) '(:offenders v))
                                      :as :violation
                                      :note '(str v)})
                               (list cap-var 'target-db))})
         :contract-form (observations-contract)
         :holds-check (finding-holds-pred db probe-name)})
      {:instruction (instruction db probe-name)
       :contract-form (observations-contract)
       :holds-check (finding-holds-pred db probe-name)})))
