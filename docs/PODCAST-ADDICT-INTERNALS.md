# Podcast Addict — internals, read from a decompiled APK

Reverse-engineering report on **Podcast Addict 2026.11**, `com.bambuna.podcastaddict`, from
`Podcast+Addict_+Podcast+player_2026.11_APKPure.apk`.

All evidence paths are relative to the jadx output root `/private/var/folders/0x/tq2_tw6d7_lckb2kqwt23klc0000gn/T/opencode/podcast-addict-dec/`.

Declared names in this report follow the decompiled tree: the app's own code has been R8-obfuscated
into package `defpackage` with two/three-character class names (`fo5`, `e4`, `yc3`, `a51`, `fl3`, …).
Only the components that the manifest must reference by name, plus a handful of library-free classes,
kept their original names (`com.bambuna.podcastaddict.*`). Class names quoted below are the
*decompiled* names, which are stable identifiers in the binary.

---

## 1. Verdict — what this app actually is

Podcast Addict is a **single-dex, single-process, aggressively obfuscated Android podcast client
whose "podcast API" is a private PHP backend at `addictpodcast.com/ws/php/v4.1/`, not a public
catalogue**. The evidence:

* The search "backend" is a first-party PHP service. `sources/defpackage/wn5.java:3737` (and ~70 other
  call sites) hard-code `https://addictpodcast.com/ws/php/v4.1/…`, with the API key
  `d2fad335-a44d-4aeb-9e5b-67b19a15572c` injected both as an HTTP header
  (`fo5.java:2003` — `X-App-Key`) and as a query parameter (`wn5.java:2422` — `key`).
* Apple's iTunes/Apple Podcasts search API is used **directly and in parallel** for podcast and
  episode search: `to3.java:358` (`/search?media=podcast&limit=100&term=`), `qh4.java:467`
  (`/search?entity=podcastEpisode&media=podcast&limit=200&term=`), `z42.java:267` (`/lookup?id=`),
  `z42.java:302` (customerreviews RSS→JSON). These are the *only* third-party podcast metadata
  sources; there is no PodcastIndex/Gpodder/ListenNotes call in the binary.
* RSS is fetched straight from the publisher by a hand-rolled `java.net.URI`/OkHttp layer in
  `defpackage.fo5`, parsed by a **SAX** (`org.xml.sax.helpers.DefaultHandler`) handler hierarchy
  `u1 → w0 → e4 → {f54, g54, h54}` — not a library, not XmlPull.
* Audio is **media3/ExoPlayer** (`sources/defpackage/yc3.java:51` `public final class yc3 extends c3`,
  with `bh1` = media3 `ExoPlayerImpl`), with a custom `AudioProcessor` chain for volume levelling and
  silence-skipping (`oc3.java`, `ad3.java`, `bd3.java`) and a `Sonic`-based speed path.
* Persistence is **raw SQLite via `android.database.sqlite`**, `podcastAddict.db`, schema version **135**,
  with no Room (`yu0.java:3377` in `PodcastAddictApplication` constructs
  `new so3(app, "podcastAddict.db", null, 135, new xu0())`; `so3.java:644` `onUpgrade` has branches
  up to `i < 135`).
* Background refresh is **WorkManager** (`androidx.work.impl.WorkManagerInitializer` in
  `resources/AndroidManifest.xml`) driven by an `AlarmManager.setExactAndAllowWhileIdle` alarm
  (`mf5.java:230-259`), plus a **WebSub** subscription mechanism that posts hub/topic URLs to the
  backend (`bo5.java:121` → `post_websub_urls.php`).
* There is a **large analytics/telemetry surface** that is not podcast functionality: per-day,
  per-hour, per-podcast, per-episode playback statistics tables (`so3.java:278-319`), a
  `playback_stats_server_*` upload path, `update_playback_statistics.php`, `update_global_stats.php`,
  `update_podcast_stats.php`, `track_3rdparty_search.php`, plus a full ad stack
  (AppLovin, Fyber/Inneractive, InMobi, PubMatic, Amazon, Google Mobile Ads).
* The app is **free with a paid "donate" split** (`com.bambuna.podcastaddictdonate`), and enforces a
  **signature allowlist** — `zh.java:108-175` compares the signing cert SHA-256 against 7 hard-coded
  digests and rejects known APK-mirror installers (`zh.java:33-51`: APKPure, Aptoide, Aurora,
  F-Droid, Uptodown, Cafe Bazaar…). APKPure-sourced copies fail this check by construction.

Two important negative findings, established by exhaustive grep rather than inference:

* **No PodcastIndex `podcast:value` / Lightning / value-block support anywhere in the binary.**
  `podcast:value`, `valueBlock`, `lightning`, `LNURL`, `boostagram` — zero hits in `sources/`.
* **No `podcast:locked` support.** Only Acast's proprietary `acast:locked-item` is recognised
  (`e4.java:2128, 2791, 2926`), which just suppresses an item; there is no feed-level locking.

---

## 2. Toolchain used and what was readable

| Tool | Version / path | Use |
|---|---|---|
| jadx | 1.5.6 (`/opt/homebrew/bin/jadx`) | full APK → `sources/` (17,228 `.java`) + `resources/` |
| jadx `--single-class … -m simple` | 1.5.6 | re-rendered `defpackage.e4`, `defpackage.so3`, `defpackage.fo5`, `defpackage.e4` in "simple" mode to recover control flow that the default `auto` mode mangled |
| `unzip` | macOS | extracted `META-INF/`, `classes*.dex` listing, `lib/`, `assets/` |
| `python3` + `zipfile` | — | manifest parsing, entry census |
| websearch | — | external semantics of the iTunes Search API and RSS/Atom enclosure rules only |

**What is readable.** The manifest decodes cleanly (`resources/AndroidManifest.xml`). All string
constants survive R8 (they are `String` literals in the dex pool): every URL, JSON key, SQL
statement, `SharedPreferences` key, and log tag. `arsc` decodes, so `res/values/*.xml` is fully
available (arrays, preference keys, plurals). The class hierarchy and method signatures are fully
recoverable. The database schema is recoverable in full because it is built with literal SQL
strings (`so3.java`).

**What is NOT readable.** Method *bodies* are frequently unusable:

* R8's aggressive inlining, class merging (dozens of unrelated classes merged into one `defpackage.Xxx`
  file — e.g. `ev1.java` alone holds 20+ unrelated classes), and outline/de-novo synthesised
  control flow mean that many methods are only partially decompilable.
* ~1% of methods are emitted as
  `Method dump skipped, instruction units count: N … throw new UnsupportedOperationException("Method not decompiled…")` —
  e.g. `fo5.f0(String,String,boolean)` (`fo5.java:1405`) and `wn5.F(Context,int)` (`wn5.java:~595`).
  For these I re-ran jadx in `-m simple` mode (linear, gotos) which recovered them.
* Local variable names are gone (`r1`, `r2`, `str3`, `i2`), so control flow must be read from
  structure, not names. This is the main reason several mechanisms below land in §12 rather than
  being asserted.
* Native libraries (`libsonic.so`, `libgenius_blur.so`, `libapplovin-native-crash-reporter.so`,
  `libdatastore_shared_counter.so`) are ELF only; no symbol/source was read.

**Provenance of the binary.** `META-INF/version-control-info.textproto` records
`revision: "48f68e01d009e91b4790ef8b78c4185dcd8794cf"`; `META-INF/com/android/build/gradle/app-metadata.properties`
records `androidGradlePluginVersion=9.4.0`. See §11 for signing.

---

## 3. The network layer

### 3.1 The HTTP client stack — what it actually is

**The app does not use Retrofit. It uses OkHttp directly, from behind a single 424 KB utility class
`defpackage.fo5` ("WebTools") that owns the client, the interceptors, the retry policy, the URL
sanitiser, the caching and the cookie jar.**

The client is OkHttp (not `java.net.HttpURLConnection` for the app's own traffic; the
`HttpURLConnection` imports in `fo5.java` are used only by helper methods such as
`fo5.h0()` at `fo5.java:1505` for the walled-garden probe, and by the *download* engine
`rx0.java:190`, `ai9.java:81`, `sg7.java:70`, `ig7.java:233` — see §7).

Proof of OkHttp: `fo5.java:14-17` imports `com.applovin.shadow.okhttp3.HttpUrl`,
`com.applovin.shadow.okhttp3.internal.http2.Http2Stream`,
`com.applovin.shadow.okhttp3.internal.ws.RealWebSocket`, `com.applovin.shadow.okhttp3.internal.ws.WebSocketProtocol`.

