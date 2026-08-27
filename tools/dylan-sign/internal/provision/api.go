//go:build darwin
// +build darwin

// Package provision mirrors AltSign's ALTAppleAPI.m (QH65B2) for the Apple
// Developer Portal. It provides stubs that log and return placeholder data
// while documenting the correct Apple endpoints and request construction.
//
// Reference: https://github.com/rileytestut/AltSign/blob/master/AltSign/Apple%20API/ALTAppleAPI.m
// Protocol version and client ID are reverse-engineered from Xcode (ALTAppleAPI.m:27-29).
package provision

import (
	"bytes"
	"crypto"
	"crypto/rand"
	"encoding/hex"
	"encoding/xml"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"github.com/abhakash/dylan/tools/dylan-sign/internal/anisette"
	"github.com/abhakash/dylan/tools/dylan-sign/internal/auth"
)

// ---------------------------------------------------------------------------
// Constants — mirrored from ALTAppleAPI.m
// ---------------------------------------------------------------------------

const (
	// ProtocolVersion is the Developer Services protocol version.
	// Mirrors ALTProtocolVersion = @"QH65B2" (ALTAppleAPI.m:28).
	ProtocolVersion = "QH65B2"

	// ClientID is the Xcode client identifier.
	// Mirrors ALTClientID = @"XABBG36SBA" (ALTAppleAPI.m:29).
	ClientID = "XABBG36SBA"

	baseURL         = "https://developerservices2.apple.com/services/" + ProtocolVersion + "/"
	servicesBaseURL = "https://developerservices2.apple.com/services/v1/"
)

// ---------------------------------------------------------------------------
// API
// ---------------------------------------------------------------------------

// API is the Developer Portal client. It holds the authenticated session
// (DSID + GS token) and anisette data required for X-Apple-I-MD-* headers.
//
// Mirrors ALTAppleAPI (ALTAppleAPI.m) which stores session + baseURL +
// servicesBaseURL + NSURLSession.
type API struct {
	Session    *auth.Session
	Anisette   *anisette.Data
	HTTPClient *http.Client
}

// httpClient returns the configured HTTP client or http.DefaultClient.
func (a *API) httpClient() *http.Client {
	if a.HTTPClient != nil {
		return a.HTTPClient
	}
	return http.DefaultClient
}

// ---------------------------------------------------------------------------
// Types — mirrors AltSign Model/Apple API
// ---------------------------------------------------------------------------

// Team mirrors ALTTeam (ALTTeam.h).
type Team struct {
	Name       string `json:"name" plist:"name"`
	Identifier string `json:"teamId" plist:"teamId"`
	Type       string `json:"type" plist:"type"` // Company/Organization, Individual, etc.
}

// Device mirrors ALTDevice (ALTDevice.h).
type Device struct {
	Name       string `json:"name" plist:"name"`
	Identifier string `json:"deviceNumber" plist:"identifier"` // UDID / deviceNumber
	Type       string `json:"deviceClass" plist:"type"`
}

// Certificate mirrors ALTCertificate (ALTCertificate.h).
type Certificate struct {
	Identifier        string            `json:"id" plist:"id"`
	Name              string            `json:"name" plist:"name"`
	SerialNumber      string            `json:"serialNumber" plist:"serialNumber"`
	MachineName       string            `json:"machineName" plist:"machineName"`
	MachineIdentifier string            `json:"machineId" plist:"machineId"`
	Data              []byte            `json:"certContent" plist:"certContent"` // DER
	PrivateKey        crypto.PrivateKey `json:"-"`                               // not serialized; set via AddCertificate
	P12Data           []byte            `json:"-"`                               // PKCS#12
}

// AppID mirrors ALTAppID (ALTAppID.h).
type AppID struct {
	Name             string                 `json:"name" plist:"name"`
	Identifier       string                 `json:"appIdId" plist:"appIdId"`
	BundleIdentifier string                 `json:"identifier" plist:"identifier"`
	ExpirationDate   *time.Time             `json:"expirationDate,omitempty" plist:"expirationDate"`
	Features         map[string]interface{} `json:"features,omitempty" plist:"features"`
}

