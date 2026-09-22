(ns fukan.common.vocab.code.region
  "The `Region` element — a grouping of namespaces claimed by NAME PREFIX, together with the regions
   its namespaces may depend on, checked against the EXTRACTED namespace dependency graph.

   The sibling of `Subsystem`, and the difference is the evidence. A Subsystem clusters authored
   Modules and checks its `:may-depend` graph against `module-depends`, built from authored
   `:delegates` — so it says nothing until code is modelled operation by operation. A Region claims
   namespaces by prefix and checks the same shape of declaration against `ns-depends`, which
   extraction produces the moment it runs, so an existing codebase is declarable without authoring
   a single operation.

   A Region asserts REACH, and ranks nothing. `:may-depend` says which regions a region's namespaces
   may depend on; two regions neither of which reaches the other are simply unrelated. Acyclicity is
   a separate claim, kept on its own merits: acyclic reach is a partial order, so a codebase that
   conforms has no dependency cycle between regions. A partial order is not a layering — nothing
   gives a region a depth.

   MEMBERSHIP IS A PARTITION, derived from the namespace's name and never authored. A prefix claims
   the namespace it names and every namespace below it at a `.` boundary — `app.server` claims
   `app.server.http` and not `app.server-components` — and a prefix already ending in `.` claims only
   what lies below it. Where two regions' prefixes both claim a namespace, the LONGER prefix wins,
   which is what lets a region be carved out of another's subtree (`app.db.execute` out of
   `app.db`). Two claims on one namespace can only tie by being the same string, and a prefix
   claimed by two regions is a law of its own; with coverage on top, every namespace is in exactly
   one region, so each undeclared dependency is one finding.

   REGIONS NEST, and nesting has two kinds. `:child` holds a region visible from outside, so a
   declaration can name a BOX that is not itself a stratum — a pair of regions at different depths
   that share a name and nothing else — and an outward edge onto the box covers what it holds
   without restating it against each part. `:interior` holds one that is NOT visible.

   CONTAINMENT IMPLIES REACH, DOWNWARD AND ONLY DOWNWARD. A region reaches what it contains without
   declaring an edge onto it: a whole that may not touch its own parts describes nothing anybody
   builds, and the alternative was a `:may-depend` line every author has to write whose only
   purpose is to not be a bug (55 findings on brian, all of Persistence reaching the connection it
   owns). The converse never holds — a part reaching its container, or a sibling, is an ordinary
   crossing and is declared or reported. A `:may-depend` edge that containment already licenses is
   a law of its own below, so the line can be deleted once rather than kept forever as a no-op.

   That leaves three rungs, and an author picks by what they want checked:

     `:child`              contained; the owner reaches it, an edge onto the box covers it
     `:child` + `:sealed`  contained; every inbound crossing declared onto it, the owner's included
     `:interior`           contained and hidden; the owner's subtree, plus edges landing on it

   The middle rung is the one to reach for when the owner's reach should be STATED rather than
   implied — the seal is what makes the owner declare an edge, and containment stops that
   declaration from being redundant.

   SEALING is what `:interior` turns out to be a case of. A region says `:sealed` of itself to admit
   an inbound crossing only from a caller something licensed; a region held as another's `:interior`
   is sealed too, and additionally licenses its owner's subtree. One law covers both. That is the
   rule a drawing states by dashing a node and an arrow-graph cannot state at all — a database
   connection sitting inside the region that owns it, reachable from its owner and from the
   composition root that declares its way in, and from nowhere else.

   A seal is independent of `:may-depend`, not a shorthand for it. Conformance asks whether an edge
   BETWEEN two regions was declared, and needs both ends claimed to ask it; a seal asks what may
   reach INTO a region, and needs only the target claimed. That asymmetry is why a seal says
   something true about a codebase that is mostly not modelled yet, and why `Domain :may-depend
   [Persistence]` — a perfectly good declared edge — still does not reach what Persistence seals.

   ⚠ THIS TIER REACHES A LANGUAGE HERE (as `Band` does). `Ns` is a Clojure fact sort, named by FULL
   TAG KEYWORD — the documented spelling for a namespace deliberately not required, so the coupling
   is at the data level and not the compile level.

   WHAT THAT COSTS A PROJECT THAT IS NOT CLOJURE, measured rather than assumed, because this said
   the wrong thing until 2026-09-22 (it promised vacuous laws): with the Clojure extraction tier
   LOADED and no code extracted, these laws are vacuous and green — that is the design-only build,
   and the distinction that makes it work is that the SORT is registered, not that any namespace
   exists. Without that tier at all, four of them are UNDECIDABLE: `(is ?ns Ns)` cannot compile
   against a sort nobody registered, which drops `region-claims`, and `ns-depends` is the
   extractor's rule and is simply absent. That is exit 2, and it is the honest answer rather than a
   defect — a law about namespaces cannot be decided where namespaces are not a thing, and a green
   verdict there would be a claim nobody checked. A project with a different extractor should
   expect this vocabulary to report as unevaluated, not as satisfied. The fix is the
   extractor-neutral code-unit sort, and a second extractor is its trigger."
  (:require [clojure.string :as str]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.cozo.query :as cq]))

