# Podcast Addict 2026.11 — Security Assessment

**Target.** `com.bambuna.podcastaddict` (Podcast Addict 2026.11, versionCode 21774, targetSdk 36,
minSdk 26) — decompiled APK at `/private/var/folders/0x/tq2_tw6d7_lckb2kqwt23klc0000gn/T/opencode/podcast-addict-dec/`
(sources `sources/`, decoded manifest/resources `resources/`), and the first-party backend it
talks to, `https://addictpodcast.com/ws/php/v4.1/`.

**Method.** Static analysis only. One proportionate live probe was made where a finding could not
otherwise be confirmed (§B, F-2). Nothing was written, deleted or modified. No enumeration of or
access to another user's data. No injection payload was executed against the live backend — where a
sink needs live confirmation the finding is marked **THEORETICAL** and the cost of confirmation is
stated.

**Disclaimer.** This is responsible-disclosure work. Every finding carries a severity, evidence
quoted verbatim from the decompiled tree or a raw probe response, impact, and remediation the vendor
could act on.

All quoted line numbers refer to files under
`/private/var/folders/0x/tq2_tw6d7_lckb2kqwt23klc0000gn/T/opencode/podcast-addict-dec/sources/`
unless the path says otherwise (`resources/` for the decoded manifest / `res/`).

---

## 0. Findings table

Ordered by severity. "Live" is the risk against the currently deployed backend; "code" is the risk in
the shipped APK.

| # | Title | Severity | Status | Live? |
|---|---|---|---|---|
| D-1 | `ArtworkFileProvider` exported, unauthenticated, `new File(parent, uri.getPath())` — arbitrary read/write/delete | **Critical** | **CONFIRMED** (code) | yes |
| A-1 | No XXE / entity-expansion hardening on the SAX parser used for every feed | **High** | **CONFIRMED** (code) | yes |
| A-2 | Two independent untrusted-XML sinks, incl. a user-picked OPML file and a resolver-less HTML parse | **High** | **CONFIRMED** (code) | yes |
| C-1 | One shared API key is the only auth on 55 endpoints; `reset_user_registrations.php` takes only a non-secret `userUUID` | **High** | **CONFIRMED** (code + probe); destructive half **THEORETICAL** | yes |
| B-1 | `rss_proxy.php` / `search_podcast_by_url.php` take a client-supplied URL and fetch it server-side | **High** | **CONFIRMED** (code); **live probe: proxy returns 401 "missing token" → not reachable as shipped** | no (latent) |
| A-3 | The entity resolver stubs exactly one DTD and otherwise delegates to the platform | **Medium** | **CONFIRMED** (code) | yes |
| B-2 | The client's "validation" of the proxy target is not an SSRF defence; the retry rewrites the URL | **Medium** | **CONFIRMED** (code) | yes |
| E-2 | `TranscriptWebViewActivity`: remote feed-controlled URL, JS on, third-party cookies on, no bridge | **Medium** | **CONFIRMED** (code) | yes |
| H-1 | `network_security_config.xml` permits cleartext globally **and** trusts the user CA store | **Medium** | **CONFIRMED** (decoded resource) | yes |
| F-1 | `fo5.f0()` rewrites rather than rejects; scheme-less URLs give up to `http://`; not applied to the backend URL endpoints | **Medium** | **CONFIRMED** (code) | yes |
| H-5 | `allowBackup="true"` with only the podcast media excluded | **Low-Medium** | **CONFIRMED** (decoded manifest + resource) | yes |
| C-2 | `X-App-Installer` from PackageManager, interpolated into request URLs and client-visible in headers | **Low** | **CONFIRMED** (code) | spoofable |
| E-1 | The one JS-bridge WebView loads remote third-party content | **Low-Medium** (topology) | **CONFIRMED** (code) | mitigated (not exported) |
| D-2 | 27 exported activities / 18 receivers, incl. 40 app-specific broadcast actions | **Informational** | **CONFIRMED** (manifest) | triaged |
| G | Client-side SQL injection | **none** | **CONFIRMED clean** | — |
| E-4 | `PodcastPrivacyActivity` is not a WebView | — | correction | — |
| H-2/H-3/H-4 | randomness clean; no passcode feature exists; media-browser allowlist enforced | — | **CONFIRMED clean** | — |

**Top three by priority:** D-1 (Critical), A-1/A-2 (High), C-1 (High). B-1 is High by code but is
neutralised in practice by the live 401 — fix it as defence-in-depth, not as an active incident.

---

## A. XML parsing of untrusted feeds — the SAX stack

### A-1. No XXE or entity-expansion hardening anywhere in the app's SAX configuration

**Severity: High** — every feed the app ever parses passes through a `SAXParserFactory` with zero
security features set, on a parser whose default accepts `DOCTYPE`.

**CONFIRMED** (code). There is exactly one factory in the app, constructed with no configuration at
all:

`sources/defpackage/e54.java:51`
```java
    public static final SAXParserFactory b = SAXParserFactory.newInstance();
```

There is exactly one factory method, and it sets only a content handler's dependency:

`sources/defpackage/e54.java:1512-1516`
```java
    public static XMLReader i() throws SAXException {
        XMLReader xMLReader = b.newSAXParser().getXMLReader();
        xMLReader.setEntityResolver(c);
        return xMLReader;
    }
```

A grep for every hardening feature JAXP offers returns **nothing** for the SAX path:

```
$ grep -rn "setFeature" sources/defpackage/
defpackage/fr2.java:5122:            mediaFormat.setFeatureEnabled("tunneled-playback", z);
defpackage/sv.java:249:                        xmlSerializerNewFeature.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
defpackage/ai4.java:87:                xml.setFeature("http://xmlpull.org/v1/doc/features.html#process-namespaces", true);
defpackage/ai4.java:88:                xml.setFeature("http://xmlpull.org/v1/doc/features.html#report-namespace-prefixes", true);
```

Not one of the following is set anywhere in `defpackage/`:
- `http://apache.org/xml/features/disallow-doctype-decl`
- `http://xml.org/sax/features/external-general-entities`
- `http://xml.org/sax/features/external-parameter-entities`
- `http://javax.xml.XMLConstants/feature/secure-processing` (`FEATURE_SECURE_PROCESSING`)
- the SAX/StAX `entity-expansion-limit` (`org.apache.xerces.impl.Constants.ENTITY_EXPANSION_LIMIT`)

`grep -rn "disallow-doctype\|FEATURE_SECURE_PROCESSING\|external-general\|external-parameter\|entity-expansion" sources/defpackage/` returns no hits at all.

The factory is also never made namespace-aware (the internals report establishes this from the
handler bodies, which compare `qName` with `equalsIgnoreCase`), which is consistent with, but not
itself a security defect.

**Impact.** A feed containing a `DOCTYPE` with an internal subset defining
`<!ENTITY a "…">`/`<!ENTITY % …>` is accepted and expanded. Because an `https://` podcast feed is
attacker-controlled and unauthenticated, a hostile publisher controls the entire document handed to
this parser. Concretely:
- **Entity-expansion DoS / CPU & memory blow-up** (billion-laughs class). A feed can nest entity
  definitions to expand to gigabytes from a few kB, on a background thread with no user interaction
  (refresh is alarm-driven, see internals §9).
- **External-entity read** if the platform parser resolves external entities. This is the
  higher-impact variant but its reachability depends on the device's `SAXParserFactory`
  implementation; see A-3 for why it cannot be confirmed from the APK alone.

**Remediation (client-side).** At `e54.java:51`, immediately after construction:

```java
b.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
b.setFeature("http://xml.org/sax/features/external-general-entities", false);
b.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
b.setFEATURE_SECURE_PROCESSING -> b.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
b.setFeature("http://apache.org/xml/properties/security-manager", new SecurityManager() /* or setEntityExpansionLimit */);
```

`disallow-doctype-decl = true` is the single most valuable line: real-world RSS/Atom feeds do not
use a DOCTYPE, and the one XHTML DOCTYPE the app currently *needs* is worked around by its own
resolver (A-3), so refusing DOCTYPEs outright costs the app nothing.

### A-2. Two independent untrusted-XML sinks, one of them a user-picked file

**Severity: High** — the parser configuration is shared by a remote feed path (no interaction
beyond subscribing/refresh) and by a local file-import path (attacker only needs the user to open a
`.opml` file).

**CONFIRMED** (code). The parse sites, with the trust level of each input:

1. **RSS feed refresh — untrusted remote, unattended.** `e54.w(Context, ul3, boolean, boolean, boolean)`
   at `sources/defpackage/e54.java:1960`, parsing at `:2102`, `:2313`, `:2363` via `i()` + handler
   `h54`. Triggered by alarm/WorkManager refresh (internals §9.1), so it runs without the user
   opening the app.
2. **Episode comments — untrusted remote.** `e54.d(ob1, boolean)` at `sources/defpackage/e54.java:784`,
   parsing at `:819` via `i()` + handler `uf0`, on a body fetched from the feed's `comments` URL.
3. **OPML import — untrusted local file.** `e54.k(InputStream)` at `sources/defpackage/e54.java:1541`:

   `sources/defpackage/e54.java:1541-1556`
   ```java
   public static ArrayList k(InputStream inputStream) throws i92, IOException {
       String strConcat;
       String str = a;
       ArrayList arrayList = new ArrayList(10);
       a83 a83Var = new a83();
       a83Var.f = false;
       try {
           InputSource inputSource = new InputSource(inputStream);
           XMLReader xMLReaderI = i();
           xMLReaderI.setContentHandler(a83Var);
           try {
               xMLReaderI.parse(inputSource);
   ```

   And the stream comes from a user-picked URI — a file picker or an `http(s)` URL:

   `sources/defpackage/d83.java:82-84`
   ```java
                   this.l = new zc3(context, uri.toString(), true);
                   bufferedInputStream = new BufferedInputStream(this.l.h());
   ```
   `sources/defpackage/d83.java:87`
   ```java
                   ArrayList arrayListK = e54.k(bufferedInputStream);
   ```
   plus `cw2.java:292` (onboarding OPML) and `j.java:543` (same helper, second call site). The
   `zc3` wrapper resolves the URI, so `content://` and `file://` sources both reach the parser.

4. **HTML probe / meta-refresh — untrusted remote, and *without* the entity resolver.**
   `e54.o(String, yo, StringBuilder, boolean)` at `sources/defpackage/e54.java:1621`, parsing at
   `:1649-1651`:

   `sources/defpackage/e54.java:1649-1651`
   ```java
                                       XMLReader xMLReader = b.newSAXParser().getXMLReader();
                                       xMLReader.setContentHandler(rg5Var);
                                       xMLReader.parse(wd0VarC);
   ```

   Note this site constructs the reader **directly from the factory and never calls
   `setEntityResolver`** — so on the one path that parses arbitrary HTML fetched from an
   attacker-chosen URL, the app installs *no* resolver at all.

