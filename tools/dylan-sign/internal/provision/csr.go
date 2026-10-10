//go:build darwin
// +build darwin

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
)

// CSRRequest mirrors ALTCertificateRequest (ALTCertificateRequest.m/.h).
// It holds the PEM-encoded CSR and the private key that signed it.
type CSRRequest struct {
	Data       []byte            // PEM-encoded CSR (-----BEGIN CERTIFICATE REQUEST-----)
	PrivateKey crypto.PrivateKey // RSA 2048 or ECDSA P-256
}

// GenerateCSR generates a new private key (RSA 2048 by default) and a PKCS#10
// Certificate Signing Request, mimicking ALTCertificateRequest.m
// generateRequest:privateKey:. The subject matches AltSign:
//
//	C=US, ST=CA, L=Los Angeles, O=AltSign, CN=AltSign
//
// The CSR is signed with SHA256 (AltSign used SHA1 via EVP_sha1, but SHA256
// is preferred and accepted by Apple). Returns PEM-encoded CSR and private key.
//
// This stub logs and returns placeholder data for offline development; real
// provisioning will POST csrContent to ios/submitDevelopmentCSR.action.
func GenerateCSR() ([]byte, crypto.PrivateKey, error) {
	log.Printf("[provision] GenerateCSR: generating RSA 2048 key + CSR (AltSign subject C=US/ST=CA/L=Los Angeles/O=AltSign/CN=AltSign)")
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return nil, nil, fmt.Errorf("provision: generate RSA key: %w", err)
	}
	csrPEM, err := createCSR(priv, &priv.PublicKey)
	if err != nil {
		return nil, nil, err
	}
	log.Printf("[provision] GenerateCSR → %d bytes CSR PEM (RSA 2048)", len(csrPEM))
	return csrPEM, priv, nil
}

// GenerateECCSR generates an ECDSA P-256 key + CSR. Useful as an alternative
// to RSA 2048; Apple accepts both for iOS Development certificates.
func GenerateECCSR() ([]byte, crypto.PrivateKey, error) {
	log.Printf("[provision] GenerateECCSR: generating ECDSA P-256 key + CSR")
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, fmt.Errorf("provision: generate EC key: %w", err)
	}
	csrPEM, err := createCSR(priv, &priv.PublicKey)
	if err != nil {
		return nil, nil, err
	}
	log.Printf("[provision] GenerateECCSR → %d bytes CSR PEM (ECDSA P-256)", len(csrPEM))
	return csrPEM, priv, nil
}

// NewCSRRequest is a convenience wrapper that returns a CSRRequest struct
// analogous to [[ALTCertificateRequest alloc] init] (AltSign).
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

// createCSR builds a PKCS#10 request with AltSign subject and signs it.
func createCSR(priv crypto.PrivateKey, pub crypto.PublicKey) ([]byte, error) {
	subj := pkix.Name{
		Country:            []string{"US"},
		Province:           []string{"CA"},
		Locality:           []string{"Los Angeles"},
		Organization:       []string{"AltSign"},
		CommonName:         "AltSign",
		OrganizationalUnit: []string{},
	}
	template := &x509.CertificateRequest{
		Subject:            subj,
		SignatureAlgorithm: x509.SHA256WithRSA, // overridden for EC below
	}
	// Adjust SignatureAlgorithm for ECDSA
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
	// Validate by parsing back (defense)
	if _, err := x509.ParseCertificateRequest(csrDER); err != nil {
		return nil, fmt.Errorf("provision: CSR failed validation: %w", err)
	}
	_ = pub // keep signature consistent; pub is embedded via priv
	return csrPEM, nil
}

// GenerateCSRWithKey generates a CSR using an existing private key.
// Useful when the caller already has a persisted key (e.g. from cert P12).
func GenerateCSRWithKey(priv crypto.PrivateKey) ([]byte, error) {
	var pub crypto.PublicKey
	switch k := priv.(type) {
	case *rsa.PrivateKey:
		pub = &k.PublicKey
	case *ecdsa.PrivateKey:
		pub = &k.PublicKey
	default:
		return nil, fmt.Errorf("provision: unsupported private key type %T", priv)
	}
	return createCSR(priv, pub)
}

var _ = pem.Block{} // ensure encoding/pem import is used if stub paths change
