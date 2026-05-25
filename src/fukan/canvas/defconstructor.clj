(ns fukan.canvas.defconstructor
  (:require [clojure.string :as str]))

(def ^:private universal-forms
  #{'description 'scope 'note 'includes 'excludes})

(defn- parse-form-decl
  "Parses (form name docstring & opts) into a map.
   :name is stored as a keyword so it serializes cleanly in macro-emitted code."
  [decl]
  (let [[_ fname doc & opts] decl
        opts-map (apply hash-map opts)]
    {:name (keyword fname)
     :doc doc
     :shape (:shape opts-map)
     :required (boolean (:required opts-map))
     :repeatable (boolean (:repeatable opts-map))}))

(defn parse-instance-body
  "Parses a lift invocation body into {form-name -> parsed-value}.
   Throws on unknown forms or repeated non-repeatable forms."
  [lift-name allowed-forms body]
  ;; :name in each allowed-form is a keyword (e.g. :intent, :target)
  (let [allowed-names (set (map :name allowed-forms))
        repeatable    (set (keep #(when (:repeatable %) (:name %)) allowed-forms))]
    (reduce
      (fn [acc clause]
        (let [head     (first clause)
              head-kw  (keyword head)
              args     (rest clause)]
          (cond
            (contains? universal-forms head)
            (assoc acc head-kw args)

            (not (contains? allowed-names head-kw))
            (throw (ex-info
                     (format "`%s` is not a body form of `%s`. Available forms: %s"
                             head lift-name
                             (str/join ", " (sort (map name (concat allowed-names universal-forms)))))
                     {:lift lift-name :form head :available allowed-names}))

            (contains? repeatable head-kw)
            (update acc head-kw (fnil conj []) args)

            (contains? acc head-kw)
            (throw (ex-info
                     (format "form `%s` appears more than once in `%s` instance" head lift-name)
                     {:lift lift-name :form head}))

            :else
            (assoc acc head-kw args))))
      {}
      body)))

(defn check-required!
  "Throws ExceptionInfo if any required form is absent from parsed."
  [lift-name allowed-forms parsed]
  ;; :name in each allowed-form is already a keyword
  (doseq [{:keys [name required]} allowed-forms]
    (when (and required (not (contains? parsed name)))
      (throw (ex-info
               (format "required form `%s` missing in `%s` instance" (clojure.core/name name) lift-name)
               {:lift lift-name :form name})))))

(defmacro defconstructor
  "Defines a lift. Form syntax:
     (defconstructor name docstring
       (form form-name docstring :shape :shape-kw :required true|false :repeatable true|false)*
       (produces [name doc forms] body))

   The defined lift is itself a macro so that body forms like
   (intent [x :String]) are never evaluated as Clojure expressions.
   Parsing, validation, and the produces block all run at invocation time
   (runtime), not at macro-expansion time."
  [lift-name docstring & body]
  (let [form-decls    (filter #(= 'form (first %)) body)
        produces-decl (first (filter #(= 'produces (first %)) body))
        forms         (mapv parse-form-decl form-decls)
        [_ produces-args & produces-body] produces-decl
        impl-sym      (symbol (str lift-name "-impl__"))
        name-arg      (first produces-args)
        doc-arg       (second produces-args)
        forms-arg     (nth produces-args 2)]
    `(do
       ;; The impl function takes name, doc, raw-body-forms, allowed-forms
       ;; and runs the produces body after parsing and validation.
       (defn ~impl-sym
         [~name-arg ~doc-arg raw-body# allowed#]
         (let [~forms-arg (fukan.canvas.defconstructor/parse-instance-body
                            ~(str lift-name) allowed# raw-body#)]
           (fukan.canvas.defconstructor/check-required! ~(str lift-name) allowed# ~forms-arg)
           ~@produces-body))
       ;; The macro quotes its body forms and delegates to the impl fn at runtime.
       (defmacro ~lift-name
         ~docstring
         [name# doc# & body-forms#]
         (list '~impl-sym
               name#
               doc#
               (list 'quote body-forms#)
               (list 'quote ~forms))))))
