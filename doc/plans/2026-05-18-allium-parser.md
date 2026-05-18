# Allium Parser Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the existing `fukan.libs.allium.parser` to cover the **canonical Allium grammar** ([juxt/allium language reference](../../../../.claude/plugins/cache/juxt-plugins/allium/3.3.0/references/language-reference.md), 2185 lines). The current parser handles ~70% of canonical constructs; this plan fills the structural gaps. **Expression-language bodies stay captured as text** (the parser's deliberate layering — Plan 2b builds the expression parser as part of the analyzer). After this plan lands, every `.allium` construct documented in the canonical reference parses into a well-shaped AST, and fukan's own 5 `.allium` files parse without warnings.

**Architecture:** In-place additions to `src/fukan/libs/allium/parser.clj` — instaparse PEG grammar + transform map (the existing two-section split at lines 11–244 and 246–712). Each new construct: grammar rule(s) + matching transform + tests (per-construct unit test in `test/fukan/libs/allium/parser_test.clj` + corpus regression). No new dependencies. **Allium is external** ([feedback memory](../../../.claude/projects/-Users-yabmas-Code-fukan/memory/feedback_allium_external.md)) — this plan conforms to the canonical grammar; it never proposes changes to Allium itself.

**Tech Stack:** Existing `instaparse/instaparse 1.5.0` from `deps.edn`; existing `clojure.test` + `cognitect.test-runner` via the `:test` alias. No new deps.

---

## Plan-of-plans context

This is **Plan 2a of 7** in the next-chapter overhaul. The full sequence (revised — Plan 2 was split into 2a + 2b mid-execution):

1. **Kernel substrate** *(closed)* — primitives, value records, Type, relations, Expression / Effect, vocabulary mechanism, Artifact ontology, fixture-only Model construction.
2. **2a. Allium parser completion** *(this plan)* — structural grammar to canonical coverage; expression bodies text-captured.
3. **2b. Allium analyzer** — Allium AST → kernel content + `Allium::*` tags; embedded expression parser; Effect canonicalisation matcher fills the Plan-1 `fukan.model.effect/canonicalise` stub.
4. **Boundary analyzer + build pipeline + validation rules**.
5. **Constraint language + Phase 5**.
6. **Clojure Target extension + project layer**.
7. **Explorer rewrite + generation flow**.

Plan 1 closed with 5 follow-ups carried in-place via `jj squash` amendments (Type `:semantics/keyed` rename, primitives schema dedup + docstring, build NOTE + storage-convention docstring). The substrate at `@-` (currently `ynmkoupp`) is the foundation Plan 2a's parser will eventually feed (in Plan 2b).

