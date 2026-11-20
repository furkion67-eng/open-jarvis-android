#!/usr/bin/env python3
"""Deterministic tests for preview-stage and narrated-video acceptance modes."""
from __future__ import annotations
import subprocess, tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VALIDATOR = ROOT / "scripts" / "validate_video_artifact.py"

def run(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, text=True, capture_output=True, check=False)

def make_mp4(path: Path, width: int, height: int, seconds: int, audio: bool) -> None:
    command = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", f"testsrc2=size={width}x{height}:rate=24"]
    if audio:
        command += ["-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000"]
    command += ["-t", str(seconds), "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p"]
    command += ["-c:a", "aac", "-b:a", "96k", "-shortest"] if audio else ["-an"]
    command += ["-movflags", "+faststart", str(path)]
    result = run(command)
    if result.returncode != 0:
        raise RuntimeError(f"ffmpeg fixture generation failed: {result.stderr}")

def expect(label: str, args: list[str], accepted: bool, marker: str | None = None) -> None:
    result = run(["python3", str(VALIDATOR), *args])
    ok = result.returncode == 0
    if ok != accepted or (marker and marker not in result.stdout):
        raise AssertionError(f"{label}: expected accepted={accepted}\n{result.stdout}\n{result.stderr}")
    print(f"PASS: {label}")


