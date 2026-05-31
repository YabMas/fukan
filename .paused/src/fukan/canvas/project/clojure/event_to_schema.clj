(ns fukan.canvas.project.clojure.event-to-schema
  "Clojure-lens projection — event Affordance → Malli schema def with
   `^:event` metadata.

   Canvas `(event \"LeaderElected\" \"...doc...\" (payload [term :Term]
   [leader :NodeId]))` declares a named event with a payload shape. The
   corresponding Clojure idiom mirrors `type-to-malli` — a Malli
   `[:map ...]` schema — but the def carries `^:event` rather than
   `^:schema` so the analyzer-side extraction (`source.clj`) can
   distinguish events from record schemas. Cf. Sprint 1 design open
   question 7.

   Routes: `[:clojure :canvas/event]`. Address derivation reuses
   `addr/canonical` with `:primitive/event :projection-kind/schema`
   — the address path was locked symmetric to `:primitive/container`
   in Sprint 2 Task 4."
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
  [n]
  (cond
    (keyword? n) n
    (symbol? n)  (keyword (name n))
    (string? n)  (keyword n)
    :else        (keyword (str n))))

(defn- pr-malli
  [expr]
  (cond
    (keyword? expr) (str expr)
    (vector? expr)  (str "[" (str/join " " (map pr-malli expr)) "]")
    (map? expr)     (str "{" (str/join " " (map (fn [[k v]]
                                                  (str (pr-malli k) " " (pr-malli v)))
                                                expr)) "}")
    :else           (pr-str expr)))

(defn- field->entry
  [[fname fshape]]
  (let [fkw (field-keyword fname)]
    (if (= :optional (:kind fshape))
      (str "[" fkw " {:optional true} " (pr-malli (core/render-shape (:inner fshape))) "]")
      (str "[" fkw " " (pr-malli (core/render-shape fshape)) "]"))))

(defn- render-template
  [{:keys [symbol]} prose payload]
  (let [field-lines (map field->entry payload)
        opts-map    (when (seq prose)
                      (str " {:description \"" (escape-prose prose) "\"}"))]
    (str "(def ^:event " symbol "\n"
         "  [:map" opts-map
         (when (seq field-lines)
           (str "\n   " (str/join "\n   " field-lines)))
         "])")))

(defmethod core/project [:clojure :canvas/event]
  [_lens-id element opts]
  (let [{:keys [stable-id entity-name module-coord doc payload]} element
        registry (:registry opts)
        address  (addr/canonical registry
                                 :primitive/event
                                 :projection-kind/schema
                                 module-coord
                                 entity-name)
        target   {:path      (core/ns->path (:ns address))
                  :namespace (:ns address)
                  :symbol    (:name address)}]
    {:projection-kind    :clojure/event-to-schema
     :lens-id            :clojure
     :model-element-kind :Affordance
     :model-element-id   stable-id
     :target             target
     :template           (render-template target doc payload)
     :prose              (or doc "")
     :context            {:canvas-source-ref (str "canvas/"
                                                  (str/replace module-coord #"\." "/")
                                                  ".clj")
                          :doc-source        :canvas/event-doc
                          :payload-count     (count payload)}}))
