(ns fukan.canvas.core.helpers
  (:require [fukan.canvas.core.substrate :as sub]
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

(defn declare-affordance [name & {:as opts}]
  (let [a (sub/affordance name
            :module *enclosing-module*
            :shape (:shape opts)
            :role (:role opts)
            :formal-expression (:formal-expression opts))]
    (swap! *store* store/transact! a)
    a))

(defn declare-state [name & {:as opts}]
  (let [s (sub/state name
            :module *enclosing-module*
            :shape (:shape opts))]
    (swap! *store* store/transact! s)
    s))

(defn declare-relation [from kind to]
  (swap! *store* store/transact! (sub/relation from kind to))
  nil)

(defn declare-tag [entity tag]
  (let [tagged (sub/apply-tag entity tag)]
    (swap! *store* store/transact! tagged)
    tagged))
