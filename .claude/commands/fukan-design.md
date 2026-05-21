---
name: fukan-design
description: High-altitude design conversation grounded in the fukan model.
context: fork
agent: fukan-architect
argument-hint: [topic-or-scope]
---

High-altitude design conversation. Argument is a path (consult that scope) or a free-text question (engage on that question).

**Argument:** $ARGUMENTS

## How to interpret `$ARGUMENTS`

- **Path** — if `$ARGUMENTS` resolves as a relative-or-absolute filesystem path (e.g. `src/fukan/model/`, `src/fukan/web/handler.allium`), treat it as a scope. Query the model for primitives owned by that scope and engage about its design.
- **Free-text** — anything else is a design question. Engage on that question, gathering model state as needed.
- **Empty** — invite the user to provide a path or a question before continuing.

You cannot read the path's contents directly; treat it as an owner filter when querying the model.

## Workflow

1. **Orient.**
   - Path argument → `(primitives :kind …)` filtered client-side by owner (or L0 Datalog `(q '[:find ?p :where [?p :primitive/owner "<owner-id>"]])`) plus `(neighborhood …)` to pull in the scope's primitives and their one-hop context.
   - Free-text argument → `(vocabulary)`, `(drift)`, `(violations)`, `(idioms)` as the question demands. Don't over-fetch; pull what the conversation needs.
2. **Engage.** Reason from what the model returned. If the user asks for a proposal, frame it as one and present tradeoffs.
3. **Surface gaps.** When a question can't be answered from the model, name it as a model-completeness gap and stop. Don't guess past the model. A gap is a finding worth flagging — the reconciler's audit (`/fukan-audit`) is what closes it.
4. **No file artefacts.** The conversation is the output. If the user wants a proposal preserved, they capture it themselves.
