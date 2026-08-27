package anisette

// Data is defined in anisette.go (canonical type with RoutingInfo uint64).
// This file provides backward-compatible helpers (Headers, IsValid) so code
// that previously imported the stub data.go continues to compile. The original
// data.go defined a duplicate Data struct with RoutingInfo string, which
// conflicted with anisette.go. We keep only the helper methods here.

// Headers is a compatibility alias for ToHeaders.
// It returns the same map as ToHeaders, preserving the older method name
// used by early stubs. Prefer ToHeaders for new code.
func (d *Data) Headers() map[string]string {
	if d == nil {
		return map[string]string{}
	}
	// Reuse canonical ToHeaders to avoid duplicating header logic.
	return d.ToHeaders()
}

// IsValid reports whether the minimal required fields are present.
func (d *Data) IsValid() bool {
	if d == nil {
		return false
	}
	return d.MachineID != "" && d.OneTimePassword != "" && d.LocalUserID != ""
}
