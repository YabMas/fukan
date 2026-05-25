(ns fukan.canvas.shape
  "Shape expression grammar. Parses shape expressions like
   :String, (optional :T), (list-of :T), (set-of :T), (sum-of :A :B),
   (record-of [:n :T]+), (ref-to :module/Type) into edn maps.")

(defn parse [expr]
  (cond
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

    (and (seq? expr) (= 'ref-to (first expr)))
    {:kind :ref :target (second expr)}

    (and (seq? expr) (= 'record-of (first expr)))
    {:kind :record
     :fields (mapv (fn [pair]
                     [(first pair) (parse (second pair))])
                   (rest expr))}

    :else
    (throw (ex-info "unknown shape expression" {:expr expr}))))
