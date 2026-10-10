package sign

import (
	"bytes"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

// Options holds inputs for IPA signing via zsign or codesign fallback.
// Designed to match: zsign -k p12 -p pwd -m profile -b bundleID -o out -2 input
// For details see zhlynn/zsign #391 (SHA256-only via -2).
type Options struct {
	InputIPA    string
	OutputIPA   string
	P12Path     string
	ProfilePath string
	BundleID    string
	Password    string
	SHA256Only  bool
	// Strict disables all silent fallbacks: no copy-stub when backends are
	// missing or the real branch fails — Sign returns a hard error instead.
	Strict bool
}

// IsAvailable reports whether a signing backend is present on this host.
// On macOS it checks for zsign in PATH first, then codesign as fallback.
// Returns false on non-darwin when zsign is absent.
func IsAvailable() bool {
	if _, err := exec.LookPath("zsign"); err == nil {
		return true
	}
	if runtime.GOOS == "darwin" {
		if _, err := exec.LookPath("codesign"); err == nil {
			return true
		}
	}
	return false
}

// Sign validates inputs and attempts to sign the IPA.
//
// Order:
//  1. zsign if available: `zsign -k p12 -p pwd -m profile -b bundleID -o out [-2] input`
//     (-2 enforces SHA256-only per zhlynn/zsign #391 when SHA256Only is set)
//  2. fallback to codesign on macOS (stub): `codesign -f -s identity --entitlements ...`
//  3. if neither backend is available, returns error with install instructions.
func Sign(opts Options) error {
	if err := validateOptions(opts); err != nil {
		return err
	}

	// 1. Try zsign if present in PATH.
	if _, err := exec.LookPath("zsign"); err == nil {
		args := buildZsignArgs(opts)
		cmd := exec.Command("zsign", args...)
		out, err := cmd.CombinedOutput()
		if err == nil {
			return nil
		}
		zErr := fmt.Errorf("zsign failed: %w: %s", err, string(out))
		// Attempt codesign fallback on macOS before returning zsign error
		// (unless strict: surface the zsign failure directly).
		if runtime.GOOS == "darwin" {
			if _, lookErr := exec.LookPath("codesign"); lookErr == nil {
				if opts.Strict {
					return zErr
				}
				if codeErr := tryCodesign(opts); codeErr == nil {
					return nil
				} else {
					return fmt.Errorf("%v; codesign fallback also failed: %v", zErr, codeErr)
				}
			}
		}
		return zErr
	}

	// 2. Fallback to codesign on macOS.
	if runtime.GOOS == "darwin" {
		if _, err := exec.LookPath("codesign"); err == nil {
			if err := tryCodesign(opts); err != nil {
				return err
			}
			return nil
		}
	}

	// 3. Neither available.
	return fmt.Errorf("no signing tool available: install zsign (https://github.com/zhlynn/zsign) via 'brew install zsign' or via 'go install github.com/zhlynn/zsign@latest' (and ensure it is in PATH), or ensure Xcode Command Line Tools are installed for 'codesign' (xcode-select --install) on macOS; attempted 'zsign -k %q -p *** -m %q -b %q -o %q [-2] %q' and 'codesign -f -s <identity> --entitlements <path> <app>'", opts.P12Path, opts.ProfilePath, opts.BundleID, opts.OutputIPA, opts.InputIPA)
}

func validateOptions(opts Options) error {
	if opts.InputIPA == "" {
		return fmt.Errorf("InputIPA is required")
	}
	if opts.OutputIPA == "" {
		return fmt.Errorf("OutputIPA is required")
	}
	if _, err := os.Stat(opts.InputIPA); err != nil {
		return fmt.Errorf("input ipa not found at %q: %w", opts.InputIPA, err)
	}
	if opts.P12Path != "" {
		if _, err := os.Stat(opts.P12Path); err != nil {
			return fmt.Errorf("p12 not found at %q: %w", opts.P12Path, err)
		}
	}
	if opts.ProfilePath != "" {
		if _, err := os.Stat(opts.ProfilePath); err != nil {
			return fmt.Errorf("profile not found at %q: %w", opts.ProfilePath, err)
		}
	}
	return nil
}

func buildZsignArgs(opts Options) []string {
	var args []string
	if opts.P12Path != "" {
		args = append(args, "-k", opts.P12Path)
	}
	if opts.Password != "" {
		args = append(args, "-p", opts.Password)
	}
	if opts.ProfilePath != "" {
		args = append(args, "-m", opts.ProfilePath)
	}
	if opts.BundleID != "" {
		args = append(args, "-b", opts.BundleID)
	}
	if opts.OutputIPA != "" {
		args = append(args, "-o", opts.OutputIPA)
	}
	// zhlynn/zsign #391: -2 forces SHA256-only (avoids SHA1). Only add when requested
	// to preserve default zsign behaviour unless caller opts in.
	if opts.SHA256Only {
		args = append(args, "-2")
	}
	args = append(args, opts.InputIPA)
	return args
}

// tryCodesign attempts real codesign when P12/profile are provided, otherwise falls
// back to copy stub. Xcode's codesign (12+) defaults to SHA256 digest, so no extra
// flag is required — documenting here that SHA256-only is implicit (zsign needs -2,
// codesign does not).
// In strict mode there is no copy-stub fallback: missing identity material or a
// failed real branch is a hard error. Otherwise a warning goes to stderr and the
// copy stub preserves previous pipeline-testing behaviour.
func tryCodesign(opts Options) error {
	// 1. Offline ad-hoc case: no P12/profile.
	if opts.P12Path == "" && opts.ProfilePath == "" {
		if opts.Strict {
			return fmt.Errorf("strict: real signing identity required (no --p12/--profile); refusing copy stub")
		}
		fmt.Fprintf(os.Stderr, "warning: codesign: no P12/profile provided (offline ad-hoc) — using copy stub (not Xcode-real signed)\n")
		return copyStub(opts)
	}
	// 2-6. Attempt real codesign branch.
	if err := tryRealCodesign(opts); err != nil {
		if opts.Strict {
			return fmt.Errorf("strict: real codesign failed (no copy-stub fallback): %w", err)
		}
		fmt.Fprintf(os.Stderr, "warning: codesign real branch failed (%v) — fallback to copy stub\n", err)
		return copyStub(opts)
	}
	return nil
}

// copyStub copies InputIPA to OutputIPA without modifying contents.
// Preserves previous stub behaviour for pipeline testing and offline mode.
func copyStub(opts Options) error {
	if opts.InputIPA == opts.OutputIPA {
		return nil
	}
	data, err := os.ReadFile(opts.InputIPA)
	if err != nil {
		return fmt.Errorf("codesign stub: failed to read input %q: %w", opts.InputIPA, err)
	}
	if err := os.WriteFile(opts.OutputIPA, data, 0o644); err != nil {
		return fmt.Errorf("codesign stub: failed to write output %q: %w", opts.OutputIPA, err)
	}
	return nil
}

// tryRealCodesign performs the full Xcode-close codesign flow:
//
//  1. security import <p12> -k ~/Library/Keychains/login.keychain-db -P password -T /usr/bin/codesign (best-effort)
//  2. Extract identity via `security find-identity -v -p codesigning` or openssl fallback
//  3. Embed provisioning profile via EmbedProvisioningProfile (already in bundle.go)
//  4. Derive entitlements via `security cms -D -i <profile> | plutil -extract Entitlements xml1 -o - -`
//  5. Invoke `codesign -f -s "<identity>" --entitlements /tmp/entitlements.plist <appPath>` for app + frameworks
//
// SHA256-only is implicit on modern codesign; no extra flag needed.
func tryRealCodesign(opts Options) error {
	// 2. Import P12 into login keychain (best-effort, ignore already exists).
	if opts.P12Path != "" {
		if err := importP12(opts.P12Path, opts.Password); err != nil {
			// Best-effort: log but continue; identity may already be present.
			fmt.Fprintf(os.Stderr, "warning: security import: %v (continuing)\n", err)
		}
	}

	// 3. Extract codesigning identity.
	identity, err := findCodesignIdentity(opts.P12Path, opts.Password)
	if err != nil {
		return fmt.Errorf("identity extraction failed: %w", err)
	}
	if strings.TrimSpace(identity) == "" {
		return fmt.Errorf("empty identity")
	}
	fmt.Fprintf(os.Stderr, "codesign: using identity %q\n", identity)

	// 5. Derive entitlements from provisioning profile (if provided).
	var entPath string
	if opts.ProfilePath != "" {
		ep, derr := deriveEntitlements(opts.ProfilePath)
		if derr != nil {
			fmt.Fprintf(os.Stderr, "warning: entitlements derivation failed: %v (continuing without entitlements)\n", derr)
			entPath = ""
		} else {
			entPath = ep
			if entPath != "" {
				fmt.Fprintf(os.Stderr, "codesign: entitlements at %s\n", entPath)
			}
		}
	}

	// Unzip IPA to temp dir.
	tmpDir, err := os.MkdirTemp("", "dylan-sign-codesign-*")
	if err != nil {
		return fmt.Errorf("mktemp failed: %w", err)
	}
	defer os.RemoveAll(tmpDir)

	unzipDir := filepath.Join(tmpDir, "unzip")
	if err := UnzipIPA(opts.InputIPA, unzipDir); err != nil {
		return fmt.Errorf("unzip failed: %w", err)
	}

	appPath, err := findAppPath(unzipDir)
	if err != nil {
		return err
	}

	// 4. Embed provisioning profile (already done via EmbedProvisioningProfile in bundle.go).
	if opts.ProfilePath != "" {
		data, err := os.ReadFile(opts.ProfilePath)
		if err != nil {
			return fmt.Errorf("read profile failed: %w", err)
		}
		if err := EmbedProvisioningProfile(appPath, data); err != nil {
			return fmt.Errorf("embed profile failed: %w", err)
		}
	}

	// Optional bundle ID rewrite (best-effort). zsign does this via -b; codesign path does via Info.plist edit.
	if opts.BundleID != "" {
		if cur, err := ReadBundleID(appPath); err == nil && cur != opts.BundleID {
			if err := UpdateBundleID(appPath, opts.BundleID); err != nil {
				// Binary plist case: try converting via plutil then retry.
				if strings.Contains(err.Error(), "binary plist") {
					plistPath := filepath.Join(appPath, "Info.plist")
					if out, cerr := exec.Command("plutil", "-convert", "xml1", plistPath).CombinedOutput(); cerr != nil {
						fmt.Fprintf(os.Stderr, "warning: plutil convert failed: %v: %s\n", cerr, string(out))
					} else {
						if uerr := UpdateBundleID(appPath, opts.BundleID); uerr != nil {
							fmt.Fprintf(os.Stderr, "warning: UpdateBundleID after convert failed: %v\n", uerr)
						}
					}
				} else {
					fmt.Fprintf(os.Stderr, "warning: UpdateBundleID failed: %v\n", err)
				}
			}
		}
	}

	// 6. Codesign frameworks then main app. SHA256 is default on modern Xcode; no flag needed.
	frameworksDir := filepath.Join(appPath, "Frameworks")
	if fi, err := os.Stat(frameworksDir); err == nil && fi.IsDir() {
		entries, rerr := os.ReadDir(frameworksDir)
		if rerr == nil {
			for _, e := range entries {
				if strings.HasSuffix(e.Name(), ".framework") && e.IsDir() {
					fwPath := filepath.Join(frameworksDir, e.Name())
					if err := codesignTarget(identity, entPath, fwPath); err != nil {
						return fmt.Errorf("codesign framework %q failed: %w", e.Name(), err)
					}
				}
			}
		}
	}

	if err := codesignTarget(identity, entPath, appPath); err != nil {
		return err
	}

	// Zip back to OutputIPA.
	if err := os.MkdirAll(filepath.Dir(opts.OutputIPA), 0o755); err != nil {
		return fmt.Errorf("failed to create output dir: %w", err)
	}
	if err := ZipIPA(unzipDir, opts.OutputIPA); err != nil {
		return fmt.Errorf("zip failed: %w", err)
	}

	return nil
}

// importP12 runs `security import <p12> -k <keychain> -P password -T /usr/bin/codesign`.
// The keychain defaults to ~/Library/Keychains/login.keychain-db but honours
// $DYLAN_KEYCHAIN when set (e.g. CI build.keychain) instead of hardcoding login.
// Best-effort: caller ignores "already exists" via error string check, but we surface other errors.
func importP12(p12Path, password string) error {
	if p12Path == "" {
		return nil
	}
	keychainPath := os.Getenv("DYLAN_KEYCHAIN")
	if keychainPath == "" {
		keychainPath = filepath.Join(os.Getenv("HOME"), "Library", "Keychains", "login.keychain-db")
		if home, err := os.UserHomeDir(); err == nil && home != "" {
			keychainPath = filepath.Join(home, "Library", "Keychains", "login.keychain-db")
		}
	}
	args := []string{"import", p12Path, "-k", keychainPath, "-P", password, "-T", "/usr/bin/codesign"}
	cmd := exec.Command("security", args...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		msg := string(out)
		if strings.Contains(msg, "already exists") || strings.Contains(msg, "SecKeychainItemAlreadyExists") {
			return nil
		}
		return fmt.Errorf("security import failed: %w: %s", err, msg)
	}
	return nil
}

// findCodesignIdentity extracts the signing identity.
// First tries `security find-identity -v -p codesigning` and parses the quoted Common Name.
// Falls back to parsing the P12 via openssl to extract CN.
func findCodesignIdentity(p12Path, password string) (string, error) {
	cmd := exec.Command("security", "find-identity", "-v", "-p", "codesigning")
	out, err := cmd.CombinedOutput()
	sout := string(out)
	// Parse even if err != nil, because exit 0 may still have 0 identities.
	if len(sout) > 0 && !strings.Contains(sout, "0 valid identities found") {
		for _, line := range strings.Split(sout, "\n") {
			if !strings.Contains(line, "\"") {
				continue
			}
			first := strings.Index(line, "\"")
			last := strings.LastIndex(line, "\"")
			if first >= 0 && last > first {
				ident := line[first+1 : last]
				if strings.TrimSpace(ident) != "" {
					return ident, nil
				}
			}
		}
	}
	// Fallback: openssl parse P12 CN.
	if p12Path != "" {
		if cn, oerr := p12CommonName(p12Path, password); oerr == nil && strings.TrimSpace(cn) != "" {
			return cn, nil
		} else if err == nil && oerr != nil {
			// security succeeded but found no identity; surface openssl error.
			err = oerr
		} else if oerr != nil {
			err = oerr
		}
	}
	if err != nil {
		return "", fmt.Errorf("no codesigning identity found: %w: %s", err, strings.TrimSpace(sout))
	}
	return "", fmt.Errorf("no codesigning identity found: %s", strings.TrimSpace(sout))
}

// p12CommonName extracts Common Name from P12 via openssl.
func p12CommonName(p12Path, password string) (string, error) {
	// openssl pkcs12 -in <p12> -nokeys -clcerts -passin pass:<password>
	cmd1 := exec.Command("openssl", "pkcs12", "-in", p12Path, "-nokeys", "-clcerts", "-passin", "pass:"+password)
	pem, err := cmd1.CombinedOutput()
	if err != nil {
		// Retry with legacy flag for newer openssl
		cmd1b := exec.Command("openssl", "pkcs12", "-in", p12Path, "-nokeys", "-clcerts", "-passin", "pass:"+password, "-legacy")
		if pem2, err2 := cmd1b.CombinedOutput(); err2 == nil {
			pem = pem2
			err = nil
		} else {
			return "", fmt.Errorf("openssl pkcs12 failed: %w: %s", err, string(pem))
		}
	}
	// Pipe PEM to openssl x509 -noout -subject
	cmd2 := exec.Command("openssl", "x509", "-noout", "-subject")
	cmd2.Stdin = bytes.NewReader(pem)
	subjOut, err := cmd2.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("openssl x509 failed: %w: %s", err, string(subjOut))
	}
	s := string(subjOut)
	// s looks like: subject=C = US, OU = ..., CN = Apple Development: foo (ID), ...
	// Extract CN value.
	// Try CN = "..." and CN=...
	idx := strings.Index(s, "CN")
	if idx >= 0 {
		eq := strings.Index(s[idx:], "=")
		if eq >= 0 {
			rest := strings.TrimSpace(s[idx+eq+1:])
			// CN may be quoted or until comma/slash
			if strings.HasPrefix(rest, "\"") {
				rest = rest[1:]
				if end := strings.Index(rest, "\""); end >= 0 {
					return strings.TrimSpace(rest[:end]), nil
				}
			}
			// Find terminator
			end := len(rest)
			if comma := strings.Index(rest, ","); comma >= 0 && comma < end {
				end = comma
			}
			if slash := strings.Index(rest, "/"); slash >= 0 && slash < end {
				end = slash
			}
			cn := strings.TrimSpace(rest[:end])
			cn = strings.Trim(cn, "\"' ")
			if cn != "" {
				return cn, nil
			}
		}
	}
	trimmed := strings.TrimSpace(strings.TrimPrefix(s, "subject="))
	trimmed = strings.TrimSpace(trimmed)
	if trimmed != "" {
		return trimmed, nil
	}
	return "", fmt.Errorf("failed to parse CN from %q", string(subjOut))
}