**Impact.** Three distinct trigger models an attacker can choose between:
- host a hostile feed and get the victim to subscribe (or wait for an existing subscription to
  redirect to it);
- send a `.opml` attachment — no feed hosting needed, and the user's own file becomes the XML source;
- rely on #4, the HTML probe, which parses attacker-controlled bytes with no resolver in the way.

**Remediation (client-side).**
1. Apply A-1's hardening to the single shared factory; it fixes all four sites at once.
2. For OPML import, additionally validate the picketed file before parsing (a real OPML root
   element check on a size-capped stream is enough, since the parse is already size-limited by the
   character cap in `u1.c()` at `u1.java:42-49`).
3. Make the `e54.java:1649` site go through `i()` so no parse path is resolver-less.
4. Bound the response size before handing the `InputStream` to the parser — `fo5` already has
   size helpers for this.

### A-3. The entity resolver stubs exactly one DTD and otherwise trusts the platform

**Severity: Medium** — a defence that exists only by accident and only for one document type.

**CONFIRMED** (code). The only entity handling in the app:

`sources/defpackage/hp0.java` (whole file)
```java
public final class hp0 implements EntityResolver {
    @Override // org.xml.sax.EntityResolver
    public final InputSource resolveEntity(String str, String str2) {
        if (str2.contains("xhtml1-transitional.dtd")) {
            return new InputSource(new StringReader("<!ENTITY bull \"&#8226;\">"));
        }
        return null;
    }
}
```

and `sources/defpackage/e54.java:52`
```java
    public static final hp0 c = new hp0();
```

**Impact.** The resolver is a targeted workaround for feeds that declare the XHTML 1.0 transitional
DTD (very common in WordPress-generated feeds): instead of fetching that DTD, it supplies a stub.
It is not a general defence:
- It returns `null` for every other system id, which *delegates the decision back to the platform
  parser*. What the platform does with a `null` return for an external entity is
  implementation-defined and **cannot be determined from the APK**, because the
  `SAXParserFactory` implementation lives in the device's `libcore`/`org.apache.harmony` classes, not
  in the app's dex. That is why the external-entity *file read* variant of A-1 is not asserted
  here as confirmed.
- It does not touch the internal subset: `<!ENTITY …>` definitions inside the DOCTYPE are unaffected
  by any resolver, so the entity-expansion exposure of A-1 stands regardless.
- It is bypassed entirely on the `e54.java:1649` site, which sets no resolver.

**Remediation (client-side).** Delete `hp0` and set `disallow-doctype-decl` (A-1). If the app must
keep parsing feeds that carry an XHTML DOCTYPE, keep the resolver but make the factory refuse
DOCTYPEs at the same time so the two mechanisms cannot disagree.

---

## B. Backend SSRF — `rss_proxy.php` and `search_podcast_by_url.php`

### B-1. Both endpoints take a client-supplied `url` and are meant to fetch it server-side

**Severity: High** (as a code-level weakness) — but see the live-probe result below: the proxy is
currently **not reachable through the shipped client**, which lowers the *live* risk to
"latent, will bite the moment the token requirement changes or is bypassed".

**CONFIRMED** (code) that the client performs no validation; **live probe** for reachability.

The proxy helper, in full — the `url` parameter is the caller's string, unmodified:

`sources/defpackage/wn5.java:3665-3673`
```java
    public static tb4 v(PodcastAddictApplication podcastAddictApplication, String str) {
        if (podcastAddictApplication == null || TextUtils.isEmpty(str)) {
            return null;
        }
        ArrayList arrayList = new ArrayList(2);
        arrayList.add(new qd3(POBCoreNativeConstants.NATIVE_LINK_URL, str));
        arrayList.add(new qd3("ts", String.valueOf(System.currentTimeMillis())));
        return fo5.p("https://addictpodcast.com/ws/php/v4.1/rss_proxy.php", arrayList, false);
    }
```

The URL that gets here is the *original* request URI, i.e. whatever the app was about to fetch:

`sources/defpackage/fo5.java:4707-4710`
```java
                                if (!z6 && i3 == 0 && (th instanceof SSLHandshakeException) && y85.n(th).contains("CertPathValidatorException")) {
                                    try {
                                        a20.E(str9, "Certificate issue, trying to use proxy");
                                        return wn5.v(PodcastAddictApplication.E(), uriI.toString());
```

So the fallback rewrites a *podcast feed URL* into a server-side fetch of that same URL. That is the
design. The second sink is the search endpoint:

`sources/defpackage/to3.java:402-405`
```java
                ArrayList arrayList2 = new ArrayList(1);
                arrayList2.add(new qd3(POBCoreNativeConstants.NATIVE_LINK_URL, Uri.encode(str2.toLowerCase())));
                String str4 = wn5.a;
                strQ = fo5.q("https://addictpodcast.com/ws/php/v4.1/search_podcast_by_url.php", arrayList2);
```

And neither `fo5.p(String, List, boolean)` nor `fo5.q(String, ArrayList)` does any URL inspection —
the only work they do with the parameter list is percent-encode it into the query string
(`fo5.java:1988-1991`, via `es9.d(0, 0, str4, HttpUrl.QUERY_COMPONENT_ENCODE_SET, 91)`) and attach
the shared key (`fo5.java:2003`) and the installer (`fo5.java:2009`). There is no scheme check, no
host check, no private-address check, no length check.

The only private-address awareness in the whole client is in the **error-message** path, not in a
decision:

`sources/defpackage/fo5.java:3670-3681`
```java
                                                if (tb4VarW3 == null && sb != null && TextUtils.isEmpty(sb.toString())) {
                                                    host = uriI.getHost();
                                                    String str110 = s9.a;
                                                    if (TextUtils.isEmpty(host)) {
                                                        zB = false;
                                                    } else {
                                                        zB = s9.b(InetAddress.getAllByName(host));
                                                    }
                                                    if (zB) {
                                                        a20.i(str9, str6 + uriI.getHost() + "   ***   " + uriI.toURL());
                                                        sb.append(PodcastAddictApplication.E().getString(n34.adBlockerError));
                                                    }
                                                }
```

`s9.b(InetAddress[])` (`sources/defpackage/s9.java:38-50`) returns true only when *every* resolved
address fails `g(inetAddress)` — and `s9` is `AdBlockHelper` (`s9.java:19`), so `g()` is the
ad-blocker predicate. This code classifies a *loopback/private* host as "the user has an ad blocker
running" and appends a UI string. It never blocks the request.

**Live probe (proportionate, non-destructive; raw responses).** `rss_proxy.php` with the app's real
User-Agent and the shared key:

```
$ curl -s -w '\nHTTP %{http_code}\n' --max-time 25 -A 'PodcastAddict/v5 (+https://podcastaddict.com/; Android podcast app)' \
    -H 'X-App-Key: d2fad335-a44d-4aeb-9e5b-67b19a15572c' -H 'X-App-Installer: com.android.vending' \
    --get 'https://addictpodcast.com/ws/php/v4.1/rss_proxy.php' \
    --data-urlencode 'url=http://nonexistent-a1b2c3d4.invalid/probe.xml' --data-urlencode 'ts=...'

HTTP 401
missing token
```

and, for comparison, the same request against the other backend endpoints:

```
search_podcast_by_url.php   HTTP 200  {}            (before, wrong UA: HTTP 403)
searchpodcast.php           HTTP 400  Missing parameters
get_podcast.php             HTTP 200  null          (id=-1)
get_podcast_server_id.php   HTTP 200  -1
ping_user.php               HTTP 200  Missing parameters
reset_user_registrations.php HTTP 200  Missing parameters
```

**What this establishes.**
- `rss_proxy.php` **requires a token that the shipped APK never sends.** The app sets exactly two
  custom headers, both quoted above at `fo5.java:2003` and `fo5.java:2009`; a grep for any other
  `X-…` header in `defpackage/` returns nothing:
  ```
  $ grep -rn '"Authorization"\|Bearer \|"X-App-Token"\|"X-Token"\|X-Auth' sources/defpackage/
  fo5.java:2003  ka4VarH.a("X-App-Key", "d2fad335-a44d-4aeb-9e5b-67b19a15572c");
  fo5.java:2009  ka4VarH.a("X-App-Installer", str6);
  ```
  Therefore the SSL-handshake fallback at `fo5.java:4707-4710` **silently fails on every device**,
  and the SSRF primitive behind it is unreachable through the current client. The vendor has
  evidently hardened this endpoint since 2026.11 shipped — but the client code still hands an
  arbitrary URL to it, so the exposure returns the moment the token requirement is relaxed,
  mis-configured per-route, or fronted by a different origin.
- `search_podcast_by_url.php` accepted an arbitrary, unresolvable, external host (`*.invalid`,
  guaranteed NXDOMAIN by RFC 6761) and answered `200 {}` — i.e. it did **not** reject the URL on
  scheme/host grounds and reached application logic. A real public feed URL produced the same
  `{}`, so I cannot distinguish server-side fetching from a stored-index lookup from the outside,
  and I am not going to probe internal addresses to find out (that would be the actual attack).
  **The URL is therefore passed to the backend with no client-side allowlist, which is the
  precondition for SSRF; the server-side half is the operator's to confirm.**
- `reset_user_registrations.php` and `ping_user.php` reached parameter validation with **only the
  shared key** and no per-user credential (see §C).

**Impact.** If `rss_proxy.php`'s token requirement is ever absent, mis-routed, or bypassed, any
client can make the backend issue an arbitrary outbound `GET` with a fully attacker-chosen URL:
internal hostnames, `127.0.0.1`, RFC1918 space, and (if the backend's HTTP client is not
scheme-locked) `file://`. That is a read primitive against the operator's own infrastructure, and a
port-scan oracle via response-shape/timing differences. Because the trigger is "a podcast feed the
victim is subscribed to has a bad certificate", it needs no attacker-supplied input at all beyond
the feed URL the victim already trusts.

**Remediation.**
- *Client-side (vendor).* Validate the URL in `wn5.v()` and at `to3.java:403` before sending:
  require `https://`, require a syntactically valid host with a public suffix, reject anything
  resolving to loopback/link-local/private/unique-local space, and cap the length. Do this in
  addition to, not instead of, the server-side checks.