// AppGroup mirrors ALTAppGroup (ALTAppGroup.h).
type AppGroup struct {
	Name            string `json:"name" plist:"name"`
	Identifier      string `json:"applicationGroup" plist:"identifier"`
	GroupIdentifier string `json:"identifier" plist:"groupIdentifier"`
}

// Profile mirrors ALTProvisioningProfile (ALTProvisioningProfile.h).
type Profile struct {
	Name           string                 `json:"name" plist:"name"`
	Identifier     string                 `json:"provisioningProfileId" plist:"provisioningProfileId"`
	UUID           string                 `json:"UUID" plist:"UUID"`
	BundleID       string                 `json:"appIdId" plist:"bundleIdentifier"`
	TeamIdentifier string                 `json:"teamId" plist:"teamId"`
	Data           []byte                 `json:"encodedProfile" plist:"encodedProfile"` // base64 / plist
	Entitlements   map[string]interface{} `json:"entitlements" plist:"entitlements"`
	Expiration     time.Time              `json:"expirationDate" plist:"expirationDate"`
	Certificates   []Certificate          `json:"certificates" plist:"certificates"`
	DeviceIDs      []string               `json:"deviceIds" plist:"deviceIds"`
	CreationDate   time.Time              `json:"creationDate" plist:"creationDate"`
}

// ---------------------------------------------------------------------------
// Helpers — plist body + headers (ALTAppleAPI.m:689-708)
// ---------------------------------------------------------------------------

// encodePlist encodes a map to Apple plist XML (text/x-xml-plist).
// This is a minimal implementation sufficient for the Developer Portal which
// expects clientId/protocolVersion/requestId/teamId + action-specific keys.
// Values are encoded as <string> (or <array> for []string). Complex types
// are fmt.Sprint'd. For production you would use howett.net/plist.
func encodePlist(params map[string]interface{}) ([]byte, error) {
	var buf bytes.Buffer
	buf.WriteString(`<?xml version="1.0" encoding="UTF-8"?>` + "\n")
	buf.WriteString(`<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">` + "\n")
	buf.WriteString(`<plist version="1.0"><dict>` + "\n")
	for k, v := range params {
		// <key>...</key>
		buf.WriteString("  <key>")
		_ = xml.EscapeText(&buf, []byte(k))
		buf.WriteString("</key>")
		switch val := v.(type) {
		case string:
			buf.WriteString("<string>")
			_ = xml.EscapeText(&buf, []byte(val))
			buf.WriteString("</string>")
		case []string:
			buf.WriteString("<array>")
			for _, s := range val {
				buf.WriteString("<string>")
				_ = xml.EscapeText(&buf, []byte(s))
				buf.WriteString("</string>")
			}
			buf.WriteString("</array>")
		case int, int32, int64, uint, uint64, float32, float64, bool:
			buf.WriteString("<string>")
			_ = xml.EscapeText(&buf, []byte(fmt.Sprint(val)))
			buf.WriteString("</string>")
		default:
			buf.WriteString("<string>")
			_ = xml.EscapeText(&buf, []byte(fmt.Sprint(val)))
			buf.WriteString("</string>")
		}
		buf.WriteString("\n")
	}
	buf.WriteString(`</dict></plist>`)
	return buf.Bytes(), nil
}

