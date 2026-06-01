(ns fukan.target.clojure.types
  "Substrate Type → malli rendering. Per DESIGN.md 'Type translation'.

   Project layer's :type-overrides provides per-Scalar-name overrides
   that win over substrate defaults.")

(def ^:private builtin-scalar->malli
  {"String"   :string
   "Integer"  :int
   "Boolean"  :boolean
   "Number"   :double
   "Text"     :string})

(declare render)

(defn- render-scalar [registry ty]
  (let [name (:name ty)
        overrides (:type-overrides registry {})]
    (or (get overrides name)
        (get builtin-scalar->malli name)
        :any)))

(defn- render-enum [_ ty]
  (into [:enum] (:values ty)))

(defn- render-collection [registry ty]
  (let [inner (render registry (:of ty))
        sem   (or (:semantics ty) :sequential)]
    (cond
      (= :sequential sem)              [:vector inner]
      (= :unique sem)                  [:set inner]
      (= :semantics/keyed (:case sem)) [:map-of (render registry (:key-type sem)) inner]
      :else                            [:vector inner])))

(defn- render-union [registry ty]
  (into [:or] (map #(render registry %) (:types ty))))

(defn- render-composite [registry ty]
  (let [shape (:shape ty)]
    (case (:case shape)
      :shape/named   {:fukan/composite-ref (:container shape)}
      :shape/inline  (into [:map]
                           (for [f (:fields shape)]
                             [(keyword (:name f))
                              {:optional (boolean (:optional f))}
                              (render registry (:type f))]))
      :any)))

(defn render
  "Render a kernel substrate Type as a malli value (Clojure data).
   Composite-named refs render as a sentinel map; Plan 6's Projector
   will resolve them to actual schema-def references."
  [registry ty]
  (case (:case ty)
    :type/scalar      (render-scalar registry ty)
    :type/enum        (render-enum registry ty)
    :type/collection  (render-collection registry ty)
    :type/union       (render-union registry ty)
    :type/composite   (render-composite registry ty)
    :type/ref         :any
    :any))
