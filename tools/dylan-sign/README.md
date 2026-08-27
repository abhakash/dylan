# dylan-sign — MacBook-only iOS signer (AltStore-style)

Signs unsigned Dylan IPA with **free Apple ID** (7-day profile, 3-app slot) without asking 2FA every time.

> **How 2FA is avoided after first run:** First `login` does GSA SRP + 2FA, then stores `DSID+authToken+anisette` in `session.json` (0600) + Keychain copy. Later `sign`/`refresh` reuse that session until expiry (~30d). Only when token expires does `refresh` silently re-auth via stored password (Keychain); otherwise no prompt. Provision profiles (7d) are re-fetched before expiry.

## Requirements

- **macOS only** (`darwin/arm64` — uses local `AOSKit`-style anisette via `ioreg`/`sysctl` synthetic fallback, no remote server needed)
- Go 1.22+
- `zsign` (SHA256-only via `-2`, see `zhlynn/zsign#391`) — `brew install zsign` (optional; fallback is `codesign` stub that copies IPA for pipeline testing)
- Xcode Command Line Tools (`codesign --verify`)

## Build

```bash
cd tools/dylan-sign
go mod tidy
go build ./... && go vet ./...
go build -ldflags "-X main.Version=0.1.0" -o /tmp/dylan-sign ./cmd/dylan-sign
/tmp/dylan-sign version # dylan-sign 0.1.0-dev (darwin/arm64)
```

## Quick start (placeholders until you supply real IDs)

```bash
# one-time: stores Apple ID + synthetic session (real GSA wired via auth/gsa.go TODO)
./dylan-sign login --apple-id "__APPLE_ID_PLACEHOLDER__" --team "__TEAM_ID__"
# optionally: ./dylan-sign login --apple-id you@icloud.com --password '...'  (stored in Keychain file fallback 0600)

# sign unsigned IPA produced by ios-free lane (must be unsigned, no _CodeSignature)
# unsigned IPA is built by: xcodebuild ... CODE_SIGNING_ALLOWED=NO -> Payload/iosApp.app -> zip
./dylan-sign sign --ipa /tmp/Dylan-unsigned-real.ipa --apple-id test@example.com --udid 00008030-00123456789ABC --out /tmp/Dylan-signed.ipa
# bundle auto-derived as app.dylan.player.ios.<user> sanitized (e.g. test@example.com -> app.dylan.player.ios.test)
# override: --bundle-id app.dylan.player.ios.abhakash --p12 /path/cert.p12 --profile /path/profile.mobileprovision

./dylan-sign status
./dylan-sign refresh --apple-id test@example.com --udid 00008030-00123456789ABC  # re-fetches profile if <24h left
./dylan-sign --debug  # prints anisette JSON + X-Apple-I-MD-* headers
```

## Commands

| Cmd | What |
|-----|------|
| `login --apple-id --team [--password]` | Fetch darwin anisette, store config, create placeholder session (real GSA SRP in `internal/auth/gsa.go` TODO, then `authToken` caching) |
| `sign --ipa <unsigned> --udid <UDID> [--apple-id --bundle-id --out --anisette --p12 --profile --strict]` | Anisette → session reuse → provision stubs (`QH65B2` teams/devices/AppIDs `app.dylan.player.ios.<user>` → profile) → `zsign -k -m -b -o -2` SHA256-only or `codesign` stub → `Dylan-signed.ipa` |
| `sign --strict` (or automatic when real `--apple-id` + `--udid` are given) | Hard-fail on placeholder IDs, copy-stub signing, and `codesign --verify --deep` errors on the extracted `.app` (default non-strict only warns) |
| `install --ipa <signed> --udid <UDID>` | **Stub (phase-1):** no go-ios vendored — probes PATH for `ideviceinstaller` (`-u <UDID> -i <IPA>`) then `ios-deploy` (`--id/--bundle`); if neither exists it returns a helpful error. The usbmuxd → lockdown → `installation_proxy` flow is documented in `internal/install/install.go` but not yet wired. |
| `status` | Config + `session.json` DSID/expiry + anisette + Keychain path |
| `refresh --apple-id --udid` | Checks session expiry (silent Keychain re-auth if needed) + profile `Expiration` (7d free). If <24h, would re-fetch & re-sign. Stub logs endpoint intent. |

