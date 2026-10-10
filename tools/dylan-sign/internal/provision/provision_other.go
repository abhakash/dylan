//go:build !darwin

// Package provision — non-darwin stub.
//
// The Developer Portal client (api.go) is darwin-only because the full flow
// assumes macOS Keychain + local anisette. This file provides the same
// exported API surface on Linux so `GOOS=linux go build ./...` and
// `go vet ./...` pass on CI. All network methods return clearly-marked
// placeholder data and must not be mistaken for real provisioning: on Linux
// there is no Keychain/AOSKit, so real signing always happens on macOS.
package provision

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"log"
	"net/http"
	"time"

	"github.com/abhakash/dylan/tools/dylan-sign/internal/anisette"
	"github.com/abhakash/dylan/tools/dylan-sign/internal/auth"
)

const (
	// ProtocolVersion mirrors ALTProtocolVersion = @"QH65B2" (ALTAppleAPI.m:28).
	ProtocolVersion = "QH65B2"
	// ClientID mirrors ALTClientID = @"XABBG36SBA" (ALTAppleAPI.m:29).
	ClientID = "XABBG36SBA"
)

// API is the Developer Portal client stub for non-darwin builds.
// Same shape as the darwin implementation so callers compile cross-platform.
type API struct {
	Session    *auth.Session
	Anisette   *anisette.Data
	HTTPClient *http.Client
}

// Types mirror AltSign models (see api.go for full docs).
type Team struct {
	Name       string `json:"name" plist:"name"`
	Identifier string `json:"teamId" plist:"teamId"`
	Type       string `json:"type" plist:"type"`
}

type Device struct {
	Name       string `json:"name" plist:"name"`
	Identifier string `json:"deviceNumber" plist:"identifier"`
	Type       string `json:"deviceClass" plist:"type"`
}

type Certificate struct {
	Identifier        string            `json:"id" plist:"id"`
	Name              string            `json:"name" plist:"name"`
	SerialNumber      string            `json:"serialNumber" plist:"serialNumber"`
	MachineName       string            `json:"machineName" plist:"machineName"`
	MachineIdentifier string            `json:"machineId" plist:"machineId"`
	Data              []byte            `json:"certContent" plist:"certContent"`
	PrivateKey        crypto.PrivateKey `json:"-"`
	P12Data           []byte            `json:"-"`
}

type AppID struct {
	Name             string                 `json:"name" plist:"name"`
	Identifier       string                 `json:"appIdId" plist:"appIdId"`
	BundleIdentifier string                 `json:"identifier" plist:"identifier"`
	ExpirationDate   *time.Time             `json:"expirationDate,omitempty" plist:"expirationDate"`
	Features         map[string]interface{} `json:"features,omitempty" plist:"features"`
}

type AppGroup struct {
	Name            string `json:"name" plist:"name"`
	Identifier      string `json:"applicationGroup" plist:"identifier"`
	GroupIdentifier string `json:"identifier" plist:"groupIdentifier"`
}

type Profile struct {
	Name           string                 `json:"name" plist:"name"`
	Identifier     string                 `json:"provisioningProfileId" plist:"provisioningProfileId"`
	UUID           string                 `json:"UUID" plist:"UUID"`
	BundleID       string                 `json:"appIdId" plist:"bundleIdentifier"`
	TeamIdentifier string                 `json:"teamId" plist:"teamId"`
	Data           []byte                 `json:"encodedProfile" plist:"encodedProfile"`
	Entitlements   map[string]interface{} `json:"entitlements" plist:"entitlements"`
	Expiration     time.Time              `json:"expirationDate" plist:"expirationDate"`
	Certificates   []Certificate          `json:"certificates" plist:"certificates"`
	DeviceIDs      []string               `json:"deviceIds" plist:"deviceIds"`
	CreationDate   time.Time              `json:"creationDate" plist:"creationDate"`
}

func linuxStub(method string) {
	log.Printf("[provision] %s: non-darwin stub — placeholder data only (real provisioning requires macOS)", method)
}

