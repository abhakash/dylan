package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"runtime"
	"strings"
	"time"

	"github.com/abhakash/dylan/tools/dylan-sign/internal/anisette"
	"github.com/abhakash/dylan/tools/dylan-sign/internal/auth"
	"github.com/abhakash/dylan/tools/dylan-sign/internal/bundle"
	"github.com/abhakash/dylan/tools/dylan-sign/internal/config"
	"github.com/abhakash/dylan/tools/dylan-sign/internal/install"
	"github.com/abhakash/dylan/tools/dylan-sign/internal/provision"
	"github.com/abhakash/dylan/tools/dylan-sign/internal/sign"
	"github.com/abhakash/dylan/tools/dylan-sign/internal/store"
	"github.com/spf13/cobra"
)

var (
	Version = "0.1.0-dev"
	debug   bool
)

func main() {
	if runtime.GOOS != "darwin" {
		fmt.Fprintln(os.Stderr, "warning: dylan-sign is intended for macOS (darwin) only; some features may not work on this platform")
	}

	root := &cobra.Command{
		Use:   "dylan-sign",
		Short: "Dylan iOS signing helper (macOS only)",
		Long: `dylan-sign — Dylan iOS signing helper for macOS.

Manages Apple ID session, provisioning profiles and IPA signing.
MacBook-only: uses local AOSKit anisette, Keychain for secrets, unique bundle per user.`,
		SilenceUsage: true,
		PersistentPreRunE: func(cmd *cobra.Command, args []string) error {
			if debug {
				if err := printAnisetteDebug(cmd); err != nil {
					fmt.Fprintf(os.Stderr, "debug: anisette fetch failed: %v\n", err)
				}
			}
			return nil
		},
		RunE: func(cmd *cobra.Command, args []string) error {
			if debug {
				return nil
			}
			return cmd.Help()
		},
	}
	root.PersistentFlags().BoolVar(&debug, "debug", false, "enable debug output (print anisette data)")

	root.AddCommand(newLoginCmd())
	root.AddCommand(newSignCmd())
	root.AddCommand(newStatusCmd())
	root.AddCommand(newRefreshCmd())
	root.AddCommand(newInstallCmd())
	root.AddCommand(newVersionCmd())

	if err := root.Execute(); err != nil {
		os.Exit(1)
	}
}

func printAnisetteDebug(cmd *cobra.Command) error {
	anisetteURL := ""
	if f := cmd.Flags().Lookup("anisette"); f != nil {
		anisetteURL, _ = cmd.Flags().GetString("anisette")
	}
	if anisetteURL == "" {
		if cmd.Parent() != nil {
			if f := cmd.Parent().PersistentFlags().Lookup("anisette"); f != nil {
				anisetteURL, _ = cmd.Parent().PersistentFlags().GetString("anisette")
			}
		}
		if anisetteURL == "" {
			if f := cmd.Root().PersistentFlags().Lookup("anisette"); f != nil {
				anisetteURL, _ = cmd.Root().PersistentFlags().GetString("anisette")
			}
		}
	}
	var p anisette.Provider
	if anisetteURL != "" && (anisetteURL == "remote" || len(anisetteURL) > 4 && (strings.HasPrefix(anisetteURL, "http://") || strings.HasPrefix(anisetteURL, "https://"))) {
		p = anisette.NewRemoteProvider(anisetteURL)
		fmt.Fprintf(os.Stderr, "debug: using remote anisette provider %q\n", anisetteURL)
	} else {
		p = anisette.NewDarwinProvider()
		fmt.Fprintln(os.Stderr, "debug: using darwin anisette provider")
	}
	data, err := p.Fetch()
	if err != nil {
		return err
	}
	fmt.Fprintln(os.Stderr, "debug: anisette data fetched")
	j, _ := json.MarshalIndent(data, "", "  ")
	fmt.Fprintf(os.Stderr, "%s\n", string(j))
	fmt.Fprintln(os.Stderr, "debug: X-Apple-I-MD-* headers:")
	for k, v := range data.ToHeaders() {
		fmt.Fprintf(os.Stderr, "  %s: %s\n", k, v)
	}
	if cmd.Name() == "dylan-sign" {
		fmt.Println(string(j))
	}
	return nil
}

