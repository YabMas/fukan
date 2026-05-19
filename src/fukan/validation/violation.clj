(ns fukan.validation.violation
  "Phase 4 / Phase 5 Violation value-record. Shape per DESIGN.md
   §'Phase ordering and error semantics'.")

(defn make-violation
  "Construct a Violation. severity ∈ #{:error :warning}, phase ∈ #{:phase4 :phase5},
   sub-phase is one of #{:4a :4b :4c :4d :4e :4f :4g :5} for Phase 4 / Phase 5
   respectively (Phase 5 uses :5 since it has no sub-phases). kind is an open
   keyword namespacing the violation type. location is a free-form map carrying
   attribution context (e.g. {:coord <coord> :primitive-id <id>}). message is
   a human-readable string."
  [{:keys [severity phase sub-phase kind location message]}]
  {:severity  severity
   :phase     phase
   :sub-phase sub-phase
   :kind      kind
   :location  (or location {})
   :message   message})

(defn error?   [v] (= :error   (:severity v)))
(defn warning? [v] (= :warning (:severity v)))

(defn errors [violations]
  (filterv error? violations))

(defn warnings [violations]
  (filterv warning? violations))
