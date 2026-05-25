# Sprint 2b — Emergence vs. Translation Comparison

**Date:** 2026-05-25
**Synthesist role:** Stage 3 (comparative synthesis)
**Experiments compared:**
- Sprint 2a — Translation experiment: three sessions porting real Allium specs to canvas (fukan-explore-{1,2,3})
- Sprint 2b — Emergence experiment: three sessions extracting patterns from raw corpus data with no source vocabulary visible (session-{A,B,C} in /tmp/fukan-emergence)

---

## 1. Lift Catalog

Every proposed lift across all six sessions, grouped by conceptual similarity. Naming variants, instance counts, and per-experiment convergence are noted in each group.

---

### Group A: Behavioral Claim (shapeless Affordance with prose)

The single largest conceptual cluster. Every session across both experiments proposed some form of this lift.

| Dimension | Sprint 2a Session 1 (constraint) | Sprint 2a Session 2 (validation) | Sprint 2a Session 3 (vocabulary) | Sprint 2b Session A | Sprint 2b Session B | Sprint 2b Session C |
|-----------|----------------------------------|----------------------------------|----------------------------------|---------------------|---------------------|---------------------|
| Name used | `invariant` | `invariant` | `invariant` | `definvariant` | `invariant` | `guarantee` |
| Role/semantic | Named behavioral commitment (`:constraint/invariant`) | Named behavioral commitment (`:fukan.canvas.validation/invariant`) | Named behavioral commitment (`:invariant`) | Internal behavioral truth (no role proposed, form only) | Named always-holds commitment (no role proposal) | Covers ALL shapeless-Affordance sub-types |
| Structural form | Shapeless Affordance, formal-expression = prose | Shapeless Affordance, formal-expression = `{:kind :invariant :prose doc}` | Shapeless Affordance, formal-expression = `{:text doc}` | Shapeless Affordance, formal-expression = prose | Shapeless Affordance, formal-expression = prose | Shapeless Affordance, formal-expression = prose |

**Naming variants:** `invariant` (5 of 6 sessions), `definvariant` (Session A — defmacro convention), `guarantee` (Session C — unified name covering all shapeless-Affordance sub-types)

**Instance count:** 36 shapeless Affordances across 8 corpus modules (per corpus README). Every module has at least one. The most pervasive pattern in the entire dataset.

**Convergence assessment:** 6-of-6. Every session independently proposed this lift. The strongest possible evidence.

---

### Group B: Behavioral Guarantee (consumer-facing commitment, distinct from internal invariant)

Session A (2b) proposed `defguarantee` as a separate lift from `definvariant`. No 2a session or 2b sibling session made this split.

| Dimension | Sprint 2a (all) | Sprint 2b Session A | Sprint 2b Session B | Sprint 2b Session C |
|-----------|-----------------|---------------------|---------------------|---------------------|
| Name used | (not proposed separately) | `defguarantee` | (not proposed separately) | (not proposed separately) |
| Semantic | All behavioral commitments folded into `invariant` | Consumer-facing promise; distinct from internal truth | Subsumed under `invariant` | Subsumed under `guarantee` (their unified name) |
| Instances | — | 6 (SingleServerInstance, SnapshotIsolation, SingleModelSource, ModelServerDecoupled, PureDelegation, PerRequestModel) | Not carved out | Not carved out |

**Naming variants:** `defguarantee` (Session A only)

**Convergence assessment:** 1-of-6. Session A alone proposed `guarantee` as a structurally separate lift from `invariant`. Sessions B and C (2b) explicitly rejected the split at the lift level. All 2a sessions unified them.

---

### Group C: Triggered Rule (reactive computation with `when:` clause)

| Dimension | Sprint 2a Session 2 (validation) | Sprint 2a Session 3 (vocabulary) | Sprint 2b Session A | Sprint 2b Session B | Sprint 2b Session C |
|-----------|----------------------------------|----------------------------------|---------------------|---------------------|---------------------|
| Name used | `rule` | `rule` | `defrule` (candidate, not shipped) | `fn-triggers-rule` (rejected) | `triggered-rule` (candidate, not shipped) |
| Structural form | Shapeless Affordance, role `:rule`, formal-expression `{:kind :rule :prose doc :when [{:param :type}]}` | Shapeless Affordance, role `:rule`, formal-expression `{:trigger text}` | Proposed but below rule-of-three; would be shapeless Affordance with `when:` prefix | Not proposed | Not proposed |
| Corpus instances | 2 (RunPhase4, LoadSource) | 2 (LoadSource, implied RunPhase4) | 2 (RunPhase4, LoadSource) | 2 | 2 |