func newLoginCmd() *cobra.Command {
	var appleID, team, password string
	cmd := &cobra.Command{
		Use:   "login",
		Short: "Authenticate with Apple ID (one-time, caches session in Keychain)",
		Long: `Login caches the Apple session (DSID+authToken) in Keychain so later
sign/refresh runs headless without 2FA prompt (AltStore-style).

First run will prompt for password and 6-digit 2FA code, then store
session.json (0600) + Keychain entry. Subsequent sign calls reuse it until expiry (~30d).`,
		RunE: func(cmd *cobra.Command, args []string) error {
			cfg, cfgPath, err := config.Load()
			if err != nil {
				return err
			}
			if appleID != "" {
				cfg.AppleID = appleID
			}
			if team != "" {
				cfg.TeamID = team
			}
			if cfg.AppleID == "" {
				return fmt.Errorf("--apple-id is required (or set %s / config.yaml)", config.EnvAppleID)
			}
			if err := store.EnsureDirs(); err != nil {
				return err
			}
			if err := config.Save(cfg); err != nil {
				return err
			}
			fmt.Printf("login: stored Apple ID %q", cfg.AppleID)
			if cfg.TeamID != "" {
				fmt.Printf(" (team %q)", cfg.TeamID)
			}
			fmt.Printf("\nconfig: %s\n", cfgPath)

			// Fetch anisette (darwin local)
			prov := anisette.NewDarwinProvider()
			ad, err := prov.Fetch()
			if err != nil {
				fmt.Fprintf(os.Stderr, "warning: anisette fetch failed: %v (continuing with synthetic)\n", err)
				ad = &anisette.Data{MachineID: "placeholder", OneTimePassword: "placeholder", LocalUserID: "placeholder", RoutingInfo: 171061, Date: time.Now()}
			} else {
				fmt.Printf("anisette: %s / %s (RINFO %d)\n", ad.MachineID[:8]+"...", ad.DeviceDescription, ad.RoutingInfo)
			}

			// Store password if supplied via flag or prompt
			st := store.New("", nil)
			kc := st.Keychain()
			if kc == nil {
				kc = auth.NewKeychain(st.BaseDir() + "/keychain.json")
			}
			if password != "" {
				if err := auth.SavePassword(kc, cfg.AppleID, password); err == nil {
					fmt.Println("password: stored in Keychain (system Keychain via go-keyring, fallback 0600)")
				} else {
					fmt.Fprintf(os.Stderr, "warning: keychain store failed (%v), fallback to file\n", err)
					_ = auth.SavePassword(auth.NewFileKeychain(st.BaseDir()+"/keychain.json"), cfg.AppleID, password)
					fmt.Println("password: stored in Keychain (file fallback 0600)")
				}
			} else {
				// Try loading existing password to avoid prompting
				if pwd, err := auth.LoadPassword(kc, cfg.AppleID); err == nil && pwd != "" {
					password = pwd
				}
			}

			// Attempt real GSA auth (stub currently returns ErrInteractiveLogin)
			sess, err := auth.Authenticate(cfg.AppleID, password, ad)
			if err != nil {
				if err == auth.ErrInteractiveLogin {
					// Create placeholder session for offline dev so sign can proceed without Apple
					// Real auth will be wired when SRP is implemented (see auth/gsa.go TODO).
					fmt.Fprintln(os.Stderr, "note: real Apple GSA not yet wired (stub) — creating placeholder session for offline signing")
					fmt.Fprintln(os.Stderr, "      on first real run: enter password + 6-digit code when prompted; session will be cached in Keychain")
					sess = &auth.Session{
						DSID:      "PLACEHOLDER-DSID",
						AuthToken: "PLACEHOLDER-TOKEN",
						Anisette:  ad,
						CreatedAt: time.Now(),
						ExpiresAt: time.Now().Add(30 * 24 * time.Hour),
					}
					_ = auth.Save(sess, st)
					_ = auth.SaveSession(kc, sess)
					fmt.Printf("session: placeholder written to %s (expires %s)\n", st.SessionPath(), sess.ExpiresAt.Format(time.RFC3339))
					fmt.Println("next: dylan-sign sign --ipa <unsigned> --udid <UDID> --out <signed>")
					return nil
				}
				return fmt.Errorf("authenticate: %w", err)
			}
			_ = auth.Save(sess, st)
			_ = auth.SaveSession(kc, sess)
			fmt.Printf("session: authenticated DSID %s expires %s\n", sess.DSID, sess.ExpiresAt.Format(time.RFC3339))
			return nil
		},
	}
	cmd.Flags().StringVar(&appleID, "apple-id", "", "Apple ID email (or env DYLAN_APPLE_ID)")
	cmd.Flags().StringVar(&team, "team", "", "Apple Team ID (or env DYLAN_TEAM)")
	cmd.Flags().StringVar(&password, "password", "", "Apple ID password (or will prompt / use Keychain)")
	return cmd
}

