(ns fukan.common.vocab.code.region
  "The `Region` element — a stratum of a codebase claimed by NAMESPACE PREFIX, arranged in a
   CONTAINMENT tree, and checked against the EXTRACTED call graph.

   Region is `Band` with two things Band does not have, both of them demanded by the first real
   consumer's own design document rather than invented here:

   CONTAINMENT (`:child`). Band's `:may-depend` is flat, so a box that is not itself a stratum —
   brian's `Access`, which holds `Identity` and `Policy` at different depths — cannot be said at
   all, and a broad edge (`Presentation` may depend on `Domain`) has to be restated against every
   one of Domain's fourteen entities. Conformance here reads the edge against the ANCESTRY of both
   ends, so the broad edge covers the narrow dependency and the box carries no edges of its own.

   INTERIORITY (`:interior`). A `:may-depend` DAG can say who may depend on a region; it cannot say
   that a region is reachable only from inside its own parent. brian draws exactly that — `Execute`,
   the database connection, dashed inside `Persistence` — and calls it the rule the drawing states
   rather than writes. `:interior` is Region's own containment kind (NOT a `contains` species — see
   the note on the relation), and the interior law denies every edge from outside the owner's
   subtree that no `:may-depend` edge INTO the interior licenses. The explicit-licence escape is what
   lets a composition root declare its way in (brian's `Boot` may depend on everything, interiors
   included) without the law needing a notion of privilege.

   Membership is DERIVED from the namespace's own name, never authored — Band's trade, kept: you
   give up putting a namespace anywhere, and get a declaration no file move can quietly falsify.
   Where prefixes nest (`brian.database.` and `brian.database.execute`), the LONGEST claim wins, so
   an interior can be carved out of the region that surrounds it without restating the surroundings.

   ⚠ Inherits Band's language leak: `Ns` is a Clojure fact sort and this file names it by full tag
   keyword, at the DATA level, through datalog injection. A project with another extractor mints no
   `Ns` and gets vacuous laws rather than failing ones. A second extractor is the trigger to lift a
   neutral code-unit sort; until then the debt sits here in the open, as it does in `band`."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure defrelation]]))