> **Important nuance.** The OkHttp classes the app uses are the **shaded/relocated copy**
> `com.applovin.shadow.okhttp3.*` that ships inside the AppLovin SDK, *not* the unshaded
> `okhttp3.*`. The unshaded `okhttp3` package in the APK contains only 2 classes
> (`okhttp3/internal/platform/PlatformInitializer`, `okhttp3/internal/publicsuffix/PublicSuffixDatabase`
> — i.e. only the parts that must not be relocated, because `PublicSuffixDatabase` is referenced by
> name from `assets/PublicSuffixDatabase.list`). Everything else — `OkHttpClient`, `Request`,
> `Response`, `Call`, `HttpUrl`, connection specs, `RealConnectionPool`, the `CookieJar` interface —
> resolves to AppLovin's relocated copies. This is almost certainly an **accident of dependency
> merging** (AppLovin relocated its OkHttp and R8 then bound the app's own OkHttp references to it),
> but the effect is real and observable: the app's HTTP stack is literally
> `com.applovin.shadow.okhttp3`. Two copies of OkHttp are in the binary and only the shaded one is
> used by app code.
>
> **INFERRED** (not proven from code): why the app compiled against the shaded package. Most likely
> R8's "repackage/relocate classes" merged the two OkHttp flavours and chose the shaded name. I could
> not find any explicit Gradle configuration or shading rule in the APK.

The obfuscated type aliases are recoverable from the code:

| Decompiled name | Real class | Evidence |
|---|---|---|
| `ia3` | `OkHttpClient` | `fo5.java:2361` `ia3 implements m50`, has `public static final List E` (protocols) / `List F` (cipher suites) |
| `ha3` | `OkHttpClient.Builder` | `fo5.java:2363-2384` `.b(...)`, `.f = true`, `.k = new c71(new CookieManager())` |
| `ka4` | `Request.Builder` | `fo5.java:364-405` `ka4Var.a("If-None-Match", …)`, `ka4Var.a("Upgrade-Insecure-Requests", …)`, `ka4Var.b()` returns `na4` |
| `na4` | `Request` | `na4.java:12-25` fields `a: HttpUrl`, `b: String method`, `c: Headers` |
| `d32` | `HttpUrl` | `d32.java:10-25` `String a,b,c,d; int e; List f; String g,h` |
| `c32` | `HttpUrl.Builder` | `fo5.java:1971-1994` `.g()`, `.i` (query list), `.f(null, str)`, `.b()` |
| `t64` | `RealCall` | `fo5.java:2448` `new t64(new ia3(ha3Var), new na4(ka4Var)).c()` |
| `tb4` | `Response` | `tb4.java:9-27` `int d` (code), `boolean q` (isSuccessful), `xb4 g` (body), `tb4 k` (networkResponse) |
| `xb4` | `ResponseBody` | `e4.java`/`t15.java:44` `xb4Var.k()` returns the body string |
| `ow2` | `MediaType` | `ow2.java:19-40` regex `type/subtype`, `.a(ow2)` → charset |
| `es9` | `HttpUrl.Companion`-ish helper | `fo5.java:1990` `es9.d(0, 0, str3, HttpUrl.QUERY_COMPONENT_ENCODE_SET, 91)` |
| `lx3` | `Protocol` | `ia3.java:19-20` `lx3.HTTP_2`, `lx3.HTTP_1_1` |
| `ui0` | `ConnectionSpec` | `ui0.java:20-27` `g` = MODERN_TLS, `h` = COMPATIBLE_TLS (+TLS 1.1/1.0), `i` = CLEARTEXT |
| `ev1(8, 2L)` | `RealConnectionPool` | `ev1.java:82-107` `new ss()` with `a = 8`, `b = 2 min` keep-alive |
| `c71` | app's `CookieJar` impl | `c71.java` imports `java.net.CookieManager`; `fo5.java:2378` `ha3Var.k = new c71(new CookieManager())` |
| `wz0` | `Authenticator` | `wz0.java`; `fo5.java:534-540` `wz0(yo)` used as `ha3Var.h` |
| `m22` | app's `CacheEntry` (etag+last-modified) | `m22.java:8-15` `long a; String b`, consumed by `fo5.java:384-390` |

### 3.2 Construction of the shared client — `fo5.v()`

`/private/.../sources/defpackage/fo5.java:2359-2385`

```java
public static ia3 v() {
    if (r == null) {
        synchronized (s) {
            if (r == null) {
                ha3 ha3Var = new ha3();
                ha3Var.b(Arrays.asList(ui0.g, ui0.i));   // connectionSpecs = [MODERN_TLS, CLEARTEXT]
                ha3Var.f = true;                          // retryOnConnectionFailure
                ev1 ev1Var = c;                           // RealConnectionPool(8, 2 min)
                ev1Var.getClass();
                ha3Var.b = ev1Var;
                try { Logger.getLogger(ia3.class.getName()).setLevel(Level.FINE); } catch (Throwable th) {…}
                try { ha3Var.k = new c71(new CookieManager()); } catch (Throwable th) {…}
                r = new ia3(ha3Var);
            }
        }
    }
    return r;
}
public static ha3 x() { return v().a(); }   // newBuilder() — a per-call copy
```

Facts established:

* **Connection pool: 8 idle connections, 2-minute keep-alive** (`fo5.java:126` `c = new ev1(8, 2L)`).
* **Protocols `HTTP_2`, `HTTP_1_1`** (`ia3.java:19` `E = zt5.k(new lx3[]{lx3.HTTP_2, lx3.HTTP_1_1})`).
* **Connection specs include CLEARTEXT** (`ui0.i`), matching
  `android:usesCleartextTrafficPermitted="true"` in the app's
  `res/xml/network_security_config.xml` — needed because a large share of podcast enclosures are
  still plain `http://`.
* **`retryOnConnectionFailure = true`.**
* **Cookies are enabled via `java.net.CookieManager`** wrapped in `c71` — i.e. the app keeps a
  process-wide `java.net.CookieManager` and bridges it to OkHttp's `CookieJar`.
* `x()` = `v().a()` = `client.newBuilder()`, so **every request gets a fresh mutable builder** and
  therefore its own timeouts/authenticator, but shares the pool, the protocols, the specs and the
  cookie jar. There is no per-host configuration anywhere.
* **No HTTP cache.** `ia3` has no `Cache` field set anywhere in `fo5`; instead caching is
  implemented by the app itself with `If-None-Match` / `If-Modified-Since` (§3.5) and an
  in-process `LruCache` (`fo5.java:85`, `fo5.java:2039` `q0(...)`).
* **There is no OkHttp interceptor.** `fo5` never calls `addInterceptor`/`addNetworkInterceptor`.
  Everything is done by mutating the request builder before `execute()`. The "interceptors" are the
  functions `fo5.H()`, `fo5.n0()`, `fo5.p()`, `fo5.l0()`.

### 3.3 Per-request configuration — the "interceptor" equivalents

**`fo5.H(String url, m22 cacheEntry, boolean allowUpgrade)` — `fo5.java:363-407`**

Builds the `Request.Builder` from a `java.net.URI` (with the aggressive fix-ups in `fo5.b(String)`,
`fo5.java:936-1043`, which percent-encodes spaces/quotes/angle-brackets/backticks/braces/pipes, and
reflectively patches a missing `host` field into the `URI`). If the scheme is `http` and
`allowUpgrade`, it adds:

```java
ka4Var.a("Upgrade-Insecure-Requests", "1");
```

If a `m22` cache entry is present:

```java
if (!TextUtils.isEmpty(str3)) ka4Var.a("If-None-Match", str3);          // etag
else if (j2 > 0)          ka4Var.a("If-Modified-Since", fv0.C.format(Instant.ofEpochMilli(j2)));
```

**`fo5.n0(...)` — `fo5.java:1859-1917` — the timeout/header "network interceptor"**

```java
public static void n0(ha3 ha3Var, ka4 ka4Var, boolean z2, boolean z3, int i2, boolean z4) {
    ka4Var.d(POBCommonConstants.USER_AGENT, J(z3));
    TimeUnit.MILLISECONDS;
    ha3Var.a(15000L, timeUnit);                                  // connectTimeout 15 s
    ha3Var.y = zt5.b(z4 ? 135000L : 45000L, timeUnit);           // readTimeout   45 s / 135 s
    ha3Var.e(z4 ? 135000L : 45000L);                             // writeTimeout  45 s / 135 s
    ha3Var.i = true;   // followRedirects
    ha3Var.j = true;   // followSslRedirects
    if (i2 > 0) { … SSL workarounds … }
    if (z2) return;
    ka4Var.d("Accept-Encoding", "identity");                     // z2 == true ⇒ gzip allowed
}
```

* connect **15 000 ms**, read/write **45 000 ms** (or **135 000 ms** for big bodies, `z4`).
* **`Accept-Encoding: identity` is sent unless the caller passes `z2 == false`.** i.e. the default
  app position is *no* gzip for those paths, and gzip is opted into by `p()` (`fo5.java:2015`-ish)
  and `o()`.
* `ha3Var.i = true` / `ha3Var.j = true` — OkHttp follows redirects and cross-scheme redirects.
  On top of that, `fo5.w()` implements its **own** redirect handling for walled-garden/proxy cases
  (see §3.7).

**`fo5.p(String url, List<qd3> params, boolean z2)` — `fo5.java:1958-2023` — the backend caller**

This is the canonical "call addictpodcast.com" helper. Every backend endpoint goes through it.
It builds the URL with `c32`/`es9` (percent-encoding each key and value with
`HttpUrl.QUERY_COMPONENT_ENCODE_SET`), then:

```java
ka4 ka4VarH = H(d32VarB2.h, null, true);
ka4VarH.a = d32VarB2;                                                                       // GET
ka4VarH.a("X-App-Key", "d2fad335-a44d-4aeb-9e5b-67b19a15572c");                              // ← API key
String str5 = zh.a;                                                                         // installer package
String str6 = yh.a;
if (TextUtils.isEmpty(str6)) str6 = "null";
ka4VarH.a("X-App-Installer", str6);
ka4VarH.c();                                                                                // GET, no body
n0(ha3VarX, ka4VarH, true, P(str), 0, z2);
tb4 tb4VarC = new t64(new ia3(ha3VarX), new na4(ka4VarH)).c();
```

`java.lang.String P(String)` — `fo5.java:691-694`:
```java
return !TextUtils.isEmpty(str) && str.startsWith("https://addictpodcast.com");
```
so `J(true)`/`J(false)` pick the UA (§3.4).

**`fo5.l0(url, yo, ArrayList headers, List formParams)` — `fo5.java:1685-1747` — the generic caller**

Used by `q()` (JSON-as-string GET) and `j0()` (raw GET). Notable branches:

```java
if (str.contains("itunes.apple.com")) {
    ka4VarH.d(POBCommonConstants.USER_AGENT,
        "Mozilla/5.0 (Linux; Android 14; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36");
}
…
} else if (str.contains("opml.radiotime.com/")) {
    ka4VarH.d(POBCommonConstants.USER_AGENT,
        "TuneIn Radio/24.2 (Linux;Android 10) ExoPlayerLib/2.11.4");
}
boolean zStartsWith = str.startsWith("https://addictpodcast.com");
if (zStartsWith) {
    ka4VarH.a("Cache-Control", "no-cache");
    q0(c(str, list2));     // populate the in-memory LruCache key
}
if (!z81.p(list2)) { ka4VarH.e("POST", new cq1(arrayList2, arrayList3)); }   // form POST
```

**This is the single biggest header finding in the app: Podcast Addict sends three different
User-Agents depending on the host** (§3.4).

### 3.4 User-Agent — exact strings

`fo5.J(boolean z)` — `fo5.java:444-469`:

```java
public static String J(boolean z2) {
    if (TextUtils.isEmpty(h)) { … h = System.getProperty("http.agent"); h = Normalizer.normalize(h, NFD); h = j.matcher(h).replaceAll(""); … }
    return z2 ? b : h;
}
```

`b` is built in the static initialiser — `fo5.java:116-125`:

```java
StringBuilder sb = new StringBuilder("PodcastAddict/v5");
int i2 = PodcastAddictApplication.c3;
if (i2 == 2)      str = "A";      // Amazon build
else if (i2 == 4) str = "H";      // Huawei build
else              str = "";       // Google Play / ChromeOS
b = yv.o(sb, str, " (+https://podcastaddict.com/; Android podcast app)");
```

`PodcastAddictApplication.c3` is **1** in this build (`PodcastAddictApplication.java:310`
`public static final int c3 = 1;` — the Google-Play flavour, confirmed by the mapping in
`zh.java:313-321` where `c3` 1→`GOOGLE_PLAY_STORE`, 2→`AMAZON`, 3→`CHROMEOS`, 4→`HUAWEI`).

So, for this APK, the exact strings are:

| Purpose | Header value |
|---|---|
| **Default app UA** (`J(true)`) | `PodcastAddict/v5 (+https://podcastaddict.com/; Android podcast app)` |
| `http.agent` fallback (`J(false)`) | the device's system `http.agent`, NFD-normalised, non-ASCII stripped (`fo5.java:455-462`) |
| **itunes.apple.com** (`fo5.java:1697-1698`) | `Mozilla/5.0 (Linux; Android 14; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36` |
| **opml.radiotime.com** (`fo5.java:1709-1710`) | `TuneIn Radio/24.2 (Linux;Android 10) ExoPlayerLib/2.11.4` |
| **Google image search / Custom Search** (`fo5.java:1939`) | `Mozilla/5.0 (Linux; Android 14; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.7103.113 Mobile Safari/537.36` |
| **ExoPlayer media requests** (`yc3.java:200` → `c0(uri, fo5.J(true), true, false, null)`) | the default app UA, passed as the `userAgent` of the OkHttp data source |

> The Chrome and TuneIn UAs are **spoofing**: the app impersonates a browser to Apple (which
> otherwise rate-limits/404s non-browser agents) and impersonates the official TuneIn app to
> RadioTime (the OPML directory). The TuneIn string even claims `ExoPlayerLib/2.11.4`.

### 3.5 Caching, timeouts, retry, redirects

**Caching.** Four distinct layers:

1. **OkHttp `Cache` — not used.** `ia3` is never given a cache.
2. **`LruCache A` (100 entries) — `fo5.java:85`, `fo5.java:2039-2064` (`q0`)**. `q0(url)` is called
   on every backend GET with a canonical key built by `fo5.c(String, List)` —
   `fo5.java:1085-1107` — which lower-cases and **sorts** the query parameters:
   ```java
   arrayList.add(lowercase(key) + "=" + trimmed(value));  … Collections.sort(arrayList);
   sb.append('?').append(String.join("&", arrayList));
   ```
   This is a de-duplication guard ("don't fire the identical request twice"), not a response cache;
   the value is the stack trace of the caller for diagnostics.
3. **HTTP conditional requests (real caching).** `m22 {long lastModified; String etag}` is stored on
   the podcast row (`podcasts.last_modified`, `podcasts.etag`, `so3.java:590`). On the next refresh
   `fo5.H(url, m22, true)` turns it into `If-None-Match` / `If-Modified-Since`
   (`fo5.java:384-390`). Episode *comments* use the same mechanism with their own pair
   (`episodes.comments_last_modified`, `episodes.comments_etag`) — see `e54.java:843,880,924`.
   A `304` short-circuits in `fo5.w()` (the response object is closed and treated as "nothing new").
4. **Feed-level cache busting.** `Cache-Control: no-cache` is added for `addictpodcast.com`
   (`fo5.java:1715`, `fo5.java:1653`), and `wn5.v()` (`rss_proxy.php`) appends
   `ts = System.currentTimeMillis()` (`wn5.java:3671`) so the proxy never serves a stale copy.

**Timeouts.** See `fo5.n0()` above: 15 s connect, 45 s read/write, 135 s for large bodies.
Distinct overrides:
* `fo5.y()` (`fo5.java:7669-7710`) — used for HEAD/probe requests: 3 000 ms connect,
  2 500 ms read/write, or 1 500/1 000/1 000 ms when `z4`.
* `fo5.h0()` (`fo5.java:1505-1556`) — walled-garden probe: **1 000 ms** on everything, against
  `http://clients3.google.com/generate_204`.
* Chapter JSON fetch (`x90.java:6030-6036`): **10 000 ms** connect and read.
* SimpleCache-related media requests: 15 s / 45 s / 45 s (`fo5.java:1859-1866`).

**Retry.** Two mechanisms:
* OkHttp's `retryOnConnectionFailure` (`fo5.java:2366`).
* The app's own retry loop in `fo5.w(ka4, yo, z2, z3, z4, sb, int attempt, …)` —
  `fo5.java:2444-7665`, ~5 200 lines, receiving the attempt index as `i2`. Observable behaviours:
  * `attempt < 2` retries after a `UnknownHostException`/`ConnectException`
    (`fo5.java:~4700`: `if (i4 < 2) { i3 = i4 + 1; … return w(ka4Var2, …); }`).
  * On `SSLHandshakeException` + `CertPathValidatorException` it **gives up on the origin and falls
    back to the backend RSS proxy** (`fo5.java:4700-4706`):
    ```java
    if (!z6 && i3 == 0 && (th instanceof SSLHandshakeException) && y85.n(th).contains("CertPathValidatorException")) {
        a20.E(str9, "Certificate issue, trying to use proxy");
        return wn5.v(PodcastAddictApplication.E(), uriI.toString());
    }
    ```
  * On `UnknownHostException`/`ConnectException` it calls `s0(host)` — the tracker-prefix
    workaround (§3.8) — and retries with the rewritten host.
  * There are two SSL-fallback paths in `fo5.n0()`: `i2 == 1` → `Arrays.asList(ui0.h, ui0.i)`
    (COMPATIBLE_TLS incl. TLS 1.0/1.1), `i2 == 2` → `ye4.b(ha3Var)`, which installs a custom
    `TrustManager` over a `KeyStore` pre-loaded with three hard-coded root CAs
    (`ye4.java:36-46`: `BACKPORT_COMODO_ROOT_CA`, `SECTIGO_USER_TRUST_CA`,
    `LETSENCRYPT_ISRG_CA`) plus a `GLOBALSIGN_R6` — a hand-maintained CA bundle for old Android
    devices, mirroring `res/xml/network_security_config.xml`'s
    `<certificates src="@raw/isrg_root_x1"/>` etc.
* **Exponential backoff for scheduling**, not for HTTP: `fo5.r(int, String, String)`
  (`fo5.java:2107`) and `ExponentialBackOff.DEFAULT_MAX_INTERVAL_MILLIS` referenced in
  `yc3.java:359`.

**Redirects.** OkHttp's built-in following is on (`ha3Var.i`, `ha3Var.j`). The app additionally
classifies redirects itself in `fo5.R(tb4, int, int, boolean)` — `fo5.java:713-740`:

```java
boolean z3 = i4 == 301 || i4 == 308;              // permanent
if (!z3 && !z2) z3 = i4 == 303 || i4 == 307 || i4 == 302;
…
if (!str2.equals(str3) && str2.startsWith("http://") && str3.equals(str2.replace("http://", "https://"))) {
    a20.E(r0, "isPermanentRedirection(" + i4 + ") - Permanent redirection when http redirected to https: …");
```
i.e. it detects **http→https upgrades as permanent redirects** and then persists the new URL on the
podcast row (via the `itunes:new-feed-url` / `pa:new-feed-url` handling in the RSS parser, §4).
`fo5.b0(String)` (`fo5.java:1043-1083`) is a narrower Acast-specific variant that rewrites
`access.acast.com/rss/…` → `feeds.acast.com/public/shows/…`.

### 3.6 Embedded API keys / tokens / identifiers

| Value | Where found | Purpose |
|---|---|---|
| `d2fad335-a44d-4aeb-9e5b-67b19a15572c` | `fo5.java:2003` (`X-App-Key` header), `wn5.java:2422` (`key` param), `wn5.java:659` (`submit_radio_catalog.php` body), `ey6.java:103` (a second, base64 blob) | **the app's single shared backend API key** — a GUID, sent on *every* `addictpodcast.com` request |
| `AIzaSyBicrf4tD7I0dtz8TlWRgaS5oUhxa6aLZA` | `db4.java:197` inside `https://www.googleapis.com/customsearch/v1?cx=016898519741764642980%3Ayxwdudstyra&safe=medium&num=3&searchType=image&key=…&q=` | **Google Custom Search JSON API key** (image search feature) |
| `016898519741764642980:yxwdudstyra` | same line | the Custom Search engine id (`cx`) |
| `d2fad335-…` variant `ey6.java:103` | `ey6.java:103` — a long base64 literal | a second key/token used by a different code path; not resolvable statically to an endpoint |

No OAuth client-id, no Firebase web API key string (Firebase config comes from
`google-services`-generated `resources/com/google/...` values, decoded by jadx into
`resources/res/values/…`), and no secret used to sign requests. The backend key is a bearer-ish
shared secret: **anyone with the APK has it**, and it is the same for all installs.

The Google Sign-In flow used by cross-device sync (`j25.java:60` `googleSignInAccount.b` = the
`idToken`) and the resulting `Authorization: Bearer <sessionToken>` are the only real
per-user credentials (§3.7, §9).

### 3.7 The complete endpoint table

Legend: **M** = HTTP method. All URLs are literal strings in the decompiled tree. "Params" are the
literal keys used at the call site. `fo5.p()` and `fo5.q()`/`fo5.j0()`/`fo5.k0()` are the four
call helpers described above.

#### A. First-party backend — `https://addictpodcast.com/ws/php/v4.1/…`

All of these go through `fo5.p(url, params, false)` unless noted, and therefore all carry
`X-App-Key` + `X-App-Installer` and the app UA.

| # | Endpoint (exact) | M | Params (literal names) | Purpose | Evidence |
|---|---|---|---|---|---|
| A1 | `/ws/php/v4.1/searchpodcast.php` | GET | `term` (lower-cased), `type` (media type name, omitted when `jp3.b`), `languages`, `category`, `exactName` ("1"/"0"), `dateFilter` ("1"/"0"), `explicitFilter` ("1"/"0") | **Podcast search** (the app's own engine) | `to3.java:397-426` |
| A2 | `/ws/php/v4.1/search_podcast_by_url.php` | GET | `url` (lower-cased, `Uri.encode`d) | Podcast search by exact feed URL | `to3.java:387` |
| A3 | `/ws/php/v4.1/search_episodes.php` | GET | `term`, `type`, `languages`, `dateFilter`, `explicitFilter` | **Episode search** | `wn5.java:548-555`, `wn5.java:586` |
| A4 | `/ws/php/v4.1/get_podcast.php` | GET | `id` | Podcast metadata by app id | `wn5.java:3338`, `el4.java:33` |
| A5 | `/ws/php/v4.1/get_podcast_server_id.php` | GET | `url` | Feed URL → server-side podcast id | `wn5.java:3439` |
| A6 | `/ws/php/v4.1/get_podcast_itunes_id.php` | GET | (see `e54.java:93`) | Feed URL → iTunes id | `e54.java:93` |
| A7 | `/ws/php/v4.1/get_podcast_top_episodes.php` | GET | `id` **or** `url` | Top episodes of a podcast | `wn5.java:3490` |
| A8 | `/ws/php/v4.1/get_popular_podcasts.php` | GET | `languages`, `limit` (6) | "Popular podcasts" rail | `pf5.java:220` |
| A9 | `/ws/php/v4.1/get_popular_episodes.php` | GET | `languages`, `limit` (200), `nbDays`, `search`, `category` | Popular episodes | `wn5.java:3554-3565` |
| A10 | `/ws/php/v4.1/get_popular_search_terms.php` | GET | `languages` (optional), `category` (omitted when empty) | Search suggestions | `wn5.java:3592-3598` |
| A11 | `/ws/php/v4.1/get_new_podcasts.php` | GET | `languages`, `category` (optional), `offset`, `limit` (100) | "New podcasts" discovery | `if5.java:251` |
| A12 | `/ws/php/v4.1/get_trending_podcasts.php` | GET | `languages`, `category`, `offset`, `limit` (100) | "Trending" | `if5.java:262` |
| A13 | `/ws/php/v4.1/get_random_podcasts.php` | GET | `languages`, `category`, `limit` (100) | "Random" / shuffle | `if5.java:272` |
| A14 | `/ws/php/v4.1/get_top_podcasts.php` | GET | `languages`, `category`, `offset`, `limit` (200), `isAudio` ("true"/"false"), `durationFilter` (`-1`,`600`,`1200`,`1800`,`2400`) | Top charts / browse by category+audio flag | `wn5.java:3722-3725` |
| A15 | `/ws/php/v4.1/get_podcasts_by_author.php` | GET | (see `uh4.java:63`) | "Podcasts by author" | `uh4.java:63` |
| A16 | `/ws/php/v4.1/get_similar_podcasts.php` | GET | (see `wn5.java:3695`) | Similar podcasts | `wn5.java:3695` |
| A17 | `/ws/php/v4.1/podcasts_suggestions.php` | GET | (see `wn5.java:3679`) | Personalised suggestions | `wn5.java:3679` |
| A18 | `/ws/php/v4.1/get_topics.php` | GET | (see `c95.java:62`) | Topics directory | `c95.java:62` |
| A19 | `/ws/php/v4.1/retrievenetworks.php` | GET | — | Podcast "networks" list | `of5.java:61` |
| A20 | `/ws/php/v4.1/retrieve_network_podcasts.php` | GET | (see `nf5.java:160`) | Podcasts in a network | `nf5.java:160` |
| A21 | `/ws/php/v4.1/get_radio.php` | GET | `catalogStationId` **or** (`id`, `tuneInId`) | Radio station lookup | `fl4.java:62`, `fl4.java:123` |
| A22 | `/ws/php/v4.1/get_top_radios.php` | GET | `countryName`, `limit` (100) | Top radios by country | `uf5.java:191` |
| A23 | `/ws/php/v4.1/search_radios.php` | GET | `term` (via `wn5.c(1)`: also `key`, `installer`) | Radio search | `ii4.java:69` |
| A24 | `/ws/php/v4.1/get_iha.php` | GET | (see `c72.java:129`) | "In-house ads" / promoted content | `c72.java:129` |
| A25 | `/ws/php/v4.1/get_APS.php` | POST JSON | `key` = the GUID | Ad/promo payload | `zb.java:132` |
| A26 | `/ws/php/v4.1/get_adCampaign.php` | GET | — | Ad campaign definitions | `grep` hit, call site in `defpackage` |
| A27 | `/ws/php/v4.1/get_blocking_services.php` | GET | — | Blocklist of tracking prefixes/services to ignore | `zb.java:60` |
| A28 | `/ws/php/v4.1/get_content_policy_violation.php` | GET | — | Content-policy blocklist | `zb.java:680` |
| A29 | `/ws/php/v4.1/get_rss_redirections.php` | GET | (see `qz4.java:230,311`) | Known feed redirects | `qz4.java:230,311` |
| A30 | `/ws/php/v4.1/rss_proxy.php` | GET | `url`, `ts` (`System.currentTimeMillis()`) | **Backend RSS proxy fallback** | `wn5.java:3665-3672`, used at `fo5.java:4702` |
| A31 | `/ws/php/v4.1/submitpodcast.php` | POST JSON | `urls[]` = `{url, iTunesId?}` | Submit a new podcast to the directory | `wn5.java:705` |
| A32 | `/ws/php/v4.1/submitpodcastregistration.php` | POST JSON | (see `wn5.java:1617`) | Claim/register a podcast | `wn5.java:1617` |
| A33 | `/ws/php/v4.1/submit_radio_catalog.php` | POST JSON | caller JSON + `key` | Submit a radio station | `wn5.java:661` |
| A34 | `/ws/php/v4.1/report_radio_catalog.php` | POST JSON | (see `e3.java:107`) | Report a radio entry | `e3.java:107` |
| A35 | `/ws/php/v4.1/get_radio_catalog_fallback.php` | POST JSON | (see `ej2.java:1301`) | Radio fallback | `ej2.java:1301` |
| A36 | `/ws/php/v4.1/get_episode.php` | GET | `id` | Episode metadata by app id | `al4.java:43` |
| A37 | `/ws/php/v4.1/ping_user.php` | POST JSON | `userUUID`, `appVersion`, `appBuild`, `hasDonated` (0/1/2), `subscriptionNumber`, `languages`, `device`, `totalSkippedTime`, `totalRadioTime`, `totalPlaybackTime`, `androidVersion`, `androidAPI`, `playbackTelemetryVersion` (1), `installer`, `userId`?, `userTokenId`? | User heartbeat / install telemetry | `wn5.java:126-176` |
| A38 | `/ws/php/v4.1/reset_user_registrations.php` | GET | `userUUID` (+ `key`,`installer`) | Un-register the install | `wn5.java:524-528` |
| A39 | `/ws/php/v4.1/update_global_stats.php` | POST JSON | `key`, `value`, `playbackTelemetryVersion` | Global counter (sharded) | `wn5.java:641-648` |
| A40 | `/ws/php/v4.1/update_podcast_stats.php` | POST JSON | (see `wn5.java:1155`) | Per-podcast stats | `wn5.java:1155` |
| A41 | `/ws/php/v4.1/update_playback_statistics.php` | POST JSON | `origin` ("podcast_addict"), `shard` (0-63), `batches[]` = `{delivery_id, period_start, content_type, listening_seconds, playback_speed_saved_seconds, skipped_silence_seconds, skip_forward_seconds, skip_intro_seconds, skip_outro_seconds, muted_chapter_seconds}` | Playback telemetry upload | `wn5.java:755-785` |
| A42 | `/ws/php/v4.1/update_categories_stats.php` | POST JSON | (see `r70.java:863`) | Category impression stats | `r70.java:863` |
| A43 | `/ws/php/v4.1/update_iha_stats.php` | POST JSON | (see `c72.java:543`) | In-house-ad stats | `c72.java:543` |
| A44 | `/ws/php/v4.1/update_curatedList_stats.php` | POST JSON | (see `xo0.java:120`) | Curated-list impression stats | `xo0.java:120` |
| A45 | `/ws/php/v4.1/update_adCampaign_stats.php` | POST JSON | — | Ad campaign stats | grep hit |
| A46 | `/ws/php/v4.1/track_3rdparty_search.php` | GET | (see `ne3.java:286`) | Logs a search that came from an external source (e.g. Google Assistant/App Actions) | `ne3.java:286` |
| A47 | `/ws/php/v4.1/get_podcast_stats.php` | GET | (see `j5.java:310`) | Podcast stats read | `j5.java:310` |
| A48 | `/ws/php/v4.1/notify_rss_redirect.php` | POST JSON | (see `st4.java:353`) | Tell the backend a feed moved | `st4.java:353` |
| A49 | `/ws/php/v4.1/post_websub_urls.php` | POST JSON | `websubs[]` = `{id, hub, topic}` | **WebSub** hub/topic subscription | `bo5.java:118-121` |
| A50 | `/ws/php/v4.1/flagContent.php` | POST JSON | (see `pk.java:139`) | Report content | `pk.java:139` |
| A51 | `/ws/php/v4.1/post_review.php` | POST JSON | (see `vc4.java:438`) | Write a podcast review | `vc4.java:438` |
| A52 | `/ws/php/v4.1/edit_review.php` | GET | (see `vc4.java:218`) | Edit own review | `vc4.java:218` |
| A53 | `/ws/php/v4.1/delete_review.php` | GET | (see `vc4.java:170`) | Delete own review | `vc4.java:170` |
| A54 | `/ws/php/v4.1/delete_user_reviews.php` | GET | (see `vc4.java:118`) | Delete all own reviews | `vc4.java:118` |
| A55 | `/ws/php/v4.1/flag_review.php` | GET | (see `vc4.java:262`) | Report a review | `vc4.java:262` |

**Pagination.** Only four endpoints paginate, all with the same `offset`/`limit` pair:
`get_new_podcasts.php`, `get_trending_podcasts.php`, `get_random_podcasts.php` (limit 100) and
`get_top_podcasts.php` (limit 200). The caller loop is visible at `if5.java:236-290`
(`i10 += …; arrayListY = wn5.o(… "offset", String.valueOf(i10) … "limit", String.valueOf(100))`).
Search endpoints (`A1`, `A3`) do **not** paginate — a single request returns the full result set,
capped server-side.

#### B. Apple / iTunes (called directly, no key)

| # | Endpoint (exact) | M | Params | Purpose | Evidence |
|---|---|---|---|---|---|
| B1 | `https://itunes.apple.com/search?media=podcast&limit=100&term=<URL-encoded>` | GET | `media=podcast`, `limit=100`, `term`; **plus `&g=<storefrontId>`** (`r70.b(country)` → numeric id, `r70.java:61-77`) | **Podcast search** | `to3.java:358-363` |
| B2 | `https://itunes.apple.com/search?entity=podcastEpisode&media=podcast&limit=200&term=<uri-encoded, words joined by `+`>` | GET | `entity=podcastEpisode`, `media=podcast`, `limit=200`, `term` | **Episode search** | `qh4.java:462-467` |
| B3 | `https://itunes.apple.com/lookup?id=<itunesId>` | GET | `id` | feed-URL resolution from an iTunes id | `z42.java:260-267`, `jc4.java:46` |
| B4 | `https://itunes.apple.com/%s/rss/customerreviews/page=%d/id=%s/sortby=mostrecent/json` | GET | `%s` = lower-cased country code (`pref_iTunesCountry`, default `US`, falling back to `PodcastAddictApplication.L0()`, `z42.java:294-302`), `page`, `id` | Podcast reviews | `z42.java:295-302` |
| B5 | `https://itunesu.itunes.apple.com/feed/id<itunesId>` | GET | — | iTunes-U feed URL synthesised from an `/itunes-u/` link | `z42.java:336-338` |

Store-front id mapping: `r70.b(String countryName)` returns `p70.d` for the matching category entry
(`r70.java:61-77`); `-1` when unknown, in which case the `&g=` parameter is omitted entirely
(`to3.java:360-362`). The `languages` parameter accepted by the backend is built by
`wn5.d(boolean, boolean)` (`wn5.java:2432-2446`) as a comma+space separated, quoted list from
`PodcastAddictApplication.P()`.

iTunes response parsing: `z42.a(JsonReader, String, ArrayList)` (`z42.java:33-266`) reads
`results[].{trackName, feedUrl, collectionId, artworkUrl160, artworkUrl100, artworkUrl60,
artworkUrl30, artworkUrl600, releaseDate, trackCount, artistName, description, shortDescription,
primaryGenreName, contentAdvisoryRating, …}`, filters `contentAdvisoryRating == "Explicit"` when the
user has explicit content disabled (`z42.java:216-224`), and picks the largest artwork
(`z42.java:190-215`).

#### C. TuneIn / RadioTime OPML (only for the "live radio" feature)

| # | Endpoint (exact) | M | Params | Purpose | Evidence |
|---|---|---|---|---|---|
| C1 | `https://opml.radiotime.com/Search.ashx?query=<q with spaces→%20>&types=station&render=json` | GET | `query`, `types=station`, `render=json` | Radio station search | `ii4.java:59` |
| C2 | `https://opml.radiotime.com/Browse.ashx?c=schedule&id=<id>&render=json` | GET | — | Schedule browse | `pk3.java:229` |
| C3 | `https://opml.radiotime.com/Browse.ashx?id=<id>&render=json` | GET | — | Station browse | `ej2.java:1448` |
| C4 | `https://opml.radiotime.com/Browse.ashx?c=local&render=json` | GET | `c=local` | "Local stations" | `g10.java:232` |
| C5 | `https://opml.radiotime.com/Describe.ashx?c=nowplaying&id=<id>&render=json` | GET | `c=nowplaying` | Now-playing metadata | `ak3.java:107` |
| C6 | `https://opml.radiotime.com/Describe.ashx?id=<id>&render=json` | GET | — | Station description | `ej2.java:3890` |
| C7 | `https://cdn-cms.tunein.com/service/Audio/{georestricted.enUS, nostream.enUS, notcompatible.enUS, restricted.enUS}.mp3` | GET | — | Sentinel streams used to detect geo/stream errors | `ej2.java:49-54` |

`g10.java:213-232` special-cases `opml.radiotime.com` + path containing `browse.ashx` when following
OPML redirects, and prepends a synthetic "local stations" entry.

#### D. Google APIs (image search, drive backup, auth)

| # | Endpoint (exact) | M | Params | Purpose | Evidence |
|---|---|---|---|---|---|
| D1 | `https://www.googleapis.com/customsearch/v1?cx=016898519741764642980%3Ayxwdudstyra&safe=medium&num=3&searchType=image&key=AIzaSyBicrf4tD7I0dtz8TlWRgaS5oUhxa6aLZA&q=<term>` | GET | `cx`, `safe`, `num`, `searchType`, `key`, `q` | **Image search** (podcast/episode artwork) | `db4.java:197` |
| D2 | `https://www.google.com/search?tbm=isch&safe=active&q=<term>` | GET | `q` | Fallback image search (scrapes `<img>` matches with the regexes at `fo5.java:139-140`) | `rn.java:114` |
| D3 | `https://www.googleapis.com/drive/v3/…` | GET | — | Google Drive backup/restore | grep hit (`grep -rn "googleapis.com/drive" sources/` → `z…java`) |
| D4 | `https://www.googleapis.com/batch` | POST | — | Batched Drive calls | grep hit |
| D5 | `https://accounts.google.com/o/oauth2/token`, `https://accounts.google.com/o/oauth2/auth` | — | — | Google Sign-In token endpoints (Play-services-internal) | grep hit |
| D6 | `https://www.googleapis.com/youtube/v3/…` | GET | — | YouTube (player for video podcasts) | grep hit |
| D7 | `https://api.twitch.tv/helix/…` | GET | — | Twitch (video podcast source) | grep hit |

#### E. Everything else (third-party services pulled in by the ad/analytics stack)

These are in the binary but are not podcast functionality. Listed for completeness so the endpoint
census is exhaustive.

* Fyber/Inneractive: `https://telemetry.sdk.inmobi.com/metrics`, `https://unif-id.ssp.inmobi.com/fetch`,
  `https://synapse.exchange.inmobi.com/v1/signals/push`, `https://cdn2.inner-active.mobi/…`,
  `https://supply.inmobicdn.net/…`
* Google Mobile Ads: `https://googleads.g.doubleclick.net/…`, `https://pagead2.googlesyndication.com/pagead/ping?e=2&f=1`,
  `https://www.googleadservices.com/pagead/conversion/app/deeplink?id_type=adid&sdk_version=`,
  `https://imasdk.googleapis.com/admob/sdkloader/native_video.html`, `https://app-measurement.com/{a,s/d}`
* Amazon: `https://c.amazon-adsystem.com/`, `https://www.amazon.com/gp/mas/dl/android?`
* AppLovin: `https://rt.applovin.com/`, `https://ms.applovin.com/`, `https://sts.applovin.com/v1/stats/sdk`, `https://prod-a.applovin.com/…`
* Firebase/Crashlytics: `https://firebase-settings.crashlytics.com/spi/v2/platforms/android/gmp/…`
* Sponsored-content/measurement: `https://spadsync.com/sync`, `https://prod.tahoe-analytics.publishers.advertising.a2z.com/logevent/putRecord`, `https://prod.cm.publishers.advertising.a2z.com/logrecord/putlog`
* Google Fonts: `https://fonts.googleapis.com`

#### F. Verified *absence*

* No `podcastindex.org`, no `api.podcastindex.org`, no `gpodder.net`, no `listennotes.com`, no `fyyd.de`.
* No `acast.com` API (only feed URLs, rewritten by `fo5.b0`).
* No Chartable/Podtrac/Optimized-by-AdvertiseCast *API* — only URL-prefix stripping (§3.8).
* No websocket endpoint of the app's own (the `RealWebSocket`/`WebSocketProtocol` imports in
  `fo5.java:16-17` are from the bundled OkHttp and are not used by app code — see §12).

### 3.8 URL normalisation and tracker stripping — `fo5` as a URL sanitiser

`fo5` contains a large amount of *pure* URL-mangling logic, all with string constants. In order of
application:

1. **Scheme repair** — `fo5.e0(String, boolean)` (`fo5.java:1188-1386`):
   * `d0(str)` then `X(str)` gate;
   * `podcasts.google.com/feed/…` URLs are rewritten: the `…/episode/…` suffix is cut
     (`oy4.j(strE, "/episode/")`), the page is fetched and the real feed extracted with the regex
     `ww1.b` (`;http…` up to `;`), else falls back to the episode-stripped URL
     (`fo5.java:1201-1247`).
   * `z42.e(str)` (`z42.java:325-352`) turns `itunes.apple.com/podcast/id123` /
     `podcasts.apple.com/…` into the real `feedUrl` via the `lookup` API (B3), and
     `/itunes-u/` links into `itunesu.itunes.apple.com/feed/id`.
   * `at4` handles generic "this is an HTML page, find the feed" links (§5.3), including a
     SoundCloud special case that drops the last path segment when there are more than 3 slashes
     (`fo5.java:1283-1301`).
2. **`fo5.l(String)`** (`fo5.java:1661-1684`) — `URI.create` normalisation with a fallback to
   OkHttp's `HttpUrl.parse`; detects and URL-decodes `https%3A%2F%2F…` double-encodings.
3. **`fo5.f0(url, baseUrl, validate)`** (`fo5.java:1405-1413`; body recovered from
   `jadx -m simple`) — `normalizeEpisodeUrl`:
   * **Chartable prefix stripping** over the array `n` (`fo5.java:133-134`:
     `{"chtbl.com/track/", "chrt.fm/track/"}`) — cuts everything up to the next `http://`/`https://`
     or reassembles `host + "/" + rest` when the remainder is a valid URL.
   * **Scheme fix-ups**: `^(https?|ftp|sftp|rtsp):/(?=[^/])` → `scheme://` (regex `g`,
     `fo5.java:130-131`); leading `//` → `https://`; leading `/` → `https://`;
     strips `\n`/`\r`.
   * **Relative-URL resolution** against `baseUrl` when the URL has a host but no scheme
     (`fo5.java:~1660-1710` of the simple rendering).
   * **Host validation**: every dot-separated label must be non-empty, and all but the last two
     must be ≥ 2 chars, then checked against `q84.c` (a TLD regex). Fails → the URL is returned
     unchanged.
4. **`fo5.s0(String)`** (`fo5.java:2216-2274`; body from `-m simple`) — `workaroundTrackerFailure`:
   * strips prefixes from the array **`t`** (`fo5.java:144-145`):
     `podtrac.com/redirect.mp3/`, `podtrac.com/pts/redirect.mp3/`, `pdst.fm/e/`,
     `prfx.byspotify.com/e/`, `op3.dev/e/`, `claritaspod.com/measure/`, `mgln.ai/track/`,
     `/clrtpod.com/m/` — simply truncating the URL at the end of the prefix.
   * then strips prefixes from **`u`** (`fo5.java:146-147`):
     `verifi.podscribe.com/rss/p/track/`, `play.podtrac.com/`, `chtbl.com/track/`, `chrt.fm/track/`,
     `verifi.podscribe.com/rss/`, `mgln.ai/e/`, `pscrb.fm/rss/p/`, `arttrk.com/p/`,
     `p.podderapp.com/`, `.vpixl.com/`, `a.pdcst.to/`, `tracking.swap.fm/track/`, `pdrl.fm/`,
     `.podderapp.com/` — cutting the prefix **plus one path segment**, and re-prefixing the
     extracted segment when it looks like a filename.
   * prepends `https://` if the result lost its scheme.
   * Called from `fo5.w()` on `UnknownHostException`/`ConnectException` (`fo5.java:2540, 2743, 2762, 3025`).
5. **`fo5.c0(String)`** (`fo5.java:1108-1137`) — `megaphone.fm` cleanup:
   `http→https`, `feeds-origin.megaphone.fm → feeds.megaphone.fm`, and strips everything from the
   first `?` after the last `.megaphone.fm/`.
6. **`fo5.a0(HashMap)`** (`fo5.java:907-935`) — query-string builder.
7. **Analytics-parameter stripping** — the array **`v`** (`fo5.java:148-149`):
   `_ga, _gl, _gac, _gclid, _gaexp, _gat, _gid, _utm_source, _utm_medium, _utm_campaign,
   _utm_term, _utm_content`. Used by a `Removing Analytics arguments from '…'` path
   (`fo5.java:1380-1385`) and by `zh.a()`-adjacent helpers.
8. **URL-scheme allow-list** — the `LinkedHashMap m` (`fo5.java:135-142`):
   `feed://`, `itpc://`, `itms://`, `pcast://`, `pcast:`, `podcast://`, `podcastaddict://` → `TRUE`
   (rewritten to http), and `https://podcastaddict.com/feed/` → `FALSE` (not rewritten).
   `fo5.tz`-adjacent `fo5.java:1703` (`if (tz.t(r18))`) gates entry into the normaliser.
9. **Regexes** defined alongside: `w` (`fo5.java:150-152`) strips tracking pixels and
   `<audio>/<video>` embeds from HTML descriptions; `x` (`fo5.java:153`) collapses repeated `<br>`;
   `y`/`z` (`fo5.java:154-155`) parse `Content-Disposition` `attachment;filename=` /
   `inline;filename=` to derive a download filename.

---

## 4. Feed parsing and enclosure resolution

### 4.1 The parser: SAX, hand-rolled, not a library

**Parser = `org.xml.sax` (`javax.xml.parsers.SAXParserFactory` + `org.xml.sax.helpers.DefaultHandler`).
Not XmlPullParser, not a third-party RSS library.**

Proof:
* `e54.java:39` `import javax.xml.parsers.SAXParserFactory;` and `e54.java:51`
  `public static final SAXParserFactory b = SAXParserFactory.newInstance();`
* `e54.java:1512-1516`:
  ```java
  public static XMLReader i() throws SAXException {
      XMLReader xMLReader = b.newSAXParser().getXMLReader();
      xMLReader.setEntityResolver(c);
      return xMLReader;
  }
  ```
  with `c = new hp0()` (`e54.java:52`), an `EntityResolver` that only special-cases
  `xhtml1-transitional.dtd` → a stub `<!ENTITY bull "&#8226;">` (`hp0.java:7-14`).
* The call sites drive it as `xMLReaderI.setContentHandler(h54Var); xMLReaderI.parse(wd0VarB);`
  (`e54.java:2103-2106`, `e54.java:2323-2326`).
* `XmlPullParser` appears in the tree only in `sv.java:724`/`os1.java:244` — i.e. for parsing the
  **SharedPreferences backup XML**, not RSS. The `org.xmlpull` imports in `defpackage/uc5.java`,
  `pi6.java`, `x95.java`, `aa5.java`, `zs4.java` etc. belong to media3/gson internals.

**Not namespace-aware.** `SAXParserFactory.newInstance()` is never given
`setNamespaceAware(true)`. The handler therefore works on **qualified names** (`str3`, the
`qName` argument) and compares them with `equalsIgnoreCase` — which is why every tag test in the
parser is written as `str3.equalsIgnoreCase("itunes:image")` rather than by URI+localName. This is
directly visible throughout `e4.startElement(...)` (`e4.java:2619-3049`) and `e4.endElement(...)`
(`e4.java:1829-2254`). The `namespaceURI` parameter (`str`) is effectively unused.

**Handler hierarchy** (all in `defpackage/`):

```
u1  "AbstractHandler"      extends org.xml.sax.helpers.DefaultHandler   u1.java:11
 └─ w0  "AbstractFeedHandler"  extends u1                               w0.java:15
     ├── h54  "RSSUpdatePodcastHandler"  extends w0                      h54.java:14
     └── e4   "AbstractRSSEpisodesHandler" extends w0                     e4.java:32
         ├── f54  "RSSNewEpisodesHandler"   extends e4                   f54.java:12
         └── g54  "RSSUpdateEpisodeHandler" extends e4                    g54.java:11
a83 "OPMLHandler" extends u1                                              a83.java:7
```

`u1` supplies the character accumulation (`StringBuilder c`, reset with `c()` capped at 16 KiB) and
the attribute helper `u1.a(Attributes, name, default)` (`u1.java:22-26`).
`w0` adds the "is this an HTML page?" branch (`w0.C(...)`, `w0.java:116-152`) and the
`<meta http-equiv=refresh>` handler.

Both **RSS 2.0** (`<rss><channel><item>…`) and **Atom** (`<feed><entry>…`) are supported, and the
code deliberately duplicates the item/entry branch for each: `e4.startElement` runs the RSS branch
when `N()` is false (`this.J && !this.K`, `e4.java:940-943`) and the Atom branch when true, with
`X()` creating a new episode on `<item>`/`<entry>` and `W()` closing one.

**Which elements/namespaces are read — the definitive list** (all from `e4.java`, `w0.java`,
`h54.java`; `f54`/`g54` only override `M`, `F`, `P`, `K`, `W`, `f0`):

**RSS core (no prefix):**
`channel`, `item`, `title`, `link`, `description`, `author`, `category`, `comments`, `guid`,
`pubDate`, `enclosure`, `image`, `url`, `language`, `lastBuildDate`, `managingEditor`,
`webMaster`, `generator`, `docs`, `ttl`, `rating`, `skipHours`, `skipDays`, `textInput`,
`cloud`.

**`itunes:` namespace (iTunes Podcast RSS extension):**
`itunes:author`, `itunes:block`, `itunes:category`, `itunes:complete`, `itunes:duration`,
`itunes:email`, `itunes:episode`, `itunes:episodeType`, `itunes:explicit`, `itunes:image`
(attribute `href`), `itunes:keywords`, `itunes:name`, `itunes:new-feed-url`, `itunes:owner`
(`+ itunes:name`), `itunes:season`, `itunes:subtitle`, `itunes:summary`, `itunes:title`, `itunes:type`.

**`podcast:` namespace (PodcastIndex / "podcast" 1.0 spec):**
`podcast:alternateEnclosure` (attrs `type`, `length`, `bitrate`, `title`, `default`; also read as
element text), `podcast:chapters` (attr `url` → `ob1.U`), `podcast:funding` (attr `url`),
`podcast:guid`, `podcast:liveItem`, `podcast:location`, `podcast:person`, `podcast:private`
(alias for `itunes:block`), `podcast:season` (attr `role`, and as element text),
`podcast:socialInteract`, `podcast:source`, `podcast:transcript` (attrs `url`, `type`).

**`psc:` (Podlove Simple Chapters):**
`psc:chapters` (attr `version`), `psc:chapter` (attrs `start`, `startTime`, `title`, `href`, `image`).
Also the Podlove legacy form `<link rel="http://podlove.org/simple-chapters" href=…>`
(`e4.java:2736-2745`) — logged, not parsed.

**`media:` namespace (MRSS):**
`media:content` (attrs `url`, `type`, `medium`, `duration`, `isDefault`), `media:thumbnail`
(→ `J(url)`), `media:title`, `media:description`, `media:community`, `media:credit`, `media:player`.

**Atom (`atom:`):**
`feed`, `entry`, `id`, `title`, `subtitle`, `summary`, `content`, `published`, `updated`, `link`
(with `rel=enclosure|alternate|payment`, attrs `href`, `type`, `length`, `duration`, `isDefault`),
`author` (`+ name`), `category`, `rights`, `logo`, `generator`, `icon`.

**Others actually handled:**
* `content:encoded`
* `wfw:commentRss` (`commentRss` local name)
* `commentsRss` (`commentRss`)
* `dc:creator` (local name `creator`), `dc:date`, `dc:title`, `dc:description`, `dc:subject`,
  `dc:rights`, `dc:publisher`, `dc:language` — matched on local name only
* `geo:lat`, `geo:long`, `georss:point` — folded into `podcast:location`
* `rawVoice`/`rawvoice:donate` (attr `href` → `z0`)
* `googleplay:*` — **not present**; `grep -rn "googleplay:" sources/defpackage/` returns nothing.
  (There *are* `podcasts.google.com/feed/…` URL rewrites, but no `googleplay:` element parsing.)
* `psc:` is present; `podcast:` prefix is the PodcastIndex one.
* `sy:updatePeriod`/`sy:updateFrequency` — **not present** (no hits).
* `atom10:link` — matched by local name `link`, prefix-agnostic.
* `source:*` (podcasting 2.0 `source:*` remote items) — **not present**.
* `podcast:value` — **not present** (§1).
* `podcast:locked` — **not present**; `acast:locked-item` is (`e4.java:2128, 2791, 2926`) and merely
  sets `this.x0`, which suppresses the item's body handling (`e4.java:1836-1838`, `e4.java:2128-2130`).
* App-private extensions: `pa:new-feed-url` (`e4.java:1921`) — a
  Podcast-Addict-specific redirect tag, handled by `V(String)` (`e4.java:1131-1143`) which logs
  `"Unknown podcast using private redirection tag..."` and only honours it when
  `v54.b(ul3)` (i.e. when the podcast is registered to this developer).
* `anchor:support` (`e4.java:1928`).
* `podcast:liveItem` — parsed via `m(Attributes)` (`e4.java:2786`), closed via `n()` (`e4.java:1837`).
* `<redirect><newLocation>…</newLocation></redirect>` — `this.C` + `k(b(), true)`
  (`e4.java:1943-1947`).

**Podcast-level vs episode-level dispatch** is by the flag `N() = this.J && !this.K`
(`e4.java:940-943`): `J` is set on `<channel>`/`<feed>` start and cleared on its end
(`e4.java:1953`, `e4.java:2215`), `K` is set on `<item>`/`<entry>` start
(`X()`, `e4.java:1324`) and cleared on end (`W()`, `e4.java:1144`).

### 4.2 Enclosure collection

Every enclosure candidate is accumulated into a `d4` record and appended to one of **three** lists
(`e4.java:308-317`):

| List | Field | Fed by |
|---|---|---|
| `this.Y` (`arrayList2`) | `enclosure` (RSS, `e4.java:2016, 2759, 2800, 2935`) and `atom:link rel=enclosure` (`e4.java:2676-2690`) |
| `this.Z` | `media:content` (`e4.java:2021, 2818, 2948`) |
| `this.a0` (`arrayList3`) | `podcast:alternateEnclosure` (`e4.java:2113, 2234, 2728, 2915, 3040`) |

The `d4` constructor is called with **five** arguments, always in this order:

```java
new d4(url, type, length /* from H(Attributes) */, duration, isDefault)
```

`e4.H(Attributes)` — `e4.java:177-185`:
```java
public static String H(Attributes attributes) {
    String strA = null;
    if (attributes != null) {
        String strA2 = u1.a(attributes, "length", null);
        strA = TextUtils.isEmpty(strA2) ? u1.a(attributes, "fileSize", null) : strA2;
    }
    return TextUtils.isEmpty(strA) ? "-1" : strA;
}
```
so the byte length comes from `@length` **or** `@fileSize`, defaulting to the string `"-1"`.

`duration` comes from `@duration` (RSS/MRSS convention; also present on atom enclosures), and
`isDefault` from `@isDefault` (default `"false"`). All three are `null` when the enclosure is given
as element *text* rather than attributes (`e4.java:2216-2222`, `e4.java:2224-2230`).

Insertion order is **not** first-wins. `e4.E(List, d4)` — `e4.java:202-214`:

```java
public final void E(List list, d4 d4Var) {
    if (list != null) {
        if (!this.v0 && d4Var.e) {          // e == isDefault
            list.add(0, d4Var);             // insert at the FRONT
            this.v0 = true;
        } else {
            if (list.contains(d4Var)) return;   // exact dedup
            list.add(d4Var);
        }
    }
}
```

So: **the first enclosure carrying `isDefault="true"` is jumped to index 0**, subsequent ones are
appended (deduplicated by `d4.equals`, which is a pair of url+type — `d4.java`). If no enclosure is
marked default, order is document order.

### 4.3 Enclosure selection — `e4.I(String baseUrl)`

`e4.java:258-866` is the resolver (610 lines). Algorithm, in order:

**Step 0 — synthesise an enclosure from the episode `link`** (`e4.java:308-320`):
if all three lists are empty and the episode has a `d` (link) field, take the URL's extension with
`xk1.o(url)`; if it is in the audio-extension set `lj3.d` (`lj3.java:59-66`:
`.mp3 .m4a .m4b .aac .aax .flac .ogg .wav .mid .mod .mp1 .mp2 .oga .ogx .opus .alac .weba .mp3package`),
create `new d4(link, xk1.r(ext), null, null, false)` — i.e. **the episode's own `<link>` becomes the
enclosure when it points at an audio file**.

**Step 1 — merge the three lists** in the order `Y` (RSS enclosures) → `Z` (media:content) →
`a0` (alternateEnclosure), deduplicating (`e4.java:322-348`).

**Step 2 — pick the target media type.** `e4.java:349-356`:
```java
boolean z3 = ul3Var.J;              // accept_audio
if (z3 && !ul3Var.K)      jp3Var4 = jp3.d;   // AUDIO only
else if (ul3Var.K && !z3)  jp3Var4 = jp3.e;   // VIDEO only
```
where `podcasts.accept_audio` / `accept_video` are the per-podcast toggles (`so3.java:590`), so
**when a podcast accepts only audio (the default), video enclosures are skipped** and vice-versa.

**Step 3 — iterate the merged list**, and for each candidate:
* `d()` — if the episode duration is unknown, parse the `@duration` string through `S(String)`
  (`e4.java:1048-1130`), which normalises `HH:MM:SS`, `HH:MM:SS.mmm`, raw seconds, and
  milliseconds (`> 86400` is treated as ms and divided by 1000).
* **Image detection**: `("image".equalsIgnoreCase(type) || tz.v(url, tz.b))` → the candidate is
  demoted to `this.U` (the episode artwork) and *not* considered as media
  (`e4.java:380-384`).
* `y85.l(type, url)` — `y85.java:373-420` — resolves the media type (§4.4).
* **Skip when it equals the base URL** (`str` argument), i.e. don't use the feed itself as an enclosure.
* **`z5` latch**: `z5 = y85.s(type) || y85.w(type)` — `y85.s` = "is audio"
  (`y85.java:526-531`: contains `audio` or `mp3`, or equals `application/ogg` /
  `application/x-flac`); `y85.w` = "is video" (`y85.java:566-572`: contains `video`, or
  `application/vnd.apple.mpegurl` / `application/x-mpegurl`). Once a *typed* enclosure has been
  seen, `z5` is sticky.
* **Extension sniffing when `type` is missing** (`e4.java:393-415`): the query string is cut at
  `?`, the extension taken with `xk1.o`, and then
  ```java
  if ((".mp4".equalsIgnoreCase(strConcat) && zS && lj3.g(strConcat, lj3.c))
      || (zW && lj3.g(strConcat, lj3.b))) {
      strR = xk1.r(strO2);
      ((ob1) this.b).i0 = true;      // flag: media type derived from the URL extension
  }
  ```
  i.e. for a `.mp4` URL that `y85` thinks is audio, the video extension set `lj3.c`
  (`lj3.java:69-72`: `.mov .avi .mp4 .3gp .ogv .ts .m4v .mkv .webm .mpg .mpeg .ogm .m2ts`) is
  consulted, and the type is *overridden to* `xk1.r(ext)`.
* **SoundCloud fallback**: if the URL contains `soundcloud.com/` (`at4.a`) the type is forced to
  `"audio"` (`e4.java:437-447`).
* **Size-implied video**: if the `@length` parses to `> 3000` (the RSS default when the attribute is
  absent is `"-1"`, so this is a *bytes* heuristic) and the podcast accepts video, the type becomes
  `"video"` (`e4.java:454-462`).
* **Final type assignment**: `strR` (the resolved MIME or the literal `"audio"`/`"video"`) →
  `ob1.n`; `hc1.A0(strR)` (`hc1.java:93-112`) maps it to the `jp3` enum, with `hc1.z0(ob1Var)`
  (`hc1.java:7164+`) as the fallback when the MIME is unknown.
* **URL finalisation**: `ob1Var.m = fo5.f0(url, null, false)` (`e4.java:479`), i.e. the
  Chartable/scheme/relative normaliser of §3.8 is applied to every enclosure URL. A URL starting
  with `/` after normalisation is logged as `"Invalid episode url: …"` (`e4.java:482-486`).
* **Size** is parsed into `ob1Var.r` (`e4.java:489-496`); values `>= 3000` are kept.
* A per-type counter `this.W` (an `EnumMap<jp3, AtomicInteger>`) tallies how many enclosures of each
  type were accepted (`e4.java:498-512`).
* **Loop exit**: breaks out as soon as an enclosure of the target type is found (`z6` latches) —
  but only after the artwork has been captured.

**Step 4 — resolve `podcast:alternateEnclosure`** (`e4.java:211-289` of the `-m simple` rendering,
entered from `e4.java:~2180`): for each entry in `this.X` (a `pd1` list), it
* ignores anything whose URL equals the already-chosen `ob1Var.m`, is not a URL
  (`fo5.U`), or ends in `.torrent`;
* computes `hc1.d0(bitrate, length, duration)` and, when positive, *rewrites* `pd1.f` (bitrate)
  with it — i.e. **bitrate is derived from size/duration when the feed lied or omitted it**;
* coerces the type via `y85.l(type, url)` and `hc1.A0(...)` and only keeps entries whose type
  matches the accepted one.
These become `episodes.alternate_urls` (`so3.java:16`).

**Summary of the selection rule:**
> **Type-first, then document order (with `isDefault` bumped to the front), filtered by the
> podcast's `accept_audio`/`accept_video`, with MIME inferred from the URL extension when `@type`
> is missing, `.mp4`-vs-audio conflicts resolved in favour of the video extension set, SoundCloud
> forced to audio, and `@length > 3000` implying video. There is no bitrate/quality preference for
> the *primary* enclosure; bitrate only matters inside `podcast:alternateEnclosure`.**

### 4.4 Media type determination when `@type` is missing or wrong

`y85.l(String type, String url)` — `y85.java:373-420`:

```java
if (!TextUtils.isEmpty(url)) {
    if (nt5.h(url))  return "YOUTUBE";     // youtube.com / youtu.be
    if (dd5.b.matcher(url).find()) return "TWITCH";
    if (am5.a.matcher(url).find()) return "VIMEO";
    if (as0.a.matcher(url).find()) return "DAILYMOTION";
    if (TextUtils.isEmpty(type)) {
        String strQ = xk1.q(url);          // MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (!TextUtils.isEmpty(strQ)) return strQ;
    }
}
return type;
```

`xk1.q(String)` — `xk1.java:551-562` — resolves the extension via
`MimeTypeMap.getFileExtensionFromUrl` → `MimeTypeMap.getSingleton().getMimeTypeFromExtension(...)`;
`xk1.o(String)` (`xk1.java:513-530`) is the extension extractor (falls back to a manual
last-`.`-before-`?` scan, and drops an `aspx` 4-char extension).

**There is no content sniffing** (no magic-byte inspection, no `URLConnection.guessContentTypeFromStream`).
Type resolution is: `@type` → host heuristics (YouTube/Twitch/Vimeo/DailyMotion) → filename extension
→ `MimeTypeMap`. The `.mp4` override in `e4.I` is the only "wrong @type" repair.

### 4.5 `guid` handling — dedup and "already played"

`guid` is stored in `episodes.guid` (`so3.java:16`, `TEXT NOT NULL`) and is the dedup key.

* **New-episode path** — `f54.M(String guid)` (`f54.java:113-160`):
  ```java
  this.V0.add(str);                                     // everything seen in this parse
  if (this.I0 == null) this.I0 = str;                   // remember the FIRST guid of the feed
  HashSet hashSet = this.U0;                            // preloaded from the DB
  if (!z || !str.contains("://")) {
      if (!hashSet.add(str)) return false;              // already known ⇒ skip episode
      ((ob1) this.b).z = str; return true;
  }
  // scheme-swap workaround: try https<->http of the same guid
  ```
  The preloaded set is `yu0.L1(podcastId, true)` (`f54.java:37`) which reads **both** `episodes` and
  `archived_episodes` (`yu0.java:2084-2092`).
* The dedup set is bounded: `f54` constructor sets `this.K0 = z || hashSetL1.size() < 4`
  (`f54.java:41`), and `K()` returns `Y0 > 1` (`f54.java:104-106`).
* **Update-single-episode path** — `g54.M(String)` (`g54.java:52-84`): matches the *stored* episode's
  guid, with a second attempt comparing only the `://…` part, and a third comparing the enclosure
  URL (`this.b.m.equals(ob1Var.m)`).
* **"Already played"** is **not** derived from `guid`; it is `episodes.seen_status` (INTEGER)
  together with `episodes.position_to_resume`. The play-history filter
  `"playbackDate > 0 and position_to_resume <= 10 "` (`or4.java:53`) and the in-progress filter
  `"position_to_resume > 10000 and (duration_ms - position_to_resume) > 5000 "` (`or4.java:66`)
  are the two canonical predicates. `guid` is additionally **not** unique-constrained in the schema
  (`guid TEXT NOT NULL` with no `UNIQUE`) — dedup happens in application code against the hash set,
  not in SQLite.
* The first-guid memo `I0` is used for the "Workaround First Episode refresh"
  (`e4.java:1208-1218`).

### 4.6 Redirects, `new-feed-url`, archived pages, and feed-type sniffing

* **Feed-move handling.** `itunes:new-feed-url` (`e4.java:1915-1919`, `e4.java:1943-1945`, `e4.java:2124-2127`) → `k(url, true)`; `pa:new-feed-url` → `V(url)`; the `<redirect>` element
  → `this.C` + `k(b(), true)`. All are gated by `v54.b(ul3Var)` (is this a developer-registered
  podcast) — otherwise the redirect is *logged and ignored*
  (`"Unknown podcast using private redirection tag..."`, `e4.java:1140-1142`).
* **The RSS parser is also the HTML-diagnoser.** `e4.endElement` state 3 and `w0.C(...)`
  (`w0.java:116-152`) handle a document that starts with `<html>`:
  * `<meta http-equiv="refresh" content="0;url=…">` → `k(url, false)` (follow the redirect);
  * `<link rel="alternate" type="application/rss+xml"|"application/atom+xml" href="…">` →
    `k(href, false)` — **the feed auto-discovery mechanism**;
  * a `<body>` start tag while not in a feed ⇒ `g("html","html")` and the SAX exception that
    aborts the parse (`w0.java:123-126`, `e4.java:2252-2254`).
* **RSS "pages" / archives.** `f0(boolean, boolean)` (`e4.java:2254-2528`) implements pagination of
  long feeds: when the feed ends and `this.w` (an `atom:link rel="next"`/archived-page URL captured
  during the parse) is set, it stores `ul3Var.X = this.w` and increments `ul3Var.Y` (depth), with a
  **max depth of 20** (`e4.java:2505-2516`) and a guard that resets the pagination state if the URL
  is the feed itself or if the page yielded no new episodes.
* **Feed-type sniffing.** `ej2.A(String contentType)` (`ej2.java:59-63`):
  ```java
  return (oy4.s(str, "application/atom+xml") || oy4.s(str, "text/html")
       || oy4.s(str, "application/xml")   || oy4.s(str, "text/xml")) ? false : true;
  ```
  is the "this response is a feed, not a media file" test used by the download/resume paths.
* **Encoding handling.** `e54.w(...)` catches `SAXParseException` with message
  `"at line 1, column 0: not well-formed (invalid token)"` and, when the podcast's `t`
  (`CHARSET` column) contains `-16`, forces `StandardCharsets.UTF_8` and retries once
  (`e54.java:~2260`). Otherwise `en3.x0(ul3Var, inputSource.getEncoding(), false)`
  (`e54.java:2102`) records the detected encoding.

### 4.7 Chapters, transcripts, and other episode side-car data

| Feature | Element | Landing field | Code |
|---|---|---|---|
| **Podlove Simple Chapters (inline)** | `psc:chapters` + `psc:chapter` (`@start`/`@startTime`, `@title`, `@href`, `@image`) | `chapters` table | `e4.java:1387-1468` (`Y`); tag sites at `e4.java:2103, 2226, 2702, 2869, 2994` |
| **Podlove external** | `<link rel="http://podlove.org/simple-chapters" href=…>` | logged only (`"Found a PodLove external chapter information link in podcast …"`) | `e4.java:2736-2745` |
| **Podcasting-2.0 chapters** | `podcast:chapters @url` | `episodes.chapters_url` | `e4.java:2716-2722`, `e4.java:2891-2898`, `e4.java:3016-3023` |
| **Chapter 1.0 object** | `podcast:chapters` element *text* (JSON) | same | `e4.java:2226`-adjacent |
| **Transcripts** | `podcast:transcript @url @type` | `b0` map keyed by lower-cased MIME subtype (`"srt"`, `"vtt"`, …) | `e4.java:1566-1578` (`d0`); tag sites `e4.java:2710, 2879, 3004` |
| **Funding** | `podcast:funding @url` | `ob1` funding | `e4.java:2666, 2721, 2780, 2899, 3024` |
| **Persons** | `podcast:person` (attrs + text) | `persons` / `person_relation` | `e4.java:1934, 2109, 2166, 2230, 2662, 2712, 2776, 2883, 3008` |
| **Locations** | `podcast:location` (+ `geo:lat/long`, `georss:point`) | `locations` / `location_relation` | `e4.java:1936, 2111, 2168, 2232, 2664, 2714, 2778, 2887, 3012` |
| **Soundbites / socialInteract** | `podcast:socialInteract` | parsed via `a0(Attributes)` | `e4.java:2668, 2732, 2783-2784, 2925` |
| **Live items** | `podcast:liveItem` | `m(Attributes)` + `n()` | `e4.java:1837, 2786` |
| **Sources (remote items)** | `podcast:source` | `Z(Attributes)` | `e4.java:1469-1486`; tag sites `e4.java:2730, 2917, 3042` |
| **Donate** | `rawvoice:donate @href`, `anchor:support` | `ob1.z0`, `ob1.y0` | `e4.java:2774` (rawvoice), `e4.java:1928` (anchor:support) |
| **Value/Lightning** | — | — | **not implemented** |

Chapter *consumption*: `x90.e(Context, p32, ob1, boolean)` (`x90.java:593-…`) fetches the chapter
document (`hc1.q0(...)` resolves local-file vs URL), parses it, and if fewer than 2 chapters are
found it falls back to `x90.g(ob1Var, false)` (DB chapters). `x90.n(...)` (`x90.java:5982-6045`)
opens the source: a local file via `FileInputStream`, a content URI via `ContentResolver`, or an HTTP
stream with a **10 s timeout** (`x90.java:6030-6036`). Chapter start times are parsed as
`HH:MM:SS.mmm` (`y85.m`) or plain seconds × 1000 (`e4.java:1425-1437`).

Chapters also drive **muted-chapter** logic (`chapters.isMuted`), **stop-at-chapter-end** sleep timer
(`fl3.java:7812-7834`, `"Sleep timer. Stopping because chapter ended"`), and chapter skip
(`fl3.java:6748-6790` `a0()`).

`alternate_urls` (`episodes.alternate_urls`, `so3.java:16`) is the JSON-serialised
`podcast:alternateEnclosure` list, so a user can manually switch to a different enclosure/bitrate.

---

## 5. Search, discovery and OPML

### 5.1 The directory API

There are **two** search backends, consulted in a fixed order, both under
`defpackage.to3` ("internal search engine" per its log strings).

`to3.n(boolean z)` — `to3.java:352-390` (**iTunes path**, taken when the query is not a URL):

```java
String str3 = "https://itunes.apple.com/search?media=podcast&limit=100&term=" + strReplace;
int iB = r70.b(this.l);                       // storefront id
String str4 = z42.a;
if (!TextUtils.isEmpty(str3) && iB != -1) str3 = str3 + "&g=" + iB;
tb4VarL0 = fo5.l0(str3, null, null, null);
if (tb4VarL0 != null && (jsonReaderB = fo5.B(tb4VarL0)) != null) z42.a(jsonReaderB, str, arrayList);
```

* `term` = `URLEncoder.encode(query, "UTF-8")`, falling back to `query.replace(" ", "+")`.
* Request goes through `fo5.l0` ⇒ **Chrome/131 user-agent** (§3.4).
* `limit=100` is hard-coded and there is **no offset/page parameter** — a single request.

`to3.o(boolean z)` — `to3.java:392-450` (**backend path**, taken when the iTunes call returns
nothing):

```java
arrayList3.add(new qd3(AppLovinEventParameters.SEARCH_QUERY, str2.toLowerCase()));   // "term"
jp3 jp3Var = this.k; if (jp3Var != jp3.b) arrayList3.add(new qd3("type", jp3Var.name()));
if (this.n) arrayList3.add(new qd3("languages", wn5.d(true, false)));
if (!TextUtils.isEmpty(str)) arrayList3.add(new qd3("category", str));
arrayList3.add(new qd3("exactName", z3 ? "1" : "0"));
arrayList3.add(new qd3("dateFilter", (this.o && !z3) ? "1" : "0"));
arrayList3.add(new qd3("explicitFilter", (this.m && !z3) ? "1" : "0"));
strQ = fo5.q("https://addictpodcast.com/ws/php/v4.1/searchpodcast.php", arrayList3);
```

and, when the user is searching **by URL** (`this.p`):
```java
arrayList2.add(new qd3("url", Uri.encode(str2.toLowerCase())));
strQ = fo5.q("https://addictpodcast.com/ws/php/v4.1/search_podcast_by_url.php", arrayList2);
```

Cross-linking is visible at the tail of `o()`: `if (!z && !z2 && arrayList.isEmpty()) n(true);`
(`to3.java:447`) — if the backend search found nothing and we came from the iTunes path, fall back
to the backend. And `n()` mirrors it: `if (!z && arrayList.isEmpty()) o(true);` (`to3.java:369`).

Episode search is separate:
* iTunes: `qh4.h(...)` (`qh4.java:456-490`) — words split on spaces and joined with `+` after
  `Uri.encode`:
  ```java
  String strJ0 = fo5.j0("https://itunes.apple.com/search?entity=podcastEpisode&media=podcast&limit=200&term=" + strEncode, null);
  ```
  with the pre-check `!e(str) && wi0.r(context)` — `qh4.e(str)` rejects queries that are
  themselves URLs or are surrounded by quotes (`qh4.java:410-434`), and `qh4.g(str)`
  (`qh4.java:436-455`) enforces a minimum query length of 2 characters unless the first code point
  is ideographic (`te2`).
* Backend: `wn5.E(...)` (`wn5.java:539-605`) — `search_episodes.php` with
  `term`, `type`, `languages`, `dateFilter`, `explicitFilter`.

Search-result scoring: iTunes results are split into exact-title matches and the rest by
`strH.trim().compareToIgnoreCase(str)` (`to3.java:281-297`), exact matches first
(`arrayList.addAll(0, arrayList3)`). Backend results carry a `score` column
(`episode_search_results.score`, `so3.java:597`).

### 5.2 Top charts / categories / browse

* **Top charts**: `wn5.y(context, isAudio, category, durationFilter, offset)` →
  `get_top_podcasts.php` with `limit=200`, `offset`, `isAudio`, `durationFilter`
  (`-1`/`600`/`1200`/`1800`/`2400` seconds) — `wn5.java:3698-3740`.
* **Categories**: `r70` ("CategoryHelper") holds the local category catalogue
  (`HashMap b, c, d, e, f`, `r70.java:15-22`), with `r70.d()` returning the list and
  `r70.b(String)` mapping a category name to its iTunes storefront id (`r70.java:61-77`).
* **Browse pages** (`NewPodcastsActivity` family): `if5.java:230-300` drives
  `get_new_podcasts.php` / `get_trending_podcasts.php` / `get_random_podcasts.php` /
  `get_top_podcasts.php` with `offset`/`limit=100` paging.
* **Popular**: `get_popular_podcasts.php` (`pf5.java:220`, `limit=6`) and
  `get_popular_episodes.php` (`wn5.java:3565`, `limit=200`, `nbDays`).
* **Search suggestions**: `get_popular_search_terms.php` (`wn5.java:3598`), cached in the
  `popular_search_terms` table (`so3.java:594`).
* **Topics**: `get_topics.php` (`c95.java:62`) → `topics` table.
* **Networks**: `retrievenetworks.php` (`of5.java:61`) → `retrieve_network_podcasts.php`
  (`nf5.java:160`).
* **Curated lists / in-house ads**: `get_iha.php` (`c72.java:129`), `get_APS.php` (`zb.java:132`),
  `get_adCampaign.php`, `get_content_policy_violation.php` (`zb.java:680`),
  `get_blocking_services.php` (`zb.java:60`).

### 5.3 Resolving a bare URL pasted by the user

The entry point is `fo5.e0(String url, boolean z)` (`fo5.java:1188-1386`).

1. **Sanitise**: `d0(str)` (trim/strip control chars) then gate on `X(str)`.
2. **De-special-case known short-link hosts**:
   * `podcasts.google.com/feed/…` → fetch the page, regex `ww1.b` for `;http…;` and take that as
     the feed; if not found, keep the URL with `/episode/…` removed and log
     `"Invalid Google podcast url: not a podcast (…)"` (`fo5.java:1201-1247`).
   * `z42.e(str)` (`z42.java:325-352`): URLs on `itunes.apple.com`/`podcasts.apple.com` → extract
     the numeric id with `Pattern b = /id[\d]+/` or `Pattern c = /id=?([\d]+)/i`, then
     `itunes.apple.com/lookup?id=…` → `feedUrl`; `/itunes-u/` paths →
     `https://itunesu.itunes.apple.com/feed/id<id>`.
3. **Generic HTML-page resolution** via `at4`:
   * `at4.b.matcher(str).find()` detects "this looks like a web page", then
     `at4.a(context, url)` fetches it (**with the `w0` SAX handler**, which reads
     `<link rel="alternate" type="application/rss+xml|application/atom+xml" href>` and
     `<meta http-equiv="refresh">`) and returns the discovered feed URL (`w0.java:137-152`).
   * A **SoundCloud** fallback: if the discovery fails and the host contains `soundcloud.com` and
     there are more than 3 `/`, the last path segment is dropped and discovery retried
     (`fo5.java:1283-1301`).
4. **Final normalisation** via `fo5.f0(url, baseUrl, validate)` — §3.8.

**What happens when a URL returns HTML instead of a feed** is therefore a *two-stage* answer:
the page is fetched and parsed by the same SAX `w0` handler (looking for the alternate link and
meta-refresh), and *only* if that fails does the code fall through to the RSS parser, which
additionally detects `<html>` at the root and throws, aborting the update
(`w0.java:123-126`; `e4.java:2252-2254`).

Other URL-recognition helpers in the same class, all with string literals:
* `fo5.U(String)` (`fo5.java:799-807`): `oy4.s(str,"https://") || oy4.s(str,"http://") || f.matcher(str).find()`
  where `f = ^(https|http|rtsp|ftp|sftp):\/\/` (case-insensitive) — the "is this a URL" test.
* `fo5.b0(String)` (`fo5.java:1043-1083`): Acast `access.acast.com/rss/…` → `feeds.acast.com/public/shows/…`.
* `fo5.c0(String)`: Megaphone URL cleanup.
* `nt5.k(ul3Var, …)` / `nt5.h(url)` (`nt5.java`) host classification (YouTube etc.).
* `ww1` regexes for Google-Podcasts page scraping.

### 5.4 OPML import and export

**Export** — `sv.t(XmlSerializer)` (`sv.java:808-950`). Exact format written:

```xml
<opml version="1.0">
  <head>
    <title>PodcastAddict registration feeds</title>
    <dateCreated>…</dateCreated>
    <dateModified>…</dateModified>
  </head>
  <body>
    <outline text="<podcast name>" type="rss" xmlUrl="<feed url>" htmlUrl="<homepage>" imageUrl="<artwork url>"/>
    …
  </body>
</opml>
```

* Attribute names/order per element: `text`, `type="rss"`, `xmlUrl`, `htmlUrl`, `imageUrl`
  (`sv.java:846-905`). Note it emits **`text`**, not `title` — but the importer accepts both (§below).
* The URL written is `fo5.k.matcher(url).replaceAll("")` — i.e. the tracking-prefix regex
  from §3.8 is applied on export so the OPML is portable.
* Source list: `PodcastAddictApplication.E().f.c2(true)` (`sv.java:840`), skipping
  `ul3Var.v` (virtual podcasts).
* File name (`sv.java:224`):
  ```java
  String str = "PodcastAddict_OPML_export_" + fv0.n(System.currentTimeMillis());
  ```
  and the MIME type is `text/x-opml`, extension `.opml` (`sv.java:230`).
  Storage helper `wx4.e(context, …, ".opml", "text/x-opml")` (`sv.java:230`).

**Import** — `a83` ("OPMLHandler", `a83.java:7-97`), a SAX `DefaultHandler` over the same `u1` base:

```java
if (str2.equalsIgnoreCase("opml")) { this.f = true; return; }
boolean zEqualsIgnoreCase = str2.equalsIgnoreCase("outline");
if (!zEqualsIgnoreCase) { … }
if (!this.f) throw new i92();                       // <outline> before <opml> ⇒ abort
String strA = u1.a(attributes, "type", "");
if (strA.compareToIgnoreCase("rss") == 0 || strA.isEmpty()) {
    String strA2 = u1.a(attributes, "title", "");
    if (TextUtils.isEmpty(strA2)) strA2 = u1.a(attributes, "text", "");     // title OR text
    String strA3 = u1.a(attributes, "xmlUrl", "");
    if (TextUtils.isEmpty(strA3)) strA3 = u1.a(attributes, "url", "");
    …
    String strE0 = strA3.startsWith("{") ? strA3 : fo5.e0(strA3, false);    // full URL normalisation
    ul3 ul3VarC1 = this.d.C1(strE0);            // look up by feed url
    if (ul3VarC1 == null) ul3VarC1 = this.d.C1(strA3);
    if (ul3VarC1 == null) { /* homepage lookup with nt5.d */ }
    …
    uo3 uo3Var = new uo3(null, null, str4, strE0, z, this.a.size());
    if (ul3Var != null) { uo3Var.q = ul3Var.a; uo3Var.d = ul3Var.f; }
    else { String strA4 = u1.a(attributes, "imageUrl", ""); … }
}
```

* Accepts `type="rss"` **or an empty `type`**, and reads `title` **or** `text`,
  `xmlUrl` **or** `url`, plus `imageUrl` (`a83.java:30-96`). This is deliberately permissive so it
  can read OPML from other apps.
* Every URL goes through `fo5.e0(strA3, false)` — the full normaliser of §5.3.
* Entries are collected into `this.a` (an `ArrayList<uo3>`); existing podcasts are matched by feed
  URL or by `homepage` so the import de-duplicates against the library.
* Import is user-facing: `BackupFileBrowserActivity` with `intent2.putExtra("opmlOnly", z)`
  (`sv.java:771,782`), and the result screen is `OPMLImportResultActivity`
  (`sv.java:595-596`). File-format detection for the picker is
  `"xml".equals(strO) || "opml".equals(strO)` (`sv.java:607,660,681`).

**Related formats in the same class** (not OPML but sharing the file picker):
* **Preferences backup/restore**: writes `SharedPreferences` XML by the app's own writer, restores
  it with `Xml.newPullParser()` handling only `string`/`int`/`long`/`boolean`/`set` element types
  and explicitly **excluding `pref_FCMTopken`** (`os1.java:244-330`).
  Backup file name: `com.bambuna.podcastaddict_preferences` + `".xml"`, MIME `text/x-opml`
  (`sv.java:63`); database backup is `podcastAddict.db` / `podcastAddict_corrupted.db`,
  MIME `application/octet-stream` (`yu0.java:565-566`).
* **Android Auto backup** is enabled (`android:fullBackupContent="@xml/auto_backup_scheme"`,
  `resources/AndroidManifest.xml`), with the downloaded-episode folders excluded:
  ```xml
  <full-backup-content>
      <exclude domain="file" path="podcast"/>
      <exclude domain="external" path="podcast"/>
  </full-backup-content>
  ```
  (`resources/res/xml/auto_backup_scheme.xml`).

---

## 6. Audio playback

### 6.1 Engine and construction

**Primary engine: media3 ExoPlayer.** `yc3` ("ExoPlayer") extends the app's abstract player
`c3` (`yc3.java:51`), and drives `bh1` = media3's `ExoPlayerImpl` (every call site is written
`((bh1) ((ExoPlayer) this.a)).method(...)` — i.e. the decompiler prints the *real* interface type
`ExoPlayer` in the cast because `bh1` implements it).

