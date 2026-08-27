package auth

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/abhakash/dylan/tools/dylan-sign/internal/anisette"
	"github.com/abhakash/dylan/tools/dylan-sign/internal/store"
)

// Session holds the persisted Apple authentication state.
//
// Mirrors AltSign's ALTAppleAPI session handling (see ALTAppleAPI.m and
// ALTAppleAPI+Authentication.m) but as a pure-Go stub. Real AltStore
// persists DSID + auth token + anisette, and checks expiry before any
// Developer Portal operation.
//
// Layout on disk: JSON at ~/.dylan-sign/session.json (0600) + optional
// duplicate of sensitive token in Keychain via store.Keychain.
//
// Fields are JSON-serialized for session.json; AuthToken is also duplicated
// into Keychain for defense-in-depth when a Keychain is available.
type Session struct {
	DSID      string         `json:"dsid"`
	AuthToken string         `json:"authToken"`
	Anisette  *anisette.Data `json:"anisette,omitempty"`
	CreatedAt time.Time      `json:"createdAt"`
	ExpiresAt time.Time      `json:"expiresAt"`
}

// IsExpired reports whether the session is expired.  A zero ExpiresAt is
// treated as non-expiring (not expired) to allow sessions without explicit
// expiry from stub flows.
func (s Session) IsExpired() bool {
	if s.ExpiresAt.IsZero() {
		return false
	}
	return time.Now().After(s.ExpiresAt)
}

// Save persists the session to JSON at store.SessionPath() (or
// ~/.dylan-sign/session.json when st is nil) with 0600 perms and also
// duplicates the auth token into the Keychain when available.
//
// Keychain duplicates use service "dylan-sign" / account "session:<dsid>".
// Failures to write to Keychain are not fatal — the file is the source of
// truth for this stub.
func Save(s *Session, st *store.Store) error {
	if s == nil {
		return fmt.Errorf("nil session")
	}
	if st == nil {
		st = store.New("", nil)
	}
	if err := os.MkdirAll(filepath.Dir(st.SessionPath()), 0o700); err != nil {
		return err
	}
	if s.CreatedAt.IsZero() {
		s.CreatedAt = time.Now()
	}
	// Default expiry ~30 days from creation if not set (mirrors typical Apple
	// token lifetime before refresh; AltSign refreshes via gsa.apple.com).
	if s.ExpiresAt.IsZero() {
		s.ExpiresAt = s.CreatedAt.Add(30 * 24 * time.Hour)
	}
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	if err := os.WriteFile(st.SessionPath(), data, 0o600); err != nil {
		return err
	}
	// Best-effort Keychain duplicate of the token (0600 fallback is already
	// satisfied by file perms; Keychain gives macOS Keychain protection).
	// The underlying store.FileKeychain uses file fallback with 0600 perms
	// when go-keyring is unavailable (see store/store.go and auth/keychain.go).
	if kc := keychainFromStore(st); kc != nil && s.AuthToken != "" {
		_ = SaveTokenToKeychain(kc, s.DSID, s.AuthToken)
	}
	return nil
}

// Load reads the session from JSON at store.SessionPath() (or default path
// when st is nil). If the file does not exist it also tries to reconstruct
// from Keychain (see keychain.go SaveSession/LoadSession).  When both are
// present the file takes precedence and Keychain is used only to fill a
// missing AuthToken.
func Load(st *store.Store) (*Session, error) {
	if st == nil {
		st = store.New("", nil)
	}
	path := st.SessionPath()
	// Legacy fallback: also try default path if custom path missing.
	if _, err := os.Stat(path); err != nil && os.IsNotExist(err) {
		if st.SessionPath() != store.SessionPath() {
			path = store.SessionPath()
		}
	}
	data, err := os.ReadFile(path)
	if err != nil {
		// Try Keychain-only fallback.
		if kc := keychainFromStore(st); kc != nil {
			if sess, kerr := LoadSessionFromKeychain(kc); kerr == nil && sess != nil {
				return sess, nil
			}
		}
		return nil, err
	}
	var s Session
	if err := json.Unmarshal(data, &s); err != nil {
		return nil, fmt.Errorf("decode session %s: %w", path, err)
	}
	// If AuthToken missing from file but present in Keychain, fill it.
	if s.AuthToken == "" {
		if kc := keychainFromStore(st); kc != nil {
			if tok, kerr := LoadTokenFromKeychain(kc, s.DSID); kerr == nil && tok != "" {
				s.AuthToken = tok
			}
		}
	}
	return &s, nil
}

// SaveToPath is a test helper that persists to an explicit path with 0600.
func SaveToPath(s *Session, path string) error {
	if s == nil {
		return fmt.Errorf("nil session")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o600)
}

// LoadFromPath is a test helper that loads from an explicit path.
func LoadFromPath(path string) (*Session, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var s Session
	if err := json.Unmarshal(data, &s); err != nil {
		return nil, err
	}
	return &s, nil
}

// keychainFromStore returns the Store's underlying Keychain when available
// (on darwin this is *store.KeyringKeychain via go-keyring with FileKeychain
// fallback), otherwise constructs a file fallback at the store's base dir.
func keychainFromStore(st *store.Store) store.Keychain {
	if st == nil {
		return nil
	}
	if kc := st.Keychain(); kc != nil {
		return kc
	}
	keychainPath := filepath.Join(st.BaseDir(), "keychain.json")
	return store.NewFileKeychain(keychainPath)
}
