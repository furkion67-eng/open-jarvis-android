#!/usr/bin/env python3
"""Integrated source contracts plus deterministic MP4/SRT acceptance fixtures."""
from __future__ import annotations
import subprocess
import tempfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
VALIDATOR = ROOT / "scripts" / "validate_video_artifact.py"
def read(path: str) -> str:
    target = ROOT / path
    if not target.is_file():
        raise AssertionError(f"required source missing: {path}")
    return target.read_text(encoding="utf-8")
def run(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, text=True, capture_output=True, check=False)
def source_contract() -> None:
    router = read("features/video-mvp/app/src/main/java/com/openjarvis/video/VideoCommandRouter.kt")
    plan = read("features/video-mvp/app/src/main/java/com/openjarvis/video/VideoStoryPlan.kt")
    assets = read("features/video-mvp/app/src/main/java/com/openjarvis/video/assets/VideoVisualAssets.kt")
    pipeline = read("features/video-mvp/app/src/main/java/com/openjarvis/video/VideoStoryPipeline.kt")
    integration = read("features/task-engine/app/src/main/java/com/openjarvis/tasks/VideoTaskIntegration.kt")
    repository = read("features/task-engine/app/src/main/java/com/openjarvis/tasks/JarvisTaskRepository.kt")
    player = read("features/video-mvp/app/src/main/java/com/openjarvis/ui/player/VideoArtifactPlayerPanel.kt")
    player_test = read("features/video-mvp/app/src/androidTest/java/com/openjarvis/ui/player/VideoArtifactPlayerInstrumentedTest.kt")
    narrated_test = read("features/video-mvp/app/src/androidTest/java/com/openjarvis/video/VideoNarratedExportInstrumentedTest.kt")
    policy = read("features/turkish-tts/app/src/main/java/com/openjarvis/voice/TurkishTtsPolicy.kt")
    engine = read("features/turkish-tts/app/src/main/java/com/openjarvis/voice/AndroidTTSEngine.kt")
    paging = read("features/device-actions/app/src/main/java/com/openjarvis/commands/ContactChoicePaging.kt")
    coordinator = read("features/device-actions/app/src/main/java/com/openjarvis/commands/DeviceActionCoordinator.kt")
    paging_test = read("features/device-actions/app/src/test/java/com/openjarvis/commands/ContactChoicePagingTest.kt")
    checks = {
        "video request idempotency is deterministic and bounded": "requestIdempotencyKey" in router and "REQUEST_DEDUP_WINDOW_MS = 30_000L" in router,
        "story plan requires a short first-two-second hook": "hookText.isNotBlank()" in plan and "hookDurationMs in 1L..2_000L" in narrated_test,
        "story claims are source-validated and scene-linked": "isTrustedResearchSource" in plan and "usedClaimIds == claimsById.keys" in plan,
        "generic filler and repeated visuals are rejected": "genericFiller" in plan and "plan.scenes.map { it.visualAssetId }.distinct().size == plan.scenes.size" in plan,
        "Commons filename/revision and license checks remain strict": "isPinnedCommonsSource" in assets and "isDownloadForPinnedSource" in assets and "VideoAssetLicensePolicy.validate(descriptor)" in assets,
        "asset cache integrity uses SHA-256": "MessageDigest.getInstance(\"SHA-256\")" in assets and "cacheKey" in assets,
        "renderer requires a complete attribution manifest": "resolver.resolveAll(descriptors)" in pipeline and "attributionManifestComplete = attributionComplete" in pipeline,
        "renderer exports and reopens MediaStore MP4/SRT": "VideoMediaStoreExporter.export(" in pipeline and "VideoMediaStoreExporter.exportCaptions(" in pipeline and "verifyExportedVideo" in pipeline and "verifyExportedCaptions" in pipeline,
        "production executor verifies persisted video and captions": "class VideoStoryTaskExecutor" in integration and "VideoStoryPipeline(appContext).render" in integration and "isReadableVideoUri" in integration and "isReadableCaptionsUri" in integration,
        "interrupted tasks are persisted and never auto-replayed": "APP_RESTARTED" in repository and "JarvisTaskStatus.INTERRUPTED" in repository,
        "actual Android TTS voice must report exact tr-TR or fail closed": "localeTag.equals(EXACT_TURKISH_LOCALE_TAG, ignoreCase = true)" in policy and "exactActualEngineLocale" in policy and "actualLocale = TurkishTtsPolicy.exactActualEngineLocale(actualLocaleTag)" in engine and "if (actualLocale == null)" in engine,
        "player uses in-app VideoView and content URI validation": "VideoView(viewContext)" in player and "setVideoURI(state.uri)" in player and "parseContentUri(videoUriText)" in player and "VideoArtifactPlaybackValidator.inspect" in player_test,
        "player end-to-end test restores and plays an older accepted Room artifact": "productionCreateAcceptExecutorPersistsAndDashboardPlaysOlderArchiveArtifactAfterRoomReopen" in player_test and "JarvisTaskRepository.getInstance(context)" in player_test and "awaitPlaybackProgress(playerActivity!!)" in player_test and "ACTION_VIEW" in player_test,
        "narrated export test checks H.264/AAC and timed captions": "MediaFormat.MIMETYPE_VIDEO_AVC" in narrated_test and "MediaFormat.MIMETYPE_AUDIO_AAC" in narrated_test and "720" in narrated_test and "1_280" in narrated_test and "result.sceneCount + 1" in narrated_test,
        "ambiguous contacts paginate all candidates with stable ordinals": "PAGE_SIZE = 5" in paging and "ContactChoicePaging.navigate(" in coordinator and "ContactChoicePaging.page(choice.candidates, choice.pageIndex)" in coordinator and "sixthCandidateIsVisibleOnNextPageAndCanBeSelectedByItsDisplayedOrdinal" in paging_test,
    }
    for name, passed in checks.items():
        print(f"{'PASS' if passed else 'FAIL'}: {name}")
    failed = [name for name, passed in checks.items() if not passed]
    if failed:
        raise AssertionError("source contract failed: " + "; ".join(failed))
    print(f"JARVIS-SOURCE-CONTRACT-OK: {len(checks)} assertions")
