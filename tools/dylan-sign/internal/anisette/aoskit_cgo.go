//go:build darwin && cgo

package anisette

/*
#include <dlfcn.h>
#include <stdlib.h>
*/
import "C"
import (
	"fmt"
	"os"
	"unsafe"
)

// tryAOSKit attempts to dlopen the private AOSKit framework and resolve a
// known anisette symbol at runtime. This mirrors what Xcode does internally:
//
//   dlopen("/System/Library/PrivateFrameworks/AOSKit.framework/AOSKit", RTLD_NOW)
//   dlsym(handle, "retrieveOTPHeadersForDSID") // or AKAnisetteData / AKAnisetteProvisioningController
//
// Why dlopen/dlsym instead of linking?
//   - AOSKit is a PrivateFramework; its headers (AOSKit/AKAnisetteData.h) are
//     not in the public SDK. Linking with `-framework AOSKit` would require
//     those private headers and would break `go build` on CI, on machines
//     without Xcode, and with CGO_ENABLED=0. See darwin.go header comment.
//   - On modern macOS (13+) the framework binary lives in the dyld shared
//     cache, so `nm`/`otool` on the on-disk stub shows nothing, but dlopen
//     via dyld still succeeds. We therefore probe at runtime.
//   - Symbols change across macOS versions (e.g. retrieveOTPHeadersForDSID
//     existed on older releases, newer use AKAnisetteProvisioningController
//     ObjC class). We probe a list and treat any hit as "AOSKit present".
//
// If dlopen+dlsym succeeds we *would* call the private API to obtain real
// X-Apple-I-MD-* headers and populate Data. Until the ObjC bridging is wired
// (requires Foundation + private headers), we log the successful probe and
// return (nil,false) so the caller falls back to syntheticData(). This keeps
// `go build ./...` hermetic while proving the dlopen path works on real Macs.
// When the call is wired, replace the final return with real header extraction:
//
//   // Example (requires private headers, not compiled here):
//   //   cHeaders := C.retrieveOTPHeadersForDSID(cDSID)
//   //   d := &Data{MachinID: C.GoString(cHeaders.machineID), ...}
//   //   return d, true
//
// Must compile both with and without CGO_ENABLED=0: this file is only built
// when `cgo` is enabled (build tag `darwin && cgo`). The fallback stub is in
// aoskit_nocgo.go (darwin && !cgo) which always returns (nil,false).
func tryAOSKit() (*Data, bool) {
	const frameworkPath = "/System/Library/PrivateFrameworks/AOSKit.framework/AOSKit"

	cPath := C.CString(frameworkPath)
	defer C.free(unsafe.Pointer(cPath))

	// Clear any prior dlerror.
	C.dlerror()

	handle := C.dlopen(cPath, C.RTLD_NOW)
	if handle == nil {
		// dlopen failed — AOSKit not present (CI, Linux, or stripped cache).
		// Only log when debugging to avoid noise; synthetic fallback will log
		// its own "using synthetic" line in darwin.go Fetch().
		if os.Getenv("DYLAN_DEBUG") != "" || os.Getenv("DEBUG") != "" {
			if errStr := C.GoString(C.dlerror()); errStr != "" {
				fmt.Fprintf(os.Stderr, "anisette: AOSKit dlopen %q failed: %s (fallback to synthetic)\n", frameworkPath, errStr)
			} else {
				fmt.Fprintf(os.Stderr, "anisette: AOSKit dlopen %q failed (fallback to synthetic)\n", frameworkPath)
			}
		}
		return nil, false
	}
	defer C.dlclose(handle)

	// Probe symbols that have existed across macOS/Xcode versions. The exact
	// set is intentionally broad — see AltSign/ALTAnisetteData.m and
	// SideStore anisette-v3 for historical names.
	symbols := []string{
		"retrieveOTPHeadersForDSID",
		"_retrieveOTPHeadersForDSID",
		"AKAnisetteProvisioningController",
		"AKAnisetteData",
		"AKDevice",
		"ak_anisette_data",
	}
	found := ""
	for _, sym := range symbols {
		cSym := C.CString(sym)
		ptr := C.dlsym(handle, cSym)
		C.free(unsafe.Pointer(cSym))
		if ptr != nil {
			found = sym
			break
		}
	}

	if found == "" {
		// AOSKit present but interface changed — no known symbol.
		// This is expected on macOS 15+ where the framework is stubbed
		// and the real implementation is in the shared cache / another daemon.
		fmt.Fprintf(os.Stderr, "anisette: AOSKit dlopen succeeded but no known symbol found (probe %v) — fallback to synthetic\n", symbols)
		return nil, false
	}

	// Symbol found. In a fully-wired implementation we would now invoke the
	// private API via ObjC runtime (objc_msgSend) or C function pointer and
	// populate Data from the returned headers:
	//
	//   // TODO(real AOSKit): vendor AOSKit/AKAnisetteData.h and Foundation,
	//   // then:
	//   //   id anisette = C.objc_msgSend(C.objc_getClass("AKAnisetteData"), sel_registerName("anisetteDataWithMachineID:..."))
	//   //   // extract MachineID, OTP, LocalUserID, RoutingInfo, etc.
	//   //   // return &Data{...}, true
	//
	// For now we stop here to avoid requiring private headers at compile time
	// and to keep CI green. We log the probe success so `go run --debug`
	// clearly shows whether AOSKit or synthetic was used, as required.
	fmt.Fprintf(os.Stderr, "anisette: AOSKit dlopen succeeded (symbol %q found) — using synthetic fallback until private header wiring completed (see darwin.go TODO)\n", found)
	return nil, false
}