- *Client-side (vendor).* Delete the silent proxy fallback at `fo5.java:4707-4710`, or surface the
  401 to the user. A fallback that fails on 100% of devices is worse than no fallback: it hides the
  real error ("this feed's certificate is broken") behind a generic failure.
- *Backend-side (operator).* Keep the token on `rss_proxy.php`; add an egress allowlist on the
  proxy (scheme `https` only, deny loopback/link-local/RFC1918/CGNAT, resolve-then-pin the address
  before connecting so a rebinding DNS answer cannot smuggle a private IP); add a per-key rate limit
  and a response-size cap. Apply the same allowlist to `search_podcast_by_url.php`.

### B-2. The client's "validation" is not an SSRF defence

**Severity: Medium** — the code that *looks* like validation is normalisation, and one branch makes
the problem worse.

**CONFIRMED** (code).

`fo5.d0(String)` is the first thing every user-supplied URL passes through:

`sources/defpackage/fo5.java:1172`
```java
        return (strTrim.startsWith("/") || V(strTrim)) ? strTrim : "http://".concat(strTrim);
```

A scheme-less input is turned into `http://<input>`. And `fo5.e0(String, boolean)` — the
"scheme repair" stage — is gated by `fo5.X(String)`, which is `android.util.Patterns.WEB_URL`:

`sources/defpackage/fo5.java:836-838`
```java
        boolean zMatches = Patterns.WEB_URL.matcher(str).matches();
        if (zMatches) {
            return zMatches;
        }
```

`Patterns.WEB_URL` is a *shape* test, not a security test: it matches `http://…` and `https://…`
URLs, so it does at least exclude `file://`/`gopher://` at this one gate — but it is applied only on
the `e0()` path (which is the OPML `xmlUrl` path, `a83.java:47`), and it is **not** applied to the
`rss_proxy` / `search_podcast_by_url` paths at all. The internals report already establishes
(§3.8) that `fo5.f0()` strips Chartable and tracker prefixes and then *prepends `https://`* when the
result has no scheme — i.e. the sanitiser's answer to a missing scheme is to invent one, not to
reject.

The `UnknownHostException` retry makes a specific case worse:

`sources/defpackage/fo5.java:4716-4729`
```java
                                if ((th instanceof UnknownHostException) || (th instanceof ConnectException)) {
                                    g0(tb4Var, str8, sb, th);
                                    strS0 = s0(str8);
                                    if (TextUtils.isEmpty(strS0) || TextUtils.equals(str8, strS0)) {
                                        ...
                                    } else {
                                        try {
                                            ka4Var.g(strS0);
                                            i3++;
                                            try {
                                                return w(ka4Var, yoVar, z2, z8, z4, sb, i3, false, false);
```

`s0()` rewrites the URL by truncating tracker prefixes and re-prefixing a path segment as a
*filename*. A feed whose enclosure URL fails to resolve is therefore retried with a machine-built
URL derived from attacker-controlled path components — so the eventual fetch target is, in part,
supplied by the feed.

**Impact.** Any future fix that consists of "validate the URL in the app" is bypassable by the
retry/rewrite paths unless the check is applied to the *final* URL of every attempt, not to the
user-visible input. And because `fo5.X` gates only one of several entry points, a partial rollout of
"we now validate" leaves the proxy and search paths open.

**Remediation (client-side, vendor).** One function, called on the URL *immediately before* each
`OkHttp` enqueue and on the value handed to any backend URL-taking endpoint, doing:
`scheme ∈ {https} → host present and public-suffix-valid → resolved addresses all global → else
abort`. Apply it inside `fo5.w()`'s retry loop too, so rewritten URLs are re-checked.

**Responsible-disclosure note.** B-1/B-2 are split: the *absence of client-side validation* is the
vendor's code (client-side); the *presence of the token on `rss_proxy.php` and the backend's
allowlist* is the operator's (backend-side). I did not test the backend's egress controls because
doing so requires probing internal addresses, which is out of bounds for this engagement.

---

## C. The shared API key and the auth model

### C-1. One shared key is the only credential on 55 of 56 endpoints; `userUUID` is asserted, not authenticated

**Severity: High** — an unauthenticated, cross-user destructive primitive on `reset_user_registrations.php`,
plus a telemetry-write primitive.

**CONFIRMED** (code) for the client side; **live probe** established that the endpoint is reachable
with the shared key alone. The *destructive* half is **THEORETICAL** — see below.

The shared key is attached in exactly one place, to exactly one family of requests:

`sources/defpackage/fo5.java:2003-2009`
```java
            ka4VarH.a("X-App-Key", "d2fad335-a44d-4aeb-9e5b-67b19a15572c");
            String str5 = zh.a;
            String str6 = yh.a;
            if (TextUtils.isEmpty(str6)) {
                str6 = POBCommonConstants.NULL_VALUE;
            }
            ka4VarH.a("X-App-Installer", str6);
```

and again as a query parameter for the radio calls (`sources/defpackage/wn5.java:2422-2428`):

`sources/defpackage/wn5.java:2420-2429`
```java
    public static ArrayList c(int i) {
        ArrayList arrayList = new ArrayList(Math.max(0, i + 2));
        arrayList.add(new qd3("key", "d2fad335-a44d-4aeb-9e5b-67b19a15572c"));
        String str = zh.a;
        String str2 = yh.a;
        if (TextUtils.isEmpty(str2)) {
            str2 = POBCommonConstants.NULL_VALUE;
        }
        arrayList.add(new qd3("installer", str2));
        return arrayList;
    }
```

Every `/ws/php/v4.1/*` endpoint in §3.7's table A is called through `fo5.p/q/j0/k0`, i.e. with this
header and no other credential. There is no request signing, no nonce, no timestamp, no HMAC — the
internals report already establishes this (§3.6: "no secret used to sign requests").

**`userUUID` is a locally generated identifier, not a secret.**

`sources/com/bambuna/podcastaddict/helper/a.java:1370-1389`
```java
    public static String i1(Context context) {
        String string = Y0().getString("pref_uuid", "-1");
        if (!"-1".equals(string)) {
            return string;
        }
        String str = y85.a;
        StringBuilder sb = new StringBuilder(128);
        if (context != null) {
            try {
                sb.append(Settings.Secure.getString(context.getContentResolver(), "android_id"));
                sb.append('#');
            } catch (Throwable th) {
                w26.i(y85.a, th);
            }
            sb.append(UUID.randomUUID().toString());
        }
        String string2 = sb.toString();
        lp2.s("pref_uuid", string2);
        return string2;
    }
```

Three properties make this unsafe as an authorisation token:
1. It is stored in app-private `SharedPreferences`, and the manifest declares
   `android:allowBackup="true"` with `@xml/auto_backup_scheme` that excludes only the `podcast` file
   directories (`resources/AndroidManifest.xml:195-200` + `resources/res/xml/auto_backup_scheme.xml`).
   `pref_uuid` is **not** in the exclude list, so it is carried in device backups.
2. It is derived from `Settings.Secure.ANDROID_ID`, which is not confidential, plus a random UUID.
3. The app writes it into crash reports itself, in cleartext, in the very function that calls the
   reset endpoint:

   `sources/defpackage/wn5.java:525-531`
   ```java
                   String strI1 = a.i1(context);
                   ArrayList arrayListC = c(1);
                   arrayListC.add(new qd3("userUUID", strI1));
                   String strJ0 = fo5.j0("https://addictpodcast.com/ws/php/v4.1/reset_user_registrations.php", arrayListC);
                   zEquals = "OK".equals(strJ0);
                   if2.u(new Throwable("resetUserRegistrations(" + strI1 + ") - " + strJ0));
   ```

   That `Throwable` goes to the Crashlytics/`w26` reporting path (`if2.u`), so the identifier is in a
   telemetry pipeline that the vendor can read and that any crash-report exfiltration would capture.

`reset_user_registrations.php` therefore accepts only `{key, installer, userUUID}` — i.e. the shared
key plus a non-secret identifier. Nothing binds the request to *who is asking*. Confirmed by live
probe: with only the shared key and a well-formed-but-unused `userUUID`, the endpoint reached its own
parameter handling rather than an auth failure:

```
$ curl -s -w '\nHTTP %{http_code}\n' --max-time 20 -A 'PodcastAddict/v5 (+https://podcastaddict.com/; Android podcast app)' \
    -H 'X-App-Key: d2fad335-a44d-4aeb-9e5b-67b19a15572c' -H 'X-App-Installer: com.android.vending' \
    --get 'https://addictpodcast.com/ws/php/v4.1/reset_user_registrations.php' \
    --data-urlencode 'userUUID=00000000-0000-0000-0000-000000000000'

HTTP 200
Missing parameters
```

Same for `ping_user.php` (`HTTP 200 / Missing parameters`). No 401/403. I did **not** supply a real
`userUUID`, because doing so would actually un-register a real install — that is a destructive
cross-user action and out of bounds. Hence:

**THEORETICAL (destructive half):** that `reset_user_registrations.php` will reset an arbitrary
install given only someone else's `userUUID`. Confirmation cost: one request with a real
`userUUID` belonging to a test install the reporter controls — i.e. it cannot be confirmed without
either the vendor's cooperation or a write. What **is** confirmed: the endpoint is reachable with
the shared key alone and performs no additional authentication check before parameter validation.

**Impact.** Assuming the backend trusts `userUUID` (which is the only sensible reading of the client
design), any holder of the APK — which is anyone — can un-register any other user's install by
guessing or harvesting the identifier. `userUUID.hashCode()` is even used as a deterministic sharding
key (`wn5.java:744`), which shows the value is treated as stable and non-secret throughout. The
`ping_user.php` payload additionally carries `totalPlaybackTime`, `subscriptionNumber`, `languages`,
`device`, `androidVersion` — a full fingerprint posted on a shared key.

**Remediation.**
- *Backend-side (operator).* `reset_user_registrations.php` and every other endpoint taking
  `userUUID` must authenticate the caller independently of `userUUID` — a Bearer token from
  `sync/session.php`, or at minimum a server-issued per-install secret that is not derived from
  `ANDROID_ID` and is not backed up. Treat `X-App-Key` as a rate-limiting/quota key, never as an
  authorisation decision.
- *Client-side (vendor).* Stop putting `userUUID` in a `Throwable` handed to crash reporting
  (`wn5.java:530`). Also exclude `pref_uuid` from `auto_backup_scheme.xml` so it does not migrate
  between devices — an identifier that is copied between installs is worse than a per-install one.

### C-2. `X-App-Installer` comes from PackageManager and is interpolated into request URLs

**Severity: Low** — no client-side injection (it is percent-encoded), but it is spoofable by any raw
HTTP client and must not be trusted server-side.

