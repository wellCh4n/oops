package domain

import (
	"errors"
	"fmt"
	"regexp"
	"strings"
)

// BizError carries a user-facing failure message, the Go form of BizException.
// Handlers render it as Result.failure; anything else is an internal error.
type BizError struct{ Message string }

func (e *BizError) Error() string { return e.Message }

func Bizf(format string, args ...any) error {
	return &BizError{Message: fmt.Sprintf(format, args...)}
}

func IsBizError(err error) bool {
	var bizError *BizError
	return errors.As(err, &bizError)
}

// Naming rules, mirroring shared/util/ResourceNameChecker.
var (
	resourceNamePattern    = regexp.MustCompile(`^[a-z]([-a-z0-9]*[a-z0-9])?$`)
	environmentNamePattern = regexp.MustCompile(`^[A-Za-z]([-A-Za-z0-9]*[A-Za-z0-9])?$`)
	hostPattern            = regexp.MustCompile(`^([a-z0-9]([a-z0-9\-]{0,61}[a-z0-9])?)(\.[a-z0-9]([a-z0-9\-]{0,61}[a-z0-9])?)+$`)
)

// IsValidResourceName: lowercase RFC-1123 label, max 24 chars.
func IsValidResourceName(name string) bool {
	return name != "" && len(name) <= 24 && resourceNamePattern.MatchString(name)
}

// IsValidEnvironmentName allows mixed case, max 24 chars.
func IsValidEnvironmentName(name string) bool {
	return name != "" && len(name) <= 24 && environmentNamePattern.MatchString(name)
}

// NormalizeHost mirrors DomainPolicy.normalizeHost: trim and strip "*.".
func NormalizeHost(host string) string {
	return strings.TrimPrefix(strings.TrimSpace(host), "*.")
}

// ValidateHost mirrors DomainPolicy.validateHost.
func ValidateHost(host string) error {
	if host == "" {
		return Bizf("Domain host is required")
	}
	if host != strings.ToLower(host) {
		return Bizf("Domain must be lowercase: %s", host)
	}
	if !hostPattern.MatchString(host) {
		return Bizf("Invalid domain format: %s", host)
	}
	return nil
}

// HostCoveredBy reports whether fullHost is governed by the managed domain
// host (exact or suffix match) — the longest-suffix matching primitive shared
// by domain lookup and the rebinding guard.
func HostCoveredBy(fullHost, domainHost string) bool {
	return fullHost == domainHost || strings.HasSuffix(fullHost, "."+domainHost)
}
