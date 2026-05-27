# Phase 5 Trial Run — findings

**Date:** 2026-05-27
**Subsystem authored:** `canvas/distributed/` (Raft-flavoured leader election, 3 modules)

## What was built

Three modules sketching the design of a Raft-style replicated state machine at canvas altitude — not an implementation, a design surface that names the system's essential state, the protocol events, and the safety invariants the protocol must protect.

- **`canvas/distributed/cluster.clj`** — the essential state every node must remember about the cluster: `NodeId` and `Term` as opaque `value` types, `NodeRole` as a documented value (Follower/Candidate/Leader), `Node` and `Cluster` records. Three `invariant` declarations: `AtMostOneLeaderPerTerm`, `TermMonotonicity`, `MajorityRequiredForLeadership`. Three `getter`s (`get_current_term`, `get_current_leader`, `get_self_role`) for zero-arg state reads, plus parametrised `function`s `get_node` and `members`. Lifts used: `function`, `record`, `value`, `exports`, `invariant`, `getter`.
- **`canvas/distributed/election.clj`** — the leader-election protocol. `Vote` and `ElectionRound` records carrying cross-module refs to `cluster/Term` and `cluster/NodeId`. Seven `event`s naming the protocol's wire vocabulary (`HeartbeatExpired`, `ElectionStarted`, `VoteRequested`, `VoteGranted`, `VoteDenied`, `LeaderElected`, `HeartbeatReceived`). Four `handler`s wiring the reactive protocol. Three `invariant`s naming the safety properties that bind cluster + election together. Lifts used: `record`, `exports`, `invariant`, `event`, `handler`.
- **`canvas/distributed/log.clj`** — the replicated log. `LogIndex` and `Command` as opaque values, `LogEntry` and `Log` as records. Three replication events (`ClientCommandReceived`, `AppendEntriesRequested`, `AppendEntriesAcknowledged`, `EntryCommitted`). Three handlers for the leader/follower exchange. Four invariants for log safety (`LogAppendOnly`, `LogMatching`, `CommitIndexMonotonic`, `LeaderCompleteness`). Lifts used: `function`, `record`, `value`, `exports`, `invariant`, `event`, `handler`, `getter`.

**No new lifts were invented.** Every shape the design needed was covered by the existing vocabulary. The `:patterns` lens confirmed this at the end of the trial: my events clustered as "already lifted by `vocab.event/event`", my handlers as "already lifted by `vocab.event/handler`", and my invariants joined the 276-member cluster already covered by `vocab.behavioral/invariant`. Rule-of-three did not trigger anywhere in the distributed subsystem.

Module breakdown stayed faithful to the original sketch — cluster/election/log felt like the right partition during authoring, and nothing about the loop pushed me to redraw the boundaries.

## The loop in practice — turn-by-turn observations

### Round 1 — `cluster.clj`

- **Orient.** Skimmed `demo/event-driven/order.clj` and `payment.clj` for cross-module ref style, read the EXAMPLES for `construction`, `behavioral`, `lifecycle`, and the shape grammar. The EXAMPLES were unambiguous and short — I leaned on them rather than searching for in-canvas examples. Useful.
- **Edit.** Authored straightforwardly. The "Hickey check" — *is this essential state, or accidental representation?* — caught me before I added a `voted_for` field to `Cluster`. That belongs to the election protocol, not the cluster's essential state. I moved that thought into the election module's handler descriptions. This was the single most useful design-altitude reflex the system prompt produced in this trial.
- **Reflect.** `(integrity)` returned `[]`. `(canvas-coverage)` returned `:warning`-tier findings only: exported types not yet referenced, two pure read-accessor affordances orphaned. Both expected — exports are by definition reach-targets for sister modules, and pure read accessors have no synchronous caller until projection lands. The severity ladder was exactly right.

### Round 2 — `election.clj`

- **Orient.** Re-read `demo/event-driven/payment.clj` for the `(handler (on ...) (emits ...))` form. Sketched the seven events and four handlers on paper before authoring.
- **Edit.** Reached for `event` and `handler` immediately — the system prompt's "reach for existing vocabulary first" was active but didn't slow me down because the vocabulary fit. Authored with `:distributed.cluster/NodeId`-style refs (matching the module name dot-for-dot).
- **Reflect.** Integrity exploded with **36 `:severity :error` findings**: every cross-module ref to `:distributed.cluster/X` failed to resolve, *and* every same-module ref like `:distributed.election/LeaderElected` failed too. I had used a dotted namespace (`distributed.cluster`) but the canvas-source resolver matches the *last segment* of the module name, not the full dotted form. The CLAUDE.md note ("the namespace portion is the last path segment of the canvas module that owns the type") said this explicitly — I had misread it.

  Two pieces of evidence the loop earned its weight here:

  1. The integrity finding was instant, precise, and actionable. Every offender named both source and target, so a single sed across the file fixed all 36 in one step.
  2. The demos under `demo/event-driven/*` use the same dotted form (`:event-driven.cart/LineItem`) — meaning **the demos would also fail integrity if they ran through it**. They don't, because they are not registered in `canvas-source/canvas-namespaces`. This is a real, undocumented divergence between the demos and the canvas integrity invariant. Flagging it here — see Recommendations.

  Re-ran. Integrity clean. Coverage showed handlers orphaned (no synchronous caller — expected for event-driven) and the `event-without-handler` warnings cleared as expected.

