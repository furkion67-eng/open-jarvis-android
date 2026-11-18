# JARVIS System Architecture (Proposal)

Status: draft proposal for Issue #10. This document defines a target architecture; it does not claim that these capabilities are already implemented.

## Product goal

Build a Turkish-first Android assistant that accepts voice and text requests, plans multi-step work, invokes only authorized device actions and specialist workflows, survives interruptions, and reports verifiable outcomes. The Android app is the user's control surface; long-running or cloud-dependent work may use a separately configured backend. A successful APK build is not proof that a user workflow works.

## 1. Product principles

- **User intent first:** understand the goal, ask only for missing information, and show a plan for consequential or ambiguous work.
- **Truthful status:** distinguish planned, running, waiting for approval, completed, failed, and unverified. Never report success from an API request alone if the external result is not confirmed.
- **Human approval for side effects:** require an explicit, action-specific confirmation immediately before sending messages, placing calls, publishing content, spending money, changing accounts, or submitting orders.
- **Least privilege:** each tool/agent receives only the permissions and data needed for its task.
- **Resumable execution:** persist task state and checkpoints so work can resume after process death, device restart, or network loss.
- **Cost and safety limits:** expose paid API/cloud usage and enforce configured budgets; never spend or subscribe without approval.
- **Modular implementation:** agent workflows are capabilities registered behind stable contracts, not hard-coded prompt branches.
- **Turkish by default:** user-facing plans, confirmations, errors, and results are natural Turkish; provider/model output must be normalized before display or speech.

## 2. Logical components

### Android client
- Voice/text input, permission onboarding, task timeline, approval screen, artifact preview, and settings.
- Device-action adapter for supported Android intents/APIs (app launch, contacts, SMS/call handoff, notifications, files, microphone).
- Foreground service only for user-visible, permitted ongoing work. Wake-word listening is an explicit opt-in mode with a visible status and battery disclosure; do not promise unrestricted always-on behavior.
- Local persistence/outbox for pending tasks and results when offline.

### Orchestrator
- Converts a request into a typed task plan.
- Selects a registered capability based on intent, required inputs, permissions, cost, and availability.
- Tracks dependencies, checkpoints, retries, cancellation, deadlines, and approvals.
- Routes device-local actions to Android adapters and cloud work to configured services.
- Validates output against the task contract before marking a task successful.

### Agent registry
Each specialist is registered with:
- stable capability ID and version;
- supported intents and excluded intents;
- required input schema and output schema;
- allowed tools/data scopes and approval requirements;
- estimated cost class and timeout/retry policy;
- validation rules and test fixtures.

Initial capability families (not necessarily separate autonomous processes):
1. Research and summarization.
2. Video script/storyboard and asset/licensing review.
3. Video rendering, Turkish narration, captions, and export.
4. YouTube/TikTok draft and publishing integration.
5. Marketing and analytics.
6. E-commerce research and catalog/workflow assistance.
7. Personal Android actions.

Do not create a new agent when an existing capability can safely handle the work.

### Backend / integrations
- Optional API gateway and job worker for tasks that cannot reliably run on-device.
- OAuth integrations use official provider flows and narrowly scoped tokens.
- Secrets stay in server-side secret storage or GitHub Actions secrets; never in APK assets, source files, prompts, or logs.
- Backend availability is not assumed: app must clearly show when cloud connectivity or credentials are missing.

### Observability and control center
- Task queue and task detail.
- Agent/capability status and last heartbeat where available.
- Progress/checkpoints, timestamps, retry count, and concise activity log.
- Pending approvals, costs/usage, generated artifacts, and failure diagnostics.
- Cancel/retry controls and safe export of diagnostic bundles.

## 3. Task contract and lifecycle

A task record should include:
- task_id, user_goal, normalized_intent, created_at, updated_at;
- status, priority, requested_by, current_step, dependency_ids;
- assigned_capability and capability_version;
- input references (avoid duplicating sensitive content);
- output/artifact references and validation results;
- approval requirement, approval decision, and approval timestamp;
- retry_count, timeout/deadline, error_code, safe_error_summary;
- execution location (device/cloud) and connectivity requirements.

Allowed states:
- `queued`: accepted and waiting for execution;
- `planning`: plan being constructed or clarified;
- `running`: a step is executing;
- `waiting_for_user`: approval or missing information required;
- `waiting_for_network`: safely paused pending connectivity;
- `succeeded`: output contract and success evidence validated;
- `failed`: terminal failure with actionable explanation;
- `cancelled`: user or policy cancelled execution.

State transitions must be explicit. A task cannot move from `running` to `succeeded` merely because a tool call returned HTTP 200. External effects require a provider/device receipt or other appropriate confirmation. Retries must be idempotent or guarded against duplicate messages/posts/orders.

## 4. Execution flow