**Fallback engine: `android.media.MediaPlayer`.** Two wrappers exist:
`qu3` ("PrestoMediaPlayer", `qu3.java:13`) and `he` ("AndroidMediaPlayer", `he.java:10`).

Selection — `ks2.a()` (`ks2.java:38-95`):

```java
wi3 wi3VarR = lj3.r(j, str, i, wi3Var2);
int iOrdinal = wi3VarR.ordinal();
boolean z2 = this.b;                                  // customPlayer
if (iOrdinal == 0) {                                  // MEDIAPLAYER
    qu3Var = (z2 && i == 1) ? new qu3() : new he();
} else if (iOrdinal != 1) { qu3Var = null; }
else {                                                // EXOPLAYER
    long jC0 = a.c0(j);
    if (i == 3) z = true;                             // i == 3 ⇒ radio
    else z = !(xm.b(j, i == 1));
    qu3Var = new yc3(this.k, this.d, this.e, jC0, this.g, this.h, this.i, this.j, z);
}
```

and `lj3.r(...)` (`lj3.java:2257-2282`):

```java
if (wi3Var != null) return wi3Var;
int iX = jl0.x(i);
if (iX != 0) {
    if (iX != 1) return iX != 2 ? wi3.a
        : wi3.a(Integer.parseInt(a.Y0().getString("pref_radioPlayerEngine", "1")));
    return a.x0(j, false);                            // video preference
}
wi3 wi3VarX0 = a.x0(j, true);                         // audio preference
if (Build.VERSION.SDK_INT < 27 && wi3VarX0 == wi3.b) {
    String strO = xk1.o(str);
    if (strO.equalsIgnoreCase("flac")) {
        w26.i(a, new Throwable("Workaround for ExoPlayer lack of support for FLAC files:".concat(str)));
        return wi3Var2;                                // force MediaPlayer
    }
}
return wi3VarX0;
```

