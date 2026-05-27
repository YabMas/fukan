(ns fukan.canvas.lens.tar-pit-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.construction :refer [function record value]]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.lens.core :as core]
            [fukan.canvas.lens.registry :as registry]
            [fukan.canvas.lens.survey :as survey]
            [fukan.canvas.lens.tar-pit :as tar-pit]
            [fukan.canvas.vocab.behavioral :refer [invariant rule]]
            [fukan.canvas.vocab.lifecycle :refer [getter]]))

;; ---------------------------------------------------------------------------
;; Lens contract — substrate validation
;; ---------------------------------------------------------------------------

(deftest lens-var-is-well-formed
  (testing "the tar-pit lens var satisfies the substrate contract"
    (is (core/valid-lens? tar-pit/lens))
    (is (= :tar-pit (:id tar-pit/lens)))
    (is (string? (:description     tar-pit/lens)))
    (is (string? (:prompt-fragment tar-pit/lens)))
    (is (fn?     (:compute         tar-pit/lens)))
    (is (fn?     (:render          tar-pit/lens)))))

(deftest tar-pit-lens-registered
  (testing "the registry exposes the tar-pit lens by id"
    (is (some #(= :tar-pit (:id %)) (registry/all-lenses)))
    (is (= tar-pit/lens (registry/lens-by-id :tar-pit)))))

(deftest prompt-fragment-substantive
  (testing "the prompt-fragment is the load-bearing artifact — must be substantial"
    (let [pf (:prompt-fragment tar-pit/lens)
          words (count (clojure.string/split pf #"\s+"))]
      (is (>= words 200)
          (str "prompt-fragment too thin: " words " words"))
      (is (re-find #"(?i)essential" pf))
      (is (re-find #"(?i)accidental" pf))
      (is (re-find #"(?i)Moseley|Tar.?Pit" pf)))))

;; ---------------------------------------------------------------------------
;; Compute — slice extraction shape
;; ---------------------------------------------------------------------------

(defn- synth-db []
  (h/with-canvas
    (h/within-module "demo.orders"
      (record "Order"
        "p."
        (field id :String)
        (field customer :String))
      (record "Customer"
        "p."
        (field id :String))
      (value "OrderStatus" "An order status.")
      (getter "current_order" "The current order, if any." :Order)
      (function "compute_total"
        "Pure derivation."
        (takes [o :Order])
        (gives :String))
      (function "persist_order"
        "Effectful — writes to db."
        (takes [o :Order])
        (gives :Unit)
        (effect :db-write))
      (invariant "OrderHasCustomer"
        "Every order has a non-empty customer."
        (holds-that "order.customer != ''"))
      (rule "OnNewOrder"
        "Reacts to new orders."
        (when (NewOrder (o :Order)))))))

(deftest compute-extracts-slice-shape
  (testing "compute returns the expected nested slice structure"
    (let [db    (synth-db)
          slice (tar-pit/compute db {})]
      (is (contains? slice :state-candidates))
      (is (contains? slice :behavior))
      (is (contains? slice :scope-summary))
      (let [{:keys [types-with-fields atomic-types getters]} (:state-candidates slice)]
        (is (= 2 (:total types-with-fields))
            "Order + Customer are record-shaped")
        (is (= 1 (:total atomic-types))
            "OrderStatus is the atomic type")
        (is (= 1 (:total getters))
            "current_order is the one getter"))
      (let [{:keys [pure-functions effectful-functions declarative-rules]} (:behavior slice)]
        (is (= 1 (:total pure-functions))
            "compute_total is the pure function")
        (is (= 1 (:total effectful-functions))
            "persist_order is the effectful function")
        (is (= 2 (:total declarative-rules))
            "one invariant + one rule")))))

(deftest pure-vs-effectful-split-on-performs-relation
  (testing "functions with (effect ...) end up in effectful-functions, others pure"
    (let [db    (synth-db)
          slice (tar-pit/compute db {})
          eff   (-> slice :behavior :effectful-functions :items first)
          pure  (-> slice :behavior :pure-functions      :items first)]
      (is (true?  (:has-effects? eff)))
      (is (false? (:has-effects? pure)))
      (is (= [:db-write] (:effects eff)))
      (is (empty? (:effects pure))))))

(deftest record-fields-included
  (testing "record-shaped Types carry their [field-name field-type] tuples"
    (let [db    (synth-db)
          recs  (-> (tar-pit/compute db {}) :state-candidates :types-with-fields :items)
          order (first (filter #(= "Order" (:name %)) recs))]
      (is (some? order))
      (is (some (fn [[fname _]] (= :id fname))       (:fields order)))
      (is (some (fn [[fname _]] (= :customer fname)) (:fields order))))))

;; ---------------------------------------------------------------------------
;; Compute — opts behaviour
;; ---------------------------------------------------------------------------

(deftest max-members-truncates-categories
  (testing ":max-members caps each category and sets :truncated?"
    (let [db (h/with-canvas
               (h/within-module "demo.many"
                 (record "A" "p." (field x :String))
                 (record "B" "p." (field x :String))
                 (record "C" "p." (field x :String))
                 (record "D" "p." (field x :String))
                 (record "E" "p." (field x :String))))
          slice (tar-pit/compute db {:max-members 2})
          recs  (-> slice :state-candidates :types-with-fields)]
      (is (= 5 (:total recs)))
      (is (= 2 (count (:items recs))))
      (is (true? (:truncated? recs))))))

(deftest include-effects-false-omits-effectful-category
  (testing ":include-effects? false yields an :omitted marker, not the items"
    (let [db    (synth-db)
          slice (tar-pit/compute db {:include-effects? false})
          eff   (-> slice :behavior :effectful-functions)]
      (is (true? (:omitted eff)))
      (is (= 1   (:total   eff)))
      (is (nil?  (:items   eff)))
      ;; Pure functions are NOT affected
      (is (= 1 (-> slice :behavior :pure-functions :total))))))

;; ---------------------------------------------------------------------------
;; Scope summary — ratios
;; ---------------------------------------------------------------------------

(deftest scope-summary-ratios-computed
  (testing "scope-summary ratios computed from the categorised counts"
    (let [db    (synth-db)
          {:keys [scope-summary]} (tar-pit/compute db {})]
      (is (= 1 (:total-modules scope-summary)))
      ;; pure-fraction: 1 pure / 2 total fns = 0.5
      (is (= 0.5 (:pure-fraction   scope-summary)))
      (is (= 0.5 (:effect-fraction scope-summary)))
      ;; state-fraction: 4 state entities (2 records + 1 atomic + 1 getter)
      ;; / 6 total (4 state + 2 functions + … wait: behavior-count counts
      ;; functions + rules = 2 + 2 = 4) → 4/(4+4) = 0.5
      (is (= 0.5 (:state-fraction scope-summary))))))

(deftest scope-summary-handles-empty-canvas
  (testing "ratios safe on an empty canvas (no division by zero)"
    (let [db    (h/with-canvas)
          {:keys [scope-summary]} (tar-pit/compute db {})]
      (is (= 0   (:total-modules scope-summary)))
      (is (= 0.0 (:pure-fraction   scope-summary)))
      (is (= 0.0 (:effect-fraction scope-summary)))
      (is (= 0.0 (:state-fraction  scope-summary))))))

;; ---------------------------------------------------------------------------
;; Render — markdown synthesis
;; ---------------------------------------------------------------------------

(deftest render-contains-prompt-fragment-and-slice-sections
  (testing "render produces markdown with the prompt-fragment + slice sections"
    (let [db   (synth-db)
          out  (tar-pit/render (tar-pit/compute db {}) {})]
      (is (re-find #"Tar-Pit Analysis"      out))
      (is (re-find #"essential"             out))
      (is (re-find #"accidental"            out))
      (is (re-find #"Canvas slice"          out))
      (is (re-find #"State candidates"      out))
      (is (re-find #"(?i)pure functions"    out))
      (is (re-find #"(?i)effectful"         out))
      (is (re-find #"(?i)declarative"       out))
      (is (re-find #"Scope summary"         out))
      (is (re-find #"Modules:"              out))
      (is (re-find #"demo\.orders/type/Order"     out))
      (is (re-find #"demo\.orders/persist_order"  out))
      (is (re-find #"Questions for the LLM"       out)))))

(deftest render-shows-truncation-trailer
  (testing "truncated category renders a `... and N more` trailer"
    (let [db (h/with-canvas
               (h/within-module "demo.many"
                 (record "A" "p." (field x :String))
                 (record "B" "p." (field x :String))
                 (record "C" "p." (field x :String))
                 (record "D" "p." (field x :String))
                 (record "E" "p." (field x :String))))
          out (tar-pit/render (tar-pit/compute db {:max-members 2}) {})]
      (is (re-find #"and 3 more" out)))))

(deftest render-shows-omitted-when-include-effects-false
  (testing "render flags omitted effectful category clearly"
    (let [db  (synth-db)
          out (tar-pit/render
                (tar-pit/compute db {:include-effects? false})
                {:include-effects? false})]
      (is (re-find #"omitted" out)))))

(deftest render-no-severity
  (testing "render output is interpretive — never carries severity / error language"
    (let [db  (synth-db)
          out (tar-pit/render (tar-pit/compute db {}) {})]
      (is (not (re-find #"(?i)severity" out)))
      (is (not (re-find #"(?i)error:"   out))))))

;; ---------------------------------------------------------------------------
;; Substrate end-to-end — survey/run
;; ---------------------------------------------------------------------------

(deftest survey-run-tar-pit-end-to-end
  (testing "survey/run :tar-pit assembles a complete result"
    (let [db     (synth-db)
          result (survey/run db [:tar-pit])]
      (is (= [:tar-pit] (:survey/lenses result)))
      (let [r (first (:survey/results result))]
        (is (= :tar-pit (:lens/id r)))
        (is (string? (:lens/rendered r)))
        (is (some?   (:lens/findings r)))
        (is (contains? (:lens/findings r) :state-candidates))
        (is (contains? (:lens/findings r) :behavior))
        (is (contains? (:lens/findings r) :scope-summary))))))

(deftest survey-substrate-unchanged-accepts-slice-shape-compute
  (testing "the substrate accepts a :compute fn returning slice data (not findings list)
            — proves the contract is data-shape-agnostic"
    ;; If this passes without touching core/registry/survey, the substrate
    ;; handled a theoretical lens cleanly. The assertion is intentionally
    ;; structural — we check the surveyed result shape mirrors what
    ;; compute returned, with no substrate-side reshaping.
    (let [db     (synth-db)
          slice  (tar-pit/compute db {})
          result (survey/run db [:tar-pit])
          r      (first (:survey/results result))]
      (is (= slice (:lens/findings r))
          "survey must pass compute's return through verbatim — no findings-shape coercion"))))
