# Issue #10 — local task engine slice

This source overlay is copied into the packaged Android project by scripts/repair_sources.py during the existing PR #9 build. It adds a separate Room database (jarvis_tasks.db) so the existing graphify.db schema and recovery path remain untouched.

The only registered capability is local.plan-preview. It persists a Turkish plan draft, then waits for user review. Accepting the plan moves the task to waiting_for_network because no cloud/specialist executor is configured. No SMS, call, message, purchase, account change, video render, or public upload is executed by this slice.

Tests cover state transitions, persistence, safe process-restart recovery, cancellation, retry behavior when no executor is registered, and a launcher Activity visibility assertion (not just process liveness).
