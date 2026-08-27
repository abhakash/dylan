//go:build !darwin

package anisette

// NewRemoteProvider is defined here for non-darwin builds so that
// `go vet ./...` and `go build ./...` succeed on Linux CI. On darwin the
// same symbol is provided by darwin.go (which also delegates to
// newRemoteProvider in remote.go). Keeping the portable remote logic in
// remote.go and only the constructor in build-tagged files avoids duplicate
// symbol errors while satisfying the spec's "darwin.go includes
// NewRemoteProvider" requirement.

// NewRemoteProvider returns a Provider that fetches anisette from a remote
// SideStore/AltStore v3 server. Portable — works on Linux and darwin (via
// darwin.go's identical wrapper).
func NewRemoteProvider(url string) Provider {
	return newRemoteProvider(url)
}
