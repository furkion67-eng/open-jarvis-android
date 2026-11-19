# Local video MVP — Turkish narrated vertical MP4

Current feature stage:

- Accepts a natural-language topic and builds a five-scene Turkish storyboard.
- The Istanbul conquest topic uses a small curated set of dated historical milestones; other topics use a clearly generic outline rather than invented topic-specific facts.
- Renders a 20-second, 9:16 H.264 scene track locally with Android MediaCodec, EGL14 and OpenGL ES. Istanbul scenes include five locally drawn procedural vector illustrations (walls, cannon, ships, sunrise and map).
- Synthesizes each scene with an installed Turkish Android TTS voice using `tr-TR`; there is no silent English fallback. If the device has no usable Turkish voice data, the pipeline stops with a Turkish explanation.
- Encodes narration to AAC, muxes it with H.264, and validates that the final MP4 contains both audio and video tracks with compatible durations.
- Builds SRT cues from each actual synthesized WAV segment duration, then exports the MP4 to Movies/Open JARVIS and the SRT to Downloads/Open JARVIS through MediaStore on Android 10+.
- The share action only opens Android's chooser after the user presses the button. It does not upload or publish to YouTube/TikTok.
- Runs render/encoding work off the main thread, checks cancellation, removes temporary WAV/AAC/silent files on failure, limits resolution/FPS/duration/bitrate and checks free storage.

**Visual provenance:** the five scene images are procedural local Canvas vector drawings, not AI-generated images or licensed historical photographs.

**Remaining validation:** the new narration/AAC/mux/export path needs exact-head Android 13/14 CI and physical TECNO acoustic/playback acceptance. Emulator or unit tests do not prove the installed TECNO TTS voice sounds natural. YouTube/TikTok OAuth, token storage and per-post approval are not included in this PR.

No secrets, paid providers, account settings or external publication are used.
