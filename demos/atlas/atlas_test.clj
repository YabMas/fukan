(ns demos.atlas.atlas-test
  "Regression for the Atlas demo: the auth/users subsystem is well-formed, and each
   kind of ill-formedness is caught — an execution-function depending UPWARD across
   tiers (Atlas's flagship invariant), a facet slot pointed at the wrong type, and
   (style B) an entity carrying two aspects on the SAME axis. Run via `clj -M:demos`."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [fukan.canvas.core.assemble :as a]
            [demos.atlas.model.auth :as auth]
            [demos.atlas.vocab.core
             :refer [Domain Operation Tier ExecutionFunction
                     Axis Aspect Faceted]]))

(defn- laws [db] (set (map :law (s/check db))))

(deftest auth-subsystem-is-well-formed
  (testing "the auth/users subsystem — all deps downward or sideways, aspects on distinct axes — satisfies every law"
    (is (empty? (s/check (auth/build))))))

;; ── case 1: dependency UPWARD across tiers — Atlas's flagship invariant ───────
;; A service-tier function calls an api-tier function. foundation ← service ← api,
;; so api is "over" service and the dependency points upward — illegal.

(Tier ^{:name "foundation"} c1-foundation)
(Tier ^{:name "service"} c1-service {:over c1-foundation})
(Tier ^{:name "api"} c1-api {:over c1-service})
(Domain ^{:name "auth"} c1-d)
(Operation ^{:name "handle"} c1-op)
(declare c1-handler)
(ExecutionFunction ^{:name "validator"} c1-validator
  {:domain c1-d :tier c1-service :operation c1-op :deps [c1-handler]})
(ExecutionFunction ^{:name "handler"} c1-handler
  {:domain c1-d :tier c1-api :operation c1-op})

(deftest upward-tier-dependency-is-caught
  (testing "a service-tier function depending on an api-tier function trips the tier-boundary law"
    (let [db (a/assemble-vars [#'c1-foundation #'c1-service #'c1-api
                               #'c1-d #'c1-op #'c1-validator #'c1-handler])]
      (is (contains? (laws db) "no execution-function depends on a higher tier")))))

;; ── case 2: a facet slot must point at its axis type ──────────────────────────
;; :domain targets a Tier, not a Domain — wrong target type.

(Tier ^{:name "service"} c2-tier)
(Operation ^{:name "validate"} c2-op)
(ExecutionFunction ^{:name "bad"} c2-bad
  {:domain c2-tier :tier c2-tier :operation c2-op})

(deftest facet-slot-must-match-its-axis-type
  (testing "an execution-function whose :domain targets a Tier, not a Domain, is caught"
    (let [db (a/assemble-vars [#'c2-tier #'c2-op #'c2-bad])]
      (is (contains? (laws db) "ExecutionFunction.domain target must be a Domain")))))

;; ── case 3 (style B): at most one aspect per axis ─────────────────────────────
;; Both aspects sit on the tier axis — two tiers at once is ill-formed.

(Axis ^{:name "tier"} c3-tier-axis)
(Aspect ^{:name "service"} c3-service {:axis c3-tier-axis})
(Aspect ^{:name "api"} c3-api {:axis c3-tier-axis})
(Faceted ^{:name "confused"} c3-confused {:aspects [c3-service c3-api]})

(deftest two-aspects-on-one-axis-is-caught
  (testing "a faceted entity carrying two aspects on the same axis trips the one-per-axis law"
    (let [db (a/assemble-vars [#'c3-tier-axis #'c3-service #'c3-api #'c3-confused])]
      (is (contains? (laws db) "an entity carries at most one aspect per axis")))))
