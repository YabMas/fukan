(ns fukan.canvas.core.defquery)

(def registry (atom {}))

(def ^:private primitive-kinds #{'Module 'Affordance 'State 'Type})

(defn- walk-replace [mapping form]
  (cond
    (symbol? form) (get mapping form form)
    (seq? form)    (with-meta (map #(walk-replace mapping %) form) (meta form))
    (vector? form) (with-meta (mapv #(walk-replace mapping %) form) (meta form))
    :else form))

(defn- substitute-vars
  "Substitute formal parameters in a query body with actual variable names."
  [body params args]
  (let [mapping (zipmap params args)]
    (walk-replace mapping body)))

(defn expand
  "Recursively expand a sequence of constraint clauses, resolving named forms
   into datom patterns."
  [clauses]
  (vec
    (mapcat
      (fn [clause]
        (cond
          ;; Already a datom pattern (vector): pass through
          (vector? clause)
          [clause]

          ;; Named form: (Module ?x), (tag :X ?e), (defquery'd-op args)
          (seq? clause)
          (let [head (first clause)
                args (rest clause)]
            (cond
              ;; Primitive type pattern
              (contains? primitive-kinds head)
              [[(first args) :entity/type (keyword (str head))]]

              ;; Tag pattern
              (= head 'tag)
              [[(second args) :entity/tag (first args)]]

              ;; Registered defquery operator
              (contains? @registry head)
              (let [{:keys [params body]} (@registry head)
                    substituted (substitute-vars body params args)]
                (expand substituted))

              :else
              (throw (ex-info (format "unknown query form: %s" head) {:head head}))))

          :else
          (throw (ex-info "unexpected clause type" {:clause clause}))))
      clauses)))

(defmacro defquery
  "Register a named Datalog operator. The body is a Datalog query body
   that may reference other defquery'd operators."
  [name params docstring body]
  `(swap! registry assoc '~name
          {:params '~params
           :doc ~docstring
           :body ~body}))
