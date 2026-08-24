I’m going to act as the staff engineer here: I’ll push back where requirements conflict, then ask you to confirm the decisions that materially change the architecture.

I will **not** write the final complete implementation spec yet, because several unknowns would cause rework. First, here is the deep analysis and the list of decisions I need from you.

---

## 1. Core principles / invariants

These are non-negotiable if we want “most performant,” “minimal code changes,” and “human readable” to be true at the same time:

1. **No main-thread I/O.**  
   Networking, file cache, database, image loading, and audio decoding all happen off the UI thread.

2. **Unidirectional data flow.**  
   UI events flow into state holders; state flows back into composables/views. No UI writes directly to repositories or players.

3. **Immutable models.**  
   Track, cache entry, player state, and UI state are immutable data classes/value types.

4. **Small dependency surface.**  
   We use only dependencies that earn their place. No dependency injection framework unless absolutely needed. Manual constructor injection is more readable for a small app.

5. **Cache is explicit and bounded.**  
   The cache has hard limits on file count and total bytes. Eviction is deterministic.

6. **Performance is measurable.**  
   “Most performant ever” is not a spec. I translate it into SLOs:

   | Metric | Proposed target |
   |---|---|
   | Cold start | < 800 ms p50 on mid-range iPhone 11 / Pixel 6a |
   | Cached track tap-to-audio | < 150 ms p50 |
   | Search UI response | < 250 ms after network returns |
   | Scroll | 60 fps, no dropped frames in normal use |
   | Memory during playback | < 200 MB |
   | Network/cache I/O | never blocks UI |
   | APK/IPA size | Android < 30 MB, iOS < 40 MB |

If those targets are not acceptable, I need your numbers.

---

## 2. The biggest architectural fork: cross-platform strategy

This decision changes everything.

| Option | Performance | Code reuse | Maintainability | Risk |
|---|---|---|---|---|
| **Native separate** — SwiftUI + Jetpack Compose | Best possible per platform | None | Worst for solo/small team | Duplicate logic, slower changes |
| **KMP + Compose Multiplatform** | Very good for this UI | Highest — one UI codebase | Excellent | iOS Compose has some platform feel/startup risk |
| **KMP + native UI** — SwiftUI on iOS, Compose on Android | Very good | High for logic, medium for UI | Very good | Twice the UI code |
| **Flutter** | Good | High | Good | Larger runtime, less native feel |
| **React Native** | Good/Moderate | High | Moderate | Bridge overhead, list performance risk |

### My recommendation

**Kotlin Multiplatform + Compose Multiplatform** with a shared core and shared UI.

Why:

- One Kotlin codebase for search, networking, caching, playback abstraction, state machines, and UI.
- Very little duplicated code.
- Compose is mature on Android and good enough on iOS for a minimal list-and-player UI.
- Coroutines, Flow, and `kotlinx.serialization` make the code linear and readable.
- We can still use platform players under the hood: **Media3 ExoPlayer** on Android, **AVPlayer** on iOS.

This gives high performance with much lower maintenance than native separate.

If you tell me you need **absolute maximum native feel on iOS**, I would switch to:

- Shared KMP core for all logic/data/cache/playback interfaces.
- Native UI: SwiftUI on iOS, Jetpack Compose on Android.
- More code, but better platform integration.

**Question 1:** Do you accept KMP + Compose Multiplatform as the default, or do you want native UI on each platform?

---

## 3. Proposed high-level architecture

This is provisional but will guide the final spec:

```
UI Layer (Compose Multiplatform)
        │
        ▼
ViewModels / State Holders
        │
        ▼
Domain Use Cases
   ├── SearchTracksUseCase
   ├── GetCachedTrackUseCase
   ├── DownloadTrackUseCase
   └── PlayTrackUseCase
        │
        ▼
Repositories
   ├── SearchRepository
   ├── CacheRepository
   └── PlaybackRepository
        │
        ├── Remote API client
        ├── SQLDelight metadata DB
        ├── File cache + LRU evictor
        └── PlayerController
             ├── Android: Media3 ExoPlayer
             └── iOS: AVPlayer / AVQueuePlayer
```

Key rules:

- UI only observes state, never holds player objects.
- Player is behind a common `PlayerController` interface.
- All disk and network work uses coroutines with `Dispatchers.IO` / `Dispatchers.Default`.
- Cache writes are atomic: write to temp file, then rename.
- Downloads are queued, limited concurrency, cancellable.

---

## 4. Preliminary module layout

If we go KMP + Compose:

```
com.example.music/
├── commonMain/
│   ├── model/          # Track, SearchResult, PlayerState, CacheEntry
│   ├── data/
│   │   ├── api/        # Ktor client, DTOs
│   │   ├── cache/      # CacheManager, LRU eviction
│   │   ├── db/         # SQLDelight
│   │   └── player/     # PlayerController interface
│   ├── domain/         # Use cases, state machines
│   └── ui/
│       ├── search/
│       ├── player/
│       ├── components/
│       └── theme/
├── androidMain/
│   ├── platform/       # Android platform implementations
│   └── player/         # Media3 ExoPlayer wrapper
├── iosMain/
│   ├── platform/       # iOS platform implementations
│   └── player/         # AVPlayer wrapper
└── shared/             # optional if needed
```

No Dagger/Hilt. A simple `AppContainer` created in each app entry point.

---

## 5. Tech choices I would recommend

| Concern | Recommendation |
|---|---|
| Language | Kotlin 2.x |
| UI | Compose Multiplatform |
| Async | Kotlin coroutines + Flow |
| Networking | Ktor Client with platform engines + `kotlinx.serialization` |
| JSON | `kotlinx.serialization` |
| Metadata/cache DB | SQLDelight |
| File I/O | Okio |
| Image loading | Coil 3 |
| Android playback | Media3 ExoPlayer |
| iOS playback | AVPlayer / AVQueuePlayer |
| Build | Gradle KTS, version catalog |
| Linting/formatting | ktlint + detekt |
| Tests | JUnit, Turbine, MockK, Robolectric/Android instrumented where needed |
| Navigation | Simple sealed class or Compose Navigation, no heavy library |

---

## 6. What I need from you before the final implementation spec

Please answer explicitly. If you agree with a default, you can say **“accept defaults”** for that item.

---

### Q1. API contract and legal rights

This is the highest-risk item.

I need to know:

- Is this a REST API returning JSON?
- What does the search response look like?
- Does each result include:
  - `id`
  - `title`
  - `artist`
  - `album`
  - `artworkUrl`
  - `mediaUrl`
  - `durationMs`
  - `fileSizeBytes` or Content-Length support
- Does the API require an API key, token, or Authorization header?
- Is there pagination? Cursor or offset?
- Does the direct media URL support **HTTP Range requests**?
- Are there rate limits?
- Does the API allow downloading, caching, and offline playback of full tracks?
- Are you the rights holder or authorized to distribute/cache the content?

**Default assumption:** REST JSON, API key header, paginated search, direct HTTPS media URL with Content-Length, Range supported, caching allowed.

**Blocking legal note:**  
If the API’s terms do **not** permit downloading/caching full songs, or if the content is not properly licensed, I can help design a compliant streaming architecture, but I will not help build a piracy app. Please confirm this is not the case.

---

### Q2. Platform targets

- Minimum Android version?
- Minimum iOS version?
- Target devices or performance baseline?
- Do you need tablet/foldable support?
- Do you need Android Auto / CarPlay?
- Distribution through Play Store / App Store / enterprise / sideloading?

**Defaults:**
- Android minSdk 29 / Android 10
- iOS min 15
- Optimize for mid-range hardware: iPhone 11 / Pixel 6a
- No tablet-specific layouts
- No Android Auto / CarPlay
- Play Store + App Store

Why it matters: affects APIs, storage behavior, background playback permissions, and UI layout.

---

### Q3. Cache and offline behavior

This is central to your app.

- What are the exact cache limits?
  - Max file count?
  - Max total bytes?
  - Max bytes per file?
- Should the cache persist across app restarts?
- Should users be able to explicitly download tracks for offline playback, or is caching only an automatic playback cache?
- Should the cache use LRU eviction?
- Should cached tracks be removable from a settings screen?
- Do you need playback to start **before** the file finishes downloading, or is download-first acceptable?
- Should the app pre-cache the next track in the queue?
- What happens if a cached file is evicted while it is playing? We can protect active files.