**CONFIRMED** (code). The installer is read from the OS once, in a static initialiser:

`sources/defpackage/yh.java` (whole file)
```java
public abstract class yh {
    public static final String a;
    public static final String b;

    static {
        int i = s95.a;
        try {
            a = zh.a("com.bambuna.podcastaddict");
```

`sources/defpackage/zh.java:52-76`
```java
    public static String a(String str) {
        String installingPackageName = null;
        if (TextUtils.isEmpty(str)) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                installingPackageName = PodcastAddictApplication.E().getPackageManager().getInstallSourceInfo(str).getInstallingPackageName();
            } catch (PackageManager.NameNotFoundException unused) {
            } catch (Throwable th) {
                if2.u(th);
            }
        }
        if (!TextUtils.isEmpty(installingPackageName)) {
            return installingPackageName;
        }
        try {
            return PodcastAddictApplication.E().getPackageManager().getInstallerPackageName(str);
```

and it reaches the network in two shapes:
- as a query **parameter** on the URL, via the helpers at `wn5.java:2428` (`installer`) and
  `wn5.java:167` (`jSONObject.put("installer", str3)` in the `ping_user.php` body);
- as a header, at `fo5.java:2009` (`X-App-Installer`).

Because `fo5.p()` runs every query parameter through `es9.d(..., HttpUrl.QUERY_COMPONENT_ENCODE_SET, 91)`
(`fo5.java:1988-1991`) and `wn5.java:2422-2428` builds the list as `qd3` pairs, the value is
percent-encoded before it is placed in the URL — so there is **no** URL/query-parameter injection
from this source. What there *is*: the value is fully attacker-controlled from outside the app (any
raw HTTP client sets `X-App-Installer` to anything; `PackageManager.getInstallingPackageName()` is
a best-effort OS answer, trivially `null`/spoofable on a rooted device). The app already uses the
installer for a security decision — the deny-list at `zh.java:34-47` — so a raw client can make an
APKPure-sourced copy look like a Play install, and can make a Play-sourced request look like an
Uptodown one.

**Impact.** Any server-side logic keyed on `X-App-Installer` — promotional gating, piracy
enforcement, quota tiers, log attribution — is bypassable. That is the whole risk; it is not a
client memory-corruption or injection issue.

**Remediation.**
- *Backend-side (operator).* Never trust `X-App-Installer`; if the installer matters, re-derive it
  server-side from Play Integrity / Amazon licensing, and log it as untrusted metadata only.
- *Client-side (vendor).* Nothing required for injection. Treat `zh.java`'s installer deny-list as
  cosmetic given this — and note §11.2's signing-certificate check is the load-bearing control
  there, not the installer list.

### C-3. The `sync/*` endpoints are correctly authenticated — checked, and clean

**CONFIRMED** (code). A separate stack, on a different path (`/ws/php/current/sync/`), with a real
credential. The session endpoint consumes a Google ID token:

`sources/defpackage/j25.java:80`
```java
                    jSONObject.put("googleIdToken", str3);
```
`sources/defpackage/j25.java:109-111`
```java
                    ka4 ka4VarH = fo5.H("https://addictpodcast.com/ws/php/current/sync/session.php", null, true);
                    ka4VarH.d("X-Sync-Protocol", String.valueOf(1));
                    ka4VarH.f(va4.create(jSONObject.toString(), b));
```

and the response is a bearer token used on every subsequent call:

`sources/defpackage/j25.java:132`
```java
                                            String string2 = jSONObject3.getString("sessionToken");
```

`sources/defpackage/t15.java:95-98`
```java
            ka4 ka4VarH = fo5.H("https://addictpodcast.com/ws/php/current/sync/pull.php", null, true);
            ka4VarH.d("X-Sync-Protocol", String.valueOf(1));
            ka4VarH.d("Authorization", "Bearer " + i25Var.a);
            ka4VarH.f(va4.create(jSONObject.toString(), b));
```

Note the difference from §B/§C-1: `fo5.H()` (`fo5.java:363-406`) is a bare request builder that sets
no `X-App-Key` at all. So the sync family is the one part of the backend that does **not** rely on
the shared key, and it uses a genuine per-user credential. Confirmed clean for the
"what can be done without a Bearer token" question: nothing — these endpoints reject without it.

---

## D. Exported components

### D-1. `ArtworkFileProvider` is exported, unauthenticated, and resolves `Uri.getPath()` with `new File(parent, child)` — arbitrary read, write **and** delete

**Severity: Critical** — an exported `ContentProvider` with no permission, exposing `openFile()` in
write modes and `delete()`, over a path built from unvalidated `Uri.getPath()`.

**CONFIRMED** (manifest + code). The manifest declares it exported, with URI grants and **no**
permission of any kind:

`resources/AndroidManifest.xml` (provider block, extracted verbatim)
```xml
<provider android:name="com.bambuna.podcastaddict.provider.ArtworkFileProvider"
    android:exported="true"
    android:authorities="com.bambuna.podcastaddict.artworkFileProvider"
    android:grantUriPermissions="true"/>
```

It is the only exported provider; the other six are all `exported="false"` (`FileProvider`,
`MobileAdsInitProvider`, `FirebaseInitProvider`, `AppLovinInitProvider`,
`androidx.startup.InitializationProvider`, `PicassoProvider`).

The class extends `android.content.ContentProvider` **directly** — not the androidx `FileProvider`
whose safety it is otherwise imitating — and it implements write and delete:

`sources/com/bambuna/podcastaddict/provider/ArtworkFileProvider.java`
```java
    @Override // android.content.ContentProvider
    public final int delete(Uri uri, String str, String[] strArr) {
        this.a.getClass();
        return uw9.c(uri).delete() ? 1 : 0;
    }
```
```java
    @Override // android.content.ContentProvider
    public final ParcelFileDescriptor openFile(Uri uri, String str) {
        int i;
        this.a.getClass();
        File fileC = uw9.c(uri);
        if ("r".equals(str)) {
            i = 268435456;
        } else if (POBCoreNativeConstants.NATIVE_IMAGE_WIDTH.equals(str) || "wt".equals(str)) {
            i = 738197504;
        } else if ("wa".equals(str)) {
            i = 704643072;
        } else if ("rw".equals(str)) {
            i = 939524096;
        } else {
            if (!"rwt".equals(str)) {
                x40.i(k61.u("Invalid mode: ", str));
                return null;
            }
            i = 1006632960;
        }
        return ParcelFileDescriptor.open(fileC, i);
    }
```

(`738197504` = `0x2C000000` = `O_RDWR|O_CREAT`, i.e. `"wt"` truncates; `704643072` = `0x2A000000`
= `O_WRONLY|O_APPEND`, i.e. `"wa"` appends. So an external caller can create, overwrite, append
to, or truncate a file, then read it back.)

The `attachInfo` override is the androidx `FileProvider` idiom, copied but **not applied** — it reads
`providerInfo.exported` into a local and never uses it:

```java
    @Override // android.content.ContentProvider
    public final void attachInfo(Context context, ProviderInfo providerInfo) {
        super.attachInfo(context, providerInfo);
        boolean z = providerInfo.exported;
        if (!providerInfo.grantUriPermissions) {
            throw new SecurityException("Provider must grant uri permissions");
        }
        this.a = new uw9((byte) 25);
    }
```

And the path resolution, the actual defect:

`sources/defpackage/uw9.java:39-42`
```java
    public static File c(Uri uri) {
        PodcastAddictApplication.E();
        return wx4.J("thumbnails", uri.getPath(), true);
    }
```

`sources/defpackage/wx4.java:317-330`
```java
    public static File J(String str, String str2, boolean z) {
        if (TextUtils.isEmpty(str2) || TextUtils.isEmpty(str)) {
            return null;
        }
        String strK = K();
        if (TextUtils.isEmpty(strK)) {
            return null;
        }
        File file = new File(strK + '/' + str);
        if (z) {
            xk1.h(file);
        }
        return new File(file, str2);
    }
```

That final line is the defect. `new File(File parent, String child)` performs **no** traversal
check, and there is no canonical-path containment check anywhere on this path. `wx4.J()` is called
with `uri.getPath()` — the raw path component of the `content://` URI, attacker-controlled, with no
length, character, or `..` filtering. `new File(<root>/thumbnails, "/../../databases/…")` resolves
outside `thumbnails` (on Unix, `File(File,String)` concatenates when the child begins with `/`, and
the result is still resolved by the filesystem, so the leading slash does not sanitise it).

Two confirmations of the same defect are visible in `query()`, which hands the resolved absolute
path back to the caller as `_data`:

```java
                if ("_data".equals(str3)) {
                    strArr3[i2] = "_data";
                    i = i2 + 1;
                    objArr[i2] = fileC.getPath();
                }
```

**A decisive comparison exists in the same package.** The app has a *second*, sibling provider,
`com/bambuna/podcastaddict/provider/FileProvider.java`, which is the same code with one crucial
difference — it contains the check `ArtworkFileProvider` omits:

`sources/com/bambuna/podcastaddict/provider/FileProvider.java:36-46`
```java
    @Override // android.content.ContentProvider
    public final void attachInfo(Context context, ProviderInfo providerInfo) {
        super.attachInfo(context, providerInfo);
        if (providerInfo.exported) {
            throw new SecurityException("Provider must not be exported");
        }
        if (!providerInfo.grantUriPermissions) {
            throw new SecurityException("Provider must grant uri permissions");
        }
        this.a = new ck0((byte) 11);
    }
```

So the developer knew the pattern, applied it to `FileProvider` (which is correctly
`exported="false"` in the manifest), and left it out of `ArtworkFileProvider`. `ArtworkFileProvider`'s
`attachInfo` reads `providerInfo.exported` into a local variable `z` and never uses it — the check was
there in spirit and got dropped.

**The sibling is itself not clean**, and is worth fixing in the same change: `FileProvider` resolves
`new File(uri.getPath())` with **no base directory at all** (`FileProvider.java:50,56,79,102`):

`sources/com/bambuna/podcastaddict/provider/FileProvider.java:76-95`
```java
    @Override // android.content.ContentProvider
    public final ParcelFileDescriptor openFile(Uri uri, String str) {
        int i;
        this.a.getClass();
        File file = new File(uri.getPath());
        if ("r".equals(str)) {
            i = 268435456;
        } else if (POBCoreNativeConstants.NATIVE_IMAGE_WIDTH.equals(str) || "wt".equals(str)) {
            i = 738197504;
```
so `content://com.bambuna.podcastaddict.fileProvider/data/data/com.bambuna.podcastaddict/databases/podcastAddict.db`
resolves to an absolute path. It is currently saved only by `exported="false"`; since it declares
`grantUriPermissions="true"`, any URI permission the app grants (directly or via a prefix grant) is
exercisable on an absolute path chosen by the grantee. It needs the same canonical-path containment
check, and ideally a base directory like `ArtworkFileProvider`'s.