// buildHeaders constructs the headers required for every Developer Portal
// request, mirroring ALTAppleAPI.m:689-708 (sendRequestWithURL:).
//
// Required headers:
//
//	X-Apple-App-Info: com.apple.gs.xcode.auth
//	X-Xcode-Version: 11.2 (11B41)
//	X-Apple-I-Identity-Id: session.DSID
//	X-Apple-GS-Token: session.AuthToken
//	X-Apple-I-MD-M, X-Apple-I-MD, X-Apple-I-MD-LU, X-Apple-I-MD-RINFO,
//	X-Mme-Device-Id, X-MMe-Client-Info, X-Apple-I-Client-Time,
//	X-Apple-Locale, X-Apple-I-TimeZone, etc from anisette.
func (a *API) buildHeaders(req *http.Request) {
	// Content negotiation — Developer Portal expects plist.
	req.Header.Set("Content-Type", "text/x-xml-plist")
	req.Header.Set("Accept", "text/x-xml-plist")
	req.Header.Set("Accept-Language", "en-us")
	req.Header.Set("User-Agent", "Xcode")
	req.Header.Set("X-Apple-App-Info", "com.apple.gs.xcode.auth")
	req.Header.Set("X-Xcode-Version", "11.2 (11B41)")

	if a.Session != nil {
		if a.Session.DSID != "" {
			req.Header.Set("X-Apple-I-Identity-Id", a.Session.DSID)
		}
		if a.Session.AuthToken != "" {
			req.Header.Set("X-Apple-GS-Token", a.Session.AuthToken)
		}
		// Prefer Session.Anisette if present, otherwise top-level Anisette.
		ad := a.Anisette
		if a.Session.Anisette != nil {
			ad = a.Session.Anisette
		}
		if ad != nil {
			// Mirrors ALTAppleAPI.m:700-707 — session.anisetteData.*
			if ad.MachineID != "" {
				req.Header.Set("X-Apple-I-MD-M", ad.MachineID)
			}
			if ad.OneTimePassword != "" {
				req.Header.Set("X-Apple-I-MD", ad.OneTimePassword)
			}
			if ad.LocalUserID != "" {
				req.Header.Set("X-Apple-I-MD-LU", ad.LocalUserID)
			}
			// RoutingInfo is uint64 → decimal string
			req.Header.Set("X-Apple-I-MD-RINFO", strconv.FormatUint(ad.RoutingInfo, 10))
			if ad.DeviceUniqueIdentifier != "" {
				req.Header.Set("X-Mme-Device-Id", ad.DeviceUniqueIdentifier)
				// Some endpoints expect X-Mme-Device-Id with different casing;
				// lower-case variant is also accepted but we set canonical.
			}
			if ad.DeviceDescription != "" {
				req.Header.Set("X-MMe-Client-Info", ad.DeviceDescription)
			}
			if !ad.Date.IsZero() {
				// ALTAppleAPI uses NSISO8601DateFormatter → 8601 string
				req.Header.Set("X-Apple-I-Client-Time", ad.Date.UTC().Format(time.RFC3339))
			}
			if ad.Locale != "" {
				req.Header.Set("X-Apple-Locale", ad.Locale)
				req.Header.Set("X-Apple-I-Locale", ad.Locale)
			}
			if ad.TimeZone != "" {
				req.Header.Set("X-Apple-I-TimeZone", ad.TimeZone)
			}
			// Optional serial
			if ad.DeviceSerialNumber != "" {
				req.Header.Set("X-Apple-I-SRL-NO", ad.DeviceSerialNumber)
			}
		}
	} else if a.Anisette != nil {
		// No session but anisette present — still set MD headers (best effort)
		ad := a.Anisette
		if ad.MachineID != "" {
			req.Header.Set("X-Apple-I-MD-M", ad.MachineID)
		}
		if ad.OneTimePassword != "" {
			req.Header.Set("X-Apple-I-MD", ad.OneTimePassword)
		}
		if ad.LocalUserID != "" {
			req.Header.Set("X-Apple-I-MD-LU", ad.LocalUserID)
		}
		req.Header.Set("X-Apple-I-MD-RINFO", strconv.FormatUint(ad.RoutingInfo, 10))
		if ad.DeviceUniqueIdentifier != "" {
			req.Header.Set("X-Mme-Device-Id", ad.DeviceUniqueIdentifier)
		}
		if ad.DeviceDescription != "" {
			req.Header.Set("X-MMe-Client-Info", ad.DeviceDescription)
		}
	}
}

