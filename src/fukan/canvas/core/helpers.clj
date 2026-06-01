(ns fukan.canvas.core.helpers
  (:require [datascript.core :as d]
            [fukan.canvas.core.kinds :as kinds]
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
  ;; Seed the store with the flat kind→family tagdefs up front, so kinds/family-of
  ;; works on every substrate — including code that queries *store* from inside
  ;; the body, not only the returned db.
  `(binding [*store* (atom (d/db-with (store/create) (kinds/tagdef-datoms)))]
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

(defn declare-node
  "The generic, kind-agnostic construction seam: transact a built Node and
   register it as a child of the enclosing container. The node already carries
   whatever :node-kind / :role / payload its caller gave it — declare-node knows
   none of those kinds.

   Legacy floor seam from the old store substrate; the lean-kernel rebuild
   authors via fukan.canvas.core.structure (defstructure) instead."
  [n]
  (swap! *store* store/transact! n)
  (register-child! n)
  n)

;; Kind-specific sugar over `declare-node` — convenience for the bespoke lifts
;; and test fixtures, pending the registry-driven path above.
(defn declare-affordance [name & {:as opts}]
  (declare-node (sub/affordance name
                  :shape (:shape opts)
                  :role (:role opts)
                  :formal-expression (:formal-expression opts)
                  :doc (:doc opts)
                  :returns-label (:returns-label opts))))

(defn declare-state [name & {:as opts}]
  (declare-node (sub/state name :shape (:shape opts))))

(defn declare-type
  "Construct a Type and register it as a child of the enclosing module."
  [name & {:keys [kind fields doc]}]
  (declare-node (case kind
                  :atomic (sub/type-primitive name :doc doc)
                  :record (sub/type-record name fields :doc doc))))

(defn declare-relation [from kind to]
  (swap! *store* store/transact! (sub/relation from kind to))
  nil)

(defn declare-tag [entity tag]
  (let [tagged (sub/apply-tag entity tag)]
    (swap! *store* store/transact! tagged)
    tagged))
