# Kavya Agentic Assistant Foundation

This document defines the integration plan for bringing robust agent-style workflows into Kavya. It is an implementation guide, not a claim that these capabilities are already wired into the app.

## Reference and attribution

Agent-workflow concepts were reviewed from the JARVIS Android project by Tech GPTx: https://github.com/techgptx/Playlist (especially Part 7). This document is newly written for Kavya; it does not copy the source implementation.

The reference repository's LICENSE (TAMCL v1.0) specifies source attribution and special requirements for digital media that use, demonstrate, showcase, review, fork, or build upon the project/codebase/architecture/concept. Before shipping or publishing a derivative, review that license and satisfy its conditions. For applicable videos/posts, it calls for verbal credit, source credit in the first three description lines, and visible on-screen credit.

## Desired capabilities

1. **Goal intake and scope classification** — normalize the user's request; distinguish one-item tasks, bounded bulk tasks, and searches; ask for confirmation before consequential or ambiguous actions.
2. **Explicit task plan** — represent work as steps with stable IDs, dependencies, expected results, actual results, timestamps, retry counts, and status.
3. **Observe → plan → act → verify** — inspect fresh app/device state before selecting a target, execute one action, then verify the resulting state before marking progress.
4. **Single action gate** — serialize UI mutations; associate actions with a task generation so stale work from a replaced/cancelled task cannot run.
5. **Pause, resume, cancel** — expose controls and make cancellation stop queued work. On resume, observe the current screen again and re-resolve targets rather than blindly replaying old coordinates.
6. **Recovery and replanning** — use bounded retries, detect repeated/no-progress screen states, and re-plan from fresh observations instead of looping forever.
7. **Evidence-based completion** — do not report success just because an action API returned true. Require a locally observable goal condition; otherwise report blocked, uncertain, or failed with a reason.
8. **Bulk-task deduplication** — keep stable item identities/signatures so the same item is not processed repeatedly; stop when the end condition is verified or a hard safety limit is reached.
9. **Live progress UI** — show current task, current step, completed/pending steps, percentage, last observation/action, retries, and the reason a task stopped.
10. **Session lifecycle** — clear active task state and cancel pending actions when the assistant session ends or restarts.
11. **Safety boundaries** — require explicit user authorization for external side effects; confirm purchases, messages, deletions, and other high-impact actions; respect app permissions and authentication boundaries; never bypass security controls.
12. **Diagnostics and tests** — structured redacted logs, deterministic fake action adapters, tests for cancellation races, pause/resume, ambiguous targets, no progress, retries, and false completion.

## Suggested architecture

Keep platform-independent orchestration separate from app integrations:

- `GoalParser`: normalizes intent and classifies scope.
- `TaskPlanner`: returns a dependency-aware list of steps.
- `TaskCoordinator`: owns the active task and its state transitions.
- `ObservationProvider`: returns a fresh, minimal representation of current state.
- `ActionAdapter`: performs one explicitly allowed action.
- `ActionVerifier`: checks postconditions using fresh observations.
- `SerializedActionExecutor`: serializes mutations and rejects stale task generations.
- `RecoveryPolicy`: applies bounded retry/backoff and no-progress detection.
- `TaskProgressStore`: publishes immutable snapshots to UI.
- `TaskProgressPanel`: presents progress, pause/resume, and cancel controls.

Use interfaces for observation and action so tests can run without controlling a real device. Keep secrets out of logs and never persist raw screen contents unless the user has explicitly enabled a suitable, privacy-reviewed feature.

## Task lifecycle

```text
IDLE → UNDERSTANDING → PLANNING → OBSERVING → RESOLVING_TARGET
     → ACTING → VERIFYING → CONTINUING → COMPLETED
                         ↘ RECOVERING → REPLANNING
Any active state → PAUSED / BLOCKED / FAILED / CANCELLED
```

Only transition to `COMPLETED` when the verifier returns goal-specific evidence. A maximum-step or timeout limit is a stop condition, not proof of success.

## Integration sequence

1. Inspect Kavya's existing language, app entry points, permissions, storage, and UI before choosing concrete files.
2. Add domain models and state-machine unit tests first.
3. Introduce observation/action interfaces and a serialized executor.
4. Connect one low-risk workflow end-to-end and require verified postconditions.
5. Add cancellation, pause/resume, stale-action rejection, bounded recovery, and progress UI.
6. Add bulk processing only after stable identity, deduplication, and end-condition verification are tested.
7. Run the existing build and test suite; document permissions, limitations, and attribution before release.

## Acceptance checklist

- [ ] Every active task has a unique ID and explicit state.
- [ ] Every action is tied to the current task generation.
- [ ] No UI mutation runs concurrently with another mutation.
- [ ] Resume always obtains a fresh observation before the next action.
- [ ] Failed/unverified actions never advance a step to completed.
- [ ] Retries and loops have hard limits.
- [ ] Bulk actions deduplicate items and verify the stopping condition.
- [ ] User can pause and cancel; cancellation prevents queued actions from running.
- [ ] Progress UI distinguishes success, failure, blocked, and uncertain outcomes.
- [ ] Automated tests cover race conditions and false-success cases.
