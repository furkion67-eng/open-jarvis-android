# Local video MVP — source-backed Turkish vertical MP4

Current renderer/content-pipeline contract:

- A render request is accepted only when a researched story pack exists. The first supported pack is **İstanbul'un Fethi: 1453**; unsupported topics fail closed instead of receiving generic filler or invented facts.
- The five scenes use five distinct Wikimedia Commons assets with pinned file-page revisions. Every descriptor records the creator, exact license/version, license URL, source revision URL and attribution text. The resolver rejects missing/unknown licenses, NC/ND terms, unverified source revisions, non-HTTPS or non-Wikimedia downloads, unexpected MIME types, oversized/invalid images and duplicate image hashes.
- The current pack uses Public Domain Mark 1.0 and CC BY-SA 4.0 assets. CC BY-SA adaptations must be distributed under CC BY-SA 4.0; this obligation is carried in the typed attribution manifest and the generated credits. Attribution includes the creator, license, source revision and 9:16 crop note.
- Visual assets are fetched over HTTPS from Wikimedia Commons, cached under the app cache directory for at most 30 days, and bounded to 10 entries / 24 MB. Expired, corrupt and excess entries are removed. A valid cache entry can be reused offline; without a valid cache, network failure stops rendering. There is no procedural visual fallback for production story renders.
- The first spoken segment is a separate Turkish hook. The pipeline measures the actual tr-TR TTS WAV duration and refuses to render if the hook exceeds 2,000 ms. The following scene segments are synthesized independently; SRT cues use the measured duration of the exact spoken text.
- Each scene uses its own source image as a full-bleed 9:16 visual, with readable Turkish title/caption overlays and a visible creator/license credit line. The full attribution manifest and researched claim sources are returned as typed metadata for the host task/publishing-draft layer.
- Production output is 720×1280 vertical at 24 FPS with a 1.5 Mbps video target (the size validator keeps the estimated MP4 under 24 MB). The output is H.264 video plus AAC tr-TR TTS narration only; no background music is included yet. A royalty-cleared music catalogue and mixing stage remain separate work. The renderer validates the muxed tracks; the pipeline additionally verifies a readable content:// MP4 URI, an SRT content:// URI, the MP4 ftyp signature and a timed first-hook caption before reporting success.
- A 720×1280 JPEG cover is extracted from the rendered MP4, exported to Pictures/Open JARVIS, validated by its JPEG signature and returned with concise Turkish alt text and creator/license context. Output is exported to Movies/Open JARVIS and captions to Downloads/Open JARVIS through MediaStore. The share action remains a user-initiated Android chooser; this renderer does not upload or publish to any platform.

## Research-source and story-quality gate

Claim-source metadata is accepted only when the publisher label exactly matches its HTTPS host:
- `Türk Tarih Kurumu` → `ttk.gov.tr`
- `TDV İslâm Ansiklopedisi` → `islamansiklopedisi.org.tr`

Source URLs with lookalike hosts, user-info, non-default ports, query strings or fragments are rejected. `checkedOn` must be a valid ISO calendar date and cannot be in the future. Every claim ID must be referenced by a scene, and every scene claim must resolve back to that scene; orphaned or missing citations stop rendering.

Turkish-normalized scene headings and captions must be unique even when punctuation differs. The opening hook must not be reused as a later scene heading. These are fail-closed quality checks, not a substitute for reviewing the underlying historical source or the exact pinned Commons revision.

## Research pack

The İstanbul 1453 story uses the following fact sources:

- Türk Tarih Kurumu, İstanbul'un Fethi: https://ttk.gov.tr/istanbulun-fethi/
- TDV İslâm Ansiklopedisi, Haliç: https://islamansiklopedisi.org.tr/halic
- TDV İslâm Ansiklopedisi, Rumelihisarı: https://islamansiklopedisi.org.tr/rumelihisari
- TDV İslâm Ansiklopedisi, İstanbul: https://islamansiklopedisi.org.tr/istanbul

The script avoids a definitive ship count or route. The TDV sources note that the number, route and duration details are disputed.

## Asset provenance

| Scene | Creator | License | Pinned source |
|---|---|---|---|
| Siege miniature / hook | Jean Le Tavernier | Public Domain Mark 1.0 | https://commons.wikimedia.org/w/index.php?title=File:Constantinople_1453.jpg&oldid=1222147207 |
| Mehmed II portrait | Gentile Bellini | Public Domain Mark 1.0 | https://commons.wikimedia.org/w/index.php?title=File:Bellini,_Gentile_-_Sultan_Mehmet_II.jpg&oldid=1284749530 |
| Rumeli Hisarı | mike | CC BY-SA 4.0 | https://commons.wikimedia.org/w/index.php?title=File:Rumeli_Hisar%C4%B1_Fortress_in_2025.jpg&oldid=1118525609 |
| Siege map | Sémhur | CC BY-SA 4.0 | https://commons.wikimedia.org/w/index.php?title=File:Siege_of_Constantinople_1453_map-en.svg&oldid=1287462680 |
| Golden Horn wall | Isabeau | CC BY-SA 4.0 | https://commons.wikimedia.org/w/index.php?title=File:Golden_Horn_Wall.jpg&oldid=1237207514 |

## Remaining validation and limitations

- Exact-head Android 13/14 CI and physical TECNO Spark 20 Pro acceptance are still required. Unit tests do not prove the device's installed Turkish voice sounds natural.
- The current curated research pack is only for İstanbul 1453. Other topics need a separate verified research bundle and a distinct licensed asset per scene before they can render.
- This is the creative-renderer layer only. It returns attribution and research metadata; the host task/publishing layer must persist those fields and expose the credits in a draft description.
- No YouTube/TikTok/Instagram OAuth, account access, credential storage, scheduling or real publishing is included. Public posting remains a separate product flow requiring official OAuth and explicit per-post approval.

No passwords, real API keys, account settings, paid providers or external publications are used.
