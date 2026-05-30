(ns fukan.canvas.vocab.registry
  "The tag-definition registry: every canvas vocabulary term as declared data —
   its kind `:tag`, its `:family` (the coarse Module/Affordance/Type grouping),
   the `:payload` type an application carries, and a one-line `:doc`.

   This is the vocabulary made self-describing. The registry is projected into
   the substrate db as `:tagdef/*` entities (see canvas-source/enrich-substrate),
   so the vocabulary is introspectable via `d/q` alongside the model it
   classifies — and it is the seam a generic `declare-node` will consume to build
   nodes from a term + payload instead of bespoke per-term lifts.

   `:payload` is a descriptor (`:arrow`/`:record`/`:prose`/`:trigger`/`:on-emits`/
   `:none`) — the full typed payload schema and the produces-recipe arrive with
   the generic declare-node work. Central for now; per-vocab co-location follows.")

(def tag-definitions
  "Declared definition for every canvas vocabulary term. `:family` nil marks a
   secondary marker tag (not a primary node kind)."
  [{:tag :canvas/module
    :family :Module :payload :none
    :doc "A grouping/namespace host; owns its children via containment."}
   {:tag :canvas/value
    :family :Type :payload :none
    :doc "An opaque named type — a concept whose internal structure is withheld."}
   {:tag :canvas/record
    :family :Type :payload :record
    :edges [{:strategy :shape-refs :edge :references}]
    :doc "A data type with named, typed fields."}
   {:tag :fukan.canvas.monolith/exposed-call
    :family :Affordance :payload :arrow
    :edges [{:strategy :shape-refs  :edge :references}
            {:strategy :to-keywords :from :effect   :edge :fukan.canvas.monolith/performs}
            {:strategy :by-name     :from :triggers :edge :triggers}
            {:strategy :by-name     :from :emits    :edge :emits :role :canvas/event}]
    :doc "A synchronous function call: takes inputs, gives an output, may have effects."}
   {:tag :canvas/getter
    :family :Affordance :payload :arrow
    :edges [{:strategy :shape-refs :edge :references}]
    :doc "A zero-arg accessor returning Optional<T>."}
   {:tag :canvas/checker
    :family :Affordance :payload :arrow
    :edges [{:strategy :shape-refs :edge :references}]
    :doc "A validation entry point with the signature (Model) -> [Violation]."}
   {:tag :canvas/invariant
    :family :Affordance :payload :prose
    :doc "A timeless behavioural commitment, stated as prose."}
   {:tag :canvas/rule
    :family :Affordance :payload :trigger
    :doc "A reactive declaration that fires when its trigger pattern matches."}
   {:tag :canvas/event
    :family :Affordance :payload :record
    :edges [{:strategy :shape-refs :edge :references}]
    :doc "A named event message carrying a payload record."}
   {:tag :canvas/handler
    :family :Affordance :payload :on-emits
    :edges [{:strategy :to-keywords :from :on    :edge :references}
            {:strategy :to-keywords :from :emits :edge :references}]
    :doc "A reactive handler: invoked by an event, may emit downstream events."}
   {:tag :exported
    :family nil :payload :none
    :doc "Marker: tags a declaration as part of its module's exported API."}])