### Round 3 — `log.clj`

- **Orient.** Quick re-read of cluster.clj + election.clj to remind myself of the cross-module ref convention I'd just learned. Sketched the records + events on paper.
- **Edit.** Wrote it in one pass. The convention was now internalised; no integrity surprises this round.
- **Reflect.** `(integrity)` returned `[]` on the first try. `(canvas-coverage)` returned 19 warnings, all expected: orphan handlers, exported leaf types, two pure read affordances without callers. No errors.

### Final survey

After all three modules landed, ran `(survey)` against the full lens set.

- **`:patterns`** — Surfaced two distributed-specific clusters: cluster 7 (7 events) and cluster 11 (4 handlers), both correctly labelled "already lifted by `vocab.event/{event,handler}`". The 3 distributed.cluster invariants joined the giant 276-member invariant cluster — confirming I had not invented anything that needs lifting. *Signal.*
- **`:consistency`** — Flagged the `distributed.` prefix's sister-module asymmetry: cluster has getters and a function but no events/handlers; election has events and handlers but no getters; log has all of these. The "majority shape" computed across the three is met by none of them. **This finding is technically correct and substantively wrong** — the three modules serve different concerns and *should* differ structurally. But noticing the lens noticed it was useful: it forced me to articulate the reason for the divergence (different concerns, intentional asymmetry) and confirm I hadn't accidentally drifted between modules. The :id-with-:NodeId-type collision was noise; `Node.id` should be `NodeId`, full stop.
- **`:tar-pit`** — See closing self-survey.

## Loop quality

The loop felt like a thinking-enhancing tool more than ceremony. Three concrete moments where signal improved design:

