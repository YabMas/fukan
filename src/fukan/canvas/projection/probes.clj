(ns fukan.canvas.projection.probes
  "Implemented probes — the LLM-authored leaves whose specs are projected from the model.
   Each is a pure (model-db -> finding) reader. `run-probe` is the registry: each leaf
   self-registers via `defmethod run-probe`, and `run`/`run-all` are the live entry:
   run a probe against the held model.
   probe-patterns implements the Instruction projected from the `patterns` probe;
   probe-integrity realizes the modelled `integrity` probe by composing the kernel's
   `check`."
  (:require [clojure.string :as str]
            [datascript.core :as d]
            [fukan.canvas.core.structure :as structure]
            [fukan.target.correspondence :as corr]))

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

(defn probe-survey
  "A structural overview (a View): how many nodes of each structure kind, optionally
   scoped to `focus`. Returns {:lens \"survey\" :gating false :finding [\"N Kind\" …]}."
  ([target-db] (probe-survey target-db nil))
  ([target-db focus]
   (let [in? (if focus (set focus) (constantly true))]
     {:lens    "survey"
      :gating  false
      :finding (->> (d/q '[:find ?e ?k :where [?e :structure/of ?k]] target-db)
                    (filter (fn [[e _]] (in? e)))
                    (map second) frequencies (sort-by val >)
                    (mapv (fn [[k n]] (str n " " (name k)))))})))

(defn probe-consistency
  "Stage-name ambiguity (a View): Stage names borne by more than one module — a
   consistency signal, since name-based correspondence resolves a Stage by (name,
   module). Empty ⇔ every modelled Stage name is unambiguous. Scopable to `focus`."
  ([target-db] (probe-consistency target-db nil))
  ([target-db focus]
   (let [in? (if focus (set focus) (constantly true))]
     {:lens    "consistency"
      :gating  false
      :finding (->> (d/q '[:find ?s ?sn ?mn
                           :where [?s :structure/of :Stage] [?s :entity/name ?sn]
                                  [?m :module/child ?s] [?m :entity/name ?mn]] target-db)
                    (filter (fn [[s _ _]] (in? s)))
                    (reduce (fn [acc [_ sn mn]] (update acc sn (fnil conj #{}) mn)) {})
                    (filter (fn [[_ mods]] (> (count mods) 1)))
                    (sort-by key)
                    (mapv (fn [[sn mods]] (str sn " in " (count mods) " modules: " (str/join ", " (sort mods))))))})))

(defn probe-tar-pit
  "Complexity hotspots (a View): the most-connected nodes by relation degree (in +
   out), top 10. Tangles worth attention. Scopable to `focus`."
  ([target-db] (probe-tar-pit target-db nil))
  ([target-db focus]
   (let [in?  (if focus (set focus) (constantly true))
         out  (map second (d/q '[:find ?r ?e :where [?r :rel/from ?e]] target-db))
         ins  (map second (d/q '[:find ?r ?e :where [?r :rel/to ?e]] target-db))]
     {:lens    "tar-pit"
      :gating  false
      :finding (->> (frequencies (concat out ins))
                    (filter (fn [[e _]] (in? e)))
                    (sort-by val >) (take 10)
                    (mapv (fn [[e n]]
                            (let [ent (d/entity target-db e)]
                              (str n " edges: " (or (:entity/name ent) "(value)")
                                   " (" (name (:structure/of ent)) ")")))))})))

(defn probe-coverage
  "Spec ↔ code coverage (a gating Signal): extracted Operations not covered by a
   modelling Stage (code→spec gaps). Surfaces correspondence/unrealized-operations.
   Empty ⇔ every Operation is modelled. Global — `focus` is accepted but ignored."
  ([target-db] (probe-coverage target-db nil))
  ([target-db _focus]
   {:lens "coverage" :gating true :finding (vec (sort (corr/unrealized-operations target-db)))}))

(defn probe-drift
  "Spec ↔ code divergence (a gating Signal): modelled Stages not realized by an
   Operation (spec→code gaps). Surfaces correspondence/unrealized-stages. Empty ⇔ the
   model is fully realized. Global — `focus` is accepted but ignored."
  ([target-db] (probe-drift target-db nil))
  ([target-db _focus]
   {:lens "drift" :gating true :finding (vec (sort (corr/unrealized-stages target-db)))}))

(defmulti run-probe
  "The probe surface as a self-registering multimethod: dispatch on probe-name.
   A probe leaf registers by `(defmethod run-probe \"<name>\" [db _ focus] …)`, so
   adding a probe is dropping a method — symmetric with `render-base`. `focus` is a
   node-set the leaf reads through (nil = the whole model)."
  (fn [_db probe-name _focus] probe-name))

(defmethod run-probe :default [_ probe-name _]
  (throw (ex-info (str "no implemented probe " (pr-str probe-name))
                  {:probe probe-name
                   :available (vec (remove #{:default} (keys (methods run-probe))))})))

(defmethod run-probe "survey"      [db _ focus] (probe-survey db focus))
(defmethod run-probe "patterns"    [db _ focus] (probe-patterns db focus))
(defmethod run-probe "consistency" [db _ focus] (probe-consistency db focus))
(defmethod run-probe "tar-pit"     [db _ focus] (probe-tar-pit db focus))
(defmethod run-probe "integrity"   [db _ focus] (probe-integrity db focus))
(defmethod run-probe "coverage"    [db _ focus] (probe-coverage db focus))
(defmethod run-probe "drift"       [db _ focus] (probe-drift db focus))

(defn run
  "Run probe `probe-name` against `target-db`, optionally scoped to `focus`
   (a node-set). Dispatches through `run-probe`; the :default method throws for an
   unregistered name."
  ([target-db probe-name] (run target-db probe-name nil))
  ([target-db probe-name focus] (run-probe target-db probe-name focus)))

(defn run-all
  "Run every registered probe leaf against `target-db` -> {probe-name finding}."
  [target-db]
  (into (sorted-map)
        (for [pn (remove #{:default} (keys (methods run-probe)))]
          [pn (run-probe target-db pn nil)])))
