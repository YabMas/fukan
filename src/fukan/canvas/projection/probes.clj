(ns fukan.canvas.projection.probes
  "Implemented probes — the LLM-authored leaves whose specs are projected from the model.
   Each is a pure (model-db -> finding) reader. `implemented` registers the realized
   leaves and `run`/`run-all` are the live entry: run a probe against the held model.
   probe-patterns implements the Instruction projected from the `patterns` probe;
   probe-integrity realizes the modelled `integrity` probe by composing the kernel's
   `check`."
  (:require [datascript.core :as d]
            [fukan.canvas.core.structure :as structure]))

(defn probe-patterns
  "Recurring structures across the model. Returns {:lens \"patterns\" :gating false
   :finding <a list of strings, one per recurring structure>}.

   A 'recurring structure' is a structural triplet (source-tag, relation-kind, target-tag)
   that appears in more than one distinct reified relation in the model. Each such triplet
   is a repeated architectural pattern — a structural signature of how concepts are
   connected throughout the design. Triplets appearing only once are unique connections,
   not patterns.

   Each finding string is formatted as: \"<count>× <from-tag> -[<rel-kind>]-> <to-tag>\",
   sorted descending by occurrence count."
  ([target-db] (probe-patterns target-db nil))
  ([target-db focus]
   (let [in?  (if focus (set focus) (constantly true))
         rows (d/q '[:find ?r ?f ?ft ?rk ?t ?tt
                     :where [?r :rel/from ?f] [?r :rel/kind ?rk] [?r :rel/to ?t]
                            [?f :structure/of ?ft] [?t :structure/of ?tt]]
                   target-db)]
     {:lens    "patterns"
      :gating  false
      :finding (->> rows
                    (filter (fn [[_r f _ft _rk t _tt]] (and (in? f) (in? t))))  ; scope to focus endpoints
                    (map (fn [[_r _f ft rk _t tt]] [ft rk tt]))
                    frequencies                                                  ; count relations per triplet
                    (filter #(> (val %) 1))
                    (sort-by val >)
                    (mapv (fn [[[ft rk tt] cnt]]
                            (str cnt "× " ft " -[" rk "]-> " tt))))})))

(defn probe-integrity
  "The canonical integrity inspect: the kernel's `check` (laws -> violations) surfaced
   as a gating Finding. This realizes the modelled `integrity` probe — the same probe
   that `:calls` the modelled `check` capability. Returns {:lens \"integrity\"
   :gating true :finding <a list of violation strings>}; an empty finding means the
   model's laws all hold. Integrity is GLOBAL — laws span the whole model — so an
   optional `focus` is accepted (for a uniform probe signature) but ignored."
  ([target-db] (probe-integrity target-db nil))
  ([target-db _focus]
   {:lens    "integrity"
    :gating  true
    :finding (mapv str (structure/check target-db))}))

(def implemented
  "The realized probe leaves: probe name -> (model-db -> finding). The live probe
   surface. Other modelled probes (survey/consistency/tar-pit/coverage/drift) are
   modelled but not yet implemented."
  {"patterns"  probe-patterns
   "integrity" probe-integrity})

(defn run
  "Run the implemented probe `probe-name` against `target-db`, optionally scoped to
   `focus` (a node-set — the probe reads only that sub-graph), returning its finding.
   So a refined focus chains into a probe. Throws if no leaf is implemented."
  ([target-db probe-name] (run target-db probe-name nil))
  ([target-db probe-name focus]
   (if-let [f (implemented probe-name)]
     (f target-db focus)
     (throw (ex-info (str "no implemented probe " (pr-str probe-name))
                     {:probe probe-name :available (vec (keys implemented))})))))

(defn run-all
  "Run every implemented probe against `target-db` -> {probe-name finding}."
  [target-db]
  (into (sorted-map) (map (fn [[n f]] [n (f target-db)])) implemented))
