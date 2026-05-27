(ns fukan.canvas.project.clojure.value-to-def
  "Clojure-lens projection — atomic Type → opaque `def` schema.

   Canvas `(value \"NodeId\" \"...doc...\")` declares an opaque named
   type. The corresponding Clojure idiom is a `def` carrying a Malli
   schema that exposes only the name and the descriptive docstring —
   no internal structure. The implementing LLM may refine the schema
   (e.g. constrain `:any` to `[:and :string [:fn ...]]`) but the
   projection's job is to stamp the name + doc into a structurally
   safe spec.

   Routes: `[:clojure :Type/atomic]`. Dispatch on `:type-kind :atomic`
   keeps record types out of this method — they project through
   `type-to-malli`."
  (:require [clojure.string :as str]
            [fukan.canvas.project.core :as core]
            [fukan.target.clojure.address :as addr]))

(defn- escape-prose
  "Collapse runs of whitespace and escape embedded double-quotes so
   the prose lands inside a Clojure string literal cleanly."
  [s]
  (when s
    (-> s
        str/trim
        (str/replace #"\s+" " ")
        (str/replace #"\"" "\\\\\""))))

(defn- render-template
  [{:keys [symbol]} prose]
  (if (seq prose)
    (str "(def ^:schema " symbol "\n"
         "  [:any {:description \"" (escape-prose prose) "\"}])")
    (str "(def ^:schema " symbol "\n"
         "  [:any])")))

(defmethod core/project [:clojure :Type/atomic]
  [_lens-id element opts]
  (let [{:keys [stable-id entity-name module-coord doc]} element
        registry (:registry opts)
        address  (addr/canonical registry
                                 :primitive/container
                                 :projection-kind/schema
                                 module-coord
                                 entity-name)
        target   {:path      (core/ns->path (:ns address))
                  :namespace (:ns address)
                  :symbol    (:name address)}]
    {:projection-kind    :clojure/value-to-def
     :lens-id            :clojure
     :model-element-kind :Type
     :model-element-id   stable-id
     :target             target
     :template           (render-template target doc)
     :prose              (or doc "")
     :context            (cond-> {:canvas-source-ref (str "canvas/"
                                                          (str/replace module-coord
                                                                                  #"\."
                                                                                  "/")
                                                          ".clj")
                                  :doc-source        :canvas/value-doc}
                           (:related-elements element)
                           (assoc :related-elements (:related-elements element)))}))
