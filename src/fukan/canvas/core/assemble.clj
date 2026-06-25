(ns fukan.canvas.core.assemble
  "The assembler: walk authored InstanceValues → plain node/rel datom-maps (stamp identity,
   resolve refs, recurse into inline values). The maps are engine-neutral; the native Cozo build
   (`fukan.cozo.build`) writes them to the substrate."
  (:require [fukan.canvas.core.substrate :as s]))

;; ── assembler: instance-vars → stamp identity → resolve refs → node/rel maps ──

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
