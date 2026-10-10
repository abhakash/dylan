//go:build !darwin

package store

import (
	"errors"
	"path/filepath"
)

// errDarwinOnly reports that the system Keychain is darwin-only.
var errDarwinOnly = errors.New("store: system keychain only available on darwin")

// KeyringKeychain stub for non-darwin builds. Keeps the type available so
// callers can type-switch, but operations return errors indicating real
// Keychain is darwin-only. Store.New on non-darwin uses FileKeychain instead.
type KeyringKeychain struct{}

func (k *KeyringKeychain) Set(_, _, _ string) error { return errDarwinOnly }
func (k *KeyringKeychain) Get(service, account string) (string, error) {
	return "", notFound(service, account)
}
func (k *KeyringKeychain) Delete(_, _ string) error { return nil }

// NewKeyringKeychain stub for non-darwin; returns stub that always errors.
// This keeps the constructor available for portable code.
func NewKeyringKeychain(_ string) *KeyringKeychain { return &KeyringKeychain{} }

func defaultKeychain(baseDir string) Keychain {
	return NewFileKeychain(filepath.Join(baseDir, "keychain.json"))
}

var _ Keychain = (*KeyringKeychain)(nil)
var _ Keychain = (*FileKeychain)(nil)
