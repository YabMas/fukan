(ns demos.atlas.model.auth
  "A small auth/users subsystem modelled with the Atlas vocabulary — the same
   example domain the Atlas write-up registers (`validate-token`, `get-user`, a
   login endpoint), well-formed under every invariant.

   Tiers form the chain foundation ← service ← api. All dependencies point
   downward or sideways: the api `login-endpoint` calls the service `get-user`
   and `validate-token`; `get-user` calls the same-tier `validate-token`. Nothing
   depends upward, so the tier-boundary law is satisfied.

   The style-B faceting half is exercised by `token-validator`, whose two aspects
   sit on DIFFERENT axes (domain, tier) — well-formed; the at-most-one-per-axis
   law has nothing to fire on."
  (:require [fukan.canvas.core.assemble :as a]
            [demos.atlas.vocab.core
             :refer [Domain Operation Tier ExecutionFunction
                     Axis Aspect Faceted]]))

;; ── style A: the execution-function graph ─────────────────────────────────────

;; Tiers — foundation ← service ← api (each :over the one beneath it)
(def foundation (Tier "foundation"))
(def service    (Tier "service" (over foundation)))
(def api        (Tier "api"     (over service)))

;; Domains
(def auth  (Domain "auth"))
(def users (Domain "users"))

;; Operations
(def validate (Operation "validate"))
(def fetch    (Operation "fetch"))
(def handle   (Operation "handle"))

;; Functions — every dep points downward or sideways across tiers
(def validate-token (ExecutionFunction "validate-token"
                      (domain auth) (tier service) (operation validate)))
(def get-user       (ExecutionFunction "get-user"
                      (domain users) (tier service) (operation fetch)
                      (deps validate-token)))
(def login-endpoint (ExecutionFunction "login-endpoint"
                      (domain auth) (tier api) (operation handle)
                      (deps validate-token get-user)))

;; ── style B: the same identity carried as an open aspect set ──────────────────

(def domain-axis (Axis "domain"))
(def tier-axis   (Axis "tier"))

(def auth-aspect    (Aspect "auth"    (axis domain-axis)))
(def service-aspect (Aspect "service" (axis tier-axis)))

(def token-validator (Faceted "token-validator"
                       (aspects auth-aspect service-aspect)))

(defn build [] (a/assemble ['demos.atlas.model.auth]))
