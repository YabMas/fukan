(ns fukan.cozo.build
  "The datascript-free NATIVE build: assemble InstanceValues straight into a Cozo
   substrate, no datascript intermediate. Reproduces what datascript's transaction
   did implicitly — dedup nodes by `:entity/id` (value-identity) and rels by
   `:rel/id`, assign integer eids, and resolve `:rel/from`/`:rel/to` lookup-refs —
   then writes the datoms through the shared `mirror/load-datoms`.

   The substrate it produces is content-identical to the datascript build mirrored;
   eids differ (assigned here, not by datascript), but every query is name-based so
   results agree — verified against the datascript build by the oracle."
  (:require [fukan.canvas.core.assemble :as assemble]
            [fukan.canvas.core.substrate :as sub]
            [fukan.cozo.mirror :as mirror]))

(defn- roots-of
  "`[id InstanceValue]` roots for `vars`, mirroring assemble-vars' identity rule:
   a named entity authored without a name takes the var's simple name; identity is
   the content key (values) or the var-id (named)."
  [vars]
  (for [v vars]
    (let [iv0 (deref v)
          iv  (if (and (not (:value? iv0)) (nil? (:name iv0)))
                (assoc iv0 :name (sub/var-simple-name v))
                iv0)
          id  (if (:value? iv) (sub/value-content-key iv) (sub/var-id v))]
      [id iv])))

(defn- instances->datoms
  "`[id InstanceValue]` roots → a seq of `[eid attr v]` datoms. Dedups nodes by
   `:entity/id` (merging their maps — the value-identity datascript did via
   `:db.unique/identity`) and rels by `:rel/id`, assigns disjoint integer eids to
   nodes then rels, and resolves each `[:entity/id id]` ref to the id's eid."
  [id+ivs]
  (let [{:keys [nodes rels]} (assemble/emit-instances id+ivs)
        node-by-id (reduce (fn [m n] (update m (:entity/id n) merge n)) {} nodes)
        rel-by-id  (reduce (fn [m r] (assoc m (:rel/id r) r)) {} rels)
        n-nodes    (count node-by-id)
        node-eid   (zipmap (keys node-by-id) (range))
        rel-eid    (zipmap (keys rel-by-id) (range n-nodes (+ n-nodes (count rel-by-id))))
        ref->eid   (fn [v] (if (and (vector? v) (= :entity/id (first v))) (node-eid (second v)) v))]
    (concat
     (for [[id n] node-by-id, [a v] (dissoc n :entity/id) :when (some? v)]
       [(node-eid id) a v])
     (for [[id r] rel-by-id, [a v] (dissoc r :rel/id)]
       [(rel-eid id) a (ref->eid v)]))))

(defn vars->cozo
  "Build a Cozo substrate natively (no datascript) from instance-bearing `vars`:
   roots → native datoms → `mirror/load-datoms`. Returns the open Cozo db."
  [vars]
  (mirror/load-datoms (instances->datoms (roots-of vars))))

(defn- collect
  "Every instance-bearing interned var across the (already-required) `ns-syms`."
  [ns-syms]
  (for [ns-sym ns-syms
        [_ v]  (ns-interns ns-sym)
        :when  (sub/instance-value? (deref v))]
    v))

(defn nss->cozo
  "Build a Cozo substrate natively from the instance-vars of the (already-loaded)
   `ns-syms` — the namespace-scan entry to `vars->cozo`. Returns the open Cozo db."
  [ns-syms]
  (vars->cozo (collect ns-syms)))
