#!/usr/bin/env python3
"""Validate JARVIS video artifacts in preview (silent) or narrated acceptance mode."""
from __future__ import annotations
import argparse, json, re, subprocess, sys
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
        if len(lines) < 3 or not lines[0].isdigit() or int(lines[0]) != index:
            raise ValueError(f"cue {index} must have a sequential index, timing line and text")
        timing = re.fullmatch(r"(\d{2}:\d{2}:\d{2},\d{3})\s+-->\s+(\d{2}:\d{2}:\d{2},\d{3})", lines[1])
        if not timing:
            raise ValueError(f"cue {index} has an invalid timing line")
        start, end = map(parse_time, timing.groups())
        if start < previous_start or end <= start or end > duration + 0.10:
            raise ValueError(f"cue {index} has invalid or out-of-range timing")
        if not any(line for line in lines[2:]):
            raise ValueError(f"cue {index} has no caption text")
        previous_start = start
    return len(blocks)

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("preview", "narrated"), default="narrated")
    parser.add_argument("video")
    parser.add_argument("captions")
    parser.add_argument("--min-seconds", type=float, default=15.0)
    parser.add_argument("--max-seconds", type=float, default=60.0)
    args = parser.parse_args()
    video, captions = Path(args.video), Path(args.captions)
    if not video.is_file() or video.stat().st_size < 4_096:
        return fail("MP4 is missing or unexpectedly small")
    if not captions.is_file():
        return fail("timed SRT caption sidecar is missing")
    if args.min_seconds <= 0 or args.max_seconds < args.min_seconds:
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
    if args.mode == "narrated" and not audios:
        return fail("narration/audio stream is missing")
    if any(stream.get("codec_name") != "aac" for stream in audios):
        return fail("audio streams must use AAC in the MP4 export")
    video_stream = videos[0]
    if video_stream.get("codec_name") != "h264":
        return fail("video codec must be H.264")
    width, height = int(video_stream.get("width", 0)), int(video_stream.get("height", 0))
    if width <= 0 or height <= 0 or abs((width / height) - (9.0 / 16.0)) > 0.015:
        return fail(f"expected 9:16 portrait video, received {width}x{height}")
    format_data = metadata.get("format", {})
    try:
        duration = float(video_stream.get("duration") or format_data.get("duration"))
    except (TypeError, ValueError):
        return fail("video duration is missing or invalid")
    if duration < args.min_seconds or duration > args.max_seconds:
        return fail(f"duration {duration:.2f}s is outside {args.min_seconds:.0f}-{args.max_seconds:.0f}s")
    for stream in audios:
        try:
            audio_duration = float(stream.get("duration") or format_data.get("duration"))
        except (TypeError, ValueError):
            return fail("audio duration is missing or invalid")
        if abs(audio_duration - duration) > 0.75:
            return fail("audio and video durations differ by more than 750 ms")
    try:
        cue_count = validate_srt(captions, duration)
    except (OSError, UnicodeError, ValueError) as error:
        return fail(f"timed captions are invalid: {error}")
    try:
        fps = float(Fraction(video_stream.get("avg_frame_rate", "0/1")))
    except (ValueError, ZeroDivisionError):
        return fail("video frame rate is missing or invalid")
    if fps < 15.0:
        return fail(f"video frame rate {fps:.2f} FPS is below 15 FPS")
    audio_label = "AAC" if audios else "none (silent preview)"
    marker = "VIDEO-NARRATED-ACCEPTANCE-OK" if args.mode == "narrated" else "VIDEO-PREVIEW-ACCEPTANCE-OK"
    print(f"{marker}: {width}x{height}, {duration:.2f}s, H.264, audio={audio_label}, {fps:.2f} FPS, {cue_count} timed captions")
    if args.mode == "preview" and not audios:
        print("STAGE-INCOMPLETE: silent preview only; no narration audio track")
    print("NOTE: metadata does not prove Turkish speech intelligibility, caption semantic accuracy, generated visuals or shareable export URI.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
