# Podcast Addict backend — verified API contract

**Scope:** exactly four capabilities — (1) search podcasts, (2) get a podcast and its
episodes, (3) resolve a playable audio URL, (4) discover a podcast by feed URL.

**Status of this document:** every request below was **executed** with `curl` from this machine
against the live service on **2026-10-09, 17:31–18:14 UTC**. Full machine-readable evidence is
> **Provenance — read before citing.** This document was produced by probing the service live on
> 2026-10-09. The raw captures, response headers and the re-runnable probe scripts that produced it
> (`docs/contract/`) have since been **deleted and were not preserved**. The load-bearing proofs are
> quoted inline below — the MPEG frame sync bytes, the `Content-Range`, the verbatim response bodies
> — so every conclusion here is checkable without them. What is *not* recoverable is the ability to
> re-run the probes, and the per-call timestamps that no longer have a machine-readable home. Treat
> specific UTC timestamps as transcribed, not re-verifiable.

**What is inline in this document** (the part that survives):

| Section | Evidence quoted inline |
|---|---|
| §4 — audio URL | The full 117,103,291-byte download, its first four bytes, and the ranged `206` with `Content-Range: bytes 0-4095/117103291` |
| §2–§3 | Verbatim response bodies, byte-for-byte, for each endpoint that worked |
| §5 | The measured failure shapes, including the empty-body `{}` that a naive decoder accepts |

**What was deleted** — `docs/contract/` in full: `VERIFICATION.log`, `AUDIO-VERIFICATION.log`,
`captures/*.json`, `captures/*.hdr`, and every `probe*.sh` / `verify.sh` / `verify_audio.sh`.

Everything is labelled **[M]** (measured — I ran it and this is what came back) or
**[I]** (inferred from the decompiled code / the report, not measured). Where a measurement
disagrees with the report, the measurement wins and the disagreement is called out.

**Baseline report:** `docs/PODCAST-ADDICT-INTERNALS.md`. Endpoint table §3.7, enclosure
resolution §4.2–4.3, URL normalisation §3.8, Undetermined §12.

---

## 0. How to call this backend at all (a precondition, not a capability)

This is the first thing to know, and it is a **measured** finding that the report does not
contain.

### 0.1 `X-App-Key` is enforced by a WAF rule, not by the PHP

Measured live (first calls 17:31 UTC, 2026-10-09; scripts since deleted):

| Request | Result |
|---|---|
| `GET …/get_podcast.php?id=1`, **no** `X-App-Key`, no special UA | **HTTP 403**, Cloudflare "Attention Required!" HTML block page |
| `GET …/get_podcast.php?id=1`, `X-App-Key` correct, **PodcastAddict UA** | **HTTP 200**, JSON |
| `GET …/searchpodcast.php?term=…`, `X-App-Key` correct, **Chrome UA** | **HTTP 403**, block page |
| `GET …/searchpodcast.php?term=…`, `X-App-Key` correct, `X-App-Installer` absent | **HTTP 400** `Missing parameters` (i.e. past the WAF) |
| `GET …/searchpodcast.php?term=…`, **no** `X-App-Key`, `X-App-Installer` present | **HTTP 403**, block page |

So: Cloudflare has a rule that requires **both** the `X-App-Key` header with the exact GUID
value **and** a User-Agent starting `PodcastAddict/v5`. Drop either one and you get the
generic bot-wall page, not an application error. `X-App-Installer` is *not* part of that rule
(it is checked later, by the PHP, if at all).

### 0.2 The request the app actually sends (from `fo5.p()`, report §3.3)

```
GET https://addictpodcast.com/ws/php/v4.1/<endpoint>.php?<k>=<v>&…
X-App-Key: d2fad335-a44d-4aeb-9e5b-67b19a15572c
X-App-Installer: <installer package name, e.g. com.android.vending>   # literal "null" if unknown
User-Agent: PodcastAddict/v5 (+https://podcastaddict.com/; Android podcast app)
Accept-Encoding: gzip            # z2==true in fo5.n0() ⇒ identity is overridden
Cache-Control: no-cache          # only on the fo5.l0() path, not fo5.p()
```

Query values are percent-encoded with OkHttp's `QUERY_COMPONENT_ENCODE_SET`, so a space becomes
`%20` and a `'` becomes `%27` — **not** `+`. This matters for the `languages` parameter (§1.3).

There is no `Authorization` header, no bearer token, no request signature, no nonce, no
timestamp. One static GUID in one header is the entire authentication story. **[M]**

### 0.3 Caching behaviour of the CDN in front (measured)

Responses carry `cache-control: public, max-age=3600, s-maxage=3600, stale-while-revalidate=120,
stale-if-error=86400` and Cloudflare `cf-cache-status: HIT`. So metadata is cached at the edge
for an hour. `get_podcast_top_episodes.php` was `cf-cache-status: DYNAMIC` on every call.
Implication for us: a "podcast changed" poll is cheap; an "episode changed" poll is not.

---

## 1. Capability 1 — Search podcasts

### 1.1 `searchpodcast.php` — **does not work. Verified failure.**

This is the headline negative result, and it is a *finding*, not a gap in my method.

**Exact request used (the one the app makes, per report §5.1 `to3.o()` and the decompiled
`to3.java:397-426`):**

```
GET https://addictpodcast.com/ws/php/v4.1/searchpodcast.php
    ?term=stuff%20you%20should%20know
    &type=AUDIO
    &languages=%27English%27%2C%20%27%27
    &exactName=0&dateFilter=0&explicitFilter=0
X-App-Key: d2fad335-a44d-4aeb-9e5b-67b19a15572c
X-App-Installer: com.android.vending
User-Agent: PodcastAddict/v5 (+https://podcastaddict.com/; Android podcast app)
```

**Response (measured live 2026-10-09; quoted inline):**

```
HTTP/2 400
content-type: text/html; charset=UTF-8
cf-cache-status: BYPASS
content-length: 18

Missing parameters
```

**Every attempt made, all identical.** Scripts are on disk and re-runnable:
`probe.sh` (7 variants), `probe2.sh` (19), `probe3.sh` (11), plus ~60 ad-hoc
variants run inline. Total ~100 GETs. Sampled:

| Variant | Result |
|---|---|
| `term` only | `Missing parameters` |
| `term` + all three filter flags | `Missing parameters` |
| `term` + `type=AUDIO` | `Missing parameters` |
| `term` + `type=audio` (lower-case) | `Missing parameters` |
| `term` + `languages` in the app's `'English', ''` format | `Missing parameters` |
| `term` + `type` + `languages` + all three filters (the app's exact set) | `Missing parameters` |
| `+ category=TV & Film` | `Missing parameters` |
| `+ key=<GUID>&installer=<pkg>` as query params (the `wn5.c(1)` style used by `search_radios.php`) | `Missing parameters` |
| `+ limit` / `offset` / `page` / `country` / `version` / `nbDays` / `isAudio` / `durationFilter` / `sortBy` | `Missing parameters` |
| `search=…` or `q=…` instead of `term` | `Missing parameters` |
| `term` empty, 1 char, whitespace, non-ASCII, array syntax, duplicated | `Missing parameters` |
| `exactName/dateFilter/explicitFilter` as `true`/`false` instead of `1`/`0` | `Missing parameters` |
| POST form-encoded; POST JSON; PUT; OPTIONS; HTTP/1.1 forced; `Accept: application/json` | `Missing parameters` |

Two facts pin this down as a **server-side change, not a parameter mismatch on my side**:

1. The error text is identical for `OPTIONS` (18 bytes, `cf-cache-status: DYNAMIC`) — the guard
   fires before any parameter is read.
2. `get_podcast_top_episodes.php` with **no** parameters returns a *different* message,
   `Parameters missing` (HTTP 400), and works fine once `id` is supplied. `searchpodcast.php`
   and `search_episodes.php` say `Missing parameters` and never get past it. They are on
   different code paths.

**Conclusion [M]:** `searchpodcast.php` and `search_episodes.php` are **unusable from outside
the app** as of 2026-10-09. The capability "search podcasts by free text" **cannot be delivered
through this backend.** I did not work around it, spoof anything, or paper over it.

**What I did not try, and why:** replaying a request captured from a real device, or anything
that mints a device identity. That is outside "call the API from here".

### 1.2 What *does* work for discovery: `get_top_podcasts.php` (browse, not search)

This is the closest usable substitute, and it proves the backend is otherwise healthy.

```
GET https://addictpodcast.com/ws/php/v4.1/get_top_podcasts.php
    ?languages=%27English%27%2C%20%27%27
    &offset=0&limit=3&isAudio=true&durationFilter=-1
```

**Response (measured 2026-10-09T18:01:37Z, HTTP 200, 3623 bytes, `application/json`):**

```json
[
  {
    "id": 2280906,
    "iTunesId": "1089022756",
    "url": "https://mcsorleys.barstoolsports.com/feed/pardon-my-take",
    "name": "Pardon My Take",
    "description": "<p><p>On \"Pardon My Take,\" Big Cat &amp; PFT Commenter deliver the loudest…",
    "language": "",
    "thumbnail": "https://chumley.barstoolsports.com/union/2024/02/21/PMT-DK.994e7478.jpg?crop=3000%3A3000%2Csmart&format=jpeg",
    "type": "AUDIO",
    "keywords": "",
    "author": "Barstool Sports",
    "explicit": 1,
    "episodeNb": 910,
    "averageDuration": 8246,
    "subscribers": 26793,
    "categories": [ { "name_en": "Sports/Football", "name_fr": "" } ]
  }
]
```
(full body quoted inline below)

**Pagination is real and measured:** `offset=0`, `offset=3`, `offset=6` with `limit=3` each
returned three *different*, non-overlapping podcasts (ids `2280906…5680002`, then
`7203879…1816325`, then `4776514…5692174`). `limit` is honoured. `isAudio=false` switched the
result set to video podcasts (German `MVW - Markus Lanz` entries). `durationFilter` values
change the result set too. So `offset`/`limit`/`isAudio`/`durationFilter` are all live
parameters. **[M]**

### 1.3 The `languages` parameter is required and has one accepted format — a recovered secret

This is the single most valuable thing I recovered by experiment, and it applies to **every**
browse endpoint.

Measured with `get_top_podcasts.php`, `limit=3`:

| `languages` value | Items returned |
|---|---|
| *(parameter absent)* | **error** — not JSON, request fails |
| `en` | **0** |
| `English` | **0** |
| `'English'` | **0** |
| `fr` | **0** |
| `'English', ''` | **3** |
| `'English',''` (no space after comma) | **3** |
| `'French', ''` | **3** |
| `'english', ''` (lower-case) | **3** |
| `''` | **3** |

So the format is: **a comma-separated list of single-quoted, full English language names**, and
the empty entry `''` is what matches podcasts with no declared language. It is case-insensitive
on the name. This matches `wn5.d(boolean, boolean)` in the report (§3.7 note) — a
`TreeSet` joined with `", "` with each element wrapped in `'` and a trailing `, ''` appended —
but the report never states that omitting or mistyping it yields an error or silently zero
results, which is exactly the trap. **[M]**

**Note the asymmetry:** `get_popular_search_terms.php?languages='English', ''` returns
`{"results":[]}` while `?languages=en` returns populated results. The backend is internally
inconsistent about this parameter; the only safe advice is to use the app's exact format and
verify against the specific endpoint.

---

## 2. Capability 2 — Get a podcast and its episodes

### 2.1 `get_podcast.php` — podcast metadata

```
GET https://addictpodcast.com/ws/php/v4.1/get_podcast.php?id=2280906
X-App-Key: d2fad335-a44d-4aeb-9e5b-67b19a15572c
X-App-Installer: com.android.vending
User-Agent: PodcastAddict/v5 (+https://podcastaddict.com/; Android podcast app)
```

**Verification [M]:** HTTP 200, 24 870 bytes, `application/json`, requested
2026-10-09T18:01:35Z, answered 18:01:36Z, `cf-cache-status: MISS`. Ran it 3 times across the
session (ids `1` and `2280906`), 200 every time.

**Response shape — a single JSON object (NOT an array):**

```json
{
  "id": 2280906,
  "iTunesId": "1089022756",
  "url": "https://mcsorleys.barstoolsports.com/feed/pardon-my-take",
  "name": "Pardon My Take",
  "description": "<p><p>On \"Pardon My Take,\" … </p>",
  "language": "",
  "thumbnail": "https://chumley.barstoolsports.com/union/2024/02/21/PMT-DK.994e7478.jpg?crop=3000%3A3000%2Csmart&format=jpeg",
  "type": "AUDIO",
  "keywords": "",
  "author": "Barstool Sports",
  "explicit": 1,
  "team_id": -1,
  "accepted": 1,
  "canBeFeatured": 1,
  "timestamp": 20261009095349,
  "firstPublicationDate": 1602651393000,
  "lastPublicationDate": 1791524589000,
  "episodeNb": 910,
  "averageDuration": 8246,
  "errorcounter": 0,
  "httpCode": 200,
  "isDeprecated": 0,
  "newUrl": null,
  "websubId": -1,
  "lastmod": 0,
  "manuallyInspected": 0,
  "lastMetadataUpdateTimestamp": 20261009102349,
  "estimatedNextRelease": 20261009105338,
  "subscribers": 26793,
  "reviews": [ { "id": 905, "userName": "oso722", "rating": 5, … } ],
  "categories": [ { "name_en": "Sports/Football", "name_fr": "" } ]
}
```
(raw body quoted inline)

**Field semantics (measured / inferred):**

| Field | Meaning |
|---|---|
| `id` | **The podcast id.** Integer, stable, the value every other endpoint takes. |
| `iTunesId` | Apple Podcasts collection id as a **string** (`null` when unknown). |
| `url` | The canonical feed URL. Lower-cased by the server. |
| `name` / `description` / `author` / `keywords` | Feed metadata, HTML in `description`. |
| `thumbnail` | Artwork URL. Note: **only one size** — no 150/500 split like our model wants. |
| `type` | `AUDIO` or `VIDEO` (from the `jp3` enum, report §4.3). |
| `explicit` | `0`/`1`. |
| `episodeNb` | Total episodes the directory knows about. |
| `averageDuration` | **seconds** (measured: `8246` for a 2h17m-average show — an `itunes:duration` style sum). Contrast with `duration` in §2.2, which is **ms**. |
| `firstPublicationDate` / `lastPublicationDate` | **epoch milliseconds** (measured: `1791524589000` → 2026-10-09). |
| `timestamp`, `lastMetadataUpdateTimestamp` | **`YYYYMMDDhhmmss` as an integer** — not epoch, not epoch-ms. `20261009102349` = 2026-10-09 10:23:49. |
| `estimatedNextRelease` | Predicted next-episode time, same `YYYYMMDDhhmmss` format. |
| `httpCode` / `errorcounter` | The crawler's last fetch result. |
| `isDeprecated` / `newUrl` | Feed-moved signals; `newUrl` is `null` or the new feed URL (report §A29). |
| `reviews`, `categories`, `subscribers`, `team_id`, `websubId`, `canBeFeatured`, `manuallyInspected`, `lastmod` | Directory-only. `categories[]` carries both `name_en` and `name_fr`. |

**No pagination, no episode list.** `get_podcast.php` contains no `episodes` array — episodes
come from `get_podcast_top_episodes.php` (§2.2) or from parsing the feed (§3.4).

**Unknown id [M]:** `GET get_podcast.php?id=999999999` → HTTP 200, body exactly `null`.
No id at all → also `null`. So "unknown" and "unparameterised" are indistinguishable; treat a
`null` body as `ErrorCode.NOT_FOUND`.

### 2.2 `get_podcast_top_episodes.php` — the episode list, and where the audio URL lives

```
GET https://addictpodcast.com/ws/php/v4.1/get_podcast_top_episodes.php?id=2280906
```
(`url=<feed url>` also works and returns a byte-identical body — 286 143 bytes both ways.
If both `id` and `url` are present, `id` wins and `url` is ignored: measured.)

**Verification [M]:** HTTP 200, 286 143 bytes, `application/json; charset=UTF-8`,
`cf-cache-status: DYNAMIC` (never edge-cached), requested 2026-10-09T18:01:36Z, answered
18:01:37Z, 1.375 s. Ran it 4 times across the session; identical every time.

**Response shape:**

```json
{
  "podcastId": 2280906,
  "episodes": [ … exactly 100 elements … ]
}
```

`episodes[0]`, verbatim (long fields elided, elisions marked; raw file on disk):

```json
{
  "guid": "ed95a029-176d-4cf0-a5e1-f9efb5f27b86",
  "episodeId": 230579356,
  "episodeUrl": "https://dts.podtrac.com/redirect.mp3/landmark-dynamic.barstoolsports.com/stream/EplzUaEORrWMZVrDHVFKoFr1/audio.mp3?v=eyJzcmMiOiJodHRwczovL2JhcnN0b29sLXBvZGNhc3RzLnMzLnVzLWVhc3QtMS5hbWF6b25hd3MuY29tL2JhcnN0b29sLXNwb3J0cy9wYXJkb24tbXktdGFrZS9FcGx6VWFFT1JyV01aVnJESFZGS29GcjEvMjAyNi8wOC8xNy9wbXRhdWRpby04LTE3LjY4MGYzYjIzLjk2cy5kOTk2ZWE4MS5tcDMiLCJiZWMiOlsibWFsZSIsInNwb3J0cyJdLCJhZnAiOm51bGx9&etag=eccbc87e",
  "podcastId": 2280906,
  "title": "Football With Booger McFarland, NFL Preseason Week 1, …",
  "publicationDate": 1786943765000,
  "duration": 9819649,
  "type": "AUDIO",
  "description": "<p>NFL preseason week 1 is complete …  …[1035 chars elided]…</p>",
  "artwork": "https://chumley.barstoolsports.com/union/2026/08/17/IMG_0021-1.fc2825d1.jpg?crop=3000%3A3000%2Csmart&format=jpeg",
  "thumbnail": "https://chumley.barstoolsports.com/union/2026/08/17/IMG_0021-1.fc2825d1.jpg?crop=3000%3A3000%2Csmart&format=jpeg",
  "downloads": 34744,
  "streams": 5400,
  "fullyListened": 10400,
  "thumbsUp": 18,
  "thumbsDown": 0,
  "plays": 40144,
  "url": "https://dts.podtrac.com/redirect.mp3/landmark-dynamic.barstoolsports.com/stream/EplzUaEORrWMZVrDHVFKoFr1/audio.mp3?v=eyJzcmMiOiJ…&etag=eccbc87e",
  "name": "Football With Booger McFarland, NFL Preseason Week 1, …",
  "descriptionText": "<p>NFL preseason week 1 … </p>",
  "isAudio": true,
  "downloadNumber": 34744,
  "streamingNumber": 5400
}
```
(raw body quoted inline)

**Field semantics (measured):**

| Field | Meaning |
|---|---|
| `podcastId` | Echoes the podcast id. Present even when the id is unknown. |
| `episodes` | **Always exactly 100.** `limit` and `offset` query params are accepted and **ignored** (measured: `?limit=5` and `?offset=50` both still returned 100 elements, byte-identical size). This is the directory's "top 100", not a paged list. |
| `guid` | The feed's episode guid — the stable dedup key (report §4.5). |
| `episodeId` | **The episode id.** Directory-scoped integer; unique within the response. The value `get_episode.php` takes. |
| `episodeUrl` / `url` | **The playable audio URL.** Both carry the same value; `url` is a duplicate. See §3. |
| `publicationDate` | **epoch ms** (measured: `1786943765000` → 2026-08-17 05:16 UTC). |
| `duration` | **milliseconds** (measured: `9819649` → 2h43m40s). Note the unit flip vs `averageDuration` in §2.1. |
| `type` / `isAudio` | `AUDIO` and `true` on every element of this response. |
| `artwork` / `thumbnail` | Identical values here; per-episode `itunes:image`. Falls back to podcast art. |
| `description` / `descriptionText` | Two copies of the same HTML; `description` is the raw one (it contains the app's own `<a href="podcastaddict:1040000">` chapter-link markup). |
| `downloads`, `streams`, `plays`, `fullyListened`, `thumbsUp`, `thumbsDown`, `downloadNumber`, `streamingNumber` | Aggregated popularity counters. The `…Number` variants are the **same numbers as strings** — present for schema compatibility, not new data. |
| **Ordering** | **Not chronological.** Measured: `publicationDate` is not monotonic (`1786943765000, 1787111578000, 1787283915000, 1786679100000, 1561338660000…`), and the tail reaches back to 2021. It is a popularity-ordered "top 100". **Do not assume newest-first.** |

**Unknown podcast id [M]:** `?id=999999999` → HTTP 200,
`{"podcastId":999999999,"episodes":[]}`.
**No parameters [M]:** → HTTP 400, `Parameters missing`.

### 2.3 `get_episode.php` — one episode, flat shape

```
GET https://addictpodcast.com/ws/php/v4.1/get_episode.php?id=230579356
```

**Verification [M]:** HTTP 200, 3540 bytes, `application/json; charset=UTF-8`, 2026-10-09T18:01:37Z.

**Response (verbatim, elisions marked):**

```json
{
  "id": 230579356,
  "podcast_id": 2280906,
  "url": "https://dts.podtrac.com/redirect.mp3/landmark-dynamic.barstoolsports.com/stream/EplzUaEORrWMZVrDHVFKoFr1/audio.mp3?v=eyJzcmMiOiJ…&etag=eccbc87e",
  "rssFeedUrl": "https://mcsorleys.barstoolsports.com/feed/pardon-my-take",
  "name": "Football With Booger McFarland, NFL Preseason Week 1, …",
  "type": "AUDIO",
  "thumbnail": "https://chumley.barstoolsports.com/union/2026/08/17/IMG_0021-1.fc2825d1.jpg?crop=3000%3A3000%2Csmart&format=jpeg",
  "podcastThumbnail": "https://chumley.barstoolsports.com/union/2024/02/21/PMT-DK.994e7478.jpg?crop=3000%3A3000%2Csmart&format=jpeg",
  "podcast_name": "Pardon My Take",
  "podcastDescription": "<p><p>On \"Pardon My Take,\" … </p>",
  "podcastAuthor": "Barstool Sports",
  "podcastNewUrl": null,
  "accepted": 1,
  "canBeFeatured": 1,
  "description": "<p>NFL preseason week 1 … </p>",
  "publicationDate": 1786943765000,
  "duration": 9819649,
  "downloadNumber": "34744",
  "streamingNumber": "5400",
  "fullyListenedTo": "10400",
  "thumbsUp": "18",
  "thumbsDown": "0",
  "guid": "ed95a029-176d-4cf0-a5e1-f9efb5f27b86",
  "guid_hash": null
}
```

Note the different conventions in one payload: counters are **strings** here (`"34744"`) but
**numbers** in `get_podcast_top_episodes.php` (`34744`). Any decoder must accept both.
`id` (snake) vs `podcastId`/`episodeId` (camel) also differ between the two endpoints.

---

## 3. Capability 3 — Resolve a playable / downloadable audio URL

This is the critical one. The report calls `e4.I(String baseUrl)` a "heuristic pile" (§4.3) and
says the media URL "typically lives in the episode's `more_info` or an enclosure node" (§12
framing). **The answer for this backend is simpler than that, and it is measured.**

### 3.1 Where the URL comes from

The backend already stores the resolved enclosure. It is the **`episodeUrl`** field (duplicated
as **`url`**) of every element of `get_podcast_top_episodes.php`'s `episodes[]`, and the
**`url`** field of `get_episode.php`. There is no `more_info` wrapper, no `encrypted_media_url`,
no `resolve_ref` — those are JioSaavn's conventions (our current provider), not this one.

Nothing has to be selected: the backend hands back one URL per episode and it is the one to
play. There is no bitrate ladder to choose from, so our `Quality` has nothing to bite on here
(see §6).

### 3.2 Does it need signing? **No. Measured, twice.**

**Test 1 — full body, no headers of any kind** (2026-10-09 ~17:46 UTC):

```
curl --http1.1 <episodeUrl verbatim from the JSON>
  → HTTP 200, 117 103 291 bytes, content-type: audio/mpeg,
    first 4 bytes: ff fb 70 64   (MPEG-1 Layer III frame sync, 128 kbps / 44.1 kHz)
```

**Test 2 — ranged, no headers** (measured live, 2026-10-09T18:14:18Z):

```
curl -L --http1.1 -r 0-4095 <episodeUrl>
  → HTTP 206 Partial Content
    Content-Length: 4096
    Content-Range: bytes 0-4095/117103291
    Accept-Ranges: bytes
    Content-Type: audio/mpeg
    X-Cache: HIT
    first 8 bytes: fffb7064000ff000
```

No `X-App-Key`, no `X-App-Installer`, no app User-Agent, no `Cookie`, no `Authorization` was
sent on either media request. `Accept-Ranges: bytes` and a 206 prove the endpoint is
download-resumable, which is what our `DownloadEngine`'s breakpoint store needs.

**Conclusion [M]: the enclosure requires no signature from us.** The `X-App-Key` credential is
consumed only by `addictpodcast.com`.

### 3.3 What the redirect chain actually is (and who signs it)

Measured, followed with zero headers:

| Hop | Host | Status |
|---|---|---|
| 1 | `dts.podtrac.com/redirect.mp3/…` | 302 → publisher CDN |
| 2 | `landmark-dynamic.barstoolsports.com` | 301 → `pipe-stream.barstoolsports.com` |
| 3 | `pipe-stream.barstoolsports.com/…?token=<JWT>&i=<hash>` | 206 / 200, `audio/mpeg` |

The only token in the chain is on hop 3, and it is **minted by the publisher, not by the
backend**. Decoded JWT claims:

```json
{"iat":1000000000,"exp":10000000000,"data":{"c":"audio/mpeg","v":{…},"f":[…19 S3 object paths…]}}
```

`iat` = 2001-09-09, `exp` = 2286-11-20. **It does not meaningfully expire**, and we cannot
refresh it — but we do not need to, because it arrives as part of the URL the backend gives us.
This is exactly the "does the resolve need a signed URL" question from our
`MusicProvider.resolve()` design, and the answer here is: **there is no resolve step.**

Measured caveat: hop 3 is flaky from this network — 5 of ~10 attempts failed with
`curl: (35) Recv failure: Connection reset by peer` before one succeeded, and hop 2 reset
several times too. That is a property of `barstoolsports.com`, not of `addictpodcast.com`
(nothing on either hop asked for a credential). A player must be prepared for mid-stream
resets regardless of backend.

### 3.4 The URL is just the feed's enclosure — cross-checked against the RSS

Measured live 2026-10-09: fetched the feed itself and compared.

Feed: `https://mcsorleys.barstoolsports.com/feed/pardon-my-take` — HTTP 200,
`application/xml; charset=utf-8`, publicly fetchable. For the same episode guid:

```xml
<enclosure url="https://dts.podtrac.com/redirect.mp3/landmark-dynamic.barstoolsports.com/stream/v2/EplzUaEORrWMZVrDHVFKoFr1/audio.mp3?v=1&etag=eccbc87e"
           length="0" type="audio/mpeg"/>
```

versus the backend's `episodeUrl`:

```
https://dts.podtrac.com/redirect.mp3/landmark-dynamic.barstoolsports.com/stream/EplzUaEORrWMZVrDHVFKoFr1/audio.mp3?v=<base64 {src,bec,afp}>&etag=eccbc87e
```

Same host, same object id (`EplzUaEORrWMZVrDHVFKoFr1`); the backend has **resolved the
podtrac redirect once and stored the result**. Decoding its `?v=`:

```json
{"src":"https://barstool-podcasts.s3.us-east-1.amazonaws.com/barstool-sports/pardon-my-take/EplzUaEORrWMZVrDHVFKoFr1/2026/08/17/pmtaudio-8-17.680f3b23.96s.d996ea81.mp3","bec":["male","sports"],"afp":null}
```

Two consequences:

1. **The backend value is a convenience, not a requirement.** The same enclosure is in the
   public feed; we can get it ourselves with no credential at all.
2. **The backend value is a snapshot.** It will go stale when the publisher rotates the
   redirect query, which feeds do routinely. If we cache the backend URL we need a refresh
   path; if we parse the feed ourselves we always have the current one.

Also measured on this feed: `<enclosure … length="0">` — the publisher declares size `0`, so
byte-length is unavailable from the feed and the report's `@length > 3000` heuristic (§4.3)
cannot fire. Plan for unknown sizes.

### 3.5 The report's `e4.I()` heuristics are still the right fallback, but they are not needed for the happy path

Report §4.3 applies when **we parse a feed ourselves**. It matters if we go the RSS route (§7),
and its rules are worth restating because they are the part that is genuinely reusable:
type-first selection filtered by `accept_audio`/`accept_video`; `isDefault="true"` bumped to
index 0; MIME inferred from the URL extension when `@type` is absent; `.mp4`-vs-audio conflicts
resolved toward the video extension set; SoundCloud forced to audio; `@length > 3000` implying
video. **None of it was needed to make playback work through this backend**, because the
backend does the selection for us. That is a measured statement about this backend, not a
claim that the report's analysis is wrong.

### 3.6 Tracker-prefix stripping is still required

Every enclosure on this podcast is `dts.podtrac.com/redirect.mp3/…`-wrapped. The report's §3.8
gives the two arrays that matter: `fo5.f0()`'s `{"chtbl.com/track/", "chrt.fm/track/"}` and
`fo5.s0()`'s workaround list (`podtrac.com/redirect.mp3/`, `pdst.fm/e/`,
`prfx.byspotify.com/e/`, `op3.dev/e/`, `claritaspod.com/measure/`, `mgln.ai/track/`, …).
**We do not need to strip anything to make the URL play** — podtrac resolves fine (measured,
§3.2) — but stripping removes a redirect, removes a tracking third party from our network
path, and removes a failure domain. Recommend porting `fo5.s0()` verbatim; it is pure string
logic with no Android dependencies.

---

## 4. Capability 4 — Discover a podcast by feed URL (user pastes a link)

### 4.1 `search_podcast_by_url.php` — **works**

```
GET https://addictpodcast.com/ws/php/v4.1/search_podcast_by_url.php
    ?url=https%3A%2F%2Fmcsorleys.barstoolsports.com%2Ffeed%2Fpardon-my-take
X-App-Key: d2fad335-a44d-4aeb-9e5b-67b19a15572c
X-App-Installer: com.android.vending
User-Agent: PodcastAddict/v5 (+https://podcastaddict.com/; Android podcast app)
```

The app lower-cases the query and `Uri.encode`s it before sending (`to3.java:1070`,
`POBCoreNativeConstants.NATIVE_LINK_URL`). **Measured: case does not matter** — sending
`https://MCSorleys.BarstoolSports.com/Feed/Pardon-My-Take` percent-encoded returned a
byte-identical 1445-byte response. So we can skip the lower-casing, but it is harmless.

**Verification [M]:** HTTP 200, 1445 bytes, `application/json`, `cf-cache-status: HIT`,
requested 2026-10-09T18:01:35Z, answered same second. Ran it 5 times with different inputs.

**Response shape — `{"results":[…]}`:**

```json
{
  "results": [
    {
      "id": 2280906,
      "iTunesId": "1089022756",
      "url": "https://mcsorleys.barstoolsports.com/feed/pardon-my-take",
      "name": "Pardon My Take",
      "description": "<p><p>On \"Pardon My Take,\" Big Cat &amp; PFT Commenter …  …[420 chars elided]…",
      "language": "",
      "thumbnail": "https://chumley.barstoolsports.com/union/2024/02/21/PMT-DK.994e7478.jpg?crop=3000%3A3000%2Csmart&format=jpeg",
      "type": "AUDIO",
      "keywords": "",
      "author": "Barstool Sports",
      "explicit": 1,
      "team_id": -1,
      "accepted": 1,
      "canBeFeatured": 1,
      "timestamp": 20261009095349,
      "firstPublicationDate": 1602651393000,
      "lastPublicationDate": 1791524589000,
      "episodeNb": 910,
      "averageDuration": 8246,
      "errorcounter": 0,
      "httpCode": 200,
      "isDeprecated": 0,
      "newUrl": null,
      "websubId": -1,
      "lastmod": 0,
      "manuallyInspected": 0,
      "lastMetadataUpdateTimestamp": 20261009102349,
      "estimatedNextRelease": 20261009105338
    }
  ]
}
```
(raw body quoted inline)

**Exact key-set difference from `get_podcast.php` (measured, set-diffed):** this response is
**missing** `reviews`, `categories` and `subscribers`, and is otherwise identical — same `id`,
same field order, same values. So the by-url endpoint is a cheaper `get_podcast` without the
social/directory extras. **Parse them with the same DTO and treat those three as nullable.**

**Misses [M]:**

| Input | Status | Body |
|---|---|---|
| `https://example.com/feed.xml` | 200 | `{}` |
| `https://feeds.megaphone.fm/stuffyoushouldknow` (plausible but unindexed) | 200 | `{}` |
| *(no `url` param)* | 400 | `Missing parameters` |

So a miss is an **empty object**, not an empty `results` array and not a 404. That is a decode
trap: `{"results":[…]}` vs `{}` are two different top-level shapes from the same endpoint, and
only the success case has the `results` key.

### 4.2 Companion lookups — both work

```
GET https://addictpodcast.com/ws/php/v4.1/get_podcast_server_id.php?url=<encoded feed url>
  → HTTP 200, 7 bytes, text/html; charset=UTF-8, body: 2280906
GET https://addictpodcast.com/ws/php/v4.1/get_podcast_itunes_id.php?url=<encoded feed url>
  → HTTP 200, 10 bytes, text/html; charset=UTF-8, body: 1089022756
```

**Measured 2026-10-09T18:01:39Z.** Both return a **bare scalar**, not JSON, with
`content-type: text/html` — a `json.decode` will throw. Feed-URL → numeric id, and feed-URL →
iTunes id, with no directory hit required. Useful for bootstrapping: `search_podcast_by_url`
gives us the feed URL; these give us the ids the rest of the API wants.

Misses: an unindexed feed returns `-1` from `get_podcast_server_id.php` (measured with
`https://feeds.simplecast.com/54BfDD3E` → `-1`, HTTP 200).

### 4.3 `rss_proxy.php` — **does not work from here**

```
GET https://addictpodcast.com/ws/php/v4.1/rss_proxy.php?url=<encoded feed>&ts=<epoch ms>
  → HTTP 401, content-type: text/plain, body: missing token
```

**Measured**, with `ts` set to the current epoch-ms as the report's §A30 describes
(`wn5.java:3665-3672`, `System.currentTimeMillis()`). The `ts` value is not the token; the
server wants something else that the decompiled code does not reveal, and the report's §12 U1
does not cover it either. **Not usable.** We do not need it — we can fetch feeds directly
(§3.4).

---

## 5. What §12 "Undetermined" I resolved, and how

| §12 item | Verdict | How |
|---|---|---|
| **U12 — "Server behaviour: everything in §3.7 is what the app *sends*; the response schemas, rate limits, error codes and required-vs-optional semantics are server-side and were not observed."** | **Resolved for the four capabilities in scope.** | Executed every endpoint. Full response schemas are in §2 and §4 with per-field semantics. Required-vs-optional is now known empirically: `languages` is **required** on browse endpoints and has exactly one accepted format; `id` is required on `get_podcast.php`/`get_podcast_top_episodes.php`; `url` is required on `search_podcast_by_url.php`. Error codes are `400 Missing parameters` / `400 Parameters missing` / `200 null` / `200 {}` / `200 -1`. Edge caching (`max-age=3600`) is now known. |
| **U1 — "Exact parameter lists for ~25 endpoints."** | **Partially resolved, in the useful direction.** | The report's listed parameter names are **correct** where I could test them: `search_podcast_by_url.php` takes `url`, `get_podcast.php` takes `id`, `get_top_podcasts.php` takes `languages`/`offset`/`limit`/`isAudio`/`durationFilter`, `get_podcast_server_id.php` and `get_podcast_itunes_id.php` take `url`. And the report **omitted** one critical fact that I recovered: `languages` is mandatory and must be the quoted-list format (§1.3). Two endpoints in the report's table (`searchpodcast.php`, `search_episodes.php`) I could not make succeed at all (§1.1). |
| **U2 — the JSON field names for the stats endpoints** | **Not attempted — out of scope** (telemetry is explicitly excluded). No calls made. |

New undetermined items created by this work are listed in §8.

---

## 6. The cursor into our app — concrete field mapping

Our seam is `shared/src/commonMain/kotlin/dylan/provider/MusicProvider.kt`; the models are
`shared/src/commonMain/kotlin/dylan/model/Models.kt`. Both read in full. `SaavnProvider.kt` +
`saavn/Mapper.kt` are the reference implementation this mapping follows (`mapCard` returns
`null` + a `Drift` for one unusable card rather than dropping the page).

### 6.1 `MiniEntity` — one podcast card

`MiniEntity(songKey, albumId, title, subtitle, type, image, permaToken, artistId)`

| `MiniEntity` field | Source | Note |
|---|---|---|
| `title` | `results[].name` / `get_podcast.name` | |
| `subtitle` | `results[].author` | Show name goes in `subtitle` because the *title* slot is the episode in our player. If the UI wants the show as the headline, swap. |
| `image` | `results[].thumbnail` | Only one size exists; set `artUrl150` **and** `artUrl500` to it. Do **not** run `art500()`'s `150x150→500x500` rewrite on it — that rewrite is JioSaavn-specific and would corrupt this URL. |
| `type` | `"podcast"` (our string, not theirs) | Their `type` is `AUDIO`/`VIDEO`; that is a media-kind flag, not an entity kind. Keep theirs in a separate field if the UI needs to grey out video podcasts. |
| `albumId` | `String(results[].id)` | The podcast id as an `album` handle is the honest fit: a podcast *is* the album of its episodes. |
| `songKey` | `null` | A podcast card is not a playable song. |
| `permaToken` | `null` | No such concept here. |
| `artistId` | `null` | `author` is a free-text string; there is no author id in the response. Do not synthesise one from the name — two podcasts with the same author string would collide. |

`Paged<MiniEntity>` for `searchAlbumPage` / `topSearchList`-style rails:
`total = 0` (the count is unknown; `get_podcast.php`'s `episodeNb` is a per-podcast episode
count, not a result count), `page = offset / limit`. `Paged.total` being `0` is honest here;
the backend gives no total.

### 6.2 `Song` — one episode

`Song(key, title, subtitle, albumId, albumName, artUrl150, artUrl500, durationS, has320, resolveRef, permaToken, artistName, artistToken)`

| `Song` field | Source | Note |
|---|---|---|
| `key` | `SongKey("podcastaddict", CachePath.segment(episodeId))` | **Use `episodeId`, not `guid`.** It is a stable directory integer; `guid` is publisher-supplied and can be recycled or non-unique across podcasts. Keep `guid` alongside as the dedup key for "already played" (report §4.5), exactly as the app does. |
| `title` | `episodes[].title` | Note `episodes[].name` is the same value — a duplicate field. |
| `subtitle` | `podcast.name` (from the enclosing `get_podcast.php` call) | Needs a second call, or take it from the `episodeUrl` response of `get_episode.php` which already carries `podcast_name`. |
| `albumId` | `String(episodes[].podcastId)` | |
| `albumName` | podcast `name` | |
| `artUrl150` / `artUrl500` | `episodes[].artwork ?: episodes[].thumbnail`, falling back to podcast `thumbnail` | Same URL for both; no `art500()` rewrite. |
| `durationS` | `episodes[].duration / 1000` | **ms → s.** Cross-check against the report's `S(String)` normaliser (§4.3) for feed-parsed values. |
| `has320` | `false` | There is no bitrate ladder. Do not lie and say `true`. |
| `resolveRef` | `episodes[].episodeUrl` | **This is not an opaque ref** — it is already the public media URL (§3.2). Our `resolve()` seam exists to turn a ref into a URL; here the ref *is* the URL. That is a genuine mismatch with `MusicProvider.resolve()`'s design and must be handled explicitly (below). |
| `permaToken` | `null` | |
| `artistName` | podcast `author` | |
| `artistToken` | `null` | No author id exists. |

### 6.3 The `resolve()` mismatch — decide this before writing code

`MusicProvider.resolve(resolveRef, q): CatalogResult<SignedStream>` exists because JioSaavn
hands back an encrypted ref that must be exchanged. Measured here, **there is no exchange**:
the URL is public, unauthenticated and non-expiring in practice (§3.2–3.3).

Two honest options:

1. **`resolve()` is a no-op that validates.** `resolveRef` is already a URL; `resolve` checks
   it is a well-formed `http(s)` URL and returns `SignedStream(url = resolveRef, type = "audio")`,
   issuing `HEAD`/ranged `GET` only if we want to pre-flight it. `Quality` is ignored because
   there is nothing to select. This keeps one code path and one set of `ErrorCode`s.
2. **Skip `resolve()` for this provider** and hand the enclosure straight to the engine, with a
   narrow adapter that reports `NO_SOURCE` when `resolveRef` is blank.

Option 1 is better: it preserves the `ErrorCode` taxonomy (`NO_SOURCE` vs `NETWORK` vs
`FORBIDDEN_REGION`) that `DownloadEngine.kt:398` depends on, and a failed media fetch then
surfaces as a real error instead of a silent `null`. **Whatever is chosen, `Quality` must not be
presented to the user for this provider** — offering a 320 kbps option that does nothing is a
lie in the UI.

Also note `SignedStream.type`: use the real MIME from the feed/response (`audio/mpeg`), which
we measured, not a guess.

### 6.4 What maps to nothing in our model (drop, but log as `Drift`)

`team_id`, `accepted`, `canBeFeatured`, `timestamp`, `lastMetadataUpdateTimestamp`,
`estimatedNextRelease`, `websubId`, `lastmod`, `errorcounter`, `httpCode`, `isDeprecated`,
`newUrl`, `manuallyInspected`, `keywords`, `explicit`, `episodeNb`, `averageDuration`,
`subscribers`, `categories[]`, `reviews[]`, `downloads`/`streams`/`plays`/`fullyListened`/
`thumbsUp`/`thumbsDown` (and their `…Number` string twins), `guid_hash`, `podcastNewUrl`.

Several are worth keeping even though `Song`/`MiniEntity` have no slot: `isDeprecated` +
`newUrl` are the feed-moved signal (report §A29) and belong in whatever persistence we keep;
`explicit` gates playback in some jurisdictions; `guid` is the played/dedup key. Per
`Mapper.kt`'s pattern, a card that cannot become a `Song` is a `Drift`, never a dropped page.

---

## 7. Is this backend reusable by our app?

**No. Not as a shipping dependency. It is not a public API, and using it would mean using
another company's private backend under a key extracted from their binary.**

That is the direct answer, and here is the reasoning without hedging.

**The key is a shared secret in someone else's binary.** `d2fad335-a44d-4aeb-9e5b-67b19a15572c`
is a hard-coded string constant in Podcast Addict's APK (report §3.6). It is not a per-install
token, not OAuth, not revocable-by-us: it is one secret shared by every copy of their app, and
we only have it because we decompiled theirs. Shipping it in our app means shipping a
credential we are not entitled to, extracted by reverse engineering. Every user of our app
would be making authenticated requests as "a Podcast Addict install" using a key that belongs
to Podcast Addict. That is impersonation of their clients at scale, and it is the reason the
answer is no regardless of how well the endpoints work.

**There is no contract to rely on.** The service is undocumented and unversioned-in-practice.
Two data points from this session prove the point better than any argument:

* `searchpodcast.php` and `search_episodes.php` — two of the endpoints we were asked to
  prove — **stopped working** without notice, without a deprecation period, and with an error
  message that gives no clue what changed (§1.1). Our app would have broken the same day.
* `languages` silently returns **zero results** rather than an error when given the wrong
  format (§1.3). A silent empty list is the worst failure mode available: it looks like "no
  podcasts in English".

There is no status page, no changelog, no SLA, no support address, no rate-limit documentation
(and we would find the limits by hitting them, which the report explicitly warns against, §3.7
"Respect rate limits"). Any of these can change at any time and we would learn about it from a
crash report.

**It is rate-limited and unaccountable.** ~100 requests across ~45 minutes produced no 429 and
no block, so the limit is not trivially low. But nothing tells us where the ceiling is, whether
it is per-IP or per-key, or what happens when we cross it. If our app shipped and grew, we
would be consuming someone else's capacity with someone else's credential, and the only remedy
available to them would be to block the key — which would take down our podcast feature with
no notice.

**And it is not even necessary.** Measured: the media URL is a plain public URL (§3.2), the
feed is public (§3.4), and the podcast metadata we would want is a thin cache of feed fields we
can read ourselves. The backend's actual value-add is *discovery* (a directory of podcasts with
popularity ordering and iTunes ids) — and the discovery endpoint we most wanted is the one that
is broken.