So: **ExoPlayer by default** (`pref_audioPlayerEngine` / `pref_videoPlayerEngine` /
`pref_radioPlayerEngine`, all defaulting to `"1"` = `EXOPLAYER`, `a.java:1720-1726`,
`a.java:1516-1522`), overridable per podcast (`pref_playerEngine_<podcastId>`,
`a.java:2016-2027`), with a **forced fallback to MediaPlayer for FLAC files on API < 27**.

### 6.2 Player configuration — `yc3.P(Context)` (`yc3.java:330-470`)

Built with a `gg1` (`ExoPlayer.Builder`) whose suppliers are replaced one by one:

| Component | Class | Configuration |
|---|---|---|
| **RenderersFactory** | `new gg1(context, new iy0(obj,(byte)4), new en(context,2))` | media3 default renderers |
| **LoadControl** | `ey0(ge0Var, minBufferMs, minBufferMs, maxBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs, bufferForPlaybackAfterRebufferMs, prioritizeTimeOverSizeThresholds, …, backBufferDurationMs, HashMap)` | see below |
| **MediaSource.Factory** | `new iy0(new vz0(context), (byte) 2)` → `DefaultMediaSourceFactory` | |
| **DataSource.Factory** | `new ky0(new zt7(context, (byte) 10))`, then `ky0Var.b(false)` | `DefaultDataSource.Factory` with `setUserAgent` from `fo5.J(true)` |
| **Looper** | `h65.b` | |
| **TrackSelector** | `new jj4(...)` then `bh1Var.j0()` and the wake-mode/playlist suppression block at `yc3.java:413-428` | |

