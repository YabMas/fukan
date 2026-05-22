(ns fukan.vocabulary.allium.renderers
  "Default RendererRegistrations for Allium role-naming tags. Each
   registration declares a `treatments` map keyed by consumer; the only
   consumer wired in V0 is \"node\", with a minimum payload of
   `{\"icon\" <lowercased-tag-name>}`. The projection layer walks these
   registrations to assemble the per-primitive `treatment` map.

   The set mirrors `fukan.vocabulary.allium.tags/allium-tag-definitions` for
   the tags that name a primitive role (Module, Entity, Value, Variant,
   Surface, Contract, Rule, Event, Actor, Call, ExternalEntity).
   Source-clause sub-substrate tags (Invariant, Requires, Ensures, Let,
   ContractInvariant, SurfaceGuarantee, Guidance) and edge-shading tags
   (Trigger, Provides, Exposes, Fulfils, Demands) do not register a node
   treatment — they don't attach to a primitive node in the projection."
  (:require [clojure.string :as str]
            [fukan.model.vocabulary :as v]))

(defn- node-icon-registration [tag-name]
  (v/make-renderer-registration
    {:tag {:namespace "Allium" :name tag-name}
     :treatments {"node" {"icon" (str/lower-case tag-name)}}}))

(def allium-renderer-registrations
  "Default Allium renderer registrations — node-icon treatments for the
   role-naming primitive tags."
  (mapv node-icon-registration
        ["Module" "Entity" "Value" "Variant" "ExternalEntity"
         "Surface" "Contract" "Rule" "Event" "Actor" "Call"]))
