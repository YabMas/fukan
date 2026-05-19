(ns fukan.validation.rules-4c
  "Phase 4c — binding rules (per DESIGN.md §4c).

   Five violation kinds:
     1. :4c/unresolved-operation (error) — `fn Contract.op` / `fn alias/Contract.op`
        whose op-id doesn't resolve. From `:unresolved-operation` records.
     2. :4c/unresolved-trigger-rule (error) — `fn { triggers: Rule }` whose
        rule-id doesn't resolve. From `:unresolved-trigger-rule` records.
     3. :4c/attach-returns-without-triggers (warning) — attach-form fn with
        returns: but no triggers: — no edge to tag. From Plan 3b carry-forward.
     4. :4c/return-derivation-mismatch (error) — `returns:` required iff
        Operation has `:return-type`. Walks R4 edges + Boundary::Binding tags.
     5. :4c/signature-match-uncertain (warning) — reduced-fidelity at MVP:
        WARNING when Rule has no event-shaped trigger beyond the binding
        itself. Plan 4's constraint engine subsumes full equality check."
  (:require [fukan.validation.violation :as v]
            [fukan.model.build :as build]))

(defn- binding-issues [model]
  (get-in model [:phase4-state :binding-issues] []))

(defn- unresolved-operations [model]
  (for [issue (binding-issues model)
        :when (= :unresolved-operation (:kind issue))]
    (v/make-violation
      {:severity :error :phase :phase4 :sub-phase :4c
       :kind :4c/unresolved-operation
       :location (dissoc issue :kind)
       :message (str "binding references an Operation that does not resolve: " (pr-str issue))})))

(defn- unresolved-trigger-rules [model]
  (for [issue (binding-issues model)
        :when (= :unresolved-trigger-rule (:kind issue))]
    (v/make-violation
      {:severity :error :phase :phase4 :sub-phase :4c
       :kind :4c/unresolved-trigger-rule
       :location (dissoc issue :kind)
       :message (str "binding's triggers: clause references an unresolved Rule: " (pr-str issue))})))

(defn- attach-returns-without-triggers [model]
  (for [issue (binding-issues model)
        :when (= :attach-returns-without-triggers (:kind issue))]
    (v/make-violation
      {:severity :warning :phase :phase4 :sub-phase :4c
       :kind :4c/attach-returns-without-triggers
       :location (dissoc issue :kind)
       :message "fn attach-form has returns: but no triggers: — no edge to tag"})))

(defn- binding-edges [model]
  (filter #(= :relation/triggers (:kind %)) (:edges model)))

(defn- operation-by-id [model id]
  (let [p (build/get-primitive model id)]
    (when (= :primitive/operation (:kind p)) p)))

(defn- binding-tag-for-edge [model edge-id]
  (first (filter (fn [ta]
                   (and (= "Boundary" (-> ta :tag :namespace))
                        (= "Binding" (-> ta :tag :name))
                        (= edge-id (-> ta :target :edge-identity))))
                 (:tag-apps model))))

(defn- return-type-mismatches [model]
  (for [edge (binding-edges model)
        :let [op-id (-> edge :from :id)
              op (operation-by-id model op-id)
              edge-id ((requiring-resolve 'fukan.model.relations/edge-identity) edge)
              binding-tag (binding-tag-for-edge model edge-id)
              has-return (some? (:return-type op))
              has-returns-clause (some? (-> binding-tag :payload :returns_expression))]
        :when (and op (not= has-return has-returns-clause))]
    (v/make-violation
      {:severity :error :phase :phase4 :sub-phase :4c
       :kind :4c/return-derivation-mismatch
       :location {:operation op-id :edge-id edge-id}
       :message (if has-return
                  (str "Operation " op-id " has a return-type but binding has no returns: clause")
                  (str "Operation " op-id " has no return-type but binding has a returns: clause"))})))

(defn- rule-by-id [model id]
  (let [p (build/get-primitive model id)]
    (when (= :primitive/rule (:kind p)) p)))

(defn- signature-mismatches
  "Reduced-fidelity check at MVP: warn when the bound Rule has no event-shaped
   triggers edge beyond the binding itself. Plan 4's constraint language will
   subsume the full signature-equality check."
  [model]
  (for [edge (binding-edges model)
        :let [rule-id (-> edge :to :id)
              rule (rule-by-id model rule-id)
              ;; Other triggers edges pointing at this Rule (excluding `edge`):
              other-triggers (filter #(and (= :relation/triggers (:kind %))
                                           (= rule-id (-> % :to :id))
                                           (not= (-> edge :from :id) (-> % :from :id)))
                                     (:edges model))]
        :when (and rule (empty? other-triggers))]
    (v/make-violation
      {:severity :warning :phase :phase4 :sub-phase :4c
       :kind :4c/signature-match-uncertain
       :location {:rule-id rule-id :op-id (-> edge :from :id)}
       :message (str "Rule " rule-id " has no event-shaped when: clause beyond the binding itself — signature match cannot be verified at MVP fidelity")})))

(defn check
  "Run all 4c binding rules. Returns a vector of Violations."
  [model]
  (vec (concat
         (unresolved-operations model)
         (unresolved-trigger-rules model)
         (attach-returns-without-triggers model)
         (return-type-mismatches model)
         (signature-mismatches model))))
