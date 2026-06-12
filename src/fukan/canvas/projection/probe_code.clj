(ns fukan.canvas.projection.probe-code
  "Projector for the executable probe surface: reads the FINDING model (the structure db) and
   emits, per reading, the artifacts to run it. A reading is identified by its LENS name (a Finding
   reads `:through` a lens). Pure (db → forms/strings).
   - A COMPOSING reading (its Finding is realized by a `ReadingComposition` that `:calls` a modelled
     capability) → a mechanical fn-form that wraps the capability's output as violation
     observations, plus the uniform contract.
   - A FRESH reading (no composition) → a projected Instruction for an implementing LLM to write the
     leaf, plus the uniform contract.

   The finding contract is uniform (observations: {focus tag note}), so no per-finding shape is read."
  (:require [datascript.core :as d]))

(def ^:private capability->var
  "Modelled capability name → the real var the projected code calls."
  {"check" 'fukan.canvas.core.structure/check})

(defn- finding-eid
  "The Finding eid that reads `:through` the named lens (nil if none)."
  [db lens-name]
  (ffirst (d/q '[:find ?f :in $ ?ln
                 :where [?l :structure/of :canvas.acts/Lens] [?l :entity/name ?ln]
                        [?r :rel/from ?f] [?r :rel/kind :through] [?r :rel/to ?l]
                        [?f :structure/of :canvas.acts/Finding]]
               db lens-name)))

(defn- finding-gating [db lens-name]
  (:val/gating (d/entity db (finding-eid db lens-name))))

(defn- finding-focus [db lens-name]
  (ffirst (d/q '[:find ?fc :in $ ?ln
                 :where [?l :structure/of :canvas.acts/Lens] [?l :entity/name ?ln]
                        [?l :val/focus ?fc]]
               db lens-name)))

(defn- finding-holds [db lens-name]
  (:val/holds (d/entity db (finding-eid db lens-name))))

(defn- finding-holds-pred [db lens-name]
  ;; the executable holds-predicate lives on the FindingCheck that :realizes the finding
  ;; (the realization view); :val/pred is unique to FindingCheck (the ReadingComposition has none)
  (ffirst (d/q '[:find ?p :in $ ?f
                 :where [?rz :rel/kind :realizes] [?rz :rel/to ?f]
                        [?rz :rel/from ?fc] [?fc :val/pred ?p]]
               db (finding-eid db lens-name))))

(defn- reading-capability [db lens-name]
  ;; the kernel capability a gating reading composes lives on the ReadingComposition that
  ;; :realizes the finding (the realization view), not on the domain Finding itself
  (let [results (d/q '[:find ?cn :in $ ?f
                       :where [?rz :rel/kind :realizes] [?rz :rel/to ?f]
                              [?rz :rel/from ?pc] [?pc :structure/of :canvas.architecture.acts/ReadingComposition]
                              [?cr :rel/from ?pc] [?cr :rel/kind :calls] [?cr :rel/to ?c]
                              [?c :structure/of :lib.code/Operation] [?c :entity/name ?cn]]
                     db (finding-eid db lens-name))]
    (when (> (count results) 1)
      (throw (ex-info "cut-1 projector handles a single :calls edge only"
                      {:lens lens-name :capabilities (mapv first results)})))
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
  [db lens-name]
  (let [focus (finding-focus db lens-name)
        gate  (finding-gating db lens-name)
        holds (finding-holds db lens-name)]
    (str "Implement the fukan reading `probe-" lens-name "`.\n\n"
         "Focus: " focus "\n"
         "Signature: (probe-" lens-name " [target-db]) where target-db is a datascript model db.\n"
         "Return the finding {:lens \"" lens-name "\", :gating " gate ", :observations [...]}, "
         "where each observation is {:focus <a set of node eids>, :as <a keyword tag>, "
         ":note <a descriptor string>}.\n"
         "Invariant (holds): " holds)))

(defn project-probe
  "Project the reading read through the named lens.
   Composing reading → {:fn-form <fn building violation observations> :contract-form <pred> :holds-check <form|nil>}.
   Fresh reading → {:instruction <spec> :contract-form <pred> :holds-check <form|nil>}."
  [db lens-name]
  (let [cap (reading-capability db lens-name)]
    (if cap
      (let [cap-var (capability->var cap)]
        (when-not cap-var
          (throw (ex-info (str "no known capability var for " (pr-str cap))
                          {:lens lens-name :capability cap})))
        {:fn-form (list 'fn '[target-db]
                        {:lens lens-name
                         :gating (finding-gating db lens-name)
                         :observations
                         (list 'mapv
                               (list 'fn '[v]
                                     {:focus (list 'into #{} '(mapcat identity) '(:offenders v))
                                      :as :violation
                                      :note '(str v)})
                               (list cap-var 'target-db))})
         :contract-form (observations-contract)
         :holds-check (finding-holds-pred db lens-name)})
      {:instruction (instruction db lens-name)
       :contract-form (observations-contract)
       :holds-check (finding-holds-pred db lens-name)})))
