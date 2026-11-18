# Local MP4 prototype — Issue #8, Stage A foundation

This is a deliberately small Android-only renderer, not a full content-generation or publishing pipeline.

- Accepts a short topic and creates a 6-second 9:16 H.264/MP4 title-card draft.
- Uses Android MediaCodec, MediaMuxer, EGL14 and OpenGL ES; no FFmpeg, cloud service, API key, paid service or extra manifest permission is required.
- Runs on Dispatchers.IO, checks coroutine cancellation between frames, publishes progress through a thread-safe StateFlow collected by Compose, limits dimensions/FPS/duration/bitrate, checks free disk space before rendering and caps the output at 24 MB.
- Stores output under app-private filesDir/jarvis-videos; partial files are deleted after failure/cancellation.
- Validates the final MP4 with MediaExtractor before reporting success.

The current output is **silent** and contains only locally drawn text cards. It does not generate narration, images, captions, licensed music, a 15–60 second finished story, gallery export, YouTube upload or TikTok publishing. Those remain separate milestones and require separate approval for any external publishing or paid service.

Tests:
- JVM tests validate input bounds, portrait output constraints, worker-thread progress updates and terminal-state protection against late callbacks.
- Android instrumentation renders an actual MP4, reopens it with MediaExtractor, checks H.264, dimensions, duration and app-private storage, then deletes the fixture.

The emulator test does not establish hardware encoder support on every OEM device. TECNO Spark 20 Pro / HiOS validation remains required.
