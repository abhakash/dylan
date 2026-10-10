# Spotify support — feasibility study & integration design

**Status:** proposal only. No file in this repo was changed.
**Date:** 2026-10-09
**Analysed:** `librespot` @ `e023adbbf017ae1fc10d01531dbe50c409786f2d` (`v0.8.0-22-ge023adb`, 2026-10-05, "chore: fix formatting and lints for Rust 1.99 (#1776)"), cloned to
`/private/var/folders/0x/tq2_tw6d7_lckb2kqwt23klc0000gn/T/opencode/librespot`. Workspace version `0.8.0`
(`Cargo.toml:[package] version = "0.8.0"`). All `path:line` references below are against that commit unless
marked otherwise. Repo references are against this working tree.

---

## 1. Verdict

**NO-GO on shipping Spotify playback/offline through librespot's protocol path in a distributed app.
CONDITIONAL GO on exactly one narrow thing: a Web-API-only *metadata* provider behind the existing
`MusicProvider` seam, with no audio and no offline.**

The load-bearing reasons, in order:

1. **The audio bytes are ciphertext, and the repo's download pipeline cannot consume them.** Spotify's CDN serves
   AES-128-CTR-encrypted files (`audio/src/decrypt.rs:5-29`) under a per-file key delivered over the private AP
   session (`core/src/audio_key.rs:81-109`). `dylan.provider.SignedStream` + `dylan.download.Transfer`
   (`download/Transfer.kt`) assume an HTTPS URL that serves **plaintext** and is Range-resumable. Handing a
   Spotify CDN URL to `resolve()` would produce a committed `.part` of ciphertext that `sniffContainer`
   (`download/Container.kt:51-58`) rejects anyway, because it only recognises `ftyp`/ID3/frame-sync — and
   Spotify's best-quality default is **Ogg Vorbis** (`metadata/src/audio/file.rs:18-41`), which is not a
   recognised container at all. This is a second download pipeline, not a provider.
2. **The license does not permit the protocol path.** Spotify Developer Terms v10 (eff. 15 May 2025)
   §IV.2.2.b forbids "reverse-engineering… the Spotify Platform …, Spotify Service, or Spotify Content";
   §IV.2.3.d forbids "facilitating 'stream ripping' or other functionalities that make it easier for users to
   capture or otherwise make permanent copies of Spotify Content"; §IV.3.2 permits only "temporary caching of
   metadata and cover art; or Conditional Downloads". librespot itself carries the same reading in its own
   README: *"Using this code to connect to Spotify's API is probably forbidden by them. Use at your own
   risk."*
3. **There is no supported native path to the audio, so the ToS-clean route cannot deliver the product.**
   The supported ways to get Spotify audio are the Web Playback SDK (browser JS + EME, Premium only,
   non-mobile-only Premium excluded) and the now-deprecated iOS/Android app-remote SDKs (which never gave you
   audio bytes at all). Neither is usable from Kotlin Multiplatform.
4. **librespot has no search.** There is no search implementation anywhere in the tree; the only query path is
   a *context* URI (`core/src/spclient.rs:862`, `:879-897`) that returns track URIs and a string map
   (`protocol/proto/context_track.proto`) — no album/artist/playlist results, no popularity, no scores.

So: the feature as envisioned (Spotify catalog + offline download + playback, integrated with the existing
engine) is **not feasible** without either a second download/decrypt pipeline *and* a decision to ship ToS
violating code. The single defensible deliverable is a read-only Spotify **metadata** provider over the public
Web API, and even that is capped at 5 allowlisted users per app in development mode
(`developer.spotify.com/documentation/web-api/concepts/quota-modes`).

**Recommendation:** spend the effort on milestone M1 (§7) — a Web-API metadata provider behind
`MusicProvider`, no audio, no Rust, no CI change — and stop there unless a written agreement with Spotify exists.

---

## 2. What librespot actually provides, from its source

### 2.1 Capability table

