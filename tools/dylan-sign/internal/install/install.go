//go:build darwin

// Package install provides Xcode-free IPA installation via usbmuxd →
// installation_proxy, mirroring what Xcode does through MobileDevice.framework
// but without requiring Xcode.
//
// Xcode-equivalent flow (what this package would do on a real device):
//
//	Xcode (MobileDevice.framework)
//	  │ 1. Connect to usbmuxd Unix socket /var/run/usbmuxd
//	  │    (mux "Listen" + "Connect" to device port 62078 lockdown)
//	  ├─→ usbmuxd (system daemon, USB + Wi-Fi multiplex)
//	  │ 2. Lockdown handshake: StartSession / PairRecord / EnableSessionSSL
//	  │    (validates host pairing, enables SSL for lockdown service)
//	  │ 3. lockdown.StartService("com.apple.mobile.installation_proxy")
//	  │    → returns port for installation_proxy
//	  │ 4. Connect to installation_proxy on that port (via usbmuxd Connect)
//	  │ 5. Send plist { Command: "Install", PackagePath: "<ipa-path>" }
//	  │    streamed over DeviceConnection → installation_proxy
//	  └─→ iOS installation_proxy installs IPA to /private/var/containers/Bundle/Application/...
//
// go-ios equivalent (danielpaulus/go-ios):
//
//	device, _ := ios.GetDevice(udid) // ListDevices + match UDID
//	// internally: NewUsbMuxConnection(NewDeviceConnection("/var/run/usbmuxd")).ListDevices()
//	conn, _ := installationproxy.New(device)
//	// internally: ios.ConnectToService(device, "com.apple.mobile.installation_proxy")
//	//           → UsbMuxConnection.Connect(deviceID, port) + lockdown StartService
//	err = conn.Install(ipaPath) // not yet in v1.0.1; Browse* only — phase-2 will vendor Install
//	// Real Install plist (from AltStore/ideviceinstaller):
//	//   { Command: "Install", PackagePath: "/path/to/app.ipa", ClientOptions: { CFBundleIdentifier: "..." } }
//
// Until go-ios gains a stable Install API (or we vendor it), this file is a
// stub that compiles and documents the flow. On darwin it probes for
// lightweight alternatives so `go vet ./... && go build ./...` stays green
// without requiring a heavy C dependency chain.
//
// See also: ios/installationproxy/installationproxy.go, ios/usbmuxconnection.go,
// ios/deviceconnection.go, ios/startservice.go in github.com/danielpaulus/go-ios.
package install

import (
	"fmt"
	"os"
	"os/exec"
)

// Options holds the parameters for installing a signed IPA.
// Mirrors the minimal inputs Xcode's Devices window needs.
type Options struct {
	IPA      string // path to signed .ipa
	UDID     string // device UDID (40-char hex or 24-char for newer devices)
	BundleID string // optional CFBundleIdentifier (for logging / verification)
}