**Impact.** Any app installed on the device can, with no permission and no user interaction:

1. **Read** the app's private data. `ContentResolver.openFileDescriptor(
   Uri.parse("content://com.bambuna.podcastaddict.artworkFileProvider/../../databases/podcastAddict.db"), "r")`
   yields a readable descriptor on `podcastAddict.db` — the subscription database, including every
   feed URL, any `privateFeed` flag, listening statistics, and the `pref_uuid`/token SharedPreferences
   if they can be reached (they live under `/data/data/…/shared_prefs/`, reachable with the same
   traversal). The provider process runs as the app's UID, so the file is readable by it.
2. **Write** to any file the app's UID can write — overwrite or append to the database, corrupt
   settings, or plant a file that the app will later execute or trust.
3. **Delete** any file the app's UID can write, via `delete()`.

Because `delete()` and `openFile(…, "wt")` are destructive, I did not demonstrate this live: I have
no test device, and a live demonstration would be a write against the vendor's own binary. The
traversal itself is statically unambiguous — `new File(parent, child)` with an unfiltered `child`.

**Remediation (client-side, vendor).** Short and non-negotiable:
1. Set `android:exported="false"` on `ArtworkFileProvider`. There is no reason for it to be exported:
   it exists to hand artwork to `NotificationManager` and to Glide/Picasso inside the app, and
   `grantUriPermissions="true"` is sufficient for that once the provider is private. The URIs it
   hands out are built at exactly two sites — `tz.java:1704` and `xn4.java:813` — both
   `Uri.Builder().scheme("content").authority("com.bambuna.podcastaddict.artworkFileProvider").encodedPath(...)`,
   i.e. internally-constructed paths for notifications, downloads and shares, never for cross-app
   consumption. Nothing about the feature requires it to be public.
2. Independently, replace the body of `uw9.c()`/`wx4.J()` with a canonical-path check:
   ```java
   File f = new File(base, child);
   File canon = f.getCanonicalFile();
   if (!canon.toPath().startsWith(base.getCanonicalFile().toPath())) throw new SecurityException();
   ```
   on **both** sides of the write mode. The app does not use the androidx `FileProvider` — it has two
   hand-rolled `ContentProvider` subclasses (`provider/ArtworkFileProvider.java` and
   `provider/FileProvider.java`) — so either add the check to both, or migrate to androidx
   `FileProvider`, which performs it.
3. Remove `delete()` and the non-`"r"` branches of `openFile()` if the provider only ever serves
   artwork for reading. A provider that serves thumbnails has no reason to expose `"rwt"`.
4. Apply the identical fix to `provider/FileProvider.java`, whose `new File(uri.getPath())` has no
   base directory and is saved only by `exported="false"`.

**Responsible-disclosure note.** This is entirely the vendor's code (client-side). It is the single
highest-impact finding in this assessment and the one I would fix first.

### D-2. The rest of the exported surface — triage

**Severity: Informational overall**, with two Medium items noted inline.

**CONFIRMED** (manifest). 130 activities (27 exported), 23 services (5 exported), 25 receivers
(18 exported), 7 providers (1 exported). Breakdown of the 18 exported receivers:

**Well-controlled (no action needed):**

| Component | Why it is fine |
|---|---|
| `QuickSettingUpdateService` | `android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"` (manifest:1481) — the system grants this; third-party apps cannot bind. |
| `WidgetPlaylistService` | guarded by `android.permission.BIND_REMOTEVIEWS`. |
| `SystemJobService` | guarded by `android.permission.BIND_JOB_SERVICE`. |
| `RevocationBoundService` | guarded by Google's `…auth.api.signin.permission.REVOCATION_NOTIFICATION`. |
| `FirebaseInstanceIdReceiver` | guarded by `com.google.android.c2dm.permission.SEND`. |
| `DiagnosticsReceiver`, `ProfileInstallReceiver` | guarded by `android.permission.DUMP` (signature-level). |
| `ChromecastMediaButtonReceiver` | **`exported="false"`** (manifest:1508) — the internals report flags this as exported; the decoded manifest says otherwise. This is a correction to the prior report. |

**Exported and reachable by any app, but low impact:**

- `PodcastAddictMediaButtonReceiver` (manifest:1491-1497) — `exported="true"`,
  `intent-filter priority=1000`, `action android.intent.action.MEDIA_BUTTON`. This is the standard,
  *correct* configuration for a media-button receiver: it must be exported for the system to
  deliver `MEDIA_BUTTON`, and it is protected by the framework requiring the sender to hold
  `android.permission.STATUS_BAR`/be the media session owner — an app that blindly broadcasts
  `MEDIA_BUTTON` can make the app play/pause, which is the normal UX contract, not a vulnerability.
- `PodcastAddictPlayerReceiver` (manifest:1517-1564) — `exported="true"`, priority 1000, with **40
  app-specific actions** (`com.bambuna.podcastaddict.service.player.toggle`,
  `…player.playLiveStream`, `…service.player.deletecurrentskipnexttrack`,
  `…player.playepisode` etc.). These are the documented Tasker/third-party-integration broadcasts
  and are the app's intended extension surface. Impact is limited to playback control and
  local-file operations; see the **intent-redirection note** below for the one item worth checking.
- `PodcastAddictBroadcastReceiver` (manifest:1565-1579) — `exported="true"`, priority 1000, for
  `BOOT_COMPLETED`, `com.bambuna.podcastaddict.service.update`, `…service.opml_export`,
  `…service.full_backup`. Note that `service.opml_export` and `service.full_backup` are
  triggerable by any app — a backup/export writes files to the user's storage, and a repeated
  trigger is a local resource-abuse annoyance, not a confidentiality break. **Recommend** gating
  `…service.opml_export` and `…service.full_backup` behind a signature-level permission, or at
  minimum a `BroadcastReceiver`-side caller check, since nothing here needs to be public.
- `AlarmReceiver`, `PodcastAddictBluetoothReceiver`, `WazeWakeUpReceiver` — exported as required
  by their system roles (`AlarmManager`, A2DP profile broadcasts, Waze SDK handshake). Normal.
- 11 widget providers + `Widget1x1UpdaterProvider` — required to be exported for the launcher.
- 27 exported activities — the app's navigable surface. Standard; no `exportedActivity`
  `intent-redirection` pattern (`getParcelableExtra` fed to `startActivity`) was found in the
  decompiled sources (see "checked and found clean"). `PreferencesActivity` is exported, which is
  normal for the redirected-from-settings case; nothing in the preference tree is credential-bearing.
- 2 Amazon APS ad activities (`ApsInterstitialActivity`, `DTBInterstitialActivity`) — third-party
  ad-SDK components, exported as those SDKs require.

**Exported `AndroidAutoMediaBrowserService` — clean.** (manifest:1750-1753,
`android.media.browse.MediaBrowserService`, no `android:permission` but `onGetRoot` enforces its own
allowlist). `sources/com/bambuna/podcastaddict/service/AndroidAutoMediaBrowserService.java:85-228`
validates the caller by UID and certificate SHA-256 from
`res/xml/allowed_media_browser_callers.xml`, and — correctly — **fails closed**: unknown callers
get an empty browser root, not a rejection, so the media session is still usable
(`AndroidAutoMediaBrowserService.java:222-228`):
```java
            String str5 = q;
            StringBuilder sbL = jl0.l("OnGetRoot: Browsing NOT ALLOWED for unknown caller. Returning empty browser root so all apps can use MediaController.", str, " / PackageValidator: ");
            sbL.append(f() == null ? "NULL" : "NOT null");
            a20.E(str5, sbL.toString());
            if (PodcastAddictApplication.E() != null) {
                PodcastAddictApplication.E().F1 = false;
            }
            return new ci7("__EMPTY_ROOT__", (Object) null);
```
The allowlist is **enforced, not advisory** — the answer to §H's question.

**TODO note for the vendor (Medium, needs runtime confirmation).** `PodcastAddictPlayerReceiver`
handles `com.bambuna.podcastaddict.service.player.playLiveStream` and the episode-selection
broadcasts. If any of those read a URL/path from the incoming `Intent` extras and start playback or
a download directly, an app could drive playback of an arbitrary URL or trigger a download to an
arbitrary destination. I could not trace all 40 action branches to their handlers in the decompiled
tree, so I am **not** asserting this — it is the one exported surface worth a runtime audit, and
the cost of confirming it is a one-screen fuzz of those actions on a device with the app installed.

---

## E. WebView configuration

**Severity: Informational** — the dangerous combination (remote content + JS bridge) is **not**
present in any reachable WebView. Two Medium observations and one clean negative result follow.

**CONFIRMED** (code + manifest). The app has four WebView-bearing activities. Their exact
configuration, in order of how dangerous the configuration is:

### E-1. `ImageSearchActivity` — the only app WebView with a JS bridge, and the URL is app-built

`sources/com/bambuna/podcastaddict/activity/ImageSearchActivity.java:31-45`
```java
    public final void F() {
        String str;
        if (this.A == null || (str = this.H) == null || str.isEmpty()) {
            return;
        }
        this.A.getSettings().setJavaScriptEnabled(true);
        this.A.getSettings().setDomStorageEnabled(true);
        this.A.addJavascriptInterface(new f62(this), "PodcastAddictArtwork");
        this.A.setWebViewClient(new g62(this));
        this.A.loadUrl(this.H);
    }
```

with

`sources/com/bambuna/podcastaddict/activity/ImageSearchActivity.java` (onCreate)
```java
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            this.H = extras.getString(POBCoreNativeConstants.NATIVE_LINK_URL);
        }
```

The bridge's only method:

`sources/defpackage/f62.java` (whole file)
```java
    @JavascriptInterface
    public void select(String str) {
        if (str != null) {
            if (str.startsWith("https://") || str.startsWith("http://")) {
                int i = ImageSearchActivity.K;
                ImageSearchActivity imageSearchActivity = this.a;
                h65.g(new cq7(imageSearchActivity, str, false, (byte) 17));
            }
        }
    }
```

The bridge surface is one function that hands an `http(s)://` string to an async task — i.e. it can
only choose artwork, nothing else. Two mitigations stop this from being exploitable:

1. **`ImageSearchActivity` is `exported="false"`** (`resources/AndroidManifest.xml`), so no external
   app can supply its own `url` extra.
2. The only caller builds the URL from an encoded search term:

   `sources/defpackage/ep0.java:42-58`
   ```java
                   try {
                       strM0 = URLEncoder.encode(strM0, "utf-8");
                   } catch (Throwable th) {
                       w26.i(i.e0, th);
                   }
               }
               StringBuilder sb = new StringBuilder("https://www.google.com/search?q=");
               int i = oy4.a;
               if (strM0 == null) {
                   strM0 = "";
               }
               sb.append(strM0);
               sb.append("&tbm=isch");
               bundle.putString(POBCoreNativeConstants.NATIVE_LINK_URL, sb.toString());
               Intent intent = new Intent(fp0Var, (Class<?>) ImageSearchActivity.class);
               intent.putExtras(bundle);
               fp0Var.startActivityForResult(intent, 1001);
   ```

   The `URLEncoder.encode(...)` at `:43` covers the podcast title, so feed-derived text cannot break
   out of the `q=` parameter. Note the fallback order at `:48-54` is interesting but not a defect:
   when `InternetImageSearch` is unavailable it falls back to scraping `google.com/search` for
   `<img>` sources rather than the JSON API.

**Residual Medium observation.** The page loaded is `google.com/search` — third-party, remote, and
JS-enabled, *in a WebView with an injected JS interface and DOM storage*. The bridge method is
narrow, but this is exactly the topology a single careless bridge-method addition later turns into
an app-API exposure. If the app ever adds a second `@JavascriptInterface` method (say, one that
takes a path), this WebView becomes a remote-code-execution-in-app-context primitive, because the
loaded content is not the app's own. Recommend: keep `ImageSearchActivity` non-exported, and add a
`shouldOverrideUrlLoading` that only permits `google.com/search` hosts (g62's `WebViewClient`
currently overrides neither `shouldOverrideUrlLoading` nor `onPageStarted`).

### E-2. `TranscriptWebViewActivity` — remote, attacker-chosen URL, JS on, cookies on, no bridge

`sources/com/bambuna/podcastaddict/activity/TranscriptWebViewActivity.java:36-45`
```java
        byte b = 1;
        this.A.getSettings().setJavaScriptEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(this.A, true);
        this.A.setWebViewClient(new za5(this));
        this.A.getSettings().setUseWideViewPort(false);
```
`TranscriptWebViewActivity.java` (onCreate)
```java
            this.H = extras.getString("mime");
            this.G = extras.getString(POBCoreNativeConstants.NATIVE_LINK_URL);
            this.I = extras.getLong("episodeId");
```

`this.G` is the transcript URL, which comes from the feed's `podcast:transcript` /
`chapters_url`-adjacent metadata — i.e. **remote, attacker-controlled, unsanitised**. Also
`exported="false"`, so it is reachable only via a podcast the user has subscribed to.

There is **no** `addJavascriptInterface` here, so there is no bridge for loaded JS to call — which
is why this is Medium and not Critical. The exposure is: JavaScript from a hostile transcript host
runs in a WebView with third-party cookies accepted and full DOM/storage access, inside a process
that holds the app's database credentials, the backend key and the user's session tokens. There is
no same-origin boundary to the app's own data (WebViews have no URL-based origin restrictions the
app hasn't imposed), so any file the WebView can reach — including via `FileProvider` grants
temporarily held, or the exported `ArtworkFileProvider` of §D-1 — is in scope for that page's JS.

**Remediation (client-side, vendor).**
- Set `setJavaScriptEnabled(false)` for `TranscriptWebViewActivity`. Transcripts are text; the app
  already converts `srt`/`vtt`/`json` in Java (`new Thread(new b4(...))`) for the non-HTML mime
  types. If a vendor-hosted HTML transcript is a requirement, render it on a first-party allowlisted
  domain only.
- `setAcceptThirdPartyCookies(...)` should be `false` (or the WebView not used at all).
- Set `setAllowFileAccess(false)` / `setAllowContentAccess(false)` explicitly. The app does this
  correctly in several other places (`xs6.java:54-55`, `qj8.java:39-40`, `uq6.java:383`), so the
  pattern exists and just was not applied here.

### E-3. `TestRSSFeedActivity` — same shape, app-internal only, no bridge

`sources/com/bambuna/podcastaddict/activity/TestRSSFeedActivity.java:29-38`
```java
        this.A.getSettings().setJavaScriptEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(this.A, true);
        this.A.setWebViewClient(new h55());
        this.A.getSettings().setUseWideViewPort(false);
```
`exported="false"`, no JS bridge, and the only caller is
`com/bambuna/podcastaddict/activity/EpisodeListActivity.java:844` (app-internal). Lowest risk of the
three, but it carries the same unnecessary `setJavaScriptEnabled(true)` +
`setAcceptThirdPartyCookies(true)` pair and should be tightened along with E-2.

### E-4. `PodcastPrivacyActivity` is **not** a WebView — correcting the internals report

The internals report (§11.1) lists `PodcastPrivacyActivity` alongside the WebView activities. It is
not one: `sources/com/bambuna/podcastaddict/activity/PodcastPrivacyActivity.java` builds a native
`setContentView(t24.podcast_privacy_policy)` and contains no `WebView`, no `WebSettings` and no
`loadUrl` — it reads a privacy-policy URL (`POBCoreNativeConstants.NATIVE_LINK_URL` extra) and
popsulates native `TextView`s from a `c70` async task. It is `exported="false"` and takes a
`podcastId` long, which it resolves through `en3.B(long)` — a DB lookup, no injection surface
worth reporting. No action needed.

### E-5. Summary table

| Activity | exported | JS | JS bridge | Remote URL | File/Content access | Verdict |
|---|---|---|---|---|---|---|
| `ImageSearchActivity` | false | **yes** | **yes** (`f62`, one narrow method) | yes, app-built from `URLEncoder.encode`d term | default (unset) → `file://` allowed | Medium (topology) |
| `TranscriptWebViewActivity` | false | **yes** | no | **yes, feed-controlled** | default (unset) | **Medium** |
| `TestRSSFeedActivity` | false | **yes** | no | yes, from episode row | default (unset) | Low |
| `PodcastPrivacyActivity` | false | n/a | n/a | n/a (native layout) | n/a | clean |
| 47 ad-SDK WebViews (`com/applovin`, `com/inmobi`, `com/fyber`, `com/pubmatic`, `com/google/android/gms/ads`, `com/iab/omid`) | per-SDK | yes | yes (per-SDK) | yes (ad content) | explicitly `setAllowFileAccess(false)` in the OMID/Pubmatic wrappers; `setAllowFileAccessFromFileURLs(false)` + `setAllowUniversalAccessFromFileURLs(false)` in `com/google/android/gms/ads/internal/util/zzs.java:818-819` | third-party, out of scope for this vendor's remediation, but note `com/applovin/impl/adview/AppLovinWebViewBase.java:71,75` make those two flags **configurable by the ad server** |

That last row is worth flagging to the operator: `AppLovinWebViewBase` lets the ad config re-enable
`allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs`, which would let served creative
JavaScript reach `file://` URLs inside the app's WebView:
`sources/com/applovin/impl/adview/AppLovinWebViewBase.java:71`
`sources/com/applovin/impl/adview/AppLovinWebViewBase.java:75`
```java
                settings.setAllowFileAccessFromFileURLs(boolF.booleanValue());
                settings.setAllowUniversalAccessFromFileURLs(boolG.booleanValue());
```

---

## F. The URL sanitiser — `fo5.f0()` does not reject, it rewrites

**Severity: Medium** — the sanitiser is a *normaliser*, not a *validator*: no branch anywhere returns
`null` or an error for a dangerous scheme, and it is not applied to the two URL-taking backend
endpoints at all.

**CONFIRMED** (code, `jadx -m simple` re-render of `defpackage.fo5`; line numbers are from that
render, which differs from the default-mode file by ~150 lines).

Every enclosure URL the parser finds is passed through it with `validate == false`:

`sources/defpackage/e4.java:486`
```java
                                        ((ob1) this.b).m = fo5.f0(strL2, null, false);
```
(identically at `e4.java:620` and `e4.java:791`; `ob1.m` is the episode's media URL). Also
`w0.java:374` (feed redirects) and `z8.java:472` (opening a link) and `yu0.java:5181` (artwork).

The full body, re-rendered linearly (`fo5.f0(String r18, String r19, boolean r20)`, signature at
`fo5.java:1564` of the simple render; scheme-fix section quoted verbatim):

```java
1704:         Matcher r29 = g.matcher(r2);
1705:         if (r29.find() == false) goto L12;
1706:         r2 = r29.replaceFirst(r29.group(1) + "://");
1707:         a20.i(r7, new Object[]{"Fixing incomplete scheme for url: ".concat(r18)});
1708:         goto L19
1709:     L12:
1710:         if (r2.startsWith("//") == false) goto L15;
1711:         r2 = "https:".concat(r2);
1712:         goto L19
1713:     L15:
1714:         if (r2.startsWith("/") == true) goto L17;
1715:         r2 = "https://".concat(r2);
1716:         goto L19
1717:     L17:
1718:         if (r20 == true) goto L19;
1719:         w26.i(r7, new Throwable("DEBUG - normalizeEpisodeUrl() - Invalid Scheme => ".concat(r2)));
1720:         goto L19
```
and the regex it uses (`fo5.java:129`, quoted in §F above from the static initialiser):
```java
        g = Pattern.compile("^(https?|ftp|sftp|rtsp):/(?=[^/])", 2);
```

**What this does and does not do.**

| Input | Result | Assessment |
|---|---|---|
| `https://x` / `http://x` | unchanged | correct |
| `ftp:/x`, `sftp:/x`, `rtsp:/x` | rewritten to `ftp://x`, `sftp://x`, `rtsp://x` | **a non-HTTP scheme is a *valid output* of the sanitiser** |
| `//evil.com/x` | `https://evil.com/x` | scheme-relative accepted, as suspected in §F's brief |
| `/data/local/tmp/x` | **returned unchanged**, only a log line | a leading-`/` path is preserved as an app-relative path |
| `file://…`, `content://…`, `intent://…`, `javascript:` | falls through to `"https://".concat(r2)` | mangled into `https://file://…` — broken, not passed through |
| `data:image/…;base64,…` | returned unchanged by the early `tz.t()` exit at `tz.java:1852-1853` | the one pass-through |

So the sanitiser is **not** the arbitrary-file-read primitive the brief hypothesised: the catch-all
`"https://".concat(r2)` at line 1719 mangles every non-HTTP scheme before it can reach the download
engine. I checked the two consumers that could have turned a preserved non-HTTP scheme into
something dangerous, and both are clean:

- `hc1.k1(ob1)` (`hc1.java:3658-3663`) is the only place a local-path episode URL is *used*:

  ```java
      public static boolean k1(ob1 ob1Var) {
          if (ob1Var == null || TextUtils.isEmpty(ob1Var.m)) {
              return false;
          }
          return ob1Var.m.startsWith("/") || ob1Var.m.startsWith("content://");
      }
  ```

  and its single caller (`xn4.java:537-540`) uses it to *refuse* to share such an episode:

  ```java
          if (ob1Var.D || (en3.b0(ob1Var.c) && hc1.k1(ob1Var))) {
              z8.h0(activity, activity.getString(n34.errorCannotShareVirtualEpisode), true);
              return;
          }
  ```
- `zc3`'s `file://` handling (`zc3.java:88-95`) strips the scheme and builds `new File(str)` — but
  `zc3` is only ever constructed from a **user-picked** URI (the OPML picker, `d83.java:82`), so that
  is the file picker's own trust model, not a feed-controlled path.

**Where the real exposure is.** Three concrete consequences, all of which are about *absence* of
rejection rather than a bad rewrite:

1. **The sanitiser is not on the URL-taking backend paths at all.** `wn5.v()` (§B-1) and
   `to3.java:403` (§B-1) hand the raw string straight to `rss_proxy.php` / `search_podcast_by_url.php`
   with no `e0()`, no `f0()`, no `X()`. Any scheme- or host-level rule the app *does* have is
   bypassed by those two endpoints by construction.
2. **`fo5.d0()` fetches scheme-less hostnames over cleartext.** `fo5.java:1172` (default-mode numbering)
   ```java
           return (strTrim.startsWith("/") || V(strTrim)) ? strTrim : "http://".concat(strTrim);
   ```
   `fo5.d0()` is the first stage of `fo5.e0()`, i.e. of every URL the user or the OPML importer
   supplies. A feed that advertises `example.com/feed` (no scheme) or an OPML `<outline
   xmlUrl="example.com/feed">` is fetched over **`http://`**, and — per §H-1 — the app's network
   security config permits cleartext globally, so an on-path attacker can substitute the feed
   content for any such subscription. (The app also has an `Upgrade-Insecure-Requests` hint
   (`fo5.java:391-394`), but a redirect to `https://` requires the malicious server to cooperate.)
3. **Feed-derived links are opened in the browser unsanitised.** `z8.J0()`:

   `sources/defpackage/z8.java:467-478`
   ```java
       public static boolean J0(Context context, String str, boolean z) {
           if (context == null) {
               context = PodcastAddictApplication.E();
           }
           if (context != null && !TextUtils.isEmpty(str)) {
               String strF0 = fo5.f0(str, null, false);
               String str2 = ld.a;
               Intent intent = new Intent("android.intent.action.VIEW", Uri.parse(strF0));
               intent.setFlags(268435456);
               try {
                   context.startActivity(intent);
                   return true;
               }
   ```

   Called with feed-derived strings from at least six sites (`ba0.java:119,153`, `c2.java:167`,
   `c72.java:484`, `ks4.java:96`, `lm3.java:57`, `om3.java:25`, `o05.java:61,84`, `pn.java:31`).
   A podcast whose "link" or description-URL metadata points at a hostile site makes the app hand the
   user to that site with the app's own UI framing (`z8.J0(activity, url, true)` shows an error
   dialog on failure). Low severity (the user sees the destination) but it is an SSRF-adjacent
   open-redirect that a feed fully controls.

**Impact.** The realistic impact of §F is (2) and (3): cleartext feed fetch on a missing scheme, and
feed-controlled URLs opened in the browser. The download-engine file-read hypothesis does **not**
survive the code — I record that as a negative result below so the work is not redone.

**Remediation (client-side, vendor).**
1. Make `fo5.f0()` a *validator*: on an unrecognised scheme, return `null` (or the original with a
   hard failure flag) and have callers drop the enclosure. Specifically: accept only `https`/`http`
   (and `ftp`/`rtsp`/`sftp` only if the player really supports them — the media3 setup in §6.2 does
   not); reject leading-`/` and `//` inputs unless a `baseUrl` is supplied and the result is
   absolute.
2. Change `fo5.java:1172` (default-mode numbering) so a scheme-less input gets `https://`, not `http://`. There is no podcast
   feed that requires cleartext.
3. Call `fo5.f0()`/`fo5.e0()` in `wn5.v()` and `to3.java:403` before the URL leaves the device.

