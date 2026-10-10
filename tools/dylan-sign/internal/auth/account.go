package auth

import (
	"fmt"
)

// Account represents the Apple ID account metadata returned by the Developer
// Portal / App Store Connect after authentication.
//
// In AltSign this is populated from:
//   - AltSign/ALTAppleAPI+Account.m  (fetchAccount)
//   - App Store Connect `https://developerservices2.apple.com/services/QH65B2/listTeams`
//   - iTunes `https://buy.itunes.apple.com/WebObjects/MZFinance.woa/wa/authenticate`
//
// Real fields include personId (DSID), team memberships, roles, etc.
// This stub keeps the minimal fields needed for dylan-sign session display.
type Account struct {
	AppleID   string `json:"appleId"`
	PersonID  string `json:"personId"` // DSID — directory services identifier
	FirstName string `json:"firstName,omitempty"`
	LastName  string `json:"lastName,omitempty"`
	TeamID    string `json:"teamId,omitempty"`
}

// FullName returns "FirstName LastName" or AppleID if names are empty.
func (a *Account) FullName() string {
	if a == nil {
		return ""
	}
	if a.FirstName != "" || a.LastName != "" {
		if a.FirstName != "" && a.LastName != "" {
			return a.FirstName + " " + a.LastName
		}
		if a.FirstName != "" {
			return a.FirstName
		}
		return a.LastName
	}
	return a.AppleID
}

// FetchAccount returns account metadata for the authenticated session.
// Stub: always returns ErrInteractiveLogin after validating session.
//
// Real implementation (see ALTAppleAPI+Account.m / SideStore's
// ALTAppleAPI+Account+Teams.swift):
//  1. Ensure sess != nil, !sess.IsExpired(), sess.AuthToken != "" and DSID != "".
//  2. GET https://developerservices2.apple.com/services/QH65B2/listTeams
//     with headers: Cookie: myacinfo=<AuthToken>, X-Apple-I-MD-M etc. (anisette)
//  3. Parse plist/JSON for teams[], personId, firstName, lastName, and
//     select the requested TeamID. Cache to session.
//  4. Optionally call App Store Connect API to enrich roles/entitlements.
//
// TODO: wire to Developer Portal after Authenticate is implemented. No CGO.
func FetchAccount(sess *Session) (*Account, error) {
	if sess == nil {
		return nil, fmt.Errorf("nil session: %w", ErrInteractiveLogin)
	}
	if sess.IsExpired() {
		return nil, fmt.Errorf("session expired: %w", ErrInteractiveLogin)
	}
	if sess.AuthToken == "" {
		return nil, fmt.Errorf("missing auth token: %w", ErrInteractiveLogin)
	}
	// DSID may be empty in stub sessions; treat as interactive-login for now.
	// In real flow DSID is populated by FetchAppTokens (GsService2/apptokens).
	if sess.DSID == "" {
		return nil, fmt.Errorf("missing DSID: %w", ErrInteractiveLogin)
	}
	// Stub: synthesize minimal account from session so callers that only
	// display status can proceed without network.
	// Comment out to make this a hard stub that always requires login:
	//   return nil, ErrInteractiveLogin
	//
	// For compile-and-display convenience we return a synthetic account.
	// Real auth would replace this with a network call to listTeams.
	return &Account{
		AppleID:  "", // caller can fill from config if needed
		PersonID: sess.DSID,
	}, nil
}

// FetchAccountWithAppleID is a convenience that fills AppleID from the caller
// when the session's DSID is present but the account AppleID is not yet known.
func FetchAccountWithAppleID(sess *Session, appleID string) (*Account, error) {
	acct, err := FetchAccount(sess)
	if err != nil {
		return nil, err
	}
	if acct.AppleID == "" && appleID != "" {
		acct.AppleID = appleID
	}
	return acct, nil
}
