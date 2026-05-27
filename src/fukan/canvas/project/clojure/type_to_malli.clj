(ns fukan.canvas.project.clojure.type-to-malli
  "Clojure-lens projection — record-shaped Type → Malli `[:map ...]`
   schema.

   Canvas `(record \"ServerOpts\" \"...doc...\" (field port (optional
   :Integer)))` declares a structured Type with named fields. The
   corresponding Clojure idiom is a `def` carrying a Malli `[:map ...]`
   schema with one entry per field. Compound field shapes flow through
   `core/render-shape`; the outer `optional` wrapper translates to the
   idiomatic `{:optional true}` field-entry rather than a nested
   `[:maybe ...]` form (the Sprint 1 design doc's convention, matching
   fukan's existing style on e.g. `ServerOpts`'s `:port`).

   Routes: `[:clojure :Type/record]`."
  (:require [clojure.string :as str]
            [fukan.canvas.project.core :as core]
            [fukan.target.clojure.address :as addr]))

(defn- escape-prose
  [s]
  (when s
    (-> s
        str/trim
        (str/replace #"\s+" " ")
        (str/replace #"\"" "\\\\\""))))

(defn- field-keyword
  "Normalize a canvas field-name into a keyword. Field-names arrive as
   strings (substrate-direct) or symbols (via the `field` macro)."
  [n]
  (cond
    (keyword? n) n
    (symbol? n)  (keyword (name n))
    (string? n)  (keyword n)
    :else        (keyword (str n))))

(defn- pr-malli
  "Pretty-print a Malli expression to its idiomatic source form. Keywords
   render as themselves; vectors render with one space between entries."
  [expr]
  (cond
    (keyword? expr) (str expr)
    (vector? expr)  (str "[" (str/join " " (map pr-malli expr)) "]")
    (map? expr)     (str "{" (str/join " " (map (fn [[k v]]
                                                  (str (pr-malli k) " " (pr-malli v)))
                                                expr)) "}")
    :else           (pr-str expr)))

(defn- field->entry
  "Render one [field-name parsed-shape] tuple into a Malli map-entry
   vector. The outer `optional` wrapper rewrites as `{:optional true}`
   on the field entry; everything else flows through `render-shape`."
  [[fname fshape]]
  (let [fkw (field-keyword fname)]
    (if (= :optional (:kind fshape))
      (str "[" fkw " {:optional true} " (pr-malli (core/render-shape (:inner fshape))) "]")
      (str "[" fkw " " (pr-malli (core/render-shape fshape)) "]"))))

(defn- render-template
  [{:keys [symbol]} prose fields]
  (let [field-lines (map field->entry fields)
        opts-map    (when (seq prose)
                      (str " {:description \"" (escape-prose prose) "\"}"))]
    (str "(def ^:schema " symbol "\n"
         "  [:map" opts-map
         (when (seq field-lines)
           (str "\n   " (str/join "\n   " field-lines)))
         "])")))

(defmethod core/project [:clojure :Type/record]
  [_lens-id element opts]
  (let [{:keys [stable-id entity-name module-coord doc fields]} element
        registry (:registry opts)
        address  (addr/canonical registry
                                 :primitive/container
                                 :projection-kind/schema
                                 module-coord
                                 entity-name)
        target   {:path      (core/ns->path (:ns address))
                  :namespace (:ns address)
                  :symbol    (:name address)}]
    {:projection-kind    :clojure/type-to-malli
     :lens-id            :clojure
     :model-element-kind :Type
     :model-element-id   stable-id
     :target             target
     :template           (render-template target doc fields)
     :prose              (or doc "")
     :context            {:canvas-source-ref (str "canvas/"
                                                  (str/replace module-coord #"\." "/")
                                                  ".clj")
                          :doc-source        :canvas/record-doc
                          :field-count       (count fields)}}))
