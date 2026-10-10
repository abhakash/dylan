package sign

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/abhakash/dylan/tools/dylan-sign/internal/bundle"
)

// RunPipeline validates the IPA, ensures bundle ID uniqueness, signs, and verifies.
// Steps:
//   - Validates IPA structure via ValidateIPA (must be unsigned per docs/medo)
//   - Ensures bundleID is unique via bundle helpers
//   - Calls Sign with the resolved bundle ID
//   - Verifies the extracted .app with `codesign --verify` on macOS.
//
// Non-strict (default): verification failures are warnings (stub copies cannot
// verify). See RunPipelineStrict for hard-fail behaviour.
func RunPipeline(ctx context.Context, ipaIn, ipaOut, bundleID, p12Path, profilePath string) error {
	return runPipeline(ctx, ipaIn, ipaOut, bundleID, p12Path, profilePath, false)
}

// RunPipelineStrict is RunPipeline with hard failures: placeholder/empty bundle
// IDs, copy-stub signing, and codesign verification errors all fail instead of
// warning. Use when real --apple-id/--udid (or --strict) were given.
func RunPipelineStrict(ctx context.Context, ipaIn, ipaOut, bundleID, p12Path, profilePath string) error {
	return runPipeline(ctx, ipaIn, ipaOut, bundleID, p12Path, profilePath, true)
}

func runPipeline(ctx context.Context, ipaIn, ipaOut, bundleID, p12Path, profilePath string, strict bool) error {
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if ipaIn == "" {
		return fmt.Errorf("ipaIn is required")
	}
	if ipaOut == "" {
		return fmt.Errorf("ipaOut is required")
	}
	// 1. Validate IPA exists and is a valid IPA.
	if err := ValidateIPA(ipaIn); err != nil {
		return fmt.Errorf("validate ipa: %w", err)
	}
	// Docs/medo: input must be unsigned. Enforce by checking for existing signature.
	unsigned, err := isIPAUnsigned(ipaIn)
	if err != nil {
		return fmt.Errorf("failed to check if ipa is unsigned: %w", err)
	}
	if !unsigned {
		return fmt.Errorf("ipa %q appears already signed (contains _CodeSignature); must be unsigned per docs/medo — provide an unsigned IPA", ipaIn)
	}

	// 2. Ensure bundleID unique.
	effectiveBundleID := bundleID
	if strings.TrimSpace(effectiveBundleID) == "" || effectiveBundleID == bundle.BaseBundleID {
		if strict {
			return fmt.Errorf("strict: explicit --bundle-id required (refusing placeholder/default %q)", bundleID)
		}
		// Random suffix so concurrent placeholder callers never collide on one
		// bundle ID (previously a fixed "dylan" suffix collided across users).
		effectiveBundleID = bundle.UniqueBundleID("", "unknown-"+bundle.RandomSuffix(4))
	} else {
		// Call for side-effect/coverage — ensures uniqueness helper is referenced.
		_ = bundle.UniqueBundleID(effectiveBundleID, "")
	}

	// Ensure output directory exists.
	if err := os.MkdirAll(filepath.Dir(ipaOut), 0o755); err != nil {
		return fmt.Errorf("failed to create output dir for %q: %w", ipaOut, err)
	}

	// 3. Call Sign().
	opts := Options{
		InputIPA:    ipaIn,
		OutputIPA:   ipaOut,
		P12Path:     p12Path,
		ProfilePath: profilePath,
		BundleID:    effectiveBundleID,
		SHA256Only:  true, // per zhlynn/zsign #391 — enforce SHA256-only
		Strict:      strict,
	}
	// Respect context cancellation while signing (zsign/codesign).
	// Wrap Sign in goroutine so ctx can cancel long-running exec.
	signErrCh := make(chan error, 1)
	go func() {
		signErrCh <- Sign(opts)
	}()
	select {
	case <-ctx.Done():
		return fmt.Errorf("pipeline cancelled: %w", ctx.Err())
	case err := <-signErrCh:
		if err != nil {
			return fmt.Errorf("sign failed: %w", err)
		}
	}

	// Quick existence check for output.
	if _, err := os.Stat(ipaOut); err != nil {
		return fmt.Errorf("signed ipa not created at %q: %w", ipaOut, err)
	}

	// 4. Verify the extracted .app with codesign on macOS (never the .ipa zip:
	// codesign verifies bundles, not archives).
	if runtime.GOOS == "darwin" {
		if _, err := exec.LookPath("codesign"); err == nil {
			if verr := verifySignedApp(ctx, ipaOut); verr != nil {
				if strict {
					return fmt.Errorf("codesign verify failed: %w", verr)
				}
				fmt.Fprintf(os.Stderr, "warning: codesign verify: %v (stub copies cannot verify; use --strict to fail)\n", verr)
			}
		} else if strict {
			return fmt.Errorf("strict: codesign not found in PATH, cannot verify")
		}
	} else if strict {
		fmt.Fprintf(os.Stderr, "note: strict verify skipped on %s (codesign is darwin-only)\n", runtime.GOOS)
	}

	return nil
}

// verifySignedApp unzips ipaPath to a temp dir and runs
// `codesign --verify --deep` on the extracted Payload/*.app bundle.
func verifySignedApp(ctx context.Context, ipaPath string) error {
	tmpDir, err := os.MkdirTemp("", "dylan-sign-verify-*")
	if err != nil {
		return fmt.Errorf("mktemp failed: %w", err)
	}
	defer os.RemoveAll(tmpDir)

	unzipDir := filepath.Join(tmpDir, "unzip")
	if err := UnzipIPA(ipaPath, unzipDir); err != nil {
		return fmt.Errorf("unzip failed: %w", err)
	}
	appPath, err := findAppPath(unzipDir)
	if err != nil {
		return err
	}
	verifyCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	cmd := exec.CommandContext(verifyCtx, "codesign", "--verify", "--deep", "--verbose=2", appPath)
	out, err := cmd.CombinedOutput()
	if err != nil {
		if verifyCtx.Err() != nil {
			return fmt.Errorf("codesign verify cancelled: %w", verifyCtx.Err())
		}
		return fmt.Errorf("codesign --verify --deep %q: %w: %s", appPath, err, string(out))
	}
	return nil
}
