package auth

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"

	"github.com/abhakash/dylan/tools/dylan-sign/internal/store"
)

// Keychain service/account constants used for dylan-sign secrets.
// On macOS the real implementation is backed by Keychain via
// github.com/zalando/go-keyring (store.KeyringKeychain); on other platforms or
// when Keychain is unavailable (CI), it falls back to FileKeychain at
// ~/.dylan-sign/keychain.json with 0600 perms.
const (
	KeychainService        = "dylan-sign"
	KeychainAccountSession = "session"
	// KeychainAccountPasswordPrefix is prefixed to the AppleID when storing passwords.
	KeychainAccountPasswordPrefix = "password:"
	KeychainAccountTokenPrefix    = "token:"
)

// SavePassword stores the Apple ID password in the Keychain.
//
// Wrapper around store.Keychain: on darwin kc is typically a
// *store.KeyringKeychain backed by github.com/zalando/go-keyring (service
// "dylan-sign", account "password:<appleID>") with automatic FileKeychain
// fallback to ~/.dylan-sign/keychain.json (0600) if Keychain is unavailable.
func SavePassword(kc store.Keychain, appleID, password string) error {
	if kc == nil {
		return fmt.Errorf("nil keychain")
	}
	if appleID == "" {
		return fmt.Errorf("appleID required")
	}
	account := KeychainAccountPasswordPrefix + appleID
	return kc.Set(KeychainService, account, password)
}

// LoadPassword loads the Apple ID password from the Keychain.
func LoadPassword(kc store.Keychain, appleID string) (string, error) {
	if kc == nil {
		return "", fmt.Errorf("nil keychain")
	}
	account := KeychainAccountPasswordPrefix + appleID
	return kc.Get(KeychainService, account)
}

// DeletePassword removes the Apple ID password from the Keychain.
func DeletePassword(kc store.Keychain, appleID string) error {
	if kc == nil {
		return fmt.Errorf("nil keychain")
	}
	account := KeychainAccountPasswordPrefix + appleID
	return kc.Delete(KeychainService, account)
}

// SaveTokenToKeychain stores an auth token for a DSID in the Keychain.
// Used by session.go Save() as a best-effort duplicate.
func SaveTokenToKeychain(kc store.Keychain, dsid, token string) error {
	if kc == nil {
		return fmt.Errorf("nil keychain")
	}
	if token == "" {
		return fmt.Errorf("empty token")
	}
	account := KeychainAccountTokenPrefix + dsid
	if dsid == "" {
		account = KeychainAccountTokenPrefix + "default"
	}
	return kc.Set(KeychainService, account, token)
}

// LoadTokenFromKeychain loads an auth token for a DSID from the Keychain.
func LoadTokenFromKeychain(kc store.Keychain, dsid string) (string, error) {
	if kc == nil {
		return "", fmt.Errorf("nil keychain")
	}
	account := KeychainAccountTokenPrefix + dsid
	if dsid == "" {
		account = KeychainAccountTokenPrefix + "default"
	}
	return kc.Get(KeychainService, account)
}

// SaveSession persists the session JSON blob into the Keychain as a single
// entry (service "dylan-sign", account "session"). This is an alternative to
// file persistence for callers that prefer Keychain-only storage.
//
// The blob is stored as JSON; callers should prefer session.go Save/Load which
// use file (0600) + Keychain duplicate. This helper exists to satisfy the
// spec "Wrap store.Keychain interface: SaveSession, LoadSession".
func SaveSession(kc store.Keychain, sess *Session) error {
	if kc == nil {
		return fmt.Errorf("nil keychain")
	}
	if sess == nil {
		return fmt.Errorf("nil session")
	}
	data, err := json.Marshal(sess)
	if err != nil {
		return err
	}
	return kc.Set(KeychainService, KeychainAccountSession, string(data))
}

// LoadSession loads the session JSON blob from the Keychain.
func LoadSession(kc store.Keychain) (*Session, error) {
	if kc == nil {
		return nil, fmt.Errorf("nil keychain")
	}
	raw, err := kc.Get(KeychainService, KeychainAccountSession)
	if err != nil {
		return nil, err
	}
	var s Session
	if err := json.Unmarshal([]byte(raw), &s); err != nil {
		return nil, fmt.Errorf("decode keychain session: %w", err)
	}
	return &s, nil
}

// LoadSessionFromKeychain is an alias for LoadSession for session.go usage.
func LoadSessionFromKeychain(kc store.Keychain) (*Session, error) {
	return LoadSession(kc)
}

// DeleteSession removes the session blob from the Keychain.
func DeleteSession(kc store.Keychain) error {
	if kc == nil {
		return fmt.Errorf("nil keychain")
	}
	return kc.Delete(KeychainService, KeychainAccountSession)
}

// NewFileKeychain creates a file-backed Keychain fallback with 0600 perms.
// Thin wrapper around store.NewFileKeychain so auth callers don't import store
// directly for this concern.
func NewFileKeychain(path string) store.Keychain {
	// Ensure parent dir exists with 0700 for the fallback file.
	// Best-effort; ignore error so constructor never fails.
	_ = os.MkdirAll(getDir(path), 0o700)
	return store.NewFileKeychain(path)
}

// NewKeychain creates a Keychain backed by the system Keychain when available.
// On darwin this is a *store.KeyringKeychain via github.com/zalando/go-keyring
// (service "dylan-sign") with automatic FileKeychain fallback at path with 0600
// perms if Keychain is unavailable (CI, no DBus, permission denied). On
// !darwin it returns a FileKeychain at path.
func NewKeychain(path string) store.Keychain {
	_ = os.MkdirAll(getDir(path), 0o700)
	return store.NewKeyringKeychain(path)
}

// NewDefaultKeychain creates a Keychain at the default location
// ~/.dylan-sign/keychain.json, using the system Keychain on darwin.
func NewDefaultKeychain() store.Keychain {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		home = "."
	}
	path := filepath.Join(home, ".dylan-sign", "keychain.json")
	return NewKeychain(path)
}

func getDir(path string) string {
	for i := len(path) - 1; i >= 0; i-- {
		if path[i] == '/' || path[i] == '\\' {
			return path[:i]
		}
	}
	return "."
}

// Ensure Keychain interface compliance is checked at compile time via
// the store package. Any *store.FileKeychain or *store.KeyringKeychain
// satisfies store.Keychain.
var _ store.Keychain = (*store.FileKeychain)(nil)
var _ store.Keychain = (*store.KeyringKeychain)(nil)
