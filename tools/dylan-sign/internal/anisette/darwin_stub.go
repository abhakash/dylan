//go:build !darwin

package anisette

import "fmt"

type stubProvider struct{}

func (s *stubProvider) Fetch() (*Data, error) {
	return nil, fmt.Errorf("anisette: darwin provider is only available on macOS (darwin); use NewRemoteProvider(url) with a remote anisette server instead")
}

// NewDarwinProvider on non-darwin builds returns a stub that errors on Fetch.
// This keeps `go build ./...` and `go vet ./...` portable while preserving
// the darwin-only semantics for callers on Linux CI.
func NewDarwinProvider() Provider {
	return &stubProvider{}
}
