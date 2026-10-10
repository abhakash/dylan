package store

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

// ErrNotFound is returned when a secret is absent. Use errors.Is(err,
// ErrNotFound) — it also matches os.ErrNotExist for backward compatibility
// with callers that check the stdlib sentinel.
var ErrNotFound = errors.New("store: secret not found")

// NotFoundError is the typed not-found error carrying the lookup key.
// Its Is() matches both ErrNotFound and os.ErrNotExist.
type NotFoundError struct {
	Service string
	Account string
}

func (e *NotFoundError) Error() string {
	return fmt.Sprintf("store: secret not found (service %q account %q): %v", e.Service, e.Account, ErrNotFound)
}

func (e *NotFoundError) Is(target error) bool {
	return target == ErrNotFound || target == os.ErrNotExist
}

func notFound(service, account string) error {
	return &NotFoundError{Service: service, Account: account}
}

// Paths for persistent data under ~/.dylan-sign/
func BaseDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		home = "."
	}
	return filepath.Join(home, ".dylan-sign")
}

func SessionPath() string { return filepath.Join(BaseDir(), "session.json") }
func ConfigPath() string  { return filepath.Join(BaseDir(), "config.yaml") }
func CertsDir() string    { return filepath.Join(BaseDir(), "certs") }
func ProfilesDir() string { return filepath.Join(BaseDir(), "profiles") }

// EnsureDirs creates required directories if missing.
func EnsureDirs() error {
	for _, dir := range []string{BaseDir(), CertsDir(), ProfilesDir()} {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return err
		}
	}
	return nil
}

// Store abstracts persistence for sessions/certs/profiles.
type Store struct {
	baseDir string
	ks      Keychain
}

// New creates a Store rooted at baseDir (default BaseDir() if empty).
func New(baseDir string, ks Keychain) *Store {
	if baseDir == "" {
		baseDir = BaseDir()
	}
	if ks == nil {
		ks = defaultKeychain(baseDir)
	}
	return &Store{baseDir: baseDir, ks: ks}
}

func (s *Store) BaseDir() string     { return s.baseDir }
func (s *Store) SessionPath() string { return filepath.Join(s.baseDir, "session.json") }
func (s *Store) CertsDir() string    { return filepath.Join(s.baseDir, "certs") }
func (s *Store) ProfilesDir() string { return filepath.Join(s.baseDir, "profiles") }

// Keychain returns the underlying Keychain for callers that need direct secret access.
func (s *Store) Keychain() Keychain { return s.ks }

// ---------------------------------------------------------------------------
// Keychain abstraction
// ---------------------------------------------------------------------------

// Keychain defines a minimal secret storage interface.
// On macOS the real implementation would use Keychain via zalando/go-keyring;
// for now a file-backed stub is provided so the skeleton compiles on any platform.
type Keychain interface {
	Set(service, account, value string) error
	Get(service, account string) (string, error)
	Delete(service, account string) error
}

// FileKeychain is a simple file-backed fallback. NOT secure — placeholder only.
type FileKeychain struct {
	path string
	data map[string]string
}

func NewFileKeychain(path string) *FileKeychain {
	return &FileKeychain{path: path, data: make(map[string]string)}
}

func (f *FileKeychain) key(service, account string) string {
	return service + ":" + account
}

func (f *FileKeychain) Set(service, account, value string) error {
	f.data[f.key(service, account)] = value
	// Best-effort persist; ignore error for skeleton.
	_ = f.persist()
	return nil
}

func (f *FileKeychain) Get(service, account string) (string, error) {
	if v, ok := f.data[f.key(service, account)]; ok {
		return v, nil
	}
	// Try loading from file on miss.
	_ = f.load()
	if v, ok := f.data[f.key(service, account)]; ok {
		return v, nil
	}
	return "", notFound(service, account)
}

func (f *FileKeychain) Delete(service, account string) error {
	delete(f.data, f.key(service, account))
	_ = f.persist()
	return nil
}

// persist/load helpers — simple JSON file with 0600 perms.
func (f *FileKeychain) persist() error {
	if f.path == "" {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(f.path), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(f.data, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(f.path, data, 0o600)
}

func (f *FileKeychain) load() error {
	if f.path == "" {
		return ErrNotFound
	}
	data, err := os.ReadFile(f.path)
	if err != nil {
		return err
	}
	var m map[string]string
	if err := json.Unmarshal(data, &m); err != nil {
		return err
	}
	if m == nil {
		m = make(map[string]string)
	}
	// Merge file into memory, file wins on conflict.
	for k, v := range m {
		if _, exists := f.data[k]; !exists {
			f.data[k] = v
		}
	}
	return nil
}

// defaultKeychain is platform-specific: darwin returns KeyringKeychain with
// FileKeychain fallback, other platforms return FileKeychain directly.
// Implementations are in keyring_darwin.go and keyring_other.go via build tags.