1. **The "reach for existing vocab first" anchor caught a near-miss in cluster.clj.** I was about to add a `voted_for` field directly to `Cluster`. Pausing on "is this essential cluster state?" surfaced that voting is election-protocol state, not cluster state. The Tar-Pit framing — essential vs accidental — did real work here.
2. **The cross-module ref convention was caught by trust-tier on the first reflect.** I would have shipped the file with broken refs otherwise. The 36 errors collapsed to a single rule (use module's last segment, not full dotted path) and a single global replace.
3. **The consistency lens's sister-symmetry finding forced an articulation.** I had to justify why my three modules diverge structurally. That articulation surfaced clearly *because* the lens prompted it. Even though the finding was technically a false positive against my design intent, the act of resolving it produced a sharper understanding of what each module is for.

Where the loop was ceremony rather than signal:

- The 200+ KB tar-pit and patterns survey output is mostly canvas-wide noise from outside my scope. There is no first-class "scope this survey to module X or prefix Y" affordance, so I had to filter with `(filter #(re-find #"distributed" ...))`-style ad-hoc grep. For a focused authoring session, the lens output is at the wrong granularity by default.
- The coverage check's `exported-but-unreferenced` warning for *every* exported type at the leaf of the subsystem is structurally inevitable for the outermost module in a build-up trial run. It's correct but pre-known; in a sketch-from-scratch scenario most of these warnings are not actionable.

## Where the loop pinched

- **Refresh-vs-restart.** `(refresh)` does not pick up newly-loaded namespaces — it rebuilds the model from already-required code. Adding a new canvas file requires restarting the daemon, which takes ~10–20s and breaks flow. The CLAUDE.md note covers this ("After adding a new canvas file: add a require entry … then use `(reset)`"), and `(reset)` is the documented escape valve — but `bin/fukan` exposes only `status`, `eval`, `primer`, not `reset`. I had to `pkill` + restart from the shell. A first-class `fukan reset` command (or making `(refresh)` do an idempotent code-reload + model-rebuild) would smooth the loop materially.
- **Registry update is a manual step.** Each new canvas file requires two edits to `src/fukan/canvas/projection/canvas_source.clj` (the `:require` form and the explicit registry vector). This is mechanical but easy to miss; the canvas-source module could auto-discover `canvas/**/*.clj` files via classpath scanning, eliminating the dual-edit step. (Phase 6 candidate.)
- **Cross-module ref convention is not strongly signalled at edit time.** The CLAUDE.md mention is one line; the EXAMPLES files do not explicitly call out the single-segment rule; the demos *use the dotted form*. An authoring agent that learns from the demos will be wrong. This bit me on round 2. A documentation fix would help; a lint that flagged dotted ref keywords at edit time would be better.
- **Scoping the survey.** Already covered above — no `(survey :scope "distributed")` affordance.

## What surprised me about applying the loop to a real subsystem

- The `:patterns` lens **confirmed my design discipline rather than directing it**. I expected the lens to surface candidate lifts; instead its main contribution was telling me my work was already-lifted — i.e. that I had reached for existing vocab successfully. That's an unusual shape for a feedback tool: it's reassuring, not directing. Reassurance is a real signal when you're authoring under pressure to do something novel and the loop's job is to tell you "no, don't invent yet".
- The Tar-Pit lens output is **mostly canvas-wide context, not a focused analysis**. The lens computes a slice and asks me to answer three questions; the analysis is mine to produce. This is exactly the trust/weigh discipline — the lens does not give a verdict — but the cost is that the most valuable part (the analysis) is the part the lens cannot help with. The frame is useful; the slice is largely scenery; the work is human.
- I needed **the integrity check more than I expected**. Half my "design effort" in round 2 was actually a rote convention I had misread. The trust-tier signal converted that from a hidden bug into a 30-second fix. Without it I'd have had broken refs across 36 sites that look fine at read time.

## Recommendations for Phase 6+

1. **Make `(reset)` callable from `bin/fukan`** (or make `(refresh)` reload newly-added namespaces). The current restart cycle is the loop's single biggest friction point.
2. **Auto-discover canvas files** in `canvas/**/*.clj`. The explicit registry under `canvas-source/canvas-namespaces` is a maintenance burden with no benefit relative to convention-based discovery. The demos already exist outside the registry; the canvas tree itself should not need one.
3. **Document the single-segment cross-module ref convention loudly.** Either: add a callout block to `core/shape.md` and every vocab EXAMPLES.md; or change the resolver to *also* accept dotted forms (`:distributed.cluster/NodeId` → match module `"distributed.cluster"`). The latter is more flexible; the former is cheaper. Either way the demos' use of dotted refs needs reconciling with the integrity invariant — currently the demos would fail integrity if they were checked.
4. **Add `(survey :scope <prefix-or-module>)`** so weigh-tier output can be focused on the slice an author is working in. The current default firehose is at the wrong granularity for in-flight authoring.
5. **Consider a `:coupling` or `:dependency` lens.** During the trial I noticed I was holding the cluster/election/log dependency graph entirely in my head — there is no weigh-tier observation about "are these three modules coupled the way you expect?" The patterns and consistency lenses look at shape, not at the dependency wiring. An author asking "did I get the layering right?" has no lens for it.
6. **Trust-tier severity-ladder is well-judged.** Errors caught the real bug; warnings were correctly non-blocking. Keep the partition.

## Closing self-survey through Tar-Pit

Applied to `canvas/distributed/`, Moseley & Marks's distinction yields a clean and useful read:

**Essential state.** `NodeId`, `Term`, `LogIndex`, `Command`, `LogEntry`, the entry list inside `Log`, and the `members` set inside `Cluster` are all essential — they are what the system *must remember* to do its job. Strip away protocol, transport, and timers and these survive. `NodeRole` is borderline: it is essential to the consensus algorithm specifically, but in a more declarative framing the role could be *derived* from observed votes and heartbeats rather than stored. The fact that I made it stored, rather than derived, is a representation choice — accidental complexity in the Tar-Pit sense, even if it is the conventional Raft framing. A maximally-declarative rewrite would compute `NodeRole` from history.

**Accidental representation.** The `ElectionRound` record is the strongest accidental-complexity candidate in the subsystem: it is bookkeeping a candidate maintains *during* an election to track grants. In a more relational framing, the candidate could simply query "how many vote-grant events have I received for this term?" — `ElectionRound` exists because we have made the candidate carry the tally. Useful for implementation, weak in the design. Similarly the `current_leader` field on `Cluster` is derivable from the most recent `LeaderElected` event the node has observed; storing it duplicates state. The design honours essential-minimisation *partly* — events are first-class, invariants are named — but it concedes to imperative representation in a few spots where pure derivation would have read more cleanly.

**Effects.** The Tar-Pit summary reports **100% pure-function fraction across the entire canvas** including my subsystem — no function carries an `(effect ...)` declaration. This is partly an artefact (the canvas at this altitude does not yet model effects on the protocol functions; transport, persistence, and timing live below the surface), but it does reflect the canvas's actual stance: effects are projected, not declared. The distributed subsystem inherits this — no `(effect ...)` was needed because every commitment was expressed declaratively, either as an invariant or as a handler-on-event arrow.

**The headline finding.** *The design names its safety properties explicitly as invariants — that is the most Tar-Pit-aligned thing the subsystem does.* Ten invariants across three modules (`AtMostOneLeaderPerTerm`, `TermMonotonicity`, `MajorityRequiredForLeadership`, `OneVotePerVoterPerTerm`, `VoteImpliesTermAcknowledgement`, `ElectionRequiresStrictMajority`, `LogAppendOnly`, `LogMatching`, `CommitIndexMonotonic`, `LeaderCompleteness`) carry the essential domain logic at the declarative tier rather than burying it inside handler bodies. A more imperative canvas of the same domain would have left these as comments or unstated. The presence of named invariants — the lens's strongest essential-complexity signal — is evidence the canvas vocabulary is doing exactly what it was built to do: it lets the design speak in the register of *what the system must promise*, not *how the system will compute it*.

The lens does not say the design is right; it says the design is thinking in the right register. That is the right verdict for a trial-run sketch.
