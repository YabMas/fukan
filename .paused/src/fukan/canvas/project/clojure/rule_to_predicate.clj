(ns fukan.canvas.project.clojure.rule-to-predicate
  "Clojure-lens projection — rule Affordance → predicate `defn`
   skeleton.

   Canvas `(rule \"GrantVoteOnRequest\" \"…descriptive doc…\" (when
   (RequestVoteReceived candidate term)))` declares a reactive
   behavioral declaration that fires on a trigger pattern. The
   corresponding Clojure idiom is symmetric with
   `invariant-to-predicate` — `(defn <kebab(rule-name)> [model] …)`
   returning true iff the reactive condition holds after the trigger
   fires.

   Where the invariant projection's prose framing is *timeless*
   (\"what must hold\"), the rule projection's framing carries the
   trigger pattern explicitly so the implementing LLM knows the
   reactive context. The trigger vec lives in the canvas
   `formal-expression.when` payload; Layer A renders it as quoted
   data inside the exception stub's `ex-info` map (audit trail; not
   executable shape).

   Routes: `[:clojure :canvas/rule]`."
  (:require [clojure.string :as str]
            [fukan.canvas.project.core :as core]
            [fukan.target.clojure.address :as addr]))

(defn- escape-prose
  [s]
  (when s
    (-> s
        str/trim
        (str/replace #"\"" "\\\\\""))))

(defn- when-pattern->str
  "Render the canvas `when` vec back to a parenthesized form for prose
   and `ex-info` audit. The vec arrives as `[TriggerName (param
   :Type) …]` of symbols/lists; pr-str on a wrapping list emits the
   readable shape."
  [when-vec]
  (when (seq when-vec)
    (pr-str (apply list when-vec))))

(defn- prose-envelope
  [rule-name doc when-vec]
  (let [when-str (when-pattern->str when-vec)]
    (str "Reactive rule: " rule-name "."
         (when (seq doc) (str " " (str/trim doc)))
         (when (seq when-str)
           (str "\n\nTrigger: " when-str "."))
         "\n\nThe rule predicate evaluates the model after the trigger fires"
         " and returns true iff the rule's reactive condition holds.")))

(defn- render-template
  [{:keys [symbol]} stable-id doc rule-name when-vec]
  (let [doc-line (when (seq doc)
                   (str "  \"" (escape-prose doc) "\"\n"))
        when-str (when-pattern->str when-vec)]
    (str "(defn " symbol "\n"
         doc-line
         "  [model]\n"
         "  (throw (ex-info \"" symbol ": not yet implemented\"\n"
         "                  {:canvas-id \"" stable-id "\"\n"
         "                   :rule-name \"" rule-name "\""
         (when (seq when-str)
           (str "\n                   :when (quote " when-str ")"))
         "})))")))

(defmethod core/project [:clojure :canvas/rule]
  [_lens-id element opts]
  (let [{:keys [stable-id entity-name module-coord doc when-vec]} element
        registry (:registry opts)
        address  (addr/canonical registry
                                 :primitive/rule
                                 :projection-kind/rule
                                 module-coord
                                 entity-name)
        target   {:path      (core/ns->path (:ns address))
                  :namespace (:ns address)
                  :symbol    (:name address)}]
    {:projection-kind    :clojure/rule-to-predicate
     :lens-id            :clojure
     :model-element-kind :Affordance
     :model-element-id   stable-id
     :target             target
     :template           (render-template target stable-id doc entity-name when-vec)
     :prose              (prose-envelope entity-name doc when-vec)
     :context            (cond-> {:canvas-source-ref (str "canvas/"
                                                          (str/replace module-coord #"\." "/")
                                                          ".clj")
                                  :doc-source        :canvas/rule-doc+when
                                  :rule-name         entity-name}
                           (seq when-vec) (assoc :when (when-pattern->str when-vec)))}))