// Install installs the IPA at opts.IPA onto the device with opts.UDID.
//
// Intended go-ios path (phase-2, Xcode-close, no Xcode required):
//
//	import (
//	    ios "github.com/danielpaulus/go-ios/ios"
//	    "github.com/danielpaulus/go-ios/ios/installationproxy"
//	)
//	func Install(opts Options) error {
//	    device, err := ios.GetDevice(opts.UDID) // or ListDevices + match
//	    if err != nil { return err }
//	    conn, err := installationproxy.New(device) // ConnectToService(..., "com.apple.mobile.installation_proxy")
//	    if err != nil { return err }
//	    defer conn.Close()
//	    // conn.Install(opts.IPA) // when go-ios exposes Install; today it only has Browse*
//	    // Until then, fallback to external tools below.
//	}
//
// Current (phase-1, sign-only) behavior:
//   - Validates IPA exists.
//   - Probes PATH for `ideviceinstaller` (libimobiledevice) or `ios-deploy`
//     (ios-control) as the lightest Xcode-free installer.
//   - If either is found, execs it: `ideviceinstaller -u <UDID> -i <IPA>`
//     or `ios-deploy --id <UDID> --bundle <IPA>` (variant flags handled).
//   - If neither is found, returns a helpful error with install instructions
//     but still compiles (stub contract).
//
// This keeps `go build ./...` green even when go-ios is not vendored or when
// running on a Mac without usbmuxd/device.
func Install(opts Options) error {
	if opts.IPA == "" {
		return fmt.Errorf("install: --ipa is required")
	}
	if opts.UDID == "" {
		return fmt.Errorf("install: --udid is required")
	}
	if _, err := os.Stat(opts.IPA); err != nil {
		return fmt.Errorf("install: ipa not found at %q: %w", opts.IPA, err)
	}

	// NOTE: Real go-ios path would be (commented to avoid hard dep breaking build
	// if go-ios is removed, yet documents the usbmuxd→installation_proxy flow):
	//
	//   deviceList := ios.NewUsbMuxConnection(ios.NewDeviceConnection(ios.DefaultUsbmuxdSocket)).ListDevices()
	//   // find entry where Properties.SerialNumber == opts.UDID
	//   // or ios.GetDevice(opts.UDID) helper
	//   deviceConn, err := ios.ConnectToService(device, "com.apple.mobile.installation_proxy")
	//   // ios.ConnectToService does:
	//   //   - UsbMuxConnection.Connect(deviceID, 62078) to lockdown
	//   //   - lockdown.StartService("com.apple.mobile.installation_proxy") -> port
	//   //   - UsbMuxConnection.Connect(deviceID, port) -> DeviceConnection
	//   // conn := &installationproxy.Connection{deviceConn: deviceConn, plistCodec: ios.NewPlistCodec()}
	//   // conn.Send(map[string]interface{}{"Command": "Install", "PackagePath": opts.IPA})
	//   // conn.plistCodec.Decode(reader) // wait for Complete status
	//
	// Until that API is vendored, try lightweight external tools that already
	// speak installation_proxy via usbmuxd:

	if path, err := exec.LookPath("ideviceinstaller"); err == nil {
		// libimobiledevice: speaks usbmuxd → installation_proxy directly.
		// Equivalent to Xcode's install, but via C lib.
		cmd := exec.Command(path, "-u", opts.UDID, "-i", opts.IPA)
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		if err := cmd.Run(); err != nil {
			return fmt.Errorf("install: ideviceinstaller failed: %w (tried %q -u %s -i %s)", err, path, opts.UDID, opts.IPA)
		}
		return nil
	}

	if path, err := exec.LookPath("ios-deploy"); err == nil {
		// ios-deploy: also talks usbmuxd → installation_proxy.
		// Flags vary by version; try --id / --bundle form.
		cmd := exec.Command(path, "--id", opts.UDID, "--bundle", opts.IPA)
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		if err := cmd.Run(); err != nil {
			// Fallback flag set: ios-deploy --bundle <ipa> --id <udid>
			cmd2 := exec.Command(path, "--bundle", opts.IPA, "--id", opts.UDID)
			cmd2.Stdout = os.Stdout
			cmd2.Stderr = os.Stderr
			if err2 := cmd2.Run(); err2 != nil {
				return fmt.Errorf("install: ios-deploy failed: %w (tried %q --id %s --bundle %s; fallback also failed: %v)", err, path, opts.UDID, opts.IPA, err2)
			}
			return nil
		}
		return nil
	}

	// Neither helper found — stub still compiles, but instructs user.
	// This is the sign-only phase; installation will be wired in phase-2
	// via go-ios installation_proxy.Install when go-ios is vendored.
	return fmt.Errorf("install: no installer found (checked ideviceinstaller, ios-deploy in PATH); to enable Xcode-free install: brew install libimobiledevice ios-deploy  OR  vendor github.com/danielpaulus/go-ios and wire installationproxy.New(device).Install(%q); IPA=%q UDID=%s bundle=%q (usbmuxd→installation_proxy stub)", opts.IPA, opts.IPA, opts.UDID, opts.BundleID)
}
