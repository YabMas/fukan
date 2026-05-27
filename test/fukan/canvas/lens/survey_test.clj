(ns fukan.canvas.lens.survey-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.lens.registry :as registry]
            [fukan.canvas.lens.survey :as survey]))

;; ---------------------------------------------------------------------------
;; Tiny canvas db for survey dispatch tests.
;; ---------------------------------------------------------------------------

(defn- tiny-canvas-db []
  (h/with-canvas
    (h/within-module "demo"
      (function "f"
        "An identity-ish function."
        (takes [x :String])
        (gives :String)))))

;; ---------------------------------------------------------------------------
;; Survey dispatch over a synthetic lens.
;; ---------------------------------------------------------------------------

(deftest run-invokes-compute-then-render
  (testing "run calls :compute then :render and assembles a result map"
    (let [calls (atom [])
          fake  {:id              :fake
                 :description     "Fake lens."
                 :prompt-fragment "fake prompt"
                 :compute (fn [_db opts]
                            (swap! calls conj [:compute opts])
                            {:fake-findings 42})
                 :render  (fn [findings opts]
                            (swap! calls conj [:render findings opts])
                            (str "fake-rendered:" (:fake-findings findings)))}
          db (tiny-canvas-db)]
      (with-redefs [registry/lens-by-id (fn [id] (when (= id :fake) fake))]
        (let [result (survey/run db [:fake] {:my-opt true})]
          (is (= [:fake] (:survey/lenses result)))
          (is (= 1 (count (:survey/results result))))
          (let [r (first (:survey/results result))]
            (is (= :fake (:lens/id r)))
            (is (= "Fake lens." (:lens/description r)))
            (is (= {:fake-findings 42} (:lens/findings r)))
            (is (= "fake-rendered:42" (:lens/rendered r))))
          (is (re-find #"Fake lens\." (:survey/rendered result)))
          (is (re-find #"fake-rendered:42" (:survey/rendered result)))
          (is (= [[:compute {:my-opt true}]
                  [:render {:fake-findings 42} {:my-opt true}]]
                 @calls)))))))

(deftest run-without-compute-passes-nil-to-render
  (testing "lenses without :compute receive nil findings in :render"
    (let [fake {:id              :no-compute
                :description     "No compute."
                :prompt-fragment "frame-only"
                :render          (fn [findings _opts]
                                   (if (nil? findings)
                                     "no-findings"
                                     "had-findings"))}
          db (tiny-canvas-db)]
      (with-redefs [registry/lens-by-id (fn [id] (when (= id :no-compute) fake))]
        (let [result (survey/run db [:no-compute])]
          (is (= "no-findings"
                 (-> result :survey/results first :lens/rendered)))
          (is (nil? (-> result :survey/results first :lens/findings))))))))

(deftest run-with-unknown-id-produces-warning
  (testing "an unregistered lens id produces a warning entry, not a throw"
    (let [db (tiny-canvas-db)
          result (survey/run db [:no-such-lens])]
      (is (= 1 (count (:survey/results result))))
      (let [r (first (:survey/results result))]
        (is (= :no-such-lens (:lens/id r)))
        (is (string? (:lens/warning r))))
      (is (re-find #"warning" (:survey/rendered result))))))

(deftest run-multi-lens-assembles-sections
  (testing "multiple lenses produce per-section markdown in :survey/rendered"
    (let [a {:id :a :description "Lens A." :prompt-fragment "."
             :render (fn [_ _] "a-body")}
          b {:id :b :description "Lens B." :prompt-fragment "."
             :render (fn [_ _] "b-body")}
          db (tiny-canvas-db)]
      (with-redefs [registry/lens-by-id (fn [id] (case id :a a :b b nil))]
        (let [result (survey/run db [:a :b])]
          (is (= [:a :b] (:survey/lenses result)))
          (is (re-find #"Lens A\." (:survey/rendered result)))
          (is (re-find #"Lens B\." (:survey/rendered result)))
          (is (re-find #"a-body" (:survey/rendered result)))
          (is (re-find #"b-body" (:survey/rendered result))))))))
