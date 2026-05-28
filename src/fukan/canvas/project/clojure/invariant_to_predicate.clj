(ns fukan.canvas.project.clojure.invariant-to-predicate
  "Clojure-lens projection — invariant Affordance → predicate `defn`
   skeleton.

   Canvas `(invariant \"TermMonotonicity\" \"…descriptive doc…\"
   (holds-that \"current_term is monotonically non-decreasing per
   node\"))` declares a named behavioral commitment. The
   corresponding Clojure idiom is a one-arg predicate `(defn
   <kebab(entity-name)> [model] …)` that returns true iff the
   invariant holds against the model. The `holds-that` clause is
   prose — it carries the semantic intent and lands in the docstring,
   never in the symbol position (Phase 7.5 Sprint 2).

   Layer A's job is the *skeleton* — bare signature + throw-stub body
   + prose envelope that carries the semantic intent (the
   descriptive docstring plus the holds-that clause). The
   implementing LLM writes the actual property check; invariants are
   inherently semantic and template-able only as a shape.

   Routes: `[:clojure :canvas/invariant]`."
  (:require [clojure.string :as str]
            [fukan.canvas.project.core :as core]
            [fukan.target.clojure.address :as addr]))

(defn- escape-prose
  [s]
  (when s
    (-> s
        str/trim
        (str/replace #"\"" "\\\\\""))))

(defn- prose-envelope
  "The full prose carried alongside the skeleton — descriptive doc
   first, then the holds-that clause, then a generic property-check
   framing for the implementing LLM."
  [invariant-name doc holds-that]
  (str "Invariant: " invariant-name "."
       (when (seq doc) (str " " (str/trim doc)))
       (when (seq holds-that)
         (str "\n\nWhat must hold: " holds-that "."))
       "\n\nProperty-check approach: this predicate evaluates the model and"
       " returns true iff the invariant holds. The implementing LLM should"
       " walk the model (related entities) and verify the property."))

(defn- render-template
  [{:keys [symbol]} stable-id doc invariant-name holds-that]
  (let [doc-line (when (seq doc)
                   (str "  \"" (escape-prose doc) "\"\n"))]
    (str "(defn " symbol "\n"
         doc-line
         "  [model]\n"
         "  (throw (ex-info \"" symbol ": not yet implemented\"\n"
         "                  {:canvas-id \"" stable-id "\"\n"
         "                   :invariant-name \"" invariant-name "\""
         (when (seq holds-that)
           (str "\n                   :holds-that \"" (escape-prose holds-that) "\""))
         "})))")))

(defmethod core/project [:clojure :canvas/invariant]
  [_lens-id element opts]
  (let [{:keys [stable-id entity-name module-coord doc holds-that]} element
        registry (:registry opts)
        address  (addr/canonical registry
                                 :primitive/rule
                                 :projection-kind/invariant
                                 module-coord
                                 entity-name)
        target   {:path      (core/ns->path (:ns address))
                  :namespace (:ns address)
                  :symbol    (:name address)}]
    {:projection-kind    :clojure/invariant-to-predicate
     :lens-id            :clojure
     :model-element-kind :Affordance
     :model-element-id   stable-id
     :target             target
     :template           (render-template target stable-id doc entity-name holds-that)
     :prose              (prose-envelope entity-name doc holds-that)
     :context            (cond-> {:canvas-source-ref (str "canvas/"
                                                          (str/replace module-coord #"\." "/")
                                                          ".clj")
                                  :doc-source        :canvas/invariant-doc+holds-that
                                  :invariant-name    entity-name}
                           (seq holds-that) (assoc :holds-that holds-that))}))
