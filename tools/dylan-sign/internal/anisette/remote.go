package anisette

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// remoteProvider fetches anisette data from a SideStore/AltStore-style
// anisette server. It is portable (no darwin build tag) so CI/linux builds
// can still compile and stub the provider.
//
// Endpoint convention (SideStore v3): GET http://host:port/anisette
// The server may return either:
//  1. A Data-shaped JSON object (fields like machineID, oneTimePassword, ...)
//  2. A header-map JSON object (keys like "X-Apple-I-MD", ...)
//
// Both are accepted; (2) is converted via FromHeaders.
type remoteProvider struct {
	url    string
	client *http.Client
}

func newRemoteProvider(rawURL string) Provider {
	rawURL = strings.TrimSpace(rawURL)
	// Normalise: trim trailing slash but keep path; caller may pass
	// "http://host:port" or "http://host:port/anisette".
	rawURL = strings.TrimRight(rawURL, "/")
	return &remoteProvider{
		url: rawURL,
		client: &http.Client{
			Timeout: 10 * time.Second,
		},
	}
}

// Fetch performs HTTP GET to the configured URL and decodes the response
// into Data. Stub-friendly: if the server is unreachable an error is returned
// with a hint to start a local anisette server.
func (r *remoteProvider) Fetch() (*Data, error) {
	if r.url == "" {
		return nil, fmt.Errorf("anisette: remote URL is empty (hint: pass --anisette http://host:port/anisette)")
	}
	// If caller passed bare host:port without path, many servers expose
	// the endpoint at /anisette. Try the URL as-is; the caller is expected
	// to provide the full endpoint. We do not silently rewrite to avoid
	// double-path issues.
	target := r.url
	// Heuristic: if URL has no path component (just scheme://host:port),
	// append /anisette for convenience.
	if !strings.Contains(strings.TrimPrefix(strings.TrimPrefix(target, "http://"), "https://"), "/") {
		target += "/anisette"
	}

	req, err := http.NewRequest(http.MethodGet, target, nil)
	if err != nil {
		return nil, fmt.Errorf("anisette: build request %q: %w", target, err)
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "dylan-sign/0.1 anisette-remote")

	resp, err := r.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("anisette: GET %q failed: %w (is the anisette server running?)", target, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return nil, fmt.Errorf("anisette: GET %q returned %d %s: %s", target, resp.StatusCode, resp.Status, strings.TrimSpace(string(body)))
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, fmt.Errorf("anisette: read body: %w", err)
	}
	if len(body) == 0 {
		return nil, fmt.Errorf("anisette: empty response from %q", target)
	}

	// First try direct Data JSON.
	if d, err := FromJSON(body); err == nil && d.MachineID != "" {
		// Accept if at least MachineID was populated; some servers omit
		// Device* fields for privacy.
		return d, nil
	}

	// Second, try header-map style: {"X-Apple-I-MD": "...", ...}
	var hdrMap map[string]string
	if err := json.Unmarshal(body, &hdrMap); err == nil {
		// Detect header-map by presence of X-Apple keys.
		for k := range hdrMap {
			if strings.HasPrefix(k, "X-Apple-") || strings.HasPrefix(k, "X-Mme-") {
				return FromHeaders(hdrMap), nil
			}
		}
	}

	// Third, try generic map with mixed types (some servers return
	// routingInfo as number, headers as strings, etc.).
	var gen map[string]interface{}
	if err := json.Unmarshal(body, &gen); err == nil {
		// Build header map from whatever is present.
		h := make(map[string]string, len(gen))
		for k, v := range gen {
			switch val := v.(type) {
			case string:
				h[k] = val
			case float64:
				// JSON numbers decode as float64.
				if k == "routingInfo" || k == "RoutingInfo" || k == "X-Apple-I-MD-RINFO" {
					h["X-Apple-I-MD-RINFO"] = strconv.FormatUint(uint64(val), 10)
				} else {
					h[k] = strconv.FormatFloat(val, 'f', -1, 64)
				}
			}
		}
		// Also try direct Data remapping for camelCase keys.
		if _, ok := gen["machineID"]; ok {
			if d, err := FromJSON(body); err == nil {
				return d, nil
			}
		}
		if len(h) > 0 {
			if d := FromHeaders(h); d.MachineID != "" {
				return d, nil
			}
		}
	}

	return nil, fmt.Errorf("anisette: unable to decode response from %q: %s", target, truncate(string(body), 500))
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