**Buffer sizes** (`yc3.java:353-390`), with `a0` = isLowRamDevice:

| Field | Radio (`i == 3`) | Normal, `!e` (not local file) | Normal, local file |
|---|---|---|---|
| `bufferForPlaybackMs` | `pref_radioBufferDuration` × 1000 (default **3 000** ms) | 1 000 ms | 1 000 ms |
| `bufferForPlaybackAfterRebufferMs` | same (default **3 000** ms) | 2 000 ms | 2 000 ms |
| `minBufferMs` | 180 000 ms (300 000 on low-RAM) | 180 000 ms | 180 000 ms |
| `maxBufferMs` | 900 000 ms, `z = true` (prioritize time over size) | `max(3 600 000, duration_ms × 1.15)` capped at **10 800 000** | 3 600 000 → capped 10 800 000 |
| `backBufferDurationMs` | `max(50 000, this.z + 500)` | same | same |
| target buffer bytes | — | `nj3.c.a → 144 179 200` (**137.5 MB**) | same |

`pref_radioBufferDuration` is read as a **string** and multiplied by 1000
(`yc3.java:374-375`), default `"3"`.

Additional configuration:
* **Audio offload** (`yc3.java:429-443`): when `this.J` and `i != 2` and
  `pref_audio_offload` is set, installs `la5(j72)` with `b = true`
  (`DefaultAudioSink.Builder.setEnableAudioTrackPlaybackParams(true)`-style) and registers
  `vc3`. Otherwise logs `"Disabling Audio Offload..."`.
* **Handle audio becoming noisy / wake mode**: the `pa0`/`pb` pair toggled by
  `char c = z3 ? 1 : 2` (`yc3.java:444-459`) is media3's
  `setHandleAudioBecomingNoisy` / `setSuppressPlaybackOnUnsuitableOutput`.
* **AudioAttributes** — `c3`'s contract, implemented at `yc3.java:198-219`:
  ```java
  q5 q5Var = new q5();
  q5Var.a = audioAttributes.getContentType();
  q5Var.b = audioAttributes.getFlags();
  q5Var.c = audioAttributes.getUsage();
  ((bh1) ((ExoPlayer) this.a)).R(new pm(q5Var.a, q5Var.b, q5Var.c));
  ```
  and the default attributes are built in the player task (`fl3.java:356`):
  ```java
  this.T0 = new AudioAttributes.Builder().setUsage(1).setContentType(1).build();
  ```
  i.e. `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`.
* **Listeners**: `tc3` (player listener), `uc3` (audio-becoming-noisy / device-listener
  registration), `vc3` (offload listener), `qc3` (`onBufferingUpdate`, `yc3.java:647`),
  `wc3`/`xc3` (`yc3.java:1640-1642`).
* **LoudnessEnhancer**: `yc3.d0()` (`yc3.java:130-175`) queries `AudioEffect.queryEffects()` for
  `EFFECT_TYPE_LOUDNESS_ENHANCER` with `connectMode == "Insert"`; if found, an
  `android.media.audiofx.LoudnessEnhancer` is created and attached (`yc3.java:1446-1468`).

### 6.3 The custom audio processor chain

`oc3` ("PAAudioProcessorChain", `oc3.java:13-77`) builds **four** audio processors and exposes them
as one:

```java
public oc3(Context context) {
    vs4 vs4Var = new vs4();                 // silence-skip timestamp mapper
    this.f = wq4.a;                         // OFF
    g41 g41Var = new g41(); g41Var.i = -1; g41Var.j = 0; g41Var.k = bo.a;
    g41Var.l = true; g41Var.c(ao.d);
    this.a = g41Var;                        // "DownMixAudioProcessor"
    bd3 bd3Var = new bd3();                 // "PAVolumeLevelerAudioProcessor"
    bd3Var.i = false; bd3Var.m = 1.0d; bd3Var.n = false; bd3Var.o = 0.0d; bd3Var.p = 0L;
    bd3Var.q = false; bd3Var.r = 1.0d; bd3Var.s = 0.0d; bd3Var.t = new byte[0];
    this.b = bd3Var;
    ad3 ad3Var = new ad3(context);          // "SkipSilenceAudioProcessor"
    this.c = ad3Var;
    this.e = new bo[]{g41Var, ad3Var, bd3Var, vs4Var};
}
```

| Processor | Purpose |
|---|---|
| `g41` "DownMixAudioProcessor" | channel-count/pcm-format normalisation (`g41.java:10`) |
| `ad3` "SkipSilenceAudioProcessor" | silence removal (`ad3.java:9`) |
| `bd3` "PAVolumeLevelerAudioProcessor" | volume levelling / loudness normalisation (`bd3.java:9`) |
| `vs4` | maps output timestamps back through the silence-skip So the *reported* position stays in sync with the original timeline |

**Speed.** `oc3.d(ih3)` (`oc3.java:60-74`) applies playback speed/volume to `vs4` and
`yc3`'s `h0()` (`yc3.java:1533-1546`) pushes it into ExoPlayer:
```java
ih3 ih3VarU = ((bh1) ((ExoPlayer) this.a)).u();
((bh1) ((ExoPlayer) this.a)).V(new ih3(this.v, ih3VarU.b));   // setPlaybackParameters
…
bh1Var.d0 = z; bh1Var.Q(1, 9, Boolean.valueOf(z));            // setSkipSilenceEnabled
```
`oc3.e(long)` maps media time through `vs4.h(j)`.
Speed range/step is stored on the player task (`fl3`), and `oc3.g(boolean)` returns
`this.f != wq4.a` (i.e. "silence skipping active").

**Skip silence.** `yc3.R(boolean, wq4)` (`yc3.java:473-497`) → `oc3.a(wq4Var)`
(`oc3.java:38-58`), which configures `ad3`:
```java
public final void r(long j, float f, long j2, short s) {
    a92.l(f >= 0.0f && f <= 1.0f);
    this.m = (int) j;      // minimum silence duration (µs)
    this.j = f;            // sample-keep ratio
    this.n = (int) j2;     // max analysis window (µs)
    this.l = (byte) 10;    // PCM sample byte width
    this.k = s;            // amplitude threshold (16-bit)
    this.t = lg5.b; this.w = lg5.b;
}
```

| `wq4` preset | min-silence µs | ratio | window µs | amplitude |
|---|---|---|---|---|
| `OFF` | – | – | – | – |
| `LOW_THRESHOLD` | 416 000 | 0.20 | 2 000 000 | 250 |
| `MEDIUM_THRESHOLD` | 300 000 | 0.20 | 2 000 000 | 250 |
| `HIGH_THRESHOLD` (default) | 100 000 | 0.20 | 1 500 000 | 512 |
| `VERY_HIGH_THRESHOLD` | 100 000 | 0.10 | 1 000 000 | 1024 |
| `EXTREME_THRESHOLD` | 83 000 | 0.075 | 500 000 | 1024 |

(`oc3.java:42-58`; `wq4.java:19-27` for the enum order.)
Skipped time is reported to the telemetry layer as `skipped_silence_ms` (`ad3.java:~163-172`
`lh3.e((j3 - j4) * 1000, "skipped_silence_ms")`) and broadcast as
`com.bambuna.podcastaddict.service.PodcastAddictService.TOTAL_SKIPPED_SILENCE_UPDATE_INTENT`.
`MediaPlayer`'s own `setSkipSilence` is also used on the fallback engine
(`qu3.java:98` `((MediaPlayer) this.a).setSkipSilence(z, wq4Var == null ? 3 : wq4Var.ordinal())`),
and `xq4` (Chromecast path) also calls `setSkipSilence(long, boolean, int)` (`zs4.java:420`).

**Speed change also uses Sonic** (`org.vinuxproject.sonic.Sonic`, imported at `aa5.java:32`,
`zs4.java:21`, `x95.java:15`) — this is the media3 `SonicAudioProcessor`, so time-stretching is
sample-rate/pitch-preserving.

### 6.4 Streaming, cache, stalls and error reporting

**Streaming cache.** A single process-wide media3 `SimpleCache` in `context.getCacheDir()/streaming`:

`lg1.g(Context, long)` — `lg1.java:149-172`:
```java
File file = new File(context.getCacheDir(), "streaming");
d = j;  c = new e23(j);                                  // e23 = LeastRecentlyUsedCacheEvictor
b = new zp4(new File(context.getCacheDir(), "streaming"), c, new iv4(context));
```
and the cache index is its own SQLite DB — `iv4.java:12-14`:
```java
public iv4(Context context) {
    super(context.getApplicationContext(), "exoplayer_internal.db", (SQLiteDatabase.CursorFactory) null, 1);
}
```

**Cache sizing** — `lg1.f(long)` (`lg1.java:140-147`):
```java
long jMax = (long) Math.max(1.34217728E8d, j * 1.2d);   // max(128 MB, 1.2 × file size)
if (jMax <= 805306368) return jMax;                    // 768 MB hard cap
w26.i(a, new Throwable("getCachedFileSize(...MB) - MAX_LOCAL_CACHE_SIZE exceeded!"));
return 805306368L;
```
The cache grows on demand (`lg1.e(...)` grows `c.a` when a larger file arrives) and shrinks by
evicting every key except the current one (`lg1.d(...)`, `lg1.java:77-111`).
`lg1.h(...)` (`lg1.java:174-…`) tests "is this file fully cached" by sorting the cache spans and
checking contiguity; if it is, the player switches to an **offline** data source and logs
`"Playing a fully cached episode OFFLINE!!"` (`yc3.java:811`).

Per-episode streaming state: `yc3.f0(z, z2)` (`yc3.java:560-660`) tracks buffering, reports
`onBufferingUpdate` with `Math.max(player.getBufferedPercentage(), this.F)` (`yc3.java:647`), and
`yc3.E` / `yc3.F` hold "was cached" / "cached percentage".

**Stall / network handling.**
* The download engine detects stalls with `rx0.java:190` (`Range` header +
  `HttpURLConnection`) and retries; the player relies on OkHttp's own retry plus `fo5.w()`.
* `fo5.h0()` (`fo5.java:1505-1556`) is the **walled-garden / captive-portal detector**: a 1 s
  request to `http://clients3.google.com/generate_204` with `Cache-Control: no-cache` and the app
  UA; the HTTP status is cached in `fo5.q`.
* On a total failure the player surfaces a message through `z63`/`z8.h0(...)`
  notifications and `n20.d(context, new Intent("com.bambuna.podcastaddict.service.PodcastAddictService.UPDATE_FAILURE_INTENT"))`
  (`UpdateWorker.java`), and `fl3` logs the failure with
  `"Failed to contact the server at <date>"`.

### 6.5 Audio focus, MediaSession, notification

**Audio focus** — `fl3.a()` / `fl3.a0()` / the request path at `fl3.java:10869-10883`:

```java
AudioFocusRequest.Builder audioAttributes = new AudioFocusRequest.Builder(1).setAudioAttributes(this.T0);
…
AudioFocusRequest audioFocusRequestBuild = audioAttributes
    .setAcceptsDelayedFocusGain(Build.VERSION.SDK_INT >= 35)
    .setWillPauseWhenDucked(a.o() == cn.a)
    .setOnAudioFocusChangeListener(this, h65.c)
    .build();
int iRequestAudioFocus = audioManager.requestAudioFocus(audioFocusRequestBuild);
```
with `1` = `AUDIOFOCUS_GAIN`, and the legacy path
`audioManager.requestAudioFocus(this, 3, 1)` (`fl3.java:10883`, i.e. `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`).
Abandon is `this.B.abandonAudioFocusRequest(audioFocusRequest); this.U0 = null;` (`fl3.java:6668-6674`).

Focus-change handling (`fl3.java:9644-9740`) explicitly branches on
`AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`, `AUDIOFOCUS_LOSS_TRANSIENT`, `AUDIOFOCUS_GAIN` and
`AUDIOFOCUS_LOSS`, including a phone-call hack (`"Phone call is in progress..."` /
`"Phone call in progress Hack worked :)"`, `fl3.java:9689-9691`) and an opt-out
`pref_ignoreAudioFocusRequests` (`fl3.java:7749-7753`).

**MediaSession.** `fl3` maintains a `androidx.media`/`android.support.v4.media.session.MediaSessionCompat`
(import at `fl3.java:25` `MediaSessionCompat$Token`), with metadata updates via
`updateMediaSessionMetaData` (`fl3.java:10124`) and `updateMediaSessionStatus` (`fl3.java:10179`).
The token is handed to:
* **Android Auto** — `AndroidAutoMediaBrowserService` (`com/bambuna/podcastaddict/service/AndroidAutoMediaBrowserService.java:66`,
  `extends MediaBrowserServiceCompat`), with `onGetRoot`/`onLoadChildren` and a caller allowlist
  (`resources/res/xml/allowed_media_browser_callers.xml` pinning SHA-256 certs of Android Auto,
  WearOS, Google Assistant, Samsung CarMode and **Waze**).
* **Chromecast** — `com.google.android.gms.cast.framework.media.MediaIntentReceiver` is declared in
  the manifest (`grep AndroidManifest.xml`), plus `ChromecastMediaButtonReceiver`,
  `ReconnectionService`, and `xq4`/`zs4`/`gs9`-style cast player glue.
* **Wear / bluetooth media buttons** — `PodcastAddictMediaButtonReceiver` and
  `ChromecastMediaButtonReceiver` are both exported (`resources/AndroidManifest.xml`).

**Notification** — `fl3.R(ob1, ul3, boolean, boolean, boolean)` (`fl3.java:5429-…`) builds a
**custom `RemoteViews`** notification on channel `com.bambuna.podcastaddict.CHANNEL_ID_DOWNLOAD_INPROGRESS`
(for downloads) and the player's own channel. It supports a standard and an expanded layout
(`pref_playbackExpandedNotification`, `fl3.java:5498`), with per-button visibility driven by
`pref_playerStandardNotificationPreviousTrack`,
`pref_playerStandardNotificationRewind`,
`pref_playerStandardNotificationFastForward` (default true),
`pref_playerStandardNotificationNextTrack` (`fl3.java:5647-5683`), a progress bar gated by
`pref_disablePlayerNotificationProgressBar` (`fl3.java:404`) and artwork caching via
`u65.a(ob1Var, ul3Var, 8, true, pref_enableNotificationWidgetBitmapCaching)` (`fl3.java:5705`).
There is an explicit keep-alive workaround:
`"Trying to keep the player notification alive but Notification Manager was null. Workaround success => …"`
(`fl3.java:10417`).

### 6.6 Position persistence

`fl3.J1(int position, boolean z, boolean z2)` (`fl3.java:1299-…`, log tag
`saveCurrentPosition(<episode>, <pos>, …, mainThread: …)`) is the single write path. It calls
`h1(ob1, position, speed, …)` (`fl3.java:7758-…`) which dispatches to
`hc1.o2(...)` (`hc1.java:6249-6284`):

```java
ob1Var.A = j;                                   // in-memory
ob1 ob1VarU = U(ob1Var.a);                      // cached copy
if (ob1VarU != null) ob1VarU.A = j;
if (z) ob1Var.G = System.currentTimeMillis();   // playbackDate
else   ob1Var.G = -1;
…
if (zC) h65.e(new wb1(...));                    // hop to a worker thread if on main
else   PodcastAddictApplication.E().f.K3(i, ob1Var.a, j2);
```

