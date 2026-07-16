(ns fukan.canvas.core.substrate
  "The kernel SUBSTRATE — the node layer beneath the `defstructure` grammar: what a node IS
   and how it is IDENTIFIED. Depends on NOTHING (the foundation the grammar, the assembler,
   the checker, and value-construction all sit ON).

   A node is either a named entity (its identity is its authoring var's fully-qualified name —
   `var-id`) or a `^:value` InstanceValue (its identity is a purely-structural content key —
   `value-content-key`, so structurally-equal values collapse to one node). `InstanceValue` is the
   in-flight record an authored instance evaluates to before the assembler stamps it into the db.

   This is the substrate the structure registry sits on — extracted DOWNWARD from the grammar so the
   foundation has a code home of its own (it long had only a self-model `Node`/`Relation` portrait)."
  )

;; ── value-authoring: instances as values, references as vars ──────────────────

(defrecord InstanceValue [tag name doc scalars clauses value?])

(defn ^{:malli/schema [:=> [:cat :any] :boolean]}
  instance-value? [x] (instance? InstanceValue x))

;; A reference to an already-emitted node by its natural-key `entity/id` — the assembler resolves it
;; to that id and emits NO node (the node arrives via its own root). The generated analog of a var
;; reference (a var resolves to `var-id`; a `Ref` resolves to a literal id): an EXTRACTED feeder wires
;; cross-references (a fact op's `:calls`, a fact module's `:child`) by natural key, so the ONE
;; assembler links them exactly as it links authored var-refs — no post-build eid-arithmetic pass.
(defrecord Ref [id])

(defn ^{:malli/schema [:=> [:cat :any] :boolean]}
  ref? [x] (instance? Ref x))

(def stratum-attr
  "The PROVENANCE attribute — the design/fact stratum marker. The BUILD stamps it on every
   non-value node arriving through the extraction plug-point (`fukan.cozo.build/model->cozo`
   via `stamp-stratum`); the generic `(fact ?n)`/`(design ?n)` substrate rules
   (`fukan.canvas.core.rules/substrate-rules`) read it — embedded LITERALLY in `substrate-rules`
   (rules are pure quoted data; keep in sync) AND, in `fukan.canvas.core.structure`, in the
   `:correspondence` declaration handler (the twin-rule generator) and the demand-law generators
   (node-demand-law / container-demand-laws / root-guard-clause / covered-from-law) — update all in
   concert. Provenance has ONE writer — `stamp-stratum` (the pipeline); it is NOT an authoring concern,
   so no vocab slot exposes it. Tests synthesize a fact-side node the same way, via
   `fukan.cozo.build/fact-vars->cozo` (stamps the designated fixtures fact)."
  :val/extracted)

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  stamp-stratum
  "Stamp an InstanceValue tree as FACT-stratum: `stratum-attr` true on `iv` and, recursively,
   on every nested non-value instance target. `^:value` instances stay unstamped —
   content-deduped values are STRATUM-FREE (a design-side and a fact-side occurrence of an
   equal value are ONE node; stamping would fork their content keys)."
  [iv]
  (if (:value? iv)
    iv
    (-> iv
        (update :scalars assoc stratum-attr true)
        (update :clauses (fn [cs]
                           (mapv (fn [c]
                                   (update c :targets
                                           (fn [ts]
                                             (mapv #(if (instance-value? %) (stamp-stratum %) %) ts))))
                                 cs))))))

(defn ^{:malli/schema [:=> [:cat :any] :string]}
  var-id
  "The fully-qualified-var-name id of an instance-bearing var."
  [v]
  (let [m (meta v)] (str (ns-name (:ns m)) "/" (:name m))))

(defn ^{:malli/schema [:=> [:cat :any] :string]}
  var-simple-name
  "The simple (unqualified) name of an instance-bearing var, as a string — the
   default `:entity/name` for an entity authored without an explicit name."
  [v]
  (name (:name (meta v))))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  value-content-key
  "A deterministic, purely structural identity for a ^:value InstanceValue.
   Returns a pr-str over [tag-name scalars-map slot-entries] where each entry is
   [rk-name [[label target-id] …]] with targets resolved recursively:
     - a Var → (var-id v)
     - an InstanceValue → (value-content-key iv) (recurse)
   Clauses are grouped per slot and the groups sorted by name, so clause ORDER
   across different slots never splits identity. Within a slot, sequence cards
   (:many/:some) preserve authoring order ([A B] ≠ [B A]); a :set card sorts its
   pairs, so order — and duplicate targets — are excluded from identity."
  [^InstanceValue iv]
  (let [tag-name (clojure.core/str (:tag iv))   ; qualified — value identity is ns-distinct
        scalars  (into (sorted-map) (:scalars iv))
        resolve-target (fn resolve-target [t]
                         (cond
                           (var? t)             (var-id t)
                           (instance? InstanceValue t) (value-content-key t)
                           :else                (pr-str t)))
        entries  (->> (group-by :rk (:clauses iv))
                      (map (fn [[rk clauses]]
                             (let [pairs (vec (for [{:keys [targets labels]} clauses
                                                    [i t] (map-indexed vector targets)]
                                                [(when labels (nth labels i nil))
                                                 (resolve-target t)]))
                                   pairs (if (= :set (:card (first clauses)))
                                           (vec (distinct (sort-by pr-str pairs)))
                                           pairs)]
                               [(clojure.core/name rk) pairs])))
                      (sort-by first)
                      vec)]
    (pr-str [tag-name scalars entries])))