func newSignCmd() *cobra.Command {
	var ipa, appleID, udid, bundleID, out, anisetteURL, p12, profile string
	var strictFlag bool
	cmd := &cobra.Command{
		Use:   "sign",
		Short: "Sign an unsigned IPA for a device (MacBook-only, Keychain, unique bundle)",
		Long: `Sign an unsigned IPA. Flow (AltStore-style):

  1. Load config + session (reuse cached DSID/authToken from Keychain; no 2FA if valid)
  2. Anisette: darwin provider (AOSKit synthetic on this Mac) or --anisette remote for CI stretch
  3. Provision: teams/devices/AppIDs/profiles via Developer Portal QH65B2 (stub logs, real when session live)
  4. Sign: zsign -2 SHA256-only or codesign fallback; bundle rewritten to app.dylan.player.ios.<user>
  5. Install stub: prepared for go-ios phase-2 (currently sign-only)

Input must be unsigned (no _CodeSignature) per docs.
`,
		RunE: func(cmd *cobra.Command, args []string) error {
			cfg, _, _ := config.Load()
			if appleID == "" {
				appleID = cfg.AppleID
			}
			if udid == "" {
				udid = cfg.UDID
			}
			// Placeholders allowed per spec: use synthetic if still empty
			if appleID == "" {
				appleID = "__APPLE_ID_PLACEHOLDER__"
				fmt.Fprintf(os.Stderr, "warning: --apple-id not set, using placeholder %q (set DYLAN_APPLE_ID or --apple-id)\n", appleID)
			}
			if udid == "" {
				udid = "__UDID_PLACEHOLDER__"
				fmt.Fprintf(os.Stderr, "warning: --udid not set, using placeholder %q\n", udid)
			}
			if ipa == "" {
				return fmt.Errorf("--ipa is required")
			}
			if _, err := os.Stat(ipa); err != nil {
				return fmt.Errorf("ipa not found at %q: %w", ipa, err)
			}
			effectiveBundleID := bundle.UniqueBundleIDForSign("", bundleID, appleID)
			// Strict is default-on when real IDs are given: placeholder IDs,
			// copy-stub signing, and verify failures become hard errors.
			strict := strictFlag || (!bundle.IsPlaceholder(appleID) && !bundle.IsPlaceholder(udid))
			if strict && (bundle.IsPlaceholder(appleID) || bundle.IsPlaceholder(udid)) {
				return fmt.Errorf("strict: real --apple-id and --udid required (got placeholders); pass real IDs or re-run without --strict")
			}
			if out == "" {
				out = "Dylan-signed.ipa"
			}
			if err := store.EnsureDirs(); err != nil {
				return err
			}
			st := store.New("", nil)
			// Anisette
			var prov anisette.Provider
			if anisetteURL != "" && (strings.HasPrefix(anisetteURL, "http://") || strings.HasPrefix(anisetteURL, "https://")) {
				prov = anisette.NewRemoteProvider(anisetteURL)
			} else {
				prov = anisette.NewDarwinProvider()
			}
			ad, err := prov.Fetch()
			if err != nil {
				fmt.Fprintf(os.Stderr, "warning: anisette fetch failed: %v\n", err)
				ad = &anisette.Data{MachineID: "synthetic", OneTimePassword: "synthetic", LocalUserID: "synthetic", RoutingInfo: 171061, Date: time.Now()}
			}

			// Session reuse (AltStore-style: no 2FA if cached session valid)
			sess, err := auth.Load(st)
			if err != nil || sess == nil || sess.IsExpired() {
				if err != nil {
					fmt.Fprintf(os.Stderr, "note: no cached session (%v) — proceeding offline/placeholder\n", err)
				} else if sess != nil && sess.IsExpired() {
					fmt.Fprintf(os.Stderr, "note: cached session expired %s — would re-auth via Keychain (stub)\n", sess.ExpiresAt.Format(time.RFC3339))
				}
				// Offline placeholder session so pipeline can still sign
				sess = &auth.Session{DSID: "PLACEHOLDER-DSID", AuthToken: "PLACEHOLDER-TOKEN", Anisette: ad, CreatedAt: time.Now(), ExpiresAt: time.Now().Add(7 * 24 * time.Hour)}
			} else {
				fmt.Printf("session: reuse DSID %s (expires %s)\n", sess.DSID, sess.ExpiresAt.Format(time.RFC3339))
			}

			// Provision stubs (real when GSA wired; currently logs endpoint intent)
			if udid != "__UDID_PLACEHOLDER__" && appleID != "__APPLE_ID_PLACEHOLDER__" {
				api := &provision.API{Session: sess, Anisette: ad}
				if teams, err := api.FetchTeams(); err == nil && len(teams) > 0 {
					team := teams[0]
					if cfg.TeamID != "" {
						for _, t := range teams {
							if t.Identifier == cfg.TeamID {
								team = t
								break
							}
						}
					}
					fmt.Printf("provision: team %s (%s)\n", team.Name, team.Identifier)
					_, _ = api.FetchDevices(team)
					if udid != "" {
						_, _ = api.RegisterDevice(team, "Dylan MacBook", udid)
					}
					_, _ = api.FetchAppIDs(team)
					_, _ = api.AddAppID(team, "Dylan Player", effectiveBundleID)
					if appIDs, err := api.FetchAppIDs(team); err == nil && len(appIDs) > 0 {
						_, _ = api.FetchProvisioningProfile(team, appIDs[0])
					}
				}
			} else {
				fmt.Println("provision: placeholder IDs — skipping Developer Portal calls (set real --apple-id/--udid to enable)")
			}

			fmt.Printf("sign: %s -> %s\n", ipa, out)
			fmt.Printf("  apple-id:  %s\n", appleID)
			fmt.Printf("  udid:      %s\n", udid)
			fmt.Printf("  bundle-id: %s\n", effectiveBundleID)
			fmt.Printf("  anisette:  %s (%s)\n", ad.MachineID[:8]+"...", ad.DeviceDescription)
			if p12 != "" {
				fmt.Printf("  p12:       %s\n", p12)
			}
			if profile != "" {
				fmt.Printf("  profile:   %s\n", profile)
			}
			if !sign.IsAvailable() {
				fmt.Fprintln(os.Stderr, "warning: no zsign/codesign found — install zsign: brew install zsign (stub will copy IPA)")
			}

			// Real pipeline: validates unsigned, rewrites bundle, zsign -2
			ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
			defer cancel()
			var pipeErr error
			if strict {
				fmt.Fprintln(os.Stderr, "strict: on (real IDs) — placeholder IDs, copy stub, and verify failures are fatal")
				pipeErr = sign.RunPipelineStrict(ctx, ipa, out, effectiveBundleID, p12, profile)
			} else {
				pipeErr = sign.RunPipeline(ctx, ipa, out, effectiveBundleID, p12, profile)
			}
			if pipeErr != nil {
				// If pipeline fails due to missing zsign, fallback to copy stub already in sign/zsign.go
				return fmt.Errorf("sign pipeline: %w", pipeErr)
			}
			fmt.Printf("signed: %s (bundle %s)\n", out, effectiveBundleID)
			fmt.Printf("store: %s\n", st.BaseDir())
			fmt.Println("next: install via ios-deploy / go-ios (phase 2) or AltServer; refresh before 7d expiry via 'dylan-sign refresh'")
			return nil
		},
	}
	cmd.Flags().StringVar(&ipa, "ipa", "", "Path to input unsigned .ipa (required)")
	cmd.Flags().StringVar(&appleID, "apple-id", "", "Apple ID email (or env DYLAN_APPLE_ID)")
	cmd.Flags().StringVar(&udid, "udid", "", "Device UDID (or env DYLAN_UDID)")
	cmd.Flags().StringVar(&bundleID, "bundle-id", "", "Override bundle identifier (default: app.dylan.player.ios.<user>)")
	cmd.Flags().StringVar(&out, "out", "", "Output signed IPA path (default: Dylan-signed.ipa)")
	cmd.Flags().StringVar(&anisetteURL, "anisette", "", "Remote anisette server URL (for CI stretch; default: darwin local)")
	cmd.Flags().StringVar(&p12, "p12", "", "Existing .p12 signing identity (optional; will fetch via Developer Portal if missing)")
	cmd.Flags().StringVar(&profile, "profile", "", "Existing .mobileprovision (optional)")
	cmd.Flags().BoolVar(&strictFlag, "strict", false, "Fail on placeholder IDs, copy-stub signing, and codesign verify errors (default-on when real --apple-id/--udid are given)")
	return cmd
}

