//go:build !darwin

// Package install — non-darwin stub.
//
// On Linux/CI, installation via usbmuxd/installation_proxy is not available.
// This file ensures `go vet ./... && go build ./...` pass on any platform
// while documenting the darwin-only intent.
package install

import "fmt"

// Options holds the parameters for installing a signed IPA.
// Same shape as darwin implementation so callers compile cross-platform.
type Options struct {
	IPA      string
	UDID     string
	BundleID string
}

// Install on non-darwin always returns a darwin-only error.
// Real installation requires macOS + usbmuxd (see install.go darwin docs:
// usbmuxd → lockdown → installation_proxy flow via go-ios).
func Install(opts Options) error {
	_ = opts
	return fmt.Errorf("install: only available on darwin (requires usbmuxd → installation_proxy via go-ios or ideviceinstaller/ios-deploy); IPA=%q UDID=%q", opts.IPA, opts.UDID)
}