**Defaults:**
- 200 files **or** 2 GB total, whichever comes first
- Persistent cache in app-specific storage
- Automatic cache only, no separate “offline downloads” section
- LRU eviction
- Yes, a simple “Clear cache” setting
- Download-first playback first; streaming later if needed
- Pre-cache next track in the queue
- Active files are protected from eviction

Why it matters: determines cache manager, download manager, player integration, and storage location.

---

### Q4. Playback features

Which of these do you need for v1?

- Play / pause
- Seek / scrubber
- Next / previous track
- Queue
- Repeat one / repeat all
- Shuffle
- Playback speed
- Sleep timer
- Background playback + lock screen controls + headphone controls
- Audio focus / interruption handling
- Gapless playback
- Equalizer

**Defaults:**
- Play/pause, seek, next/previous, queue, repeat, shuffle
- Background playback, lock screen controls, headphone controls
- Audio focus handling
- No playback speed, sleep timer, equalizer, or gapless playback in v1

Why it matters: affects player state machine and platform integration complexity.

---

### Q5. UI/design

You said “minimal graphics” but “Spotify design team style.”

I interpret that as:

- Dark theme only
- Strong typography
- Flat surfaces, no heavy gradients or blur
- High contrast
- Large touch targets
- Small amount of motion, only where useful

Proposed screens:

1. **Search** — top search field, recent searches/history
2. **Search results** — lazy list of tracks
3. **Now Playing** — full-screen minimal player with small album art, track title, artist, seek bar, transport controls
4. **Mini player** — bottom bar when a track is playing
5. **Library/Downloads** — list of cached tracks

Questions:

- Is dark-only acceptable?
- Do you want a bottom navigation bar? If so, which tabs?
- Should album art be displayed? If the API provides it?
- Do you need dynamic type / accessibility?
- Do you need haptics?
- Do you want animations, or should they be near-zero?

**Defaults:**
- Dark only
- Bottom tabs: Search, Library
- Yes to album art if provided
- Full accessibility support including TalkBack/VoiceOver
- Minimal haptics
- Subtle, fast animations only

---

### Q6. Performance definition

I proposed SLOs above. Do you accept them?

If you want “most performant ever” in absolute terms, that conflicts with “minimal code changes” and “easy maintainable.”

We have two directions:

- **A:** Cross-platform shared UI, excellent performance, one codebase, fast development.
- **B:** Native separate UI, maximal platform performance, more code, higher maintenance.

I recommend **A** for this project.

**Question:** Do you accept the SLOs? If not, give me your measurable targets.

---

### Q7. Development environment and team

- Will you develop on a Mac? Required for iOS builds.
- Are you comfortable with Kotlin?
- Single developer or team?
- Do you need CI/CD?
- Do you need analytics/crash reporting?
- Do you need feature flags?

**Defaults:**
- Yes, Mac available
- Yes, Kotlin comfortable
- Single developer
- GitHub Actions for lint, test, build
- No third-party analytics/crash reporting in v1
- No feature flags in v1

Why it matters: if you are not comfortable with Kotlin, Flutter may be the safer choice. If you do not have a Mac, iOS development is not possible.

---

### Q8. App name, branding, icon

Do you already have a name, logo, or brand color?

**Default:** We use a temporary name and neutral dark theme. Branding can be added later without affecting architecture.

---

## 7. What happens after you answer

Once I get your answers, I will produce the complete implementation spec, including:

- Final module breakdown
- Data models and API mapping
- Cache manager algorithm and eviction rules
- Download manager behavior
- Player state machine
- UI component tree and screen states
- Error/loading/empty states
- Background playback integration
- Build setup for Android and iOS
- Test plan
- Performance profiling guide
- Release checklist
- Step-by-step implementation order

If you want, you can reply with:

> Defaults accepted for Q2, Q3, Q4, Q5, Q6, Q7, Q8.  
> For Q1, here is the API response sample / docs.

That is enough for me to write the full spec.