**Naming variants:** `rule` (2a Sessions 2 and 3), `defrule` (2b Session A candidate), `triggered-rule` (2b Session C candidate)

**Instance count:** 2 in corpus (RunPhase4, LoadSource). The two `:triggers` relations in the corpus correspond to exactly these two.

**Convergence assessment:** 2-of-6 shipped (both in Sprint 2a). All three 2b sessions observed the pattern but withheld it for not meeting rule-of-three. Sprint 2a sessions shipped it anyway on 2 instances.

---

### Group D: Validation Checker (fixed-signature `model -> [violations]` callable)

| Dimension | Sprint 2a Session 2 (validation) | Sprint 2b Session A | Sprint 2b Session B | Sprint 2b Session C |
|-----------|----------------------------------|---------------------|---------------------|---------------------|
| Name used | `sub-phase-checks` | `defchecker` | `checker` | `checker` |
| Form | Plain Clojure function, not a defconstructor; iterates over a label vector producing one Affordance per label | defmacro, single-name declaration | defconstructor, single-name declaration | defn child-constructor, single-name declaration |
| Semantic intent | "A family of structurally-identical sub-phase check entry points named by labels" | "Declare a checker function on the current module — participation in the checker protocol" | "A validation-checker callable with the standard signature" | Same as B |
| Single-name form | Not provided (takes `[labels doc takes-shape gives-shape]`) | `(defchecker rules_4a)` | `(checker rules_4a)` | `(checker rules_4a)` |

**Sprint 2a Session 1** (constraint): did not propose this lift — its assigned modules (constraint/builtins, derivations, well_known) did not contain the checker pattern.

**Sprint 2a Session 3** (vocabulary): did not propose this lift — assigned vocabulary modules did not contain checkers.

**Naming variants:** `sub-phase-checks` (2a Session 2 — iteration-oriented), `defchecker` (2b Session A — defmacro style), `checker` (2b Sessions B and C)

**Instance count:** 8 (rules_4a through rules_4g in validation.phase4, plus `check` in validation.rules_4a). Strongest shaped-Affordance repetition in the corpus.

**Convergence assessment:** 4-of-6 proposed (2a Session 2 + all three 2b sessions). The two 2a sessions that missed it were assigned to different modules. Among sessions that saw the pattern: effectively 4-of-4.

---

### Group E: Checker Family / Iteration over uniform callables

| Dimension | Sprint 2a Session 2 (validation) | Sprint 2b Session B |
|-----------|----------------------------------|---------------------|
| Name used | `sub-phase-checks` | `checker-family` |
| Form | Plain function taking `[labels doc takes-shape gives-shape]`; general enough for any uniformly-typed function family | defmacro; specific to the checker signature; wraps repeated `(checker n)` calls |
| Scope | General: any named function family with a shared signature | Specific: expands to checker invocations only |

**Note:** Session A (2b) did not propose a family lift. Session C (2b) did not propose it either (just used `checker` repeatedly in the module example). The two sessions that proposed it (2a Session 2 and 2b Session B) chose different scopes.

**Convergence assessment:** 2-of-6. Both 2a and 2b contributed one proposal, but they are structurally different in scope (general vs. checker-specific).

---

### Group F: Zero-Argument Optional Getter

| Dimension | Sprint 2a (all three sessions) | Sprint 2b Session A | Sprint 2b Session B | Sprint 2b Session C |
|-----------|-------------------------------|---------------------|---------------------|---------------------|
| Name used | (not proposed in any 2a session) | `defgetter` | `nullable-reader` | `getter` |
| Structural form | — | defmacro; `() -> Optional<T>`; type can be atomic keyword or `[:ref target]` | defconstructor; output-type is a parsed inner shape | defn child-constructor; inner-type-shape is a map |
| Corpus instances | — | 4 (get_port, get_model, refresh_model, get_src) | 3 (get_port, get_model, get_src — excluded refresh_model) | 4 |

**Naming variants:** `defgetter` (Session A), `nullable-reader` (Session B — most semantically precise), `getter` (Session C — most read-naturally)

**Instance count:** 3–4 (all sessions agree on get_port, get_model, get_src; Sessions A and C include refresh_model; Session B excludes it as semantically impure)

**Convergence assessment:** 3-of-6 proposed, all in Sprint 2b. No Sprint 2a session proposed this lift. The pattern was absent from Sprint 2a's assigned modules (constraint subsystem = no getters; validation = no getters; vocabulary = no getters). This is selection bias, not priming gap.

---

### Group G: Purity Invariant (specialized behavioral claim about pure functions)

