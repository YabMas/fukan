# Module Review Command

`$ARGUMENTS` is the **module path** (e.g. `src/fukan/projection/`). No flags.

---

## Dispatch

### Step 1: Analysis

Spawn a Task (subagent_type: `general-purpose`) with the full **Analysis Protocol** below, substituting `<MODULE>` with the module path.

Pass the entire Analysis Protocol text as the task prompt — do not summarize it.

### Step 2: Present Report

Present the subagent's report to the user **verbatim**.

### Step 3: Approval — Doc Alignment

If the report contains proposals prefixed `D1`, `D2`, etc. under "Proposed Doc Alignment":

1. List them by ID with a one-line summary of each.
2. Ask the user which to approve. Accept numbers (e.g. `D1, D3`), `all`, or `none`.

If no doc proposals exist, skip this step.

### Step 4: Approval — Code Improvements

If the report contains proposals prefixed `I1`, `I2`, etc. under "Proposed Improvements":

1. List them by ID with a one-line summary of each.
2. Ask the user which to approve. Accept numbers (e.g. `I1, I2`), `all`, or `none`.

If no code proposals exist, skip this step.

### Step 5: Implementation

If any proposals were approved in steps 3 or 4:

1. Spawn a second Task (subagent_type: `general-purpose`) with the **Implementation Protocol** below, substituting `<MODULE>` with the module path and `<APPROVED>` with the full text of only the approved proposals.
2. Present the implementation summary to the user.

If nothing was approved, say so and finish.

---

## Analysis Protocol (pass to subagent 1)

You are reviewing the module at `<MODULE>`. You are **read-only** — never write or edit any files.

### Phase 1: Bootstrap

