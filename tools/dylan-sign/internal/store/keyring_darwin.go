//go:build darwin

package store

import (
	"path/filepath"

	"github.com/zalando/go-keyring"
)

// KeyringKeychain wraps zalando/go-keyring with a FileKeychain fallback.
// On macOS it uses the native Keychain via Security framework; if that fails
// (e.g. headless CI without keychain access, or permission denied), it falls
// back to the file at ~/.dylan-sign/keychain.json with 0600 perms.
type KeyringKeychain struct {
	fallback *FileKeychain
}

// NewKeyringKeychain creates a KeyringKeychain with an optional file fallback.
// fallbackPath is the path for the file fallback (typically baseDir/keychain.json).
// If fallbackPath is empty, no file fallback is used.
func NewKeyringKeychain(fallbackPath string) *KeyringKeychain {
	var fb *FileKeychain
	if fallbackPath != "" {
		fb = NewFileKeychain(fallbackPath)
	}
	return &KeyringKeychain{fallback: fb}
}

func (k *KeyringKeychain) Set(service, account, value string) error {
	err := keyring.Set(service, account, value)
	if err == nil {
		return nil
	}
	// Fallback to file if Keychain unavailable (CI, no DBus, permission, etc.)
	// — single call; surface the fallback error with keyring context.
	if k.fallback != nil {
		if ferr := k.fallback.Set(service, account, value); ferr != nil {
			return ferr
		}
		return nil
	}
	return err
}

func (k *KeyringKeychain) Get(service, account string) (string, error) {
	val, err := keyring.Get(service, account)
	if err == nil {
		return val, nil
	}
	// Single fallback attempt before returning. zalando/go-keyring returns
	// keyring.ErrNotFound on macOS when the item is missing — normalize to
	// the typed ErrNotFound so callers can use errors.Is either way.
	if k.fallback != nil {
		if v, ferr := k.fallback.Get(service, account); ferr == nil {
			return v, nil
		}
	}
	if err == keyring.ErrNotFound {
		return "", notFound(service, account)
	}
	return "", err
}

func (k *KeyringKeychain) Delete(service, account string) error {
	err := keyring.Delete(service, account)
	if err == nil || err == keyring.ErrNotFound {
		// Also delete from fallback to keep consistent.
		if k.fallback != nil {
			_ = k.fallback.Delete(service, account)
		}
		if err == keyring.ErrNotFound {
			// Normalize to nil (idempotent delete) or os.ErrNotExist? Keep nil for idempotency,
			// but FileKeychain Delete is also idempotent. Ensure file also deleted.
			return nil
		}
		return nil
	}
	// Keychain delete failed for other reason; try fallback delete as best-effort.
	if k.fallback != nil {
		if ferr := k.fallback.Delete(service, account); ferr == nil {
			return nil
		}
	}
	return err
}

// defaultKeychain is used by Store.New on darwin.
func defaultKeychain(baseDir string) Keychain {
	return NewKeyringKeychain(filepath.Join(baseDir, "keychain.json"))
}

// Ensure compile-time interface compliance.
var _ Keychain = (*KeyringKeychain)(nil)
var _ Keychain = (*FileKeychain)(nil)