| Dimension | Sprint 2a Session 1 (rejected `pure`) | Sprint 2a Session 3 (rejected `pure`) | Sprint 2b Session B |
|-----------|--------------------------------------|--------------------------------------|---------------------|
| Name used | `pure` — rejected | `pure` — rejected | `purity-guarantee` |
| Rejection rationale | One instance not enough; purity is a behavioral commitment, express via `invariant` | Three purity invariants in assigned modules but "pure" collapses too many distinct meanings; `invariant` covers it | Proposed: 4 instances across 4 modules; generated consistent prose wrapping `invariant` |
| Form if shipped | — | — | `defn purity-guarantee [invariant-name fn-name]` wrapping `(invariant ...)` |

**Convergence assessment:** 1-of-6 shipped (2b Session B only). Two 2a sessions explicitly considered and rejected. 2b Sessions A and C did not propose it (Session C explicitly noted it as "content recurrence, not structural recurrence" and rejected it on those grounds).

---

### Group H: Module Container (`defmodule`)

| Dimension | Sprint 2a (all three) | Sprint 2b Session A | Sprint 2b Session B | Sprint 2b Session C |
|-----------|----------------------|---------------------|---------------------|---------------------|
| Name used | (not proposed) | (not proposed) | (not proposed) | `defmodule` |
| Pattern | Organizing construct implicit in the `within-module` form already in canvas | N/A | N/A | Eliminates dual-declaration burden: children list + module back-reference are maintained manually in corpus; lift auto-wires both |

**Convergence assessment:** 1-of-6 (Session C only). The corpus forces manual dual-declaration; Session C noticed this and proposed the lift. All other sessions worked within an existing `within-module` or equivalent organizing form already present in their target environment.

---

### Group I: fn-triggers-rule Coupling

| Dimension | Sprint 2a Session 2 (validation) | Sprint 2a Session 3 (vocabulary via rule form) | Sprint 2b Session A | Sprint 2b Session B | Sprint 2b Session C |
|-----------|----------------------------------|------------------------------------------------|---------------------|---------------------|---------------------|
| Name used | `function+` | `rule` with `when` form | `deftrigger` (candidate, not shipped) | `fn-triggers-rule` (rejected) | `triggered-rule` (candidate, not shipped) |
| Approach | Fresh defconstructor extending `function` with optional `triggers` and `returns` forms | The rule itself carries the trigger; fn has no extra form in Session 3's approach | Would be standalone coupling declaration | Not proposed | Not proposed |
| Status | Shipped | The `rule` lift in Session 3 has the `when` clause but no explicit `fn`-to-`rule` relation mechanism | Not shipped | Not shipped | Not shipped |

**Instance count:** 2 in corpus (run→RunPhase4, load_source→LoadSource).

**Convergence assessment:** 1 shipped in 2a (function+ in Session 2). Session 3 (2a) addressed this differently through the `rule` lift's `when` form. All 2b sessions observed but declined to ship (rule-of-three not met).

---

### Group J: General Typed Callable (`function` / `module-fn`)

| Dimension | Sprint 2a (all three via monolith) | Sprint 2b Session C |
|-----------|-----------------------------------|---------------------|
| Name used | `function` (from existing monolith.clj — extended as `function+` in Session 2) | `module-fn` |
| Status | Pre-existing in the canvas vocabulary; not newly proposed but extended | Newly proposed |
| Scope | General callable with takes/gives/effect forms | General callable for any typed arrow shape not covered by checker/getter |

**Convergence assessment:** Sprint 2a did not need to propose a general-function lift because it already existed. Sprint 2b Session C independently arrived at the need for a general-function form (`module-fn`) as the base case of which `checker` and `getter` are specializations. Sessions A and B (2b) did not propose it explicitly.

---

### Group K: Predicate (`Bool`-returning callable)

| Dimension | Sprint 2a Session 1 (constraint) | Sprint 2b Sessions A, B, C |
|-----------|----------------------------------|---------------------------|
| Name used | `predicate` | (not proposed by any 2b session) |
| Rationale | Four Bool-returning functions in constraint.builtins; `:constraint/predicate` role; `gives :Bool` built in | All three 2b sessions saw the 4 instances; all rejected: "concentrated in one module, no system-wide recurrence" |
| Status | Shipped in Session 1 | Explicitly rejected (Sessions A and C documented the rejection; Session B rejected `defpredicate`) |

**Instance count:** 4 (in, contains, is_present, is_absent — all in constraint.builtins)

**Convergence assessment:** 1-of-6 (2a Session 1 only). All three 2b sessions explicitly rejected it.

---

### Group K2: Predicate Catalog (surface closure for predicate modules)