| Capability | Provided? | Mechanism (source reference) | Notes / gaps |
|---|---|---|---|
| **Search — tracks** | **Partially, and only as a side effect** | `Spotify:search:<query+with+plus>` accepted as a special *context* URI: `core/src/spclient.rs:862`, known result shapes at `:856-878`; resolved by `SpClient::get_context` → `GET /context-resolve/v1/{uri}` (`core/src/spclient.rs:879-897`). Returns a `Context` protobuf of `ContextPage`s (`protocol/proto/context_page.proto`) each holding `repeated ContextTrack tracks` | `ContextTrack` carries only `uri`, `uid`, `gid`, `map<string,string> metadata` (`protocol/proto/context_track.proto`). **No artists, albums, playlists, shows.** To show a title/artist you must issue one `Track::get` per result (`metadata/src/track.rs:60-68`) — an N+1 with no batching. |
| **Search — albums / artists / playlists** | **No** | No `search` symbol exists outside doc comments and the string constant `SEARCH_IDENTIFIER` (`connect/src/state/context.rs:22`). Verified by `grep -rn "search" --include=*.rs` over the tree: hits are a CLI flag for local files (`src/main.rs:676`), a cache dir walk (`core/src/cache.rs:142`), and two codec comments | Would require an undiscovered endpoint or the public Web API |
| **Search pagination** | **Partial** | `ContextPage.next_page_url` (`protocol/proto/context_page.proto:2`); `SpClient::get_next_page` strips a leading `hm:/` and GETs the remainder (`core/src/spclient.rs:764-768`) | Loose coupling: the URL shape is whatever the server sends |
| **Track metadata** | **Yes** | `Metadata` trait (`metadata/src/lib.rs:42-58`); `Track` (`metadata/src/track.rs:23-50`); requested via `SpClient::get_track_metadata` → `ExtensionKind::TRACK_V4` (`core/src/spclient.rs:625-627`) over `POST /extended-metadata/v0/extended-metadata` (`core/src/spclient.rs:580-593`) | Rich: name, album, artists, disc/number, duration, popularity, explicit, `files`, `alternatives`, restrictions, availability, licensor, language, content ratings, original/version title |
| **Album metadata + track list** | **Yes** | `Album` (`metadata/src/album.rs:26-49`); `get_album_metadata` → `ALBUM_V4` (`core/src/spclient.rs:634-636`) | Discs, covers, copyrights, label, date, availability |
| **Artist metadata + discography + top tracks** | **Yes** | `Artist` (`metadata/src/artist.rs:26-50`); `get_artist_metadata` → `ARTIST_V4` (`core/src/spclient.rs:638-640`) | `top_tracks` are **per-country** (`CountryTopTracks`, `metadata/src/artist.rs:74-77`) — you must pick a market; `albums`/`singles`/`compilations`/`appears_on_albums` are `AlbumGroups` of *album variants* |
| **Playlist metadata + traversal** | **Yes, one shot** | `Playlist` (`metadata/src/playlist/list.rs:26-45`); `tracks()` iterator (`:69-80`); requested via `SpClient::get_playlist` → `GET /playlist/v2/playlist/{base62}` (`metadata/src/playlist/list.rs:91-100`, `core/src/spclient.rs:669-673`) | Returns the whole playlist in one payload. **No documented offset pagination for playlists** — a 10 000-track playlist is one response. `PlaylistAnnotation` (per-user annotation) exists but is `#[allow(dead_code)]` (`metadata/src/playlist/annotation.rs:66-84`) |
| **User library / rootlist** | **Yes** | `SpClient::get_rootlist` → `GET /playlist/v2/user/{user}/rootlist?…&from=&length=` (`core/src/spclient.rs:927-935`) | Paginated by `from`/`length` |
| **Lyrics** | **Yes** | `GET /color-lyrics/v2/track/{id}` (JSON) (`core/src/spclient.rs:647-652`) | Returns the "color lyrics" payload; no line-level sync model is exposed by librespot |
| **Cover art** | **Yes** | `SpClient::get_image` from the `image-url` user attribute (`core/src/spclient.rs:845-854`) | Same template trick the app's Saavn mapper already does with `artUrl150`/`artUrl500` — needs the attribute present on the session |
| **30 s preview clip (unencrypted 96 kbps MP3)** | **Yes** | `SpClient::get_audio_preview` from `audio-preview-url-template` (`core/src/spclient.rs:815-830`) | The only plaintext audio path in the tree |
| **Audio key resolution** | **Yes** | `AudioKeyManager::request(track, file)` sends a `RequestKey` packet on the AP channel, 1 500 ms timeout (`core/src/audio_key.rs:81-109`); response dispatched at `:47-79` | Requires a live AP session. No REST equivalent |
| **CDN URL resolution** | **Yes** | `CdnUrl::resolve_audio` → `SpClient::get_audio_storage` → `GET /storage-resolve/files/audio/interactive/{base16}` (`core/src/cdn_url.rs:68-79`, `core/src/spclient.rs:773-779`); parses `StorageResolveResponse` (`protocol/proto/storage-resolve.proto`) | Handles 4 CDN URL/expiry shapes (`core/src/cdn_url.rs:139-214`), with a 5-minute safety margin (`:17`) |
| **Format selection** | **Yes** | `AudioFileFormat` incl. `OGG_VORBIS_96/160/320`, `MP3_256/320/160/96`, `AAC_24/48/160/320`, `FLAC_FLAC`, `FLAC_FLAC_24BIT`, `XHE_AAC_*` (`metadata/src/audio/file.rs:18-41`) | Quality is a *negotiation*: the file list comes back in metadata and you pick |
| **Decryption** | **Yes** | `AudioDecrypt` = AES-128-CTR, fixed IV `[u8;16]` (`audio/src/decrypt.rs:5-29`) | IV is a constant, not per-file |
| **Streaming fetch** | **Yes** | Range GET requiring **HTTP 206 Partial Content** (`audio/src/fetch/mod.rs:449-487`), `RangeSet` bookkeeping (`:320-323`), read-ahead/prefetch tuning (`:63-118`) | |
| **Whole-file download / caching** | **Yes, but internal and encrypted** | `AudioFile::open` (`audio/src/fetch/mod.rs:380-413`): on completion, if `session.cache()` has an audio path, the **entire file is saved** via `cache.save_file` (`:398-410`); `Cache` with `FsSizeLimiter` (`core/src/cache.rs:264-360`) | The cached bytes are still **ciphertext**; there is no "decrypt and write to an app path" API |
| **Local playback** | **Yes** | `Player` (`playback/src/player.rs:456-596`), `PlayerEvent` channel (`:157+`) | Rust-side audio backends only (rodio/ALSA/Pulse/GStreamer/SDL/JACK). Nothing reusable on Android/iOS |
| **Spotify Connect receiver** | **Yes** | `Spirc` (`connect/src/spirc.rs:297-445`) + zeroconf `getInfo`/`addUser` (`discovery/`, `docs/connection.md`) | Makes *your device* a receiver; not a controller of another device |
| **Controller / playback-state takeover** | **No** | — | librespot is a Connect *receiver* + local player. It does not expose "transfer playback to device X" for arbitrary X |
| **Playback state / queue for a UI** | **Partial** | `PlayerEvent` (`playback/src/player.rs:157+`) and `Spirc` events | Event stream, not a queryable state model like the repo's `PlayerState` |
| **C FFI surface (uniffi / JNI / cdylib / staticlib)** | **No** | `grep -rn "uniffi\|jni\|cdylib\|staticlib\|crate-type"` over the tree returns **zero** matches; `Cargo.toml` `[lib]` declares `name`/`path` only (⇒ `rlib`) | A wrapper crate is mandatory |
| **Ad / free-tier support** | **No** | README: "librespot only works with Spotify Premium" | |

### 2.2 Protocol layer map (read from the source + `docs/`)

