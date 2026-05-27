(ns fukan.canvas.project.clojure.function-to-defn
  "Clojure-lens projection — canvas function Affordance → `defn` skeleton
   with throw-stub body and Malli schema metadata.

   Canvas `(function \"start_server\" \"...doc...\" (takes [opts
   :ServerOpts]) (gives (optional :ServerInfo)))` declares an exposed
   synchronous call. The corresponding Clojure idiom is a `defn` with:

     - kebab-cased local name (per `addr/canonical`)
     - arglist drawn from the canvas `takes` binding names
     - Malli schema `[:=> [:cat <param-types>] <return-type>]`
     - exception-stub body carrying `:canvas-id` (the stable-id) so a
       future Phase 8 verification step can detect the placeholder
     - the canvas docstring, verbatim

   Sprint 1 design doc names this the canonical Layer-A projection;
   subsequent affordance projections (getter, checker, handler) are
   syntactic variants of this base shape.

   `projector/signature-for` was considered (Sprint 1 Task 4 lifted it
   to `defn`) but its input vocabulary is the Allium kernel primitive,
   not the canvas parsed-shape. Layer A's function-to-defn renders
   directly from `:inputs`/`:outputs` parsed-shape data via
   `core/render-shape` — the lens-side vocabulary is canvas, not
   kernel.

   Routes: `[:clojure :fukan.canvas.monolith/exposed-call]`. This is
   the canvas role canvas's `function` construction assigns to plain
   function Affordances (see `canvas/construction.clj`'s
   `(defconstructor function … :role :fukan.canvas.monolith/exposed-call …)`)."
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
  "Canvas binding-names arrive as symbols or strings. Return the bare
   string suitable for splicing into the arglist."
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

(defn- render-param-type
  "Render one parameter's parsed shape. The Malli schema position
   doesn't get the optional-as-field convention — return [:maybe T]
   for explicitly optional parameter types."
  [parsed-shape]
  (core/render-shape parsed-shape))

(defn- render-return-type
  "Render the return shape. `:Unit` collapses to `:nil` per Sprint 1
   recommendation — Malli return positions express \"no meaningful
   value\" as `:nil` rather than `:any`."
  [parsed-shape]
  (let [base (core/render-shape parsed-shape)]
    (if (= :any base)
      ;; The alias table maps :Unit → :any; the return-position swap.
      (if (and (= :atomic (:kind parsed-shape))
               (= :Unit (:name parsed-shape)))
        :nil
        base)
      base)))

(defn- render-malli-schema
  [inputs outputs]
  (let [param-types (mapv (fn [[_ s]] (render-param-type s)) inputs)
        return-type (render-return-type outputs)]
    (str "[:=> [:cat" (when (seq param-types)
                        (str " " (str/join " " (map pr-malli param-types))))
         "] " (pr-malli return-type) "]")))

(defn- render-arglist
  [inputs]
  (str "[" (str/join " " (map (fn [[n _]] (name-of n)) inputs)) "]"))

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

(defmethod core/project [:clojure :fukan.canvas.monolith/exposed-call]
  [_lens-id element opts]
  (let [{:keys [stable-id entity-name module-coord doc inputs outputs
                effects emits]} element
        registry (:registry opts)
        address  (addr/canonical registry
                                 :primitive/operation
                                 :projection-kind/operation
                                 module-coord
                                 entity-name)
        target   {:path      (core/ns->path (:ns address))
                  :namespace (:ns address)
                  :symbol    (:name address)}]
    {:projection-kind    :clojure/function-to-defn
     :lens-id            :clojure
     :model-element-kind :Affordance
     :model-element-id   stable-id
     :target             target
     :template           (render-template target stable-id doc (or inputs []) outputs)
     :prose              (or doc "")
     :context            (cond-> {:canvas-source-ref (str "canvas/"
                                                          (str/replace module-coord #"\." "/")
                                                          ".clj")
                                  :doc-source        :canvas/function-doc
                                  :arity             (count (or inputs []))}
                           (seq effects) (assoc :effects (vec effects))
                           (seq emits)   (assoc :emits   (vec emits)))}))