| Dimension | Sprint 2a Session 1 (constraint) | Sprint 2b Sessions A, B, C |
|-----------|----------------------------------|---------------------------|
| Name used | `predicate-catalog` | (not proposed) |
| Rationale | BuiltinCatalogue invariant in builtins.allium names the complete predicate set; structural closure parallel to `exports` | Session A proposed `definvariant BuiltinCatalogue ...` to cover the same intent (no separate surface-closure lift); Sessions B and C similarly folded it into invariant/guarantee |
| Status | Shipped in Session 1 | Not proposed |

**Convergence assessment:** 1-of-6. Source-primed: Session 1 saw the `BuiltinCatalogue` invariant name in the Allium source and extracted a structural concept from it. The 2b corpus projected BuiltinCatalogue as a plain shapeless Affordance; no session saw it as warranting its own surface-closure lift.

---

## 2. Convergence Assessment — Across Both Experiments

### 6-of-6: Universal convergence

**Behavioral Claim (Group A)** — `invariant` / `guarantee` / `definvariant`

Every session independently proposed a lift for the shapeless-Affordance-with-prose pattern. This is the strongest possible evidence. The pattern has 36 instances in the corpus across all 8 modules. No session failed to notice it. No session declined to ship a lift for it.

This lift should be in the converged library. There is zero ambiguity about the underlying structural pattern. The naming debate (Section 4) is real but the lift's existence is settled.

---

### High-convergence (5+ sessions)

None. The 6-of-6 category is the only unanimous case. The next tier drops to 4-of-6.

---

### Cross-experiment convergence (proposed in BOTH 2a AND 2b)

**Validation Checker (Group D)** — `sub-phase-checks` / `defchecker` / `checker`

4-of-6 sessions (2a Session 2 + all three 2b sessions). The two 2a sessions that didn't propose it were assigned to modules without checkers. Among sessions that saw the validation domain: effectively unanimous. Strong cross-experiment evidence.

**Triggered Rule (Group C)** — `rule` / `defrule` / `triggered-rule`

2a Sessions 2 and 3 shipped it. All three 2b sessions observed the pattern and withheld shipment (rule-of-three not satisfied: only 2 corpus instances). Cross-experiment presence: yes — both experiments saw the same structural pattern (the "when: RuleName(...)" shapeless Affordance with incoming `:triggers` relation). The question is whether 2 instances warrant a lift. The 2b sessions' collective decision was: not yet. The 2a sessions decided: yes, the structural distinction from invariant is real enough to ship on 2 instances. This is a genuine design judgment call.

---

### Sprint 2a-only lifts

**Predicate (`predicate`) — Group K**

2a Session 1 shipped it; all three 2b sessions rejected it. The pattern (4 Bool-returning functions, all in constraint.builtins) is present in the corpus. 2b sessions had full visibility. Their rejection was consistent: one-module concentration, no system-wide recurrence.

**Predicate Catalog (`predicate-catalog`) — Group K2**

2a Session 1 only. Pattern (BuiltinCatalogue invariant as surface closure) is present in the corpus. 2b sessions saw BuiltinCatalogue as a plain invariant/guarantee and didn't distinguish it structurally.

**fn-triggers-rule coupling (`function+`) — Group I**

2a Session 2 shipped this as a full `function+` defconstructor. 2b sessions observed the pattern (2 instances) and declined. The implementation vehicle (function+) is 2a-specific.

**Purity Invariant (`purity-guarantee`) — Group G**

2b Session B is the only session to ship this — technically making it 2b-only, not 2a-only. Sessions 2a-1 and 2a-3 explicitly rejected it.

---

### Sprint 2b-only lifts

**Zero-Argument Optional Getter (`defgetter` / `nullable-reader` / `getter`) — Group F**

All three 2b sessions proposed this; no 2a session did. The reason is structural: 2a sessions were assigned to constraint, validation, and vocabulary modules — none of which contain zero-arg Optional getters. The pattern only appears in infra modules (infra.server, infra.model), which were outside Sprint 2a's scope. This is selection bias, not evidence that 2a missed the pattern.

**defguarantee (invariant/guarantee split) — Group B**

Session A (2b) only. The "guarantee as consumer-facing commitment, distinct from invariant as internal truth" split. Not proposed by any 2a session or by 2b Sessions B and C.

**Module Container (`defmodule`) — Group H**

Session C (2b) only. Noticed the dual-declaration burden (children list + module back-ref) in the raw corpus and proposed a lift to eliminate it. Sprint 2a sessions worked within an existing `within-module` form that already handled this, so the problem wasn't visible.

---

## 3. Source-Priming Assessment

### 2a-only lifts: was 2b's rejection justified?

**`predicate` — justified rejection**

