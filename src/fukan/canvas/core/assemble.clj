(ns fukan.canvas.core.assemble
  (:require [datascript.core :as d]
            [fukan.canvas.core.structure :as s]))

(defn collect
  "Seq of [var InstanceValue] for every instance-bearing interned var across the
   given namespaces."
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
   recursing into inline-value targets. Returns [nodes rels]."
  [iv id nodes rels]
  (let [nodes (conj nodes (node-map id iv))]
    (reduce
     (fn [[nodes rels] {:keys [rk label targets card]}]
       (reduce
        (fn [[nodes rels] [idx t]]
          (let [[tid nodes rels] (target-id+walk t id rk idx nodes rels)]
            [nodes
             (conj rels (cond-> {:rel/id   (str id "|" (name rk) "|" idx "|" tid)
                                 :rel/from [:entity/id id] :rel/kind rk
                                 :rel/to   [:entity/id tid]}
                          label                (assoc :rel/label label)
                          (= card :ordered)    (assoc :rel/order idx)))]))
        [nodes rels]
        (map-indexed vector targets)))
     [nodes rels]
     (:clauses iv))))

(defn assemble
  "Scan `ns-syms` for instance-vars and build one structure db (nodes first, then rels).
   Transacts all nodes before any rels so datascript lookup-refs resolve across cycles."
  [ns-syms]
  (let [[nodes rels] (reduce (fn [[nodes rels] [v iv]]
                               (walk iv (if (:value? iv) (s/value-content-key iv) (s/var-id v)) nodes rels))
                             [[] []]
                             (collect ns-syms))]
    (-> (s/create) (d/db-with nodes) (d/db-with rels))))