func (a *API) FetchTeams() ([]Team, error) {
	linuxStub("FetchTeams")
	return []Team{
		{Name: "Example Team", Identifier: "TEAMID1234", Type: "Individual"},
		{Name: "Free Team", Identifier: "FREE123456", Type: "Free"},
	}, nil
}

func (a *API) FetchDevices(team Team) ([]Device, error) {
	linuxStub("FetchDevices")
	return []Device{
		{Name: "iPhone (stub)", Identifier: "00008030-00123456789ABC", Type: "iphone"},
	}, nil
}

func (a *API) RegisterDevice(team Team, name, udid string) (Device, error) {
	linuxStub("RegisterDevice")
	if name == "" || udid == "" {
		return Device{}, fmt.Errorf("provision: RegisterDevice: name and udid required")
	}
	return Device{Name: name, Identifier: udid, Type: "iphone"}, nil
}

func (a *API) FetchCertificates(team Team) ([]Certificate, error) {
	linuxStub("FetchCertificates")
	return []Certificate{
		{Identifier: "CERTID123", Name: "iOS Development (stub)", SerialNumber: "00:11:22:33"},
	}, nil
}

func (a *API) AddCertificate(team Team, machineName string) (Certificate, error) {
	linuxStub("AddCertificate")
	if machineName == "" {
		machineName = "Dylan Mac"
	}
	csrPEM, priv, err := GenerateCSR()
	if err != nil {
		return Certificate{}, fmt.Errorf("provision: AddCertificate: generate CSR: %w", err)
	}
	_ = csrPEM
	return Certificate{
		Identifier:  "CERT-NEW-stub",
		Name:        "iOS Development",
		MachineName: machineName,
		Data:        []byte("-----BEGIN CERTIFICATE-----\nMIIB...stub...\n-----END CERTIFICATE-----"),
		PrivateKey:  priv,
	}, nil
}

func (a *API) RevokeCertificate(team Team, cert Certificate) error {
	linuxStub("RevokeCertificate")
	return nil
}

func (a *API) FetchAppIDs(team Team) ([]AppID, error) {
	linuxStub("FetchAppIDs")
	return []AppID{
		{Name: "Dylan Player (stub)", Identifier: "APPID1234", BundleIdentifier: "app.dylan.player.ios.example"},
	}, nil
}

func (a *API) AddAppID(team Team, name, bundleID string) (AppID, error) {
	linuxStub("AddAppID")
	if bundleID == "" {
		return AppID{}, fmt.Errorf("provision: AddAppID: bundleID required")
	}
	if name == "" {
		name = bundleID
	}
	return AppID{Name: name, Identifier: "APPID-stub", BundleIdentifier: bundleID}, nil
}

func (a *API) DeleteAppID(team Team, appID AppID) error {
	linuxStub("DeleteAppID")
	return nil
}

func (a *API) UpdateAppID(team Team, appID AppID) (AppID, error) {
	linuxStub("UpdateAppID")
	return appID, nil
}

func (a *API) FetchAppGroups(team Team) ([]AppGroup, error) {
	linuxStub("FetchAppGroups")
	return []AppGroup{
		{Name: "group.stub", Identifier: "GROUPID123", GroupIdentifier: "group.app.dylan.player"},
	}, nil
}

func (a *API) AddAppGroup(team Team, name, groupIdentifier string) (AppGroup, error) {
	linuxStub("AddAppGroup")
	if groupIdentifier == "" {
		return AppGroup{}, fmt.Errorf("provision: AddAppGroup: groupIdentifier required")
	}
	return AppGroup{Name: name, Identifier: "GRP-stub", GroupIdentifier: groupIdentifier}, nil
}

func (a *API) AssignAppIDToGroups(team Team, appID AppID, groups []AppGroup) error {
	linuxStub("AssignAppIDToGroups")
	return nil
}

