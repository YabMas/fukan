(ns fukan.canvas.core.substrate
  "The kernel SUBSTRATE — the node layer beneath the `defstructure` grammar: what a node IS
   and how it is IDENTIFIED, plus the empty db they live in. Depends on NOTHING but datascript
   (the foundation the grammar, the assembler, the checker, and value-construction all sit ON).

   A node is either a named entity (its identity is its authoring var's fully-qualified name —
   `var-id`) or a `^:value` InstanceValue (its identity is a purely-structural content key —
   `value-content-key`, so structurally-equal values collapse to one node). `InstanceValue` is the
   in-flight record an authored instance evaluates to before the assembler stamps it into the db.

   This is the substrate the structure registry sits on — extracted DOWNWARD from the grammar so the
   foundation has a code home of its own (it long had only a self-model `Node`/`Relation` portrait)."
  (:require [datascript.core :as d]))

;; ── the db ────────────────────────────────────────────────────────────────────

(def schema
  {:entity/id    {:db/unique :db.unique/identity}
   :entity/name  {:db/index true}
   :entity/doc   {}                                            ; instance documentation (the docstring position)
   :structure/of {:db/index true}                              ; the structure tag of an instance
   ;; reified slot relations — the seam carrying :rel/label / :rel/order
   :rel/id       {:db/unique :db.unique/identity}
   :rel/from     {:db/valueType :db.type/ref}
   :rel/kind     {:db/index true}
   :rel/to       {:db/valueType :db.type/ref}
   :rel/label    {}
   :rel/order    {}                                           ; authoring position in a sequence (:*/:+) slot
   :rel/payload  {}})                                         ; a payload slot's companion :val key (on reified grammar slot-edges)

(defn create [] (d/empty-db schema))

;; ── value-authoring: instances as values, references as vars ──────────────────

(defrecord InstanceValue [tag name doc scalars clauses value?])

(defn instance-value? [x] (instance? InstanceValue x))

(defn var-id
  "The fully-qualified-var-name id of an instance-bearing var."
  [v]
  (let [m (meta v)] (str (ns-name (:ns m)) "/" (:name m))))

(defn var-simple-name
  "The simple (unqualified) name of an instance-bearing var, as a string — the
   default `:entity/name` for an entity authored without an explicit name."
  [v]
  (name (:name (meta v))))

(defn value-content-key
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
