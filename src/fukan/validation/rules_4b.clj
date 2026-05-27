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

   Plan 4 Task 7 implements:
     3. :4b/provides-no-external-stimulus (error) — every Surface's provides:
        edge to an Event must have at least one external-stimulus triggers
        consumer in the same module (Allium rule 30). This is a hand-coded
        Phase 4b rule checking kernel-level edges and tag applications."
  (:require [fukan.validation.violation :as v]
            [fukan.model.relations :as r]
            [clojure.string :as str]))

(defn- events
  "All Event primitives in the model."
  [model]
  (filter #(= :primitive/event (:kind %)) (vals (:primitives model))))

(defn- event-tag-app
  "Find the Allium::Event TagApplication for an Event, if any."
  [model event-id]
  (first (filter (fn [ta]
                   (and (= "Allium" (-> ta :tag :namespace))
                        (= "Event"  (-> ta :tag :name))
                        (= :target/primitive (-> ta :target :case))
                        (= event-id (-> ta :target :id))))
                 (:tag-apps model))))

(defn- event-declaration-sites
  "Read `:declaration-sites` for an Event from its Allium::Event TagApplication
   payload. Returns nil if no such TagApplication exists."
  [model event-id]
  (-> (event-tag-app model event-id) :payload :declaration-sites))

;; -- Rule 1: event must have at least one declaration site -------------------
;;
;; This rule is Allium-era: it interrogates the :declaration-sites payload that
;; the Allium analyzer attaches to Events via the Allium::Event TagApplication.
;; Canvas-source-emitted events do not carry that tag-app — declaration-site
;; semantics for canvas events are handled by canvas-side inspect checks
;; (e.g. `emits` Relation integrity). Canvas-style event ids use '/' (e.g.
;; "demo.events/ThingHappened") while Allium-era ids use '::'. Events whose
;; id is canvas-style are out of scope for this rule.

(defn- canvas-style-id?
  [id]
  (and (string? id) (str/includes? id "/") (not (str/includes? id "::"))))

(defn- events-without-declaration-sites [model]
  (for [ev (events model)
        :let [sites (event-declaration-sites model (:id ev))]
        :when (and (not (canvas-style-id? (:id ev)))
                   (or (nil? sites) (empty? sites)))]
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

;; -- Rule 3: provides: must have external-stimulus triggers in same module -----

(defn- module-of-id
  "Extract the module-coord prefix from an id like 'm/sub::events::Foo'.
   Returns nil if no '::' is present."
  [id]
  (when (and (string? id) (str/includes? id "::"))
    (first (str/split id #"::" 2))))

(defn- provides-edges [model]
  (filter #(= :relation/provides (:kind %)) (:edges model)))

(defn- triggers-edges [model]
  (filter #(= :relation/triggers (:kind %)) (:edges model)))

(defn- external-stimulus-edge?
  "True iff the edge has an Allium::Trigger tag-app with payload :kind
   'external_stimulus'."
  [model edge]
  (let [edge-id (r/edge-identity edge)]
    (some (fn [ta]
            (and (= "Allium" (-> ta :tag :namespace))
                 (= "Trigger" (-> ta :tag :name))
                 (= edge-id (-> ta :target :edge-identity))
                 (= "external_stimulus" (-> ta :payload :kind))))
          (:tag-apps model))))

(defn- provides-without-external-stimulus [model]
  (for [pe (provides-edges model)
        :let [event-id (-> pe :to :id)
              event-module (module-of-id event-id)
              ext-triggers (filter (fn [te]
                                     (and (= event-id (-> te :from :id))
                                          (= event-module (module-of-id (-> te :to :id)))
                                          (external-stimulus-edge? model te)))
                                   (triggers-edges model))]
        :when (empty? ext-triggers)]
    (v/make-violation
      {:severity :error :phase :phase4 :sub-phase :4b
       :kind :4b/provides-no-external-stimulus
       :location {:provides-edge pe :event-id event-id :module event-module}
       :message (str "Event " event-id " is provided by a Surface but has no external-stimulus triggers consumer in module " event-module)})))

;; -- Rule 4: exposes: paths must resolve to a substrate endpoint -------------

(defn- exposes-unresolved [model]
  (for [issue (get-in model [:phase4-state :exposes-issues] [])]
    (v/make-violation
      {:severity :error :phase :phase4 :sub-phase :4b
       :kind     :4b/exposes-unresolved
       :location {:surface (:surface-id issue)
                  :module  (:module issue)
                  :path    (:path issue)
                  :target  (:target-id issue)
                  :field   (:field issue)
                  :resolution (:resolution issue)}
       :message  (str "exposes: path '" (:path issue) "' on surface "
                      (:surface-id issue) " could not be added as an edge ("
                      (name (:resolution issue)) "): "
                      (:message issue))})))

(defn check
  "Run all 4b event rules. Returns a vector of Violations."
  [model]
  (vec (concat
         (events-without-declaration-sites model)
         (shape-mismatches model)
         (provides-without-external-stimulus model)
         (exposes-unresolved model))))