### What *is* legitimately reusable

All of the following are either public, documented, or standards-based, and none of it requires
extracting a key from anyone's binary:

| Asset | Where it comes from | Status |
|---|---|---|
| **iTunes / Apple Podcasts search** | Public, documented, no key. Report §3.7 B1: `https://itunes.apple.com/search?media=podcast&limit=100&term=…` (+ optional `&g=<storefrontId>`), B2 episode search, B3 `lookup?id=`. Field list at report §3.7 (after the table): `trackName`, `feedUrl`, `collectionId`, `artworkUrl160/100/60/30/600`, `releaseDate`, `trackCount`, `artistName`, `description`, `primaryGenreName`, `contentAdvisoryRating`. | **Recommended primary catalog.** This is what the app itself calls *first* and only falls back to the backend from (report §5.1: `if (!z && arrayList.isEmpty()) o(true)`). Following that order costs us nothing and removes the dependency entirely. |
| **RSS / Atom feed parsing** | Report §4.1–4.7. The element/namespace inventory (§4.1, the definitive list) is a battle-tested catalogue of what real-world feeds contain, including the awkward cases: `podcast:alternateEnclosure`, `podcast:chapters`, `podcast:transcript`, `psc:chapters`, `media:content`, Atom `link rel=enclosure`, `content:encoded`, `dc:*` on local name only, `acast:locked-item`, `itunes:new-feed-url`. | **Recommended.** Their SAX handler's *shape* (not the code) is the design document for ours. Note they deliberately do **not** set `setNamespaceAware(true)` and match on qualified names — worth copying, because it tolerates feeds with broken namespace prefixes. |
| **The enclosure heuristics** | Report §4.3 (`e4.I`) and §4.4 (`y85.l`). | **Recommended.** Pure logic, no credentials. §3.5 restates the rules. The one to be careful with is `@length > 3000`-implies-video: on the feed I measured, `length="0"` (§3.4), so that rule cannot fire and size is genuinely unknown — our `DownloadEngine` must tolerate unknown `Content-Length`. |
| **Chartable / tracker prefix stripping** | Report §3.8: `fo5.f0()`'s `{"chtbl.com/track/", "chrt.fm/track/"}` and `fo5.s0()`'s two arrays (`podtrac.com/redirect.mp3/`, `podtrac.com/pts/redirect.mp3/`, `pdst.fm/e/`, `prfx.byspotify.com/e/`, `op3.dev/e/`, `claritaspod.com/measure/`, `mgln.ai/track/`, `/clrtpod.com/m/`, `verifi.podscribe.com/rss/p/track/`, `play.podtrac.com/`, `pscrb.fm/rss/p/`, `arttrk.com/p/`, `p.podderapp.com/`, `.vpixl.com/`, `a.pdcst.to/`, `tracking.swap.fm/track/`, `pdrl.fm/`, `.podderapp.com/`), plus the analytics-param strip list (`_ga`, `_gl`, `_gac`, `_gclid`, `_gaexp`, `_gat`, `_gid`, `_utm_*`) and the scheme allow-list (`feed://`, `itpc://`, `itms://`, `pcast://`, `podcast://`, `podcastaddict://`). | **Recommended.** Pure string manipulation, and independently verified as useful: every enclosure on the podcast I tested was podtrac-wrapped (§3.6). |
| **`guid` as the played/dedup key** | Report §4.5. | **Recommended**, with the caveat from §6.2: use it for dedup, not for identity. |
| **The User-Agent strings** | Report §3.4. | **Free to use** for their own hosts, but note §3.4's measured caveat: barstoolsports.com reset the connection on curl's default UA and on the Podcast Addict UA alike, and served 206 to a Chrome UA and to no UA at all. Feed hosts are picky about UA in inconsistent ways; send a browser UA and be ready to retry. |

