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
(Tier foundation)
(Tier service {:over foundation})
(Tier api     {:over service})

;; Domains
(Domain auth)
(Domain users)

;; Operations
(Operation validate)
(Operation fetch)
(Operation handle)

;; Functions — every dep points downward or sideways across tiers
(ExecutionFunction validate-token
  {:domain auth :tier service :operation validate})
(ExecutionFunction get-user
  {:domain users :tier service :operation fetch
   :deps   [validate-token]})
(ExecutionFunction login-endpoint
  {:domain auth :tier api :operation handle
   :deps   [validate-token get-user]})

;; ── style B: the same identity carried as an open aspect set ──────────────────

(Axis ^{:name "domain"} domain-axis)
(Axis ^{:name "tier"}   tier-axis)

(Aspect ^{:name "auth"}    auth-aspect    {:axis domain-axis})
(Aspect ^{:name "service"} service-aspect {:axis tier-axis})

(Faceted token-validator {:aspects [auth-aspect service-aspect]})

(defn build [] (a/assemble ['demos.atlas.model.auth]))
