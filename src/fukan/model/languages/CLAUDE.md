# Language Analysis Implementation Guide

Read these specs for context:

- `spec.md` — language analysis specification (this module)
- `../spec.md` — graph model specification (what analysis data feeds into)

## Key Pattern: Raw Data Only

Language modules produce raw data. The build pipeline (`model.build`) assembles it into the graph. Do not construct node hierarchies or compute edges in language modules.

## Analysis vs Schema Discovery

These are two distinct operations:

- **Analysis** (`run-kondo`): Static — invokes an external tool on source files, parses its output. No runtime state needed.
- **Schema discovery** (`discover-schema-data`): Runtime — scans loaded namespaces for `^:schema` metadata. Requires namespaces to be loaded in the JVM.

## Schema Ref Extraction

Schema cross-references are extracted by parsing the schema source text (not the evaluated form). This preserves symbol references that would be resolved away in the evaluated form. The two-phase collect-then-extract approach ensures forward references are handled correctly.

## Adding a New Language

A new language module should provide:
1. An analysis function returning `AnalysisData` (ns-defs, var-defs, var-usages, ns-usages)
2. Optionally, a type-nodes function for language-specific node kinds (like schema nodes for Clojure)