```
                     ┌──────────────────────────────────────────────────────────────┐
  accounts.spotify.com│  OAuth2: authorize / api/token / oauth2/device/authorize     │  ← PUBLIC
                     │  (oauth/src/lib.rs:81-87; docs/device-authorization.md)       │
                     └──────────────────────────────────────────────────────────────┘
                                        │  Bearer access token
                                        ▼
   login5.spotify.com/v3/login  ── protobuf ──▶  StoredCredential ⇒ access token
                             (core/src/login5.rs:57-67, :161-200)
   clienttoken.spotify.com/v1/clienttoken ── protobuf + hashcash challenge ──▶ client-token
                                                          (core/src/spclient.rs:151-156, :158-376)
   ┌──────────── AP session (TCP, NO TLS) ────────────────────────────────────────┐
   │ apresolve.spotify.com ─▶ a list of (host, port)                              │
   │ ClientHello / APResponseMessage (Diffie-Hellman) / ClientResponsePlaintext    │
   │ Shannon stream cipher (docs/connection.md, core/src/connection/)              │
   │   ├─ Mercury  GET/SUB/SEND on hm:// URIs   (core/src/mercury/)                │
   │   │    e.g. hm://keymaster/token/authenticated (core/src/token.rs:94-98)      │
   │   │         hm://playlist-annotate/v1/annotation/... (metadata/src/playlist/  │
   │   │           annotation.rs:59-63)                                            │
   │   ├─ Dealer   pub/sub message bus (core/src/dealer/)                          │
   │   └─ RequestKey packet ⇒ 16-byte AES key (core/src/audio_key.rs:101-109)      │
   └──────────────────────────────────────────────────────────────────────────────┘
                                        │  Bearer + client-token headers
                                        ▼
   spclient.spotify.com (HTTPS, host from apresolve; core/src/spclient.rs:123-144)
     /extended-metadata/v0/extended-metadata   protobuf   (metadata)
     /playlist/v2/playlist/{id}                protobuf   (playlists)
     /playlist/v2/user/{u}/rootlist            protobuf   (library)
     /context-resolve/v1/{uri}                 JSON-mapped protobuf (contexts, incl. search)
     /storage-resolve/files/audio/interactive/{id}  protobuf  (CDN URLs)
     /color-lyrics/v2/track/{id}               JSON       (lyrics)
     /radio-apollo/v3/... , /inspiredby-mix/...        JSON (radio)
     /connect-state/v1/devices/{id}            protobuf   (Connect state PUT/DELETE)
                                        │  URL + Range: bytes=n-m  (must answer 206)
                                        ▼
   *.spotifycdn.com / *.akamaized.net / *.scdn.co   ── AES-128-CTR ciphertext ──▶ AudioDecrypt
```

**Two independent auth systems.** The AP session carries Shannon-encrypted protobuf/Mercury; `spclient` is a
separate HTTPS service that needs a *login5* bearer token **and** a *client-token*
(`core/src/spclient.rs:494-516`). The client-token is minted by `clienttoken.spotify.com` using a **hashcash
proof-of-work** on Android/iOS (`core/src/spclient.rs:232-239`, `:271-337`). Metadata, playlist, audio-key and
CDN resolution all ride on the second system.

### 2.3 Auth / session model — what it takes to establish a session

Four credential kinds, all in `core/src/authentication.rs:30-154`:

| Kind | Constructor | Notes |
|---|---|---|
| Username + password | `Credentials::with_password` | Legacy AP login |
| Access token (OAuth) | `Credentials::with_access_token` | `AUTHENTICATION_SPOTIFY_TOKEN`; re-authenticates as stored credential afterwards (`core/src/session.rs:183-201`) |
| Encrypted blob | `Credentials::with_blob` (**deprecated**) | Zeroconf pairing |
| Stored credential | `Login5Manager` | `core/src/login5.rs:161-200`; **mobile-only** (`Login5Error::OnlyForMobile`, `core/src/login5.rs:39-40`) |

`Session::connect(credentials, store_credentials)` (`core/src/session.rs:206-288`) writes the reusable
credential to `<cache-dir>/credentials.json`, mode `0600` (`core/src/cache.rs:315-357`).

For a mobile app the realistic flow is:
`librespot-oauth` (auth-code + PKCE, or device-authorization grant) → access token → `Session::connect` →
stored credential → login5 refresh. Two hard facts from `docs/device-authorization.md`:

- Spotify **enables the device flow per client ID**. Of the three IDs librespot ships
  (`core/src/config.rs:6-8`), only the desktop keymaster ID `65b708073fc0480ea92a077233ca87bd` is accepted;
  the Android (`9a8d2f0ce77a4e248bb71fefcb557637`) and iOS (`58bd3c95768941ea9eb4350aaa033eb3`) IDs are
  rejected with `unauthorized_client`. **"The mobile IDs are not OAuth clients at all; they exist only for
  Login5."**
- The three IDs are **Spotify's first-party client IDs**, extracted from their own apps. *Inference* — but a
  well-supported one: `docs/device-authorization.md` reports the pairing API answering "Spotify for Desktop",
  "Spotify PlayStation", "Spotify for Samsung Tizen TV" and similar for these IDs. Shipping first-party client
  IDs inside a distributed binary is the single sharpest ToS and enforcement exposure in this whole design.

---

## 3. Web API vs Web Playback SDK / Connect vs librespot-protocol

These are three different systems with different auth, capability and licensing. Do not conflate them.

| | **Spotify Web API** (`api.spotify.com`) | **Web Playback SDK / Spotify Connect** | **librespot protocol** |
|---|---|---|---|
| **Auth** | OAuth 2.0 Authorization Code + PKCE with **your own registered client ID**; or Client Credentials for public data | Web API OAuth token + the SDK's own player; Premium required | AP session (Shannon) + login5 bearer + client-token; or an OAuth token obtained with **Spotify's first-party client IDs** (`core/src/config.rs:6-8`) |
| **Audio bytes to your app** | **No.** The Player endpoints only transfer/skip/seek/queue on Spotify's own devices (`/me/player`, `device_ids`, Transfer Playback ref) | **Yes**, in a browser, via `Spotify.Player` + EME. Requires Premium; "mobile only types of premium subscriptions are excluded" | **Yes**, decrypted Ogg/MP3/AAC/FLAC from Spotify's CDN, keyed by an AP-session audio key |
| **Search** | Full: `q`, field filters, `type=album,artist,playlist,track,show,episode,audiobook`, `limit` 0-10, `offset` 0-1000 (`/reference/search`) | n/a | **None.** Only `spotify:search:*` as a context URI → track URIs + string map |
| **Metadata** | Full catalog, JSON, `preview_url`, `popularity` (deprecated), `available_markets` (deprecated) | Now-playing metadata only | Full protobuf, richer in places (licensor, language of performance, availability, alternatives) |
| **Playlist traversal** | `GET /playlists/{id}/items`, paged, `snapshot_id` | n/a | `GET /playlist/v2/playlist/{id}` — whole playlist, one payload |
| **Playback state / queue control** | Yes, on Spotify devices only | Yes, on your own in-page player | Receiver-side only; `Spirc` state, not a remote controller |
| **Quota** | Rolling 30 s window + a **quota** system; development mode caps at **5 allowlisted users** and needs a Premium app owner; extended quota requires a registered organisation with ≥250 k MAU (`concepts/quota-modes`) | Same OAuth/Web API app model | None documented — but enforcement is by account ban, not a 429 |
| **ToS position** | **Permitted** and expressly licensed (§III.1) — subject to Policy IV.2 (no commercial use for Streaming SDAs), Policy III.11 (must not "replicate or attempt to replace a core user experience of Spotify"), §IV.3.2 (caching limits) | **The supported third-party playback path.** "This SDK must not be used in commercial projects without Spotify's prior written approval" (`/documentation/web-playback-sdk`) | **Prohibited on its face**: §IV.2.2.b (no reverse-engineering of the Spotify Service/Platform), §IV.2.3.d (no stream ripping / permanent copies), §IV.3.2 (no local caching beyond Conditional Downloads) |
| **Native Android/iOS usable from KMP** | Yes | **No** — browser JS, needs `allow="encrypted-media"` in iframes for Chrome; a WebView is not a supported embedding | Only via a Rust FFI you build yourself |
| **Enforcement mechanism** | Security Code revocation, app disablement (§IX.8.4) | Same | Account ban; protocol break |