The 4 Bool-returning functions in constraint.builtins are present in the corpus. 2b sessions had full visibility and unanimously rejected the lift. Their stated reason: "concentrated in one module, no system-wide recurrence" (Session C). Session B was explicit: "One instance of a cluster is not a candidate for a lift." The corpus structure supports this verdict — BuiltinCatalogue is the only module with a cluster of Bool-returning functions. The pattern genuinely doesn't recur at the module level across the corpus.

The 2a session proposed `predicate` because it was directly porting builtins.boundary, where the four `fn` declarations with Bool returns were explicit source code. Seeing the source code in context created a "these four things are structurally alike and semantically distinct from general functions" intuition. The 2b agents, seeing only shapes and counts, applied the rule-of-three correctly and rejected it.

**Verdict:** The `predicate` lift is source-primed. The 2b corpus contains the structural pattern but the 2b sessions correctly rejected it on rule-of-three grounds. 2a Session 1 was primed by seeing the predicate-domain vocabulary in context.

**`predicate-catalog` — justified rejection**

The corpus contains BuiltinCatalogue as a plain shapeless Affordance (no special structure — just formal-expression prose). The 2b sessions had full visibility and treated it like any other invariant/guarantee. Session 1 (2a) extracted a structural concept — "surface closure for predicate modules" — from the Allium source's explicit structural intent. The source text made the structural claim explicit; the corpus had only the fact. 2a was primed by source vocabulary.

**Verdict:** `predicate-catalog` is source-primed. The corpus doesn't carry enough structural information to distinguish a "catalog" Affordance from a general invariant. 2a Session 1 was reading Allium semantics, not substrate structure.

**`function+` / fn-triggers-rule coupling — partially primed**

The `:triggers` relation and the `RunPhase4`/`LoadSource` pattern are both present in the corpus (the corpus explicitly carries 2 `:kind :triggers` relations). 2b sessions universally observed the pattern and withheld the lift on rule-of-three grounds. 2a Session 2 shipped it because: (a) it was actively porting phase4.boundary which has an explicit `triggers: RunPhase4` clause in the source, and (b) it designed `function+` as a fresh defconstructor to handle this case without modifying the base `function` lift.

The structural pattern is real. The corpus carries it. The 2b decision to defer on 2 instances was disciplined and correct. The 2a decision to ship on 2 instances was motivated by direct source exposure to `triggers:` clauses.

**Verdict:** Partial priming. The structural pattern is real (cross-experiment evidence: both 2a and 2b saw it). The 2a decision to ship at 2 instances was primed by source context; 2b's discipline in waiting for 3 was stricter but defensible. This lift is a legitimate design question, not a priming artifact.

---

### 2b-only lifts: would 2a have proposed them given structural visibility?

**`getter` / `nullable-reader` / `defgetter` — selection bias, not priming gap**

Sprint 2a's three sessions covered constraint, validation, and vocabulary modules. None of these modules contains zero-arg Optional getters. The infra modules (infra.server, infra.model) were not in Sprint 2a's scope. 2a did not miss the `getter` pattern due to priming; it simply never encountered the instances.

If 2a had been assigned infra modules, it would almost certainly have proposed a getter lift — the pattern (4 instances, consistent shape, semantic coherence as "state accessor") meets all the criteria 2a sessions applied when proposing lifts.

**Verdict:** Selection bias. `getter` is a genuine lift that 2a would have proposed had it seen the infra modules.

**`defguarantee` (invariant/guarantee distinction) — genuine emergence finding**

Session A's split between `definvariant` (internal truth) and `defguarantee` (consumer-facing commitment) came from reading corpus comments that systematically distinguished the two flavors. The corpus comments (which the corpus readme notes: "guarantee", "invariant", "rule", "surface" as labels) were the source of the distinction. Session A read these labels as evidence for a structural split even though the substrate form is identical.

Sessions B and C (2b) saw the same comments and explicitly rejected the split: "at the substrate level they are structurally identical" (Session C). Sprint 2a sessions also rejected the split (Session 2 considered `guarantee` vs. `invariant` and rejected it as a "distinction that doesn't survive the altitude test").

Session A's `defguarantee` is neither primed (no source vocabulary visible) nor clearly emergent — it was motivated by corpus comments that named the distinction. It is a reasonable design interpretation of those comments that two sibling 2b sessions rejected.

**Verdict:** Session A's `defguarantee` represents a genuine design judgment call, not priming. The split may be right or wrong, but it isn't source-primed. See Section 4 for recommendation.

**`defmodule` — genuine emergence finding**

Session C's `defmodule` addresses a real structural burden in the corpus: every entity must declare its parent module id, and the module entity must list all children. This creates a dual-declaration invariant that a lift can eliminate. Sprint 2a sessions never encountered this because they worked within an existing `within-module` form that already handled it.