`yu0.K3(int, long, long)` (`yu0.java:2061-2068`):
```java
ContentValues cv = new ContentValues(2);
cv.put("position_to_resume", i);
if (j2 > 0) cv.put("playbackDate", j2);
G3(j, cv);                                       // UPDATE episodes SET … WHERE _id = ?
```

**Frequency.** A re-post loop on the player handler: `fl3.java:80`
`public static final short g2 = 20000;` (20 000 ms), used at `fl3.java:8852`
`fl3Var.a.postDelayed(rk3Var2, g2);` where `rk3` (`rk3.java:5-33`) re-queues itself. So the
position is checkpointed **every 20 s while playing**, plus on pause/stop/skip
(`fl3.java:7413`, `fl3.java:7620`, `fl3.java:7707`, `fl3.java:6945`), and the pause path adds a
15 s/45 s delayed re-check (`fl3.java:7427`). Seeks force an immediate write
(`hc1.o2` short-circuits only when `z2 || ob1Var.A != i`).

`episodes.playing_status` (`so3.java:16`) is a separate legacy column; the live state lives in
`fl3.h0` (a `wj3` enum).

---

## 7. Downloads

### 7.1 The engine

`DownloadService` (`com/bambuna/podcastaddict/service/DownloadService.java:24`) extends
`AbstractForegroundService` and delegates immediately to a singleton **`defpackage.a51`
("DownloaderTask", `a51.java:39`)** that is a `z2` (AsyncTask-like) subclass:

```java
public static volatile a51 j;                  // the singleton
public final AtomicBoolean k, l;               // foreground-start bookkeeping
public final void f(Intent intent) {           // onHandleIntent
    if (!"DownloadService.START".equals(action)) { … return; }
    a51 a51Var = j;
    if (a51Var == null) n();                   // start a new session
    else if (a51Var.y || a51Var.z || a51Var.K || a51Var.isCancelled() || !a51.a0) n();
    else a20.E(this.h, "Download already in progress, reusing current session...");
}
```

Notification channel `com.bambuna.podcastaddict.CHANNEL_ID_DOWNLOAD_INPROGRESS`, icon
`f14.ic_download_dark`, notification id **2007** (`DownloadService.java:62-68`),
`FOREGROUND_SERVICE_MEDIA_PLAYBACK` + `FOREGROUND_SERVICE_DATA_SYNC` in the manifest.

**Concurrency** — `a51.java:157-166`:
```java
this.r = new ArrayBlockingQueue(16192);
…
this.v = wi0.m(this.m, "Podcast Addict Download Service lock", false);   // WifiManager.WifiLock
ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(6, 6, 30L, TimeUnit.SECONDS,
        new SynchronousQueue(), new g65(1, "DownloadExecutor"), new ThreadPoolExecutor.AbortPolicy());
this.t = threadPoolExecutor;
threadPoolExecutor.allowCoreThreadTimeOut(true);
```
* core = max = **6 threads**, named `DownloadExecutor`, `SynchronousQueue` (so no queueing — a 7th
  task is rejected by `AbortPolicy`), 30 s keep-alive, core threads may time out.
* The *effective* concurrency is a preference — `a51.q0()` (`a51.java:3576-3591`):
  ```java
  this.E = jl0.d("pref_downloadConcurrentThreadNumber", "1");
  if (PodcastAddictApplication.E().a0) {          // low-RAM device
      this.E = Math.min(i, 2);
      a20.p(str, "updateConcurrentDownloadThreadNumber(" + this.E + ") - LowRamDevice");
  }
  if (this.E > 2 && !xf5.t.get()) { this.E = 2; … }   // forced to 2 during an update
  ```
  **Default 1**; at most 6; at most 2 on low-RAM or while a feed refresh is running.
* A `WifiManager.WifiLock` is held for the whole service lifetime (`a51.java:160`).

**Resumability.** The download uses `HttpURLConnection` (not OkHttp) with a `Range` header:
* `rx0.java:190` `httpURLConnection.setRequestProperty("Range", string)`
* `ai9.java:81`, `sg7.java:70`, `ig7.java:233` — the same pattern in sibling download tasks.
* `ej2.java:593` issues a `Range: bytes=0-1023` (1 KiB) probe to test whether the server supports
  ranges before committing to a resumable download.
* HLS/segment downloads go through `a51.q(x41)` (`a51.java:3574-3578`), which checks
  `Q() || x41Var.i || isCancelled()` and throws `"HLS download cancelled"`.

**Integrity.** After download the file is validated with the platform extractor —
`a51.B(File, String)` (`a51.java:161-191`):
```java
MediaExtractor mediaExtractor2 = new MediaExtractor();
mediaExtractor2.setDataSource(file.getAbsolutePath());
boolean z = C(mediaExtractor2, str) >= 0;      // look for a track whose mime starts with "audio"/"video"
```
`a51.C(...)` (`a51.java:192-206`) iterates `getTrackCount()` and matches
`getTrackFormat(i).getString("mime").startsWith(prefix)`. So a download is only accepted if
`MediaExtractor` can find a matching audio/video track — a real content check, not just a
byte count. (There is no MD5/SHA verification of the *media*; an `md5` column exists only on the
`bitmaps` artwork table, `so3.java:590`.)

**Failure handling** (`a51.java:1340-1380`):
* `a51.F(x41)` (`a51.java:208-227`) computes a **minimum acceptable file size**:
  ```java
  if (str.contains("wimpers.de") || str.contains("podnews.net/")) return 3073L;   // 3 KB
  if (lowerCase.contains(".kar") || lowerCase.contains(".mid")) return 3073L;
  return WebSocketProtocol.PAYLOAD_SHORT_MAX;      // 64 KB − 1
  ```
* Failed downloads are recorded in `episodes.download_error_msg` (`so3.java:16`) and surfaced in
  the sidebar as `<count> / <count> ⚠️` (`or4.java:150-153`).
* The retry loop is guarded by the archive policy: `"SKIP failed download Retry for episode: '…' -
  Archive mode is enabled and the podcast already has N downloaded episodes"` (`a51.java:1365`),
  else `"Retry failed download for episode: '…'"` (`a51.java:1370`).
* Pause/resume: `a51.s(long)` (`a51.java:3597-…`) parks on `Thread.sleep(500)` while
  `a.b2()` (global pause) is true, re-queueing via `publishProgress` and
  `o("consume(episodeId) - download paused")`.
* A download session can be cancelled mid-flight (`a51.q(...)`, `a51.r(boolean)` at
  `a51.java:3592-3596` which clears the `ArrayBlockingQueue` and the in-flight set).

### 7.2 Storage location and file naming

**Storage** — `wx4` ("StorageHelper"):

* Default media root: `new File(externalStorageDirectory, "PodcastAddict")` (`wx4.java:108`),
  i.e. `<external-storage>/PodcastAddict/`.
* App-private fallback: `new File(externalStorageDirectory, "Android/data/" + context.getPackageName() + "/files")`
  (`wx4.java:201`).
* Multiple candidate volumes are enumerated via `getExternalFilesDirs(null)` /
  `getExternalMediaDirs()` (`wx4.java:213`) and picked with `StorageUnitSelectorActivity` /
  `StorageFolderBrowserActivity` (both in the manifest), which can target an SD card or a
  `DocumentsContract` tree URI (`wx4.java:1066-1071`).
* Legacy locations are still probed: `Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)`
  and `DIRECTORY_PODCASTS` (`PodcastAddictApplication.java:1394-1403`).
* The episode file path is resolved by `wx4.C(ob1, ul3, Context, boolean)` (`wx4.java:131-207`),
  which also handles the "extracted artwork" and "virtual podcast" special cases.
* **Matching a download back to its episode** is via `episodes.local_file_name`
  (`so3.java:16`) plus `episodes.download_url`, both indexed
  (`episodesLocalFileName_idx`, `episodesDownloadedUrl_idx`, `so3.java:433`); the download task
  carries `x41 {episode, podcast, url, …}` and updates
  `episodes.downloaded_status_int` / `downloaded_date`.

**File naming** — `a51.K(ul3, ob1, str, str2)` (`a51.java:225-…`), driven by the preference
`pref_customFileNamePattern` (default `"0"`) and the enum `ip0` (`ip0.java:19-31`):

| Index | Enum | Pattern |
|---|---|---|
| 0 | `DEFAULT_NAME` | `<episodeId>_<sanitised title>` |
| 1 | `EPISODETITLE` | `<sanitised title>` |
| 2 | `EPISODETITLE_PUBLICATIONDATE` | `<title>_<date>` |
| 3 | `PUBLICATIONDATE_EPISODETITLE` | `<date>_<title>` |
| 4 | `PODCASTNAME_EPISODETITLE` | `<podcast>_<title>` |
| 5 | `PODCASTNAME_EPISODETITLE_PUBLICATIONDATE` | `<podcast>_<title>_<date>` |
| 6 | `PODCASTNAME_PUBLICATIONDATE_EPISODETITLE` | `<podcast>_<date>_<title>` |

Sanitisation (`xk1.m(String, boolean)`, `xk1.java:450-511`): control/format characters
(`[\p{Cntrl}\p{Cf}]`) → space, trim, then a character whitelist that strips
`\n \r " # % ' * + . / : < > = ? [ \\ ] ^ \` { | } ~` and non-printables; then truncation to
`127 − prefixLength` characters (`xk1.g(int, String)`, `xk1.java:347-355`).
When `pref_showSeasonDetails` is on and the episode has season data, the season name
(`hc1.J0(ob1, podcastName)` / `ob1.R`) is prefixed to the title (`a51.java:~265-275`).

**Wi-Fi-only / size policies.** `wi0.s(context, 1)` / `wi0.k(context, 1)` gate downloads on
network type (`UpdateWorker.java:47`, `mf5.java:87`), and the service checks
`l53.d().e()` before most network work. A per-podcast file-size filter exists
(`pref_podcastFileSizeFilter_<podcastId>`, `a.java` ~line 940) and a global duration filter
(`pref_playlist_filter_duration_shorter_than`, `a.x3(int,long)`).

**Transcoding.** None. There is no MediaCodec/MediaMuxer transcode path for downloads; the
`MediaExtractor` use is validation and artwork/metadata extraction only (§4.7 chapters,
`is_artwork_extracted`). `libsonic.so` + the Sonic processor change *playback* speed, not the
stored file.

### 7.3 Related download-adjacent services

* `AutomaticFullBackupService`, `TrashCleanerService` (a `Worker`, §9), `NewDownloadsActivity`,
  `DownloadManagerActivity` (with `q41`/`s41`/`k41`/`t41`/`r41` view adapters and a
  `DownloadHelper` `h41`).
* `gl4` ("ServiceHelper", `gl4.java`) owns start/stop authorisation for the services, including
  the Android-12 foreground-service-start workaround
  (`"Blocked Download Service: <bool>, Android 12: <bool>, Authorized: <bool>, Time since last Workaround: <n>s"`,
  `or4.java:~157-168`, with a 60 s debounce).

---

## 8. Persistence / schema

### 8.1 Which library

**Raw `android.database.sqlite.SQLiteOpenHelper`. No Room.** Proof:
* `so3.java:14` `public final class so3 extends SQLiteOpenHelper`
* `PodcastAddictApplication.java:3377`
  `yu0.C = new so3(podcastAddictApplication, "podcastAddict.db", null, 135, new xu0());`
  (`xu0` is a `DatabaseErrorHandler`.)
* `so3.java:564-586` `onConfigure` enables WAL unless the app is low-RAM / Amazon / has
  `pref_noConcurrentDBAccessFlag`, and calls `setMaxSqlCacheSize(25)`.
* `yu0` ("DatabaseManager", `yu0.java:40`) is a 6 800-line helper holding every column-name array
  and every CRUD statement. Room is present in the APK only as an unused dependency
  (`androidx.room.*` classes exist but nothing in `defpackage` or `com.bambuna` references
  `androidx.room`).

**DB name** `podcastAddict.db` (`yu0.java:204,566`, `os1.java:430`).
**Version** **135**.
**Other SQLite DBs in the process:** `exoplayer_internal.db` v1 (`iv4.java:14`) for the streaming
cache index, and the `play-services`/`firebase` internal DBs.

### 8.2 Migrations

`so3.onUpgrade(SQLiteDatabase, int oldVersion, int newVersion)` (`so3.java:644-2122`) is a linear
`if (i < N)` ladder from **3 to 135**, each branch typically doing
`ALTER TABLE … ADD COLUMN`, `DROP TABLE` + `CREATE TABLE`, `CREATE INDEX`, or a table rebuild via
`CREATE TABLE x_TEMP AS SELECT * FROM x; DROP TABLE x; CREATE TABLE x (…); INSERT INTO x SELECT … FROM x_TEMP`.
Notable migrations:

* `< 5`: `"UPDATE podcasts SET name = 'Kultur Breakdown' WHERE name = 'Kultur Breakown'"` — a
  hard-coded data fix (`so3.java:~720`).
* `< 105`/`< 107`: drops and recreates the playback-history indexes
  (`episodesPlayBackHistory_idx`, `episodesPlayBackInProgress_idx`, `bitmapLocalFile_idx`, …).
* `< 114`: recreates `episode_search_results`, `topics`.
* `< 129`: `ALTER TABLE episodes ADD COLUMN chapter_origin INTEGER DEFAULT 0`.
* `< 134`: creates `playback_stats_server_ack` / `playback_stats_server_pending`
  (`so3.java:316-321`, re-created defensively in `b0()` with the log tag `[UPD 134]`).
* `< 135`: creates the `episodesDeletePlaylistEntries` trigger (`so3.java:324-330`,
  `[UPD 135]`).
* There is a `requires_bootstrap` column on `sync_restore_state` (`so3.java:271`) used by the
  cross-device sync restore flow.

Many `ALTER TABLE` calls are wrapped in `try { … } catch (Throwable ignored) { }` because
SQLite's `ADD COLUMN` fails if the column already exists — the ladder is written to be **idempotent
and re-runnable**.

### 8.3 The full schema (recovered from `so3.java`'s literal SQL)

Column lists below are verbatim from the `CREATE TABLE` statements. Index names come from the
`CREATE INDEX` statements in the same file.

**`podcasts`** (`so3.java:590`; column array `yu0.E`, `yu0.java:82`)
```
_id INTEGER PK AUTOINCREMENT, name TEXT NOT NULL, team_id INTEGER NOT NULL, category TEXT,
type TEXT NOT NULL, subscribed_status INTEGER NOT NULL, version INTEGER NOT NULL,
homepage TEXT, latest_publication_date INTEGER NOT NULL, feed_url TEXT UNIQUE NOT NULL collate nocase,
favorite INTEGER(1), rating REAL NOT NULL, update_status INTEGER NOT NULL, update_date INTEGER,
thumbnail_id INTEGER, store_url TEXT, last_modified INTEGER NOT NULL, etag TEXT NOT NULL,
initialized_status INTEGER NOT NULL DEFAULT 0, CHARSET TEXT DEFAULT '',
last_update_failure INTEGER NOT NULL DEFAULT 0, flattr TEXT DEFAULT '', is_virtual INTEGER(1) DEFAULT 0,
is_complete INTEGER(1) DEFAULT 1, language TEXT, author TEXT, description TEXT, custom_name TEXT,
priority INTEGER NOT NULL DEFAULT 1, accept_audio INTEGER(1) NOT NULL DEFAULT 1,
accept_video INTEGER(1) NOT NULL DEFAULT 1, accept_text INTEGER(1) NOT NULL DEFAULT 1,
filter_included_keywords TEXT, filter_excluded_keywords TEXT, update_error_message TEXT,
authentication INTEGER(1) DEFAULT 0, login TEXT DEFAULT '', password TEXT DEFAULT '',
automaticRefresh INTEGER(1) DEFAULT 1, folderName TEXT DEFAULT '', private INTEGER(1) DEFAULT 0,
iTunesID TEXT DEFAULT '', subscribers INTEGER DEFAULT -1, averageDuration INTEGER DEFAULT -1,
frequency INTEGER DEFAULT -1, episodesNb INTEGER DEFAULT -1, explicit INTEGER(1) DEFAULT 0,
iTunesType INTEGER DEFAULT 0, reviews INTEGER DEFAULT 0, position INTEGER DEFAULT 0,
server_id INTEGER DEFAULT -1, muted INTEGER(1) DEFAULT 0, hub_url TEXT, topic_url TEXT,
websubSubscribed INTEGER(1) DEFAULT 0, filter_chapter_excluded_keywords TEXT,
last_played_episode_date INTEGER DEFAULT -1, guid TEXT DEFAULT NULL collate nocase,
liveStreamId INTEGER NOT NULL DEFAULT -1, liveStreamStart INTEGER NOT NULL DEFAULT -1,
liveStreamEnd INTEGER NOT NULL DEFAULT -1, liveStreamGuid TEXT DEFAULT NULL collate nocase,
liveStreamStatus INTEGER(1) NOT NULL DEFAULT 2, next_episode_forecast_date INTEGER NOT NULL DEFAULT -1,
UNIQUE(feed_url) ON CONFLICT REPLACE
```
Note: **feed credentials are stored in cleartext** (`authentication`, `login`, `password`) for
HTTP-Basic private feeds.

