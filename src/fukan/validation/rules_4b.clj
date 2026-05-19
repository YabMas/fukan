(ns fukan.validation.rules-4b
  "Phase 4b — event rules (per DESIGN.md §4b).

   Plan 3c MVP implements two rules as runtime violations:
     1. :4b/event-no-declaration-site (error) — every Event must have at
        least one declaration site (provides:, when:, emits) recorded in
        the Allium::Event tag's :declaration-sites payload.
     2. :4b/event-shape-mismatch (error) — declaration sites for the same
        Event in a module must agree on parameter shape (arity + typed
        sequences). Plan 2b's analyzer records disagreements in
        `:phase4-state.event-shape-mismatches`; this rule surfaces each
        record as a Violation.

   Two further rules from DESIGN.md §4b are NOT implemented here:
     - Cross-module event-name collisions: impossible by construction
       (Allium namespaces qualify event ids by module coordinate).
     - Allium rule 30 (Surface `provides:` triggers must be external-
       stimulus in a rule of the same module): deferred to Plan 4's
       constraint engine — it requires walking edges from Surface to
       Event to Rule and inspecting the Allium::Trigger payload on the
       Event→Rule edge."
  (:require [fukan.validation.violation :as v]))

(defn- events
  "All Event primitives in the model."
  [model]
  (filter #(= :primitive/event (:kind %)) (vals (:primitives model))))

(defn- event-declaration-sites
  "Read `:declaration-sites` for an Event from its Allium::Event TagApplication
   payload. Returns nil if no such TagApplication exists."
  [model event-id]
  (let [ta (first (filter (fn [ta]
                            (and (= "Allium" (-> ta :tag :namespace))
                                 (= "Event"  (-> ta :tag :name))
                                 (= :target/primitive (-> ta :target :case))
                                 (= event-id (-> ta :target :id))))
                          (:tag-apps model)))]
    (-> ta :payload :declaration-sites)))

;; -- Rule 1: event must have at least one declaration site -------------------

(defn- events-without-declaration-sites [model]
  (for [ev (events model)
        :let [sites (event-declaration-sites model (:id ev))]
        :when (or (nil? sites) (empty? sites))]
    (v/make-violation
      {:severity :error :phase :phase4 :sub-phase :4b
       :kind     :4b/event-no-declaration-site
       :location {:event-id (:id ev)}
       :message  (str "Event " (:id ev)
                      " has no declaration site within its owning module")})))

;; -- Rule 2: declaration sites must agree on parameter shape -----------------

(defn- shape-mismatches [model]
  (for [mm (get-in model [:phase4-state :event-shape-mismatches] [])]
    (v/make-violation
      {:severity :error :phase :phase4 :sub-phase :4b
       :kind     :4b/event-shape-mismatch
       :location {:event-id (:event-id mm)
                  :module   (:module-coord mm)}
       :message  (str "Event " (:event-id mm)
                      " has inconsistent parameter shapes across declaration sites: "
                      (pr-str (:shapes mm)))})))

(defn check
  "Run all 4b event rules. Returns a vector of Violations."
  [model]
  (vec (concat
         (events-without-declaration-sites model)
         (shape-mismatches model))))
