(ns demos.er.vocab.core
  "An entity-relationship data-modelling vocabulary, built directly on
   defstructure. Entities have typed attributes and directed relationships to
   other entities; well-formedness (referential integrity, no circular
   dependency) is expressed as slot laws + a recursive free law.

   Modelling choices worth noting — each is a finding about the core:
   - A relationship's CARDINALITY (1:1 / 1:N / N:M) is still a scalar fact with no
     home (an instance carries :name/:doc, slot relations, and now scalar VALUES).
     An attribute's `required?` / `unique?` ARE now expressible — as value slots
     (leaf :Bool values) — closing the gap this domain originally surfaced.
   - Bidirectional / cyclic relationships (User⇄Order) ARE authorable: a `declare`
     plus var-capture lets one instance reference another defined later. The shop
     model is kept acyclic on purpose (a cycle is a violation of the no-circular-
     dependency law); the circular-dependency test authors a real cycle via forward refs."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure DataType
  "A primitive attribute type — String, Int, Bool, ….")

(defstructure Attribute
  "A named, typed attribute of an entity. `:required`/`:unique?` are scalar flags
   — leaf values now that the core has them (closing this domain's original gap)."
  {:type     DataType
   :required [:? :boolean]
   :unique?  [:? :boolean]})

(defstructure Relationship
  "A directed relationship from its owning entity to a target entity."
  {:target Entity})

(defstructure Entity
  "A data entity: at least one attribute, plus relationships to other entities."
  {:attr [:+ Attribute]
   :rel  [:* Relationship]}
  ;; No circular dependency among entities. `refs*` is reachability over the
  ;; INDIRECT graph Entity →:rel→ Relationship →:target→ Entity, with the two-hop
  ;; step INLINED into the recursive rule (a recursive rule may not call a helper
  ;; rule — datascript diverges on cyclic data otherwise).
  (law "no circular dependency among entities"
    :rules '[[(refs* ?a ?b)
              [?rr :rel/from ?a]   [?rr :rel/kind :rel]    [?rr :rel/to ?rel]
              [?rt :rel/from ?rel] [?rt :rel/kind :target] [?rt :rel/to ?b]]
             [(refs* ?a ?b)
              [?rr :rel/from ?a]   [?rr :rel/kind :rel]    [?rr :rel/to ?rel]
              [?rt :rel/from ?rel] [?rt :rel/kind :target] [?rt :rel/to ?m]
              (refs* ?m ?b)]]
    :scope ::Entity
    :offenders '[?e]
    :where '[(refs* ?e ?e)]))