// sendRequest builds a plist POST to the Developer Portal baseURL and
// returns a decoded plist map. Mirrors ALTAppleAPI.m sendRequestWithURL:…
//
// Endpoint example: "listTeams.action" →
//
//	POST https://developerservices2.apple.com/services/QH65B2/listTeams.action?clientId=XABBG36SBA
//
// Body is plist XML with clientId, protocolVersion, requestId, userLocale,
// teamId (when team non-nil) plus additionalParameters.
//
// Headers are set per buildHeaders (ALTAppleAPI.m:689-708).
//
// This stub logs the request and returns a placeholder success map without
// performing network I/O, unless HTTPClient is configured and the caller
// explicitly wants real I/O (future work: parse resultCode / response).
func (a *API) sendRequest(endpoint string, additionalParams map[string]interface{}, team *Team) (map[string]interface{}, error) {
	params := map[string]interface{}{
		"clientId":        ClientID,
		"protocolVersion": ProtocolVersion,
		"requestId":       newUUID(),
		"userLocale":      "en_US",
	}
	if team != nil && team.Identifier != "" {
		params["teamId"] = team.Identifier
	}
	for k, v := range additionalParams {
		params[k] = v
	}

	body, err := encodePlist(params)
	if err != nil {
		return nil, fmt.Errorf("provision: encode plist: %w", err)
	}

	// Build URL: baseURL + endpoint + ?clientId=XABBG36SBA
	u := baseURL + endpoint
	if _, err := url.Parse(u); err != nil {
		return nil, fmt.Errorf("provision: invalid endpoint %q: %w", endpoint, err)
	}
	fullURL := u + "?clientId=" + url.QueryEscape(ClientID)

	req, err := http.NewRequest(http.MethodPost, fullURL, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	a.buildHeaders(req)
	req.ContentLength = int64(len(body))

	log.Printf("[provision] sendRequest POST %s team=%v params=%v (body %d bytes)", fullURL, team, additionalParams, len(body))
	_ = a.httpClient // reference to avoid unused import warning; real Do is stubbed

	// Stub: return placeholder success without network.
	// Real implementation would do:
	//   resp, err := a.httpClient().Do(req)
	//   defer resp.Body.Close()
	//   decode plist response, check resultCode, parse via processResponse
	return map[string]interface{}{
		"resultCode":   0,
		"resultString": "success (stub)",
		"requestId":    params["requestId"],
	}, nil
}

// sendServicesRequest builds a services/v1 request (e.g. certificates).
// Mirrors ALTAppleAPI.m sendServicesRequest:additionalParameters:session:team:
//
//	servicesBaseURL + path + ?filter[certificateType]=IOS_DEVELOPMENT etc.
//
// Headers are the same as sendRequest (X-Apple-App-Info etc). Body, when
// present, is plist-encoded similarly. For GET-like services calls the
// additionalParams are encoded as query items instead of body.
func (a *API) sendServicesRequest(req *http.Request, additionalParams map[string]interface{}, team *Team) (map[string]interface{}, error) {
	// Clone request to avoid mutating caller.
	clone := req.Clone(req.Context())
	if clone.URL == nil {
		return nil, fmt.Errorf("provision: sendServicesRequest: nil URL")
	}

	// Merge additionalParams into query or body depending on method.
	if clone.Method == http.MethodGet || clone.Method == "" {
		q := clone.URL.Query()
		for k, v := range additionalParams {
			q.Set(k, fmt.Sprint(v))
		}
		// Also inject teamId / clientId / protocolVersion as query when present.
		if team != nil && team.Identifier != "" {
			q.Set("teamId", team.Identifier)
		}
		clone.URL.RawQuery = q.Encode()
	} else {
		// For POST/DELETE with body, encode plist similarly to sendRequest
		params := map[string]interface{}{
			"clientId":        ClientID,
			"protocolVersion": ProtocolVersion,
			"requestId":       newUUID(),
		}
		if team != nil && team.Identifier != "" {
			params["teamId"] = team.Identifier
		}
		for k, v := range additionalParams {
			params[k] = v
		}
		body, err := encodePlist(params)
		if err != nil {
			return nil, fmt.Errorf("provision: encode plist (services): %w", err)
		}
		clone.Body = http.NoBody
		// Attach body for POST/DELETE
		clone.Body = http.NoBody // placeholder to keep import used
		clone.GetBody = func() (io.ReadCloser, error) { return io.NopCloser(bytes.NewReader(body)), nil }
		_ = body
		// Ensure URL contains clientId
		q := clone.URL.Query()
		q.Set("clientId", ClientID)
		clone.URL.RawQuery = q.Encode()
		clone.ContentLength = int64(len(body))
		clone.Body = http.NoBody // stub; real would be bytes.NewReader(body)
	}

	a.buildHeaders(clone)
	if clone.Header.Get("Content-Type") == "" {
		clone.Header.Set("Content-Type", "text/x-xml-plist")
	}

	log.Printf("[provision] sendServicesRequest %s %s team=%v params=%v", clone.Method, clone.URL.String(), team, additionalParams)
	_ = a.httpClient

	return map[string]interface{}{
		"resultCode": 0,
		"data":       []interface{}{},
	}, nil
}

// ---------------------------------------------------------------------------
// Teams — GET https://developerservices2.apple.com/services/QH65B2/listTeams.action
// Mirrors ALTAppleAPI.m fetchTeamsForAccount:session:
// ---------------------------------------------------------------------------

// FetchTeams fetches the teams for the authenticated account.
// Endpoint: POST https://developerservices2.apple.com/services/QH65B2/listTeams.action?clientId=XABBG36SBA
// Body: plist with clientId, protocolVersion, requestId
// Response key: "teams" → []Team
func (a *API) FetchTeams() ([]Team, error) {
	log.Printf("[provision] FetchTeams: POST %slistTeams.action", baseURL)
	// Real: m, err := a.sendRequest("listTeams.action", nil, nil)
	// Stub: return placeholder team
	_ = a
	teams := []Team{
		{Name: "Example Team", Identifier: "TEAMID1234", Type: "Individual"},
		{Name: "Free Team", Identifier: "FREE123456", Type: "Free"},
	}
	log.Printf("[provision] FetchTeams → %d teams (stub)", len(teams))
	return teams, nil
}

// ---------------------------------------------------------------------------
// Devices
// ---------------------------------------------------------------------------

// FetchDevices fetches registered devices for a team.
// Endpoint: POST https://developerservices2.apple.com/services/QH65B2/ios/listDevices.action
// Mirrors ALTAppleAPI.m fetchDevicesForTeam:session:
func (a *API) FetchDevices(team Team) ([]Device, error) {
	log.Printf("[provision] FetchDevices: POST %sios/listDevices.action team=%s", baseURL, team.Identifier)
	// Real: m, err := a.sendRequest("ios/listDevices.action", nil, &team)
	// Stub placeholder
	devices := []Device{
		{Name: "iPhone (stub)", Identifier: "00008030-00123456789ABC", Type: "iphone"},
	}
	log.Printf("[provision] FetchDevices → %d devices (stub)", len(devices))
	return devices, nil
}

// RegisterDevice registers a device (UDID) to a team.
// Endpoint: POST https://developerservices2.apple.com/services/QH65B2/ios/addDevice.action
// Params: deviceNumber=udid, name=name
// Mirrors ALTAppleAPI.m registerDeviceWithName:identifier:team:session:
// Result codes: 35 → AlreadyExists (userString contains "already exists") or InvalidDeviceID
func (a *API) RegisterDevice(team Team, name, udid string) (Device, error) {
	log.Printf("[provision] RegisterDevice: POST %sios/addDevice.action team=%s name=%q udid=%q", baseURL, team.Identifier, name, udid)
	if name == "" || udid == "" {
		return Device{}, fmt.Errorf("provision: RegisterDevice: name and udid required")
	}
	// Real: m, err := a.sendRequest("ios/addDevice.action", map[string]interface{}{"deviceNumber": udid, "name": name}, &team)
	// Handle resultCode 35 → AlreadyExists. Stub returns device as if created.
	dev := Device{Name: name, Identifier: udid, Type: "iphone"}
	log.Printf("[provision] RegisterDevice → %v (stub; would handle resultCode 35 AlreadyExists)", dev)
	return dev, nil
}

// ---------------------------------------------------------------------------
// Certificates
// ---------------------------------------------------------------------------

// FetchCertificates fetches iOS development certificates.
// Endpoint: GET https://developerservices2.apple.com/services/v1/certificates?filter[certificateType]=IOS_DEVELOPMENT
// Mirrors ALTAppleAPI.m fetchCertificatesForTeam:session:
func (a *API) FetchCertificates(team Team) ([]Certificate, error) {
	log.Printf("[provision] FetchCertificates: GET %scertificates?filter[certificateType]=IOS_DEVELOPMENT team=%s", servicesBaseURL, team.Identifier)
	// Real: req, _ := http.NewRequest("GET", servicesBaseURL+"certificates", nil)
	// m, err := a.sendServicesRequest(req, map[string]interface{}{"filter[certificateType]": "IOS_DEVELOPMENT"}, &team)
	_ = url.Values{}
	certs := []Certificate{
		{Identifier: "CERTID123", Name: "iOS Development (stub)", SerialNumber: "00:11:22:33"},
	}
	log.Printf("[provision] FetchCertificates → %d certs (stub)", len(certs))
	return certs, nil
}

// AddCertificate creates a new iOS development certificate via CSR.
// Endpoint: POST https://developerservices2.apple.com/services/QH65B2/ios/submitDevelopmentCSR.action
// Params: csrContent (PEM string), machineId (UUID), machineName
// Mirrors ALTAppleAPI.m addCertificateWithMachineName:toTeam:session:
// Uses x509 CSR via GenerateCSR (see csr.go). Result codes: 3250 InvalidCertificateRequest
func (a *API) AddCertificate(team Team, machineName string) (Certificate, error) {
	log.Printf("[provision] AddCertificate: POST %sios/submitDevelopmentCSR.action team=%s machineName=%q", baseURL, team.Identifier, machineName)
	if machineName == "" {
		machineName = "Dylan Mac"
	}
	// Generate CSR PEM via csr.go helper
	csrPEM, priv, err := GenerateCSR()
	if err != nil {
		return Certificate{}, fmt.Errorf("provision: AddCertificate: generate CSR: %w", err)
	}
	machineID := newUUID()
	additional := map[string]interface{}{
		"csrContent":  string(csrPEM),
		"machineId":   machineID,
		"machineName": machineName,
	}
	// Real: m, err := a.sendRequest("ios/submitDevelopmentCSR.action", additional, &team)
	_ = additional
	_ = priv
	cert := Certificate{
		Identifier:  "CERT-NEW-" + machineID[:8],
		Name:        "iOS Development",
		MachineName: machineName,
		Data:        []byte("-----BEGIN CERTIFICATE-----\nMIIB...stub...\n-----END CERTIFICATE-----"),
		PrivateKey:  priv,
		P12Data:     nil, // would be generated via encryptedP12DataWithPassword
	}
	log.Printf("[provision] AddCertificate → %s (stub; would handle resultCode 3250 InvalidCSR)", cert.Identifier)
	return cert, nil
}

// RevokeCertificate revokes a certificate.
// Endpoint: DELETE https://developerservices2.apple.com/services/v1/certificates/{id}
// Mirrors ALTAppleAPI.m revokeCertificate:forTeam:session:
func (a *API) RevokeCertificate(team Team, cert Certificate) error {
	log.Printf("[provision] RevokeCertificate: DELETE %scertificates/%s team=%s", servicesBaseURL, cert.Identifier, team.Identifier)
	// Real: req, _ := http.NewRequest("DELETE", servicesBaseURL+"certificates/"+cert.Identifier, nil)
	// m, err := a.sendServicesRequest(req, nil, &team)
	// Handle resultCode 7252 CertificateDoesNotExist etc.
	log.Printf("[provision] RevokeCertificate → success (stub)")
	return nil
}

// ---------------------------------------------------------------------------
// App IDs
// ---------------------------------------------------------------------------

// FetchAppIDs fetches app IDs for a team.
// Endpoint: POST https://developerservices2.apple.com/services/QH65B2/ios/listAppIds.action
// Mirrors ALTAppleAPI.m fetchAppIDsForTeam:session:
func (a *API) FetchAppIDs(team Team) ([]AppID, error) {
	log.Printf("[provision] FetchAppIDs: POST %sios/listAppIds.action team=%s", baseURL, team.Identifier)
	appIDs := []AppID{
		{Name: "Dylan Player (stub)", Identifier: "APPID1234", BundleIdentifier: "app.dylan.player.ios.example"},
	}
	log.Printf("[provision] FetchAppIDs → %d appIDs (stub)", len(appIDs))
	return appIDs, nil
}

// AddAppID creates a new App ID.
// Endpoint: POST https://developerservices2.apple.com/services/QH65B2/ios/addAppId.action
// Params: identifier=bundleID, name=sanitizedName
// Mirrors ALTAppleAPI.m addAppIDWithName:bundleIdentifier:team:session:
// Result codes: 35 InvalidAppIDName, 9120 MaximumAppIDLimitReached, 9401 BundleIdentifierUnavailable, 9412 InvalidBundleIdentifier
func (a *API) AddAppID(team Team, name, bundleID string) (AppID, error) {
	log.Printf("[provision] AddAppID: POST %sios/addAppId.action team=%s name=%q bundleID=%q", baseURL, team.Identifier, name, bundleID)
	if bundleID == "" {
		return AppID{}, fmt.Errorf("provision: AddAppID: bundleID required")
	}
	if name == "" {
		name = bundleID
	}
	// Real: sanitizedName handling (strip diacritics, alphanumeric+space) omitted for stub
	appID := AppID{Name: name, Identifier: "APPID-" + newUUID()[:8], BundleIdentifier: bundleID}
	log.Printf("[provision] AddAppID → %s (stub; would handle 9120/9401/9412)", appID.Identifier)
	return appID, nil
}

// DeleteAppID deletes an App ID.
// Endpoint: POST https://developerservices2.apple.com/services/QH65B2/ios/deleteAppId.action
// Params: appIdId=appID.identifier
// Mirrors ALTAppleAPI.m deleteAppID:forTeam:session:
// Result code: 9100 AppIDDoesNotExist
func (a *API) DeleteAppID(team Team, appID AppID) error {
	log.Printf("[provision] DeleteAppID: POST %sios/deleteAppId.action team=%s appIdId=%s", baseURL, team.Identifier, appID.Identifier)
	// Real: m, err := a.sendRequest("ios/deleteAppId.action", map[string]interface{}{"appIdId": appID.Identifier}, &team)
	log.Printf("[provision] DeleteAppID → success (stub)")
	return nil
}

// UpdateAppID updates App ID features.
// Endpoint: POST https://developerservices2.apple.com/services/QH65B2/ios/updateAppId.action
func (a *API) UpdateAppID(team Team, appID AppID) (AppID, error) {
	log.Printf("[provision] UpdateAppID: POST %sios/updateAppId.action team=%s appIdId=%s", baseURL, team.Identifier, appID.Identifier)
	// Real: would send appIdId + features map
	log.Printf("[provision] UpdateAppID → %s (stub)", appID.Identifier)
	return appID, nil
}

// ---------------------------------------------------------------------------
// App Groups
// ---------------------------------------------------------------------------

// FetchAppGroups fetches application groups.
// Endpoint: POST https://developerservices2.apple.com/services/QH65B2/ios/listApplicationGroups.action
// Mirrors ALTAppleAPI.m fetchAppGroupsForTeam:session:
func (a *API) FetchAppGroups(team Team) ([]AppGroup, error) {
	log.Printf("[provision] FetchAppGroups: POST %sios/listApplicationGroups.action team=%s", baseURL, team.Identifier)
	groups := []AppGroup{
		{Name: "group.stub", Identifier: "GROUPID123", GroupIdentifier: "group.app.dylan.player"},
	}
	log.Printf("[provision] FetchAppGroups → %d groups (stub)", len(groups))
	return groups, nil
}

// AddAppGroup creates an application group.
// Endpoint: POST https://developerservices2.apple.com/services/QH65B2/ios/addApplicationGroup.action
// Params: identifier=groupIdentifier, name=name
// Result code: 35 InvalidAppGroup
func (a *API) AddAppGroup(team Team, name, groupIdentifier string) (AppGroup, error) {
	log.Printf("[provision] AddAppGroup: POST %sios/addApplicationGroup.action team=%s name=%q group=%q", baseURL, team.Identifier, name, groupIdentifier)
	if groupIdentifier == "" {
		return AppGroup{}, fmt.Errorf("provision: AddAppGroup: groupIdentifier required")
	}
	grp := AppGroup{Name: name, Identifier: "GRP-" + newUUID()[:8], GroupIdentifier: groupIdentifier}
	log.Printf("[provision] AddAppGroup → %s (stub)", grp.Identifier)
	return grp, nil
}

// AssignAppIDToGroups assigns an App ID to groups.
// Endpoint: POST https://developerservices2.apple.com/services/QH65B2/ios/assignApplicationGroupToAppId.action
// Params: appIdId, applicationGroups=[groupIds]
// Result codes: 9115 AppIDDoesNotExist, 35 AppGroupDoesNotExist
func (a *API) AssignAppIDToGroups(team Team, appID AppID, groups []AppGroup) error {
	var ids []string
	for _, g := range groups {
		ids = append(ids, g.Identifier)
	}
	log.Printf("[provision] AssignAppIDToGroups: POST %sios/assignApplicationGroupToAppId.action appIdId=%s groups=%v", baseURL, appID.Identifier, ids)
	// Real: a.sendRequest("ios/assignApplicationGroupToAppId.action", map[string]interface{}{"appIdId": appID.Identifier, "applicationGroups": ids}, &team)
	log.Printf("[provision] AssignAppIDToGroups → success (stub)")
	return nil
}

// ---------------------------------------------------------------------------
// Provisioning Profiles
// ---------------------------------------------------------------------------

// FetchProvisioningProfile fetches (or creates) the team provisioning profile for an App ID.
// Endpoint: POST https://developerservices2.apple.com/services/QH65B2/ios/downloadTeamProvisioningProfile.action
// Params: appIdId=appID.identifier
// Mirrors ALTAppleAPI.m fetchProvisioningProfileForAppID:team:session:
// Result code: 8201 AppIDDoesNotExist
func (a *API) FetchProvisioningProfile(team Team, appID AppID) (Profile, error) {
	log.Printf("[provision] FetchProvisioningProfile: POST %sios/downloadTeamProvisioningProfile.action team=%s appIdId=%s", baseURL, team.Identifier, appID.Identifier)
	now := time.Now()
	exp := now.Add(7 * 24 * time.Hour) // free profile 7 days
	prof := Profile{
		Name:           "iOS Team Provisioning Profile: " + appID.BundleIdentifier,
		Identifier:     "PP-" + newUUID()[:8],
		UUID:           newUUID(),
		BundleID:       appID.BundleIdentifier,
		TeamIdentifier: team.Identifier,
		Data:           []byte("stub mobileprovision data"),
		Entitlements:   map[string]interface{}{"application-identifier": team.Identifier + "." + appID.BundleIdentifier},
		Expiration:     exp,
		CreationDate:   now,
		DeviceIDs:      []string{},
	}
	log.Printf("[provision] FetchProvisioningProfile → %s (stub; expires %s)", prof.Identifier, exp.Format(time.RFC3339))
	return prof, nil
}

// DeleteProfile deletes a provisioning profile.
// Endpoint: POST https://developerservices2.apple.com/services/QH65B2/ios/deleteProvisioningProfile.action
// Params: provisioningProfileId, teamId
// Mirrors ALTAppleAPI.m deleteProvisioningProfile:forTeam:session:
// Result codes: 35 InvalidProvisioningProfileIdentifier, 8101 ProvisioningProfileDoesNotExist
func (a *API) DeleteProfile(team Team, profile Profile) error {
	log.Printf("[provision] DeleteProfile: POST %sios/deleteProvisioningProfile.action team=%s profileId=%s", baseURL, team.Identifier, profile.Identifier)
	log.Printf("[provision] DeleteProfile → success (stub)")
	return nil
}

// newUUID returns a random UUID v4 string (xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx, upper-cased).
// Used for requestId / machineId generation, mirroring [[[NSUUID UUID] UUIDString] uppercaseString] in ObjC.
func newUUID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		// fallback to timestamp-based pseudo-random
		return fmt.Sprintf("%08X-%04X-%04X-%04X-%012X", time.Now().UnixNano(), time.Now().UnixNano()&0xFFFF, 0x4000|0x0FFF&0x0FFF, 0x8000|0x3FFF&0x3FFF, time.Now().UnixNano()&0xFFFFFFFFFFFF)
	}
	// RFC 4122 v4
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	hexStr := hex.EncodeToString(b[:])
	u := fmt.Sprintf("%s-%s-%s-%s-%s", hexStr[0:8], hexStr[8:12], hexStr[12:16], hexStr[16:20], hexStr[20:32])
	// AltSign upper-cases requestId
	return u // keep lower for readability; upper-casing is caller responsibility
}

// ---------------------------------------------------------------------------
// Ensure imports are used (avoid vet complaints about unused in stub branches)
// ---------------------------------------------------------------------------

var (
	_ = bytes.MinRead
	_ = xml.Header
	_ = strconv.Itoa
)