(defn ^:export read-prefix
  "A bare string in a `:prefix` vector → `RegionPrefix` clauses, so a region authors its prefixes
   as plain strings rather than as constructor calls."
  [v]
  [(list 'value v)])

(defstructure ^:value RegionPrefix
  "One namespace prefix a Region claims. A `^:value` structure because a slot holding a repeated
   LEAF has no cardinality in the kernel — a scalar slot is one or optional — so a leaf that
   repeats is modelled as a content-deduped node.

   Region mints its OWN rather than importing Band's `NsPrefix`: requiring `band` would activate
   Band's three laws alongside Region's, and Band's conformance law is another full `ns-depends`
   evaluation whose answer is already Region's to give. Standing alone costs one small duplicated
   value structure and keeps the check to one pass."
  {:value :string}
  (reader read-prefix))

;; ── interior: a containment relation that deliberately STAYS OUT of `contains` ───────────────
;; The obvious wiring is `(:sub :contains)`, so that `contains`, `contains+` and `within` pick
;; interior members up for free. It is also a 116× performance regression across the whole model,
;; and the reason is worth stating because it constrains every future species:
;;
;;   `contains` with ONE species is a single-bodied rule, and the compiler INLINES those into the
;;   stored-relation datoms they stand for, re-orienting each expansion against what the preceding
;;   clauses already bound (`cozo/query.clj`, "rule INLINING"). A second species makes it
;;   two-bodied, the inliner leaves it as a rule call, Cozo materializes it — and a materialized
;;   relation carries no key, so every hop through it degrades to a scan.
;;
;;   Measured here, on brian (904 ns / 10,069 fns / 21,477 call edges), the `ns-depends` join:
;;     :interior OUTSIDE contains (one species) ....... 2,246 ms
;;     :interior AS a contains species (two) ....... 260,652 ms
;;
;; So `:interior` is a bare relation, and Region reads its own containment through
;; `region-contains` below. The cost is that the kernel's `within`/`contains+` do not see interior
;; members; the benefit is that the hot genus every other vocab joins through stays inlinable.
;; Teaching the inliner multi-bodied rules would remove the constraint — that is the real fix, and
;; it belongs in the compiler, not in a workaround here.
(defrelation :interior
  "Membership — an interior child: contained, and HIDDEN. Nothing outside the container's own
   subtree may depend on an interior member or anything nested in one, whatever the `:may-depend`
   graph would otherwise allow. NOT a species of `contains` — see the note above.")

(s/defrelation :region-contains
  "Region ?r holds ?c, visibly (`:child`) or not (`:interior`) — Region's own containment genus.
   Two bodies, which is exactly what the note above says to avoid — but this relation is joined
   only over REGIONS (fourteen of them, twenty closure rows), never over the code graph, so
   materializing it costs nothing measurable.

   ⚠ The `(is ?r ::Region)` on the `:child` body is what makes that sentence TRUE. `:child` is not
   Region's kind — it is the kernel's, the one every structure's containment is stored under — so a
   bare `(child ?r ?c)` is every namespace→function edge in the codebase, and `reg-within` was
   11,044 rows on brian rather than twenty. Nothing looked wrong: every call site happened to bind
   a Region, so every ANSWER was right. It was `declared-dep` crossing the relation with itself
   (122M pairs) that kept both code-graph laws from finishing. `:interior` needs no pin — that kind
   IS Region's own."
  [?r ?c]
  [(is ?r ::Region) (child ?r ?c)]
  [(interior ?r ?c)])

;; ── membership, derived from the namespace name, longest claim winning ───────
(s/defrelation :in-region
  "Code namespace ?ns belongs to Region ?r — ?ns's name starts with one of ?r's declared prefixes,
   and NO region claims it by a longer prefix. The tie-break is what lets an interior be carved out
   of the region that surrounds it: once `prep-2-execute` lands, `brian.database.execute` is claimed
   by `Execute` even though `Persistence` claims `brian.database.` too. Specificity is compared on
   the PREFIXES themselves — a longer claim is one that extends the shorter — so no string-length
   primitive is needed.

   The alternative is a disjointness RULE on the declaration, with a region carving an interior out
   of its own address space by enumerating the ~43 siblings it keeps. That was tried and reverted:
   it re-introduces exactly the drift derived membership exists to prevent, since every new
   namespace under the parent needs the declaration edited to stay claimed.

   DERIVED, and read as a plain rule call — it is a filtered generator under a negation, so the
   compiler rightly does not inline it. Membership was for a while GROUNDED as `:region/of` datoms
   by a Clojure-side step before `check`, on the belief that a derived membership was what kept the
   code-graph laws from finishing. It was not (see `region-contains`): measured on brian with that
   fixed, each law costs ~2.6 s grounded and ~3.0 s derived, the same rows either way. The 0.4 s
   buys a model that answers `check` with no step for anyone to forget, and a vocabulary that
   never writes to the substrate.

   The negation is joined on the two ENTITIES (?ns, ?r) and rebinds ?n/?p1/?p2 from positive atoms
   INSIDE it: a `starts_with` port filters, it cannot generate, so a head var reaching the negation
   only through a predicate is not range-restricted and Cozo rejects the rule outright. `?p1` is
   re-derived and re-matched rather than passed, so a region with several prefixes is judged on the
   one that actually claims ?ns."
  [?ns ?r]
  [(is ?r ::Region) (prefix ?r ?px) [?px :val/value ?p]
   (is ?ns :fukan.common.extraction.clojure.module/Ns) (named ?ns ?n)
   [(clojure.string/starts-with? ?n ?p)]
   (not-join [?ns ?r]
     (named ?ns ?n1)
     (prefix ?r ?px1) [?px1 :val/value ?p1]
     [(clojure.string/starts-with? ?n1 ?p1)]
     (is ?r2 ::Region) (prefix ?r2 ?px2) [?px2 :val/value ?p2]
     [(clojure.string/starts-with? ?n1 ?p2)]
     [(clojure.string/starts-with? ?p2 ?p1)]
     [(not= ?p2 ?p1)])])

(s/defrelation :reg-within
  "Region ?r is ?anc itself, or nested anywhere inside it. Two bodies rather than a recursion:
   `region-contains+` is the compiler's own closure, minted for the relation and injected where
   referenced, so both containment kinds are rolled up without a hand-written recursion."
  [?r ?anc]
  [(is ?r ::Region) (is ?anc ::Region) [(= ?r ?anc)]]
  [(region-contains+ ?anc ?r)])

(defstructure Region
  "A stratum of the codebase: the namespaces under its `:prefix`es, the regions nested inside it,
   and the regions it is allowed to depend on. The laws are the SLOT SEMANTICS of `:may-depend` and
   `:interior` — without them both slots are prose.

   `:prefix` is zero-or-more, unlike Band's one-or-more: a region that is a BOX rather than a
   stratum claims no namespaces of its own and exists only to hold its children and carry their
   shared name. Every law is naturally vacuous in a project that declares no Regions."
  {:prefix     [:* RegionPrefix]  ; the namespace prefixes this region claims (a box claims none)
   :child      [:* Region]    ; nested regions, visible from outside
   :interior   [:* Region]    ; nested regions, hidden from outside this region's subtree
   :may-depend [:* Region]}   ; the regions it is allowed to depend on (declared intent)

  (law "every cross-region namespace dependency follows a declared :may-depend edge"
    ;; The offender is the whole EDGE plus the two regions it crosses — Band's reasoning, kept: a
    ;; law naming only the caller says a namespace is in the wrong without saying which of its
    ;; dependencies is the wrong one.
    ;;
    ;; `declared-dep` reads the edge against the ANCESTRY of both ends, which is what containment
    ;; buys: `Presentation :may-depend [Domain]` covers a call into `Domain/Organization` with no
    ;; per-entity restatement, and a box region needs no edges at all.
    {:scope :global
     :offenders [?from ?to ?from-region ?to-region]
     :rules [[(declared-dep ?fr ?tr)
              (reg-within ?fr ?fa) (reg-within ?tr ?ta) (may-depend ?fa ?ta)]]
     :where [(ns-depends ?from ?to)
             (in-region ?from ?from-region) (in-region ?to ?to-region)
             [(not= ?from-region ?to-region)]
             (not (declared-dep ?from-region ?to-region))]})

  (law "nothing outside an interior region's owner depends on it"
    ;; The teeth `:may-depend` alone cannot give. An interior is reachable from inside its owner's
    ;; subtree and nowhere else — so this fires even where the conformance law is satisfied, which
    ;; is the point: `Domain :may-depend [Persistence]` is a legitimate edge that must still not
    ;; reach `Execute` nested inside Persistence.
    ;;
    ;; An explicit `:may-depend` edge LICENSES the crossing, and that escape is deliberate: a
    ;; composition root is the one caller that legitimately reaches interiors, and declaring the
    ;; edge is how it says so. Without the escape the law would need a privileged region baked in.
    ;;
    ;; EXPLICIT means the edge lands ON the interior or inside it — `licensed`, not the conformance
    ;; law's `declared-dep`. That one reads the TARGET against its ancestry, which is right for
    ;; conformance and exactly wrong here: Execute's ancestry includes Persistence, so
    ;; `Domain :may-depend [Persistence]` licensed the very reach the first paragraph forbids, and
    ;; the law reported nothing. The CALLER's side still reads ancestry — a licence held by a box
    ;; covers what the box holds.
    ;;
    ;; Only the TARGET need be claimed. An unclaimed caller is outside the owner's subtree by
    ;; construction and holds no licence, so both of its tests are negations over its membership
    ;; rather than a positive `in-region` — which is why the row carries no `?from-region`: most
    ;; offenders in a partial model have none. On brian's three-region model that is the whole
    ;; finding: 200 namespaces reach Execute, 197 by call and 3 only by a `defmethod` on a
    ;; multimethod Execute owns — the supply half of `ns-depends`, which no call edge carries.
    {:scope :global
     :offenders [?from ?to ?interior]
     :rules [[(licensed ?fr ?i)
              (reg-within ?fr ?fa) (may-depend ?fa ?t) (reg-within ?t ?i)]]
     :where [(ns-depends ?from ?to)
             (in-region ?to ?to-region)
             (interior ?owner ?interior) (reg-within ?to-region ?interior)
             (not-join [?from ?owner] (in-region ?from ?fr) (reg-within ?fr ?owner))
             (not-join [?from ?interior] (in-region ?from ?fr) (licensed ?fr ?interior))]})

  (law "every namespace belongs to a region, once any region is declared"
    ;; Band's reasoning verbatim, and it matters more here: `in-region` is derived, so a namespace
    ;; under no prefix is not an offender anywhere — it is INVISIBLE: conformance needs both ends
    ;; claimed to fire, and the interior law needs the target. Gated on a Region existing, because a project declaring none is
    ;; asserting nothing about coverage, while one declaring any is asserting a partition.
    {:scope :global
     :offenders [?ns]
     :rules [[(some-region ?r) (is ?r ::Region)]]
     :where [(some-region ?_r)
             (is ?ns :fukan.common.extraction.clojure.module/Ns)
             (not-join [?ns] (in-region ?ns ?r))]})

  (law "every region with a prefix claims at least one namespace"
    ;; A prefix that matches nothing — a typo, or a package that moved out from under the claim.
    ;; The code-graph laws read `in-region`, so a region claiming nothing reports NO violations,
    ;; and a silent pass is the one outcome this vocabulary must never produce. A region whose every
    ;; namespace is out-claimed by a longer prefix lands here too, which is the same finding.
    ;; Gated on a namespace existing, so a design-only build stays vacuous rather than reporting
    ;; every region for the absence of code that was never extracted.
    {:scope :global
     :offenders [?r]
     :rules [[(some-ns ?ns) (is ?ns :fukan.common.extraction.clojure.module/Ns)]]
     :where [(some-ns ?_ns)
             (is ?r ::Region) (prefix ?r ?_px)
             (not-join [?r] (in-region ?_ns2 ?r))]})

  (law "the :may-depend graph is acyclic — no region transitively depends on itself"
    {:offenders [?region]
     :rules [[(reg-reaches ?s ?t) (may-depend ?s ?t)]
             [(reg-reaches ?s ?t) (may-depend ?s ?mid) (reg-reaches ?mid ?t)]]
     :where [(reg-reaches ?region ?region)]}))
