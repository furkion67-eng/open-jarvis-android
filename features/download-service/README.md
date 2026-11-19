# DownloadService lifecycle hardening

This overlay replaces the generated `com.openjarvis.local.DownloadService` implementation while preserving the existing manifest declaration and `dataSync` foreground-service type.

## Runtime guarantees

- Every `onStartCommand` path calls `startForeground` before request validation or asynchronous work. Missing tier, invalid tier, null/unknown action and cancel cannot return from a foreground-service start without promotion.
- Missing and invalid tiers return distinct Turkish error notifications and stop only the matching `startId`.
- Cancel invalidates the active generation before cancelling the coroutine.
- A new download invalidates the previous generation before cancelling/replacing its job.
- Progress, completion, error notification and cleanup from an older generation cannot remove the newer run's foreground notification or stop its service start.
- Cleanup uses `stopSelfResult(startId)`, never unqualified `stopSelf()`.
- Cancellation is rethrown rather than converted into a failed-download notification.

## Tests

JVM tests cover missing tier, invalid tier, unknown/null action, valid tier, cancel before a download, cancel followed by a new start, and stale completion after a newer start ID.

The Android manifest/foreground-service permission and service type remain owned by the infrastructure integration checks and are not changed here.

## Device acceptance

Physical TECNO Spark 20 Pro / Android 13 / HiOS service lifecycle acceptance remains NOT RUN until a real handset run verifies notification promotion, cancellation and replacement download behavior.