The supported mobile story is worse than it looks: Spotify **deprecated the iOS and Android app-remote SDKs**
("An update on the deprecated mobile streaming SDKs", 15 Jul 2022). Those SDKs never delivered audio either —
they controlled the installed Spotify app. There is currently **no supported way for a native Android/iOS app
to receive Spotify audio**.

---

## 4. Integration design

### 4.1 Where it must fit in this repo

| Seam | Current shape | A Spotify provider's obligation |
|---|---|---|
| `dylan.provider.CatalogApi` / `MusicProvider` (`provider/MusicProvider.kt:25-122`) | Every method returns `CatalogResult<T>`; every method has a default that says "not offered" | Implement `searchPage`, `searchAlbumPage`, `searchArtistPage`, `albumDetail`, `artistDetail`; leave `resolve` returning `CatalogResult.Err(UNSUPPORTED)` — the defaults already exist for this |
| `SignedStream` (`provider/MusicProvider.kt:12-15`) | `url` + `type` | **Cannot be satisfied honestly by Spotify.** The URL serves ciphertext and needs a per-file key from an AP session. There is no way to express "here is a key and an algorithm" in this type |
| `dylan.download.Transfer` (`download/Transfer.kt`) | Ktor `prepareGet` + `Range:` over the signed URL, `ByteReadChannel` → `.part` | Would need a decrypting wrapper, and `Container.sniffContainer` would have to accept Ogg/MP3/AAC |
| `AppContainer.buildNet()` (`di/AppContainer.kt:251-286`) | Constructs `SaavnProvider` directly | Needs a provider selector; `provider` is typed `SaavnProvider` today (`di/AppContainer.kt:172`), which is exactly the "aspirational interface" the repo's own architecture doc calls out (`docs/architecture.md:13-17`) |
| `SongKey(provider, songId)` (`model/Models.kt:8-26`) | `provider` is already part of identity; `itemId`, cache paths and `Reconciler` all key on it | A second provider needs a distinct `provider` string and is otherwise already supported |
| `Quality` (`model/Models.kt:28-44`) | Two tiers, `bits` 128/320 | Maps to Spotify's format list only loosely; `BITRATE_320` would have to pick MP3_320 or AAC_320 or OGG_VORBIS_320, and Ogg breaks the container path |

### 4.2 Rust → Kotlin: the FFI routes that exist

librespot ships **no FFI surface** (verified: zero matches for `uniffi`/`jni`/`cdylib`/`staticlib`/`crate-type`;
`[lib]` in `Cargo.toml` has no `crate-type`, so it builds an `rlib`). Every route below therefore starts with
**writing a new wrapper crate**.

| Platform | Route | Concretely |
|---|---|---|
| **Android** | **JNI over a `cdylib`** (lowest friction, no extra deps) | New crate `librespot-kmp` with `crate-type = ["cdylib", "staticlib"]` and `#[no_mangle] pub extern "C" fn spotify_*` returning opaque `*mut Session` handles. Kotlin side: `androidMain` `external fun` declarations + `System.loadLibrary("spotify")` in an `init` block. `.so` files land in `shared/src/androidMain/jniLibs/<abi>/` or a prepackaged AAR |
| **Android** | **uniffi** (better typed bindings, more build machinery) | `uniffi::setup_scaffolding!` + a `.udl`/proc-macro interface; `uniffi-bindgen` emits the JVM bindings and a Kotlin module you publish alongside. Worth it only if the surface stays large |
| **Android** | **JNA** — **not viable** | JNA is a desktop artefact; it is not a supported Android dependency and would add a full libffi path on-device. Reject |
| **iOS** | **`staticlib` + Kotlin/Native `cinterop`** | New crate `crate-type = ["staticlib"]` with a C header; `shared/src/iosMain/cinterop/librespot.def` pointing at the `.a` + header. Build `aarch64-apple-ios` (device) and `aarch64-apple-ios-sim` (simulator) separately — the repo already compiles both targets (`shared/build.gradle.kts:47`) |
| **iOS** | **uniffi + XCFramework** | `uniffi-bindgen generate … --language swift` + `xcodebuild -create-xcframework`; then the Swift side calls into the XCFramework and `DylanBridge.swift` forwards. More moving parts, better type safety |

### 4.3 Source sets: interface in `commonMain`, implementation per platform — **by injection, not `expect`/`actual`**

`commonMain` **can** hold the interface. It **must not** hold the native handle: a `Long`-as-pointer, a
`CValuesRef`, or an uniffi-generated object type is a platform symbol that will not resolve in the other
source set. `expect`/`actual` would work, but it is the wrong shape here because the two platform impls have
nothing in common at the FFI level — only at the Kotlin API level.

**Recommended: declare a plain interface in `commonMain` and inject the impl.**

```kotlin
// commonMain — dylan/provider/spotify/SpotifyNative.kt
interface SpotifyNative {
    suspend fun searchTracks(q: String, limit: Int): List<SpotifyTrack>
    suspend fun track(id: String): SpotifyTrack?
    suspend fun album(id: String): SpotifyAlbum?
    suspend fun artist(id: String): SpotifyArtist?
    suspend fun playlist(id: String): SpotifyPlaylist?
}
```

```kotlin
// commonMain — SpotifyProvider implements dylan.provider.MusicProvider, mapping to
// Song / MiniEntity / Album / Artist and returning CatalogResult.Err(UNSUPPORTED) from resolve().
class SpotifyProvider(private val native: SpotifyNative, /* … ResilientClient-shaped deps … */) : MusicProvider
```

