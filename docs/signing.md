# Signing (iOS) — free tier, placeholder vs strict, bundle IDs

Tool: `tools/dylan-sign` (Go, macOS-first). Unsigned `.ipa` comes from the
`ios-free` lane (`xcodebuild CODE_SIGNING_ALLOWED=NO` → `Payload/*.app` → zip).

## Free-tier constraints (personal Apple ID, no paid cert)

- Provisioning profile lifetime: **7 days** — re-run `dylan-sign refresh` before
  expiry (re-fetches when < 24 h remain; `launchd`-daily is the intended loop).
- **3-app slot** per free Apple ID — sideloaded apps compete for slots.
- Anisette is machine-local (AOSKit probe with synthetic fallback on macOS,
  `--anisette <url>` remote provider for CI). Header convention (AltStore):
  `X-Apple-I-MD-M` = machine ID, `X-Apple-I-MD` = one-time password.

## Placeholder vs strict

| Mode | When | Behaviour |
|------|------|-----------|
| Placeholder (offline) | `--apple-id`/`--udid` omitted | Synthetic session + anisette, Developer Portal calls skipped, copy-stub sign (copies IPA, warns). Bundle suffix gets a **random** component so placeholder users never collide. |
| Strict | `--strict`, or automatically when **real** `--apple-id` + `--udid` are given | Hard-fail on placeholder IDs, on copy-stub signing (real `zsign`/`codesign` identity required), and on `codesign --verify --deep` errors against the **extracted `.app`** (never the `.ipa` zip). |

```bash
# offline / dry-run (placeholder)
dylan-sign sign --ipa /tmp/Dylan-unsigned.ipa --out /tmp/Dylan-signed.ipa
# real (strict auto-on)
dylan-sign sign --ipa /tmp/Dylan-unsigned.ipa --apple-id you@icloud.com \
  --udid 00008030-00123456789ABC --out /tmp/Dylan-signed.ipa
# explicit
dylan-sign sign --strict --ipa … --apple-id … --udid … --p12 cert.p12 --profile prof.mobileprovision
```

Input must be unsigned: no `_CodeSignature` and no `embedded.mobileprovision`
(`isIPAUnsigned` rejects either). `DYLAN_KEYCHAIN` points `security import` at a
custom keychain (CI `build.keychain`); default is `login.keychain-db`.

## Bundle-ID table

| Surface | Bundle ID | Source |
|---------|-----------|--------|
| Android (`applicationId`) | `app.dylan.player` | `androidApp/build.gradle.kts` |
| Xcode (`PRODUCT_BUNDLE_IDENTIFIER`) | `app.dylan.player.ios` | `iosApp.xcodeproj` |
| `dylan-sign` default base | `app.dylan.player.ios` | `internal/bundle.BaseBundleID` |
| Signed per-user | `app.dylan.player.ios.<user>` | local part of Apple ID, sanitized to `[a-z0-9-]` (e.g. `you@icloud.com` → `app.dylan.player.ios.you`); `--bundle-id` overrides (base alone is re-suffixed; empty/placeholder input gets `unknown-<rand>`) |
| Stub/example (never ship) | `app.dylan.player.ios.example` | `FetchAppIDs` placeholder |

## Honesty notes (what is still stub)

- `provision` Developer Portal calls (`QH65B2`) log endpoint intent and return
  placeholder teams/devices/profiles until GSA SRP is wired (`auth/gsa.go` TODO).
- `install` is **not** Xcode-equivalent yet: it probes for `ideviceinstaller` /
  `ios-deploy` and otherwise errors with setup instructions. The
  usbmuxd → lockdown → `installation_proxy` flow is documented in
  `internal/install/install.go` for the go-ios phase-2 wiring.
- `ZipIPA` follows symlinks (stores them as regular files); re-zip
  Framework-heavy bundles with `ditto -c -k` / `zip --symlinks` on macOS.
