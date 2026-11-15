# Goal continuity engine

The ledger is conversation-scoped SharedPreferences (`goal_continuity_v1`), with synchronous checkpoints. Existing v1 ledgers remain readable. This extends the existing Android runtime; it does not replace ReplyRuntime or create a second background service.

## Actual behavior

- The model interprets intent in context and calls `manage_goal`. The local intent policy remains a conservative guard, not a complete semantic classifier. Negation, quoted/hypothetical instructions and appearance changes cannot authorize cancellation/replacement through the covered patterns.
- `branch` preserves the original step. Branch steps do not overwrite that checkpoint. `finish_branch` records a conclusion and restores it. Overlapping branches are rejected rather than silently replacing the checkpoint.
- Before ending a response, the shared provider loop offers queued user messages first, then a bounded continuity reminder. Paused/cancelled goals do not get a branch reminder. The model still decides whether to execute, research or report a blocker. Provider round limits continue to apply.
- Four consecutive bookkeeping calls get one stagnation reminder. This is not a general detector for repetitive research or non-tool reasoning.
- Tool dispatch first saves a pending operation. The actual runtime result then creates a UUID receipt with tool name, goal epoch, result hash and RETURNED/FAILED. Raw arguments/results are not copied into this ledger. RETURNED is not proof of business success; some older tools report failures in text rather than `failed`.
- `complete` requires existing non-failed receipt IDs from this goal and no unresolved write operation. It records NEEDS_VERIFICATION, never verified success. User text `验收通过` enables `confirm_complete` to record COMPLETED.
- Unknown side-effect outcomes survive restart and goal replacement. Identical tool+canonical-argument replay is blocked. The user can explicitly say `确认重试`; `confirm_retry` releases one pending operation ID. This is a local guard, not distributed exactly-once execution; altered arguments can represent the same external operation.
- Known read-only tools can retry. Orphan read checkpoints are removed at the next turn; concurrent read receipts use distinct IDs.
- No automatic network replay is introduced. After app restart the existing retry/continue flow reloads the ledger. Android process survival remains subject to OS restrictions.

## Verification

`GoalIntentPolicyTest`, `GoalLedgerTest`, and `GoalTurnGuardTest` run in the check-branch Android workflow alongside existing regressions. The ledger tests use a memory Store and the real JSON library, covering restoration, isolation, stale/fabricated/failed evidence, durable pending operations, argument order, read concurrency, bounded reminders, acceptance, corrupt storage and write failures. They do not replace real-device lifecycle tests.

## Device acceptance

1. Start a development task, record a step, ask for a comparison. Confirm the goal is unchanged and a branch conclusion is followed by mainline work or a specific blocker.
2. During work say “先暂停” or explicitly replace the goal. Confirm no automatic return overrides the new instruction. Test quoted, hypothetical and negated cancellation phrases.
3. Run two conversations concurrently; verify each status and evidence list belongs to its own goal.
4. Switch to another app, interrupt the network, then force-stop/relaunch. Inspect the saved step before continuing; do not infer completed actions from a restored plan.
5. Interrupt a side-effecting tool. Repeating identical arguments must be blocked until its unknown outcome is addressed. A read-only query must remain retryable.
6. Ask to complete without evidence, with a made-up ID, a failed ID, or an ID from another goal. All must be refused. Valid returned evidence only reaches pending verification; explicit user acceptance reaches completed.
7. Use both Anthropic-style and OpenAI-compatible providers. Confirm each model call retains its existing thinking boundary and queued messages are processed before continuity reminders.

## Remaining limits

Full semantic intent arbitration, criterion-specific machine verification, generic research stagnation detection, remote idempotency and automated process-restart/device validation remain separate work. Do not present this release as full coverage of those requirements.
