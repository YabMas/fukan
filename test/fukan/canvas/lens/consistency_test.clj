(ns fukan.canvas.lens.consistency-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.construction :refer [function record value]]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.lens.consistency :as consistency]
            [fukan.canvas.lens.core :as core]
            [fukan.canvas.lens.registry :as registry]
            [fukan.canvas.lens.survey :as survey]
            [fukan.canvas.vocab.behavioral :refer [invariant]]
            [fukan.canvas.vocab.validation :refer [checker]]))

;; ---------------------------------------------------------------------------
;; classify-style — unit
;; ---------------------------------------------------------------------------

(deftest classify-style-buckets
  (testing "naming-style classifier covers the five expected buckets"
    (is (= :snake_case    (consistency/classify-style "start_server")))
    (is (= :snake_case    (consistency/classify-style "edges_by_kind")))
    (is (= :camelCase     (consistency/classify-style "addPrimitive")))
    (is (= :PascalCase    (consistency/classify-style "ServerOpts")))
    (is (= :PascalCase    (consistency/classify-style "Model")))
    (is (= :kebab-case    (consistency/classify-style "build-canvas-db")))
    (is (= :SHOUTING_CASE (consistency/classify-style "MAX_RETRIES")))))

;; ---------------------------------------------------------------------------
;; Check 1 — naming-style mixing within {module, entity-type, role}
;; ---------------------------------------------------------------------------

