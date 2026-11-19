# Issue #10 — local task engine slice

This source overlay is copied into the packaged Android project by scripts/repair_sources.py during the existing Android build. It adds a separate Room database (jarvis_tasks.db) so the existing graphify.db schema and recovery path remain independent.

The current registry still contains only local.plan-preview; it does not provide a cloud/device executor. A task is persisted, receives a Turkish plan draft, and waits for the user to review it. Accepting the plan records planAcceptedAt and a plan_reviewed checkpoint, then waits for a configured executor. No SMS, call, message, purchase, account change, video render, or public upload is executed by this slice.

Execution safety:
- Plan review is a distinct, one-time compare-and-set checkpoint.
- The DAO atomically claims a waiting task only when the accepted-plan checkpoint is present and any separate side-effect approval is already granted.
- Only the caller that changes the task to running may invoke the executor.
- The persisted idempotency key is passed to the executor/API boundary and must remain stable across retries.
- Execution results update only a task that is still running, so a cancellation cannot be overwritten.
- Room migration 1→2 adds nullable planAcceptedAt; legacy tasks are not treated as already accepted.
- The UI offers “Planı kabul et” only for a newly created, unaccepted plan. An accepted plan waiting for a separate external approval is shown as a distinct state without a generic approval button.
- local.plan-preview cards are explicitly labeled as previews. When no executor is configured, the UI says “Yürütülmedi · yürütücü yok” and does not offer a misleading retry action.

Tests cover state transitions, plan-acceptance gating, concurrent duplicate execution, approval separation, idempotency-key reuse, v1→v2 migration, persistence, safe process-restart recovery, cancellation, retry behavior, Compose approval-state presentation and launcher visibility.

This remains a local/demo planning slice until a real, separately reviewed executor is configured and produces verifiable evidence.