Read the following files from `<MODULE>` (skip any that don't exist):
- `CLAUDE.md`
- `contract.edn`
- `spec.allium`

Then glob `<MODULE>**/*.clj` and read **every** `.clj` file in the module.

Also read the root `CLAUDE.md` for project-wide conventions.

### Phase 2: Contract Verification (REPL-assisted)

Discover the nREPL port:
```bash
clj-nrepl-eval --discover-ports
```

Use port 7889 (or whatever is discovered). For all REPL evaluations, use `clj-nrepl-eval -p <port>`.

**2a. Verify every contract function exists.**

Read `contract.edn`. For each symbol in `:functions`:
```bash
clj-nrepl-eval -p 7889 "(do (require '<ns> :reload) (boolean (resolve '<fully-qualified-sym>)))"
```
If result is `false` → record as **ERROR**: "Contract lists nonexistent var `<sym>`"

**2b. Discover unlisted public vars.**

For each namespace that appears in `contract.edn` `:functions`:
```bash
clj-nrepl-eval -p 7889 "(do (require '<ns> :reload) (vec (keys (ns-publics '<ns>))))"
```
Compare against contract. Any public var NOT in contract → record as **WARNING**: "Public var `<ns>/<name>` not in contract"

Ignore vars that are clearly internal utilities re-exported by accident (use judgment), but still report them.

**2c. Check schema annotations on contract functions.**

For each contract function:
```bash
clj-nrepl-eval -p 7889 "(some? (:malli/schema (meta #'<ns>/<name>)))"
```
If `false` → record as **WARNING**: "Contract function `<sym>` lacks `:malli/schema` annotation"

### Phase 3: Documentation Alignment

**3a. CLAUDE.md function signatures vs actual arglists.**

For each function mentioned in CLAUDE.md with a signature, verify via REPL:
```bash
clj-nrepl-eval -p 7889 "(-> #'<ns>/<name> meta :arglists str)"
```
Compare against what CLAUDE.md claims. Mismatch → **WARNING**.

**3b. File list accuracy.**

Glob `<MODULE>**/*.clj` and compare against any file listing in CLAUDE.md. Missing or extra files → **WARNING**.

**3c. spec.allium data shapes vs code.**

Look for `^:schema` defs or Malli schema definitions in the code. Compare their shape against what spec.allium describes. Significant mismatches → **WARNING**.

### Phase 4: Implementation Review (holistic)

Read all the code you collected in Phase 1. Step back and evaluate the module as a whole. This is not a line-level lint. Consider:

**Architectural fitness**
- Does the file decomposition still make sense? Are responsibilities in the right files?
- As the module has grown, have some files taken on too many concerns?
- Are there implicit groupings of functions that would be better as explicit files/namespaces?

**Abstraction quality**
- Are abstractions at the right level? Too high (over-engineered) or too low (repeating patterns)?
- Are there missing abstractions that would simplify multiple call sites?
- Are there leaky abstractions where internal details bleed into callers?

**Data flow clarity**
- Can you trace data through the module easily?
- Are transformation steps clear and predictable?
- Are there confusing intermediate representations?

**Complexity assessment**
- Is complexity concentrated where it belongs (core algorithm) or spread thin?
- Are there simpler formulations of the same logic?
- Could a different approach eliminate accidental complexity?

**Public interface evaluation**
- Is the contract the right set of entry points?
- Are the function signatures ergonomic for callers?
- Could the API be simpler while serving the same purpose?

**Evolution trajectory**
- Given how the module has grown, are there structural decisions becoming technical debt?
- Would a restructure now prevent compounding complexity later?

The conclusion may well be "the implementation is clean and well-suited" — be honest, not forced.

### Phase 5: Report

Format your report exactly as:

```
## Module Review: <MODULE>

### Summary
- Errors: N | Warnings: N | Suggestions: N

### Errors
- [ ] description (file:line)

### Warnings
- [ ] description (file:line)

### Suggestions
- [ ] description (file:line)

### Contract Diff
;; Add: <symbols that are public but not in contract>
;; Remove: <symbols in contract but not in code>
;; Make private: <symbols that probably shouldn't be public>

### Implementation Assessment
<Free-form paragraph(s) giving the holistic view of the module's health.
Is the current structure the right one? What's working well? What could
be better? Concrete reasoning, not vague advice.>
```

Omit any section that has zero items (except Summary and Implementation Assessment, which are always present).

### Phase 6: Proposed Changes

After producing the report, propose concrete changes in two categories. Use the prefix IDs exactly as shown — the orchestrator parses them.

**6a. Proposed Doc Alignment**

For each doc/contract issue found in phases 2-3, propose a specific fix:

```
### Proposed Doc Alignment

**D1. <Title>**
**What:** <specific change to contract.edn or CLAUDE.md>
**File:** <file path>

**D2. <Title>**
...
```

Only propose changes to `contract.edn` and `CLAUDE.md`. Never propose changes to `spec.allium` or `.clj` files in this section.

If no doc alignment issues exist, omit this section entirely.

**6b. Proposed Improvements**

Based on the Implementation Assessment and any issues found, propose concrete code improvements:

```
### Proposed Improvements

**I1. <Title>**
**What:** <specific change>
**Why:** <reasoning>
**Files:** <file paths and approximate line ranges>
**Result:** <what the code would look like after>

**I2. <Title>**
...
```

Each proposal should be concrete and implementable. Include:
- Restructuring proposals (file splits, function moves)
- Functions to make private
- Dead code to remove
- Abstractions to introduce or remove
- Simplifications to complex logic

If no improvements are warranted, omit this section entirely.

---

## Implementation Protocol (pass to subagent 2)

You are implementing approved changes for the module at `<MODULE>`.

### Approved Proposals

<APPROVED>

### Rules

1. **Doc proposals** (D-prefixed): edit only `contract.edn` and `CLAUDE.md` within `<MODULE>`. Do not touch `.clj` files or `spec.allium`.
2. **Code proposals** (I-prefixed): edit the `.clj` files specified in the proposal. Do not touch `contract.edn`, `CLAUDE.md`, or `spec.allium`.
3. After all edits, reload via REPL:
   ```bash
   clj-nrepl-eval -p 7889 "(reload/reload)"
   ```
4. If reload fails, diagnose and fix the issue before continuing.
5. Return a structured summary:

```
### Implementation Summary

#### Applied
- <ID>: <one-line description of what was done>

#### Skipped
- <ID>: <reason if any proposal could not be applied>

#### Reload Status
<success or failure details>
```

---

## Severity Reference

- **error**: Contract lists nonexistent var; broken invariant
- **warning**: Unlisted public var; missing schema; doc/code mismatch; missing docstring on contract function
- **suggestion**: Complexity candidate; naming convention deviation; dead code suspect; potential simplification