def verify_command_task_source() -> None:
    def read(relative: str) -> str:
        path = ROOT / relative
        if not path.is_file():
            raise AssertionError(f"required feature source is missing: {relative}")
        return path.read_text(encoding="utf-8")

    router = read("features/video-mvp/app/src/main/java/com/openjarvis/video/VideoCommandRouter.kt")
    repair = read("scripts/repair_sources.py")
    integration = read("features/task-engine/app/src/main/java/com/openjarvis/tasks/VideoTaskIntegration.kt")
    orchestrator = read("features/task-engine/app/src/main/java/com/openjarvis/tasks/JarvisTaskOrchestrator.kt")
    repository = read("features/task-engine/app/src/main/java/com/openjarvis/tasks/JarvisTaskRepository.kt")
    task_ui = read("features/task-engine/app/src/main/java/com/openjarvis/ui/dashboard/TaskControlCenter.kt")
    tts_policy = read("features/turkish-tts/app/src/main/java/com/openjarvis/voice/TurkishTtsPolicy.kt")
    tts_engine = read("features/turkish-tts/app/src/main/java/com/openjarvis/voice/AndroidTTSEngine.kt")
    tts_policy_test = read("features/turkish-tts/app/src/test/java/com/openjarvis/voice/TurkishTtsPolicyTest.kt")
    exact_method_start = tts_policy.find("fun isExactTurkishLocale(localeTag: String): Boolean")
    exact_method_end = tts_policy.find("\n    }", exact_method_start)
    if exact_method_start < 0 or exact_method_end < 0:
        raise AssertionError("exact tr-TR locale method is missing or malformed")
    exact_turkish_method = tts_policy[exact_method_start:exact_method_end]
    tts_engine_test = read("features/turkish-tts/app/src/androidTest/java/com/openjarvis/voice/TurkishTtsEngineInstrumentedTest.kt")
    video_pipeline_test = read("features/video-mvp/app/src/androidTest/java/com/openjarvis/video/VideoStoryPipelineInstrumentedTest.kt")
    task_model = read("features/task-engine/app/src/main/java/com/openjarvis/tasks/JarvisTaskModel.kt")
    task_database = read("features/task-engine/app/src/main/java/com/openjarvis/tasks/JarvisTaskDatabase.kt")
    task_dao = read("features/task-engine/app/src/main/java/com/openjarvis/tasks/JarvisTaskDao.kt")
    migration_test = read("features/task-engine/app/src/androidTest/java/com/openjarvis/tasks/JarvisTaskMigrationInstrumentedTest.kt")
    task_ui_test = read("features/task-engine/app/src/test/java/com/openjarvis/ui/dashboard/TaskControlCenterReviewStateTest.kt")
    safety_test = read("features/task-engine/app/src/androidTest/java/com/openjarvis/tasks/JarvisTaskExecutionSafetyInstrumentedTest.kt")
    video_execution_test = read("features/task-engine/app/src/androidTest/java/com/openjarvis/tasks/VideoTaskExecutionInstrumentedTest.kt")
    retry_instrumented_test = read("features/task-engine/app/src/androidTest/java/com/openjarvis/ui/dashboard/TaskControlCenterReviewStateInstrumentedTest.kt")

    checks = {
        "Turkish typed command enters durable task path":
            "submitVideoCommandIfMatched(text, VideoCommandOrigin.TYPED, updateStatus)" in repair,
        "recognized speech enters the same durable task path":
            "submitVideoCommandIfMatched(text, VideoCommandOrigin.RECOGNIZED_SPEECH, updateStatus)" in repair,
        "typed and speech requests use a stable idempotency key":
            "VideoCommandRouter.requestIdempotencyKey(topic, System.currentTimeMillis())" in repair,
        "router has a bounded deduplication window":
            "REQUEST_DEDUP_WINDOW_MS = 30_000L" in router,
        "local video capability and executor are explicit":
            "VideoStoryTaskCapability" in integration and "VideoStoryTaskExecutor" in integration,
        "render begins through the accepted local task executor":
            "VideoStoryPipeline(appContext).render(topic, artifactKey = artifactKey)" in integration,
        "executor verifies MP4 and SRT before success":
            "isReadableVideoUri" in integration and "isReadableCaptionsUri" in integration and "verified = true" in integration,
        "missing executor persists NOT_EXECUTED with zero progress and an explicit error":
            "markNotExecutedIfCurrent" in orchestrator and
            "EXECUTOR_NOT_CONFIGURED" in orchestrator and "progressPercent = 0" in task_dao,
        "process recovery persists INTERRUPTED and clears stale progress":
            "APP_RESTARTED" in repository and "markInterruptedIfCurrent" in repository and
            "progressPercent = 0" in task_dao,
        "task UI distinguishes unavailable and interrupted execution":
            "EXECUTOR_UNAVAILABLE" in task_ui and "INTERRUPTED" in task_ui and "Yürütülmedi · yürütücü yok" in task_ui,
        "only exact tr-TR voices are selectable":
            ".filter { isExactTurkishLocale(it.localeTag) }" in tts_policy and 'private const val EXACT_TURKISH_LOCALE_TAG = "tr-TR"' in tts_policy and "localeTag.equals(EXACT_TURKISH_LOCALE_TAG, ignoreCase = true)" in exact_turkish_method and
            "Locale.forLanguageTag" not in exact_turkish_method,
        "engine checks the actual nullable engine voice before Ready":
            "actualVoiceLocaleProvider.read(engine)" in tts_engine and
            "exactActualEngineLocale(actualLocaleTag)" in tts_engine and
            "engine.voice?.locale?.toLanguageTag()" in tts_engine and
            "fun exactActualEngineLocale(localeTag: String?)" in tts_policy,
        "unit tests reject generic tr and tr-CY":
            "genericAndOtherRegionTurkishVoicesAreRejectedWithoutExactTrTr" in tts_policy_test and "exactTrTrRejectsLocaleVariantsExtensionsAndMalformedTags" in tts_policy_test and "actualEngineLocaleIsCanonicalExactTrTrOnly" in tts_policy_test and "tr-TR-u-nu-latn" in tts_policy_test,
        "instrumentation asserts Ready always means exact tr-TR":
            "TurkishTtsPolicy.isExactTurkishLocale(status.voiceLocale)" in tts_engine_test,
        "Android instrumentation rejects a variant actual-engine locale before Ready":
            "actualEngineVariantLocaleNeverReportsReady" in video_pipeline_test and "ActualTtsVoiceLocaleProvider" in video_pipeline_test and '"tr-TR-u-nu-latn"' in video_pipeline_test,
        "task model persists NOT_EXECUTED and INTERRUPTED":
            'NOT_EXECUTED("not_executed"' in task_model and 'INTERRUPTED("interrupted"' in task_model,
        "Room version 6 retains execution recovery and video artifact migrations":
            "version = 6" in task_database and "val MIGRATION_3_4" in task_database and
            "val MIGRATION_4_5" in task_database and "val MIGRATION_5_6" in task_database and
            "EXECUTOR_NOT_CONFIGURED" in task_database and "APP_RESTARTED" in task_database,
        "Room version 6 migration clears synthetic failed progress":
            "UPDATE jarvis_tasks SET progressPercent = 0 WHERE status = 'failed'" in task_database and
            'FAILED("failed", "Başarısız", 0)' in task_model and
            "assertEquals(0, failed.progressPercent)" in migration_test,
        "only verified success shows determinate progress":
            "JarvisTaskStatus.fromStorage(task.status) == JarvisTaskStatus.SUCCEEDED" in task_ui and
            "task.progressPercent >= 100" in task_ui and
            "taskStatusLabel(task)" in task_ui and
            "failedExternalTaskDoesNotShowSyntheticCompletionPercentOrProgressBar" in task_ui_test and
            "failedVideoTaskShowsFailureWithoutCompletionPercentOrProgressBar" in task_ui_test and
            "cancelledTaskDoesNotShowProgressOrCompletionPercent" in task_ui_test,
        "executor-unavailable tasks do not offer a retry while recoverable tasks do":
            "internal fun shouldOfferRetry(task: JarvisTaskEntity)" in task_ui and
            "if (taskReviewUiState(task) == TaskReviewUiState.EXECUTOR_UNAVAILABLE) return false" in task_ui and
            "if (shouldOfferRetry(task))" in task_ui and
            "assertFalse(shouldOfferRetry(task))" in task_ui_test and
            "interruptedTaskOffersOnlyAnExplicitManualRetry" in task_ui_test and
            "ordinaryNetworkWaitOffersAnExplicitManualRetry" in task_ui_test,
        "Compose instrumentation hides retry without executor and invokes manual retry for interrupted/network-wait tasks":
            'assertTextAbsent("Yeniden dene")' in retry_instrumented_test and
            "interruptedTaskShowsAndInvokesExplicitManualRetryAction" in retry_instrumented_test and
            "ordinaryNetworkWaitShowsAndInvokesExplicitManualRetryAction" in retry_instrumented_test and
            "Interrupted task must expose a working manual retry action" in retry_instrumented_test and
            "Network-wait task must expose a working manual retry action" in retry_instrumented_test,
        "legacy migration revokes stale external approvals":
            "approvalGranted = 0" in task_database and "approvalReference = NULL" in task_database,
        "DAO uses conditional durable-state transitions":
            "markNotExecutedIfCurrent" in task_dao and "markInterruptedIfCurrent" in task_dao,
        "production repository registers the real video executor":
            "VideoStoryTaskExecutor(context.applicationContext)" in repository,
        "production executor E2E uses the singleton repository":
            "productionRepositoryRunsRegisteredVideoExecutorAndPersistsVerifiedMediaStoreBundle" in video_execution_test and
            "JarvisTaskRepository.getInstance(context)" in video_execution_test,
        "production executor E2E verifies H.264/AAC and all three exported URIs":
            "MediaFormat.MIMETYPE_VIDEO_AVC" in video_execution_test and
            "MediaFormat.MIMETYPE_AUDIO_AAC" in video_execution_test and
            "Verified MP4, SRT and thumbnail must all be persisted" in video_execution_test and
            "assertEquals(artifacts.getString(0), completed.videoUri)" in video_execution_test and
            "assertEquals(artifacts.getString(1), completed.captionsUri)" in video_execution_test and
            "thumbnailUri = Uri.parse(artifacts.getString(2))" in video_execution_test,
        "migration test covers legacy unavailable and interrupted rows":
            "versionOneDatabaseMigratesAndPreservesTasksWithoutInventingPlanAcceptance" in migration_test and
            "legacy-not-executed" in migration_test and "legacy-interrupted" in migration_test,
        "missing executor revokes previous external approval":
            "missingExecutorRevokesPreviouslyGrantedExternalApproval" in safety_test and
            "approvalGranted" in safety_test and "approvalReference" in safety_test,
        "null actual TTS voice fails closed with a tested handoff":
            "exactActualEngineLocale" in tts_policy and
            "actualVoiceLocaleProvider.read(engine)" in tts_engine and
            "nullActualEngineVoiceNeverReportsReadyAndCompletesSttHandoffExactlyOnce" in tts_engine_test,
    }
    failed = [label for label, ok in checks.items() if not ok]
    if failed:
        raise AssertionError("command/task source contract failed: " + "; ".join(failed))
    print(f"VIDEO-COMMAND-TASK-CONTRACT-OK: {len(checks)} source assertions")


