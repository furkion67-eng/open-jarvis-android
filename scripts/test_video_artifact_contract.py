#!/usr/bin/env python3
"""Deterministic positive/negative tests for the video artifact acceptance gate."""
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

def expect_accept(label: str, video: Path, captions: Path) -> None:
    result = run(["python3", str(VALIDATOR), str(video), str(captions)])
    if result.returncode != 0 or "VIDEO-ACCEPTANCE-OK" not in result.stdout:
        raise AssertionError(f"{label}: expected acceptance\n{result.stdout}\n{result.stderr}")
    print(f"PASS: {label}")

def expect_reject(label: str, video: Path, captions: Path) -> None:
    result = run(["python3", str(VALIDATOR), str(video), str(captions)])
    if result.returncode == 0 or "VIDEO-ACCEPTANCE-FAIL" not in result.stderr:
        raise AssertionError(f"{label}: expected rejection\n{result.stdout}\n{result.stderr}")
    print(f"PASS: {label}")

def main() -> int:
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
        total = 0
        expect_accept("15s portrait H.264 + AAC + valid timed captions", good, captions); total += 1
        expect_reject("silent MP4 rejected", silent, captions); total += 1
        expect_reject("6-second demo rejected by short-form duration gate", short, captions); total += 1
        expect_reject("landscape MP4 rejected", landscape, captions); total += 1
        expect_reject("missing caption sidecar rejected", good, work / "missing.srt"); total += 1
        expect_reject("malformed timed captions rejected", good, malformed); total += 1
        report = ROOT / "video-contract-test-report.txt"
        report.write_text(
            "VIDEO-ARTIFACT-CONTRACT-TESTS: 6 passed; 0 failed\n"
            "Cases: valid narrated portrait; silent; short; landscape; missing SRT; malformed SRT\n",
            encoding="utf-8",
        )
        print(f"VIDEO-CONTRACT-TESTS: {total} passed; 0 failed")
        print(f"REPORT: {report}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
