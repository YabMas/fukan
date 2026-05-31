(ns fukan.canvas.project.clojure.handler-to-defn
  "Clojure-lens projection — handler Affordance → reactive-handler
   `defn` skeleton with `[payload state]` arglist.

   Canvas `(handler \"on_vote_requested\" \"…doc…\" (on
   :election/VoteRequested) (emits :election/VoteGranted))` declares a
   reactive handler that fires when a named event arrives. The event
   vocab stores the on/emits keywords on the affordance's
   `:formal-expression` map (no arrow shape — handlers don't have a
   canvas-side input/output type pair the way functions do).

   The corresponding Clojure idiom is a two-arg `defn`:

     - kebab-cased local name (per `addr/canonical`)
     - arglist `[payload state]` — payload carries the incoming
       event's data; state is the module-local state the handler may
       read or update (whether the handler is pure-reactive or
       side-effecting is up to the implementing LLM)
     - Malli schema `[:=> [:cat <:on-event-ref> :any] :any]` —
       constrains the payload to the on-event reference (so the
       schema documents the reactive trigger) and leaves state/return
       open; the implementing LLM can refine to a more precise
       schema after writing the handler body
     - exception-stub body carrying `:canvas-id`, `:on` (the trigger
       event reference) and `:emits` (the declared downstream event
       references) so the placeholder remains audit-traceable until
       the implementing LLM fills it in
     - canvas docstring verbatim

   Known limitation (Sprint 1): the handler's payload/state shape is
   not yet plumbed through canvas-source. `vocab.event/handler`
   declares no `:shape` on the affordance — `:on` carries an event
   reference, not the payload type, and `affordance-element` exposes
   `:on`/`:emits` as keyword strings. A future sprint can chase the
   `:on` ref into the canvas-db's event declaration and inline its
   payload shape; until then, the projection emits a generic two-arg
   stub with `:any` in both positions.

   Routes: `[:clojure :canvas/handler]`."
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

(defn- ref-of
  "The on/emits values arrive from canvas-source as pr-str'd keywords
   (e.g. \":election/VoteRequested\"). Coerce back to keyword form for
   the Malli schema's `:cat` position and for the prose envelope."
  [v]
  (cond
    (keyword? v) v
    (string? v)  (try (keyword (subs v 1)) (catch Exception _ nil))
    :else        nil))

(defn- render-malli-schema
  [on-ref]
  (let [payload-shape (or (ref-of on-ref) :any)]
    (str "[:=> [:cat " (pr-malli payload-shape) " :any] :any]")))

(defn- render-emits-clause
  [emits]
  (when (seq emits)
    (->> emits
         (keep ref-of)
         (map pr-str)
         (str/join " "))))

(defn- prose-envelope
  [handler-name doc on-ref emits]
  (let [on-kw (ref-of on-ref)]
    (str "Reactive handler: " handler-name "."
         (when (seq doc) (str " " (str/trim doc)))
         (when on-kw     (str "\n\nFires on: " on-kw "."))
         (when (seq emits)
           (str "\n\nMay emit: "
                (str/join ", " (keep ref-of emits))
                "."))
         "\n\nThe handler runs in response to the named event; the"
         " implementing LLM writes the reactive body. Pure-reactive"
         " (no side effects) is preferred when feasible.")))

(defn- render-template
  [{:keys [symbol]} stable-id doc on-ref emits]
  (let [doc-line     (when (seq doc)
                       (str "  \"" (escape-prose doc) "\"\n"))
        emits-clause (render-emits-clause emits)
        on-kw        (ref-of on-ref)]
    (str "(defn " symbol "\n"
         doc-line
         "  {:malli/schema " (render-malli-schema on-ref) "}\n"
         "  [payload state]\n"
         "  (throw (ex-info \"" symbol ": not yet implemented\"\n"
         "                  {:canvas-id \"" stable-id "\""
         (when on-kw
           (str "\n                   :on " (pr-str on-kw)))
         (when (seq emits-clause)
           (str "\n                   :emits [" emits-clause "]"))
         "})))")))

(defmethod core/project [:clojure :canvas/handler]
  [_lens-id element opts]
  (let [{:keys [stable-id entity-name module-coord doc on emits]} element
        registry (:registry opts)
        address  (addr/canonical registry
                                 :primitive/operation
                                 :projection-kind/operation
                                 module-coord
                                 entity-name)
        target   {:path      (core/ns->path (:ns address))
                  :namespace (:ns address)
                  :symbol    (:name address)}
        on-kw    (ref-of on)]
    {:projection-kind    :clojure/handler-to-defn
     :lens-id            :clojure
     :model-element-kind :Affordance
     :model-element-id   stable-id
     :target             target
     :template           (render-template target stable-id doc on emits)
     :prose              (prose-envelope entity-name doc on emits)
     :context            (cond-> {:canvas-source-ref (str "canvas/"
                                                          (str/replace module-coord #"\." "/")
                                                          ".clj")
                                  :doc-source        :canvas/handler-doc
                                  :arity             2
                                  :reactive?         true}
                           on-kw       (assoc :on    on-kw)
                           (seq emits) (assoc :emits (vec (keep ref-of emits))))}))