**Recommended architecture if we build podcasts at all:** Apple Podcasts search for discovery
(report §3.7 B1/B3) → feed URL → our own RSS/Atom parser with the §4.1 element inventory →
§4.3 enclosure selection → §3.8 URL normalisation → play. That is the whole pipeline, with no
third-party key in it, and every component is documented in the report with decompiled
evidence.

---

## 8. Everything I could not establish

Blunt list. Nothing here is guessed at or papered over.

1. **Why `searchpodcast.php` and `search_episodes.php` reject every request.** ~100 GETs, four
   parameter families, four HTTP verbs, both hosts I could reach. The 400 `Missing parameters`
   fires before parameters are read (it answers `OPTIONS` identically). It is a server-side
   change I cannot see the cause of. **Free-text search is not deliverable through this
   backend.** I did not capture a real device request or attempt to forge a device identity.

2. **`rss_proxy.php`'s token.** HTTP 401 `missing token` with `url` + `ts` exactly as
   report §A30 describes. Not usable; not needed.

3. **Rate limits.** No 429 in ~100 requests over ~45 minutes, which bounds the ceiling from
   below and says nothing about where it is. I stopped rather than find it, per instructions.

4. **`get_podcasts_by_author.php`.** Returns `Missing parameters` / HTTP 400 with `id=`; the
   report §12 U1 already flags it as undecompilable and I could not guess a working parameter
   name. Out of scope anyway.

