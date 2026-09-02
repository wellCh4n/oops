package store

import (
	"encoding/json"
	"fmt"
	"strings"

	"github.com/wellch4n/oops/server/internal/domain"
)

// The JSON blob converters mirror the Jackson AttributeConverters:
//
//   - nil attribute  -> "" (an unset blob column); empty slice -> literal "[]"
//   - blank column -> nil attribute
//   - unknown keys are ignored on read (legacy "environmentName",
//     plaintext "basicAuthPassword", ...)
//   - null fields are written as null unless the domain type says omitempty

// encodeSlice writes a JSON array; nil -> "", empty -> "[]".
func encodeSlice[T any](items []T) (string, error) {
	if items == nil {
		return "", nil
	}
	encoded, err := json.Marshal(items)
	if err != nil {
		return "", err
	}
	return string(encoded), nil
}

// encodeObject writes a JSON object; nil -> "".
func encodeObject[T any](value *T) (string, error) {
	if value == nil {
		return "", nil
	}
	encoded, err := json.Marshal(value)
	if err != nil {
		return "", err
	}
	return string(encoded), nil
}

func isBlankColumn(column string) bool {
	return strings.TrimSpace(column) == ""
}

// decodeSlice reads a JSON array; blank -> nil, "[]" -> empty slice.
func decodeSlice[T any](column string) ([]T, error) {
	if isBlankColumn(column) {
		return nil, nil
	}
	items := []T{}
	if err := json.Unmarshal([]byte(column), &items); err != nil {
		return nil, fmt.Errorf("malformed JSON array: %w", err)
	}
	if items == nil {
		items = []T{}
	}
	return items, nil
}

// decodeObject reads a JSON object; blank -> nil.
func decodeObject[T any](column string) (*T, error) {
	if isBlankColumn(column) {
		return nil, nil
	}
	if strings.TrimSpace(column) == "null" {
		return nil, nil
	}
	var value T
	if err := json.Unmarshal([]byte(column), &value); err != nil {
		return nil, fmt.Errorf("malformed JSON object: %w", err)
	}
	return &value, nil
}

// ---------------------------------------------------------------------------
// Blobs whose Java classes carry field initialisers. Jackson keeps the
// initialiser for a *missing* key but honours an explicit null, so these are
// decoded into a pre-filled value.

// decodeHealthCheck: a missing liveness/readiness key yields the default
// probe; inside a probe a missing field keeps the Java default.
func decodeHealthCheck(column string) (*domain.HealthCheck, error) {
	if isBlankColumn(column) || strings.TrimSpace(column) == "null" {
		return nil, nil
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal([]byte(column), &raw); err != nil {
		return nil, fmt.Errorf("malformed health_check: %w", err)
	}
	liveness, err := decodeProbe(raw, "liveness")
	if err != nil {
		return nil, err
	}
	readiness, err := decodeProbe(raw, "readiness")
	if err != nil {
		return nil, err
	}
	return &domain.HealthCheck{Liveness: liveness, Readiness: readiness}, nil
}

func decodeProbe(raw map[string]json.RawMessage, key string) (*domain.Probe, error) {
	probe := domain.DefaultProbe()
	encoded, present := raw[key]
	if !present {
		return &probe, nil
	}
	if strings.TrimSpace(string(encoded)) == "null" {
		return nil, nil
	}
	if err := json.Unmarshal(encoded, &probe); err != nil {
		return nil, fmt.Errorf("malformed health_check.%s: %w", key, err)
	}
	return &probe, nil
}

// decodeServiceEnvironmentConfigs: `https` defaults to true when the key is
// missing (Java initialiser), stays null when written as null.
func decodeServiceEnvironmentConfigs(column string) ([]domain.ServiceEnvironmentConfig, error) {
	if isBlankColumn(column) {
		return nil, nil
	}
	var rawItems []json.RawMessage
	if err := json.Unmarshal([]byte(column), &rawItems); err != nil {
		return nil, fmt.Errorf("malformed service environment_configs: %w", err)
	}
	items := make([]domain.ServiceEnvironmentConfig, 0, len(rawItems))
	for _, rawItem := range rawItems {
		item := domain.ServiceEnvironmentConfig{HTTPS: domain.Ptr(true)}
		if err := json.Unmarshal(rawItem, &item); err != nil {
			return nil, fmt.Errorf("malformed service environment_configs item: %w", err)
		}
		items = append(items, item)
	}
	return items, nil
}