def main() -> int:
    verify_command_task_source()
    with tempfile.TemporaryDirectory(prefix="jarvis-video-contract-") as temp:
        work = Path(temp)
        good, silent, short, landscape = (work / name for name in ("good.mp4", "silent.mp4", "short.mp4", "landscape.mp4"))
        captions, malformed = work / "captions.srt", work / "malformed.srt"
        captions.write_text(
            "1\n00:00:00,000 --> 00:00:05,000\nİstanbul'un tarihi.\n\n"
            "2\n00:00:05,000 --> 00:00:10,000\nŞehrin surları.\n\n"
            "3\n00:00:10,000 --> 00:00:15,000\nSonuç.\n",
            encoding="utf-8",
        )
        malformed.write_text("1\nnot-a-time\nEksik zaman kodu.\n", encoding="utf-8")
        make_mp4(good, 360, 640, 15, True)
        make_mp4(silent, 360, 640, 15, False)
        make_mp4(short, 360, 640, 6, True)
        make_mp4(landscape, 640, 360, 15, True)
        cases = [
            ("narrated H.264/AAC MP4 accepted", ["--mode", "narrated", str(good), str(captions)], True, "VIDEO-NARRATED-ACCEPTANCE-OK"),
            ("silent MP4 rejected as narrated output", ["--mode", "narrated", str(silent), str(captions)], False, None),
            ("silent portrait preview accepted as preview only", ["--mode", "preview", str(silent), str(captions)], True, "STAGE-INCOMPLETE"),
            ("6-second sample rejected", ["--mode", "preview", str(short), str(captions)], False, None),
            ("landscape output rejected", ["--mode", "preview", str(landscape), str(captions)], False, None),
            ("missing SRT rejected", ["--mode", "preview", str(good), str(work / "missing.srt")], False, None),
            ("malformed SRT rejected", ["--mode", "preview", str(good), str(malformed)], False, None),
        ]
        for label, args, accepted, marker in cases:
            expect(label, args, accepted, marker)
        report = ROOT / "video-contract-test-report.txt"
        report.write_text("VIDEO-ARTIFACT-CONTRACT-TESTS: 7 passed; 0 failed\n", encoding="utf-8")
        print("VIDEO-CONTRACT-TESTS: 7 passed; 0 failed")
        print(f"REPORT: {report}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
