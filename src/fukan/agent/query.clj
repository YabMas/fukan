(ns fukan.agent.query
  "Parse the agent-facing Datalog DSL into a small AST.

   DSL shape:
     [:find  <var-or-aggregation> ...
      :where <atom-vector> ...]

   AST shape:
     {:find  [:?var ...]
      :where [{:predicate <kw> :args [...]} ...]}")

(defn- variable? [x] (and (symbol? x) (.startsWith (name x) "?")))

(defn- ->var-key [sym]
  (keyword (name sym)))

(defn- parse-atom [v]
  (when-not (and (vector? v) (>= (count v) 2))
    (throw (ex-info "where atom must be a vector of at least 2 elements"
                    {:type :malformed-atom :form v})))
  (let [[subject predicate & rest-args] v]
    {:predicate predicate
     :args      (mapv (fn [a] (if (variable? a) (->var-key a) a))
                      (cons subject rest-args))}))

(defn- split-sections [form]
  (loop [remaining form section nil sections {}]
    (cond
      (empty? remaining) sections
      (#{:find :where :in} (first remaining))
      (recur (rest remaining) (first remaining) sections)
      :else
      (recur (rest remaining)
             section
             (update sections section (fnil conj []) (first remaining))))))

(defn parse
  "Parse a `[:find … :where …]` form."
  [form]
  (when-not (vector? form)
    (throw (ex-info "query must be a vector" {:type :query-not-vector :form form})))
  (let [sections (split-sections form)
        find-clauses (or (:find sections)
                         (throw (ex-info "missing :find clause" {:type :missing-find :form form})))
        where-clauses (or (:where sections)
                          (throw (ex-info "missing :where clause" {:type :missing-where :form form})))]
    {:find  (mapv (fn [s] (if (variable? s) (->var-key s) s)) find-clauses)
     :where (mapv parse-atom where-clauses)
     :in    (mapv ->var-key (:in sections []))}))

(defn- unify-arg [pattern-arg tuple-val binding]
  (cond
    (and (keyword? pattern-arg)
         (.startsWith (name pattern-arg) "?"))
    (if-let [existing (get binding pattern-arg)]
      (when (= existing tuple-val) binding)
      (assoc binding pattern-arg tuple-val))
    (= pattern-arg tuple-val) binding
    :else nil))

(defn- unify-tuple [args tuple binding]
  (reduce (fn [b [pa tv]]
            (if-let [b' (unify-arg pa tv b)]
              b'
              (reduced nil)))
          binding
          (map vector args tuple)))

(defn- match-atom [atm edb binding]
  (let [tuples (get edb (:predicate atm) #{})]
    (keep (fn [tup] (unify-tuple (:args atm) tup binding)) tuples)))

(defn evaluate
  "Evaluate a parsed query against an EDB. Returns a vector of result rows
   where each row is a map from :find var keyword → value."
  [parsed edb]
  (let [bindings (reduce (fn [bs atm]
                           (mapcat #(match-atom atm edb %) bs))
                         [{}]
                         (:where parsed))]
    (mapv (fn [b]
            (into {} (map (fn [v] [v (get b v)]) (:find parsed))))
          bindings)))