- `androidMain` supplies `JniSpotifyNative` (`external fun` + `System.loadLibrary`).
- `iosMain` supplies `CinteropSpotifyNative` (generated by the `cinterop` task on the `iosMain` compilation).
- `jvmMain` supplies a fake, so `jvmTest` can drive the mapper against fixtures the way
  `SaavnProviderTest`/`MapperFixturesTest` already do.

**Wiring.** `AppContainer` already takes a platform-supplied factory for exactly this reason:
`engineFactory: () -> PlayerEngine` (`di/AppContainer.kt:85`). Add a sibling
`spotifyNativeFactory: (() -> SpotifyNative)? = null` and decide in `buildNet()`. Because `AppContainer` is
in `commonMain` and constructed by **four** call sites (`androidApp/…/DylanApp.kt:47`,
`shared/src/iosMain/kotlin/dylan/di/IosGraph.kt:399`, and two `jvmTest` files), a *required* new parameter is a
breaking change to `shared`'s public API — default it to `null`.

**iOS-specific integration risk.** The iOS bridge consumes the generated ObjC symbols, and
`iosApp/iosApp/Bridge/DylanBridge.swift` documents the naming rules it depends ("`shared.Foo` spellings do NOT
exist"). Any change to `AppContainer`'s constructor changes the generated ObjC initialiser signature, and the
only gate that catches it is the `ios/simulator` job in CI (Xcode 16.2, `xcodebuild`). **Xcode is not available
here**, so that breakage would be invisible locally. Budget for it.

### 4.4 Gradle and build-tooling consequences

Things this repo does **not** have today, which this feature would need:

| Need | Where it lands |
|---|---|
| A Rust toolchain pinned and installed | New `rust-toolchain.toml` (or an `actions-rs`/`dtolnay` action) — **pinned**, because `librespot` requires Rust 1.85+ and the analysed commit fixes lints "for Rust 1.99" (`Cargo.toml: rust-version.workspace = "1.85"`, workspace root `rust-version = "1.85"`) |
| `cargo` invocation from Gradle | Either a `Exec` task in `shared/build.gradle.kts` wired into the compile-task graph (the file already has a precedent for exactly this pattern: `generateAppVersion` must be a dependency of every compile, ktlint and detekt task — `shared/build.gradle.kts:200-220`), or a prebuilt-artifact approach where CI builds the `.so`/`.a` and checks them in / downloads them |
| Android NDK **on Linux** | CI's `android-debug` job runs `ubuntu-latest`. **Verified:** `cargo check --target aarch64-linux-android` fails here with `cc-rs: ToolNotFound: failed to find tool "aarch64-linux-android-clang"` (from `ring`'s build script). The NDK clang must be installed and `CC_aarch64-linux-android`/`AR_*` set, or use `cargo-ndk` |
| Xcode + `xcodebuild -create-xcframework` | CI's iOS jobs already pin Xcode 16.2 on `macos-14`; add a framework-package step before `:shared:assemble` |
| Vendored Rust sources + a rebuild trigger | Vendoring `librespot` (~40 k lines across 10 crates) into this repo, or a git-submodule / patch-stack. Either way you own protocol-drift fixes |
| `ktlint` + `detekt` scope | Both walk `androidMain` and `iosMain` (`shared/build.gradle.kts:21-28`), so the FFI shims are linted by the same gates — fine, but note `detekt` `allRules = false` and no baseline, so a new finding is a build failure |
| ProGuard / R8 | `androidApp` release does `isMinifyEnabled = true`; JNI `external fun` declarations need keep rules |

**Verified compile data points** (my own runs, on `aarch64-apple-darwin`):

- `cargo check -p librespot-core --no-default-features` **fails** — `librespot-oauth` has a `compile_error!`
  requiring exactly one TLS backend (`oauth/src/lib.rs:50-58`).
- `cargo check -p librespot-core --no-default-features --features rustls-tls-webpki-roots` **succeeds**
  (10.5 s, dev profile).
- `cargo check -p librespot-metadata --no-default-features --features rustls-tls-webpki-roots` **succeeds**.
- `cargo check --target aarch64-linux-android` **fails** for lack of the NDK clang (above).
- Cross-compilation for iOS and for the other three Android ABIs was **not** attempted (only
  `aarch64-apple-darwin` is installed via rustup).

**Use `rustls-tls-webpki-roots`.** `native-tls` (the default) pulls OpenSSL/Security.framework; on Android
that means either an NDK-built OpenSSL or a broken build. `rustls-tls-native-roots` needs to read the platform
CA store from Rust, which on Android is not straightforward. WebPKI roots avoid both. This is the *inference*
of someone who has fought mobile Rust TLS before — it is not stated in librespot's docs — but it is consistent
with librespot's own feature descriptions in `Cargo.toml` ("Best for reproducible builds, containerized
environments, or when you want certificate handling to be independent of the host system").

### 4.5 Binary size, ABI coverage, rebuild cadence

