(ns fukan.canvas.core.assemble
  (:require [datascript.core :as d]
            [fukan.canvas.core.structure :as s]))

(defn- collect
  "Seq of [var InstanceValue] for every instance-bearing interned var across the
   given namespaces. Internal var-discovery behind the public `assemble`."
  [ns-syms]
  (for [ns-sym ns-syms
        [_ v] (ns-interns ns-sym)
        :when (s/instance-value? (deref v))]
    [v (deref v)]))

;; ── assembler: scan instance-vars → stamp identity → resolve refs → transact ──

(defn- node-id [iv owner-id path]
  ;; a named entity uses its var-id (passed in for top vars); a ^:value uses its
  ;; content key; an anonymous inline entity uses an owner-path id.
  (if (:value? iv) (s/value-content-key iv) (str owner-id "#" path)))

(defn- node-map [id iv]
  (cond-> (merge {:entity/id id :structure/of (:tag iv)} (:scalars iv))
    (:name iv) (assoc :entity/name (:name iv))
    (:doc iv)  (assoc :entity/doc (:doc iv))))

(declare walk)

(defn- target-id+walk
  "Resolve a clause target to an id, walking inline values to also emit their nodes/rels.
   Returns [id nodes rels]."
  [t owner-id rk idx nodes rels]
  (cond
    (var? t)              [(s/var-id t) nodes rels]
    (s/instance-value? t) (let [id (node-id t owner-id (str (name rk) "/" idx))
                                [ns rs] (walk t id nodes rels)]
                            [id ns rs])
    :else (throw (ex-info (str "unresolvable target " (pr-str t)) {:rk rk}))))

(defn- walk
  "Emit `iv` (already assigned `id`): conj its node-map and its reified relations,
   recursing into inline-value targets. Returns [nodes rels].

   The per-slot index runs ACROSS clauses (`(child a) (child b)` orders like
   `(child a b)`): sequence slots (:many/:some) record it as `:rel/order` and keep
   duplicate targets distinct; a :set slot's rel-id omits it, so duplicate targets
   collapse to one relation."
  [iv id nodes rels]
  (let [nodes (conj nodes (node-map id iv))
        [nodes rels _]
        (reduce
         (fn [acc {:keys [rk labels targets card]}]
           (reduce
            (fn [[nodes rels counters] [i t]]
              (let [n     (get counters rk 0)
                    [tid nodes rels] (target-id+walk t id rk n nodes rels)
                    label (when labels (nth labels i nil))]
                [nodes
                 (conj rels (cond-> {:rel/id   (if (= card :set)
                                                 (str id "|" (name rk) "|" tid)
                                                 (str id "|" (name rk) "|" n "|" tid))
                                     :rel/from [:entity/id id] :rel/kind rk
                                     :rel/to   [:entity/id tid]}
                              label                 (assoc :rel/label label)
                              (#{:many :some} card) (assoc :rel/order n)))
                 (assoc counters rk (inc n))]))
            acc
            (map-indexed vector targets)))
         [nodes rels {}]
         (:clauses iv))]
    [nodes rels]))

(defn assemble-vars
  "Build one structure db from an explicit collection of instance-bearing vars.
   Useful for assembling ad-hoc models (e.g. in negative tests) without a namespace scan."
  [vars]
  (let [[nodes rels] (reduce (fn [[nodes rels] v]
                               (let [iv0 (deref v)
                                     ;; a named entity authored without an explicit name
                                     ;; takes its :entity/name from the binding var's
                                     ;; simple name (values stay anonymous)
                                     iv (if (and (not (:value? iv0)) (nil? (:name iv0)))
                                          (assoc iv0 :name (s/var-simple-name v))
                                          iv0)
                                     id (if (:value? iv) (s/value-content-key iv) (s/var-id v))]
                                 (walk iv id nodes rels)))
                             [[] []]
                             vars)]
    (-> (s/create) (d/db-with nodes) (d/db-with rels))))

(defn emit-instances
  "Walk explicit `[id InstanceValue]` roots into `{:nodes [...] :rels [...]}` maps
   WITHOUT transacting — for builders that merge into an EXISTING db (e.g. the
   grammar reflector). Inline-value children get owner-path / content-key ids, so
   value dedup holds across separate emits into one db."
  [id+ivs]
  (let [[nodes rels] (reduce (fn [[nodes rels] [id iv]] (walk iv id nodes rels))
                             [[] []]
                             id+ivs)]
    {:nodes nodes :rels rels}))

(defn assemble-instances
  "Build one structure db from explicit `[id InstanceValue]` roots — programmatic
   construction with no var scan, for builders that synthesize instances at runtime
   (e.g. a code extractor). Nodes are transacted before rels so lookup-refs resolve."
  [id+ivs]
  (let [{:keys [nodes rels]} (emit-instances id+ivs)]
    (-> (s/create) (d/db-with nodes) (d/db-with rels))))

(defn assemble
  "Scan `ns-syms` for instance-vars and build one structure db (nodes first, then rels).
   Transacts all nodes before any rels so datascript lookup-refs resolve across cycles."
  [ns-syms]
  (assemble-vars (map first (collect ns-syms))))
