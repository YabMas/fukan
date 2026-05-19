(ns fukan.constraint.well-known
  "Fukan-shipped well-known constraints per MODEL.md §10.3.

   Each function returns a PredicateRegistration map that Phase 5
   evaluates. Parameterised constraints take their parameters as fn args.

   The five well-knowns:
     - signal-gap                  (warning)
     - no-dependency(from, to)     (error)
     - no-circular-refs            (error)
     - naming-convention(kind, re) (warning)
     - external-must-have-wrapper  (warning)")

(defn signal-gap
  "Every provides: Surface → Event edge has at least one triggers:
   Event → Rule consumer. Fires on the Event end of any provides edge
   with no outgoing triggers edge."
  []
  {:namespace "fukan"
   :name "signal_gap"
   :severity :warning
   :kind "methodology"
   :scope :scope/model
   :message-template "Event has no triggers: consumer"
   :predicate
   {:head {:predicate :violation :args [:?event]}
    :body [{:kind :atom :predicate :edge
            :args [:?surface :relation/provides :?event]}
           {:kind :negation
            :inner {:kind :atom :predicate :edge
                    :args [:?event :relation/triggers :?rule]}}]}})

(defn external-must-have-wrapper
  "Every Container tagged Allium::ExternalEntity belongs to a known
   module (i.e. has at least one :in-module derivation)."
  []
  {:namespace "fukan"
   :name "external_must_have_wrapper"
   :severity :warning
   :kind "methodology"
   :scope :scope/model
   :message-template "External entity has no wrapping module"
   :predicate
   {:head {:predicate :violation :args [:?ext]}
    :body [{:kind :atom :predicate :has-tag
            :args [:?ext "Allium::ExternalEntity"]}
           {:kind :negation
            :inner {:kind :atom :predicate :in-module
                    :args [:?ext :?m]}}]}})

(defn no-dependency
  "no_dependency(from-tag, to-tag): containers tagged `from-tag` must
   not have any edge to containers tagged `to-tag`."
  [from-tag to-tag]
  {:namespace "fukan"
   :name "no_dependency"
   :severity :error
   :kind "methodology"
   :scope :scope/model
   :message-template (str from-tag " must not depend on " to-tag)
   :predicate
   {:head {:predicate :violation :args [:?from :?to]}
    :body [{:kind :atom :predicate :has-tag :args [:?from from-tag]}
           {:kind :atom :predicate :has-tag :args [:?to to-tag]}
           {:kind :atom :predicate :depends-on
            :args [:?from :?to]}]}})

(defn no-circular-refs
  "no_circular_refs: no primitive has a transitive dependency on itself
   (i.e. is part of a dependency cycle, per the :depends-on derivation)."
  []
  {:namespace "fukan"
   :name "no_circular_refs"
   :severity :error
   :kind "methodology"
   :scope :scope/model
   :message-template "circular reference detected"
   :predicate
   {:head {:predicate :violation :args [:?x]}
    :body [{:kind :atom :predicate :depends-on
            :args [:?x :?x]}]}})

(defn naming-convention
  "naming_convention(kind, regex): every primitive of `kind` must have
   a :label matching `regex`. Uses the evaluator's :not-matches-regex
   comparison op against the :has-label EDB derivation, so the
   constraint fires on labels that do NOT match the supplied regex."
  [kind regex]
  {:namespace "fukan"
   :name "naming_convention"
   :severity :warning
   :kind "methodology"
   :scope :scope/model
   :message-template (str (name kind) " label doesn't match " regex)
   :predicate
   {:head {:predicate :violation :args [:?x :?label]}
    :body [{:kind :atom :predicate :primitive-kind :args [:?x kind]}
           {:kind :atom :predicate :has-label :args [:?x :?label]}
           {:kind :comparison :op :not-matches-regex
            :left :?label :right regex}]}})
