package auth

import (
	"fmt"

	"github.com/abhakash/dylan/tools/dylan-sign/internal/anisette"
)

// Apple Grand Slam (GSA) authentication — pure-Go stub.
//
// This file ports the flow from AltSign's ALTAppleAPI+Authentication.m
// (and AltOperations/ALTAppleAPIServerData.m) into Go, but without
// requiring CGO/corecrypto. Real crypto would use PBKDF2, SRP-6a and
// Apple's proprietary s2k/s2k_fo. Here we document the steps and stub
// the network calls so the package compiles on any platform.
//
// AltSign references:
//   - AltSign/Operations/ALTAppleAPI+Authentication.m  ~ `authenticateWithAnisetteData:completion:`
//   - AltSign/Operations/ALTAppleAPIServerData.m       ~ request builders for GsService2
//   - AltSign/Operations/ALTAppleAPI.m                 ~ session / DSID handling
//   - AltStore/Authentication/ALTAppleAPI+Auth.m (SideStore fork)
//
// Real endpoints (all POST, plist-encoded, anisette headers required):
//   POST https://gsa.apple.com/grandslam/GsService2/init
//     → returns: { Status: EC=-20101 (auth required) | -21669 (2FA), sp, s, iterations, salt, B, c, ... }
//   POST https://gsa.apple.com/grandslam/GsService2/authenticate  (SRP step 2, proves M1)
//     → body: { c, M1, ... }  ← client proves knowledge of password without sending it
//     → returns: { M2, sp, ... }  ← server proves knowledge, plus idmsToken / X-Apple-I-MD-M
//   POST https://gsa.apple.com/grandslam/GsService2/spd  (trusted devices)
//   POST https://gsa.apple.com/grandslam/GsService2/verify/trusteddevice
//   POST https://gsa.apple.com/grandslam/GsService2/verify/trusteddevice/verify
//   POST https://gsa.apple.com/grandslam/GsService2/apptokens  (get authToken / DSID)
//   POST https://buy.itunes.apple.com/WebObjects/MZFinance.woa/wa/authenticate  (legacy fallback)
//
// SRP-6a overview (Apple variant):
//   N, g — large safe prime + generator (2048-bit, from Apple's bootstrap)
//   s — salt (per-account, returned by init)
//   x = PBKDF2-HMAC-SHA256(password, salt, iterations, dkLen)  (see s2k/s2k_fo below)
//   v = g^x mod N  (verifier; never sent, but server stores it)
//   Client: a random, A = g^a mod N
//   Server: b random, B = (k*v + g^b) mod N  (Apple sends B, s, iterations, N, g)
//   Shared: u = H(A,B), S = (B - k*g^x)^{a + u*x} mod N, K = H(S)
//   Proofs: M1 = H(A,B,K), M2 = H(A,M1,K)  — mutual authentication
//
// Apple-specific s2k / s2k_fo (from AltSign's ALTAppleAPI+Authentication.m):
//   s2k   : PBKDF2-HMAC-SHA256 with iterations from server + per-account salt
//   s2k_fo: "forgot password" variant used when account needs extra stretching;
//           AltSign selects s2k_fo when server Cost indicates it. Both produce x
//           for SRP. The `iterations` and `salt` come from the GsService2/init
//           response's `sp` field (decoded as SRP init plist).
//
// TODO(real implementation):
//   1. Implement PBKDF2-HMAC-SHA256 via golang.org/x/crypto/pbkdf2 (pure Go, no CGO).
//        x = pbkdf2.Key([]byte(password), salt, iterations, 32, sha256.New)
//      Apple uses an additional HMAC step for s2k_fo; mirror AltSign's
//        `+ s2k_fo:` method which double-hashes with a pepper.
//   2. Implement SRP-6a big-integer math with math/big (modExp, etc.) and
//        constant-time M1/M2 comparison. No corecrypto/CGO needed.
//   3. HTTP client: plist-encode requests (howardstark/plist or golang plist),
//        attach anisette headers (X-Apple-I-MD*, X-MMe-* from anisette.Data),
//        handle cookies / X-Apple-I-MD-M one-time password, and parse
//        response plists for B, salt, iterations, M2, sp, c.
//   4. After M2 verification, call GsService2/apptokens to exchange
//        idmsToken → AuthToken + DSID, then persist via Save().
//   5. If server returns Status -21669 (HSA2 required), surface .requires2FA
//        and prompt for code via AuthenticateWith2FA.
//
// Current behaviour: all exported Authenticate* functions are stubs that
// document the above and return ErrInteractiveLogin so `go build ./...` and
// `go vet ./...` pass without network or crypto.