5. **`get_topics.php`.** `Missing parameters` with no parameters (HTTP **200**, not 400 — a
   different guard). Not attempted further; out of scope.

6. **Whether any endpoint requires a real `X-App-Installer` value.** `com.android.vending` and
   `com.amazon.venezia` were both accepted at the PHP layer (the 403s in that test came from
   dropping the User-Agent, not the installer). I never saw a request rejected *for* its
   installer. Unresolved, and not worth resolving.

7. **Whether the episode `top 100` is stable.** It is `cf-cache-status: DYNAMIC` and returned
   byte-identical bodies four times in 20 minutes, but I made no calls far enough apart to see
   it reorder. Assume it is popularity-ordered and changes.

8. **The report's `e4.I()` heuristics, verified against reality.** I did not run them against a
   corpus of feeds. The one feed I fetched had `type="audio/mpeg"` and a single enclosure, so
   none of the interesting branches (`isDefault` bumping, `.mp4`-vs-audio conflict, extension
   sniffing, size-implied video) were exercised. Those remain **inferred** from the
   decompilation, not measured.

9. **Geography.** Every call came from one machine on one network. `pipe-stream.barstoolsports.com`
   reset the connection on roughly half of attempts (§3.3) — I cannot tell whether that is
   geo, CDN load, or TLS fingerprinting. Nothing was geo-blocked outright.