Authoritative refs:
- [Allium language reference](../../../.claude/plugins/cache/juxt-plugins/allium/3.3.0/references/language-reference.md) — the canonical grammar (read-only; do not propose changes upstream).
- [MODEL.md §8.1](../MODEL.md#81-allium--kernel-mapping) — the Allium → kernel mapping table the Plan-2b analyzer will realise.
- [Plan 1](2026-05-18-kernel-substrate.md) — the kernel substrate this plan eventually plumbs into via Plan 2b.

---

## Repository conventions (jj over git)

Identical to Plan 1. This is a **colocated jj/git repository** (`.jj/` and `.git/` both present). Translate the plan's commit steps:

| In the plan | Run instead |
|---|---|
| `git add <paths>` | *(omit — jj snapshots the working copy automatically)* |
| `git commit -m "<message>"` (or heredoc form) | `jj desc -m "<message>"` followed by `jj new` to start the next change |

After each commit, verify with `jj st` and `jj log -r '::@' --limit 5`. One logical change per commit; `jj new` between tasks; never `git commit` directly.

---

## Conventions used throughout this plan

- **Instaparse grammar style** — match the existing parser's idioms: hidden whitespace via `<_>`/`<__>` rules; literal tokens in `<'...'>` brackets; PEG ordered alternation; angle-bracket prefix `<...>` on rule names to hide them from the parse tree.
- **Transform map** — each new grammar rule with a name like `:foo-bar` gets a matching `:foo-bar` entry in the `transforms` map (lines 273–712 of `parser.clj`). Transforms return Clojure maps with `:type` (declaration kind), `:field-kind` (for nested field entries), or a domain-specific keyword discriminator.
- **AST shape consistency** — every declaration map has `:type :<kind>` (`:rule`, `:entity`, `:surface`, `:actor`, etc.); every field entry inside `:fields` has `:field-kind`; every type-ref has `:kind`. The existing `structural-assertions-test` enforces these; new constructs must conform.
- **Test-as-spec** — every new construct gets a per-construct deftest in `parser_test.clj` BEFORE the grammar rule lands. Each deftest exercises one positive case and (where ambiguity is possible) one negative case. Plus integration tests that parse the relevant corpus file.
- **Underscore-vs-kebab** — Allium uses snake_case for identifiers and keywords in source text (`identified_by`, `transitions_to`). The parser preserves these as strings in the AST (`{:name "identified_by"}`); Plan 2b's analyzer is responsible for any underscore-to-kebab translation when producing kernel content. **Do not translate inside the parser.**
- **No expression parsing** — rule clause bodies (`requires:`, `ensures:`, `let` RHS), invariant bodies, field-`when` conditions, derived-value expressions, and config defaults all stay captured as **trimmed text** via the existing `clause-body` / `balanced-chunk` infrastructure. Plan 2b adds the expression parser. If you find yourself adding grammar for expression internals, stop — that's out of scope.

---

## File Structure

### Files to modify

- `src/fukan/libs/allium/parser.clj` — grammar + transforms (the single source file).
- `test/fukan/libs/allium/parser_test.clj` — unit tests for each new construct + assertion that all corpus files parse.
- `doc/MODEL.md` — §8.1 doc fix: `where: x = e` → `let x = e` (the canonical Allium grammar uses `let`; see [Allium language reference §"Local bindings (let)"](../../../.claude/plugins/cache/juxt-plugins/allium/3.3.0/references/language-reference.md) line 826).
- `doc/plans/2026-05-18-kernel-substrate.md` — plan-of-plans header: "Plan 1 of 6" → "Plan 1 of 7" (since Plan 2 was split into 2a + 2b).

### Files to create

None — all parser work is in-place additions to the existing file.

### Files to leave untouched

- All Plan-1 substrate (`src/fukan/model/*.clj`) — Plan 2a does not touch the kernel substrate.
- `src/fukan/infra/*` — fixture loader stays as-is; Plan 2b will swap it for the real analyzer.
- The 5 `.allium` corpus files in `src/` — they must parse without changes; if a corpus file would require a change to parse, the parser is wrong (don't edit the corpus).

---

## Reading the canonical reference

The Allium language reference is at `/Users/yabmas/.claude/plugins/cache/juxt-plugins/allium/3.3.0/references/language-reference.md`. **Each task references specific line ranges in that file** — go read them before adding the grammar rule. The reference is authoritative for both syntax and semantic intent.

For the implementer subagent: when in doubt about a construct's exact syntax (e.g., punctuation, optional clauses, ordering), the reference is the source of truth. The parser must match it.

---

## Task 0: Doc fixes + test-suite-as-spec setup

**Files:**
- Modify: `doc/MODEL.md` (one row in §8.1)
- Modify: `doc/plans/2026-05-18-kernel-substrate.md` (Plan-of-plans header count)
- Modify: `test/fukan/libs/allium/parser_test.clj` (add empty failing test stubs for each Tier 2 task)

This task lands the docs corrections first (so the rest of Plan 2a can reference canonical Allium semantics without contradiction), then adds **failing test stubs** for every task in the plan. Each subsequent task replaces its stub with a real test, watches it fail, then implements the grammar.

- [ ] **Step 0.1: Fix MODEL.md §8.1 row for `where:` / `let`**

Find this row in `doc/MODEL.md` §8.1 (the row about typed rule bindings):

```
| `where: x = e` (inside rule) | `Definition(name="x", expression=e)` in `Rule.body.definitions` (per §3.2) | `Allium::Where` (source-clause tag on the Definition's expression) | Typed binding visible across `Rule.intent.assertions` and `Rule.body.effects`. |
```

Replace with:

```
| `let x = e` (inside rule) | `Definition(name="x", expression=e)` in `Rule.body.definitions` (per §3.2) | `Allium::Let` (source-clause tag on the Definition's expression) | Typed binding visible across `Rule.intent.assertions` and `Rule.body.effects`. The Allium grammar names the clause `let` per the canonical reference §"Local bindings (let)". |
```

(The `Allium::Where` tag → `Allium::Let` rename keeps the source-clause tag aligned with the source-language keyword. This doesn't affect Plan 1 — no code references the tag name yet; the rename lives in the doc until Plan 2b authors the analyzer that emits the tag.)

- [ ] **Step 0.2: Fix the plan-of-plans count in Plan 1's plan file**

Open `doc/plans/2026-05-18-kernel-substrate.md` and find the "Plan-of-plans context" section. Change:

```
This is **Plan 1 of 6** for the next-chapter overhaul.
```

to:

```
This is **Plan 1 of 7** for the next-chapter overhaul (Plan 2 was split into 2a + 2b after the kernel substrate landed; see `2026-05-18-allium-parser.md`).
```

And update the numbered list. Replace this block:

```
1. **Kernel substrate** *(this plan)* — primitives, value records, Type, relations, Expression / Effect, vocabulary mechanism shells, v0 Artifact ontology, fixture-only Model construction.
2. **Allium analyzer** — Allium AST → kernel content + `Allium::*` tags ([MODEL.md §8.1](../MODEL.md#81-allium--kernel-mapping)). Reuses `libs/allium/parser.clj`.
3. **Boundary analyzer + build pipeline + validation rules** — settles `.boundary` token syntax; new parser; Phases 1–4 with gates G1/G2; sub-phases 4a–4g ([DESIGN.md "Build pipeline"](../DESIGN.md)).
4. **Constraint language + Phase 5** — stratified Datalog substrate, kernel-universal derivations, methodology-shipped predicates.
5. **Clojure Target extension + project layer** — Analyzer (`projects` edges with validity) + Projector (Implementation Blueprints).
6. **Explorer rewrite + generation flow** — new views, sidebars, edge filtering, drift markers, click-to-generate UX.
```

with:

```
1. **Kernel substrate** *(this plan)* — primitives, value records, Type, relations, Expression / Effect, vocabulary mechanism shells, v0 Artifact ontology, fixture-only Model construction.
2. **2a. Allium parser completion** — bring `libs/allium/parser.clj` to canonical Allium coverage; expression bodies stay text-captured (see `2026-05-18-allium-parser.md`).
3. **2b. Allium analyzer** — AST → kernel content + `Allium::*` tags ([MODEL.md §8.1](../MODEL.md#81-allium--kernel-mapping)); embedded expression parser; fills the Plan-1 `fukan.model.effect/canonicalise` stub.
4. **Boundary analyzer + build pipeline + validation rules** — settles `.boundary` token syntax; new parser; Phases 1–4 with gates G1/G2; sub-phases 4a–4g ([DESIGN.md "Build pipeline"](../DESIGN.md)).
5. **Constraint language + Phase 5** — stratified Datalog substrate, kernel-universal derivations, methodology-shipped predicates.
6. **Clojure Target extension + project layer** — Analyzer (`projects` edges with validity) + Projector (Implementation Blueprints).
7. **Explorer rewrite + generation flow** — new views, sidebars, edge filtering, drift markers, click-to-generate UX.
```

- [ ] **Step 0.3: Add a corpus-regression deftest to `parser_test.clj`**

Append to `test/fukan/libs/allium/parser_test.clj` (after the existing integration tests):

```clojure
;; ---------------------------------------------------------------------------
;; Corpus-regression — every .allium file in src/ must parse clean
;; ---------------------------------------------------------------------------

(def ^:private corpus-files
  ["src/fukan/infra/spec.allium"
   "src/fukan/web/spec.allium"
   "src/fukan/web/views/spec.allium"
   "src/fukan/model/spec.allium"
   "src/fukan/model/pipeline.allium"])

(deftest corpus-regression-test
  (testing "every .allium file in src/ parses without failure"
    (doseq [f corpus-files]
      (let [result (parser/parse-file f)]
        (is (not (insta/failure? result))
            (str "parse failed for " f ": " (pr-str result)))))))
```

- [ ] **Step 0.4: Verify state**

```bash
clj -M:test -n fukan.libs.allium.parser-test
```

Expected: existing tests still pass (count up to ~24 since we added one) PLUS the corpus-regression-test passes (because the current parser handles the corpus structurally even where bodies are text-only). If the corpus-regression-test fails on any file, **STOP and report** — that means there's an existing bug in the parser the gap analysis missed.

- [ ] **Step 0.5: Commit**

```bash
jj desc -m "docs(allium): fix where: -> let; plan-of-plans 6 -> 7

MODEL.md §8.1: the Allium canonical reference names the rule-clause keyword
\`let\` per §'Local bindings (let)'. Update the row + tag name accordingly.
Plan-of-plans count moves to 7 since Plan 2 split into 2a + 2b.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 1: Actor declarations

**Files:**
- Modify: `src/fukan/libs/allium/parser.clj` (grammar + transform)
- Modify: `test/fukan/libs/allium/parser_test.clj`
- Reference: [Allium language reference §"Actor declarations"](../../../.claude/plugins/cache/juxt-plugins/allium/3.3.0/references/language-reference.md) line 1660.

Allium's `actor` declaration:

```
actor Author {
    identified_by: User
    within: Account
}
```

The `identified_by` clause names the identity-bearing entity for the Actor. The `within` clause is optional and names a contextual entity scope. Both are typed (point at entity declarations).

Per the canonical reference, the grammar is:

```
actor-decl   = 'actor' Name '{' identified-by within? '}'
identified-by = 'identified_by:' type-ref
within        = 'within:' type-ref
```

The Allium reference also permits `identified_by: Type where condition` (the `where` keyword is the expression-language one from §1235); per the deliberate layering, the parser captures `where ...` text without parsing it.

- [ ] **Step 1.1: Write the failing test**

Add to `test/fukan/libs/allium/parser_test.clj` (group with other declaration tests):

```clojure
(deftest actor-decl-test
  (testing "actor with identified_by only"
    (let [d (first-decl "actor Author {\n    identified_by: User\n}\n")]
      (is (= :actor (:type d)))
      (is (= "Author" (:name d)))
      (is (= {:kind :simple :name "User"} (:identified-by d)))
      (is (nil? (:within d)))))

  (testing "actor with identified_by and within"
    (let [d (first-decl "actor Editor {\n    identified_by: User\n    within: Workspace\n}\n")]
      (is (= :actor (:type d)))
      (is (= {:kind :simple :name "User"} (:identified-by d)))
      (is (= {:kind :simple :name "Workspace"} (:within d)))))

  (testing "actor with qualified type in identified_by"
    (let [d (first-decl "actor Admin {\n    identified_by: auth/User\n}\n")]
      (is (= {:kind :qualified :ns "auth" :name "User"} (:identified-by d))))))
```

- [ ] **Step 1.2: Run; see failures**

```bash
clj -M:test -n fukan.libs.allium.parser-test/actor-decl-test
```

Expected: parse failures because the grammar has no `actor-decl` rule.

- [ ] **Step 1.3: Add the grammar rule**

In `src/fukan/libs/allium/parser.clj`, in the `declaration` alternation (currently line 31–34), add `actor-decl` to the list:

```
  declaration = use-decl / given-block / enum-decl / open-question / config-block /
                deferred-decl / contract-decl / default-decl /
                external-entity / external-value / surface-decl / variant-decl /
                entity-decl / value-decl / rule-decl / invariant-decl /
                guarantee-decl / actor-decl
```

Then add the rule itself (place it near the surface-decl rule for proximity):

```
  (* ============ Actor ============ *)

  actor-decl = <'actor'> __ ident _ <'{'> _ actor-body _ <'}'>
  actor-body = identified-by-clause _ within-clause? _
  identified-by-clause = <'identified_by'> _ <':'> _ type-ref
  within-clause = <'within'> _ <':'> _ type-ref
```

- [ ] **Step 1.4: Add the transform**

In the `transforms` map (the big map starting line 276), add:

```clojure
   :actor-decl
   (fn [name actor-body]
     (merge {:type :actor, :name name} actor-body))

   :actor-body
   (fn [& clauses]
     (reduce (fn [acc clause] (merge acc clause)) {} clauses))

   :identified-by-clause
   (fn [type-ref] {:identified-by type-ref})

   :within-clause
   (fn [type-ref] {:within type-ref})
```

- [ ] **Step 1.5: Run the tests; expect pass**

```bash
clj -M:test -n fukan.libs.allium.parser-test/actor-decl-test
```

Expected: all three sub-cases pass.

Also run the full parser test suite to confirm no regressions:

```bash
clj -M:test -n fukan.libs.allium.parser-test
```

Expected: corpus-regression-test still passes; all other declaration tests pass.

- [ ] **Step 1.6: Commit**

```bash
jj desc -m "feat(allium): actor declarations with identified_by and within

Implements canonical Allium 'actor Name { identified_by: Type, within:
Type? }' (language ref §1660).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 2: Surface anatomy refinement

**Files:**
- Modify: `src/fukan/libs/allium/parser.clj`
- Modify: `test/fukan/libs/allium/parser_test.clj`
- Reference: [Allium language reference §"Surface structure"](../../../.claude/plugins/cache/juxt-plugins/allium/3.3.0/references/language-reference.md) line 1708.

The current parser uses generic field-entry capture for clauses inside a `surface { ... }` body, except for the explicit blocks (`provides`, `exposes`, `contracts`, `related`). The canonical surface has specific clauses with semantics:

- `facing actor: ActorType` — declares the role this surface serves. The `actor` is a *role binding name*, the `ActorType` is what's bound.
- `context entity: EntityType [where ...]` — declares the typing context this surface operates within. Has the same role-binding shape as `facing`.
- `let name = expr` — surface-internal binding. (The current parser captures `let` inside rule bodies; surfaces also accept it.)
- `timeout: RuleName` — references a fallback rule by name. Single identifier, not a type.
- `related: SurfaceName(args)` — references a peer surface with optional arguments. Currently captured as opaque text via the `related-block` rule.

The current `facing-field` and `context-field` rules (parser.clj:133–134) parse role-binding form `facing actor: User` but produce a generic `field-entry`-style AST. This task structures them as distinct fields with `:field-kind :facing` / `:context` and explicit slots.

- [ ] **Step 2.1: Write the failing tests**

Add to `test/fukan/libs/allium/parser_test.clj`:

```clojure
(deftest surface-facing-test
  (testing "facing role binding"
    (let [d (first-decl "surface Login {\n    facing actor: User\n}\n")
          facing (->> (:fields d)
                      (filter #(= :facing (:field-kind %)))
                      first)]
      (is (= :facing (:field-kind facing)))
      (is (= "actor" (:role facing)))
      (is (= {:kind :simple :name "User"} (:type-ref facing))))))

(deftest surface-context-test
  (testing "context with entity binding"
    (let [d (first-decl "surface Editing {\n    context entity: Document\n}\n")
          ctx (->> (:fields d)
                   (filter #(= :context (:field-kind %)))
                   first)]
      (is (= :context (:field-kind ctx)))
      (is (= "entity" (:role ctx)))
      (is (= {:kind :simple :name "Document"} (:type-ref ctx))))))

(deftest surface-timeout-test
  (testing "timeout clause references a rule by name"
    (let [d (first-decl "surface Op {\n    timeout: ExpireSession\n}\n")
          t (->> (:fields d)
                 (filter #(= :timeout (:field-kind %)))
                 first)]
      (is (= :timeout (:field-kind t)))
      (is (= "ExpireSession" (:rule-name t))))))

(deftest surface-related-test
  (testing "related entries reference peer surfaces"
    (let [d (first-decl "surface Read {\n    related:\n        Write\n        AdminOps\n}\n")
          rel (->> (:fields d)
                   (filter #(= :related (:field-kind %)))
                   first)]
      (is (= :related (:field-kind rel)))
      (is (vector? (:entries rel)))
      (is (= 2 (count (:entries rel))))
      (is (= "Write" (-> rel :entries first :name))))))

(deftest surface-let-test
  (testing "surface-internal let binding"
    (let [d (first-decl "surface S {\n    let admins = users.filter(role = admin)\n}\n")
          binding (->> (:fields d)
                       (filter #(= :let (:field-kind %)))
                       first)]
      (is (= :let (:field-kind binding)))
      (is (= "admins" (:name binding)))
      (is (= "users.filter(role = admin)" (:expr binding))))))
```

- [ ] **Step 2.2: Run; see failures**

```bash
clj -M:test -n fukan.libs.allium.parser-test/surface-facing-test
```

- [ ] **Step 2.3: Refine the grammar**

In `parser.clj`, find the `<field-item>` alternation rule (line 118) and the existing `facing-field` / `context-field` rules (lines 133–134). Refactor:

```
  <field-item> = provides-block / related-block / exposes-block / contracts-block
               / nested-variant / invariant-decl / annotation / when-guard
               / facing-field / context-field / timeout-field / let-field
               / field-entry

  facing-field = <'facing'> __ ident _ <':'> _ type-ref
  context-field = <'context'> __ ident _ <':'> _ type-ref
  timeout-field = <'timeout'> _ <':'> _ ident
  let-field = <'let'> __ ident _ <'='> _ rest-of-line
```

For the `related-block`, refine it to produce structured entries instead of opaque text. Replace the current rules (lines 144–146):

```
  related-block = <'related'> _ <':'> _ related-entries
  related-entries = (related-entry related-entry-sep)*
  <related-entry-sep> = <#'[ \\t]*\\n'> _
  related-entry = ident related-entry-args?
  related-entry-args = <'('> _ #'[^)]*' _ <')'>
```

(The args content stays opaque text per the no-expression-parsing rule.)

- [ ] **Step 2.4: Update / add transforms**

In the `transforms` map, replace the existing `:facing-field` and `:context-field` transforms (if present) with structured ones:

```clojure
   :facing-field
   (fn [role type-ref]
     {:field-kind :facing, :role role, :type-ref type-ref})

   :context-field
   (fn [role type-ref]
     {:field-kind :context, :role role, :type-ref type-ref})

   :timeout-field
   (fn [rule-name]
     {:field-kind :timeout, :rule-name rule-name})

   :let-field
   (fn [name expr-text]
     {:field-kind :let, :name name, :expr (str/trim expr-text)})

   :related-block
   (fn [related-entries]
     {:field-kind :related, :entries (vec related-entries)})

   :related-entry
   (fn
     ([name] {:name name})
     ([name args-text] {:name name, :args (str/trim args-text)}))
```

- [ ] **Step 2.5: Run tests; verify all 5 new sub-cases pass + corpus-regression still green**

```bash
clj -M:test -n fukan.libs.allium.parser-test
```

If the corpus-regression-test fails (which it might if a surface in `src/fukan/web/views/spec.allium` uses `related:` with the old text-only AST shape), inspect the failure and adjust the transform — the **AST shape change is intentional**; older test expectations against the text-only `related-body` should be updated where they exist.

- [ ] **Step 2.6: Commit**

```bash
jj desc -m "feat(allium): structured surface anatomy (facing/context/timeout/let/related)

Refines surface body parsing — facing, context, timeout, let, related clauses
each produce a structured field entry instead of generic field-entry capture.
Related entries are now a list of structured peer-surface refs (per language
ref §1708).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 3: Field `when` clauses

**Files:**
- Modify: `src/fukan/libs/allium/parser.clj`
- Modify: `test/fukan/libs/allium/parser_test.clj`
- Reference: [Allium language reference §"Field types"](../../../.claude/plugins/cache/juxt-plugins/allium/3.3.0/references/language-reference.md) line 333; the field-`when` clause for lifecycle-dependent presence.

A field declaration may carry a `when:` clause that gates the field's presence to a lifecycle condition. Example:

```
entity Order {
    status: pending | shipped | cancelled
    shipped_at: DateTime when: status = shipped
}
```

Grammar-wise: a field with a trailing `when: condition` should still recognise its `type-ref` and capture the condition as text.

- [ ] **Step 3.1: Write the failing test**

Add to `test/fukan/libs/allium/parser_test.clj`:

```clojure
(deftest field-when-clause-test
  (testing "field with lifecycle when:"
    (let [d (first-decl
              "entity Order {\n    status: pending | shipped\n    shipped_at: DateTime when: status = shipped\n}\n")
          f2 (-> d :fields second)]
      (is (= :typed (:field-kind f2)))
      (is (= "shipped_at" (:name f2)))
      (is (= {:kind :simple :name "DateTime"} (:type-ref f2)))
      (is (= "status = shipped" (:when f2))))))
```

- [ ] **Step 3.2: Run; see failure**

The current parser will likely fail to parse the second field cleanly (the `when:` clause has no rule to absorb it).

- [ ] **Step 3.3: Refine the field grammar**

In `parser.clj`, find the `field-value` rule (line 171). Add a `when:` suffix option on `typed-field`. The cleanest approach is to add an optional `<'when'> <':'> rest-of-line` suffix.

Add a new rule:

```
  typed-with-when = type-ref _ <'when'> _ <':'> _ rest-of-line
```

And add `typed-with-when` to the `field-value` ordered alternation BEFORE `typed-with-comment` (since `when:` is more specific):

```
  field-value = relationship / projection / typed-with-when / typed-with-comment / typed-field / derived-value
```

- [ ] **Step 3.4: Add the transform**

```clojure
   :typed-with-when
   (fn [type-ref when-text]
     {:field-kind :typed, :type-ref type-ref, :when (str/trim when-text)})
```

The existing `:field-entry` transform consumes the `field-value` and merges; verify that the `:when` slot survives into the final field map. If not, the `:field-entry` transform may need to be updated to preserve unknown keys.

- [ ] **Step 3.5: Run; expect pass + corpus regression green**

```bash
clj -M:test -n fukan.libs.allium.parser-test
```

- [ ] **Step 3.6: Commit**

```bash
jj desc -m "feat(allium): field 'when:' lifecycle clauses

Fields can carry an inline 'when: condition' suffix (canonical Allium language
ref §333). Condition is captured as text per the no-expression-parsing rule.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 4: Transition graphs

**Files:**
- Modify: `src/fukan/libs/allium/parser.clj`
- Modify: `test/fukan/libs/allium/parser_test.clj`
- Reference: [Allium language reference §"Transition graphs"](../../../.claude/plugins/cache/juxt-plugins/allium/3.3.0/references/language-reference.md) line 531.

A transition graph declares the allowed state changes for a particular enum-typed field on an entity. Form:

```
entity Order {
    status: pending | confirmed | shipped | cancelled

    transitions status {
        pending -> confirmed
        pending -> cancelled
        confirmed -> shipped
        confirmed -> cancelled
    }
}
```

The block produces a nested field entry with `:field-kind :transitions`, naming the source field (`status`) and a list of `(from, to)` edges. Both `from` and `to` are simple identifiers from the field's enum vocabulary.

- [ ] **Step 4.1: Write the failing test**

```clojure
(deftest transitions-graph-test
  (testing "transitions graph with multiple edges"
    (let [d (first-decl
              (str "entity Order {\n"
                   "    status: pending | shipped\n"
                   "    transitions status {\n"
                   "        pending -> shipped\n"
                   "        pending -> cancelled\n"
                   "    }\n"
                   "}\n"))
          tx (->> (:fields d)
                  (filter #(= :transitions (:field-kind %)))
                  first)]
      (is (= :transitions (:field-kind tx)))
      (is (= "status" (:field tx)))
      (is (= 2 (count (:edges tx))))
      (is (= {:from "pending" :to "shipped"} (first (:edges tx))))
      (is (= {:from "pending" :to "cancelled"} (second (:edges tx)))))))
```

- [ ] **Step 4.2: Run; see failure**

- [ ] **Step 4.3: Add the grammar rule**

In `parser.clj`, add the `<field-item>` alternation entry (early in the list, since `transitions` is a keyword):

```
  <field-item> = provides-block / related-block / exposes-block / contracts-block
               / nested-variant / invariant-decl / annotation / when-guard
               / facing-field / context-field / timeout-field / let-field
               / transitions-block
               / field-entry
```

Then the rule:

```
  transitions-block = <'transitions'> __ ident _ <'{'> _ transition-edges _ <'}'>
  transition-edges = (transition-edge _)*
  transition-edge = ident _ <'->'> _ ident
```

- [ ] **Step 4.4: Add the transform**

```clojure
   :transitions-block
   (fn [field-name & transition-edges]
     {:field-kind :transitions
      :field field-name
      :edges (vec transition-edges)})

   :transition-edge
   (fn [from to]
     {:from from, :to to})
```

- [ ] **Step 4.5: Verify + commit**

```bash
clj -M:test -n fukan.libs.allium.parser-test
```

```bash
jj desc -m "feat(allium): transitions graph blocks on entities

'transitions <field> { from -> to ... }' produces structured state-machine
AST per language ref §531. From/to are simple identifiers from the field's
enum vocabulary.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 5: State / temporal trigger operators

**Files:**
- Modify: `src/fukan/libs/allium/parser.clj`
- Modify: `test/fukan/libs/allium/parser_test.clj`
- Reference: [Allium language reference §"Trigger types"](../../../.claude/plugins/cache/juxt-plugins/allium/3.3.0/references/language-reference.md) line 736.

Rules can trigger on state changes (`transitions_to`, `becomes`), entity creation (`created`), or temporal conditions (`<= now`). The grammar must structure the `when:` binding form so the analyzer can see the operator + operands.

Examples:

```
rule Ship {
    when: order: Order.status transitions_to shipped
    ensures: ...
}

rule Stale {
    when: o: Order.created where age(o) > 30
    ensures: ...
}

rule Expire {
    when: s: Session.expires_at <= now
    ensures: ...
}
```

The current `trigger-binding` rule captures all of these as opaque text. This task adds structured forms.

Per the canonical reference, valid trigger operators are: `transitions_to`, `becomes`, `created`, `<= now` (temporal), plus a `derived` form for arbitrary derived conditions. The parser doesn't need to validate the operator; it just captures the structured form `(var, source, operator, operand)`.

- [ ] **Step 5.1: Write the failing tests**

```clojure
(deftest trigger-transitions-to-test
  (testing "transitions_to trigger"
    (let [d (first-decl
              "rule R {\n    when: order: Order.status transitions_to shipped\n    ensures: Ok.created()\n}\n")
          tr (-> d :clauses first :trigger)]
      (is (= :binding (:kind tr)))
      (is (= "order" (:var tr)))
      (is (= "Order.status" (:source tr)))
      (is (= "transitions_to" (:operator tr)))
      (is (= "shipped" (:operand tr))))))

(deftest trigger-becomes-test
  (testing "becomes trigger"
    (let [d (first-decl
              "rule R {\n    when: order: Order.is_paid becomes true\n    ensures: Ok.created()\n}\n")
          tr (-> d :clauses first :trigger)]
      (is (= "becomes" (:operator tr)))
      (is (= "true" (:operand tr))))))

(deftest trigger-created-test
  (testing "created trigger"
    (let [d (first-decl
              "rule R {\n    when: order: Order.created\n    ensures: Ok.created()\n}\n")
          tr (-> d :clauses first :trigger)]
      (is (= :binding (:kind tr)))
      (is (= "order" (:var tr)))
      (is (= "Order" (:source tr)))
      (is (= "created" (:operator tr)))
      (is (nil? (:operand tr))))))

(deftest trigger-temporal-test
  (testing "temporal trigger with `<= now`"
    (let [d (first-decl
              "rule R {\n    when: s: Session.expires_at <= now\n    ensures: Ok.created()\n}\n")
          tr (-> d :clauses first :trigger)]
      (is (= "<=" (:operator tr)))
      (is (= "now" (:operand tr))))))
```

- [ ] **Step 5.2: Run; see failures**

- [ ] **Step 5.3: Refine the grammar**

In `parser.clj`, find the `trigger-expr` rule (line 211) and the `trigger-binding` rule (line 213). Replace the unstructured binding with a structured form:

```
  trigger-expr = trigger-call / trigger-binding
  trigger-call = ident <'('> _ trigger-params? _ <')'>
  trigger-binding = ident _ <':'> _ binding-source _ trigger-operator _ trigger-operand?

  binding-source = dotted-path
  dotted-path = ident (<'.'> ident)*

  trigger-operator = 'transitions_to' / 'becomes' / 'created' / '<=' / '>=' / '<' / '>' / '='
  trigger-operand = #'[^\\n]+'
```

Note: `created` takes no operand (it's a self-contained "this entity was just created" marker). The grammar admits absent operand.

- [ ] **Step 5.4: Add the transforms**

```clojure
   :trigger-binding
   (fn
     ([var-name source operator]
      {:kind :binding, :var var-name, :source source, :operator operator})
     ([var-name source operator operand]
      {:kind :binding, :var var-name, :source source
       :operator operator, :operand (str/trim operand)}))

   :binding-source
   (fn [dotted-path]
     ;; dotted-path returns a vector of idents — join with .
     (str/join "." (if (vector? dotted-path) dotted-path [dotted-path])))

   :dotted-path
   (fn [& idents] (vec idents))

   :trigger-operator
   (fn [op] op)
```

- [ ] **Step 5.5: Verify + commit**

```bash
clj -M:test -n fukan.libs.allium.parser-test
```

If existing trigger-params tests still pass and the new four trigger-operator tests pass, you're done. If the old `:trigger-binding` consumer code (in transforms or callers) depended on the opaque-text shape, update it — the structured form is the new contract.

```bash
jj desc -m "feat(allium): structured state/temporal trigger operators

Trigger bindings now produce structured AST: {kind :binding, var, source,
operator, operand?} for transitions_to, becomes, created, <=, etc.
(language ref §736). Previously captured as opaque text.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 6: `for` iteration in rule ensures

**Files:**
- Modify: `src/fukan/libs/allium/parser.clj`
- Modify: `test/fukan/libs/allium/parser_test.clj`
- Reference: [Allium language reference §"Rule-level iteration"](../../../.claude/plugins/cache/juxt-plugins/allium/3.3.0/references/language-reference.md) line 715.

A rule's `ensures:` clause can iterate over a collection:

```
rule TouchAll {
    when: Refresh(items: List<Item>)
    ensures: for item in items: item.touched_at = now()
}
```

The parser already captures `ensures: ...` bodies as text via `clause-body`. This task adds a structured recognition of the leading `for X in Y:` so the analyzer can dispatch on iteration explicitly.

**Scope note:** the body of the `for` (the bit after the colon) stays as opaque text — this is the no-expression-parsing rule. The parser just splits "for-iteration prefix" from "iteration body text."

- [ ] **Step 6.1: Write the failing test**

```clojure
(deftest ensures-for-iteration-test
  (testing "ensures: for x in coll: body"
    (let [d (first-decl
              (str "rule R {\n"
                   "    when: R(items: List<Item>)\n"
                   "    ensures: for item in items: item.touched_at = now()\n"
                   "}\n"))
          ens (-> d :clauses (nth 1))]
      (is (= :ensures (:clause-type ens)))
      (is (= :for-iteration (:kind ens)))
      (is (= "item" (:var ens)))
      (is (= "items" (:collection ens)))
      (is (= "item.touched_at = now()" (:body ens))))))
```

- [ ] **Step 6.2: Run; see failure**

- [ ] **Step 6.3: Refine the rule grammar**

In `parser.clj`, find the `ensures-clause` rule (line 220). Refactor:

```
  ensures-clause = <'ensures:'> _ (ensures-for / ensures-expr) _
  ensures-for = <'for'> __ ident __ <'in'> __ ident _ <':'> _ clause-body
  ensures-expr = clause-body
```

(`requires-clause` could be similarly extended later if `for` appears in requires; not in scope for this task per gap analysis.)

- [ ] **Step 6.4: Add the transforms**

```clojure
   :ensures-clause
   (fn [body-or-for]
     ;; body-or-for is either a clause-body string (plain ensures) or
     ;; the result of ensures-for (a map).
     (if (map? body-or-for)
       (merge {:clause-type :ensures} body-or-for)
       {:clause-type :ensures, :body (str/trim body-or-for)}))

   :ensures-for
   (fn [var-name collection body]
     {:kind :for-iteration
      :var var-name
      :collection collection
      :body (str/trim body)})

   :ensures-expr
   (fn [body] (str/trim body))
```

- [ ] **Step 6.5: Verify + commit**

```bash
clj -M:test -n fukan.libs.allium.parser-test
```

```bash
jj desc -m "feat(allium): structured 'for' iteration in rule ensures clauses

ensures: for var in collection: body  ->  {:clause-type :ensures,
:kind :for-iteration, :var, :collection, :body}. Body stays text per
the no-expression-parsing rule (language ref §715).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 7: `for` quantification in top-level invariants

**Files:**
- Modify: `src/fukan/libs/allium/parser.clj`
- Modify: `test/fukan/libs/allium/parser_test.clj`
- Reference: [Allium language reference §"Top-level invariants"](../../../.claude/plugins/cache/juxt-plugins/allium/3.3.0/references/language-reference.md) line 1289.

Top-level invariants commonly use `for` quantification over entity sets:

```
invariant LeafNodesHaveNoChildren {
    for n in Node where n.kind = leaf: n.children = []
}
```

The current `invariant-decl` rule captures the entire body via the balanced-chunk text-capture mechanism. This task structures the leading `for X in Y:` similarly to Task 6, then captures the remaining body as text.

The reference also notes "nested quantification" (`for a: for b: ...`) — for Plan 2a, we structure only the outermost `for` and let nested ones live in the body text (the analyzer parses them if needed).

- [ ] **Step 7.1: Write the failing test**

```clojure
(deftest invariant-for-quantification-test
  (testing "top-level invariant with for-quantification"
    (let [d (first-decl
              (str "invariant LeafNoChildren {\n"
                   "    for n in Node where n.kind = leaf: n.children = []\n"
                   "}\n"))]
      (is (= :invariant (:type d)))
      (is (= "LeafNoChildren" (:name d)))
      (is (= :for-quantification (-> d :body :kind)))
      (is (= "n" (-> d :body :var)))
      (is (= "Node" (-> d :body :source)))
      (is (= "n.kind = leaf" (-> d :body :guard)))
      (is (= "n.children = []" (-> d :body :assertion))))))

(deftest invariant-expression-body-test
  (testing "invariant body without for-quantification stays as text"
    (let [d (first-decl
              "invariant TotalIsPositive {\n    Order.total > 0\n}\n")]
      (is (= :expression (-> d :body :kind)))
      (is (= "Order.total > 0" (-> d :body :text))))))
```

- [ ] **Step 7.2: Run; see failures**

- [ ] **Step 7.3: Refine the grammar**

In `parser.clj`, find the `invariant-decl` rule (lines 103–108). Refactor:

```
  invariant-decl = <'invariant'> __ ident _ description-string? _ <'{'> _ invariant-form _ <'}'>
  invariant-form = invariant-for-quantification / invariant-expression
  invariant-for-quantification = <'for'> __ ident __ <'in'> __ ident _ (<'where'> __ invariant-guard)? _ <':'> _ invariant-assertion
  invariant-guard = #'(?:(?!\\b:|where\\b).)+'        (* up to the colon *)
  invariant-assertion = balanced-chunks-text
  invariant-expression = balanced-chunks-text

  (* re-using the existing balanced-chunk infrastructure for both branches *)
  balanced-chunks-text = invariant-chunk+
```

Keep the existing `inv-brace-group` / `inv-paren-group` / `inv-text-chunk` rules for the inner balanced-chunk captures.

> **Implementation note:** the `invariant-guard` regex needs to terminate at the closing colon of the `for-quantification`. The implementer should test the regex carefully — invariants like `for n in Node: n.children = []` (no `where`) must parse cleanly with `:guard` absent.

- [ ] **Step 7.4: Add the transforms**

```clojure
   :invariant-decl
   (fn [name & rest]
     (let [[description form] (if (string? (first rest))
                                rest
                                [nil (first rest)])]
       (cond-> {:type :invariant, :name name, :body form}
         description (assoc :description description))))

   :invariant-for-quantification
   (fn
     ([var-name source assertion]
      {:kind :for-quantification
       :var var-name
       :source source
       :assertion (str/trim (text-of assertion))})
     ([var-name source guard assertion]
      {:kind :for-quantification
       :var var-name
       :source source
       :guard (str/trim guard)
       :assertion (str/trim (text-of assertion))}))

   :invariant-expression
   (fn [body]
     {:kind :expression
      :text (str/trim (text-of body))})

   :balanced-chunks-text
   (fn [& chunks] (apply str chunks))
```

Where `text-of` is a helper (likely already exists in the parser; if not, add it as a private `defn-`) that concatenates balanced-chunk parse results.

- [ ] **Step 7.5: Verify + commit**

```bash
clj -M:test -n fukan.libs.allium.parser-test
```

This task is the most ambiguous of Plan 2a; expect to iterate on the grammar to avoid parsing conflicts with the existing balanced-chunk text-capture. The corpus has 5 invariants in `src/fukan/model/spec.allium` — verify they all parse correctly under the new shape.

```bash
jj desc -m "feat(allium): structured 'for' quantification in top-level invariants

invariant Name { for x in Source [where guard]: assertion }  -> structured
{:kind :for-quantification, :var, :source, :guard?, :assertion}. Plain
expression invariants stay as {:kind :expression, :text} (language ref §1289).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 8: Entity-level invariants

**Files:**
- Modify: `src/fukan/libs/allium/parser.clj`
- Modify: `test/fukan/libs/allium/parser_test.clj`
- Reference: [Allium language reference §"Entity-level invariants"](../../../.claude/plugins/cache/juxt-plugins/allium/3.3.0/references/language-reference.md) line 1315.

An entity can carry invariants in its body:

```
entity Order {
    total: Money
    items: List<LineItem>

    invariant TotalIsPositive {
        total > 0
    }
}
```

The grammar's `invariant-decl` rule already lives in the `<field-item>` alternation (line 118), so a top-level `invariant` rule reused inside `field-list` should work syntactically. The transform must distinguish "invariant as top-level declaration" from "invariant as entity-internal field-item" — both produce `:type :invariant` but the nested one carries `:field-kind :invariant` for the parent entity's `:fields` list.

- [ ] **Step 8.1: Write the failing test**

```clojure
(deftest entity-level-invariant-test
  (testing "invariant nested inside an entity body"
    (let [d (first-decl
              (str "entity Order {\n"
                   "    total: Money\n"
                   "    invariant TotalIsPositive {\n"
                   "        total > 0\n"
                   "    }\n"
                   "}\n"))
          inv (->> (:fields d)
                   (filter #(= :invariant (:field-kind %)))
                   first)]
      (is (= :invariant (:field-kind inv)))
      (is (= "TotalIsPositive" (:name inv)))
      (is (= :expression (-> inv :body :kind)))
      (is (= "total > 0" (-> inv :body :text))))))
```

- [ ] **Step 8.2: Run; see failure**

The Task 7 changes already produce `:body :kind :expression` for the inner shape. What's missing is the `:field-kind :invariant` discriminator when an invariant appears inside `field-list`.

- [ ] **Step 8.3: Add a wrapper transform**

In the grammar, the existing `<field-item>` alternation includes `invariant-decl` — so nested invariants ARE parsed by the same rule. The issue is the transform map produces `{:type :invariant, ...}` (a top-level declaration shape), not `{:field-kind :invariant, ...}` (a field-item shape).

The cleanest fix: when an invariant-decl is consumed as a field-item, wrap it. Update the `:field-entry`-style alternation handler — actually since instaparse just returns the raw transformed value into the parent's `:fields` list, we need to map the `:type :invariant` shape into `:field-kind :invariant` shape at the point of inclusion.

One option: in the entity / value / variant transforms (which compose the `:fields` vector), detect invariant-decl shapes in the field list and rewrite them. Specifically in `:entity-decl`, `:value-decl`, `:variant-decl`:

```clojure
(defn- field-itemise
  "If a field-item is an invariant-decl-shaped map (:type :invariant),
   convert to field-kind shape."
  [item]
  (if (= :invariant (:type item))
    (-> item (dissoc :type) (assoc :field-kind :invariant))
    item))

(defn- ->fields [field-items]
  (mapv field-itemise (vec field-items)))
```

Update the transforms for `:entity-decl`, `:value-decl`, `:variant-decl` to use `->fields` when composing `:fields`. Likewise `:surface-decl` (in case a surface carries invariants — verify against canonical reference).

- [ ] **Step 8.4: Run; expect pass**

```bash
clj -M:test -n fukan.libs.allium.parser-test
```

- [ ] **Step 8.5: Commit**

```bash
jj desc -m "feat(allium): entity-level invariants

Invariants declared inside entity/value/variant bodies are now structured
as field-items with :field-kind :invariant (language ref §1315). Top-level
invariants are unchanged.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 9: Config block structured parsing

**Files:**
- Modify: `src/fukan/libs/allium/parser.clj`
- Modify: `test/fukan/libs/allium/parser_test.clj`
- Reference: [Allium language reference §"Config"](../../../.claude/plugins/cache/juxt-plugins/allium/3.3.0/references/language-reference.md) line 1443.

Config blocks declare module-level parameters:

```
config {
    timeout: Duration = 30s
    retry_count: Integer = 3
    db_url: String
}
```

The current grammar treats the body as opaque text (`#'[^}]*'`). This task structures the parameter list: each entry has a name, an optional type, and an optional default (default stays as text per the no-expression-parsing rule).

- [ ] **Step 9.1: Write the failing tests**

```clojure
(deftest config-block-test
  (testing "config with typed parameter and default"
    (let [d (first-decl "config {\n    timeout: Integer = 30\n}\n")]
      (is (= :config (:type d)))
      (is (= 1 (count (:params d))))
      (let [p (first (:params d))]
        (is (= "timeout" (:name p)))
        (is (= {:kind :simple :name "Integer"} (:type-ref p)))
        (is (= "30" (:default p))))))

  (testing "config with no default"
    (let [d (first-decl "config {\n    db_url: String\n}\n")]
      (is (= :config (:type d)))
      (let [p (first (:params d))]
        (is (= "db_url" (:name p)))
        (is (= {:kind :simple :name "String"} (:type-ref p)))
        (is (nil? (:default p))))))

  (testing "config with multiple parameters"
    (let [d (first-decl
              (str "config {\n"
                   "    timeout: Integer = 30\n"
                   "    retries: Integer = 3\n"
                   "    db_url: String\n"
                   "}\n"))]
      (is (= 3 (count (:params d)))))))
```

- [ ] **Step 9.2: Run; see failures**

- [ ] **Step 9.3: Replace the opaque `config-body` with structured rules**

In `parser.clj`, find the `config-block` rule (lines 56–57) and replace:

```
  config-block = <'config'> _ <'{'> _ config-params _ <'}'>
  config-params = (config-param _)*
  config-param = ident _ <':'> _ type-ref (_ <'='> _ rest-of-line)?
```

- [ ] **Step 9.4: Replace the transform**

Replace the existing `:config-block` transform with:

```clojure
   :config-block
   (fn [& params]
     {:type :config, :params (vec params)})

   :config-param
   (fn
     ([name type-ref]
      {:name name, :type-ref type-ref})
     ([name type-ref default-text]
      {:name name, :type-ref type-ref, :default (str/trim default-text)}))
```

- [ ] **Step 9.5: Verify + commit**

```bash
clj -M:test -n fukan.libs.allium.parser-test
```

```bash
jj desc -m "feat(allium): structured config block parameter parsing

config { name: Type [= default-expr] ... }  ->  {:type :config, :params [{:name
:type-ref :default?} ...]} (language ref §1443). Default expressions stay as
text per the no-expression-parsing rule.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 10: Annotation prose-body capture

**Files:**
- Modify: `src/fukan/libs/allium/parser.clj`
- Modify: `test/fukan/libs/allium/parser_test.clj`
- Reference: [Allium language reference §"Prose-only vs expression-bearing invariants"](../../../.claude/plugins/cache/juxt-plugins/allium/3.3.0/references/language-reference.md) line 1370; the `@invariant`, `@guarantee`, `@guidance` annotations.

Annotations have two forms:

```
@invariant TotalIsPositive
```

vs:

```
-- prose explanation lives in the leading comment block
@guarantee Idempotent
```

The prose explanation lives in the contiguous block of `--`-prefixed lines *immediately preceding* the `@<name>` line, with no blank line between them. The current parser captures the annotation as `{:kind ... :name ...}` but discards the prose. This task adds prose capture.

**Implementation strategy:** the parser already has `<line-comment>` as a hidden whitespace token (line 238). To capture the leading comment block as an annotation's `:body`, we need to *not* hide it before annotations. The cleanest approach: add a new rule `annotation-with-prose` that matches `(line-comment-as-prose-line)* annotation`. Distinguish between "annotations with leading prose" and "annotations without."

- [ ] **Step 10.1: Write the failing tests**

```clojure
(deftest annotation-with-prose-test
  (testing "@guarantee with leading comment block"
    (let [d (first-decl
              (str "surface Login {\n"
                   "    facing actor: User\n"
                   "    -- the operation completes within the timeout\n"
                   "    -- regardless of which path was taken\n"
                   "    @guarantee Idempotent\n"
                   "}\n"))
          ann (->> (:fields d)
                   (filter #(= :annotation (:field-kind %)))
                   first)]
      (is (= :annotation (:field-kind ann)))
      (is (= "guarantee" (:kind ann)))
      (is (= "Idempotent" (:name ann)))
      (is (= "the operation completes within the timeout\nregardless of which path was taken"
             (:body ann))))))

(deftest annotation-no-prose-test
  (testing "@guidance with no leading comment block"
    (let [d (first-decl
              (str "surface S {\n"
                   "    facing actor: U\n"
                   "    @guidance\n"
                   "}\n"))
          ann (->> (:fields d)
                   (filter #(= :annotation (:field-kind %)))
                   first)]
      (is (= :annotation (:field-kind ann)))
      (is (= "guidance" (:kind ann)))
      (is (nil? (:body ann))))))
```

- [ ] **Step 10.2: Run; see failures**

- [ ] **Step 10.3: Refine the annotation grammar**

In `parser.clj`, find the `annotation` rule (line 137). Replace with:

```
  annotation = annotation-prose? annotation-mark
  annotation-prose = (prose-line)+
  prose-line = <#'[ \\t]*--[ \\t]?'> #'[^\\n]*' <'\\n'>
  annotation-mark = <'@'> ident (__ ident)?
```

**Critical:** the `<line-comment>` rule in `<_>` (line 238) needs to NOT consume `--` lines that are part of an `annotation-prose` block. Two options:

(a) Re-order: try `annotation` before `<_>` consumes the leading whitespace.
(b) Tighten `line-comment` to only match `--` lines NOT immediately followed by `@`.

Option (b) is more robust. Replace:

```
  <line-comment> = <#'--[^\\n]*\\n'>
```

with:

```
  <line-comment> = <#'--[^\\n]*\\n(?![ \\t]*@)'>
```

(The negative lookahead `(?![ \t]*@)` means "this `--` line is a regular comment iff NOT immediately followed by another `--` line preceding an `@`." Verify with a quick scratch test that this regex matches what we want.)

> **Implementation note:** instaparse's combinator regex support depends on the underlying regex engine. Verify lookaheads work; if not, restructure by having `annotation-prose` greedily consume the contiguous `--` block, and ensure the `<_>` whitespace-eater doesn't consume `--` lines BEFORE annotations get a chance to match. The implementer may need to iterate.

- [ ] **Step 10.4: Update the transform**

```clojure
   :annotation
   (fn
     ([mark] (merge {:field-kind :annotation} mark))
     ([prose mark]
      (merge {:field-kind :annotation, :body prose} mark)))

   :annotation-prose
   (fn [& lines] (str/join "\n" (map str/trim lines)))

   :annotation-mark
   (fn
     ([kind] {:kind kind})
     ([kind name] {:kind kind, :name name}))
```

- [ ] **Step 10.5: Verify + commit**

```bash
clj -M:test -n fukan.libs.allium.parser-test
```

Pay attention to the corpus-regression-test — annotations exist in `src/fukan/web/spec.allium`. Verify they parse with the new shape; if the new shape breaks consumer assumptions elsewhere, surface them.

```bash
jj desc -m "feat(allium): annotation prose-body capture

@kind Name with a leading contiguous '--' comment block now produces
{:field-kind :annotation, :kind, :name?, :body?} where body is the joined
prose. Standalone @kind with no prose still works (language ref §1370).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 11: Backtick enum literals + corpus regression smoke

**Files:**
- Modify: `src/fukan/libs/allium/parser.clj`
- Modify: `test/fukan/libs/allium/parser_test.clj`
- Reference: [Allium language reference §"Field types"](../../../.claude/plugins/cache/juxt-plugins/allium/3.3.0/references/language-reference.md) line 333; enums permitting backtick-quoted literals.

Allium permits backtick-quoted values inside enums (for literals containing special characters):

```
enum Locale {
    `en-US` | `de-CH-1996` | en_GB
}
```

The current enum grammar treats values as plain `ident` (line 75). This task widens enum-value lexing.

Plus this task is the **final corpus-regression smoke**: verify the full new parser parses every `.allium` file in `src/` cleanly with the structured AST.

- [ ] **Step 11.1: Write the failing test**

```clojure
(deftest backtick-enum-literal-test
  (testing "enum value with backtick-quoted literal"
    (let [d (first-decl "enum Locale { `en-US` | de_DE | `de-CH-1996` }\n")]
      (is (= :enum (:type d)))
      (is (= ["en-US" "de_DE" "de-CH-1996"] (:values d))))))
```

- [ ] **Step 11.2: Run; see failure**

- [ ] **Step 11.3: Widen the enum-values grammar**

In `parser.clj`, find the `enum-values` rule (line 75) and add a backtick-literal alternative:

```
  enum-values = enum-value (_ <'|'>? _ enum-value)*
  enum-value = backtick-literal / ident
  backtick-literal = <'`'> #'[^`]+' <'`'>
```

- [ ] **Step 11.4: Add the transform**

```clojure
   :enum-value
   (fn [v] v)

   :backtick-literal
   (fn [content] content)
```

The existing `:enum-values` transform should still work since both `backtick-literal` and `ident` resolve to strings.

- [ ] **Step 11.5: Run the full test suite (final corpus smoke)**

```bash
clj -M:test
```

Expected: all parser tests pass (Plan-1 tests + Plan-2a additions, which add at least 14 new deftests across Tasks 1–11) plus the corpus-regression-test passes for all 5 `.allium` files in `src/`. Tasks 2–10 may have broken some Plan-1-era tests if the AST shape changed (e.g. `surface` `related:` clauses); fix those tests inline if they assert against the old text-only shape.

Total test count should be **113 (Plan 1) + ~14 (Plan 2a unit tests) = ~127 tests**, with possibly some Plan-1-era parser tests updated to match new structured ASTs (count adjusted accordingly).

- [ ] **Step 11.6: Commit**

```bash
jj desc -m "feat(allium): backtick enum literals + final corpus smoke

Enum values now accept backtick-quoted literals for special-character cases
(e.g. \`de-CH-1996\`, language ref §333). All 5 .allium files in src/ parse
clean under the new canonical-coverage grammar.

Closes Plan 2a (Allium parser completion).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Self-review

After completing all 12 tasks (Task 0 through Task 11), verify before declaring Plan 2a done:

1. **Canonical reference coverage.** Skim each section of the [Allium language reference](../../../.claude/plugins/cache/juxt-plugins/allium/3.3.0/references/language-reference.md). For each structural construct, point to a Plan-2a task that addressed it. The expression language (sections §964–§1282) is intentionally OUT of scope — confirm Plan-2b will handle.

2. **Corpus parses cleanly.** `corpus-regression-test` passes for all 5 files. No structural ambiguity warnings from instaparse.

3. **AST shape consistency.** Run `structural-assertions-test` (existing Plan-1-era test) against every file — every declaration has `:type`; every field-item has `:field-kind`; every type-ref has `:kind`. Add any new field-kind keywords introduced (`:facing`, `:context`, `:timeout`, `:let`, `:related`, `:transitions`, `:invariant`, `:annotation`) to the expected-discriminators allowlist in that test if needed.

4. **No expression parsing.** Grep the grammar for any new rules that look like they're parsing expression internals (arithmetic, comparison, navigation chains, function calls). Plan 2a only structures *containers* around expression text; expression bodies stay text-captured. If you find expression-parsing creep, revert it.

5. **No grammar ambiguity.** Run instaparse with `:total true` (or examine the parse tree on each corpus file) to confirm no rule is producing multiple valid parses. If instaparse reports ambiguity, restructure the alternation order or tighten regex bounds.

6. **Test discipline.** Every new construct has a per-construct deftest in `parser_test.clj`. Tests came BEFORE grammar rules (TDD). New tests don't break older tests.

7. **Plan-of-plans consistency.** Plan 1's plan file references "Plan 2a" and "Plan 2b" (Task 0 updated this). The list of 7 plans is consistent.

8. **MODEL.md doc fix verified.** §8.1's row reads `let x = e`, not `where: x = e`. The tag name is `Allium::Let`.

9. **Full test suite green.**

   ```bash
   clj -M:test
   ```

10. **REPL still boots.**

    ```bash
    clojure -M:dev -e "(require '[fukan.infra.model :as m]) (m/load-model \"src\") (println :model (some? (m/get-model)))"
    ```

    Expected: `:model true` (the Plan-1 fixture loader is untouched by Plan 2a; the parser changes don't affect runtime model loading until Plan 2b lands).

11. **VCS state.** `jj log -r '::@' --limit 15` shows 12 new Plan-2a commits on top of Plan 1. No orphan fix-up commits; each task is one logical change.

If any check fails, fix in place — do **not** start Plan 2b until the parser is clean.