The pattern is visible in the corpus (all 8 modules have the dual-declaration structure). Sessions A and B (2b) didn't propose it, suggesting the burden wasn't obvious until Session C found it. Sprint 2a's experience with an existing `within-module` form would have suppressed the observation.

**Verdict:** Genuine emergence. The `defmodule` lift addresses a real corpus-level structural burden that Sprint 2a's use of pre-existing tooling obscured.

---

## 4. Recommendation for the Converged Library

### Ship with confidence

**1. A single behavioral-claim lift (resolving the `invariant` / `guarantee` / `defguarantee` debate)**

Evidence: 6-of-6 sessions proposed this lift. The structural pattern (36+ shapeless Affordances with prose) is undeniable.

The naming debate: Session A (2b) wants two lifts (`definvariant` + `defguarantee`). Sessions B and C (2b) want one (`invariant` or `guarantee`). All three 2a sessions named it `invariant`. Session C (2b) named it `guarantee` as an intentional unification.

**Recommendation: ship as `invariant`.** The name has 5-of-6 session support. The one outlier (Session C) chose `guarantee` as a deliberate unification term — a reasonable choice, but the name `invariant` is more precise (it makes a mathematical commitment; `guarantee` is ambiguous between "invariant that holds" and "promise to callers"). The domain already uses `invariant` in Allium; consistency reduces friction.

For the `defguarantee` split: the design question — should consumer-facing commitments be a separate lift from internal truths? — is a legitimate judgment call. The evidence does not compel the split: 5-of-6 sessions rejected it, and the substrate form is identical. **Recommendation: do not ship `guarantee` as a separate lift in the initial converged library.** The naming convention (e.g. naming consumer-facing commitments `<Thing>Guarantee` and internal truths `<Thing>Invariant`) can carry the semantic distinction without requiring two lifts. If multiple projects find that the distinction is architecturally load-bearing (i.e., query targets that need to filter only consumer commitments), revisit.

**Role keyword:** The sessions used `:constraint/invariant`, `:fukan.canvas.validation/invariant`, `:invariant`, and no role. **Recommendation: `:canvas/invariant`** — domain-agnostic, namespace-qualified, queryable. Module-domain namespacing on the role (`:constraint/invariant`) was the choice of a domain-specific library in Session 1 and should not be promoted to the base lift.

---

**2. Validation Checker (`checker`)**

Evidence: 4-of-6 sessions proposed this lift; among sessions that saw the validation domain, it was unanimous. 8 corpus instances — the strongest shaped-Affordance recurrence in the corpus.

**Recommendation: ship as `checker`.** The name `checker` (Sessions B and C, 2b) reads better than `defchecker` (Session A, `def` prefix) and better than `sub-phase-checks` (Session 2, 2a — which conflates the checker shape with the iteration concern). The lift takes only a name; the full signature `(model: model/Model) -> List<agent/Violation>` is implied. The corpus evidence for this exact signature is strong (8 identical instances, 2 modules).

For the iteration concern (the 7-in-a-row checker sequence in validation.phase4): do not ship a separate `checker-family` lift at this stage. `checker-family` is purely a macro over `checker` — it adds no new concept. Session B proposed it; no other session did. The composed form (calling `checker` seven times) is readable enough. Ship `checker-family` only if future canvas authors find the repetition ergonomically burdensome.

---

**3. Zero-Argument Optional Getter (`getter`)**

Evidence: 3-of-6 sessions (all Sprint 2b) proposed this lift; Sprint 2a did not see the pattern. 4 corpus instances across 2 infra modules.

**Recommendation: ship as `getter`.** The name `getter` (Session C) is more readable than `nullable-reader` (Session B — accurately descriptive but verbose) and more neutral than `defgetter` (Session A — `def` prefix). The lift takes a name and a return type; the zero-arg input and Optional wrapping are implied. The 4-instance corpus count meets rule-of-three. Include `refresh_model` in the documented instances with a note that the lift covers the structural signature (zero-arg Optional return), not the semantic distinction between pure readers and side-effectful refreshers — that distinction belongs in an accompanying `invariant`.

---

### Judgment calls requiring human decision

**A. Triggered Rule lift (`rule`)**

The structural pattern is real (2 corpus instances with identical form: shapeless Affordance with "when:" prefix + incoming `:triggers` relation). Sprint 2a Sessions 2 and 3 shipped `rule` on 2 instances. All three Sprint 2b sessions withheld it on rule-of-three grounds.

The question: is the rule-of-three threshold correct, or does the structural distinctness of the "when: trigger" pattern justify shipping at 2?