// deriveEntitlements derives entitlements plist from provisioning profile.
// It tries `security cms -D -i <profile>` then `plutil -extract Entitlements xml1 -o - -`.
// Writes result to /tmp/entitlements.plist (or $TMPDIR/entitlements.plist) and returns its path.
func deriveEntitlements(profilePath string) (string, error) {
	if profilePath == "" {
		return "", nil
	}
	// 1. Decode CMS
	decoded, err := exec.Command("security", "cms", "-D", "-i", profilePath).CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("security cms -D failed: %w: %s", err, string(decoded))
	}
	tmpDir := os.TempDir()
	decodedPath := filepath.Join(tmpDir, "profile-decoded.plist")
	if err := os.WriteFile(decodedPath, decoded, 0o644); err != nil {
		return "", fmt.Errorf("write decoded profile failed: %w", err)
	}
	// Prefer writing to /tmp/entitlements.plist per spec, but use $TMPDIR for sandbox friendliness.
	entPath := filepath.Join(tmpDir, "entitlements.plist")

	// 2. Try plutil -extract Entitlements xml1 -o entPath decodedPath
	cmd := exec.Command("plutil", "-extract", "Entitlements", "xml1", "-o", entPath, decodedPath)
	if out, err := cmd.CombinedOutput(); err == nil {
		if _, statErr := os.Stat(entPath); statErr == nil {
			return entPath, nil
		}
		_ = out
	} else {
		_ = out
	}

	// 3. Fallback: shell pipeline `security cms -D -i <profile> | plutil -extract Entitlements xml1 -o - -`
	shellCmd := fmt.Sprintf("security cms -D -i %q | plutil -extract Entitlements xml1 -o - -", profilePath)
	cmd2 := exec.Command("sh", "-c", shellCmd)
	out2, err2 := cmd2.CombinedOutput()
	if err2 == nil && len(out2) > 0 && bytes.Contains(out2, []byte("<plist")) {
		if err := os.WriteFile(entPath, out2, 0o644); err == nil {
			return entPath, nil
		}
	}
	// 4. Last resort: try manual extraction via plutil -p and reconstruct minimal plist?
	// Return error to allow caller to decide fallback vs copy stub.
	if err2 != nil {
		return "", fmt.Errorf("plutil -extract Entitlements failed: %v: %s", err2, string(out2))
	}
	return "", fmt.Errorf("failed to extract Entitlements to %q", entPath)
}

// findAppPath locates Payload/*.app inside unzipDir.
func findAppPath(unzipDir string) (string, error) {
	payload := filepath.Join(unzipDir, "Payload")
	entries, err := os.ReadDir(payload)
	if err != nil {
		return "", fmt.Errorf("Payload not found at %q: %w", payload, err)
	}
	for _, e := range entries {
		if e.IsDir() && strings.HasSuffix(e.Name(), ".app") {
			return filepath.Join(payload, e.Name()), nil
		}
	}
	return "", fmt.Errorf("no .app bundle found in %q", payload)
}

// codesignTarget invokes `codesign -f -s "<identity>" --entitlements <entPath> <target>`.
// Modern Xcode codesign defaults to SHA256 digest, so no --digest flag is needed.
func codesignTarget(identity, entPath, target string) error {
	args := []string{"-f", "-s", identity}
	if entPath != "" {
		if _, err := os.Stat(entPath); err == nil {
			args = append(args, "--entitlements", entPath)
		}
	}
	args = append(args, target)
	cmd := exec.Command("codesign", args...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("codesign failed for %q identity %q: %w: %s", target, identity, err, string(out))
	}
	return nil
}