**`episodes`** (`so3.java:16`; column arrays `yu0.l`/`yu0.F`)
```
_id INTEGER PK AUTOINCREMENT, name TEXT NOT NULL collate nocase, podcast_id INTEGER NOT NULL,
guid TEXT NOT NULL, url TEXT, comments TEXT, publication_date INTEGER NOT NULL, creator TEXT,
categories TEXT, short_description TEXT collate nocase, content TEXT collate nocase,
comment_rss TEXT, download_url TEXT, type TEXT, duration TEXT, size INTEGER NOT NULL,
rating REAL NOT NULL DEFAULT -1, downloaded_status TEXT NOT NULL DEFAULT '-1',
playing_status INTEGER NOT NULL DEFAULT 0, favorite INTEGER(1) NOT NULL,
seen_status INTEGER(1) NOT NULL, downloaded_date INTEGER, new_status INTEGER(1) NOT NULL,
position_to_resume INTEGER, deleted_status INTEGER(1) NOT NULL, local_file_name TEXT,
thumbnail_id INTEGER, comments_last_modified INTEGER NOT NULL, comments_etag TEXT NOT NULL,
duration_ms INTEGER NOT NULL DEFAULT -1, is_virtual INTEGER(1) DEFAULT 0,
is_artwork_extracted INTEGER(1) DEFAULT 0, virtualPodcastName TEXT DEFAULT '',
normalizedType INTEGER DEFAULT 0, playbackDate INTEGER NOT NULL DEFAULT -1,
download_error_msg TEXT, media_extracted_artwork_id INTEGER DEFAULT -1,
chapters_extracted INTEGER(1) DEFAULT 0, server_id INTEGER DEFAULT -1,
automatically_shared INTEGER(1) DEFAULT 0, downloaded_status_int INTEGER(1) NOT NULL DEFAULT 0,
donation_url TEXT, explicit INTEGER(1) DEFAULT 0, iTunesType INTEGER DEFAULT 0,
seasonNb INTEGER DEFAULT -1, episodeNb INTEGER DEFAULT -1, transcript_url TEXT,
chapters_url TEXT, seasonName TEXT, thumbsRating INTEGER(1) NOT NULL DEFAULT 0,
alternate_urls TEXT DEFAULT NULL, rssfeed_duration_ms INTEGER NOT NULL DEFAULT -1,
chapter_origin INTEGER DEFAULT 0
```
`archived_episodes` is the same table plus `archived_date INTEGER NOT NULL DEFAULT 0`
(`so3.java:16`'s `.replace(...)` chain).

`downloaded_status_int` values (from the constants at `yu0.java:~112-117` and `or4.java:53-66`):
0 = not downloaded, 1 = queued/downloading, 2 = downloaded, 3 = download error/failed.
The generated WHERE clauses are `G = "downloaded_status_int = 2 AND downloaded_date >= "`,
`H = "downloaded_status_int = 2 "`, `I = "downloaded_status_int in (0, 3) "`,
`J = "downloaded_status_int = 1 "`, `K = "downloaded_status_int = 3 "` (`yu0.java:86-93`).

**Other tables** (all `CREATE TABLE IF NOT EXISTS` literals in `so3.java`):

| Table | Columns (verbatim, condensed) | Line |
|---|---|---|
| `teams` | `_id, name, home_page, version, banner_id, thumbnail_id, store_url, language DEFAULT 'fr', last_modification_timestamp DEFAULT -1, priority DEFAULT 0` | 590, 662 |
| `bitmaps` | `_id, url UNIQUE, is_asset, is_downloaded, local_file, md5` | 590 |
| `comments` | `_id, episode_id, podcast_id, title, creator, link, pubdate, guid, description, content, new_status` | 591 |
| `supported_languages` | `_id, long_name UNIQUE, short_name UNIQUE` | 591 |
| `ordered_list` | `rank, id, type, filter DEFAULT -1` | 591 |
| `timestamp_list` | `_id, item_id, timestamp, type` | 592 |
| `server_action` | `_id, type, entity, entityId, value, timestamp` | 592 |
| `tags` | `_id, name UNIQUE, refresh_frequency, refresh_time` | 592 |
| `tag_relation` | `_id, tag_id, podcast_id, UNIQUE(tag_id,podcast_id)` | 593 |
| `genres` | `_id, name UNIQUE` | 593 |
| `genre_relation` | `_id, genre_id, radio_id, UNIQUE` | 593 |
| `statistics` | `_id, entityType, entityId, entityStringId, type, value DEFAULT 0, timestamp` | 593 |
| `radio_search_results` | `_id, url, name, country, language, genre, description, thumbnail_id, quality, tuneinID DEFAULT '', serverId, episodeId, countryCode, subscribers, type, UNIQUE(url,type)` | 594 |
| `chapters` | `_id, podcastId, episodeId, start, name, description, url, artworkId, customBookmark DEFAULT 0, diaporamaFlag DEFAULT 0, updateDate DEFAULT 0, isMuted DEFAULT 0, isMusic DEFAULT 0, loopMode DEFAULT 0` | 594 |
| `popular_search_terms` | `_id, languages, keywords` | 594 |
| `content_policy_violation` | `_id, name, url` | 594 |
| `relatedPodcasts` | `_id, relation, url, similar_id, position, score DEFAULT -1` | 595 |
| `reviews` | `_id, serverId, podcast_id, username, date, isMyReview, hasBeenFlagged, rating, comment, UNIQUE(serverId,podcast_id)` | 595 |
| `ad_campaign` | `_id, type, podcast_id, server_id, language, enabled, paid_advertisement, category_id, position, artwork_id, feature_in_popular_search_terms, search_terms` | 595 |
| `curated_lists` | `_id, server_id, type, language, enabled, position, name, description, banner_id, header_id` | 595 |
| `blocking_services` | `_id, type, pattern` | 596 |
| `iha` | `_id, server_id, format, url, enabled, artwork_portrait_id, artwork_landscape_id` | 596 |
| `persons` | `_id, name, picture_id, bio_url, UNIQUE(name,picture_id,bio_url)` | 596 |
| `person_relation` | `_id, person_id, podcast_id, episode_id, role, category` | 596 |
| `locations` | `_id, name, data, UNIQUE(name,data)` | 597 |
| `location_relation` | `_id, location_id, podcast_id, episode_id` | 597 |
| `episode_search_results` | `_id, search_type, category_id, podcastId, podcastServerId, podcastFeedUrl, podcastName, author, language, podcast_thumbnail_id, iTunesId, episodeId, episodeServerId, episodeUrl, episodeName, description, episode_thumbnail_id, publicationDate, duration, type, score, UNIQUE(search_type,category_id,episodeUrl)` | 597 |
| `topics` | `_id, name collate nocase UNIQUE, keywords, nbDays DEFAULT 3, position DEFAULT 1` | 597 |
| `alarms` | `_id, time, enabled DEFAULT 1, frequency DEFAULT 0, name, type, entityId, volume DEFAULT 5` | 598, 157 |
| `social` | `_id, podcastId, episodeId, priority, platform, accountId, url, date DEFAULT -1, json` | 598, 179 |
| `sync_subscription_map` | `podcast_id PK, subscription_id UNIQUE, created_at, updated_at` | 598, 269 |
| `sync_outbox` | `_id, operation_id UNIQUE, entity_type, operation_type, subscription_id, episode_guid, episode_guid_hash, hlc, payload, created_at, sync_field, attempt_count DEFAULT 0, next_attempt_at DEFAULT 0, last_error` | 598, 270 |
| `sync_restore_state` | `_id PK CHECK(_id=1), backup_change_id, backup_created_at, requires_bootstrap` | 599, 271 |
| `sync_runtime_state` | `key PK, value` | 599, 272 |
| `sync_remote_operation` | `operation_id PK, applied_at, server_change_id` | 599, 273 |
| `sync_remote_subscription` | `subscription_id PK, is_subscribed, feed_url, is_private, state_hlc, updated_at` | 599, 274 |
| `sync_tag_map` / `sync_genre_map` / `sync_bookmark_map` | `<local>_id PK, sync_*_id UNIQUE, created_at, updated_at` | 599, 275-277 |
| `playback_stats_day` | `day_key PK, content_played_ms, wall_played_ms, episodes_played_count, playback_speed_saved_ms, skipped_silence_ms, skip_intro_saved_ms, skip_outro_saved_ms, skip_forward_saved_ms, muted_chapter_saved_ms, live_stream_played_ms, updated_at` | 600, 278 |
| `playback_stats_hour` | `day_key, hour_of_day, content_played_ms, wall_played_ms, live_stream_played_ms, updated_at, PK(day_key,hour_of_day)` | 600, 279 |
| `playback_stats_podcast_day` | `podcast_id, day_key, content_played_ms, wall_played_ms, updated_at, PK(podcast_id,day_key)` | 600, 280 |
| `playback_stats_episode_day` | `day_key, episode_id, PK(day_key,episode_id)` | 600, 281 |
| `playback_listening_history` | `_id, day_key, episode_id, podcast_id, podcast_name, episode_name, first_listened_at, last_listened_at, content_played_ms, wall_played_ms, episode_duration_ms, completed, history_quality DEFAULT 'measured', UNIQUE(day_key,episode_id)` | 600, 282 |
| `playback_stats_meta` | `key PK, value` | 601, 283 |
| `playback_stats_sync_pending` | `day_key, hour_of_day, content_played_ms, wall_played_ms, episodes_played_count, playback_speed_saved_ms, skipped_silence_ms, skip_intro_saved_ms, skip_outro_saved_ms, skip_forward_saved_ms, muted_chapter_saved_ms, live_stream_played_ms, PK(day_key,hour_of_day)` | 601, 284 |
| `playback_stats_podcast_sync_pending` | `podcast_id, day_key, hour_of_day, content_played_ms, wall_played_ms, PK(podcast_id,day_key,hour_of_day)` | 601, 306-309 |
| `playback_stats_remote_podcast_pending` | `event_id, subscription_id, day_key, hour_of_day, content_played_ms, wall_played_ms, created_at, PK(event_id,subscription_id)` | 601, 310 |
| `playback_stats_server_ack` | `period_start, content_type, listening_ms, playback_speed_saved_ms, skipped_silence_ms, skip_forward_ms, skip_intro_ms, skip_outro_ms, muted_chapter_ms, PK(period_start,content_type)` | 603, 318 |
| `playback_stats_server_pending` | `period_start, content_type, delivery_id UNIQUE, listening_ms, playback_speed_saved_ms, skipped_silence_ms, skip_forward_ms, skip_intro_ms, skip_outro_ms, muted_chapter_ms, created_at, PK(period_start,content_type)` | 604, 319 |

**Indexes** (`so3.java:433-435`, `A()`, `d()`, `B()`, `M()`, `T()`…):
`podName_idx`, `podUpdateStatus_idx`, `podSubscriptionStatus_idx`, `podSubsPriority_idx`,
`podTeamId_idx`, `podType_idx`, `podLastPubDate_idx`, `podCustomName_idx`, `podPriority_idx`,
`podIsVirtual_idx`, `podUpdateDate_idx`, `podLanguage_idx`, `podThumbnailId_idx`,
`podAutoRefresh_idx`, `podNotifMuted_idx`, `podNextEopisodeDate_idx`, `podRelatedId_idx`,
`epPodIdSeen_idx`, `epDlSeen_idx`, `episodesLocalFileName_idx`, `episodesPlaybackDate_idx`,
`episodesPositionToResume_idx`, `episodesPlayBackInProgress_idx`, `episodesPlayBackHistory_idx`,
`episodesPublicationDate_idx`, `episodesDownloadedUrl_idx`, `episodesInProgress_idx`,
`episodesThumbsRating_idx`, `chaptersCoontent_idx`, `bitmapLocalFile_idx`,
`bitmapIsDownloaded_idx`, `bitmapMd5_idx`, `commentsPodIdNewStatus_idx`, … and a trigger
`episodesDeletePlaylistEntries AFTER DELETE ON episodes BEGIN DELETE FROM ordered_list WHERE type IN (0,1,2) AND id = OLD._id; END`.

**WAL** is on (`so3.java:574-579`), and there is a **query cache of 25 statements**
(`setMaxSqlCacheSize(25)`).

**The queue / "up next" model** is the `ordered_list` table
(`rank INTEGER NOT NULL, id INTEGER NOT NULL, type INTEGER NOT NULL, filter INTEGER DEFAULT -1`),
where `type` distinguishes playlists/episodes/virtual-episodes (0,1,2 per the trigger), and
`timestamp_list` (`item_id, timestamp, type`) backs the "in progress" list. The playlist view model
is `wf3` (`wf3.o`, `wf3.i0(2)`, `wf3.O(int)` counting by type — `fl3.java:7830+`, `or4.java:80-100`).

---

## 9. Scheduling and background sync

### 9.1 Feed refresh — WorkManager + AlarmManager

Everything is a `androidx.work.Worker`. The WorkManager initialiser is the standard
`androidx.startup.InitializationProvider` (`resources/AndroidManifest.xml`).

**Trigger: an `AlarmManager` exact alarm** — `mf5.f(Context, String origin)` ("UpdateHelper",
`mf5.java:208-275`):

```java
boolean zD1 = a.D1(applicationContext);                       // pref_isFeedAutoUpdateEnabled (default true)
AlarmManager alarmManager = (AlarmManager) applicationContext.getSystemService("alarm");
a20.p(str2, "cancelAutoUpdateAlarm()");
if (alarmManager != null && b != null) { alarmManager.cancel(pendingIntent); b = null; }
if (zD1) {
    Intent action = new Intent(applicationContext, PodcastAddictBroadcastReceiver.class)
        .setAction(PodcastAddictBroadcastReceiver.INTENT_FULL_UPDATE);
    action.putExtra("configAutomaticUpdate", true);
    action.putExtra("expedited", false);
    action.putExtra("origin", "setAlarmManager");
    b = PendingIntent.getBroadcast(applicationContext, 3657256, action, 167772160);   // FLAG_IMMUTABLE|UPDATE_CURRENT
    long jMax = Math.max(yb.a(a.e1(), a.X0(), a.D1(PodcastAddictApplication.E())),
                         System.currentTimeMillis() + 60000);
    …
    if (Build.VERSION.SDK_INT >= 31) {
        if (alarmManager.canScheduleExactAlarms()) alarmManager.setExactAndAllowWhileIdle(0, jMax, d());
        else { a20.E(str2, "Workaround missing SCHEDULE_EXACT_ALARM permission...");
               alarmManager.setAndAllowWhileIdle(0, jMax, d()); }
    }
    …
}
```

* **Interval source** — `a.X0()` (`com/bambuna/podcastaddict/helper/a.java:943-949`):
  ```java
  return Long.parseLong(Y0().getString("pref_feedAutoUpdateRefreshRate", "1440"));
  ```
  i.e. **minutes**, default **1440 (24 h)**. The user choices are
  `30, 60, 120, 240, 480, 720, 1440` (`resources/res/values/arrays.xml:1195-1217`).
* **Anchor time** — `a.e1()` (`a.java:1218-1220`) = `pref_specificTimeUpdate`, default
  **25 200 000 ms = 07:00 local**. `yb.a(lastUpdateMillis, intervalMinutes, enabled)`
  (`yb.java:8-38`) computes the next fire time:
  ```java
  long j4 = 60000 * j2;                                    // interval in ms
  long epochMilli = fv0.v(jCurrentTimeMillis, j).toInstant().toEpochMilli();   // today at the anchor
  if (epochMilli > jCurrentTimeMillis) { while (epochMilli - j4 > jCurrentTimeMillis) epochMilli -= j4; }
  while (epochMilli < jCurrentTimeMillis + 60000) epochMilli += j4;
  return epochMilli;
  ```
  So it is an **inexact-in-practice, exact-in-API repeating alarm anchored at 07:00**, re-armed
  after each fire, with a hard floor of "now + 60 s".
* `PendingIntent` request code **3657256**, flags `167772160`
  (`PendingIntent.FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`).
* The permission `SCHEDULE_EXACT_ALARM` is declared with `maxSdkVersion="32"`
  (`resources/AndroidManifest.xml`) and the code degrades to `setAndAllowWhileIdle` when it is
  missing — matching the `"Workaround missing SCHEDULE_EXACT_ALARM permission..."` log.

**The work itself** — `mf5.c(Context, UpdateServiceConfig, …)` (`mf5.java:26-185`):

```java
UpdateServiceConfig { fullUpdate, automaticUpdate, bootUpdate, resumeFailedConnection, silent, force }
```
enqueued as a `OneTimeWorkRequest` for `UpdateWorker` with unique name **`"update-foreground"`**
and `ExistingWorkPolicy.KEEP` (`eg1.a`), `mf5.java:140-185`:
```java
wr5.k(context.getApplicationContext()).f("update-foreground", eg1.a, (vb3) ub3Var.b());
```
Expedition (`is5Var.q = true; is5Var.r = jc3Var.a` = `OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST`)
is applied when the process is in the foreground
(`RunningAppProcessInfo.importance <= 200`) **and** `pref_fgs_quota` is false — an explicit
Android-12 foreground-service-start-restriction workaround (`mf5.java:150-172`).
Input data keys: `configForce`, `configFullUpdate`, `configBootUpdate`, `configAutomaticUpdate`,
`configResumeFailedConnection`, `configSilent`, `expedited`, `origin` (`mf5.java:140-148`).

`UpdateWorker` (`com/bambuna/podcastaddict/service/update/UpdateWorker.java:45`) runs the whole
refresh serially behind the `xf5.t` AtomicBoolean ("update in progress"), logs
`"Update Worker ran for more than 5 minutes…"` after 300 s, and broadcasts
`com.bambuna.podcastaddict.service.MSG_IN_PROGRESS_ACTIONS_STATUS` /
`…PodcastAddictService.UPDATE_FAILURE_INTENT`.

**Constraint check.** Before doing anything, `wi0.s(context, 1)` is consulted
(`UpdateWorker.java:47`) — the app's own "is there a usable connection" helper; if it fails the
update is skipped and, if `fullUpdate` was requested, `mf5.g(context, z)` marks
`pref_lastUpdateFailure` and shows a notification. There is **no `Constraints.Builder` /
`NetworkType` on the update work** — the network gating happens inside `doWork()`, not in
WorkManager. (The only `setRequiredNetworkType` calls in the binary belong to Google's own
transport/JPS scheduler, `js9.java:827-829`.)

**Boot.** `StarterWorker` (`com/bambuna/podcastaddict/service/StarterWorker.java:37`,
`public class StarterWorker extends Worker`) is enqueued with `boot_completed` input data and calls
`b(context, bootCompleted)`:
```java
mf5.f(context, "StarterWorker.onStartServices()");     // re-arm the alarm
xb.k(context);                                        // re-arm alarms
bd0.j(context, false);                                // cleanup
hu.d(context, "Boot or app install/uninstall intent received", false);
if (z && a.Y0().getBoolean("pref_isRefreshOnStartupEnabled", false)) {
    UpdateServiceConfig c = new UpdateServiceConfig(); c.bootUpdate = true;
    mf5.h(context, c, "Boot Completed", false);
}
if (z || wi0.r(context)) gl4.i(context, false);
```

### 9.2 Cross-device sync — a separate WorkManager chain

`h25` (`h25.java:22-110`) drives a `SyncWorker` with unique work names
**`podcast-addict-sync-immediate`** and **`podcast-addict-sync-periodic`**.

| Trigger | API | Delay | Input tag |
|---|---|---|---|
| progress checkpoint | `b(Context, long)` | `j` seconds (`10` → `progress_checkpoint`, else `user_action`) | `sync_trigger=progress_checkpoint` |
| manual playback handoff | `c(Context)` | 2 s | `manual_playback_handoff` |
| membership change | `d(Context)` | none | `membership_change` |
| FCM push | `e(Context)` | none | `fcm_wakeup` |
| periodic | `f(Context, dg1)` | interval = `pref_crossDeviceSyncPeriodicIntervalHours` (**default 6**, `p15.java:40`), flex = `min(60, max(15, hours*15))` minutes | `periodic` |

```java
long millis  = TimeUnit.HOURS.toMillis(jB);            // interval
long millis2 = TimeUnit.MINUTES.toMillis(jMin);        // flex
if (millis  < 900000)  millis  = 900000;               // 15 min floor (WorkManager minimum)
if (millis2 < 300000)  millis2 = 300000;               // 5 min floor
if (millis2 > millis)  millis2 = millis;
…
wr5.k(context.getApplicationContext()).f("podcast-addict-sync-periodic", eg1.b /* REPLACE */, …);
```
Allowed interval values are validated against the array `p15.b` (`p15.java:33-47`).

**The sync endpoints** (`https://addictpodcast.com/ws/php/current/sync/…`) all send
`X-Sync-Protocol: 1` and `Content-Type: application/json; charset=utf-8`:

| Endpoint | M | Body | Response | Code |
|---|---|---|---|---|
| `bootstrap.php` | POST | `{deviceId, backupChangeId, backupCreatedAt?}` | `{status:"DELTA_AVAILABLE", minimumAvailableChangeId, pendingChangeCount, targetChangeId, backupOwnerDeviceId, automaticDriveBackupAllowed}` | `l15.java:60-100` |
| `session.php` | POST | `{protocolVersion:1, playbackStatsPayloadVersions:[2], googleIdToken, playPurchaseToken?, device:{deviceId, displayName, platform:"android", appVersion:"2026.11", appBuild:21774}, realtimeSyncEnabled, fcmToken?, subscriptionBaseline?}` | `{sessionToken, expiresAt, entitlement:{…}, playbackStatsPayloadVersion:2, playbackStatsLastSequence}` | `j25.java:98-190` |
| `pull.php` | POST | `{deviceId, afterChangeId, limit:200, playbackStatsPayloadVersion:2, pendingSubscriptionIds?[], subscriptionBaseline?}` + `Authorization: Bearer <token>` | `{changes[], subscriptionFeeds{}, nextChangeId, hasMore, subscriptionIdentityBaselineAccepted}` | `t15.java:44-140` |
| `push.php` | POST | `{deviceId, afterChangeId, playbackStatsPayloadVersion:2, operations[]}` + `Authorization` | `{results:[{operationId, status, changeId}]}` | `v15.java:20-100` |
| `entitlement.php` | POST | `{googleIdToken}` + `X-Sync-Protocol` | `{entitlement:{state, subscriptionStatus, expiresAt}}` | `hu3.java:36-90` |

Error envelope: `{"error":{"code":<ro3.F-parseable>, "message":…}}`; unknown codes map to `9`
(`t15.java:30-40`). `push.php` rejects batches outside 1..100 operations
(`v15.java:16-19` `x40.i("Invalid sync push batch size.")`).

Change identity is an **HLC (hybrid logical clock)** string per operation
(`operation_id`, `hlc`, `entityType`, `operationType`, `subscriptionId`, `episodeGuid`,
`episodeGuidHash`, `payload`), persisted in `sync_outbox`, `sync_remote_operation`,
`sync_remote_subscription` (§8.3).

### 9.3 Push (FCM), realtime, and the other workers

* **FCM** — `FcmService` (`com/bambuna/podcastaddict/service/FCMService.java`) and `FcmWorker`
  (`:52`, `:451`). The token is stored in `pref_FCMTopken` (note the typo, consistent across the
  codebase) and is explicitly excluded from preference restore
  (`os1.java:~300` `if (!"pref_FCMTopken".equals(attributeValue))`). Realtime sync is gated by
  `pref_crossDeviceSyncRealtimeEnabled` (default `true`, `p15.java:70,85`); `p15.g(...)`
  (`p15.java:84-98`) re-registers the device when realtime is on, the token exists, and the device
  is not yet registered in the `realtime-fcm-registration-v1` datastore.
* **`NotificationWorker`** (`com/bambuna/podcastaddict/service/NotificationWorker.java:19`) —
  new-episode notifications.
* **`TrashCleanerService`** is a `Worker` (`:15`, `:23`) — trash auto-purge, gated by
  `pref_trashPeriod` (`a.x2()`, `a.java:~2035`).
* **`DeviceConnectWorker` / `DeviceDisconnectWorker`** (`:17`, `:25`) — fired on
  Bluetooth/Wi-Fi connect/disconnect to pause/resume playback and re-evaluate download policy.
* **`QuickSettingUpdateService`** — a `TileService` (`BIND_QUICK_SETTINGS_TILE` in the manifest).
* **`CommentService`**, **`SoundService`**, **`AlarmReceiver`** (`com/bambuna/podcastaddict/receiver/AlarmReceiver.java`)
  for the alarm-clock feature (reads `alarms` table, deserialises `pref_snoozedAlarm` with Gson).

### 9.4 WebSub (the app's own push channel)

`bo5` (`bo5.java:111-135`) collects the `hub`/`topic` pairs from `podcasts.hub_url` /
`podcasts.topic_url` and posts them:

```java
jSONObject2.put("id", ul3VarB.a);
jSONObject2.put("hub", ul3VarB.S);
jSONObject2.put("topic", ul3VarB.T);
…
jSONObject.accumulate("websubs", jSONArray);
strK0 = fo5.k0("https://addictpodcast.com/ws/php/v4.1/post_websub_urls.php", jSONObject);
```
i.e. the app does **not** subscribe with the hub directly — it asks its own backend to do it, and
the backend later tells the app (or returns ids in `{ids:[{id:…}]}`) which podcasts were accepted.
`e4.f0(boolean, boolean)` (`e4.java:2254-2280`) decides whether the hub/topic pair changed and calls
`bo5.i/f/l(ul3Var)` accordingly, with the special case
`TextUtils.equals(this.h.S, "PODCAST_ADDICT")` (a meta-topic meaning "use the app's own backend as
the hub").

---

## 10. Subscription and library model

### 10.1 Following a feed

1. The user pastes a URL or picks a search result. URLs are normalised by `fo5.e0` (§5.3).
2. `e54.a(Context, ul3)` (`e54.java:55-…`) sanity-checks the `iTunesID` field
   (`z42.d(...)`, `z42.java:304-320`) and resets it when invalid.
3. `e54.w(Context, ul3, …)` / `e54.updatePodcast(...)` (`e54.java:1960-2415`) fetch the feed:
   * `fo5.w(ka4H, ul3Var.b0, …)` where `ul3Var.b0` is the `m22 {etag, lastModified}` cache entry
     (§3.5) — so subscribing always revalidates;
   * `fo5.a(feedUrl, null)` (`fo5.java:902-905`) decides whether the URL is blocked by the
     blocking-services / blocklist lists (`Q(str, PodcastAddictApplication.E().z(l00.a))`);
   * the body is turned into an `InputSource` by `e54.b(ul3, tb4, …)` and parsed by `h54`
     ("RSSUpdatePodcastHandler") via `e54.i()`'s `XMLReader` (`e54.java:2096-2106`).
4. On success `h54.E()` returns the updated `ul3`, and
   `en3.l0(context, ul3, true, subscribed, "RSSFeedTool final")` +
   `en3.z0(ul3, true, false)` persist it (`e54.java:2131-2134`).
5. **New episodes** are then discovered by a second parse with `f54` ("RSSNewEpisodesHandler"),
   constructor signature `f54(Context, ul3, Set, boolean, boolean, boolean, boolean, boolean)`
   (`f54.java:21-42`). The `Set` argument is an extra guid set to pre-load; the booleans control
   force/initial/silent modes. Its guid set is `yu0.L1(podcastId, true)` which reads
   `episodes` **and** `archived_episodes` (`yu0.java:2084-2092`).
6. Filtering: `f54.F(ob1)` (`f54.java:46-78`) applies the per-podcast `accept_audio`/`accept_video`
   /`accept_text` policy via `hc1.W0(ob1)` / `hc1.p1(ob1)` (`hc1.java:2015-2017`, `6368-6370`)
   and, if the episode is accepted, appends it to `this.a`; once 100 episodes are collected it
   calls `k0()` to flush them to the DB — **so a feed is inserted in batches of 100**
   (`f54.java:66-68`).
7. `podcasts.subscribed_status` (1 = subscribed) and `update_status` (1 = needs update) drive the
   refresh selection: `UpdateWorker` queries
   `"subscribed_status = 1 and update_status = 1 and is_virtual = 1"` (`UpdateWorker.java:~50`).

### 10.2 Enumerating episodes for a feed with many episodes

There is **no "load more" pagination for the episode list** — the whole episode list for a podcast
is a SQLite query over `episodes` with a `LIMIT` only when the user has set
`pref_maxNumberOfEpisodesToDisplay` (`or4.java:~180-190`, `a.java`). The sliding-menu counts come
from `yu0.E(where, …, limit)` overloads (`or4.java:80-100`).

What *is* paginated is:
* the **RSS "pages"** mechanism (`e4.f0`, `ul3Var.X` = next page URL, `ul3Var.Y` = depth, max 20) —
  §4.6 — which walks `atom:link rel="next"`-style archive pages of very long feeds;
* the **archive**: episodes beyond a threshold are moved to `archived_episodes`
  (same schema + `archived_date`), with the archive policy enforced per podcast
  (`pref_maxDownloadedEpisodes…`-style settings, `a51.java:1365`);
* discovery/browse endpoints (`offset`/`limit`, §3.7).

### 10.3 "Played" tracking and the queue

* **Played** = `episodes.seen_status` (INTEGER(1)). It is set when playback passes the completion
  threshold, and cleared by the "mark as unplayed" action (`bd0.java:357`,
  `ln2.java:60`, `sb1.java:63,104`).
* **In-progress** = `position_to_resume > 10000 AND (duration_ms - position_to_resume) > 5000`
  (`or4.java:66`).
* **Played (history)** = `playbackDate > 0 AND position_to_resume <= 10` (`or4.java:53`).
* The playlist/queue is the `ordered_list` table with a `type` discriminator and a `filter`
  column, plus `timestamp_list` for the in-progress ordering. A trigger cascades episode deletion
  into playlist entries (`so3.java:324-330`). The live playlist model is `wf3`
  (`wf3.C()`, `wf3.o`, `wf3.i0(int)`, `wf3.O(int)`), reset by
  `wf3.Y(2, "resetPlaylist()")` (`os1.java:~270`).
* Played episodes are counted per filter and shown in the navigation drawer
  (`or4.a/b/c/d`, `or4.java:10-70`), with the `qr4` enum enumerating
  Downloading / Downloaded / Downloads-in-error / New / Favorites / In-progress / Playback-history /
  Playlist / Bookmarks / All-episodes (`or4.java:5-70`).

### 10.4 Miscellaneous library features with hard evidence

* **Virtual podcasts** (`is_virtual`, `virtualPodcastName`) — playlist-as-a-podcast, including the
  special `podcast_id = -98` synthetic feed (`or4.java:55`, `or4.java:~230`).
* **Live streams** — dedicated `liveStreamId/Start/End/Guid/Status` columns and a `LIVE_STREAM`
  media type (`jp3.i`), with TuneIn/RadioTime as the catalogue (§3.7 C).
* **Tags, genres, persons, locations, topics, curated lists, teams, reviews, bookmarks, chapters,
  social, alarms, statistics, server_action** — all first-class tables (§8.3).
* **Trash** — `deleted_status` + `TrashActivity` + `TrashCleanerService`.
* **Widgets** — 8 app-widget providers plus a `WidgetPlaylistService` (a
  `BIND_REMOTEVIEWS` service) and `ShortcutWidgetProvider` (`resources/AndroidManifest.xml`).

---

## 11. Packaging facts

### 11.1 Identity and SDK

| Fact | Value | Evidence |
|---|---|---|
| Package | `com.bambuna.podcastaddict` | `resources/AndroidManifest.xml:8` |
| versionName | `2026.11` | manifest `android:versionName` |
| versionCode | `21774` | manifest `android:versionCode` |
| minSdk | **26** (Android 8.0) | manifest `android:minSdkVersion` |
| targetSdk | **36** | manifest `android:targetSdkVersion` |
| compileSdk | 37 (codename "17" — a preview) | manifest `android:compileSdkVersion` |
| installLocation | `internalOnly` | manifest |
| AGP | 9.4.0 | `META-INF/com/android/build/gradle/app-metadata.properties` |
| VCS revision | `48f68e01d009e91b4790ef8b78c4185dcd8794cf` | `META-INF/version-control-info.textproto` |
| Build flavour | `PodcastAddictApplication.c3 == 1` ⇒ **Google Play** | `PodcastAddictApplication.java:310`; flavour table at `zh.java:313-321` |
| App component factory | `androidx.core.app.CoreComponentFactory` | manifest |
| `allowBackup` | `true` + `@xml/auto_backup_scheme` | manifest |
| `extractNativeLibs` | `false` | manifest |
| `requestLegacyExternalStorage` | `true` | manifest |
| `supportsRtl` | `false` | manifest |
| `enableOnBackInvokedCallback` | `false` | manifest |
| `hasFragileUserData` | `true` | manifest |
| `allowAudioPlaybackCapture` | `true` | manifest |
| `localeConfig` | `@xml/locales_config` | manifest |
| Network security config | `@xml/network_security_config` — **cleartext permitted globally**, plus user CAs, plus a 127.0.0.1 domain-config pinning `@raw/isrg_root_x1`, `@raw/isrg_root_x2`, `@raw/lets_encrypt_r10` | `resources/res/xml/network_security_config.xml` |

Components: **135 activities, 23 services, 25 receivers, 7 providers** (counted from the manifest),
of which **191 distinct `com.bambuna.*` component names** appear. Notable ones: `AlarmRingingActivity`,
`AutomaticSleepTimerScheduleActivity`, `YearInReviewActivity`, `TranscriptWebViewActivity`,
`TuneInBrowseActivity`, `LiveStreamSearchEngineActivity`, `ITunesCountryPreferencesActivity`,
`OPMLImportResultActivity`, `PrefixActivity`, `PodcastPrivacyActivity`, `PremiumOptionSelectionActivity`,
`TestRSSFeedActivity`, `RegisteredPodcastActivity`, `PodcastReviewActivity`.

Declared `uses-feature` list marks GPS, network location, location, touchscreen, bluetooth,
`leanback`, `screen.portrait` and `hardware.type.pc` as **not required** — i.e. installable on
Android TV, ChromeOS and desktop-PC-mode devices, which is corroborated by
`android:appCategory="audio"` + `@xml/automotive_app_desc` + the Android Auto service.

### 11.2 Signing state

**This APK is unsigned.** There is no `META-INF/*.RSA|DSA|EC|SF` block, no APK Signing Block
(`unzip -l` shows no signature entries; `zipfile` reports an empty archive comment and 3 505
non-signature entries). This is characteristic of **APKPure's repackaging**, which strips the
original signature.

The consequence is directly observable in code. `zh` ("AppSecurityHelper") contains a full
integrity gate:

* **Installer allow/deny list** (`zh.java:33-51`): trusted installers are
  `{"com.android.vending", "com.amazon.venezia"}`; the *deny* set is
  `{"cm.aptoide.pt", "com.aurora.store", "foundation.e.apps", "com.apkpure.aegon",
  "com.aurora.store.nightly", "com.uptodown", "com.uptodown.lite", "com.farsitel.bazaar",
  "com.bambuna.podcastaddict", null, "", "<none>",
  "com.google.android.packageinstaller", "com.samsung.android.scloud"}`.
* **Signing-certificate allowlist** (`zh.java:108-175`, SHA-256 of the certificate):
  ```
  F3ABB9A00BC18D76028C4D5460186743
  3133F3557A78C1200F29136B11E8707C13713381C7D6B75CFBE7BA0D8682809A
  3133F3557A78C1200F29136B11E8707CD8F05D6BD1C1D3445F556787AC742B79
  557CBC799AA0082A57C897E2260F9153FFCEC63ABAECE95AC25FFD096FD0FA75
  DE06ACA145B85F9DC2E1D81AC3CEA8DD8E93663FF08E109811E4D9AAC8922B03
  440708C732FA0B1092D350C3BCB4BC5B8BEFE5BAA70B781CD7D3A477A6FEFE69
  27B274FB92E89EF86FD6F3FE658CEFD026B5BBEF4A83BECA66571ADBE85B4C1AE765D91963E9C1EC91E3789E5C5E3D0B
  ```
* **SHA-1 allowlist** (`zh.java:259-313`):
  ```
  F823D32C9B93B62E42EB72F094F2DBBBF470EA7C     (Amazon build)
  751A69848DC3BC374EE20BF705CAB6FB641E6AC1     (Google Play build)
  61ED377E85D386A8DFEE6B864BD85B0BFAA5AF81     (explicitly-named "invalid" / pirated)
  ```
  with the check being `PodcastAddictApplication.c3 == 2 ? (AZ || G) : (G)` — i.e. a Google-Play
  build only accepts the Google-Play signature, and an Amazon build accepts either.
* The result is cached in `AtomicReference c/d/e` and surfaced as
  `zh.d()`, `zh.e()`, `zh.f()`; `zh.f()` additionally fires a
  `Throwable("IAPHelper.canUse() called before isLegit flag initialized")` if the flag was not
  computed first, and logs a `Donate_App_Session` event.
* The **donate-app** counterpart is verified separately: `yh.b` = installer of
  `com.bambuna.podcastaddictdonate`, checked against the same installer sets (`zh.java:193-235`),
  logging `"Untrusted donate app... '<pkg>'"` and `"Unexpected Donate app installer: '<pkg>'"`.

**IAP is gated on this**: `PodcastAddictApplication`'s constructor picks the billing proxy by
flavour (`PodcastAddictApplication.java:425-437`: `qw1.f()` for the Google flavour,
`kv5.h("Invalid IAP proxy: Huawei")` for Huawei), and `zh.f()` funnels into `qw1.d(...)`
("syncPurchaseToken"). So **anything bought in the Play build is tied to the Play signing
certificate, and an APKPure-sourced unsigned copy is a "pirated" build by the app's own rules.**

### 11.3 Native libraries (`lib/`)

```
lib/arm64-v8a/libapplovin-native-crash-reporter.so    843 152
lib/arm64-v8a/libdatastore_shared_counter.so           10 360
lib/arm64-v8a/libgenius_blur.so                        11 536
lib/arm64-v8a/libsonic.so                              27 888
lib/armeabi-v7a/libapplovin-native-crash-reporter.so  480 384
lib/armeabi-v7a/libdatastore_shared_counter.so          8 432
lib/armeabi-v7a/libgenius_blur.so                       9 368
lib/armeabi-v7a/libsonic.so                            17 112
lib/armeabi/libgenius_blur.so                          17 648
lib/armeabi/libsonic.so                                25 720
lib/x86/libapplovin-native-crash-reporter.so           835 960
lib/x86/libdatastore_shared_counter.so                  7 976
lib/x86_64/libapplovin-native-crash-reporter.so        873 464
lib/x86_64/libdatastore_shared_counter.so               9 424
```

* **ABI filters: `armeabi`, `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`** — a full set, i.e. the
  app still ships the deprecated `armeabi` (ARMv5/6) split.
* `libsonic.so` = the **Sonic** time-stretching library (`org.vinuxproject.sonic.Sonic`) used by
  media3's `SonicAudioProcessor` for playback-speed change (§6.3).
* `libgenius_blur.so` — blur/rendering helper (used by the player artwork/UI).
* `libdatastore_shared_counter.so` — androidx DataStore's shared counter.
* `libapplovin-native-crash-reporter.so` — AppLovin's native crash reporter (ad SDK), by far the
  largest native artefact.

### 11.4 Paid / free split

* Free app: `com.bambuna.podcastaddict`. Paid/"donate" app: **`com.bambuna.podcastaddictdonate`**,
  which is queried in the manifest's `<queries>` block and in `zh.b(context, "com.bambuna.podcastaddictdonate", …)`
  (`zh.java:78-86`), `wn5.B()` reporting `hasDonated` as `0/1/2` (`wn5.java:132-135`:
  `y31.f() ? 2 : (y31.d(context) ? 1 : 0)`).
* The Play-Store listing of the donate app is hard-coded:
  `https://play.google.com/store/apps/details?id=com.bambuna.podcastaddictdonate`
  (`grep` hit, 2 occurrences), and the free app's own Store link is
  `https://play.google.com/store/apps/details?id=com.bambuna.podcastaddict&referrer=PA_APP&utm_source=PA_APP`.
* A `PremiumOptionSelectionActivity` exists in the manifest.
* **Advertising is in the free app and is very large**: AppLovin, Fyber/Inneractive, InMobi,
  PubMatic/OpenWrap, Amazon Publisher Services, Google Mobile Ads. The whole
  `com/{applovin,fyber,inmobi,pubmatic,google}/…` tree is ~2 000+ of the 17 228 classes. Ads are
  embedded in the podcast browse lists (`ad_campaign`, `iha`, `curated_lists`, `get_APS.php`,
  `get_iha.php` — promoted podcasts are indistinguishable from organic results in the API response
  and are only flagged by the `paid_advertisement` column).
* **Analytics/telemetry** is similarly first-class: Firebase Analytics (`FirebaseAnalytics.getInstance`,
  `PodcastAddictApplication.java:1502`), Crashlytics, and the 12 playback-stats tables of §8.3
  feeding `update_playback_statistics.php` / `update_global_stats.php` / `update_podcast_stats.php`.
  `pref_statsEnabled` defaults to false but is force-enabled for alpha/beta/rc builds
  (`PodcastAddictApplication.java:1503-1512`).
* `assets/` contains a UMP consent page (`assets/privacy_consent.html`), DSA compliance page
  (`assets/dsa_page.html`) and a `/legal`-style help page (`assets/help.css`, `assets/setup.css`,
  `assets/tutorial.css`, `assets/android.css`) — GDPR/DSA plumbing.

---

## 12. Undetermined — with the reason for each

Each item below is something I could **not** establish from the decompiled code, and why. I have
deliberately not guessed.

| # | Question | Why undetermined |
|---|---|---|
| U1 | **Exact parameter lists for ~25 of the 55 backend endpoints.** The call sites for `get_podcast_itunes_id.php`, `get_podcasts_by_author.php`, `get_similar_podcasts.php`, `podcasts_suggestions.php`, `get_topics.php`, `retrieve_network_podcasts.php`, `get_iha.php`, `get_adCampaign.php`, `get_episode.php` (the second, url-based overload), `get_adCampaign_stats.php`, `track_3rdparty_search.php`, `get_podcast_stats.php`, `notify_rss_redirect.php`, `flagContent.php`, `post_review.php`, `edit_review.php`, `delete_review.php`, `delete_user_reviews.php`, `flag_review.php`, `submitpodcastregistration.php`, `report_radio_catalog.php`, `get_radio_catalog_fallback.php`, `update_categories_stats.php`, `update_iha_stats.php`, `update_curatedList_stats.php`, `update_podcast_stats.php`, `get_rss_redirections.php` are in methods whose bodies jadx could not decompile (they are merged into giant classes such as `wn5`, `zb`, `c72`, `ej2`, `vc4`, `st4`, `r70`, `xo0`, `e3`, `ne3`, `of5`, `nf5`, `uh4`, `qz4`, `c95`, `al4`). I confirmed the *URL* for each by grepping the literal string, but not the parameter names. Worse: the line numbers I recorded are offset by a few lines from the true call site because they came from a `grep -B/-A` window, so citing "the params are X" would be a guess. | **Obfuscation + R8 class merging + failed decompilation.** |
| U2 | **What the app sends to `update_podcast_stats.php`, `update_global_stats.php`, `update_categories_stats.php`, `update_iha_stats.php`, `update_curatedList_stats.php` and `track_3rdparty_search.php`** (the JSON field names). | Same as U1 — `wn5.L(Context,boolean)` (`wn5.java:805-1344`, 540 lines) is the single method that builds all of these, and its body is only partially decompiled. I could read `update_playback_statistics.php`'s payload exactly (`wn5.java:755-785`) and `ping_user.php`'s (`wn5.java:126-176`) because those methods decompiled cleanly. |
| U3 | **The full retry/backoff schedule and the exact redirect loop count in `fo5.w()`.** The method spans `fo5.java:2444-7665` (5 200 lines) and is only partially decompilable; the attempt-index parameter is reused for several distinct purposes (redirect count, SSL-fallback step, tracker-workaround attempt) and I could not separate the counters. I established: retries at `attempt < 2` after `UnknownHostException`/`ConnectException`, one SSL→proxy fallback on `CertPathValidatorException`, one scheme-rewrite retry via `s0()`, and OkHttp's own `retryOnConnectionFailure` — but **not** the total attempt budget or any sleep between attempts. | Decompilation limits (`jadx` emits `JadxRuntimeException: Can't find top splitter block` for this method). |
| U4 | **Whether a second, distinct OkHttp stack is deliberately used.** I proved the app binds to `com.applovin.shadow.okhttp3` and that the unshaded `okhttp3` package retains only 2 classes, but I could not find where the relocation happened (no Gradle/proguard files in the APK). My "accidental R8 relocation" reading is a hypothesis, not a finding. | R8 shrinking config is not shipped in the APK. |
| U5 | **The audio DSP chain's exact numeric semantics.** `ad3.r(long, float, long, short)` — I established the four fields it writes (`m` = a duration in µs derived from `sampleRate * m / 1e6`, `j` = a ratio, `n` = a duration, `k` = a 16-bit amplitude threshold from the sample-scan loop) and the five preset tuples. I could **not** determine which of `m`/`n` is "minimum silence" vs "maximum analysis window", nor the units of `j`, nor whether `l = 10` is bytes or bits. | The arithmetic in `ad3.n(int)` / `ad3.o(boolean)` (`ad3.java:225-300`) is decompiled but the variable semantics are lost with R8's name erasure, and the correct reading requires the media3 `AudioProcessor` contract plus a runtime experiment. |
| U6 | **Whether chapters are also extracted from ID3 tags / the audio file itself.** `x90.n(...)` opens either a local file, a content URI, or an HTTP stream named by `hc1.q0(...)`; `hc1.q0` resolves to `n0(...).s()` (a local-file path) or `k0(ob1)` (the `chapters_url`/`transcript_url` field). I could not follow `k0()` to see whether it synthesises a path from the downloaded media file (which would imply ID3 extraction via `MediaMetadataRetriever`). | `hc1.java` is 7 000+ lines with heavy class merging; `k0(ob1)` did not decompile. |
| U7 | **How `episodes.chapters_extracted`, `media_extracted_artwork_id` and `chapter_origin` are set.** The column names are certain; the code paths that write them are spread across `hc1` / `x90` / `yu0` and did not decompile. | Same as U6. |
| U8 | **The Chromecast integration.** `xq4` / `zs4` / `dp3` clearly implement a `CastPlayer` (they call `setSkipSilence(long, boolean, int)`, read `"Default Chromecast volume"`, and hold a `MediaSession` token), and `MediaIntentReceiver` / `ReconnectionService` / `ChromecastMediaButtonReceiver` are declared — but **no `CastOptionsProvider` or `ExpandedControllerActivity` is declared in the manifest**, so I cannot tell whether casting is reachable. | The manifest has no cast UI components; the glue class names are R8-erased and I did not locate a `CastOptionsProvider` subclass. |
| U9 | **Whether the app has any websocket of its own.** `fo5.java` imports `RealWebSocket` and `WebSocketProtocol`, but only from the bundled OkHttp; I found no `newWebSocket` call in `defpackage` or `com/bambuna`. The cross-device "realtime" sync is FCM-data-message based (`FcmWorker`, `sync_trigger=fcm_wakeup`), not a websocket. I state this as "no app-owned websocket found", which is weaker than a proof of absence because a call could sit in a method that failed to decompile. | 1% of method bodies are unavailable. |
| U10 | **The exact free/paid feature boundary.** I can see `com.bambuna.podcastaddictdonate` exists, that `PremiumOptionSelectionActivity` exists, and that `zh.f()` gates IAP on the signature check — but I did not find a single feature flag of the form `if (isDonate) {…}`. `y31.f()` / `y31.d(context)` (`wn5.java:132`) are the "has donated" predicates but their implementations did not decompile. | The `y31` / `qw1` / `ld` classes (billing) are heavily obfuscated and partially unavailable. |
| U11 | **The derivation of the episode "duration_ms" vs "rssfeed_duration_ms" and the `hc1.d0(bitrate, length, duration)` bitrate formula.** `hc1.d0(...)` is called from `e4.I` (`e4.java:~232` of the simple rendering) but its body is unavailable. | Same as U6. |
| U12 | **Server behaviour.** Everything in §3.7 is what the app *sends*; the response schemas, rate limits, error codes and required-vs-optional semantics of `addictpodcast.com/ws/php/v4.1/*` are server-side and were not observed. I only parsed the client's expectations (field names it reads back). | Runtime; no server source. |
| U13 | **Why the LruCache at `fo5.java:85` exists and what populates it.** `q0(String)` (`fo5.java:2039`) is called from `p()` and `l0()`, and `fo5.C` is a `ConcurrentHashMap` — I could not find the reader, so its purpose (request de-dup? telemetry?) is inferred from the call sites only. | Reader method not located. |
| U14 | **The `ey6.java:103` base64 blob.** It sits next to the same API key GUID and is ~10 kB of base64 in a `String` constant, used by a call I could not resolve. | Did not decompile / not traced. |
| U15 | **`pref_noConcurrentDBAccessFlag`, `pref_fgs_quota`, `pref_concurrentDBAccess`-style feature flags** — read at `so3.java:566` and `mf5.java:153,164`; no UI could be found that sets them, so they look like remote/debug toggles. | No writer located. |

### Explicit "not present" claims (verified by grep over all 17 228 files)

* `podcastindex.org`, `gpodder.net`, `listennotes`, `fyyd` — no occurrences.
* `podcast:value`, `valueBlock`, `lightning`, `LNURL`, `boostagram` — no occurrences.
* `podcast:locked` — no occurrences (only `acast:locked-item`).
* `googleplay:` namespace elements — no occurrences.
* `sy:updatePeriod`, `sy:updateFrequency` — no occurrences.
* `Retrofit` — no occurrences.
* `podlove` external chapter *parsing* — the `<link rel=...>` form is detected and logged only;
  only the inline `psc:` form and the `podcast:chapters` JSON URL are actually parsed.
