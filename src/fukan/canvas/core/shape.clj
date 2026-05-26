(ns fukan.canvas.core.shape
  "Shape expression grammar. Parses shape expressions like
   :String, (optional :T), (list-of :T), (set-of :T), (sum-of :A :B),
   (map-of :K :V), (record-of [:n :T]+), (ref-to :module/Type) into edn maps."
  (:require [clojure.set :as set]))

(defn parse [expr]
  (cond
    (and (keyword? expr) (namespace expr))
    {:kind :ref :target expr}

    (keyword? expr)
    {:kind :atomic :name expr}

    (and (seq? expr) (= 'optional (first expr)))
    {:kind :optional :inner (parse (second expr))}

    (and (seq? expr) (= 'list-of (first expr)))
    {:kind :list :elem (parse (second expr))}

    (and (seq? expr) (= 'set-of (first expr)))
    {:kind :set :elem (parse (second expr))}

    (and (seq? expr) (= 'sum-of (first expr)))
    {:kind :sum :variants (mapv parse (rest expr))}

    (and (seq? expr) (= 'map-of (first expr)))
    {:kind :map :key (parse (second expr)) :val (parse (nth expr 2))}

    (and (seq? expr) (= 'ref-to (first expr)))
    {:kind :ref :target (second expr)}

    (and (seq? expr) (= 'record-of (first expr)))
    {:kind :record
     :fields (mapv (fn [pair]
                     [(first pair) (parse (second pair))])
                   (rest expr))}

    (and (seq? expr) (= 'tuple-of (first expr)))
    {:kind :tuple :elems (mapv parse (rest expr))}

    :else
    (throw (ex-info "unknown shape expression" {:expr expr}))))

(defn type-names
  "Walk a parsed shape; collect the set of all atomic type names and ref
   targets it mentions. Returns a set of keywords."
  [shape]
  (case (:kind shape)
    :atomic   #{(:name shape)}
    :ref      #{(:target shape)}
    :optional (type-names (:inner shape))
    :list     (type-names (:elem shape))
    :set      (type-names (:elem shape))
    :sum      (apply set/union (map type-names (:variants shape)))
    :tuple    (apply set/union (map type-names (:elems shape)))
    :map      (set/union (type-names (:key shape)) (type-names (:val shape)))
    :record   (apply set/union (map (fn [[_ s]] (type-names s)) (:fields shape)))
    :arrow    (set/union (type-names (:inputs shape)) (type-names (:outputs shape)))
    #{}))