func newStatusCmd() *cobra.Command {
	var appleID string
	cmd := &cobra.Command{
		Use:   "status",
		Short: "Show signing status / session / slots",
		RunE: func(cmd *cobra.Command, args []string) error {
			cfg, cfgPath, _ := config.Load()
			if appleID == "" {
				appleID = cfg.AppleID
			}
			if appleID == "" {
				appleID = "(none — set --apple-id or DYLAN_APPLE_ID)"
			}
			fmt.Printf("apple-id:   %s\n", appleID)
			fmt.Printf("team:       %s\n", cfg.TeamID)
			fmt.Printf("udid:       %s\n", cfg.UDID)
			fmt.Printf("config:     %s\n", cfgPath)
			fmt.Printf("session:    %s\n", store.SessionPath())
			fmt.Printf("certs:      %s\n", store.CertsDir())
			fmt.Printf("profiles:   %s\n", store.ProfilesDir())
			st := store.New("", nil)
			if sess, err := auth.Load(st); err == nil && sess != nil {
				fmt.Printf("session:    DSID %s created %s expires %s expired=%v\n", sess.DSID, sess.CreatedAt.Format(time.RFC3339), sess.ExpiresAt.Format(time.RFC3339), sess.IsExpired())
				if sess.Anisette != nil {
					fmt.Printf("anisette:   %s / %s\n", sess.Anisette.MachineID, sess.Anisette.DeviceDescription)
				}
			} else {
				fmt.Printf("session:    not found (%v)\n", err)
			}
			kcPath := st.BaseDir() + "/keychain.json"
			if _, err := os.Stat(kcPath); err == nil {
				fmt.Printf("keychain:   %s (file fallback 0600)\n", kcPath)
			} else {
				fmt.Printf("keychain:   %s (not found)\n", kcPath)
			}
			// Provision stub status when real session
			if sess, err := auth.Load(st); err == nil && sess != nil && !sess.IsExpired() && appleID != "(none — set --apple-id or DYLAN_APPLE_ID)" {
				_ = sess
				fmt.Println("provision:  (run with real DSID to query Developer Portal slots)")
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&appleID, "apple-id", "", "Apple ID email (or env DYLAN_APPLE_ID)")
	return cmd
}

func newRefreshCmd() *cobra.Command {
	var appleID, udid string
	cmd := &cobra.Command{
		Use:   "refresh",
		Short: "Refresh provisioning profile before 7-day expiry (AltStore-style)",
		Long: `Re-signs before 7-day expiry. Reuses cached session (no 2FA).

  Ideal: run via launchd daily: dylan-sign refresh --apple-id ... --udid ...
  If profile expires <24h, re-fetches via Developer Portal and re-signs cached IPA.`,
		RunE: func(cmd *cobra.Command, args []string) error {
			cfg, _, _ := config.Load()
			if appleID == "" {
				appleID = cfg.AppleID
			}
			if udid == "" {
				udid = cfg.UDID
			}
			if appleID == "" {
				return fmt.Errorf("--apple-id is required (or set %s)", config.EnvAppleID)
			}
			if udid == "" {
				return fmt.Errorf("--udid is required (or set %s)", config.EnvUDID)
			}
			st := store.New("", nil)
			sess, err := auth.Load(st)
			if err != nil || sess == nil {
				return fmt.Errorf("no session: run dylan-sign login first: %w", err)
			}
			if sess.IsExpired() {
				// Try silent re-auth via Keychain password (real Keychain with fallback)
				kc := st.Keychain()
				if kc == nil {
					kc = auth.NewKeychain(st.BaseDir() + "/keychain.json")
				}
				if pwd, err := auth.LoadPassword(kc, appleID); err == nil && pwd != "" {
					ad, _ := anisette.NewDarwinProvider().Fetch()
					if newSess, err := auth.Authenticate(appleID, pwd, ad); err == nil {
						sess = newSess
						_ = auth.Save(sess, st)
					} else {
						fmt.Fprintf(os.Stderr, "refresh: session expired and silent re-auth failed (%v) — need interactive login\n", err)
						return fmt.Errorf("session expired: run dylan-sign login")
					}
				} else {
					return fmt.Errorf("session expired: run dylan-sign login")
				}
			}
			fmt.Printf("refresh: profile for %s / %s (session %s expires %s)\n", appleID, udid, sess.DSID, sess.ExpiresAt.Format(time.RFC3339))
			fmt.Printf("  session:  %s\n", store.SessionPath())
			// Stub: would fetch profile and check Expiration - now, re-sign if <24h
			api := &provision.API{Session: sess, Anisette: sess.Anisette}
			if teams, err := api.FetchTeams(); err == nil && len(teams) > 0 {
				team := teams[0]
				if apps, err := api.FetchAppIDs(team); err == nil && len(apps) > 0 {
					if prof, err := api.FetchProvisioningProfile(team, apps[0]); err == nil {
						remaining := time.Until(prof.Expiration)
						fmt.Printf("  profile %s expires %s (in %s)\n", prof.Identifier, prof.Expiration.Format(time.RFC3339), remaining.Round(time.Hour))
						if remaining < 24*time.Hour {
							fmt.Println("  -> would re-fetch and re-sign now")
						} else {
							fmt.Println("  -> still valid, no action")
						}
					}
				}
			}
			fmt.Println("install:  stub (phase-2 go-ios will re-install over USB/Wi-Fi via installation_proxy)")
			return nil
		},
	}
	cmd.Flags().StringVar(&appleID, "apple-id", "", "Apple ID email (or env DYLAN_APPLE_ID)")
	cmd.Flags().StringVar(&udid, "udid", "", "Device UDID (or env DYLAN_UDID)")
	return cmd
}

func newInstallCmd() *cobra.Command {
	var ipa, udid, bundleID string
	cmd := &cobra.Command{
		Use:   "install",
		Short: "Install signed IPA via usbmuxd → installation_proxy (Xcode-free, phase-2)",
		Long: `Install a signed IPA onto a device via USB/Wi-Fi without Xcode.

Phase-2 wiring (Xcode-equivalent, go-ios):

  usbmuxd (/var/run/usbmuxd) → lockdown StartService(com.apple.mobile.installation_proxy)
  → DeviceConnection → installationproxy.New(device).Install(ipa)
  Equivalent to Xcode's MobileDevice.framework install, but headless.

Today (phase-1 sign-only): validates IPA/UDID, then delegates to internal/install.Install
which probes for ideviceinstaller / ios-deploy in PATH, else returns a helpful error
with brew / go-ios instructions. Still compiles on any platform (stub on !darwin).`,
		RunE: func(cmd *cobra.Command, args []string) error {
			cfg, _, _ := config.Load()
			if udid == "" {
				udid = cfg.UDID
			}
			if udid == "" {
				return fmt.Errorf("--udid is required (or set %s)", config.EnvUDID)
			}
			if ipa == "" {
				return fmt.Errorf("--ipa is required")
			}
			opts := install.Options{IPA: ipa, UDID: udid, BundleID: bundleID}
			fmt.Printf("install: %s -> %s", opts.IPA, opts.UDID)
			if opts.BundleID != "" {
				fmt.Printf(" (bundle %s)", opts.BundleID)
			}
			fmt.Println()
			if err := install.Install(opts); err != nil {
				return err
			}
			fmt.Println("installed")
			return nil
		},
	}
	cmd.Flags().StringVar(&ipa, "ipa", "", "Path to signed .ipa (required)")
	cmd.Flags().StringVar(&udid, "udid", "", "Device UDID (or env DYLAN_UDID)")
	cmd.Flags().StringVar(&bundleID, "bundle-id", "", "Override bundle identifier (optional, for logging)")
	_ = cmd.MarkFlagRequired("ipa")
	_ = cmd.MarkFlagRequired("udid")
	return cmd
}

func newVersionCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "version",
		Short: "Print version",
		Run: func(cmd *cobra.Command, args []string) {
			fmt.Printf("dylan-sign %s (%s/%s)\n", Version, runtime.GOOS, runtime.GOARCH)
		},
	}
}
