(ns fukan.canvas.core.defquery
  (:require [fukan.canvas.core.classification :as classification]))

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

(defn- expand-this
  "Expand `(this :module/name ?var)` into datom patterns that bind ?var to
   the entity named `name` inside the module named `module`.

   The `ref-kw` must be a namespaced keyword where:
     - namespace = module name (dot-separated, e.g. \"infra.server\")
     - name      = entity name within that module (e.g. \"start_server\")

   Generates a fresh gensym'd module variable to avoid clashes when multiple
   `(this ...)` forms appear in the same query body."
  [ref-kw target-var]
  (when-not (and (keyword? ref-kw) (namespace ref-kw))
    (throw (ex-info "(this) requires a namespaced keyword, e.g. :module/entity"
                    {:ref-kw ref-kw})))
  (let [mod-name    (namespace ref-kw)
        entity-name (name ref-kw)
        mod-sym     (gensym "?__this_mod_")
        fam-var     (gensym "?__fam_")]
    [(list 'kind-of mod-sym fam-var)
     [(list '= fam-var :family/module)]
     [mod-sym     :entity/name  mod-name]
     [mod-sym     :module/child target-var]
     [target-var  :entity/name  entity-name]]))

(defn expand
  "Recursively expand a sequence of constraint clauses, resolving named forms
   into datom patterns.

   Supported forms:
     - `[?e :attr val]`           — raw datom pattern, passed through
     - `(Module ?x)`              — primitive type check
     - `(Affordance ?x)` etc.     — same for other primitive kinds
     - `(tag :T ?e)`              — entity tag check
     - `(this :mod/name ?var)`    — resolve named entity in module
     - `(defquery-op args...)`    — registered defquery operator"
  [clauses]
  (vec
    (mapcat
      (fn [clause]
        (cond
          ;; Already a datom pattern (vector): pass through
          (vector? clause)
          [clause]

          ;; Named form: (Module ?x), (tag :X ?e), (this :m/n ?v), (defquery'd-op args)
          (seq? clause)
          (let [head (first clause)
                args (rest clause)]
            (cond
              ;; Primitive type pattern → classification stratum (kind-of + a
              ;; family predicate); needs the rule set threaded as `%`.
              (contains? primitive-kinds head)
              (let [fam     (classification/family->super-tag (keyword (str head)))
                    fam-var (gensym "?__fam_")]
                [(list 'kind-of (first args) fam-var)
                 [(list '= fam-var fam)]])

              ;; Tag pattern
              (= head 'tag)
              [[(second args) :entity/tag (first args)]]

              ;; (this :module/entity ?var) — named entity resolution
              (= head 'this)
              (let [[ref-kw target-var] args]
                (expand-this ref-kw target-var))

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
