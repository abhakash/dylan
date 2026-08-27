package sign

import (
	"archive/zip"
	"bytes"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// UnzipIPA extracts the IPA (zip) at src to destination directory dst.
// It validates ZipSlip protection and creates parent directories as needed.
func UnzipIPA(src, dst string) error {
	if src == "" {
		return fmt.Errorf("src is required")
	}
	if dst == "" {
		return fmt.Errorf("dst is required")
	}
	r, err := zip.OpenReader(src)
	if err != nil {
		return fmt.Errorf("failed to open ipa %q: %w", src, err)
	}
	defer r.Close()

	if err := os.MkdirAll(dst, 0o755); err != nil {
		return fmt.Errorf("failed to create dst %q: %w", dst, err)
	}

	for _, f := range r.File {
		// ZipSlip protection: filepath.Join with Clean and ensure prefix.
		fpath := filepath.Join(dst, f.Name) //nolint:gosec // ZipSlip checked below
		if !strings.HasPrefix(filepath.Clean(fpath), filepath.Clean(dst)+string(os.PathSeparator)) && filepath.Clean(fpath) != filepath.Clean(dst) {
			return fmt.Errorf("illegal file path %q in zip", f.Name)
		}
		if f.FileInfo().IsDir() {
			mode := f.Mode()
			if mode == 0 {
				mode = 0o755
			}
			if err := os.MkdirAll(fpath, mode); err != nil {
				return fmt.Errorf("failed to create dir %q: %w", fpath, err)
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(fpath), 0o755); err != nil {
			return fmt.Errorf("failed to create parent dir for %q: %w", fpath, err)
		}
		rc, err := f.Open()
		if err != nil {
			return fmt.Errorf("failed to open zip entry %q: %w", f.Name, err)
		}
		mode := f.Mode()
		if mode == 0 {
			mode = 0o644
		}
		outFile, err := os.OpenFile(fpath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, mode)
		if err != nil {
			rc.Close()
			return fmt.Errorf("failed to create file %q: %w", fpath, err)
		}
		if _, err := io.Copy(outFile, rc); err != nil {
			rc.Close()
			outFile.Close()
			return fmt.Errorf("failed to extract %q: %w", f.Name, err)
		}
		rc.Close()
		outFile.Close()
	}
	return nil
}

// ZipIPA creates a zip (IPA) at outPath containing the contents of srcDir.
// srcDir is the directory to archive (typically containing Payload/).
//
// NOTE on fidelity: symlinks are followed and stored as regular files (not
// preserved as symlinks), and only permission bits from FileInfo are kept.
// Exec bits survive via the stored mode, but .app bundles containing
// symlinked Frameworks should be re-zipped with macOS `ditto -c -k` or
// `zip --symlinks` instead of this helper for a device-installable IPA.
func ZipIPA(srcDir, outPath string) error {
	if srcDir == "" {
		return fmt.Errorf("srcDir is required")
	}
	if outPath == "" {
		return fmt.Errorf("outPath is required")
	}
	fi, err := os.Stat(srcDir)
	if err != nil {
		return fmt.Errorf("srcDir not found at %q: %w", srcDir, err)
	}
	if !fi.IsDir() {
		return fmt.Errorf("srcDir %q is not a directory", srcDir)
	}
	if err := os.MkdirAll(filepath.Dir(outPath), 0o755); err != nil {
		return fmt.Errorf("failed to create output dir for %q: %w", outPath, err)
	}
	outFile, err := os.Create(outPath)
	if err != nil {
		return fmt.Errorf("failed to create zip %q: %w", outPath, err)
	}
	defer outFile.Close()

	zw := zip.NewWriter(outFile)
	defer zw.Close()

	err = filepath.Walk(srcDir, func(path string, info os.FileInfo, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		// Compute archive path relative to srcDir.
		rel, err := filepath.Rel(srcDir, path)
		if err != nil {
			return err
		}
		if rel == "." {
			return nil
		}
		// Use forward slashes inside zip per spec.
		zipPath := filepath.ToSlash(rel)
		if info.IsDir() {
			// Ensure directory entry ends with slash.
			if !strings.HasSuffix(zipPath, "/") {
				zipPath += "/"
			}
			hdr := &zip.FileHeader{
				Name:   zipPath,
				Method: zip.Store,
			}
			hdr.SetMode(info.Mode() | 0o755)
			// Ensure at least 0755 for directories
			if hdr.Mode() == 0 {
				hdr.SetMode(0o755)
			}
			_, err := zw.CreateHeader(hdr)
			return err
		}
		header, err := zip.FileInfoHeader(info)
		if err != nil {
			return err
		}
		header.Name = zipPath
		header.Method = zip.Deflate
		if header.Mode() == 0 {
			header.SetMode(0o644)
		}
		writer, err := zw.CreateHeader(header)
		if err != nil {
			return err
		}
		f, err := os.Open(path)
		if err != nil {
			return err
		}
		if _, err := io.Copy(writer, f); err != nil {
			f.Close()
			return err
		}
		f.Close()
		return nil
	})
	if err != nil {
		return fmt.Errorf("failed to zip %q: %w", srcDir, err)
	}
	if err := zw.Close(); err != nil {
		return fmt.Errorf("failed to close zip writer: %w", err)
	}
	return outFile.Close()
}

// ReadBundleID reads CFBundleIdentifier from appPath/Info.plist.
// It supports XML plists via regex. Binary plists return an explicit error
// suggesting use of a plist library.
//
// Example XML snippet:
// <key>CFBundleIdentifier</key>
// <string>com.example.app</string>
func ReadBundleID(appPath string) (string, error) {
	if appPath == "" {
		return "", fmt.Errorf("appPath is required")
	}
	plistPath := filepath.Join(appPath, "Info.plist")
	data, err := os.ReadFile(plistPath)
	if err != nil {
		return "", fmt.Errorf("failed to read Info.plist at %q: %w", plistPath, err)
	}
	// Quick check for binary plist magic "bplist" — not supported via regex.
	if bytes.HasPrefix(data, []byte("bplist")) {
		return "", fmt.Errorf("binary plist not supported at %q: use plist library or convert with plutil -convert xml1", plistPath)
	}
	// Regex: <key>CFBundleIdentifier</key> \s* <string>VALUE</string>
	re := regexp.MustCompile(`(?s)<key>\s*CFBundleIdentifier\s*</key>\s*<string>([^<]+)</string>`)
	m := re.FindSubmatch(data)
	if m == nil {
		return "", fmt.Errorf("CFBundleIdentifier not found in %q", plistPath)
	}
	return strings.TrimSpace(string(m[1])), nil
}

// UpdateBundleID edits Info.plist at appPath to set CFBundleIdentifier to newID.
// It uses regex for XML plists to avoid external plist dependency and CGO.
// For binary plists a descriptive error is returned.
func UpdateBundleID(appPath, newID string) error {
	if appPath == "" {
		return fmt.Errorf("appPath is required")
	}
	if newID == "" {
		return fmt.Errorf("newID is required")
	}
	plistPath := filepath.Join(appPath, "Info.plist")
	data, err := os.ReadFile(plistPath)
	if err != nil {
		return fmt.Errorf("failed to read Info.plist at %q: %w", plistPath, err)
	}
	if bytes.HasPrefix(data, []byte("bplist")) {
		return fmt.Errorf("binary plist not supported at %q: use plist library or convert with plutil -convert xml1", plistPath)
	}
	// Capture prefix and suffix to preserve formatting.
	re := regexp.MustCompile(`(?s)(<key>\s*CFBundleIdentifier\s*</key>\s*<string>)([^<]+)(</string>)`)
	if !re.Match(data) {
		return fmt.Errorf("CFBundleIdentifier not found in %q", plistPath)
	}
	updated := re.ReplaceAll(data, []byte("${1}"+newID+"${3}"))
	// Atomic write: write to temp then rename would be safer, but simple write suffices for now.
	if err := os.WriteFile(plistPath, updated, 0o644); err != nil {
		return fmt.Errorf("failed to write Info.plist at %q: %w", plistPath, err)
	}
	return nil
}

// EmbedProvisioningProfile writes profileData to appPath/embedded.mobileprovision.
// This mirrors what zsign does with -m and what codesign expects inside the bundle.
func EmbedProvisioningProfile(appPath string, profileData []byte) error {
	if appPath == "" {
		return fmt.Errorf("appPath is required")
	}
	if len(profileData) == 0 {
		return fmt.Errorf("profileData is empty")
	}
	fi, err := os.Stat(appPath)
	if err != nil {
		return fmt.Errorf("appPath not found at %q: %w", appPath, err)
	}
	if !fi.IsDir() {
		return fmt.Errorf("appPath %q is not a directory", appPath)
	}
	dst := filepath.Join(appPath, "embedded.mobileprovision")
	if err := os.WriteFile(dst, profileData, 0o644); err != nil {
		return fmt.Errorf("failed to write embedded.mobileprovision at %q: %w", dst, err)
	}
	return nil
}

// ValidateIPA checks that path exists and looks like a valid IPA (zip with Payload/*.app/Info.plist).
func ValidateIPA(path string) error {
	if path == "" {
		return fmt.Errorf("path is required")
	}
	fi, err := os.Stat(path)
	if err != nil {
		return fmt.Errorf("ipa not found at %q: %w", path, err)
	}
	if fi.IsDir() {
		return fmt.Errorf("ipa path %q is a directory, expected .ipa file", path)
	}
	// Check zip magic header PK.
	f, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("failed to open %q: %w", path, err)
	}
	defer f.Close()
	magic := make([]byte, 4)
	n, err := io.ReadFull(f, magic)
	if err != nil || n < 2 {
		return fmt.Errorf("failed to read magic for %q: %w", path, err)
	}
	if magic[0] != 'P' || magic[1] != 'K' {
		return fmt.Errorf("file %q is not a zip/ipa (missing PK header)", path)
	}

	r, err := zip.OpenReader(path)
	if err != nil {
		return fmt.Errorf("failed to open zip %q: %w", path, err)
	}
	defer r.Close()

	hasPayload := false
	hasApp := false
	hasInfoPlist := false
	for _, zf := range r.File {
		name := zf.Name
		if strings.HasPrefix(name, "Payload/") {
			hasPayload = true
			// Check for Payload/<App>.app/Info.plist
			if strings.Contains(name, ".app/Info.plist") {
				hasInfoPlist = true
			}
			// At least one .app directory
			if strings.Contains(name, ".app/") {
				hasApp = true
			}
		}
	}
	if !hasPayload {
		return fmt.Errorf("ipa %q missing Payload/ directory", path)
	}
	if !hasApp {
		return fmt.Errorf("ipa %q missing Payload/*.app bundle", path)
	}
	if !hasInfoPlist {
		return fmt.Errorf("ipa %q missing Payload/*.app/Info.plist", path)
	}
	return nil
}

// isIPAUnsigned reports whether an IPA appears unsigned.
// It checks for absence of _CodeSignature resources and of an embedded
// provisioning profile. Used by pipeline to enforce docs/medo requirement
// that input must be unsigned.
func isIPAUnsigned(path string) (bool, error) {
	r, err := zip.OpenReader(path)
	if err != nil {
		return false, err
	}
	defer r.Close()
	for _, f := range r.File {
		if strings.Contains(f.Name, "_CodeSignature/CodeResources") || strings.Contains(f.Name, "_CodeSignature/CodeSignature") {
			return false, nil
		}
		if strings.HasSuffix(f.Name, ".app/embedded.mobileprovision") {
			return false, nil
		}
	}
	return true, nil
}
