# Issue #5 — device-side confirmation-gating & permission validation (API 33 emulator)

Date: 2026-11-18 · Branch: `feature/issue5-local-action-execution` · Emulator: `system-images;android-33;google_apis;x86_64`, emulator 37.2.13, KVM x86_64

## What this run added

1. `app/src/androidTest/java/com/openjarvis/commands/ConfirmationGatingInstrumentedTest.kt` (NEW, 6 tests)
   - `sendSmsConfirmationRequiresExplicitConfirmBeforeSending` — routed SMS surfaces `AwaitingConfirmation` (`send_sms`), nothing sent.
   - `spokenCancelResolvesPendingSmsWithoutSendingAnything` — spoken "iptal" resolves pending with `Done("İptal edildi.")`.
   - `missingBodyWaitsForUserInputThenConfirmationThenFreeTextIsNotAnAnswer` — NeedsInput → spoken body → confirmation; free text during confirmation falls through to the router.
   - `missingContactsPermissionReportsTurkishErrorInsteadOfCrashing` — no `READ_CONTACTS` → `Error("Rehber okuma izni verilmedi...")`, no crash.
   - `confirmingSmsWithoutSendSmsPermissionNeverClaimsSent` — issue #5 honesty rule: without `SEND_SMS` the result must NOT contain "SMS gönderildi".
   - `callCommandWaitsForConfirmationAndCancelDoesNothing` — call gating (`make_call`).
2. Fixed `AnalysisEngineTest` (androidTest): `runTest(Dispatchers.IO)` → `runBlocking` (all 3 tests errored on-device before this fix; evidence: `instrumented-before-fix.log`).
3. Ported `FuzzyMatcher.kt` + `FuzzyMatcherTest.kt` (5 JVM tests) from the issue-#3 fix line and wired the fuzzy fallback into `GraphifyRepository.findContactByName`, required by `contactFuzzyMatchWithinDistanceTwo`.

## Evidence

- `instrumented-before-fix.log` — 10 tests run, 3 failures (AnalysisEngineTest `Dispatcher must implement TestDispatcher`), 6 new tests passing.
- `instrumented-final.log` — `OK (10 tests)`, 0 failures, against the clean-room build (fresh ZIP extract + exact CI patch phase).
- `issue5-launch-api33.png` — launcher renders MainActivity after cold start; process alive 20 s, 0 `FATAL EXCEPTION` in logcat crash buffer.

## Validation executed

- `gradle (8.2, JDK 17, android-34) testDebugUnitTest assembleDebug assembleDebugAndroidTest` → BUILD SUCCESSFUL, JVM **80/80** (75 prior + 5 FuzzyMatcherTest).
- `am instrument` on API 33 emulator → **OK (10 tests)**.
- Fresh-install cold launch → process alive after 20 s, 0 FATAL EXCEPTION, `com.openjarvis/.ui.MainActivity` resumed.

## ZIP delta audit (mechanical, CRC-based vs previous head `bd36709`)

191 → 195 entries; **0 removed; 3 files added + 1 new dir entry; 2 modified** (`AnalysisEngineTest.kt`, `GraphifyRepository.kt`). Manifest and `strings.xml` CRC-identical — all 12 permissions, services, accessibility config and Turkish strings unchanged.

## Remaining limitations

- x86_64 emulator; physical TECNO Spark 20 Pro (ARM/HiOS) verification still outstanding.
- Real SMS/call/send flows on device require runtime permission grants and carrier coverage.