Arguments for shipping: the structural form is genuinely different from a plain `invariant` — it has a trigger clause and an incoming relation; the combination is the tell. Two unambiguous instances of the same structural combination is meaningful. The 2a sessions, having ported the actual Allium sources, found the `rule` / `invariant` distinction architecturally legible and clean.

Arguments against shipping: the 2b sessions followed Beck's rule-of-three correctly. Two instances in a corpus of 8 modules is 25% coverage — enough to notice, not enough to call a system-wide pattern. If a third module had a triggered rule, all 2b sessions would have shipped it.

**Recommendation: ship `rule` as an explicitly provisional lift, with documentation that a third corpus instance would confirm it.** The 2-instance evidence is weaker than for `invariant` or `checker`, but the structural distinctness is clear. The `rule` lift should carry a comment noting the provisional status and the evidence threshold. Do not merge `rule` into `invariant` — Sessions 2 and 3 (2a) explicitly rejected that merge on structural grounds, and the argument is sound (rules have causal triggers; invariants hold timelessly; they produce different queries).

**B. `when` trigger content: prose or structured?**

Session 2 (2a) stored the `when` clause as a typed parameter list `[{:param "model" :type :model/Model}]`. Session 3 (2a) stored it as prose text `"LoadSource(source_root: String)"`. Sessions A, B, C (2b) didn't ship the lift, but Sessions A and B noted the "when:" prefix as the structural tell without specifying the content encoding.

**Recommendation: prose for now.** Session 3's choice to store the trigger as text is consistent with the substrate's `formal-expression` slot being deliberately untyped ("store whatever the lift author puts there"). The typed parameter list in Session 2 adds parseability at the cost of a brittle encoding decision. Ship prose; add structured encoding if a consumer needs it.

**C. `function+` vs. standalone trigger coupling**

Session 2 (2a) proposed `function+` — a full `defconstructor` replicating `function`'s forms plus `triggers` and `returns`. Session A (2b) proposed a standalone `deftrigger` coupling declaration. Neither approach is clearly superior.

**Recommendation: defer.** With only 2 corpus instances and no emerging third, the fn-triggers-rule coupling is not yet load-bearing enough to settle the implementation form. Keep `rule` (if shipped per above) without a formal fn-coupling mechanism. Document the pattern (fn with `:triggers` relation to rule) as a composition for authors to use manually until a third instance forces the decision.

---

### Weak evidence — ship as alternates, not as primary vocabulary

**`purity-guarantee`** (2b Session B only, explicitly rejected by Sessions 2a-1, 2a-3, and 2b-C)

The underlying observation — 4 modules have purity invariants with nearly identical content — is real. Session B's proposed lift generates consistent prose from a function name, wrapping `invariant`. The rejection argument from Sessions 1 and 3 (2a) is strong: purity invariants are semantically varied (PipelinePurity excepts stderr warnings; PurelyDerivedFromModel is about determinism). Generated prose would be too generic to represent the real commitments.

**Recommendation: do not ship in the converged library.** `invariant` covers purity claims adequately. Document the naming convention (use `<Subject>Purity` or `<Subject>IsPure` for purity invariants) in the canvas style guide. A convenience constructor can be added if multiple canvas authors independently request it.

**`defguarantee`** (2b Session A only)

**Recommendation: do not ship as a separate lift.** The 5-of-6 rejection evidence is clear. Document the semantic distinction (consumer-facing vs. internal) as a naming convention, not a structural split.

**`predicate-catalog`** (2a Session 1 only, source-primed)

**Recommendation: do not ship.** The pattern is source-primed and the corpus evidence doesn't support distinguishing BuiltinCatalogue from other invariants structurally. Express with `invariant`.

---

### Naming recommendations where sessions diverged

| Concept | Recommended name | Reasoning |
|---------|-----------------|-----------|
| Behavioral claim (shapeless Affordance + prose) | `invariant` | 5-of-6 sessions; precise; consistent with Allium |
| Fixed-signature `(model) -> [Violation]` callable | `checker` | 2 of 3 2b sessions; short; reads naturally |
| Zero-arg Optional callable | `getter` | Session C (2b); short; "nullable-reader" too verbose |
| Triggered reactive rule with `when:` | `rule` | 2 of 3 2a sessions; `defrule` adds unnecessary prefix |
| Consumer-facing behavioral commitment | (name as invariant, distinguish by PascalCase convention) | No separate lift; naming convention carries the distinction |

---

## 5. Meta-Finding on the Experiment

### Did Sprint 2b produce genuinely new evidence that Sprint 2a alone wouldn't have?

Yes, in three ways. The evidence is differentiated:

**1. Confirmed the central pattern under independent conditions.** The `invariant` lift's 6-of-6 convergence — including three agents who never saw Allium source vocabulary — eliminates source priming as a confounding explanation. Sprint 2a alone could only establish that agents reading `invariant Foo { ... }` in Allium source proposed an `invariant` lift (strong result, but potentially circular). Sprint 2b's three agents, seeing only structural data with no source vocabulary, independently converged on the same pattern. This is genuine evidence that `invariant` captures a naturally occurring structural category, not an Allium-vocabulary artifact.

**2. Applied stricter discipline that correctly rejected two source-primed lifts.** The `predicate` lift (Session 1, 2a) was correctly rejected by all three 2b sessions on rule-of-three grounds — one module, four instances, no system-wide recurrence. The `predicate-catalog` lift (Session 1, 2a) was not proposed by any 2b session; the corpus projected BuiltinCatalogue as a plain invariant and no session saw reason to distinguish it. Sprint 2a's session-1 proposals for both were source-primed. Without Sprint 2b's evidence, these lifts might have shipped into the converged library on the basis of "Session 1 found them useful." Sprint 2b correctly counterweights that.

**3. Identified one genuinely new pattern (getter) and one genuinely new structural insight (defmodule).** The `getter` lift was invisible to Sprint 2a because infra modules were outside Sprint 2a's assignment. Sprint 2b's corpus covered infra, so the pattern emerged. This is selection-bias correction, not emergence in the deeper sense — but it is still new evidence Sprint 2a couldn't have supplied. The `defmodule` lift (Session C) noticed a structural burden in the raw corpus format (dual-declaration) that Sprint 2a's use of pre-existing `within-module` tooling masked entirely. This is the most surprising finding: the corpus format itself created a visible problem that existing canvas tooling was already solving invisibly.

**4. Applied more consistent rule-of-three discipline on the `rule` lift.** All three 2b sessions independently applied Beck's rule correctly and declined to ship `rule` on 2 instances. Sprint 2a Sessions 2 and 3 shipped it. The evidence for `rule` is real (structurally distinct pattern, cross-experiment agreement on what it is) but weak (2 instances). Sprint 2b's collective restraint is evidence that the sprint-2a decision to ship `rule` was influenced by the source context — seeing `rule RunPhase4 { when: ... }` explicitly in Allium made the pattern feel load-bearing even at 2 instances. The 2b agents, seeing only structural data, correctly assessed it as "candidate, not shipped." This is the clearest case of source-priming affecting a shipping decision in Sprint 2a.

### What Sprint 2b did not produce

Sprint 2b did not produce a substantially different vocabulary from Sprint 2a. The overall outcome is Outcome 2 from the experiment plan ("Partial convergence"): the central lifts (`invariant`, `checker`) converge strongly; the peripheral lifts diverge in ways that trace to selection bias (`getter`) or legitimate discipline differences (`rule`). The 2b vocabulary is not "what canvas-native looks like" instead of 2a's vocabulary — it is a confirmation and calibration of 2a's vocabulary.

The one genuine outlier is Sprint 2a Session 1's domain-specific lifts (`predicate`, `predicate-catalog`) — these are the clearest priming artifacts. Everything else in Sprint 2a has cross-experiment backing or can be explained by selection bias.

---

## Self-Reported File List

Every file read while producing this comparison:

**Sprint 2a session materials:**
1. `/Users/yabmas/Code/fukan-explore-1/src/fukan/canvas/library/explore_1/constraint.clj`
2. `/Users/yabmas/Code/fukan-explore-1/doc/plans/2026-05-25-explore-1-notes.md`
3. `/Users/yabmas/Code/fukan-explore-2/src/fukan/canvas/library/explore_2/validation.clj`
4. `/Users/yabmas/Code/fukan-explore-2/doc/plans/2026-05-25-explore-2-notes.md`
5. `/Users/yabmas/Code/fukan-explore-3/src/fukan/canvas/library/explore_3/behavioral.clj`
6. `/Users/yabmas/Code/fukan-explore-3/doc/plans/2026-05-25-explore-3-notes.md`

**Sprint 2b session materials:**
7. `/tmp/fukan-emergence/session-A/proposed-lifts.clj`
8. `/tmp/fukan-emergence/session-A/patterns-extracted.md`
9. `/tmp/fukan-emergence/session-B/proposed-lifts.clj`
10. `/tmp/fukan-emergence/session-B/patterns-extracted.md`
11. `/tmp/fukan-emergence/session-C/proposed-lifts.clj`
12. `/tmp/fukan-emergence/session-C/patterns-extracted.md`

**Background context:**
13. `/Users/yabmas/Code/fukan/doc/plans/2026-05-25-canvas-substrate-phase-2-emergence.md`
14. `/Users/yabmas/Code/fukan/doc/plans/2026-05-25-emergence-corpus-readme.md`
