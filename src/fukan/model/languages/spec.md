# Language Analysis Specification

## Purpose

Language-specific analysis: source code -> raw analysis data + schema data.

Language modules are responsible for extracting structured data from source code. They produce raw data; the model build pipeline assembles it into the graph.

## Analysis Pipeline

1. **Run external analyzer** on source path (e.g., static analysis tool)
2. **Parse output** into structured data
3. **Return** four collections:
   - `namespace-definitions` — one per namespace found
   - `var-definitions` — one per var/function defined
   - `var-usages` — cross-var references (which var uses which)
   - `namespace-usages` — cross-namespace references (require/import)

This output conforms to the `AnalysisData` schema expected by the build pipeline.

## Schema Discovery

Schema discovery scans runtime metadata for vars marked with `^:schema`:

1. **Collect phase**: Walk all loaded namespaces, find vars with `^:schema` metadata, collect their names into a set
2. **Extract phase**: For each schema var, extract:
   - The schema form (the var's value)
   - The owning namespace
   - Cross-references to other schemas (by walking the schema form for symbols matching collected names)

The two-phase approach handles forward references — all names are known before ref extraction begins.

## Schema Node Construction

Given schema data and a namespace index (ns-sym -> node-id):

- Create one node per schema, with kind `:schema`
- Parent each schema node under its owning namespace's node
- Store schema form, owner namespace, and schema refs in the node's data map

These nodes are then incorporated into the model by the build pipeline alongside folder, namespace, and var nodes.

## Boundary

Language modules produce raw data only. They do not:
- Construct the node hierarchy (that's the build pipeline)
- Compute edges between nodes (that's the build pipeline)
- Filter or transform for display (that's the projection layer)