1. Capture Turkish voice/text input and preserve the user's original wording for context.
2. Resolve intent, entities, missing inputs, risk level, and whether the request is device-local or cloud-dependent.
3. Build a short plan with steps, dependencies, expected artifacts, estimated cost, and required approvals.
4. Ask for clarification only when a necessary value is missing or ambiguous.
5. Persist the task and enqueue it.
6. Dispatch each step to the registered capability with a scoped execution context.
7. Validate outputs and checkpoint progress after each step.
8. If an external side effect is next, present the exact recipient/content/destination/cost and request explicit confirmation.
9. Execute only after approval; record provider/device confirmation.
10. Persist artifacts and final result; show a Turkish summary with evidence and any remaining limitations.

Example: “Bu konuda kısa video hazırla”
- Research and source review -> Turkish script -> storyboard/assets -> render vertical video with narration/subtitles -> local preview/export.
- Stop at draft by default. Uploading to a public channel is a separate step requiring explicit channel/account selection and confirmation.
- Report copyright/licensing uncertainty and any paid service cost before proceeding.

## 5. Approval and permission model

Approval is scoped to one clearly described action and expires if its payload changes. Approval screen must show:
- what will happen;
- recipient/account/platform or destination;
- exact message or content where applicable;
- amount/cost where applicable;
- whether the action is public or reversible.

Never infer approval from a generic “do it” if the actual payload, recipient, price, or public destination was not shown. Denial or timeout leaves the task paused/cancelled without performing the action.

## 6. Reliability, offline behavior, and Android lifecycle

- Persist task metadata and a durable outbox locally.
- Use unique idempotency keys for cloud requests and provider actions where supported.
- Backoff retries for transient network/server failures; do not retry irreversible actions blindly.
- Resume from the last validated checkpoint after process restart.
- Use Android WorkManager for deferrable work and a foreground service only for eligible, user-visible ongoing work.
- Explain Android 13 and TECNO/HiOS battery/background restrictions in setup/help; request user action rather than silently bypassing OS restrictions.
- Offline mode supports local UI, saved history, drafts, and device-local capabilities that do not require network. Cloud tasks wait and resume when connectivity returns.

## 7. Privacy, security, and cost controls

- Keep OAuth tokens, API keys, passwords, and personal data out of logs and repository history.
- Redact message bodies, contact details, and sensitive prompt content from diagnostic logs.
- Encrypt sensitive local data where feasible and define retention/deletion behavior.
- Use capability-scoped credentials and short-lived tokens.
- Set configurable per-task and monthly cost limits; require approval before paid API use when cost is not already authorized.
- Never claim guaranteed income. Track revenue workflows using measured costs, conversion, and platform policy constraints.

## 8. Test strategy and acceptance gates

### Unit tests
- intent routing and missing-input handling for Turkish utterance variants;
- task state transition validity;
- dependency ordering, retry/backoff, cancellation, and checkpoint recovery;
- idempotency and duplicate-side-effect prevention;
- approval required/denied/expired/payload-changed cases;
- output schema and result validation.

### Android integration tests
- permission granted/denied/revoked flows;
- foreground service start/stop and notification behavior;
- app launch, contact ambiguity, SMS preview/confirmation, and call handoff;
- Turkish STT/TTS fallback and error messages;
- process death/restart and offline outbox recovery.

### End-to-end gates
- User request -> plan -> execution -> approval (when required) -> verified result -> persisted history.
- Fresh install and upgrade launch on Android 13 emulator.
- Legacy/corrupt database recovery.
- Video draft export with audio/video synchronization and readable captions.
- Private/unlisted publishing test where official API and account configuration permit it.
- Physical TECNO Spark 20 Pro / HiOS testing is a separate release gate; emulator results must not be presented as physical-device validation.

## 9. Ownership boundaries

| Workstream | Primary owner | Deliverable |
|---|---|---|
| Product architecture, contracts, sequencing, independent acceptance review | Chief architect | This architecture, decisions, acceptance criteria, coordination |
| Android app implementation and user flows | Code agent | Small feature branches and PRs with tests |
| CI, emulator, secrets, build artifacts, release plumbing | Infrastructure agent | Reproducible workflows and evidence |
| User | Approver | Account linking, device permissions, paid usage, public/external actions |

Avoid parallel edits to the same files. Architecture decisions should be recorded before implementation tasks are split. Do not merge or release automatically.

## 10. Delivery sequence

1. Stabilize Android 13 startup and establish repeatable emulator evidence.
2. Make foreground service lifecycle and Turkish voice/action routing truthful and testable.
3. Implement task persistence, typed lifecycle, and a minimal task timeline.
4. Add orchestrator + capability registry with one safe end-to-end workflow.
5. Add approval gates and Android action adapters.
6. Add video draft production and local export.
7. Add official publishing integrations with OAuth and explicit confirmation.
8. Expand specialist capabilities, cost controls, analytics, and recovery.

## Current implementation status

This is a proposed target design. The document itself does not implement the orchestrator, wake-word listener, video renderer, publishing APIs, or control-center UI. Track implementation through linked issues and PRs, and update this section only with verifiable repository/CI/device evidence.
