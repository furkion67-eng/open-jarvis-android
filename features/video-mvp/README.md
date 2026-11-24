# Local video MVP — source-backed Turkish vertical MP4

Current renderer/content-pipeline contract:

- A render request is accepted only when a researched story pack exists. The supported packs are **İstanbul'un Fethi: 1453** and **Mimar Sinan: Süleymaniye yedi yılda**; unsupported topics fail closed instead of receiving generic filler or invented facts.
- The five scenes use five distinct Wikimedia Commons assets with pinned file-page revisions. Every descriptor records the creator, exact license/version, license URL, source revision URL, attribution text, the pinned page-revision timestamp, the corresponding upload timestamp and the Commons SHA-1 for the original uploaded bytes. Before rendering, the downloader fetches the original upload and verifies its SHA-1 against that pinned evidence. The map scene additionally verifies the original SVG bytes before fetching the PNG thumbnail derived from that same pinned file. The resolver rejects missing/unknown licenses, NC/ND terms, mismatched upload hashes, invalid revision chronology, non-HTTPS or non-Wikimedia downloads, unexpected MIME types, oversized/invalid images and duplicate image hashes.
- The 1453 pack uses Public Domain Mark 1.0 and CC BY-SA 4.0 assets. The Mimar Sinan pack uses five distinct CC BY-SA 4.0 images. CC BY-SA adaptations must be distributed under CC BY-SA 4.0; this obligation is carried in the typed attribution manifest and the generated credits. Attribution includes the creator, license, source revision and 9:16 crop note.
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

## Mimar Sinan research pack

The Mimar Sinan script uses Selçuk Mülâyim's TDV İslâm Ansiklopedisi entries “Sinan” and “Süleymaniye Camii ve Külliyesi”:
- https://islamansiklopedisi.org.tr/sinan
- https://islamansiklopedisi.org.tr/suleymaniye-camii-ve-kulliyesi

The five scene claims are limited to the sources' statements about Şehzade Camii (1548, “çıraklık”), Süleymaniye Camii's opening on 15 October 1557, seven years after its foundation ceremony (“kalfalık”), Selimiye in Edirne as Sinan's greatest work, his appointment as chief architect at age 48, and his death in 1588 / burial in the Süleymaniye complex. The first-two-second hook is now the concrete payoff “Süleymaniye yedi yılda!” rather than a generic slogan. The opening visual is the licensed Süleymaniye exterior, so the first frame supports the hook instead of showing an unrelated sculpture; the next scenes rewind to Sinan's appointment and Şehzade before moving forward to Selimiye and his tomb.

| Scene | Creator | License | Pinned source |
|---|---|---|---|
| Şehzade minarets | R Prazeres | CC BY-SA 4.0 | https://commons.wikimedia.org/w/index.php?title=File:Sehzade_Mosque_DSCF3265.jpg&oldid=1207014206 |
| Süleymaniye exterior | Mattias Hill | CC BY-SA 4.0 | https://commons.wikimedia.org/w/index.php?title=File:S%C3%BCleymaniye_Mosque_Istanbul.JPG&oldid=1049867755 |
| Selimiye and monument | BuhaM | CC BY-SA 4.0 | https://commons.wikimedia.org/w/index.php?title=File:Selimiye_Mosque_in_Edirne_and_a_monument_to_its_builder,_architect_Mimar_Sinan.jpg&oldid=1140027221 |
| Mimar Sinan sculpture | Yahia | CC BY-SA 4.0 | https://commons.wikimedia.org/w/index.php?title=File:Koca_Mimar_Sinan.jpg&oldid=1181793138 |
| Mimar Sinan tomb | Sahar.Ahmed | CC BY-SA 4.0 | https://commons.wikimedia.org/w/index.php?title=File:Mimar_Sinan_Tomb.jpg&oldid=1289199267 |

## Pinned binary upload evidence

The timestamps and SHA-1 values below were read from the Wikimedia Commons MediaWiki API on 24 November 2026. For each asset, the selected upload is the latest file upload at or before the pinned file-description-page revision timestamp. The runtime verifies the original bytes against this SHA-1 before using them; the SHA-256 in the render attribution record then identifies the exact cached/rendered bytes.

| Asset | Pinned page revision timestamp | Original upload timestamp | Commons SHA-1 |
|---|---|---|---|
| Constantinople 1453 miniature | 2026-05-29T09:10:49Z | 2023-01-26T14:24:28Z | `28aec6604eb74d307dfdf4d530c2382212e09bbd` |
| Gentile Bellini portrait | 2026-10-08T21:43:09Z | 2024-04-28T10:57:43Z | `6ce889b4d2b97ff7fffdcafea463e2e49254f1b8` |
| Rumeli Hisarı | 2025-11-19T18:56:30Z | 2025-09-07T14:33:08Z | `74395103cd95488caae9454fac23aa2dad9890ef` |
| 1453 siege map (original SVG; PNG thumbnail rendered) | 2026-10-15T10:24:34Z | 2024-09-18T07:11:01Z | `b5482a15226ada4bdd07780518c13aef2f45c19b` |
| Golden Horn wall | 2026-06-23T23:09:02Z | 2025-09-17T14:17:45Z | `a4840825d4178818dcf4ac5d0b378198f83c48e7` |
| Şehzade minarets | 2026-05-02T09:49:35Z | 2022-09-27T06:03:19Z | `e516513bd9eb1ba9e46878751476984b5895e331` |
| Süleymaniye exterior | 2025-06-28T07:46:53Z | 2015-05-22T13:14:32Z | `95c5931aca24a2fde8d24ab046a85a920b567c70` |
| Selimiye and monument | 2026-01-01T15:10:05Z | 2025-06-09T11:20:26Z | `ab5c598321266333cdaa20e00cd8c00529ca3e3e` |
| Mimar Sinan sculpture | 2026-03-15T22:40:24Z | 2025-06-13T07:31:20Z | `019896c72049ccf8f7f37d9f0514a71be541285c` |
| Mimar Sinan tomb | 2026-10-19T21:13:14Z | 2018-04-26T10:35:21Z | `bda6a4f3949a3c4a2daf731c101a0cd02c4e5927` |

The 1453 map's original SVG is hash-verified before its Wikimedia PNG thumbnail is downloaded. The other nine render inputs are the original uploaded files, not current thumbnail URLs. The API's file-upload timestamp is checked not to be later than the pinned file-page revision timestamp.

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
- The pinned page revision is now bound to the corresponding Commons original-upload SHA-1 and upload timestamp. The render manifest also records SHA-256 for the exact visual bytes and re-hashes them when read from cache. The 1453 map is the only derived render input: its original SVG is SHA-1 checked before the matching Commons PNG thumbnail is fetched.
- Two curated research packs are available: İstanbul 1453 and Mimar Sinan. Other topics need a separate verified research bundle and a distinct licensed asset per scene before they can render.
- This is the creative-renderer layer only. It returns attribution and research metadata; the host task/publishing layer must persist those fields and expose the credits in a draft description.
- No YouTube/TikTok/Instagram OAuth, account access, credential storage, scheduling or real publishing is included. Public posting remains a separate product flow requiring official OAuth and explicit per-post approval.

No passwords, real API keys, account settings, paid providers or external publications are used.
