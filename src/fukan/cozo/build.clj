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
            [canvas.vocab.grammar :as grammar]
            [fukan.cozo.db :as db]
            [fukan.cozo.mirror :as mirror]
            [fukan.cozo.rules :as rules]))

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

(defn- maps->datoms
  "Node/rel maps → `[eid attr v]` datoms. Dedups nodes by `:entity/id` (merging their maps — the
   value-identity datascript did via `:db.unique/identity`) and rels by `:rel/id`, assigns disjoint
   integer eids from `offset` (nodes then rels), and resolves each `[:entity/id id]` ref to its eid."
  [nodes rels offset]
  (let [node-by-id (reduce (fn [m n] (update m (:entity/id n) merge n)) {} nodes)
        rel-by-id  (reduce (fn [m r] (assoc m (:rel/id r) r)) {} rels)
        n-nodes    (count node-by-id)
        node-eid   (zipmap (keys node-by-id) (map #(+ offset %) (range)))
        rel-eid    (zipmap (keys rel-by-id) (map #(+ offset n-nodes %) (range)))
        ref->eid   (fn [v] (if (and (vector? v) (= :entity/id (first v))) (node-eid (second v)) v))]
    ;; KEEP :entity/id / :rel/id as datoms (datascript stored them as :db.unique/identity, so the
    ;; mirror has them) — readers like the grammar print-dual's laws-of + the `of-structure` rule join on
    ;; :entity/id; dropping them silently broke the grammar dual on the native build.
    (concat
     (for [[id n] node-by-id, [a v] n :when (some? v)]
       [(node-eid id) a v])
     (for [[id r] rel-by-id, [a v] r]
       [(rel-eid id) a (ref->eid v)]))))

(defn- instances->datoms
  "`[id InstanceValue]` roots → a seq of `[eid attr v]` datoms (the assembler + `maps->datoms`)."
  [id+ivs]
  (let [{:keys [nodes rels]} (assemble/emit-instances id+ivs)]
    (maps->datoms nodes rels 0)))

(defn vars->cozo
  "Build a Cozo substrate natively (no datascript) from instance-bearing `vars`:
   roots → native datoms → `mirror/load-datoms`. Returns the open Cozo db."
  [vars]
  (mirror/load-datoms (instances->datoms (roots-of vars))))

(defn instances->cozo
  "Build a Cozo substrate from explicit `[id InstanceValue]` roots (code-EMITTED instances,
   not interned vars) — the cozo analog of `assemble/assemble-instances`. Returns the open db."
  [id+ivs]
  (mirror/load-datoms (instances->datoms id+ivs)))

(defn maps->cozo
  "Build a Cozo substrate directly from raw node/rel datom-MAPS (not InstanceValues) — the
   cozo analog of `(-> (sub/create) (d/db-with node-maps) (d/db-with rel-maps))`, the
   low-level entry for hand-building a substrate (e.g. a test exercising a rule in isolation).
   Dedups + resolves `[:entity/id id]` refs via `maps->datoms`, then loads. Returns the open db."
  [node-maps rel-maps]
  (mirror/load-datoms (maps->datoms node-maps rel-maps 0)))

(defn ^:test-support tx-maps->cozo
  "Build a Cozo substrate from one seq of datascript-style transaction MAPS — the cozo analog
   of `(-> (sub/create) (d/db-with tx-maps))`, replicating its tempid + `:db.unique/identity`
   semantics: nodes merge by `:entity/id` (rels by `:rel/id`, last-wins), each `:db/id` tempid
   resolves to its merged eid, and `:rel/from`/`:rel/to` values (a tempid or an `[:entity/id id]`
   lookup-ref) resolve to eids. Lets a test hand-build a substrate with tempid wiring. Returns
   the open Cozo db."
  [tx-maps]
  (let [rels      (filter :rel/id tx-maps)
        nodes     (remove :rel/id tx-maps)
        nkey      (fn [n] (if (contains? n :entity/id) [:id (:entity/id n)] [:tmp (:db/id n)]))
        merged    (reduce (fn [m n] (update m (nkey n) merge n)) {} nodes)
        node-keys (vec (keys merged))
        key->eid  (zipmap node-keys (range))
        n-nodes   (count node-keys)
        rel->eid  (zipmap rels (map #(+ n-nodes %) (range)))
        tmp->eid  (into {} (for [n nodes :when (:db/id n)] [(:db/id n) (key->eid (nkey n))]))
        id->eid   (into {} (for [[[t v] e] key->eid :when (= t :id)] [v e]))
        resolve   (fn [v] (cond (and (vector? v) (= :entity/id (first v))) (id->eid (second v))
                                (integer? v)                               (tmp->eid v)
                                :else                                      v))]
    (mirror/load-datoms
     (concat
      (for [[k attrs] merged, [a v] (dissoc attrs :db/id) :when (some? v)] [(key->eid k) a v])
      (for [r rels, [a v] (dissoc r :db/id)] [(rel->eid r) a (if (#{:rel/from :rel/to} a) (resolve v) v)])))))

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

(defn- add-calls-cozo
  "Ground the actual call graph as `:calls` rels in `cdb` — the datascript-free
   analog of the extractor's `add-calls`. Resolves each var-usage's caller/callee
   to the eid of the extracted Operation named `fn` in module `ns` (a single cozo
   query `{[ns name] → eid}`), then inserts the `:calls` rels above the current max
   eid. Returns `cdb`."
  [cdb var-usages]
  (let [op-eid  (into {} (map (fn [[ns name eid]] [[ns name] eid]))
                      (db/q cdb (str rules/eav "
?[ns, name, eid] := structof[eid, 'canvas.vocab.code.operation/Operation'], extracted[eid],
                   ename[eid, name], in_module[eid, ns]")))
        max-eid (ffirst (db/q cdb "alle[e] := *t_int[e, _, _]
alle[e] := *t_str[e, _, _]
alle[e] := *t_bool[e, _, _]
?[max(e)] := alle[e]"))
        pairs   (->> var-usages
                     (keep (fn [{:keys [from from-var to name]}]
                             (when (and from-var to name)
                               (let [c (op-eid [(str from) (str from-var)])
                                     e (op-eid [(str to)   (str name)])]
                                 (when (and c e (not= c e)) [c e])))))
                     distinct vec)
        int-rows (vec (mapcat (fn [n [c e]]
                                (let [rid (+ max-eid 1 n)]
                                  [[rid "rel/from" c] [rid "rel/to" e] [rid "rel/order" n]]))
                              (range) pairs))
        str-rows (vec (map-indexed (fn [n _] [(+ max-eid 1 n) "rel/kind" "calls"]) pairs))]
    (when (seq pairs)
      (db/q cdb "?[e, a, v] <- $rows :put t_int {e, a, v}" {:rows int-rows})
      (db/q cdb "?[e, a, v] <- $rows :put t_str {e, a, v}" {:rows str-rows}))
    cdb))

(defn- max-eid
  "The maximum integer eid currently in `cdb` (-1 when empty) — the offset base for additive inserts.
   NB the aggregate goes in the rule HEAD (`?[max(e)]`); `mx = max(e)` in the body does NOT aggregate."
  [cdb]
  (or (ffirst (db/q cdb "alle[e] := *t_int[e, _, _]
alle[e] := *t_str[e, _, _]
alle[e] := *t_bool[e, _, _]
?[max(e)] := alle[e]"))
      -1))

(defn with-grammar-cozo
  "Reflect the model's grammar into the already-built Cozo db `cdb` — the datascript-free analog of
   `grammar/with-grammar`. UPSERT by `:entity/id`: a reflected node whose id already exists (a
   `^:value` Schema shared with the model — what datascript merged via `:db.unique/identity`) REUSES
   that eid; only the genuinely-new grammar nodes (Structure/Vocabulary/Law) take fresh eids above the
   current max. Then every (new) grammar rel resolves its refs and inserts. Returns `cdb`."
  [cdb extra-seeds]
  (let [tags     (map (comp keyword first) (db/q cdb "?[t] := *t_str[_, 'structure/of', t]"))
        {:keys [nodes rels]} (grammar/reflect tags extra-seeds)
        exist    (into {} (db/q cdb "?[id, e] := *t_str[e, 'entity/id', id]"))   ; existing {entity/id → eid}
        by-id    (reduce (fn [m n] (update m (:entity/id n) merge n)) {} nodes)
        new-ids  (vec (remove exist (keys by-id)))
        base     (inc (max-eid cdb))
        new-eid  (zipmap new-ids (map #(+ base %) (range)))
        node-eid (merge exist new-eid)                                          ; reused (model) + new (grammar)
        rel-by-id (reduce (fn [m r] (assoc m (:rel/id r) r)) {} rels)
        rel-eid  (zipmap (keys rel-by-id) (map #(+ base (count new-ids) %) (range)))
        ref->eid (fn [v] (if (and (vector? v) (= :entity/id (first v))) (node-eid (second v)) v))]
    (mirror/insert-datoms
     cdb
     (concat (for [id new-ids, [a v] (by-id id) :when (some? v)] [(new-eid id) a v])
             (for [[id r] rel-by-id, [a v] r] [(rel-eid id) a (ref->eid v)])))))

(defn model->cozo
  "Native FULL build (no datascript): the instance-vars of canvas `ns-syms` + the
   extraction `{:roots :var-usages}` facts → one native Cozo substrate, with the
   `:calls` graph grounded and the grammar reflected. Assembling canvas + extraction
   roots in one native pass resolves cross-refs without a union/merge. Returns the open Cozo db."
  [ns-syms {:keys [roots var-usages]}]
  (-> (mirror/load-datoms (instances->datoms (concat (roots-of (collect ns-syms)) roots)))
      (add-calls-cozo var-usages)
      ;; seed reflection with EVERY canvas ns (as build-model does) so a zero-instance law-holder
      ;; stratum (canvas.vocab.fukan's Totality/LensCoverage) still reflects
      (with-grammar-cozo ns-syms)))