def make_mp4(path: Path, width: int, height: int, seconds: int, audio: bool) -> None:
    command = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", f"testsrc2=size={width}x{height}:rate=24"]
    if audio:
        command += ["-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000"]
    command += ["-t", str(seconds), "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p"]
    command += ["-c:a", "aac", "-b:a", "96k", "-shortest"] if audio else ["-an"]
    command += ["-movflags", "+faststart", str(path)]
    result = run(command)
    if result.returncode:
        raise RuntimeError(result.stderr)
def expect(label: str, args: list[str], accepted: bool, marker: str | None = None) -> None:
    result = run(["python3", str(VALIDATOR), *args])
    ok = result.returncode == 0
    if ok != accepted or (marker and marker not in result.stdout):
        raise AssertionError(f"{label}: expected accepted={accepted}\n{result.stdout}\n{result.stderr}")
    print(f"PASS: {label}")
def main() -> int:
    source_contract()
    with tempfile.TemporaryDirectory(prefix="jarvis-video-contract-") as temp:
        work = Path(temp)
        good, silent, short, landscape = (work / n for n in ("good.mp4", "silent.mp4", "short.mp4", "landscape.mp4"))
        captions, malformed = work / "captions.srt", work / "malformed.srt"
        captions.write_text("1\n00:00:00,000 --> 00:00:02,000\nGemiler karadan Haliç'e!\n\n2\n00:00:02,000 --> 00:00:10,000\nHaliç girişini zincir kapattı.\n\n3\n00:00:10,000 --> 00:00:15,000\nKuşatma ve sonuç.\n", encoding="utf-8")
        malformed.write_text("1\nnot-a-time\nEksik zaman kodu.\n", encoding="utf-8")
        make_mp4(good, 360, 640, 15, True)
        make_mp4(silent, 360, 640, 15, False)
        make_mp4(short, 360, 640, 6, True)
        make_mp4(landscape, 640, 360, 15, True)
        cases = [
            ("narrated H.264/AAC MP4 accepted", ["--mode", "narrated", str(good), str(captions)], True, "VIDEO-NARRATED-ACCEPTANCE-OK"),
            ("silent MP4 rejected as narrated output", ["--mode", "narrated", str(silent), str(captions)], False, None),
            ("silent MP4 is preview-only and marked incomplete", ["--mode", "preview", str(silent), str(captions)], True, "STAGE-INCOMPLETE"),
            ("short video rejected", ["--mode", "preview", str(short), str(captions)], False, None),
            ("landscape video rejected", ["--mode", "preview", str(landscape), str(captions)], False, None),
            ("missing SRT rejected", ["--mode", "preview", str(good), str(work / "missing.srt")], False, None),
            ("malformed SRT rejected", ["--mode", "preview", str(good), str(malformed)], False, None),
        ]
        for label, args, accepted, marker in cases:
            expect(label, args, accepted, marker)
        (ROOT / "video-contract-test-report.txt").write_text("VIDEO-ARTIFACT-CONTRACT-TESTS: 7 passed; 0 failed\n", encoding="utf-8")
        print("VIDEO-CONTRACT-TESTS: 7 passed; 0 failed")
    return 0
if __name__ == "__main__":
    raise SystemExit(main())