(defn ^:export read-prefix
  "A bare string in a `:prefix` vector → `NsPrefix` clauses, so a grouping authors its prefixes as
   plain strings rather than as constructor calls."
  [v]
  [(list 'value v)])

(defstructure ^:value NsPrefix
  "One namespace prefix a grouping claims. A `^:value` structure because a slot holding a repeated
   LEAF has no cardinality in the kernel — a scalar slot is one or optional — so a leaf that repeats
   is modelled as a content-deduped node. Content identity is also what makes a prefix claimed
   twice a join: the same string authored on two regions is ONE node."
  {:value :string}
  (reader read-prefix))

;; ── the two predicates membership needs ──────────────────────────────────────
;; Each is a Clojure function AND a CozoScript lowering of it, and the function is the
;; specification: the laws only ever run the lowering, so the two must agree, which the tests
;; check over real namespace names rather than trusting a string template.

(defn segment-prefix?
  "True when prefix `p` claims the namespace named `n`: `n` is `p`, or lies below it at a `.`
   boundary. A `p` ending in `.` already spells its boundary and claims only what lies below."
  [n p]
  (if (str/ends-with? p ".")
    (str/starts-with? n p)
    (or (= n p) (str/starts-with? n (str p ".")))))

(defn longer?
  "True when prefix `a` is longer than prefix `b`. Two prefixes that claim one namespace are both
   prefixes of its name, so the longer is the more specific claim."
  [a b]
  (> (count a) (count b)))

;; Registered at load, which is when a vocabulary's ports are expected to arrive: a registration
;; retires any compiled rule index built before it. Each argument arrives already compiled to a
;; CozoScript term, so a builder only splices.
(cq/register-predicate-port!
  `segment-prefix?
  (fn [[n p]]
    [(str "if(ends_with(" p ", '.'), starts_with(" n ", " p "), "
          "or(" n " == " p ", starts_with(" n ", concat(" p ", '.'))))")
     #{}])
  {})

(cq/register-predicate-port!
  `longer?
  (fn [[a b]] [(str "length(" a ") > length(" b ")") #{}])
  {})

(s/defrelation :interior
  "Membership — an interior child: contained, and HIDDEN. Nothing outside the container's own
   subtree may depend on an interior member or on anything nested in one, whatever the
   `:may-depend` graph would otherwise allow.

   NOT a species of `contains`, and the omission is deliberate rather than an oversight. The
   obvious wiring is `(:sub :contains)`, so the genus and its closure pick interior members up for
   free. It also makes `contains` two-bodied, and `contains` is the relation every other vocabulary
   joins through: a multi-bodied rule is not inlined, Cozo materializes it, and a materialized
   relation carries no key, so every hop through it degrades to a scan. Measured on brian, a
   two-bodied `contains` in the middle of a join did not finish inside 100 s. Region therefore
   reads its own containment through `region-contains` below, and pays for it only over regions.")

(s/defrelation :region-contains
  "Region ?r holds ?c, visibly (`:child`) or not (`:interior`) — Region's own containment genus.
   Two bodies, which is what the note on `:interior` says to avoid — but this one is joined only
   over REGIONS, never over the code graph, so materializing it costs nothing measurable.

   ⚠ The `(is ?r ::Region)` on the `:child` body is what makes that sentence true. `:child` is the
   KERNEL's containment kind, not Region's — every structure stores containment under it — so a
   bare `(child ?r ?c)` is every namespace→function edge in the codebase. Unpinned it made
   `reg-within` 11,044 rows on brian rather than twenty, and `declared-dep` crossing that relation
   with itself is 122M pairs, which is what kept both code-graph laws from finishing. No ANSWER was
   ever wrong — every call site happened to bind a Region — which is exactly how it stayed hidden.
   `:interior` needs no pin: that kind is Region's own."
  [?r ?c]
  [(is ?r ::Region) (child ?r ?c)]
  [(interior ?r ?c)])

(s/defrelation :reg-within
  "Region ?r is ?anc itself, or nested anywhere inside it. Two bodies rather than a recursion:
   `region-contains+` is the compiler's own closure, minted for the relation and injected where
   referenced, so both nesting kinds are rolled up without a hand-written transitive rule."
  [?r ?anc]
  [(is ?r ::Region) (is ?anc ::Region) [(= ?r ?anc)]]
  [(region-contains+ ?anc ?r)])

(s/defrelation :sealed-region
  "Region ?s admits an inbound crossing only from a licensed caller. Two ways to become one, and
   they are the same property arrived at differently: a region says `:sealed` of ITSELF, or a
   region is held as another's `:interior`. An interior is therefore not a second mechanism — it is
   a seal that additionally licenses its owner's subtree, which is what `seal-licensed` says."
  [?s]
  [(is ?s ::Region) [?s :val/sealed true]]
  [(interior ?_owner ?s)])

(s/defrelation :seal-licensed
  "Region ?fr may cross into sealed region ?s. Three licences, and each is a declaration someone
   made rather than a privilege the law grants:

     a DECLARED EDGE landing on ?s or inside it — the composition root's way in, and the reason
     this law needs no notion of a privileged region;
     the OWNER's subtree, when ?s is an interior — what makes an interior reachable from the region
     that holds it;
     ?s's own subtree — a region is not sealed against itself.

   The first is deliberately NARROW: the edge must land on the seal or inside it. Resolving it
   through the seal's ancestry instead lets an edge onto the OWNER license reaching the owner's
   interior, which is the negation of what the slot means — measured, that read 0 offenders on
   brian where the answer is 201. Joined only over regions, so three bodies cost nothing."
  [?fr ?s]
  [(reg-within ?fr ?fa) (may-depend ?fa ?t) (reg-within ?t ?s)]
  [(interior ?owner ?s) (reg-within ?fr ?owner)]
  [(reg-within ?fr ?s)])

;; ── membership ───────────────────────────────────────────────────────────────
;; Both claim relations filter on a variable their head hides, so the compiler keeps them as rules
;; and pays each once (`inline-index`); `in-region` filters nothing and folds into its call sites.

(s/defrelation :region-claims
  "Region ?r claims namespace ?ns through its prefix ?p — ?ns is ?p or lies below it at a `.`
   boundary. A namespace may be claimed by several regions; `in-region` settles which it is in."
  [?ns ?r ?p]
  [(is ?r ::Region) (prefix ?r ?px) [?px :val/value ?p]
   (is ?ns :fukan.common.extraction.clojure.module/Ns) (named ?ns ?n)
   [(fukan.common.vocab.code.region/segment-prefix? ?n ?p)]])

(s/defrelation :region-claim-outranked
  "The claim prefix ?p makes on namespace ?ns is outranked: a longer prefix claims ?ns too."
  [?ns ?p]
  [(region-claims ?ns ?_r ?p) (region-claims ?ns ?_r2 ?p2)
   [(fukan.common.vocab.code.region/longer? ?p2 ?p)]])

(s/defrelation :in-region
  "Namespace ?ns belongs to Region ?r — the region whose claim on it no longer prefix outranks."
  [?ns ?r]
  [(region-claims ?ns ?r ?p) (not (region-claim-outranked ?ns ?p))])

(defstructure Region
  "A region of the codebase: the namespaces its `:prefix`es claim, the regions nested inside it,
   and the regions those namespaces may depend on. The laws are the slot semantics of `:prefix`,
   `:may-depend` and `:interior` — without them the declaration is prose — and every one is vacuous
   in a project that declares no Region.

   `:prefix` is zero-or-more so a BOX may claim nothing and exist only to hold what it nests."
  {:sealed     [:? :boolean] ; admits an inbound crossing only from a licensed caller
   :prefix     [:* NsPrefix]  ; the namespace prefixes this region claims (a box claims none)
   :child      [:* Region]    ; nested regions, visible from outside
   :interior   [:* Region]    ; nested regions, hidden from outside this region's subtree
   :may-depend [:* Region]}   ; the regions its namespaces may depend on (declared intent)

  (law "every cross-region namespace dependency follows a declared :may-depend edge"
    ;; The offender is the EDGE plus the two regions it crosses, so a finding says which dependency
    ;; to move, not only which namespace is at fault. Membership being a partition is what makes a
    ;; row an edge: each undeclared dependency is exactly one row, so a count of rows is a count of
    ;; the dependencies a migration has to move.
    ;;
    ;; `declared-dep` reads the edge against the ANCESTRY of both ends, which is what nesting buys:
    ;; one edge onto a box covers a dependency on any part of it, and a box needs no edges of its
    ;; own. It is also the join that must stay small — see the pin note on `region-contains`.
    ;;
    ;; The second body is CONTAINMENT, and it is the sentence the seal law was already saying: a
    ;; region reaches what it holds. Without it the two laws disagree about what ownership means —
    ;; `seal-licensed` licenses the owner's subtree into its interior while this law denies the
    ;; same edge — and the disagreement is paid by the author, in a `:may-depend` line whose only
    ;; job is to silence it. It licenses the CONTAINER only: `?fr` must hold `?tr`, so a part
    ;; reaching its container, or a sibling, is an ordinary crossing and stays reported. A seal
    ;; overrides it, which is what makes `:child` + `:sealed` the way to demand a stated edge.
    {:scope :global
     :offenders [?from ?to ?from-region ?to-region]
     :rules [[(declared-dep ?fr ?tr)
              (reg-within ?fr ?fa) (reg-within ?tr ?ta) (may-depend ?fa ?ta)]
             [(declared-dep ?fr ?tr) (region-contains+ ?fr ?tr)]]
     :where [(ns-depends ?from ?to)
             (in-region ?from ?from-region) (in-region ?to ?to-region)
             [(not= ?from-region ?to-region)]
             (not (declared-dep ?from-region ?to-region))]})

  (law "no :may-depend edge names a region the same declaration already contains"
    ;; The corollary of the containment body above, and the reason it can be landed safely. The
    ;; edge is already licensed, so the line does nothing — and a line that does nothing is
    ;; indistinguishable from a load-bearing one to the next reader.
    ;;
    ;; It exists because containment ARRIVED LATE. Every canvas authored while the two laws
    ;; disagreed wrote this edge to silence the disagreement; without a law saying so, the
    ;; workaround outlives the bug in every file that needed it, and nothing will ever find it
    ;; again. One finding, one deletion, and it can never fire on a model authored after today
    ;; except by a genuine mistake.
    ;;
    ;; Narrow on purpose: only a container naming what it holds. A region naming a sibling, or
    ;; naming its own container, is an ordinary declared edge and is not reported here.
    ;;
    ;; AND ONLY WHEN THE EDGE DOES NO WORK FOR A SEAL EITHER, which is the whole difference
    ;; between the middle rung of the ladder and the bottom one. A `:child` + `:sealed` member
    ;; admits a crossing only from a caller something licensed, and for its own container that
    ;; licence is this very edge — delete it and every owner→member crossing becomes a breach. An
    ;; `:interior` member seals too, but licenses its owner's subtree by being an interior, so
    ;; there the edge really is saying nothing twice over. `licensed-anyway` is that distinction
    ;; and nothing more: the two licences `seal-licensed` grants without an edge.
    {:offenders [?r ?t]
     :rules [[(licensed-anyway ?r ?s) (interior ?owner ?s) (reg-within ?r ?owner)]
             [(licensed-anyway ?r ?s) (reg-within ?r ?s)]
             ;; containment rides INSIDE the rule: `?r` reaches this body only through the
             ;; negation otherwise, and a symbol occurring solely in a negated position is not
             ;; range-restricted — Cozo rejects the rule head.
             [(licences-a-seal ?r ?t)
              (region-contains+ ?r ?t) (sealed-region ?s) (reg-within ?t ?s)
              (not (licensed-anyway ?r ?s))]]
     :where [(may-depend ?r ?t) (region-contains+ ?r ?t)
             (not (licences-a-seal ?r ?t))]})

  (law "nothing crosses into a sealed region without a licence"
    ;; The teeth `:may-depend` alone cannot give. Conformance asks whether an edge between two
    ;; regions was declared; a seal asks whether anything at all may reach INTO a region, and
    ;; answers no unless something licensed it. The two are independent: `Domain :may-depend
    ;; [Persistence]` is a perfectly good declared edge that must still not reach the connection
    ;; sealed inside Persistence.
    ;;
    ;; ONLY THE TARGET NEED BE CLAIMED, and that asymmetry is what makes this law worth having
    ;; before a codebase is fully declared. A crossing into a seal is a fact about what was
    ;; REACHED, so a caller in no region at all is still an offender — where the conformance law,
    ;; needing both ends claimed, is silent about every namespace not yet modelled. In a model
    ;; declaring three of fourteen regions this is the law that still says something true about
    ;; the whole codebase.
    ;;
    ;; INNERMOST ATTRIBUTION. Seals nest, so one edge can breach several — on brian, 148 crossings
    ;; breach the connection's seal and the surrounding region's alike. Each is a true sentence and
    ;; together they are a worklist that double-counts, so an edge is reported against the tightest
    ;; seal it breaches and suppressed against the ones outside it. `breach` is a rule precisely so
    ;; the suppression can ask the same question of an inner seal rather than restate it; it
    ;; filters on a variable its head hides, so the compiler keeps it as a rule and pays it once.
    ;; Suppression is by BREACH, never by mere nesting: a caller licensed onto the outer region but
    ;; not the inner one still breaches the inner seal, and an outer seal it does not breach was
    ;; never going to report it.
    {:scope :global
     :offenders [?from ?to ?seal]
     :rules [[(breach ?from ?to ?tr ?s)
              ;; the EDGE rides inside the rule rather than being joined to it outside. `?from`
              ;; reaches the body only through a negation otherwise, and a symbol occurring solely
              ;; in a negated position is not range-restricted — Cozo rejects the rule head.
              (ns-depends ?from ?to) (in-region ?to ?tr)
              (sealed-region ?s) (reg-within ?tr ?s)
              (not-join [?from ?s] (in-region ?from ?fr) (seal-licensed ?fr ?s))]]
     :where [(breach ?from ?to ?to-region ?seal)
             (not-join [?from ?to ?to-region ?seal]
               (breach ?from ?to ?to-region ?inner)
               (reg-within ?inner ?seal) [(not= ?inner ?seal)])]})

  (law "every namespace belongs to a region, once any region is declared"
    ;; Without it the declaration is opt-in: a namespace no prefix claims is in no region, the
    ;; cross-region law needs both ends in one, and so an unclaimed package depends on anything and
    ;; is depended on by anything while the model stays green. GATED on a Region existing, because a
    ;; project that declares none asserts nothing about coverage.
    {:scope :global
     :offenders [?ns]
     :rules [[(some-region ?r) (is ?r ::Region)]]
     :where [(some-region ?_r)
             (is ?ns :fukan.common.extraction.clojure.module/Ns)
             (not-join [?ns] (in-region ?ns ?r))]})

  (law "a namespace prefix is claimed by at most one region"
    ;; The half of the partition longest-wins cannot settle: two claims on one namespace tie only
    ;; when they are the same string, no rule can prefer either region, and the namespace would
    ;; sit in both. Ordered by name so one conflict is one finding.
    {:scope :global
     :offenders [?r1 ?r2 ?p]
     :where [(is ?r1 ::Region) (prefix ?r1 ?px) (prefix ?r2 ?px) (is ?r2 ::Region)
             (named ?r1 ?n1) (named ?r2 ?n2) [(< ?n1 ?n2)]
             [?px :val/value ?p]]})

  (law "every region with a prefix claims at least one namespace"
    ;; A prefix matching nothing is a typo, or a package that moved out from under the claim while
    ;; the declaration stayed put. Neither shows up anywhere else: membership is derived, so an
    ;; empty region simply never appears on either side of any edge, and every other law stays
    ;; green. Gated on a namespace existing, so a design-only build reports nothing rather than
    ;; reporting every region for the absence of code that was never extracted.
    {:scope :global
     :offenders [?r]
     :rules [[(some-ns ?ns) (is ?ns :fukan.common.extraction.clojure.module/Ns)]]
     :where [(some-ns ?_ns)
             (is ?r ::Region) (prefix ?r ?_px)
             (not-join [?r] (in-region ?ns2 ?r))]})

  (law "the :may-depend graph is acyclic — no region can reach itself"
    {:offenders [?region]
     :rules [[(region-reaches ?s ?t) (may-depend ?s ?t)]
             [(region-reaches ?s ?t) (may-depend ?s ?mid) (region-reaches ?mid ?t)]]
     :where [(region-reaches ?region ?region)]}))
