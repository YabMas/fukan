(ns fukan.canvas.identity-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.identity :as identity]))

;; ---------------------------------------------------------------------------
;; stable-id generation
;; ---------------------------------------------------------------------------

(deftest canonical-id-generation-module
  (testing "stable-id of a Module is its name"
    (is (= "infra.server" (identity/stable-id :Module "infra.server" "infra.server")))))

(deftest canonical-id-generation-affordance
  (testing "stable-id of an Affordance is module-name/entity-name"
    (is (= "infra.server/start_server"
           (identity/stable-id :Affordance "infra.server" "start_server")))))

(deftest canonical-id-generation-state
  (testing "stable-id of a State is module-name/state/entity-name"
    (is (= "infra.server/state/running"
           (identity/stable-id :State "infra.server" "running")))))

(deftest canonical-id-generation-type
  (testing "stable-id of a Type is module-name/type/entity-name"
    (is (= "infra.server/type/ServerOpts"
           (identity/stable-id :Type "infra.server" "ServerOpts")))))

;; ---------------------------------------------------------------------------
;; resolve-id: canonical round-trips
;; ---------------------------------------------------------------------------

(deftest resolve-canonical-id-roundtrips-module
  (testing "a canonical module id resolves to itself"
    (let [db (h/with-canvas
               (h/within-module "infra.server"
                 (h/declare-affordance "start_server" :role :fukan.canvas.monolith/exposed-call)))]
      (is (= "infra.server" (identity/resolve-id db "infra.server"))))))

(deftest resolve-canonical-id-roundtrips-affordance
  (testing "a canonical affordance id resolves to itself"
    (let [db (h/with-canvas
               (h/within-module "infra.server"
                 (h/declare-affordance "start_server" :role :fukan.canvas.monolith/exposed-call)))]
      (is (= "infra.server/start_server"
             (identity/resolve-id db "infra.server/start_server"))))))

;; ---------------------------------------------------------------------------
;; resolve-id: unknown id returns nil
;; ---------------------------------------------------------------------------

(deftest unknown-id-returns-nil
  (testing "resolving an id that doesn't exist returns nil, not a throw"
    (let [db (h/with-canvas
               (h/within-module "infra.server"
                 (h/declare-affordance "start_server" :role :fukan.canvas.monolith/exposed-call)))]
      (is (nil? (identity/resolve-id db "made-up-id")))
      (is (nil? (identity/resolve-id db "infra.server/nonexistent"))))))

;; ---------------------------------------------------------------------------
;; alias: registers and resolves
;; ---------------------------------------------------------------------------

(deftest alias-registers-and-resolves
  (testing "declaring alias 'old-X' → entity X makes old-X resolve to canonical id of X"
    (let [db (h/with-canvas
               (h/within-module "infra.server"
                 (h/declare-affordance "start_server" :role :fukan.canvas.monolith/exposed-call)
                 (identity/alias "infra.server/start" "start_server")))]
      (is (= "infra.server/start_server"
             (identity/resolve-id db "infra.server/start"))
          "alias 'infra.server/start' must resolve to canonical 'infra.server/start_server'"))))

(deftest alias-multiple-old-ids-resolve-to-same-entity
  (testing "multiple aliases on the same entity all resolve to its canonical id"
    (let [db (h/with-canvas
               (h/within-module "infra.server"
                 (h/declare-affordance "start_server" :role :fukan.canvas.monolith/exposed-call)
                 (identity/alias "infra.server/start" "start_server")
                 (identity/alias "infra.server/boot" "start_server")))]
      (is (= "infra.server/start_server"
             (identity/resolve-id db "infra.server/start")))
      (is (= "infra.server/start_server"
             (identity/resolve-id db "infra.server/boot"))))))

;; ---------------------------------------------------------------------------
;; alias scoping: alias declared in module A does not affect module B
;; ---------------------------------------------------------------------------

(deftest alias-from-different-module-not-aliased
  (testing "alias declared in module A does NOT make an old id resolve to module B's entity"
    (let [db (h/with-canvas
               (h/within-module "infra.server"
                 (h/declare-affordance "start_server" :role :fukan.canvas.monolith/exposed-call)
                 ;; alias in infra.server registers old-id onto infra.server/start_server
                 (identity/alias "infra.server/start" "start_server"))
               (h/within-module "infra.model"
                 ;; infra.model also has an entity named "start_server"
                 (h/declare-affordance "start_server" :role :fukan.canvas.monolith/exposed-call)))]
      ;; "infra.server/start" should resolve to infra.server's start_server
      (is (= "infra.server/start_server"
             (identity/resolve-id db "infra.server/start")))
      ;; infra.model/start_server should have no alias for "infra.server/start"
      ;; (i.e. the old-id is bound to exactly one entity, not two)
      (let [result (identity/resolve-id db "infra.server/start")]
        (is (not= "infra.model/start_server" result))))))

;; ---------------------------------------------------------------------------
;; alias: no-op when entity name not found in enclosing module
;; ---------------------------------------------------------------------------

(deftest alias-unknown-entity-name-is-noop
  (testing "alias with a current-entity-name that doesn't exist in the module is a no-op"
    ;; Should not throw
    (let [db (h/with-canvas
               (h/within-module "infra.server"
                 (h/declare-affordance "start_server" :role :fukan.canvas.monolith/exposed-call)
                 (identity/alias "infra.server/old_ghost" "nonexistent_entity")))]
      ;; "infra.server/old_ghost" should not resolve (the alias was silently dropped)
      (is (nil? (identity/resolve-id db "infra.server/old_ghost"))))))
