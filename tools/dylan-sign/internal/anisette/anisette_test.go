package anisette

import (
	"testing"
	"time"
)

// Round-trip: Data -> headers -> Data must preserve the machine/OTP mapping,
// and the header convention must match provision.API.buildHeaders
// (AltStore: X-Apple-I-MD-M carries the machine ID).
func TestToHeadersMachineMapping(t *testing.T) {
	d := &Data{
		MachineID:              "MACHINE-123",
		OneTimePassword:        "OTP-456",
		LocalUserID:            "LU-789",
		RoutingInfo:            171061,
		DeviceUniqueIdentifier: "DU-1",
		DeviceSerialNumber:     "SN-1",
		DeviceDescription:      "<iPhone>",
		Date:                   time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC),
		Locale:                 "en_US",
		TimeZone:               "America/New_York",
	}
	h := d.ToHeaders()
	if h["X-Apple-I-MD-M"] != d.MachineID {
		t.Fatalf("X-Apple-I-MD-M = %q, want machineID %q", h["X-Apple-I-MD-M"], d.MachineID)
	}
	if h["X-Apple-I-MD"] != d.OneTimePassword {
		t.Fatalf("X-Apple-I-MD = %q, want oneTimePassword %q", h["X-Apple-I-MD"], d.OneTimePassword)
	}
	if h["X-Apple-I-MD-LU"] != d.LocalUserID {
		t.Fatalf("X-Apple-I-MD-LU = %q, want %q", h["X-Apple-I-MD-LU"], d.LocalUserID)
	}
	if h["X-Apple-I-MD-RINFO"] != "171061" {
		t.Fatalf("X-Apple-I-MD-RINFO = %q, want %q", h["X-Apple-I-MD-RINFO"], "171061")
	}

	rt := FromHeaders(h)
	if rt.MachineID != d.MachineID || rt.OneTimePassword != d.OneTimePassword ||
		rt.LocalUserID != d.LocalUserID || rt.RoutingInfo != d.RoutingInfo {
		t.Fatalf("round-trip mismatch: got %+v, want %+v", rt, d)
	}
	if rt.DeviceUniqueIdentifier != d.DeviceUniqueIdentifier ||
		rt.DeviceSerialNumber != d.DeviceSerialNumber ||
		rt.DeviceDescription != d.DeviceDescription ||
		rt.Locale != d.Locale || rt.TimeZone != d.TimeZone {
		t.Fatalf("round-trip extended fields mismatch: got %+v, want %+v", rt, d)
	}
	if !rt.Date.Equal(d.Date) {
		t.Fatalf("round-trip date mismatch: got %v, want %v", rt.Date, d.Date)
	}
}

func TestNilHeaders(t *testing.T) {
	var d *Data
	if h := d.ToHeaders(); len(h) != 0 {
		t.Fatalf("nil Data ToHeaders = %v, want empty", h)
	}
	if h := d.Headers(); len(h) != 0 {
		t.Fatalf("nil Data Headers = %v, want empty", h)
	}
	if !d.IsValid() {
		// ok — nil is invalid
	} else {
		t.Fatal("nil Data IsValid = true, want false")
	}
}