Env overrides: `DYLAN_APPLE_ID`, `DYLAN_UDID`, `DYLAN_TEAM` over `~/.dylan-sign/config.yaml`.
`DYLAN_KEYCHAIN` points `security import` at a custom keychain (e.g. CI `build.keychain`);
default is `~/Library/Keychains/login.keychain-db`.

Placeholder vs strict: without real IDs the tool runs offline (placeholder session,
synthetic anisette, copy-stub sign) and placeholder bundle suffixes get a random
component so users never collide. With real `--apple-id` + `--udid` strict turns on
automatically (or pass `--strict`): placeholders, copy stub, and verify failures
are fatal.

Anisette header convention (AltStore): `X-Apple-I-MD-M` = machine ID,
`X-Apple-I-MD` = one-time password — shared by `anisette.Data.ToHeaders` and
`provision.API.buildHeaders` (covered by `TestToHeadersMachineMapping`).

## Layout

```
~/.dylan-sign/
  config.yaml        # apple_id, team_id, udid
  session.json       # DSID, authToken, anisette, CreatedAt/ExpiresAt (0600)
  keychain.json      # file fallback for Keychain (0600) when go-keyring not present
  certs/             # .p12 per team (future)
  profiles/          # .mobileprovision per AppID (future)
tools/dylan-sign/
  cmd/dylan-sign/main.go        # cobra wiring, bundle sanitization
  internal/anisette/            # darwin provider (ioreg IOPlatformUUID + sysctl hw.model) + remote HTTP (CI stretch)
  internal/auth/                # session save/load, keychain, GSA SRP stub (ALTAppleAPI+Authentication.m port)
  internal/provision/           # QH65B2 API stubs: listTeams/listDevices/addDevice/submitDevelopmentCSR/listAppIds/addAppId/downloadTeamProvisioningProfile (ALTAppleAPI.m:57-872)
  internal/sign/                # zsign wrapper (-2 SHA256-only per #391) + bundle unzip/zip + pipeline (ValidateIPA unsigned, RunPipeline)
  internal/bundle/              # UniqueBundleID sanitizes email local part -> [a-z0-9-]
  internal/store, config
```

## AltStore internals cloned (research in /tmp)

- `AltSign/AltSign/Apple API/ALTAppleAPI.m` `baseURL QH65B2` + `X-Apple-I-MD*` headers → ported to `provision/api.go` `buildHeaders`/`sendRequest`
- `ALTAnisetteData.m` 10-field struct → `internal/anisette.Data`
- `ALTAppleAPI+Authentication.m` SRP s2k/s2k_fo + `gsa.apple.com/grandslam/GsService2` → `internal/auth/gsa.go` stub with `ErrInteractiveLogin`
- `ALTSigner.mm` `ldid::Sign` + `CertificatesContent` → replaced by `zsign -2` (modern SHA256-only, iOS 17+/M1-M4 valid)

## Next (phase 2 — install)

`internal/install` will use `danielpaulus/go-ios` (`idevice`/`installation_proxy`) for USB/Wi-Fi install + `launchd` plist for daily `refresh` (like SideStep). CI stretch: same binary on `macos-14` GH runner with remote anisette URL secret + `DYLAN_*` secrets.

> Honesty note: `install` today is a stub, not Xcode-equivalent — it shells out to
> `ideviceinstaller`/`ios-deploy` when present and otherwise errors with setup
> instructions. `provision` Developer Portal calls log endpoint intent and return
> placeholder teams/devices/profiles until GSA SRP is wired (`auth/gsa.go` TODO).
> `ZipIPA` follows symlinks (does not preserve them) — re-zip Framework-heavy
> bundles with `ditto`/`zip --symlinks` on macOS for device installs.

## Module

`github.com/abhakash/dylan/tools/dylan-sign` — `go 1.22`, deps `cobra`, `yaml.v3`, `google/uuid`
