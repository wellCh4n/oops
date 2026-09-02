package domain

import (
	"sort"
	"strings"
)

func ptr[T any](v T) *T { return &v }

// Ptr returns a pointer to v (exported helper for other packages).
func Ptr[T any](v T) *T { return &v }

func isBlankPtr(s *string) bool { return s == nil || strings.TrimSpace(*s) == "" }

// IsBlank reports whether s is nil or whitespace only.
func IsBlank(s *string) bool { return isBlankPtr(s) }

// Deref returns *s or "".
func Deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

// TrimToNil returns nil for blank strings, else the trimmed value.
func TrimToNil(s *string) *string {
	if isBlankPtr(s) {
		return nil
	}
	t := strings.TrimSpace(*s)
	return &t
}

// StringOrNil converts "" to nil.
func StringOrNil(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func sortStrings(s []string) { sort.Strings(s) }

// DerefBool reads an optional bool, treating nil as false.
func DerefBool(value *bool) bool { return value != nil && *value }
