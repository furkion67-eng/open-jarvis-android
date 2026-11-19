# Local video MVP — Issue #8, staged offline pipeline

Current feature stage:

- Accepts a natural-language topic and builds a five-scene Turkish storyboard.
- The Istanbul conquest topic uses a small curated set of dated historical milestones; other topics use a clearly generic outline rather than invented topic-specific facts.
- Renders a 20-second, 9:16 H.264/MP4 draft locally with Android MediaCodec, EGL14 and OpenGL ES. Istanbul scenes include five locally drawn procedural vector illustrations (walls, cannon, ships, sunrise and map).
- Writes a time-aligned SRT sidecar beside the MP4 and exposes a narration script for the next TTS stage.
- Runs on Dispatchers.IO, checks cancellation between frames, reports progress via StateFlow, limits resolution/FPS/duration/bitrate, checks free storage and caps output at 24 MB.
- Stores files under app-private filesDir/jarvis-videos and deletes the MP4 if caption-sidecar writing fails.

**Important limitations:** the MP4 is still silent and uses locally drawn procedural vector art plus text cards, not AI-generated or licensed historical images. The narration field is text only, not an audio track. The SRT is a separate sidecar, not embedded in the MP4. No gallery export, YouTube OAuth/upload or TikTok OAuth/upload is implemented. No post is published.

Tests validate storyboard selection, generic-topic safety, 20-second timing, monotonic caption cues, SRT formatting and render-spec limits. Existing Android instrumentation still validates the local H.264 MP4 renderer; this new storyboard-to-file integration requires exact-head Android CI.

TECNO Spark 20 Pro / HiOS video acceptance remains NOT RUN for the storyboard/SRT stage. The earlier user recording confirmed only the old 6-second silent sample button.
