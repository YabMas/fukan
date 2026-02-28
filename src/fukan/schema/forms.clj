(ns fukan.schema.forms
  "Pure functions for interpreting Malli schema form vectors.
   Zero fukan-internal dependencies. This is the single source of truth
   for structural access to schema forms — the rest of the system treats
   schema forms as opaque values and delegates here for any interpretation.")

;; -----------------------------------------------------------------------------
;; Structural accessors

(defn form-tag
  "First element of a vector form, nil for non-vectors."
  [form]
  (when (vector? form)
    (first form)))

(defn form-props
  "Property map (second element if map), nil otherwise."
  [form]
  (when (and (vector? form)
             (>= (count form) 2)
             (map? (second form)))
    (second form)))

(defn form-children
  "Child forms after tag and optional props."
  [form]
  (when (vector? form)
    (let [tail (rest form)]
      (if (and (seq tail) (map? (first tail)))
        (rest tail)
        tail))))

(defn form-description
  "Extract :description from a schema form's property map."
  [form]
  (:description (form-props form)))

;; -----------------------------------------------------------------------------
;; Function schema helpers

(defn fn-schema?
  "Predicate for [:=> ...] function schemas."
  [form]
  (and (vector? form) (= :=> (first form))))

(defn fn-schema-parts
  "Destructure [:=> [:cat a b] out] into {:inputs [a b] :output out}, or nil."
  [form]
  (when (fn-schema? form)
    (let [[_ input output] form
          inputs (if (and (vector? input) (= :cat (first input)))
                   (vec (rest input))
                   [input])]
      {:inputs inputs :output output})))

;; -----------------------------------------------------------------------------
;; Keyword reference extraction

(defn- walk-form
  "Semantic walk of a schema form, collecting keywords that appear in type
   position. Skips :enum/:= values, :map field keys, property maps, and
   :fn predicate bodies."
  [form]
  (cond
    ;; Keywords in type position — potential schema refs
    (keyword? form) [form]

    ;; Vector form — dispatch on tag
    (vector? form)
    (let [tag (first form)]
      (cond
        ;; :enum and := — values are not schema refs
        (#{:enum :=} tag) []

        ;; :fn — predicate body is opaque, not schema structure
        (= :fn tag) []

        ;; :map — walk only child schema types, skip field keys and prop maps
        (= :map tag)
        (let [children (form-children form)]
          (->> children
               (filter vector?)
               (mapcat (fn [entry]
                         ;; Entry is [key opts? schema] — walk only the schema part
                         (let [parts (rest entry) ;; drop the field key
                               schema-part (if (map? (first parts))
                                             (second parts)
                                             (first parts))]
                           (when schema-part
                             (walk-form schema-part)))))))

        ;; All other tags — skip props map, walk children
        :else
        (let [children (form-children form)]
          (mapcat walk-form children))))

    ;; Non-keyword, non-vector — nothing to collect
    :else []))

(defn extract-keyword-refs
  "Extract keywords from type positions in a schema form.
   Skips :enum/:= values, :map field keys, property maps, and :fn predicates.
   Returns a set of keywords."
  [form]
  (set (walk-form form)))
