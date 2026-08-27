package bundle

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"strings"
	"unicode"
)

const BaseBundleID = "app.dylan.player.ios"

// UniqueBundleID returns a per-user unique bundle identifier.
// Format: "<base>.<sanitized-lowercase-user>" where base defaults to app.dylan.player.ios.
// Apple bundle IDs allow [A-Za-z0-9.-]; we strip @domain and replace invalid runes with "" or "-".
func UniqueBundleID(base, user string) string {
	if base == "" {
		base = BaseBundleID
	}
	// If user looks like email, take local part before @.
	if at := strings.Index(user, "@"); at > 0 {
		user = user[:at]
	}
	user = strings.ToLower(strings.TrimSpace(user))
	if user == "" {
		user = "user"
	}
	var b strings.Builder
	for _, r := range user {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '-' {
			b.WriteRune(r)
		} else if r == '.' || r == '_' || r == ' ' {
			b.WriteRune('-')
		} else if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
		}
		// else skip invalid char
	}
	sanitized := b.String()
	sanitized = strings.Trim(sanitized, "-.")
	if sanitized == "" {
		sanitized = "user"
	}
	return fmt.Sprintf("%s.%s", base, sanitized)
}

// IsPlaceholder reports whether s is an unset/placeholder identity value
// (empty, or the __*_PLACEHOLDER__ sentinels used by the CLI).
func IsPlaceholder(s string) bool {
	if strings.TrimSpace(s) == "" {
		return true
	}
	return strings.Contains(s, "PLACEHOLDER")
}

// RandomSuffix returns n random lowercase hex chars for uniquifying a bundle
// suffix when the identity is a placeholder (so two placeholder users never
// collide on the same bundle ID). Falls back to a fixed token only if the
// OS RNG fails.
func RandomSuffix(n int) string {
	if n <= 0 {
		n = 4
	}
	b := make([]byte, (n+1)/2)
	if _, err := rand.Read(b); err != nil {
		return "unknown"
	}
	return hex.EncodeToString(b)[:n]
}

// UniqueBundleIDForSign derives the effective bundle ID for a sign run:
// an explicit non-base --bundle-id wins; otherwise the Apple ID local part
// is used; placeholder identities get a random suffix so concurrent
// placeholder users never collide on one bundle ID.
func UniqueBundleIDForSign(base, bundleID, appleID string) string {
	if base == "" {
		base = BaseBundleID
	}
	if bundleID != "" && bundleID != base {
		return bundleID
	}
	user := appleID
	if IsPlaceholder(user) {
		user = "unknown-" + RandomSuffix(4)
	}
	return UniqueBundleID(base, user)
}
