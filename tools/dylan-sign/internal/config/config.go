package config

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"

	"gopkg.in/yaml.v3"
)

// Config holds persisted user configuration for dylan-sign.
type Config struct {
	AppleID string `yaml:"apple_id" json:"apple_id"`
	TeamID  string `yaml:"team_id" json:"team_id"`
	UDID    string `yaml:"udid" json:"udid"`
}

// Env keys.
const (
	EnvAppleID = "DYLAN_APPLE_ID"
	EnvUDID    = "DYLAN_UDID"
	EnvTeam    = "DYLAN_TEAM"
)

// Load returns config merged from env vars and file.
// Precedence: env vars override file values.
func Load() (*Config, string, error) {
	path := ConfigPath()
	cfg := &Config{}

	// Try file first (yaml or json).
	if data, err := os.ReadFile(path); err == nil {
		// Try YAML first, then JSON fallback.
		if yamlErr := yaml.Unmarshal(data, cfg); yamlErr != nil {
			var jsonErr error
			if jsonErr = json.Unmarshal(data, cfg); jsonErr != nil {
				// Return yaml error as primary if both fail, but not fatal — use empty cfg.
				_ = yamlErr
				_ = jsonErr
			}
		}
		if json.Valid(data) && cfg.AppleID == "" && cfg.TeamID == "" && cfg.UDID == "" {
			// If yaml silently produced empty struct but json has data, try json again.
			_ = json.Unmarshal(data, cfg)
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, path, err
	}

	// Env overrides.
	if v := os.Getenv(EnvAppleID); v != "" {
		cfg.AppleID = v
	}
	if v := os.Getenv(EnvUDID); v != "" {
		cfg.UDID = v
	}
	if v := os.Getenv(EnvTeam); v != "" {
		cfg.TeamID = v
	}

	return cfg, path, nil
}

// Save writes config to ~/.dylan-sign/config.yaml (YAML format).
func Save(cfg *Config) error {
	path := ConfigPath()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	data, err := yaml.Marshal(cfg)
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o600)
}

// ConfigPath returns the default config file path: ~/.dylan-sign/config.yaml
func ConfigPath() string {
	home, err := os.UserHomeDir()
	if err != nil {
		home = "."
	}
	return filepath.Join(home, ".dylan-sign", "config.yaml")
}

// Dir returns the base config directory ~/.dylan-sign
func Dir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		home = "."
	}
	return filepath.Join(home, ".dylan-sign")
}