(deftest naming-mixing-within-role-flagged
  (testing "two PascalCase invariants + one snake_case invariant in one module → finding"
    (let [db (h/with-canvas
               (h/within-module "demo.mixed"
                 (invariant "FirstInvariant"
                   "p1." (holds-that "p1"))
                 (invariant "SecondInvariant"
                   "p2." (holds-that "p2"))
                 (invariant "third_invariant"
                   "p3." (holds-that "p3"))))
          {:keys [naming-mixings]} (consistency/compute db {})]
      (is (= 1 (count naming-mixings))
          "exactly one mixed cluster expected")
      (let [m (first naming-mixings)]
        (is (= "demo.mixed" (:module m)))
        (is (= :Affordance (:entity-type m)))
        (is (= :canvas/invariant (:role m)))
        (is (= #{:PascalCase :snake_case} (set (keys (:styles m)))))))))

(deftest cross-role-mixing-not-flagged
  (testing "PascalCase records + snake_case functions in one module → no finding"
    (let [db (h/with-canvas
               (h/within-module "demo.clean"
                 (record "Account"
                   "p." (field id :String))
                 (function "credit_account"
                   "p."
                   (takes [a :Account])
                   (gives :Unit))))
          {:keys [naming-mixings]} (consistency/compute db {})]
      (is (empty? naming-mixings)
          "different entity-types may legitimately use different styles"))))

(deftest naming-clean-module-quiet
  (testing "a module whose invariants are all PascalCase yields no naming finding"
    (let [db (h/with-canvas
               (h/within-module "demo.uniform"
                 (invariant "FirstInvariant" "p." (holds-that "p"))
                 (invariant "SecondInvariant" "p." (holds-that "p"))))
          {:keys [naming-mixings]} (consistency/compute db {})]
      (is (empty? naming-mixings)))))

;; ---------------------------------------------------------------------------
;; Check 2 — field-type drift across same-named record fields
;; ---------------------------------------------------------------------------

(deftest field-type-drift-flagged
  (testing "a field-name appearing with two different types across records → finding"
    (let [db (h/with-canvas
               (h/within-module "demo.orders"
                 (value "OrderStatus" "An order status.")
                 (record "OrderA"
                   "p."
                   (field status :String))
                 (record "OrderB"
                   "p."
                   (field status :OrderStatus))))
          {:keys [field-type-drifts]} (consistency/compute db {})
          drift (first (filter #(= :status (:field-name %)) field-type-drifts))]
      (is (some? drift)
          ":status drift must be surfaced")
      (is (= #{:String :OrderStatus} (set (keys (:types drift))))))))

(deftest consistent-fields-not-flagged
  (testing "a field-name with consistent types across records → no finding for that field"
    (let [db (h/with-canvas
               (h/within-module "demo.ids"
                 (record "A"
                   "p." (field id :String))
                 (record "B"
                   "p." (field id :String))
                 (record "C"
                   "p." (field id :String))))
          {:keys [field-type-drifts]} (consistency/compute db {})]
      (is (empty? (filter #(= :id (:field-name %)) field-type-drifts))))))

(deftest exempt-fields-skipped
  (testing ":exempt-fields opt suppresses surfacing for known-varying field names"
    (let [db (h/with-canvas
               (h/within-module "demo.payload"
                 (record "A" "p." (field value :String))
                 (record "B" "p." (field value :Integer))))
          {:keys [field-type-drifts]}
          (consistency/compute db {:exempt-fields #{:value}})]
      (is (empty? (filter #(= :value (:field-name %)) field-type-drifts))
          ":value must be exempted via opts"))))

;; ---------------------------------------------------------------------------
;; Check 3 — sister-module structural symmetry
;; ---------------------------------------------------------------------------

(deftest sister-asymmetry-flagged
  (testing "three sibling modules where one declares an extra entity → outlier finding"
    (let [db (h/with-canvas
               (h/within-module "rules-a"
                 (checker "check" "p."))
               (h/within-module "rules-b"
                 (checker "check" "p."))
               (h/within-module "rules-c"
                 (checker "check" "p.")
                 (function "extra_op"
                   "p."
                   (takes [])
                   (gives :Unit))))
          {:keys [sister-asymmetries]} (consistency/compute db {})]
      (is (= 1 (count sister-asymmetries))
          "one sibling group should yield one asymmetry finding")
      (let [g (first sister-asymmetries)]
        (is (= "rules-" (:prefix g)))
        (is (some #(= "rules-c" (:module %)) (:outliers g))
            "rules-c must be the outlier")))))

(deftest sister-symmetric-not-flagged
  (testing "three sibling modules with identical multisets → no finding"
    (let [db (h/with-canvas
               (h/within-module "rules-a"
                 (checker "check" "p."))
               (h/within-module "rules-b"
                 (checker "check" "p."))
               (h/within-module "rules-c"
                 (checker "check" "p.")))
          {:keys [sister-asymmetries]} (consistency/compute db {})]
      (is (empty? sister-asymmetries)))))

(deftest sister-min-cluster-respected
  (testing ":min-cluster opt controls the minimum sibling-group size"
    (let [db (h/with-canvas
               (h/within-module "rules-a"
                 (checker "check" "p."))
               (h/within-module "rules-b"
                 (checker "check" "p.")
                 (function "extra_op" "p." (takes []) (gives :Unit))))
          {:keys [sister-asymmetries]}
          (consistency/compute db {:min-cluster 2})]
      ;; min-cluster 2 admits the pair; default 3 would not.
      (is (= 1 (count sister-asymmetries))))))

;; ---------------------------------------------------------------------------
;; opts thread-through — :checks selector
;; ---------------------------------------------------------------------------

(deftest opts-checks-naming-only
  (testing ":checks #{:naming} runs only the naming check"
    (let [db (h/with-canvas
               (h/within-module "demo.mixed"
                 (invariant "FirstInvariant" "p." (holds-that "p"))
                 (invariant "second_invariant" "p." (holds-that "p"))
                 (invariant "third_invariant" "p." (holds-that "p")))
               (h/within-module "demo.fields"
                 (record "A" "p." (field status :String))
                 (record "B" "p." (field status :Integer))))
          out (consistency/compute db {:checks #{:naming}})]
      (is (seq (:naming-mixings out)))
      (is (empty? (:field-type-drifts out))
          ":field-types check must be skipped when not in :checks")
      (is (empty? (:sister-asymmetries out))))))

(deftest opts-checks-field-types-only
  (testing ":checks #{:field-types} runs only the field-type check"
    (let [db (h/with-canvas
               (h/within-module "demo.mixed"
                 (invariant "FirstInvariant" "p." (holds-that "p"))
                 (invariant "second_invariant" "p." (holds-that "p")))
               (h/within-module "demo.fields"
                 (record "A" "p." (field status :String))
                 (record "B" "p." (field status :Integer))))
          out (consistency/compute db {:checks #{:field-types}})]
      (is (empty? (:naming-mixings out)))
      (is (seq (:field-type-drifts out))))))

;; ---------------------------------------------------------------------------
;; Render
;; ---------------------------------------------------------------------------

(deftest render-empty-says-clean
  (testing "render returns a 'no findings' message when all checks are clean"
    (let [out (consistency/render
                {:naming-mixings []
                 :field-type-drifts []
                 :sister-asymmetries []}
                {})]
      (is (re-find #"[Nn]o " out)))))

(deftest render-has-three-sections-when-populated
  (testing "render emits markdown sections for every populated check"
    (let [findings {:naming-mixings
                    [{:module      "demo.mixed"
                      :entity-type :Affordance
                      :role        :canvas/invariant
                      :styles      {:PascalCase ["FirstInvariant"]
                                    :snake_case ["second_invariant"]}}]
                    :field-type-drifts
                    [{:field-name :status
                      :types      {:String        ["demo.orders/type/OrderA/status"]
                                   :OrderStatus   ["demo.orders/type/OrderB/status"]}}]
                    :sister-asymmetries
                    [{:prefix    "rules-"
                      :majority  {[:Affordance :canvas/checker] 1}
                      :outliers  [{:module "rules-c"
                                   :extras {[:Affordance :fukan.canvas.monolith/exposed-call] 1}}]}]}
          out (consistency/render findings {})]
      (is (re-find #"(?i)naming" out))
      (is (re-find #"(?i)field" out))
      (is (re-find #"(?i)sister|symmetry|outlier" out))
      (is (re-find #"demo.mixed" out))
      (is (re-find #":status" out))
      (is (re-find #"rules-c" out)))))

;; ---------------------------------------------------------------------------
;; Lens contract + registration
;; ---------------------------------------------------------------------------

(deftest lens-var-is-well-formed
  (testing "the consistency lens var satisfies the substrate contract"
    (is (core/valid-lens? consistency/lens))
    (is (= :consistency (:id consistency/lens)))
    (is (string? (:description     consistency/lens)))
    (is (string? (:prompt-fragment consistency/lens)))
    (is (fn?     (:compute         consistency/lens)))
    (is (fn?     (:render          consistency/lens)))))

(deftest consistency-lens-registered
  (testing "the registry exposes the consistency lens by id"
    (is (some #(= :consistency (:id %)) (registry/all-lenses)))
    (is (= consistency/lens (registry/lens-by-id :consistency)))))

(deftest survey-against-synthetic-canvas-runs-consistency
  (testing "survey/run :consistency end-to-end produces a finding shape"
    (let [db (h/with-canvas
               (h/within-module "demo.mixed"
                 (invariant "FirstInvariant" "p." (holds-that "p"))
                 (invariant "second_invariant" "p." (holds-that "p"))
                 (invariant "third_invariant" "p." (holds-that "p"))))
          result (survey/run db [:consistency])]
      (is (= [:consistency] (:survey/lenses result)))
      (let [r (first (:survey/results result))]
        (is (= :consistency (:lens/id r)))
        (is (string? (:lens/rendered r)))
        (is (some? (:lens/findings r)))
        (is (contains? (:lens/findings r) :naming-mixings))
        (is (contains? (:lens/findings r) :field-type-drifts))
        (is (contains? (:lens/findings r) :sister-asymmetries))))))