func (a *API) FetchProvisioningProfile(team Team, appID AppID) (Profile, error) {
	linuxStub("FetchProvisioningProfile")
	now := time.Now()
	return Profile{
		Name:           "iOS Team Provisioning Profile: " + appID.BundleIdentifier,
		Identifier:     "PP-stub",
		UUID:           "00000000-0000-4000-8000-000000000000",
		BundleID:       appID.BundleIdentifier,
		TeamIdentifier: team.Identifier,
		Data:           []byte("stub mobileprovision data"),
		Entitlements:   map[string]interface{}{"application-identifier": team.Identifier + "." + appID.BundleIdentifier},
		Expiration:     now.Add(7 * 24 * time.Hour),
		CreationDate:   now,
		DeviceIDs:      []string{},
	}, nil
}

func (a *API) DeleteProfile(team Team, profile Profile) error {
	linuxStub("DeleteProfile")
	return nil
}

// ---------------------------------------------------------------------------
// CSR helpers — pure-Go crypto, portable (mirrors csr.go subject).
// ---------------------------------------------------------------------------

// CSRRequest holds a PEM CSR and its private key.
type CSRRequest struct {
	Data       []byte
	PrivateKey crypto.PrivateKey
}

// GenerateCSR generates an RSA 2048 key + PKCS#10 CSR (AltSign subject).
func GenerateCSR() ([]byte, crypto.PrivateKey, error) {
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return nil, nil, fmt.Errorf("provision: generate RSA key: %w", err)
	}
	csrPEM, err := createCSR(priv)
	if err != nil {
		return nil, nil, err
	}
	return csrPEM, priv, nil
}

// GenerateECCSR generates an ECDSA P-256 key + CSR.
func GenerateECCSR() ([]byte, crypto.PrivateKey, error) {
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, fmt.Errorf("provision: generate EC key: %w", err)
	}
	csrPEM, err := createCSR(priv)
	if err != nil {
		return nil, nil, err
	}
	return csrPEM, priv, nil
}

// NewCSRRequest wraps GenerateCSR; NewECCSRRequest wraps GenerateECCSR.
func NewCSRRequest() (*CSRRequest, error) {
	csrPEM, priv, err := GenerateCSR()
	if err != nil {
		return nil, err
	}
	return &CSRRequest{Data: csrPEM, PrivateKey: priv}, nil
}

// NewECCSRRequest creates an EC variant.
func NewECCSRRequest() (*CSRRequest, error) {
	csrPEM, priv, err := GenerateECCSR()
	if err != nil {
		return nil, err
	}
	return &CSRRequest{Data: csrPEM, PrivateKey: priv}, nil
}

func createCSR(priv crypto.PrivateKey) ([]byte, error) {
	template := &x509.CertificateRequest{
		Subject: pkix.Name{
			Country:      []string{"US"},
			Province:     []string{"CA"},
			Locality:     []string{"Los Angeles"},
			Organization: []string{"AltSign"},
			CommonName:   "AltSign",
		},
		SignatureAlgorithm: x509.SHA256WithRSA,
	}
	if _, isEC := priv.(*ecdsa.PrivateKey); isEC {
		template.SignatureAlgorithm = x509.ECDSAWithSHA256
	}
	csrDER, err := x509.CreateCertificateRequest(rand.Reader, template, priv)
	if err != nil {
		return nil, fmt.Errorf("provision: create CSR: %w", err)
	}
	csrPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE REQUEST", Bytes: csrDER})
	if csrPEM == nil {
		return nil, fmt.Errorf("provision: pem encode CSR failed")
	}
	return csrPEM, nil
}

// GenerateCSRWithKey generates a CSR using an existing private key.
func GenerateCSRWithKey(priv crypto.PrivateKey) ([]byte, error) {
	switch priv.(type) {
	case *rsa.PrivateKey, *ecdsa.PrivateKey:
		return createCSR(priv)
	default:
		return nil, fmt.Errorf("provision: unsupported private key type %T", priv)
	}
}
