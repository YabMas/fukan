(ns brian.regions
  "brian's REGION MODEL, as an executable declaration — transcribed from brian's own
   `docs/design/regions.md`, stated where a law can read it instead of where only a person can.

   This lives in fukan, NOT in the brian repository, and deliberately: brian's tree is mid-migration
   and the declaration is a moving target, so it is kept beside the vocabulary that checks it until
   the shape settles. Nothing here is committed to brian.

   DELIBERATELY PARTIAL. The design names fourteen regions; three are declared here — the two under
   active work plus the interior one of them owns. The rest are left undeclared on purpose: a region
   nobody is working on is a prefix table nobody is maintaining, and a stale claim is worse than an
   absent one because it reports confidently. `Libraries`, `External`, `Access`/`Identity`/`Policy`,
   `Presentation`, `Transport`/`Http`/`Live`/`Scheduled` and `Boot` go in when work reaches them.

   WHAT A PARTIAL MODEL DOES TO EACH LAW, since it changes what two of them mean:

     interior          — FULLY MEANINGFUL. Fires for any namespace anywhere reaching `Execute`,
                         because the offender needs only the TARGET to be in a declared region.
                         This is P1 — `Persistence owns the connection, nothing else may hold one` —
                         and it is the whole reason this model exists yet.
     cross-region      — MEANINGFUL, NARROWLY. Needs BOTH ends declared, so it checks exactly one
                         edge pair today: Domain↔Persistence. That is the Phase-1 direction, so the
                         narrowness costs nothing right now.
     coverage          — NOT A FINDING. It asserts a PARTITION, and a partial model asserts no such
                         thing; its offender count is the un-modelled remainder by construction.
                         Read it as a backlog number, and gate the law when the model goes whole.
     acyclicity        — MEANINGFUL, trivially: three regions, one edge."
  (:require [fukan.common.vocab.code.region :refer [Region]]))

(declare Execute Persistence)

(Region Execute
  "The connection itself — brian's dashed node, interior to Persistence.

   BOTH addresses are claimed, on purpose. The `prep-2-execute` layer of the Phase-1 stack renames
   `brian.server-components.database` to `brian.database.execute`; naming both means this model reads
   the tree correctly on either side of that rename, and — because Execute is Persistence's interior
   — a namespace still reaching the OLD address after the rename is REPORTED by the interior law
   rather than discovered later as a compile failure. The post-rename address nests inside
   Persistence's own prefix, which is what the longest-claim tie-break in `in-region` is for."
  {:prefix ["brian.server-components.database" "brian.database.execute"]
   :may-depend []})

(Region Persistence
  "What the system must remember, and views derived over it. Owns the connection as its INTERIOR:
   nothing outside this subtree may reach Execute, whatever edges the table grants elsewhere.

   SEALED, which is Phase 1 stated where a law can read it: `Transport has no arrow to Persistence
   or External — both are reached through Domain, and that single absence is the whole of Phase 1`.
   Sealing needs only the TARGET claimed, so every caller is checked — including the ten regions
   this model does not declare yet, which is exactly where Transport and Presentation still sit.
   Domain's `:may-depend` edge is its licence; nothing else has one.

   No `:may-depend` on Domain, and that absence is the assertion — Persistence returns rows, Domain
   parses them into values (contract P2). An edge the other way is the law firing, not a gap."
  {:prefix     ["brian.database."]
   :sealed     true
   :interior   [Execute]
   :may-depend [Execute]})

(Region Domain
  "What brian means, and the actions over it. The design decomposes this into fourteen entity
   regions; the tree does not carry that split yet, so it is declared flat and claims only
   `brian.model.` — the packages that are unambiguously Domain today. The entity split is the
   obvious next expansion: each becomes a `:child` with its own prefixes and its own place in the
   order, and containment means this region's outward edges keep covering them without restatement."
  {:prefix     ["brian.model."]
   :may-depend [Persistence]})