- **ABI coverage.** `androidApp/build.gradle.kts` has **no `abiFilters` and no `splits` block**, so AGP emits
  all four ABIs (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`). A Rust staticlib therefore means **four** Android
  builds. iOS needs **two** triples (`aarch64-apple-ios`, `aarch64-apple-ios-sim`) because `shared` declares
  `iosArm64()` and `iosSimulatorArm64()` (`shared/build.gradle.kts:47`). That is 6 native artefacts.
- **Binary size.** *Not measured — do not quote a number.* The dependency closure is large and I only read it:
  `librespot-core` alone pulls `tokio`, `hyper` + `hyper-util` + `hyper-rustls`, `protobuf` 3.7,
  `protobuf-json-mapping`, `rsa`, `num-bigint`, `sysinfo`, `governor`, `quick-xml`, `time`, `uuid`, `flate2`
  and `ring` (`core/Cargo.toml`). A release/stripped build of that plus `librespot-metadata` is plausibly
  **~1–3 MB per ABI** — that is an **estimate**, not a measurement, and it is the single number most worth
  measuring before anyone commits to this route. (`rustls` + `ring` alone are typically several hundred KB of
  machine code.)
- **Rebuild cadence.** Every one of the 6 artefacts must be rebuilt on: a librespot upgrade, an NDK bump, an
  Xcode bump (CI pins 16.2), a Rust bump, and **any protocol change on Spotify's side**. The last one has no
  schedule. librespot's own history shows this cost: the `[Unreleased]` changelog notes a switch to
  `get_extended_metadata` because "a problem where the metadata didn't include the audio file" (`CHANGELOG.md`).

### 4.6 The safer alternative: a *thin* Rust façade

If the librespot route is ever forced through, do **not** expose librespot's object model across the FFI
boundary. Crossing the boundary is where the cost is: uniffi has to model `Session`, `SpotifyUri`, `Track`,
`Album`, `Artist`, `Playlist`, `AudioItem`, `RangeSet`, the `PlayerEvent` enum, and every failure type — and
every librespot upgrade re-runs that translation.

Instead: a new crate that depends only on `librespot-core` + `librespot-metadata` and exposes **five
operations**, each returning a JSON string (or a length-prefixed UTF-8 buffer) so the Kotlin side decodes with
`kotlinx.serialization` and keeps the existing `Drift`/`CatalogResult` accounting:

```
spotify_session_create(client_id, device_id, redirect_uri) -> handle
spotify_session_auth(handle, oauth_json)                       -> status   // token exchange + connect + stored credential
spotify_search_tracks(handle, query, limit)                    -> json
spotify_metadata(handle, kind, uri)                            -> json     // kind = track|album|artist|playlist
spotify_resolve_audio(handle, track_uri, format)               -> json     // { cdn_url, file_id, audio_key, expires_at }
spotify_fetch(handle, cdn_url, offset, length, out_ptr)        -> n        // ciphertext; caller decrypts
```

The boundary is deliberately **thin and lossy**:

- The Kotlin side owns **all** domain modelling. `spotify_metadata` returns a flat JSON projection
  (`{id, title, subtitle, artUrl150, artUrl500, durationS, artistName, artistId, albumId, albumName}`) that the
  repo's `Mapper`-equivalent maps to `Song`/`MiniEntity` — mirroring how `SaavnProvider`'s `Mapper` +
  `Identity` work today.
- The Kotlin side owns **caching**. librespot's `Cache` writes ciphertext to a directory you do not control
  (`core/src/cache.rs:264-360`); pass `Cache::new(None, None, None, None)` and keep caching in the repo's
  `CacheManager`/`Reconciler`.
- The Kotlin side owns **decryption** — or rather, it must not: see below.
- The Rust side owns **only** the protocol: Shannon/Mercury/login5/client-token, the audio-key request, the
  storage-resolve call, and range GETs. Everything it returns is a value, not a callback.

**And here is the part that makes this design fail anyway.** The façade above gives you `{cdn_url, audio_key}`,
which is enough to fetch and decrypt audio in Rust — but the repo's `DownloadEngine`/`Transfer` cannot use it,
because:

1. `SignedStream(url, type)` has nowhere to put a 16-byte key or the algorithm.
2. `Transfer` writes the raw body to a `.part` file; the bytes must be decrypted **before** any integrity or
   container check.
3. `Container.sniffContainer` only recognises MP4/M4A and MP3 (`download/Container.kt:14-19`, `:51-58`), so
   the default `OGG_VORBIS_320` format cannot be committed even after decryption.
4. `Breakpoint`/`Transfer` resume by byte offset, which is compatible with the CDN's Range support
   (`audio/src/fetch/mod.rs:449-487` requires `206`) — so the *transport* is reusable, but only behind a
   decrypting reader, and only for MP3/AAC formats.

Options, in increasing order of cost:

- **A. Stream only, no offline.** Add a `resolve`-shaped `SignedStream` that points at a **local proxy** the
  Rust side runs (fetch range → decrypt → serve over loopback HTTP). `Transfer` and `DownloadEngine` keep
  working unchanged, but "offline" stops being a feature for Spotify content. Still ToS-prohibited.
- **B. Decrypt in Rust, expose a plaintext pipe.** A `resolveStream` variant returning a
  `ByteReadChannel`-shaped stream instead of a URL. This requires changing `CatalogApi.resolve`'s return
  type — i.e. the W3-E seam the interface's own KDoc calls out
  (`provider/MusicProvider.kt:52-60`) — and touches `DownloadEngine`, `Transfer`, `Breakpoint`, `PartStore`,
  `LibraryCommitter`, and the iOS bridge.
- **C. Download-and-decrypt in Rust to a file the repo owns.** Least invasive to `download/`, but then the
  Rust side must implement resume, integrity and the `.part` protocol that `Transfer` already has — you would
  be maintaining two download engines and can forget the repo's fuzz gates (`jvmTest/fuzz/`) covering either.

All three are ToS-prohibited. B is the only one that keeps a single download engine; A is the only one that is
cheap.

---

## 5. The reverse-engineering gap list

Each row: the capability that librespot does **not** provide, what building it would take, an effort estimate,
and the risk that Spotify changes something underneath it.

| # | Gap | What it takes | Effort | Breakage risk |
|---|---|---|---|---|
| 1 | **Rich search** (albums, artists, playlists, popularity, cover art, ordering) | librespot has nothing. Either find an undocumented `spclient` search endpoint (there is none in the tree) or use the public Web API `/search` — which is ToS-clean but quota-capped and capped at 5 dev-mode users | **High** if RE'd; **Low** on the Web API | **High.** Undocumented endpoints change without notice. librespot's own changelog shows this churn (`CHANGELOG.md`: metadata switched to `get_extended_metadata`, `spclient` endpoints tried "up to 10 times" then rotate access points — `core/src/spclient.rs:526-544`) |
| 2 | **Playlist traversal for large playlists** | `GET /playlist/v2/playlist/{id}` returns the whole playlist (`metadata/src/playlist/list.rs:91-100`). Paging it means discovering an offset/limit parameter on that endpoint, or Web API `/playlists/{id}/items` | **Medium** RE / **Low** Web API | **Medium.** Undocumented query params are the first thing a server tightens |
| 3 | **Offline / "Conditional Downloads"** | Spotify's own offline subsystem is a *different* service from what librespot implements. librespot caches whole **ciphertext** files (`audio/src/fetch/mod.rs:398-410`) with its own `FsSizeLimiter`; it has no licence-scoped, expiring, playlist-level offline model | **High** — you would be inventing a feature Spotify never exposed | **High**, and the ToS explicitly frames the only permitted caching as "time-limited offline syncing" (§IV.3.2) |
| 4 | **Playback state as a first-class model** | librespot is a Connect *receiver* with a `PlayerEvent` stream (`playback/src/player.rs:157+`). Mapping that onto the repo's `PlayerState` algebra (`model/Models.kt:203-296`, with its queue invariants and shuffle-permutation carrying) is real work, and none of the repo's `EngineContractTest`/`Orchestrator` invariants have been validated against a remote event source | **Medium–High** | **Medium.** The `connect-state` PUT protocol (`core/src/spclient.rs:552-578`) is undocumented |
| 5 | **Transfer / cast to arbitrary devices** | `Spirc::transfer` (`connect/src/spirc.rs:445`) exists, but a full device list/selector does not | **Medium** | **Medium** |
| 6 | **Client-token / hashcash churn** | `clienttoken.spotify.com` presents a hashcash challenge on Android/iOS (`core/src/spclient.rs:232-239`, `:271-337`) with `MAX_TRIES = 3` (`:257`). If the challenge changes, metadata *and* audio resolution stop | **Low** until it happens | **High.** This is the canary — it has already broken users (issue #1623, "Librespot fails to play any tracks") |
| 7 | **Client IDs** | The three IDs in `core/src/config.rs:6-8` are Spotify's own. A distributed app either reuses them (ToS + enforcement exposure) or tries to register its own and discover that the mobile IDs are *not* OAuth clients (`docs/device-authorization.md`) | **High** / possibly **impossible** | **Certain.** These can be revoked per client at any time (§IX.8.4) |

---

## 6. Legal / ToS and product risk — stated plainly

**Is using librespot's protocol path in a distributed app viable?** No. Not "risky" — prohibited by the terms
you accept by using the Spotify Platform, and prohibited in the specific clauses this feature would violate:

- **§IV.2.2.b** — "modifying, editing, altering, creating derivative works, **disassembling, decompiling,
  reverse-engineering, or extracting source code from the Spotify Platform (including any client libraries),
  Spotify Service, or Spotify Content**". librespot *is* a reimplementation of the Spotify client protocol; its
  own protocol docs are derived from packet captures (`docs/connection.md`: "Extracted from: Spotify 1.2.52.442").
- **§IV.2.3.d** — "facilitating '**stream ripping**' or other functionalities that make it easier for users to
  capture or otherwise **make permanent copies of Spotify Content**". Offline caching is the product.
- **§IV.3.2** — "**Do not locally cache any Spotify Content**, except as strictly necessary to enhance the
  performance of your SDA …, and limited to the **temporary** caching of: metadata and cover art; or
  **Conditional Downloads** of sound recordings. Caching of Conditional Downloads … must only be available to
  subscribers to the Premium Service."
- **Policy IV.2** — commercial use of a Streaming SDA is not permitted at all.
- **Policy III.11** — "Do not build products and services which **mimic, or replicate or attempt to replace a
  core user experience of Spotify** … without our prior written permission." An offline-first music player with
  Spotify content is close to the definition.
- **Policy III.7** — "Do not permit any device or system to **segue, mix, re-mix, or overlap any Spotify Content
  with any other audio content**." *Inference:* this design's own proposal is a single queue that can hold both
  Saavn and Spotify items (that is what `SongKey.provider` is for), which is a segue of two audio sources under
  one transport. Whether Spotify reads it that broadly is unknowable, but it is a clause a Spotify reviewer
  would point at.

Enforcement under §IX.8.4 includes "revoking your Security Codes, disabling your SDA, restricting your … access
to the Spotify Platform … requiring you to delete data". For a protocol-path app there is no Security Code to
revoke — the mechanism is an account ban.

**What the Web API route permits.** It is licensed, but narrow: §III.1.a grants a "limited, non-exclusive,
non-transferable, non-sublicensable, **revocable**" right to distribute SDAs "**(i) for private personal use;
and (ii) on Approved Devices**". Practical consequences for this repo:

- Development mode caps the app at **5 allowlisted users** (`concepts/quota-modes`). Extended quota requires a
  registered **organisation**, an **active launched service**, **≥250 k MAU** and commercial viability — an
  individual project cannot get it.
- Metadata may not be offered as a standalone service (Policy II.4.3) and must be accompanied by a link back
  to the Spotify item (Policy II.4.2).
- No commercial use if you stream (Policy IV.1-2). No ML/AI training on Spotify Content (Policy III.14).
- You must show Spotify attribution using the Spotify Marks (Policy II.4.1, VI) — a branding change this repo
  has not made anywhere.
- Storing content is limited (§IV.3.1) and must not be indefinite.

**Realistic risk for a personal/non-commercial project.** The realistic exposure is: (a) your Spotify
account gets banned, taking your library with it; (b) your developer Security Code gets revoked, taking the
metadata path down too — because both paths authenticate as *you*. Since this app's core value is offline
playback of *your own* library, a ban is a total loss, not a degraded mode. That is the honest framing: the
downside is not a lawsuit from a personal project, it is losing your music.

---

## 7. Phased implementation plan

### M0 — One afternoon, host-only, no repo change *(only if the librespot route is mandated)*

Write a throwaway Rust binary against the cloned librespot that, on macOS host, does:
`Credentials::with_password` → `Session::connect` → `Track::get` for one known track →
`CdnUrl::resolve_audio` → `AudioKeyManager::request` → print the CDN URL and the key.

**Why first:** it is the only way to learn whether the credential path still works at all, before any FFI,
Gradle or CI work. `examples/play.rs` already does most of it. **Exit criterion:** a valid CDN URL + 16-byte
key printed. If this fails, everything downstream is moot.

### M1 — **The smallest useful first milestone (recommended): a Web-API metadata provider, no audio, no Rust**

Scope, all inside `shared/src/commonMain`:

1. New `shared/src/commonMain/kotlin/dylan/provider/spotify/` with `SpotifyDto`, `SpotifyMapper`,
   `SpotifyIdentity` — mirroring `provider/saavn/`'s structure (`Mapper.kt`, `Identity.kt`, `dto/Dtos.kt`,
   `PageEnvelope.kt`) so the element-wise decode + `Drift` discipline is copied, not reinvented.
2. `SpotifyProvider : MusicProvider` (commonMain) implementing:
   - `searchPage` → `GET https://api.spotify.com/v1/search?q=…&type=track&limit&offset`
   - `searchAlbumPage` / `searchArtistPage` → same call with `type=album` / `type=artist`
   - `albumDetail` → `GET /v1/albums/{id}` (with `?market=`), `artistDetail` → `GET /v1/artists/{id}`
   - `homeFeed()` / `topSearchList()` left at their defaults (`MusicProvider.kt:48-50`) — Spotify has no
     "trending" equivalent for a third-party app
   - `resolve()` left at its default `err(ErrorCode.UNSUPPORTED, retryable = false)` — **explicitly**, with a
     KDoc saying why (ciphertext + non-MP4/MP3 container)
3. A `providerSelector: () -> MusicProvider` parameter on `AppContainer` (defaulted, so all four existing
   call sites keep compiling), so Saavn stays the default and Spotify is opt-in per build/profile.
4. Fixtures + `MapperFixturesTest`-style tests on the JVM. **Zero FFI, zero Gradle change, zero CI change,
   ktlint/detekt unchanged.**
5. Auth: authorization-code + PKCE with a personal client ID, using the existing Ktor client. Note the
   27 Nov 2025 OAuth migration — **implicit grant, HTTP redirect URIs and `localhost` aliases are dead**
   (Spotify blog, 14 Oct 2025), so a custom-scheme / claimed-https redirect is mandatory.

**Effort:** ~2–4 days. **Exit criteria:** Spotify search/album/artist render in the existing Compose/SwiftUI
screens behind the selector, with `Drift` accounting on, and a clear "not playable / not downloadable"
affordance whenever `resolve` returns `UNSUPPORTED`.

**This is the honest smallest useful milestone** because it tests the seam, the mapper discipline and the
multi-provider wiring — the three things that would be needed for *any* provider — without buying any of the
Rust/FFI/ToS liability. If Spotify metadata alone does not justify itself, stop.

### M2 — Decide, with data (not before M1 lands)

Re-run §2–§6 against what M1 actually cost: is Spotify catalog worth a second provider if nothing on it can
play offline? Measure the metadata-only quota headroom under real use.

### M3 — Only with a written Spotify agreement, or only as a personal sideloaded build

- Thin Rust façade (§4.6), `cdylib` + JNI for Android, `staticlib` + `cinterop` for iOS.
- NDK on the `android-debug` CI job; XCFramework packaging on the iOS jobs; a pinned Rust toolchain; six
  native artefacts; ProGuard keep rules for the JNI surface.
- The decrypt-in-transit change to `CatalogApi.resolve` / `Transfer` (option B in §4.6) — the breaking one.
- Format negotiation restricted to MP3/AAC, since Ogg cannot pass `sniffContainer`.

---

## 8. What I could not establish

1. **Binary size.** Not measured. The closure is read from `core/Cargo.toml`; the ~1–3 MB/ABI figure in §4.5 is
   an **estimate** and must be measured with `cargo build --release` + `strip -x` per target before anyone
   commits.
2. **Cross-compilation.** Only `aarch64-apple-darwin` was checked (`librespot-core` and `librespot-metadata`,
   both green with `rustls-tls-webpki-roots`). Android (`aarch64-linux-android`) failed for lack of the NDK
   clang — that is a *toolchain* failure, not a source failure, and I did not install the NDK to retry. iOS,
   `x86_64` and `armv7` were not attempted.
3. **Whether a self-registered client ID can do the device-authorization flow.** librespot's doc says Spotify
   enables it per client and only the desktop keymaster ID works among the three it ships; it also lists other
   first-party IDs it probed on 2026-08-18. Whether a *newly registered* developer app is enabled is not stated
   anywhere I read and would need a live probe.
4. **Whether any undocumented `spclient` search endpoint exists.** I can only say none appears in this tree.
   The absence of a symbol is not proof of the absence of an endpoint.
5. **Behaviour under librespot's actual hashcash path on Android.** I read the solver
   (`core/src/spclient.rs:271-337`) and `util::solve_hash_cash`; I did not run it. Difficulty scaling on a
   mid-range phone is unknown.
6. **The FOSDEM 2026 talk "Reverse Engineering the World's Largest Music Streaming…"** — I have the slide URL
   and its search-result abstract (which corroborates the layer map in §2.2: ApResolve, AP, Spclient, Dealer,
   Login5, Stored credentials, Audio AES key, Connect state), but the PDF could not be fetched as text, so I
   have not read the deck itself.
7. **Current Web API rate-limit numbers.** Spotify publishes only a chart image, not values
   (`concepts/rate-limits`), and states that quota bucket groupings "are subject to change".
8. **Whether Spotify would grant written permission.** Unknowable from here. Policy III.11 and §IV.2.2.b read
   as a firm no for a product that replicates the core experience; I am not a lawyer and this is not legal
   advice.

---

## Sources

**Primary, read directly**
- `librespot` @ `e023adbbf017ae1fc10d01531dbe50c409786f2d` — `Cargo.toml`, `CHANGELOG.md`, `COMPILING.md`,
  `README.md`, `docs/authentication.md`, `docs/connection.md`, `docs/device-authorization.md`, and the source
  files cited inline above.
- This repo: `shared/src/commonMain/kotlin/dylan/{model/Models.kt, provider/*, download/*, playback/*,
  di/AppContainer.kt, net/Clients.kt}`, `shared/build.gradle.kts`, `androidApp/build.gradle.kts`,
  `gradle/libs.versions.toml`, `.github/workflows/ci.yml`, `iosApp/iosApp/Bridge/DylanBridge.swift`,
  `docs/architecture.md`.

**External, fetched 2026-10-09**
- Spotify Developer Terms, v10, eff. 15 May 2025 — `developer.spotify.com/terms`
- Spotify Developer Policy, eff. 15 May 2025 — `developer.spotify.com/policy`
- Web API → Quota modes — `developer.spotify.com/documentation/web-api/concepts/quota-modes`
- Web API → Rate limits — `developer.spotify.com/documentation/web-api/concepts/rate-limits`
- Web API → Search for Item — `developer.spotify.com/documentation/web-api/reference/search`
- Web API → Transfer Playback — `developer.spotify.com/documentation/web-api/reference/transfer-a-users-playback`
- Web Playback SDK overview + Spotify Connect concept — `developer.spotify.com/documentation/web-playback-sdk`
- "An update on the deprecated mobile streaming SDKs" (15 Jul 2022) — `developer.spotify.com/blog/2022-07-15-mobile-streaming-sdks-update`
- "Reminder: OAuth Migration - 27 November 2025" (14 Oct 2025) — `developer.spotify.com/blog/2025-10-14-reminder-oauth-migration-27-nov-2025`
- librespot issue #1623, "Librespot fails to play any tracks" — `github.com/librespot-org/librespot/issues/1623`
- FOSDEM 2026, "Reverse Engineering the World's Largest Music Streaming Service" (slides, not read as text) —
  `archive.fosdem.org/2026/events/attachments/RNBQ8U-reverse-engineering-spotify/slides/267362/`
- `rspotify` crate page (Web API wrapper; crate is in maintenance mode) — `lib.rs/crates/rspotify`
