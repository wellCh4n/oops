package store

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/wellch4n/oops/server/internal/domain"
)

// The JSON blob converters mirror the Jackson AttributeConverters:
//
//   - nil attribute  -> SQL NULL; empty slice -> literal "[]"
//   - NULL / blank column -> nil attribute
//   - unknown keys are ignored on read (legacy "environmentName",
//     plaintext "basicAuthPassword", ...)
//   - null fields are written as null unless the domain type says omitempty

// encodeSlice writes a JSON array; nil -> NULL, empty -> "[]".
func encodeSlice[T any](items []T) (sql.NullString, error) {
	if items == nil {
		return sql.NullString{}, nil
	}
	encoded, err := json.Marshal(items)
	if err != nil {
		return sql.NullString{}, err
	}
	return sql.NullString{String: string(encoded), Valid: true}, nil
}

// encodeObject writes a JSON object; nil -> NULL.
func encodeObject[T any](value *T) (sql.NullString, error) {
	if value == nil {
		return sql.NullString{}, nil
	}
	encoded, err := json.Marshal(value)
	if err != nil {
		return sql.NullString{}, err
	}
	return sql.NullString{String: string(encoded), Valid: true}, nil
}

func isBlankColumn(column sql.NullString) bool {
	return !column.Valid || strings.TrimSpace(column.String) == ""
}

// decodeSlice reads a JSON array; NULL/blank -> nil, "[]" -> empty slice.
func decodeSlice[T any](column sql.NullString) ([]T, error) {
	if isBlankColumn(column) {
		return nil, nil
	}
	items := []T{}
	if err := json.Unmarshal([]byte(column.String), &items); err != nil {
		return nil, fmt.Errorf("malformed JSON array: %w", err)
	}
	if items == nil {
		items = []T{}
	}
	return items, nil
}

// decodeObject reads a JSON object; NULL/blank -> nil.
func decodeObject[T any](column sql.NullString) (*T, error) {
	if isBlankColumn(column) {
		return nil, nil
	}
	if strings.TrimSpace(column.String) == "null" {
		return nil, nil
	}
	var value T
	if err := json.Unmarshal([]byte(column.String), &value); err != nil {
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
func decodeHealthCheck(column sql.NullString) (*domain.HealthCheck, error) {
	if isBlankColumn(column) || strings.TrimSpace(column.String) == "null" {
		return nil, nil
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal([]byte(column.String), &raw); err != nil {
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
func decodeServiceEnvironmentConfigs(column sql.NullString) ([]domain.ServiceEnvironmentConfig, error) {
	if isBlankColumn(column) {
		return nil, nil
	}
	var rawItems []json.RawMessage
	if err := json.Unmarshal([]byte(column.String), &rawItems); err != nil {
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
