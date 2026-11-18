# JARVIS short-form video pipeline contract

Status: implementation contract only. This document does not claim that rendering or publishing is implemented.

Related product issue: #8
Immediate Android execution dependency: #16
Credential/logging security dependency: #14

## User outcome

A Turkish instruction such as "Bugün yapay zekânın geleceği hakkında 3 video hazırla" creates three editable short-video drafts. Each draft can be rendered as a local MP4, previewed, and exported. Upload is a separate, explicitly approved action.

## Staged delivery

### Stage A — local video creation (first implementation milestone)

1. Accept a topic, target audience, duration, language, tone, and requested number of videos.
2. Generate a structured script and storyboard; retain editable text and per-scene metadata.
3. Resolve visuals only from user-provided assets, generated assets, or sources with documented reuse/licensing permission. Store attribution/source metadata.
4. Generate or import Turkish narration; support a no-narration path.
5. Produce timed captions (SRT/VTT plus burned-in option) and mix narration/music with ducking. Music must be user-provided, generated, or appropriately licensed.
6. Render portrait 9:16 MP4 using a deterministic renderer (Android Media3 Transformer or a backend FFmpeg worker; choose based on source archive/runtime constraints). Do not block the Android UI thread.
7. Validate duration, dimensions, codec/container, audio track, caption timing, and output file readability before marking ready.
8. Save to app-private storage, expose Android share/export, and retain a preview thumbnail.

Acceptance:
- At least one 15–60 second 9:16 MP4 can be generated end-to-end from a topic.
- Render survives process death/retry without duplicating outputs.
- Audio/video/captions are synchronized and the final MP4 opens in a standard Android player.
- UI shows queued, scripting, asset preparation, narration, rendering, validating, ready, failed, and cancelled states.
- A deterministic fixture-based render test runs in CI and the output MP4 is uploaded as a workflow artifact.

### Stage B — YouTube Shorts publishing

- Use official Google OAuth 2.0 authorization-code flow with least-privilege YouTube upload scope.
- Upload through the official YouTube Data API; never collect Google passwords.
- Show title, description, tags, audience setting, and privacy selection before upload.
- First end-to-end validation must use private or unlisted visibility on a test channel.
- Persist resumable-upload state and platform video ID; retries must not create duplicate posts.
- Respect quota limits and report quota/cost failures accurately.

### Stage C — TikTok publishing

- Use the official TikTok Content Posting API only.
- Implement creator authorization, required scopes, creator/account capability checks, and the platform's current posting consent UX.
- Respect app audit requirements and account eligibility. If direct posting is unavailable, offer a local export/share handoff; do not scrape or automate TikTok's UI.
- Show the exact video, caption, privacy/interaction settings and destination account before user confirmation.
- Persist publish state and platform identifiers; use idempotency where supported.

## Shared job and safety model

Suggested lifecycle:
`draft -> approved_for_render -> queued -> rendering -> validating -> ready -> awaiting_publish_approval -> uploading -> published`
with terminal/recoverable `failed`, `cancelled`, and `needs_user_action` states.

- Rendering approval and publishing approval are separate.
- Never publish publicly without a per-video explicit user action.
- Never create accounts, change account settings, or store platform passwords.
- OAuth refresh/access tokens belong in encrypted platform-appropriate storage or a backend secret store, never source code, APK assets, BuildConfig constants, issue comments, or logs.
- Redact authorization headers, API keys, prompts, scripts, personal data, and OAuth tokens from logs.
- Require explicit user approval before any paid generation, storage, or API spend; show an estimate first.
- Do not claim monetization or guaranteed earnings.
- Do not report success until the renderer validates the file or the platform API returns a verified publication identifier/status.

## Test and CI gates

- JVM tests: job transitions, retry/idempotency, approval gates, metadata validation.
- Instrumented tests: app-private file creation, preview playback handoff, export/share intent.
- Renderer integration test: fixture assets -> MP4 -> ffprobe/MediaMetadataRetriever validation; upload MP4 and test logs as CI artifacts.
- Upload contract tests use mocked official APIs; no real credentials in CI.
- A separately gated manual test uses a private/unlisted YouTube test channel and a TikTok test account where eligible.
- Every report must name the exact commit SHA and separate emulator evidence from physical TECNO Spark 20 Pro validation.

## Required secrets/configuration (names only)

Only add a secret after the corresponding official integration is implemented and tested:
- `GOOGLE_OAUTH_CLIENT_ID` (public client identifier may be configuration, not a secret)
- `GOOGLE_OAUTH_CLIENT_SECRET` (only for a confidential backend client; never ship in APK)
- `TIKTOK_CLIENT_KEY` (platform app identifier)
- `TIKTOK_CLIENT_SECRET` (backend only; never ship in APK)
- Backend signing/encryption keys, if a backend token vault is introduced.

Do not request or print secret values in issues, PRs, logs, or chat. The user must enter credentials only in the official provider console/secure secret manager.

## Implementation order and boundaries

1. Keep the current startup/command-execution fixes and Issue #14 credential redaction as blockers; do not regress the existing permissions, services, Accessibility, overlay, microphone, notifications, foreground service, screenshot, WAKE_LOCK, Internet, or Turkish localization.
2. Implement Stage A independently of any social platform SDK.
3. Implement YouTube and TikTok as separate adapters behind a common publishing interface.
4. Do not merge automatically. Each stage requires a dedicated feature branch/PR and exact-head CI evidence.
