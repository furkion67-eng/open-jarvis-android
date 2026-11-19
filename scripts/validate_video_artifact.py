#!/usr/bin/env python3
"""Validate an exported JARVIS short-video MP4 and its timed SRT captions."""
from __future__ import annotations
import json, re, subprocess, sys
from fractions import Fraction
from pathlib import Path

def fail(message: str) -> int:
    print(f"VIDEO-ACCEPTANCE-FAIL: {message}", file=sys.stderr)
    return 1

def parse_time(value: str) -> float:
    match = re.fullmatch(r"(\d{2}):(\d{2}):(\d{2}),(\d{3})", value)
    if not match:
        raise ValueError(f"invalid SRT timestamp: {value!r}")
    hours, minutes, seconds, millis = map(int, match.groups())
    if minutes > 59 or seconds > 59:
        raise ValueError(f"out-of-range SRT timestamp: {value!r}")
    return hours * 3600 + minutes * 60 + seconds + millis / 1000.0

def validate_srt(path: Path, duration: float) -> int:
    text = path.read_text(encoding="utf-8-sig").replace("\r\n", "\n").strip()
    if not text:
        raise ValueError("caption file is empty")
    blocks = [block.strip() for block in re.split(r"\n\s*\n", text) if block.strip()]
    if not blocks:
        raise ValueError("caption file has no cues")
    previous_start = -1.0
    for index, block in enumerate(blocks, start=1):
        lines = [line.strip() for line in block.splitlines()]
        if len(lines) < 3 or not lines[0].isdigit():
            raise ValueError(f"cue {index} must contain an index, timing line and text")
        if int(lines[0]) != index:
            raise ValueError(f"cue numbering must be sequential at cue {index}")
        timing = re.fullmatch(r"(\d{2}:\d{2}:\d{2},\d{3})\s+-->\s+(\d{2}:\d{2}:\d{2},\d{3})", lines[1])
        if not timing:
            raise ValueError(f"cue {index} has an invalid timing line")
        start, end = map(parse_time, timing.groups())
        if start < previous_start:
            raise ValueError(f"cue {index} starts before the preceding cue")
        if end <= start:
            raise ValueError(f"cue {index} has a non-positive duration")
        if end > duration + 0.10:
            raise ValueError(f"cue {index} extends beyond the MP4 duration")
        if not any(line for line in lines[2:]):
            raise ValueError(f"cue {index} has no caption text")
        previous_start = start
    return len(blocks)

def main() -> int:
    if len(sys.argv) not in (3, 5):
        return fail("usage: validate_video_artifact.py <video.mp4> <captions.srt> [min_seconds max_seconds]")
    video, captions = Path(sys.argv[1]), Path(sys.argv[2])
    minimum = float(sys.argv[3]) if len(sys.argv) == 5 else 15.0
    maximum = float(sys.argv[4]) if len(sys.argv) == 5 else 60.0
    if not video.is_file() or video.stat().st_size < 4096:
        return fail("MP4 is missing or unexpectedly small")
    if not captions.is_file():
        return fail("timed SRT caption sidecar is missing")
    if minimum <= 0 or maximum < minimum:
        return fail("invalid duration bounds")
    probe = subprocess.run(["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(video)], capture_output=True, text=True, check=False)
    if probe.returncode != 0:
        return fail("ffprobe could not parse the exported MP4")
    try:
        metadata = json.loads(probe.stdout)
    except json.JSONDecodeError:
        return fail("ffprobe returned invalid JSON")
    streams = metadata.get("streams", [])
    videos = [stream for stream in streams if stream.get("codec_type") == "video"]
    audios = [stream for stream in streams if stream.get("codec_type") == "audio"]
    if len(videos) != 1:
        return fail(f"expected exactly one video stream, found {len(videos)}")
    if not audios:
        return fail("narration/audio stream is missing")
    video_stream = videos[0]
    if video_stream.get("codec_name") != "h264":
        return fail("video codec must be H.264")
    if any(stream.get("codec_name") != "aac" for stream in audios):
        return fail("audio streams must use AAC in the MP4 export")
    width, height = int(video_stream.get("width", 0)), int(video_stream.get("height", 0))
    if width <= 0 or height <= 0:
        return fail("video dimensions are missing")
    if abs((width / height) - (9.0 / 16.0)) > 0.015:
        return fail(f"expected 9:16 portrait video, received {width}x{height}")
    format_data = metadata.get("format", {})
    try:
        duration = float(video_stream.get("duration") or format_data.get("duration"))
    except (TypeError, ValueError):
        return fail("video duration is missing or invalid")
    if duration < minimum or duration > maximum:
        return fail(f"duration {duration:.2f}s is outside {minimum:.0f}-{maximum:.0f}s")
    audio_durations = []
    for stream in audios:
        try:
            audio_durations.append(float(stream.get("duration") or format_data.get("duration")))
        except (TypeError, ValueError):
            return fail("audio duration is missing or invalid")
    if any(abs(value - duration) > 0.75 for value in audio_durations):
        return fail("audio and video durations differ by more than 750 ms")
    try:
        caption_count = validate_srt(captions, duration)
    except (OSError, UnicodeError, ValueError) as error:
        return fail(f"timed captions are invalid: {error}")
    try:
        fps = float(Fraction(video_stream.get("avg_frame_rate", "0/1")))
    except (ValueError, ZeroDivisionError):
        return fail("video frame rate is missing or invalid")
    if fps < 15.0:
        return fail(f"video frame rate {fps:.2f} FPS is below 15 FPS")
    print(f"VIDEO-ACCEPTANCE-OK: {width}x{height}, {duration:.2f}s, H.264/AAC, {fps:.2f} FPS, {caption_count} timed captions")
    print("NOTE: stream metadata does not prove Turkish speech intelligibility or caption semantic accuracy.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