**Responsible-disclosure note.** Client-side (vendor's code). Nothing in this section required a
live probe, and the destructive variant is not reachable, so none was made.

---

## G. Client-side SQL injection — checked, and clean

**Severity: none found.** The internals report's §8 (raw SQLite, schema v135, `setMaxSqlCacheSize(25)`)
invites this, so I traced every concatenation I could find.

**CONFIRMED clean** by inspection. There are 9 `rawQuery` sites whose SQL is built with `+`, and
every one of them either interpolates a **numeric** value, a **compile-time constant**, or
`?` placeholders generated in a loop:

| Site | What is concatenated | Verdict |
|---|---|---|
| `an3.java:802` | nothing external — a pure string literal | clean |
| `oh3.java:462` | `" … IN (" + sb + ")"` where `sb` is built by `sb.append('?')` in a loop (`oh3.java:453-459`) | **clean, correctly parameterised** |
| `so3.java:345` | `"PRAGMA table_info(" + str + ")"` — the 4 `str` arguments are hard-coded literals (`so3.java:293,300,303`), all in `onUpgrade` | clean |
| `wf3.java:877` | `"select " + yu0.M + " from …"` — `yu0.M` is a constant column list; the filter is `?` | clean |
| `yu0.java:1479,1600,1757` | `" … WHERE " + H0(j)` — `H0(long)` is a filter built from constants + promoted to a bound arg | clean |
| `yu0.java:4896` | `+ H` where `H = "downloaded_status_int = 2 "` (`yu0.java:87`, a `static final` constant) | clean |
| `yu0.java:4902` | the constant `strConcat` above + a literal suffix | clean |
| `yu0.java:5389-5394` | `lp2.g(j, " and playbackDate >= ")` where `j` is a **`long`** — a `long` cannot carry a quote | clean |
| `BookmarksListActivity.java:101`, `FilteredEpisodeListActivity.java:367` | `yu0.v3(str, "E.")` — see below | clean |

`yu0.v3(String, String)` is the search-text builder, and it uses Android's literal-escaping helper:

`sources/defpackage/yu0.java:923`
```java
                        DatabaseUtils.appendEscapedSQLString(sb2, "%" + strU.replace(" ", "%") + '%');
```
(and identically at `yu0.java:946`, `:958`, `:1100`, `:1125`, `:5156`, `dm3.java:280`,
`EpisodeListActivity.java:239`, `TeamPodcastListActivity.java:231`). `appendEscapedSQLString` doubles
embedded quotes and wraps the result in quotes, which is the sanctioned way to hand a `LIKE` pattern
into a statement. Every other user/feed-derived string in the schema goes through `?`-bound selection
args (`bd0.java:262,367,508`, `hc1.java:3751`, `of5.java:119`, `wn5.java:2836`, `bd0.java:566`,
where `yu0.o(Collection)` at `yu0.java:725-742` emits `" IN (" + long + long … )` from `Long` objects
— numeric only).

Checking the `execSQL` sites: the ~15 concatenated ones are all `UPDATE … = ?` with bound `Object[]`
args (`b4.java:446`, `mp3.java:654,656,718`, `oh3.java:73,194,526,551,553` — the last two interpolate
`System.currentTimeMillis()`), `DROP TABLE IF EXISTS`/`CREATE TABLE` with internal table names
(`ci7.java:458-459`, `hl4.java:614-615,1377`, media3/androidx internals), or `yu0.o()`-built numeric
`IN` clauses.

**Conclusion.** `setMaxSqlCacheSize(25)` and raw SQLite are code-quality concerns, not security ones
here. No client-side SQL injection found. Recorded so this is not re-done.

---

## H. Other

### H-1. Cleartext HTTP is permitted globally

**Severity: Medium** — every feed and every ad/analytics call can be downgraded by an on-path
attacker, and combining this with §F-2 makes feed substitution easy.

**CONFIRMED** (decoded resource). The whole file:

`resources/res/xml/network_security_config.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="user"/>
            <certificates src="system"/>
        </trust-anchors>
    </base-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">127.0.0.1
        </domain>
        <trust-anchors>
            <certificates src="user"/>
            <certificates src="system"/>
            <certificates src="@raw/isrg_root_x1"/>
            <certificates src="@raw/isrg_root_x2"/>
            <certificates src="@raw/lets_encrypt_r10"/>
        </trust-anchors>
    </domain-config>
</network-security-config>
```

Two things stand out:
- `<base-config cleartextTrafficPermitted="true"/>` — no domain restriction, so **any** host may be
  spoken to over cleartext. The only exception to this in the whole file is none.
- `<certificates src="user"/>` in **both** blocks — the app trusts the user's installed CA store.
  On a device with a corporate or malware-injected root CA (or a debugging proxy whose cert the user
  installed), every HTTPS connection the app makes — including the backend key
  `X-App-Key: d2fad335-…` and the Google Sign-In ID token at `j25.java:80` — is interceptable. This
  is deliberate for the old-Android dev-cert case (`ye4.java`'s hand-maintained bundle, §3.5), but it
  is shipped to every user.

The `127.0.0.1` domain-config duplicating the user-CA trust anchors and adding the three Let's
Encrypt roots is fine in itself (local debug servers), but it is inconsistent: a loopback domain is
given a bespoke trust config while the entire internet is given cleartext.

**Impact.** Feed content substitution for any `http://` feed or any scheme-less URL (§F-2) — the
attacker's feed then flows straight into the XXE sink of §A-2. Plus interception of telemetry and,
on a device with a user-installed root, of the Google ID token used for cross-device sync.

**Remediation (client-side, vendor).**
1. Set `<base-config cleartextTrafficPermitted="false"/>`.
2. Move `<certificates src="user"/>` out of `base-config` into a narrowly scoped `domain-config` for
   the specific hosts that actually need it, or drop it entirely — `ye4.java`'s backported root
   bundle covers the old-device case without weakening TLS for everyone.
3. Change `fo5.java:1172` (default-mode numbering) to `https://` (§F).

### H-2. Insecure randomness — checked, and clean for security purposes

**CONFIRMED** (grep). `new Random()` appears in 20 places and `new SecureRandom()` in 9. Every
`java.util.Random` use is in a **third-party** library (`xy.java:76` — Google Play Billing;
`bz4`, `ep4`, `nha`, `oda`, `oy0`, `vh8`, `w2a` — media3/androidx scheduling helpers;
`h82.java:137`, `wh9.java:575`, `j57.java:*` — ad-SDK sampling and ExoPlayer backoff jitter). None
of them is in `com/bambuna`, and none feeds a credential. The app's own security-relevant
identifiers use `SecureRandom`/`UUID.randomUUID()`: `c71.java:796` (ad segment id),
`hl4.java:1397` (a 12-byte seed), `oba.java:18` (`UUID.randomUUID()` +
`SecureRandom.nextLong()`), `r06.java:8` and `x73.java:11` (`static final SecureRandom`). Note
`a.i1()`'s `UUID.randomUUID()` (`helper/a.java:1384`) — on Android `UUID.randomUUID()` delegates to
`SecureRandom`, so the *random half* of `userUUID` is strong; the weakness identified in §C-1 is
that the identifier is not kept secret, not that it is guessable.

**Clean.** No action.

### H-3. Passcode / biometric app-lock — **not implemented**

**CONFIRMED** (absence). `grep -rin "passcode\|biometric\|applock"` over all 17 228 sources and the
decoded `strings.xml` returns **no hits**. The manifest declares no
`android.permission.USE_BIOMETRIC` / `USE_FINGERPRINT`. The only app "lock" preferences are
`pref_lockScreenWidgetEnabled` and `pref_lockScreenWidgetArtworkEnabled`
(`vt3.java:694,698`; `rv2.java:245`; `rn.java:231`; `ib0.java:468`) — lock-*screen widget* artwork,
not app locking. So the answer to the brief's question is: there is nothing to bypass, because the
feature does not exist. (The prior internals report does not claim otherwise; recording the check so
it is not repeated.)

### H-4. The media-browser caller allowlist is **enforced**, not advisory

**CONFIRMED** (code + resource). See §D-2. `AndroidAutoMediaBrowserService.onGetRoot` resolves the
caller's package, its single signer's SHA-256, its UID and its requested permissions, and matches
against the signatures parsed from `res/xml/allowed_media_browser_callers.xml`
(`AndroidAutoMediaBrowserService.java:173-196`); non-matching callers are handed an empty browser
root (`:222-228`), which is the correct fail-closed behaviour. **This is the one place in the app
where a signature allowlist is actually enforced in code**, and it can be used as the model for the
`ArtworkFileProvider` fix in §D-1.

### H-5. Backup surface — `allowBackup="true"` with no exclusion of credentials

**Severity: Low-Medium.**

**CONFIRMED** (`resources/AndroidManifest.xml:195-200`, `resources/res/xml/auto_backup_scheme.xml`).
`android:allowBackup="true"` + `android:fullBackupContent="@xml/auto_backup_scheme"`, whose entire
content is:
```xml
<full-backup-content>
    <exclude domain="file" path="podcast"/>
    <exclude domain="external" path="podcast"/>
</full-backup-content>
```
Only the downloaded-podcast media is excluded. Everything else travels: `podcastAddict.db` (feed
URLs, private-feed URLs, listening stats), `shared_prefs` including `pref_uuid` (§C-1) and
`pref_userTokenId`, and any cached backend responses. On a shared or restored device that is a
confidentiality exposure, and it is precisely the channel that makes `userUUID` a non-secret.

**Remediation (client-side, vendor).** Add `<exclude domain="sharedpref" path="."/>` (or at least
the keys holding `pref_uuid`, `pref_userTokenId`, `pref_FCMToken`) and `<exclude domain="database"
path="podcastAddict.db"/>` unless a database restore is a required feature.

---

## Checked and found clean

Recorded so this work is not repeated. Each was verified by reading the code or the decoded resources,
not inferred.

1. **Client-side SQL injection** (§G). All 9 concatenated `rawQuery` sites and ~15 concatenated
   `execSQL` sites are numeric, constant, or `?`-bound. `appendEscapedSQLString` is used correctly
   everywhere user text reaches a `LIKE`.
2. **JS bridge + remote content in a WebView** (§E). No reachable WebView has both. The one with a
   bridge (`ImageSearchActivity`) is `exported="false"` and loads an app-built URL; the ones that load
   remote attacker-chosen URLs (`TranscriptWebViewActivity`, `TestRSSFeedActivity`) have no bridge.
3. **Non-HTTP scheme reaching the download engine as a file read** (§F). `fo5.f0()`'s catch-all
   `"https://".concat(r2)` mangles `file://`/`content://`/`intent://` before it can reach the
   downloader, and the one local-path consumer (`hc1.k1`) only refuses to share.
4. **`userUUID` guessability.** `UUID.randomUUID()` on Android is `SecureRandom`-backed, and
   `helper/a.java:1384` composes it with `ANDROID_ID`. The problem in §C-1 is non-secrecy and backup
   exposure, not entropy.
5. **Insecure randomness in the vendor's own code** (§H-2). Every `java.util.Random` is in a
   third-party library; the app's identifiers use `SecureRandom`.
6. **Passcode / biometric app lock** (§H-3) — the feature does not exist in this build.
7. **Media-browser caller allowlist** (§H-4 / §D-2) — properly enforced with signature, UID and
   permission checks, and fails closed to an empty root.
8. **`AndroidAutoMediaBrowserService` being exported without a permission** — fine, because of #7.
9. **Exported widget services and the QuickSettings tile** — correctly guarded by
   `BIND_REMOTEVIEWS` / `BIND_QUICK_SETTINGS_TILE`.
10. **`PodcastAddictMediaButtonReceiver`** — exported as required for `MEDIA_BUTTON`; this is the
    correct configuration, not a bypass.
11. **`ChromecastMediaButtonReceiver`** — the internals report flags it as exported; the decoded
    manifest says `exported="false"` (`AndroidManifest.xml:1507-1508`). Correction, not a finding.
12. **Intent redirection through exported activities.** No
    `getParcelableExtra`/`getStringExtra`-fed-to-`startActivity` pattern was found in the decompiled
    sources; the two exported file-browser activities read only boolean extras
    (`BackupFileBrowserActivity.java:65-69`) and no extras at all
    (`BitmapFileBrowserActivity`), and derive their root folder from app state
    (`PodcastAddictApplication.E().T0` / `helper.a.z()`), never from the caller.
13. **`PodcastPrivacyActivity` is not a WebView** (§E-4) — correcting the internals report.
14. **The Google API key** (`AIzaSyBicrf4tD7I0dtz8TlWRgaS5oUhxa6aLZA`, `db4.java:197`) is exposed in
    the APK and usable by anyone — that is confirmed by its presence as a string literal, and I did
    not enumerate its scopes beyond confirming it is a live Custom Search key. The vendor should
    restrict it by HTTP referrer and IP in Google Cloud Console, or replace the client-side image
    search with a server-side proxy. No action taken here beyond the exposure confirmation.
15. **External DTD amplification via `hp0`** — the resolver returns an inline stub for exactly one
    system id and `null` for everything else, so it is not itself an SSRF; the residual risk is the
    platform's handling of a `null` return (§A-3).

---

## Scope, method and limits

**What I did.** Static analysis of the decompiled APK and the decoded manifest/resources; one class
(`defpackage.fo5`) re-rendered with `jadx -m simple` because its default-mode body for `f0()` was
skipped. Twelve HTTP requests against the live backend, all in the "malformed parameter returns an
error / does not return another party's data" category — see §B-1 for the raw responses. Nothing was
written, deleted or modified. No enumeration, no other user's data, no injection payload executed
against the backend, no metadata-endpoint probing, no use of the Google API key beyond noting its
presence as a string literal.

**What I could not establish.**

1. **What Android's `SAXParserFactory` actually does with external entities in this configuration.**
   The implementation lives in the device's `libcore`, not in the APK, so the *file-read* variant of
   A-1 cannot be settled from the binary. **Confirmation cost:** one local parse of a crafted OPML
   containing an external `SYSTEM` entity and a `file://` or `http://` target, on a device, with
   logcat watching for `hp0.resolveEntity` being hit. It is a five-minute, non-destructive test — it
   needs only a device, which I did not have.
2. **Whether the platform's Expat entity-expansion caps are engaged on API 26→36.** Expat has
   billion-laughs protection with implementation-specific defaults, so the DoS impact of A-1 is
   bounded by something I cannot read. **Confirmation cost:** the same local parse, timing a nested
   entity document.
3. **The destructive half of C-1.** That `reset_user_registrations.php` will actually reset an
   arbitrary install given someone else's `userUUID`. **Confirmation cost:** one request with a real
   `userUUID` — i.e. it cannot be confirmed without either the vendor's cooperation or a destructive
   write.
4. **Whether `rss_proxy.php`'s token requirement is the steady state or a partial deploy.** I
   observed `401 missing token` on two requests minutes apart. **Confirmation cost:** replay the same
   probe later, and/or ask the operator which routes are behind the token gate.
5. **The 40 `PodcastAddictPlayerReceiver` actions' handlers** — whether any reads a URL or path from
   its extras and starts playback or a download. **Confirmation cost:** a runtime fuzz of those
   actions on a device with the app installed.
6. **Server behaviour generally.** Response schemas, rate limits and error semantics are the
   operator's; §B-1 and §C-1's probes only reached parameter-validation. I did not attempt to map the
   backend.
7. **Native code.** `libsonic.so`, `libgenius_blur.so`, `libdatastore_shared_counter.so` and
   `libapplovin-native-crash-reporter.so` were not examined.
8. **The ad SDKs' WebViews.** 47 of them have JS bridges; I did not audit their exposed methods
   (§E-5 flags the AppLovin configurable file-access flags as the one to watch).

**Timelines and disclosure.** Both the client-side and backend-side findings need coordinated
disclosure: the shared API key, the `ArtworkFileProvider` export and the SAX hardening are
vendor-side; the `userUUID` authorisation model and the proxy's egress controls are operator-side.
I have not contacted either party as part of this task, and no proof-of-concept exploit code was
written — the findings are all established from code and, where noted, a malformed-parameter probe.

---

*Findings are written incrementally to this file as they were established; commits `407a9f4` (A),
`828599c` (B), `be73db1` (C), `435622b` (D), `a71bc42` (E), `9d0f9b0` (F/G/H) on branch `vuln2`.
Nothing was pushed or merged. The APK was copied to a scratch directory for one `jadx` re-render and
is never staged or committed.*