10. **`get_podcast.php?id=1`'s response is a JSON object, not an array.** Worth flagging
    because my first read of it was wrong: `python3 -m json.tool` output scrolled off and I
    briefly thought the endpoint returned a list. It does not. A decoder that assumes an array
    will break on this endpoint.

---

## 9. Reproduction

```bash
export PA_KEY='d2fad335-a44d-4aeb-9e5b-67b19a15572c'
BASE='https://addictpodcast.com/ws/php/v4.1'
H=(-H "X-App-Key: $PA_KEY"
   -H 'X-App-Installer: com.android.vending'
   -H 'User-Agent: PodcastAddict/v5 (+https://podcastaddict.com/; Android podcast app)')

# 4 — discover by feed URL                                     → 200 {"results":[…]}
curl -sS "${H[@]}" "$BASE/search_podcast_by_url.php?url=https%3A%2F%2Fmcsorleys.barstoolsports.com%2Ffeed%2Fpardon-my-take"

# 2a — podcast metadata                                         → 200 {…}
curl -sS "${H[@]}" "$BASE/get_podcast.php?id=2280906"

# 2b — episodes, incl. the audio URL                            → 200 {"podcastId":…,"episodes":[…100…]}
curl -sS "${H[@]}" "$BASE/get_podcast_top_episodes.php?id=2280906"

# 3 — the enclosure, no credentials at all                      → 206 audio/mpeg
URL=$(curl -sS "${H[@]}" "$BASE/get_podcast_top_episodes.php?id=2280906" \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["episodes"][0]["episodeUrl"])')
curl -sS -L --http1.1 -r 0-4095 "$URL"

# 1 — free-text search                                          → 400 "Missing parameters"
curl -sS "${H[@]}" "$BASE/searchpodcast.php?term=stuff%20you%20should%20know&type=AUDIO&languages=%27English%27%2C%20%27%27&exactName=0&dateFilter=0&explicitFilter=0"
```

The verification scripts are gone — `verify.sh`, `verify_audio.sh` and the `probe*.sh` family
were deleted with `docs/contract/`. The requests they made are documented inline above, so the
probes are reconstructible from this document, but not re-runnable as-committed.

**Working-tree state at the time it was written:** uncommitted in a since-deleted `pod-api`
worktree; nothing committed, staged, pushed, stashed or merged, and no Gradle command was run
anywhere. This document now lives in `main`, committed at `868cb3b`, alongside the two companion
reports. `docs/contract/` — its raw evidence — no longer exists; see the provenance note at the top.
