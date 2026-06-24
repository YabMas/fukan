(ns fukan.cozo.reading
  "Read-side queries ported onto the Cozo mirror — each the Cozo twin of a
   datascript reader, which the datascript→Cozo oracle asserts agreement with.
   TRANSITIONAL framing: becomes the read surface once datascript is gone (P5)."
  (:require [clojure.set :as set]
            [fukan.cozo.db :as db]
            [fukan.cozo.rules :as rules]))

(defn module-dependencies
  "The complete module→module dependency graph (calls ∪ data-adoption) as a set
   of [caller-name callee-name] pairs, computed in CozoScript over the Cozo mirror
   `cdb`. The Cozo twin of `canvas.vocab.code.module/module-dependencies`."
  [cdb]
  (set (db/q cdb (str rules/eav rules/module-depends
                      "?[caller, callee] := mdep[m, n], ename[m, caller], ename[n, callee]"))))

(defn latent-boundaries
  "Bottom-up boundary discovery (Parnas's decomposition criterion / Interface
   Segregation, made mechanical): code modules whose PUBLIC surface has split into
   ≥2 consumer-DISJOINT clienteles. COMPOSITIONAL — the `surface` building blocks
   (public_op / clientele / co_consumed / consumed) feed Cozo's `ConnectedComponents`
   fixed rule, then a cohesion (≥2-op) + PROPER-subset gate; the bundles are
   assembled in Clojure. Returns `{module-name [{:ops [name…] :clientele
   [module-name…]} …]}`. The Cozo twin of `canvas.vocab.code.subsystem/latent-boundaries`."
  [cdb]
  (->> (db/q cdb (str rules/eav rules/surface "
comp[node, cid] <~ ConnectedComponents(co_consumed[a, b])
csize[mod, cid, count(node)] := comp[node, cid], in_module[node, mod]
total[mod, count(o)]         := consumed[o, mod]
flagged[mod, cid] := csize[mod, cid, sz], sz >= 2, total[mod, t], sz < t
?[mod, cid, opname, clmod] := flagged[mod, cid], comp[node, cid], in_module[node, mod],
                             ename[node, opname], clientele[node, clmod]
"))
       (group-by (fn [[mod cid _ _]] [mod cid]))
       (reduce (fn [acc [[mod _cid] grp]]
                 (let [bundle {:ops       (sort (distinct (map #(nth % 2) grp)))
                               :clientele (sort (distinct (map #(nth % 3) grp)))}]
                   (update acc mod (fnil conj []) bundle)))
               {})
       (reduce-kv (fn [acc mod bs] (assoc acc mod (vec (sort-by (comp count :ops) > bs))))
                  (sorted-map))))

(defn throw-spread
  "How partiality spreads: `{:direct #{ops that throw themselves} :transitive-only
   #{ops that reach :throws only via a call}}`. Composes `performs` (direct) and
   `reaches_effect` (transitive) over the Cozo mirror `cdb`. The Cozo twin of
   `canvas.vocab.code.effect/throw-spread`."
  [cdb]
  (let [base     (str rules/eav rules/effect)
        names    (fn [q] (set (map first (db/q cdb (str base q)))))
        direct   (names "
?[on] := structof[o, 'canvas.vocab.code.operation/Operation'], extracted[o], performs[o, 'throws'], ename[o, on]")
        reachers (names "
?[on] := structof[o, 'canvas.vocab.code.operation/Operation'], extracted[o], reaches_effect[o, 'throws'], ename[o, on]")]
    {:direct direct :transitive-only (set/difference reachers direct)}))

(defn- effect-pairs
  "`{op-name #{effect-name…}}` from a (op-name, effect-name) row set."
  [rows]
  (reduce (fn [m [on en]] (update m on (fnil conj #{}) en)) {} rows))

(defn effect-drift
  "Per MODELLED op, the disagreement between its authored `:performs` and its
   extracted twin's TRANSITIVE effect profile: `{op-name {:undeclared #{reached ∖
   declared} :phantom #{declared ∖ reached}}}` for every op with a disagreement.
   Composes `op_twin`, `performs` and `reaches_effect` over the mirror `cdb`. The
   Cozo twin of `canvas.vocab.code.effect/effect-drift`."
  [cdb]
  (let [script   (str rules/eav rules/correspondence rules/effect)
        declared (effect-pairs (db/q cdb (str script "
?[on, en] := op_twin[o, b], ename[o, on], performs[o, en]")))
        reached  (effect-pairs (db/q cdb (str script "
?[on, en] := op_twin[o, b], ename[o, on], reaches_effect[b, en]")))]
    (reduce (fn [acc on]
              (let [dec (get declared on #{}), rea (get reached on #{})
                    und (set/difference rea dec), phan (set/difference dec rea)]
                (cond-> acc
                  (or (seq und) (seq phan)) (assoc on {:undeclared und :phantom phan}))))
            {} (into (set (keys declared)) (keys reached)))))
