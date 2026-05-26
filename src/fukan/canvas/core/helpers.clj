(ns fukan.canvas.core.helpers
  (:require [datascript.core :as d]
            [fukan.canvas.core.substrate :as sub]
            [fukan.canvas.core.substrate.store :as store]))

(def ^:dynamic *store* nil)
(def ^:dynamic *enclosing-module* nil)

;; Shape constructors (type expressions)
(defn arrow [inputs outputs]
  {:kind :arrow :inputs inputs :outputs outputs})

(defn record-of [field-pairs]
  {:kind :record :fields (vec field-pairs)})

(defn list-of [elem]
  {:kind :list :elem elem})

(defn optional [t]
  {:kind :optional :inner t})

(defn sum-of [variants]
  {:kind :sum :variants variants})

;; Substrate construction within scope
(defmacro with-canvas [& body]
  `(binding [*store* (atom (store/create))]
     ~@body
     @*store*))

(defmacro within-module [name & body]
  `(let [m# (sub/module ~name)]
     (swap! *store* store/transact! m#)
     (binding [*enclosing-module* (sub/id-of m#)]
       ~@body
       m#)))

(defn- register-child! [entity]
  (when *enclosing-module*
    (swap! *store*
           #(d/db-with % [[:db/add [:entity/id *enclosing-module*]
                                   :module/child
                                   [:entity/id (sub/id-of entity)]]])))
  entity)

(defn declare-affordance [name & {:as opts}]
  (let [a (sub/affordance name
            :shape (:shape opts)
            :role (:role opts)
            :formal-expression (:formal-expression opts)
            :doc (:doc opts)
            :returns-label (:returns-label opts))]
    (swap! *store* store/transact! a)
    (register-child! a)
    a))

(defn declare-state [name & {:as opts}]
  (let [s (sub/state name
            :shape (:shape opts))]
    (swap! *store* store/transact! s)
    (register-child! s)
    s))

(defn declare-type
  "Construct a Type and register it as a child of the enclosing module."
  [name & {:keys [kind fields doc]}]
  (let [t (case kind
            :atomic (sub/type-primitive name :doc doc)
            :record (sub/type-record name fields :doc doc))]
    (swap! *store* store/transact! t)
    (register-child! t)
    t))

(defn declare-relation [from kind to]
  (swap! *store* store/transact! (sub/relation from kind to))
  nil)

(defn declare-tag [entity tag]
  (let [tagged (sub/apply-tag entity tag)]
    (swap! *store* store/transact! tagged)
    tagged))
