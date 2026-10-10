//go:build darwin && !cgo

package anisette

// tryAOSKit is the non-CGO stub for darwin. When CGO_ENABLED=0 (e.g. CI,
// cross-compilation, or `go vet` without a C toolchain) we cannot
// #include <dlfcn.h> or call dlopen/dlsym. This stub always reports
// "not available" so darwin.go Fetch() falls back to the synthetic
// provider (ioreg + hw.model) which is pure Go and always builds.
//
// The real dlopen/dlsym implementation lives in aoskit_cgo.go
// (//go:build darwin && cgo) and is only compiled when CGO is enabled.
// See darwin.go header comment for the full strategy and why the synthetic
// fallback must remain the default on CI / without Xcode.
func tryAOSKit() (*Data, bool) {
	return nil, false
}
