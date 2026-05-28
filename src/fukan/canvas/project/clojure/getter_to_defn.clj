(ns fukan.canvas.project.clojure.getter-to-defn
  "Clojure-lens projection — getter Affordance → zero-arg `defn` skeleton
   with `Optional<T>` (Malli `[:maybe T]`) return shape.

   Canvas `(getter \"get_port\" \"Current bound port, if running.\"
   :Integer)` declares a zero-arg state accessor whose return is
   implicitly optional — getters are the lifecycle vocab's read affordance
   over module state that may or may not yet be initialised. The
   corresponding Clojure idiom is symmetric with `function-to-defn`'s
   zero-arg case:

     - kebab-cased local name (per `addr/canonical`)
     - empty arglist
     - Malli schema `[:=> [:cat] [:maybe T]]` where T is the inner shape
       declared in the canvas form (lifecycle vocab wraps it in
       `:optional` automatically; we render the wrapped shape directly)
     - exception-stub body carrying `:canvas-id`
     - the canvas docstring verbatim

   `affordance-element` in `fukan.agent.api` already produces the
   `:inputs []` + `:outputs <:optional :inner>` carrier shape for
   getter-role affordances via its default branch (lifecycle/event/etc.
   share the function-shaped arrow when a shape is declared). This
   projection consumes that data unchanged — the only difference from
   `function-to-defn` is the dispatch key and the zero-arg invariant
   asserted on input shape.

   Routes: `[:clojure :canvas/getter]`."
  (:require [clojure.string :as str]
            [fukan.canvas.project.core :as core]
            [fukan.target.clojure.address :as addr]))

(defn- escape-prose
  [s]
  (when s
    (-> s
        str/trim
        (str/replace #"\"" "\\\\\""))))

(defn- pr-malli
  [expr]
  (cond
    (keyword? expr) (str expr)
    (vector? expr)  (str "[" (str/join " " (map pr-malli expr)) "]")
    :else           (pr-str expr)))

(defn- render-return-type
  "Render the return shape. Getters are by-convention `Optional<T>` —
   the lifecycle vocab wraps the canvas-declared inner shape in
   `:optional` at parse time. If the wrap has already happened, render
   it as `[:maybe T]`. If the shape is bare (defensive — caller passed
   an already-unwrapped inner shape), still wrap in `[:maybe …]` so the
   getter contract is preserved in the projection's Malli schema."
  [parsed-shape]
  (let [rendered (core/render-shape parsed-shape)]
    (if (and (vector? rendered) (= :maybe (first rendered)))
      rendered
      [:maybe rendered])))

(defn- render-malli-schema
  [outputs]
  (str "[:=> [:cat] " (pr-malli (render-return-type outputs)) "]"))

(defn- render-template
  [{:keys [symbol]} stable-id doc outputs]
  (let [doc-line (when (seq doc)
                   (str "  \"" (escape-prose doc) "\"\n"))]
    (str "(defn " symbol "\n"
         doc-line
         "  {:malli/schema " (render-malli-schema outputs) "}\n"
         "  []\n"
         "  (throw (ex-info \"" symbol ": not yet implemented\"\n"
         "                  {:canvas-id \"" stable-id "\"})))")))

(defmethod core/project [:clojure :canvas/getter]
  [_lens-id element opts]
  (let [{:keys [stable-id entity-name module-coord doc outputs]} element
        registry (:registry opts)
        address  (addr/canonical registry
                                 :primitive/operation
                                 :projection-kind/operation
                                 module-coord
                                 entity-name)
        target   {:path      (core/ns->path (:ns address))
                  :namespace (:ns address)
                  :symbol    (:name address)}]
    {:projection-kind    :clojure/getter-to-defn
     :lens-id            :clojure
     :model-element-kind :Affordance
     :model-element-id   stable-id
     :target             target
     :template           (render-template target stable-id doc outputs)
     :prose              (or doc "")
     :context            {:canvas-source-ref (str "canvas/"
                                                  (str/replace module-coord #"\." "/")
                                                  ".clj")
                          :doc-source        :canvas/getter-doc
                          :arity             0
                          :optional-return?  true}}))