// ErrInteractiveLogin is returned by stubbed auth functions to instruct the
// user to run the real login flow.
var ErrInteractiveLogin = fmt.Errorf("interactive login required: run dylan-sign login")

// Authenticate performs the GSA SRP handshake with Apple.
// Stub: always returns ErrInteractiveLogin.
//
// Real implementation outline (see ALTAppleAPI+Authentication.m:42-180):
//  1. Validate appleID/password + anisette != nil and anisette.IsValid().
//  2. POST init to gsa.apple.com/grandslam/GsService2 with A = g^a mod N
//     + anisette headers. Parse salt, iterations, B, sp, c.
//  3. Compute x via s2k/s2k_fo (PBKDF2) → x, then S, K, M1.
//  4. POST authenticate with M1 + c + anisette; verify server M2.
//  5. POST apptokens to get DSID + AuthToken.
//  6. Return &Session{DSID, AuthToken, Anisette: anisette, CreatedAt, ExpiresAt}.
func Authenticate(appleID, password string, anisetteData *anisette.Data) (*Session, error) {
	// TODO: implement SRP handshake as above. For now stub.
	// Keep params referenced to avoid vet "unused" while still stubbing.
	_ = appleID
	_ = password
	_ = anisetteData

	// Example of what the real body would look like (comment only):
	//   a := randomBigInt(N)
	//   A := new(big.Int).Exp(g, a, N)
	//   salt := decodeSalt(initResp.Salt) // base64
	//   iters := initResp.Iterations
	//   x := pbkdf2.Key([]byte(password), salt, iters, 32, sha256.New) // s2k
	//   // s2k_fo variant: x = hmacSHA256(x, pepper) when server indicates
	//   // ... compute u, S, K, M1, M2 ...
	//   // verify M2 == serverM2 before trusting apptokens

	return nil, ErrInteractiveLogin
}

// AuthenticateWith2FA verifies a second-factor code after Authenticate
// returned a requires-2FA status.
//
// AltSign flow (ALTAppleAPI+Authentication.m ~ verifyTrustedDevice):
//
//	POST gsa.apple.com/grandslam/GsService2/verify/trusteddevice
//	POST gsa.apple.com/grandslam/GsService2/verify/trusteddevice/verify
//	  with { code, trustedDeviceID } + anisette headers, then re-call
//	  apptokens to finalize.
//
// Stub: always returns ErrInteractiveLogin.
// Signature matches the Authenticate context so callers can pass the pending
// session (or nil for password-based re-auth) plus code.
func AuthenticateWith2FA(sess *Session, code string, anisetteData *anisette.Data) (*Session, error) {
	// TODO: implement 2FA verification (see ALTAppleAPI+Authentication.m:verifyWithCode).
	_ = sess
	_ = code
	_ = anisetteData
	return nil, ErrInteractiveLogin
}

// AuthenticateWith2FAAndPassword is a convenience variant that takes the
// original appleID/password/code for callers that don't have a pending Session.
// Also stubbed.
func AuthenticateWith2FAAndPassword(appleID, password, code string, anisetteData *anisette.Data) (*Session, error) {
	_ = appleID
	_ = password
	_ = code
	_ = anisetteData
	return nil, ErrInteractiveLogin
}

// VerifyTrustedDevice lists/stimulates trusted devices (stub).
// Real endpoint: POST gsa.apple.com/grandslam/GsService2/verify/trusteddevice
func VerifyTrustedDevice(sess *Session, anisetteData *anisette.Data) error {
	_ = sess
	_ = anisetteData
	return ErrInteractiveLogin
}

// FetchAppTokens exchanges the GSA idmsToken for an AppTokens AuthToken/DSID
// (stub). Real endpoint: POST gsa.apple.com/grandslam/GsService2/apptokens
func FetchAppTokens(sess *Session, anisetteData *anisette.Data) (*Session, error) {
	_ = sess
	_ = anisetteData
	return nil, ErrInteractiveLogin
}

// Is2FARequired reports whether an error indicates 2FA is required.
// Stub: checks for ErrInteractiveLogin containing marker; real code would
// inspect the GSA Status code -21669 (HSA2) from the init response plist.
func Is2FARequired(err error) bool {
	if err == nil {
		return false
	}
	// In real code: plist Status == -21669 or EC == -20101 with HSA challenge.
	// Stub keeps the helper compiling for callers that branch on 2FA.
	return false
}
