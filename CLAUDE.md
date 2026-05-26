# Fukan

Fukan is a structural exploration tool for codebases in the era of LLM-driven development. It analyzes a target codebase — implementation code and behavioral specifications — to build a unified structural model, then projects and renders that model as an interactive graph in the browser. The core question it explores: as LLMs handle more low-level coding, how do humans maintain control over high-level structure and collaborate with LLMs at that level of abstraction?

The system is generic and pluggable: language analyzers (currently Clojure) register via multimethod dispatch, and the build pipeline is language-agnostic. Specifications and implementation are projected onto the same model so that intended structure (boundaries, contracts, guarantees) and actual structure (namespaces, functions, schemas) appear together. Documentation is a first-class input — it flows into the model to make the explorer meaningful, not just structural.

The system follows a functional core / imperative shell architecture, enforced by canvas specs.

## Querying the Model as an agent

Fukan exposes its Model to coding agents through `bin/fukan`. When working on or with Fukan, prefer querying the spec graph over grepping the codebase.

- **Primer:** [AGENTS.md](AGENTS.md) — read it before the first agent query. Covers the `fukan.agent.system` / `fukan.agent.api` surface, L0/L1/L2 query layering, the edit→refresh→query loop, `.fukan/agent-views.clj`, and sandbox limits.
- **Quick reference:** `fukan status` (daemon health), `fukan eval '<expr>'` (run a query), `fukan primer` (print AGENTS.md). Requires a running daemon (`clj -M:run …`).
- **Live catalog:** inside `fukan eval`, call `(help)` for the current fn surface — trust it over `AGENTS.md` if they disagree.

## Spec Locations (source of truth)

Specs now live at `canvas/<subsystem>/<module>.clj`. Canvas specs are the sole spec source as of Phase 3 Sprint 4.

Legacy `.allium` / `.boundary` files are archived in `.legacy-allium/` (read-only reference; not loaded by the build pipeline).

Sprint 5 will do the full vision-doc rewrite. For spec authoring guidance see `doc/DESIGN.md`.
