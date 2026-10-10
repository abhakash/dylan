package anisette

import (
	"encoding/json"
	"fmt"
	"strconv"
	"time"
)

// Data mirrors ALTAnisetteData (ALTAnisetteData.m) and SideStore/Anisette.py
// fields. It holds the device-specific values that are sent as
// X-Apple-I-MD-* headers during Apple ID authentication / provisioning.
//
// Fields are JSON-serialisable so the same struct can be persisted or
// proxied through a remote anisette server.
type Data struct {
	MachineID              string    `json:"machineID"`
	OneTimePassword        string    `json:"oneTimePassword"`
	LocalUserID            string    `json:"localUserID"`
	RoutingInfo            uint64    `json:"routingInfo"`
	DeviceUniqueIdentifier string    `json:"deviceUniqueIdentifier"`
	DeviceSerialNumber     string    `json:"deviceSerialNumber"`
	DeviceDescription      string    `json:"deviceDescription"`
	Date                   time.Time `json:"date"`
	Locale                 string    `json:"locale"`
	TimeZone               string    `json:"timeZone"`
}

// Provider abstracts anisette fetching. Implementations include the native
// darwin provider (AOSKit / synthetic fallback) and a remote HTTP provider
// for CI / Linux.
type Provider interface {
	Fetch() (*Data, error)
}

// ToHeaders returns the canonical X-Apple-I-MD-* header map for this Data.
// Headers follow the ALTAnisetteData / anisette-v3 convention (AltStore:
// -M carries the machine ID), matching provision.API.buildHeaders:
//
//	X-Apple-I-MD       -> OneTimePassword
//	X-Apple-I-MD-M     -> MachineID
//	X-Apple-I-MD-LU    -> LocalUserID
//	X-Apple-I-MD-RINFO -> RoutingInfo (decimal string)
//
// Plus extended device/time headers used by newer endpoints.
func (d *Data) ToHeaders() map[string]string {
	if d == nil {
		return map[string]string{}
	}
	h := map[string]string{
		"X-Apple-I-MD":       d.OneTimePassword,
		"X-Apple-I-MD-M":     d.MachineID,
		"X-Apple-I-MD-LU":    d.LocalUserID,
		"X-Apple-I-MD-RINFO": strconv.FormatUint(d.RoutingInfo, 10),
	}
	// Extended headers — consumed by some Apple endpoints and by SideStore
	// anisette servers. Only set when non-empty so callers can distinguish
	// synthetic vs fully-populated data.
	if d.DeviceUniqueIdentifier != "" {
		h["X-Apple-I-MD-DU"] = d.DeviceUniqueIdentifier
		h["X-Mme-Device-Identifier"] = d.DeviceUniqueIdentifier
	}
	if d.DeviceSerialNumber != "" {
		h["X-Apple-I-MD-DS"] = d.DeviceSerialNumber
	}
	if d.DeviceDescription != "" {
		h["X-Apple-I-MD-DD"] = d.DeviceDescription
		h["X-MMe-Client-Info"] = d.DeviceDescription
	}
	if d.Locale != "" {
		h["X-Apple-I-Locale"] = d.Locale
	}
	if d.TimeZone != "" {
		h["X-Apple-I-TimeZone"] = d.TimeZone
	}
	if !d.Date.IsZero() {
		h["X-Apple-I-Date"] = d.Date.UTC().Format(time.RFC3339)
	}
	return h
}

// ToJSON marshals Data to JSON.
func (d *Data) ToJSON() ([]byte, error) {
	if d == nil {
		return nil, fmt.Errorf("anisette: nil Data")
	}
	return json.Marshal(d)
}

// FromJSON unmarshals JSON into Data.
func FromJSON(b []byte) (*Data, error) {
	var d Data
	if err := json.Unmarshal(b, &d); err != nil {
		return nil, err
	}
	return &d, nil
}

// FromHeaders builds Data from an X-Apple-I-MD-* header map. Missing
// headers leave the corresponding field zero-valued. RoutingInfo is parsed
// as unsigned decimal; on parse error it stays 0.
func FromHeaders(h map[string]string) *Data {
	d := &Data{}
	if v, ok := h["X-Apple-I-MD-M"]; ok {
		d.MachineID = v
	}
	if v, ok := h["X-Apple-I-MD"]; ok {
		d.OneTimePassword = v
	}
	if v, ok := h["X-Apple-I-MD-LU"]; ok {
		d.LocalUserID = v
	}
	if v, ok := h["X-Apple-I-MD-RINFO"]; ok {
		if n, err := strconv.ParseUint(v, 10, 64); err == nil {
			d.RoutingInfo = n
		}
	}
	if v, ok := h["X-Apple-I-MD-DU"]; ok {
		d.DeviceUniqueIdentifier = v
	} else if v, ok := h["X-Mme-Device-Identifier"]; ok {
		d.DeviceUniqueIdentifier = v
	}
	if v, ok := h["X-Apple-I-MD-DS"]; ok {
		d.DeviceSerialNumber = v
	}
	if v, ok := h["X-Apple-I-MD-DD"]; ok {
		d.DeviceDescription = v
	} else if v, ok := h["X-MMe-Client-Info"]; ok {
		d.DeviceDescription = v
	}
	if v, ok := h["X-Apple-I-Locale"]; ok {
		d.Locale = v
	}
	if v, ok := h["X-Apple-I-TimeZone"]; ok {
		d.TimeZone = v
	}
	if v, ok := h["X-Apple-I-Date"]; ok {
		if t, err := time.Parse(time.RFC3339, v); err == nil {
			d.Date = t
		}
	}
	return d
}
