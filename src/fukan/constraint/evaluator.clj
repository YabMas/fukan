(ns fukan.constraint.evaluator
  "Stratified bottom-up Datalog evaluator.

   Algorithm: partition rules into strata such that negated/aggregated
   atoms in a rule's body reference predicates from strictly earlier
   strata (or EDB only). Within each stratum, iterate naive fixed point.

   Limits — MVP scope:
   - No semi-naive optimisation; full re-evaluation per iteration.
   - No magic-set transformation.
   - Stratification detector trusts the rule author; obviously
     non-stratifiable rule sets (negation cycle) raise ex-info."
  (:require [clojure.set :as set]
            [fukan.constraint.ast :as ast]))

;; ---------------------------------------------------------------------------
;; Stratification
;; ---------------------------------------------------------------------------

(defn- head-predicate [rule] (-> rule :head :predicate))

(defn- negated-or-aggregated-preds [body]
  (mapcat (fn [atm]
            (case (:kind atm)
              :negation    [(-> atm :inner :predicate)]
              :aggregation (map :predicate (filter #(= :atom (:kind %)) (:body atm)))
              []))
          body))

(defn- positive-preds [body]
  (keep (fn [atm] (when (= :atom (:kind atm)) (:predicate atm))) body))

(defn- stratify
  "Partition rules into strata. Stratum N contains rules whose negated/aggregated
   body predicates are all in strata < N. Returns vector of vectors (strata in order)."
  [rules]
  (let [all-preds (set (map head-predicate rules))
        deps (into {} (for [r rules]
                        [(head-predicate r)
                         {:neg (set (negated-or-aggregated-preds (:body r)))
                          :pos (set (positive-preds (:body r)))}]))
        max-iter (count all-preds)
        finalise
        (loop [stratum 0
               placed {}
               remaining all-preds
               iter 0]
          (cond
            (zero? (count remaining)) placed
            (>= iter max-iter)
            (throw (ex-info "rule set is not stratifiable (negation cycle?)"
                            {:type :stratification-failed
                             :remaining remaining}))
            :else
            (let [ready (set (filter (fn [p]
                                       (every? #(or (not (all-preds %))
                                                    (and (placed %) (< (placed %) stratum)))
                                               (-> deps p :neg)))
                                     remaining))]
              (if (zero? (count ready))
                (recur (inc stratum) placed remaining (inc iter))
                (recur (inc stratum)
                       (into placed (map (fn [p] [p stratum]) ready))
                       (set/difference remaining ready)
                       (inc iter))))))]
    (->> rules
         (group-by (comp finalise head-predicate))
         (sort-by key)
         (mapv val))))

;; ---------------------------------------------------------------------------
;; Substitution / unification
;; ---------------------------------------------------------------------------

(defn- substitute [t binding]
  (if (ast/var? t) (get binding t t) t))

(defn- unify-tuple
  "Unify args [?x \"foo\"] against tuple [\"a\" \"foo\"]. Returns extended
   binding map on success or nil on failure."
  [args tuple binding]
  (when (= (count args) (count tuple))
    (reduce (fn [b [arg val]]
              (cond
                (nil? b) (reduced nil)
                (ast/var? arg) (let [bound (get b arg ::unbound)]
                                 (cond
                                   (= bound ::unbound) (assoc b arg val)
                                   (= bound val) b
                                   :else (reduced nil)))
                :else (if (= arg val) b (reduced nil))))
            binding
            (map vector args tuple))))

;; ---------------------------------------------------------------------------
;; Atom evaluation
;; ---------------------------------------------------------------------------

(defn- eval-atom-in-binding
  "Given an atom and a current set of bindings, return a new set of bindings
   extended via the atom's contribution."
  [atom edb bindings]
  (case (:kind atom)
    :atom
    (let [pred (:predicate atom)
          tuples (get edb pred #{})]
      (set (for [b bindings
                 tup tuples
                 :let [b' (unify-tuple (:args atom) tup b)]
                 :when b']
             b')))

    :negation
    (let [inner (:inner atom)
          pred (:predicate inner)
          tuples (get edb pred #{})]
      (set (filter (fn [b]
                     (not-any? (fn [tup] (unify-tuple (:args inner) tup b))
                               tuples))
                   bindings)))

    :comparison
    (let [op (:op atom)
          op-fn ({:= = :!= not= :< < :<= <= :> > :>= >=} op)]
      (set (filter (fn [b]
                     (op-fn (substitute (:left atom) b)
                            (substitute (:right atom) b)))
                   bindings)))

    :aggregation
    (let [agg-op (:op atom)
          input-var (:var atom)
          result-var (:result atom)
          inner-body (:body atom)]
      (set (for [b bindings
                 :let [inner-bindings (reduce (fn [bs a]
                                                (eval-atom-in-binding a edb bs))
                                              #{b}
                                              inner-body)
                       vals (mapv #(% input-var) inner-bindings)
                       agg-val (case agg-op
                                 :count (count vals)
                                 :sum   (apply + 0 vals)
                                 :min   (when (seq vals) (apply min vals))
                                 :max   (when (seq vals) (apply max vals)))]]
             (assoc b result-var agg-val))))))

;; ---------------------------------------------------------------------------
;; Rule evaluation
;; ---------------------------------------------------------------------------

(defn- evaluate-rule
  "Evaluate one rule against the EDB. Returns a set of head tuples."
  [rule edb]
  (let [bindings (reduce (fn [bs a] (eval-atom-in-binding a edb bs))
                         #{{}}
                         (:body rule))]
    (set (for [b bindings]
           (mapv #(substitute % b) (-> rule :head :args))))))

(defn- fixed-point-stratum
  "Iterate one stratum's rules to fixed point. Returns updated edb."
  [rules edb]
  (loop [edb edb]
    (let [edb' (reduce (fn [acc r]
                         (let [tuples (evaluate-rule r acc)
                               pred   (head-predicate r)]
                           (update acc pred (fnil into #{}) tuples)))
                       edb
                       rules)]
      (if (= edb edb') edb (recur edb')))))

(defn evaluate-rules
  "Stratify rules; evaluate each stratum to fixed point; return derived EDB."
  [rules edb]
  (let [strata (stratify rules)]
    (reduce (fn [acc stratum]
              (fixed-point-stratum stratum acc))
            edb
            strata)))

(defn query
  "Run rules to fixed point against the EDB, then unify the query-atom's args
   against the resulting predicate's tuples. Returns set of binding maps."
  [rules edb query-atom]
  (let [final-edb (evaluate-rules rules edb)
        tuples (get final-edb (:predicate query-atom) #{})]
    (set (for [tup tuples
               :let [b (unify-tuple (:args query-atom) tup {})]
               :when b]
           b))))
