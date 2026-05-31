(ns fukan.canvas.project.clojure.checker-to-defn
  "Clojure-lens projection — checker Affordance → one-arg `defn`
   skeleton with `(Model) -> [Violation]` shape.

   Canvas `(checker \"check\" \"…doc…\")` declares a validation entry
   point. The signature is baked in by the `validation` vocab — every
   checker is exactly `(model: :model/Model) -> (list-of
   :agent/Violation)`. The corresponding Clojure idiom is:

     - kebab-cased local name (per `addr/canonical`)
     - single-arg arglist `[model]`
     - Malli schema `[:=> [:cat :model/Model] [:sequential :agent/Violation]]`
       — the canvas-declared shape rendered structurally
     - exception-stub body carrying `:canvas-id` so the placeholder is
       detectable until the implementing LLM fills it in
     - canvas docstring verbatim

   The arglist's parameter name comes from the canvas-side `model`
   binding (the validation vocab hard-codes that name). When the
   element carrier omits inputs (defensive — caller built the element
   map without `affordance-element`'s arrow-shape branch), the
   projection falls back to a literal `[model]`.

   Routes: `[:clojure :canvas/checker]`."
  (:require [clojure.string :as str]
            [fukan.canvas.project.core :as core]
            [fukan.target.clojure.address :as addr]))

(defn- escape-prose
  [s]
  (when s
    (-> s
        str/trim
        (str/replace #"\"" "\\\\\""))))

(defn- name-of
  [n]
  (cond
    (symbol? n)  (name n)
    (keyword? n) (name n)
    (string? n)  n
    :else        (str n)))

(defn- pr-malli
  [expr]
  (cond
    (keyword? expr) (str expr)
    (vector? expr)  (str "[" (str/join " " (map pr-malli expr)) "]")
    :else           (pr-str expr)))

(defn- render-arglist
  "The checker's arglist defaults to `[model]` when the element
   carrier omits inputs; otherwise it splices the canvas-declared
   binding names (always one — checkers are single-arg by validation
   vocab's baked-in contract)."
  [inputs]
  (let [names (mapv (fn [[n _]] (name-of n)) inputs)]
    (str "[" (str/join " " (or (seq names) ["model"])) "]")))

(defn- render-malli-schema
  [inputs outputs]
  (let [param-types (mapv (fn [[_ s]] (core/render-shape s)) inputs)
        ;; Defensive: when the element omits inputs/outputs, fall back
        ;; to the validation vocab's baked-in shape.
        param-types (if (seq param-types) param-types [:model/Model])
        return-type (if outputs
                      (core/render-shape outputs)
                      [:sequential :agent/Violation])]
    (str "[:=> [:cat " (str/join " " (map pr-malli param-types)) "] "
         (pr-malli return-type) "]")))

(defn- render-template
  [{:keys [symbol]} stable-id doc inputs outputs]
  (let [doc-line (when (seq doc)
                   (str "  \"" (escape-prose doc) "\"\n"))]
    (str "(defn " symbol "\n"
         doc-line
         "  {:malli/schema " (render-malli-schema inputs outputs) "}\n"
         "  " (render-arglist inputs) "\n"
         "  (throw (ex-info \"" symbol ": not yet implemented\"\n"
         "                  {:canvas-id \"" stable-id "\"})))")))

(defmethod core/project [:clojure :canvas/checker]
  [_lens-id element opts]
  (let [{:keys [stable-id entity-name module-coord doc inputs outputs]} element
        registry (:registry opts)
        address  (addr/canonical registry
                                 :primitive/operation
                                 :projection-kind/operation
                                 module-coord
                                 entity-name)
        target   {:path      (core/ns->path (:ns address))
                  :namespace (:ns address)
                  :symbol    (:name address)}]
    {:projection-kind    :clojure/checker-to-defn
     :lens-id            :clojure
     :model-element-kind :Affordance
     :model-element-id   stable-id
     :target             target
     :template           (render-template target stable-id doc (or inputs []) outputs)
     :prose              (or doc "")
     :context            {:canvas-source-ref (str "canvas/"
                                                  (str/replace module-coord #"\." "/")
                                                  ".clj")
                          :doc-source        :canvas/checker-doc
                          :arity             1
                          :validation-entry? true}}))
