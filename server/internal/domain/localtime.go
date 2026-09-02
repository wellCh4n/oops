package domain

import (
	"database/sql/driver"
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

// LocalDateTime is a naive wall-clock timestamp: no zone is attached, none is
// ever applied. It mirrors Java's LocalDateTime, which is what every
// datetime(6) column in the schema holds. It is written to and read from MySQL
// verbatim, and rendered as "2006-01-02T15:04:05[.fraction]" with the fraction
// trimmed in groups of three digits, exactly as Jackson does.
//
// The process runs in the zone the data was written in (TZ); the value is
// never converted with .UTC() or .In().
type LocalDateTime struct {
	time.Time
	Valid bool
}

// Now returns the current wall clock in the process-local zone.
func Now() LocalDateTime { return LocalDateTime{Time: time.Now(), Valid: true} }

// LocalTimeOf wraps a time.Time (assumed to already be in the local zone).
func LocalTimeOf(t time.Time) LocalDateTime { return LocalDateTime{Time: t, Valid: true} }

// NullLocalTime is the zero (SQL NULL / JSON null) value.
var NullLocalTime = LocalDateTime{}

const localLayout = "2006-01-02T15:04:05"

// String renders the Java LocalDateTime.toString() form.
func (t LocalDateTime) String() string {
	if !t.Valid {
		return ""
	}
	base := t.Time.Format(localLayout)
	nanos := t.Time.Nanosecond()
	if nanos == 0 {
		return base
	}
	fraction := fmt.Sprintf("%09d", nanos)
	// Java prints 3, 6 or 9 digits: trim trailing zero groups of three.
	for strings.HasSuffix(fraction, "000") && len(fraction) > 3 {
		fraction = fraction[:len(fraction)-3]
	}
	return base + "." + fraction
}

// FormatPattern renders with a Go layout (e.g. "2006-01-02 15:04:05").
func (t LocalDateTime) FormatPattern(layout string) string {
	if !t.Valid {
		return ""
	}
	return t.Time.Format(layout)
}

func (t LocalDateTime) MarshalJSON() ([]byte, error) {
	if !t.Valid {
		return []byte("null"), nil
	}
	return json.Marshal(t.String())
}

func (t *LocalDateTime) UnmarshalJSON(data []byte) error {
	var raw *string
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}
	if raw == nil || *raw == "" {
		*t = NullLocalTime
		return nil
	}
	parsed, err := ParseLocalDateTime(*raw)
	if err != nil {
		return err
	}
	*t = parsed
	return nil
}

// ParseLocalDateTime accepts "2006-01-02T15:04:05" with an optional fraction,
// and also the MySQL form with a space separator.
func ParseLocalDateTime(value string) (LocalDateTime, error) {
	value = strings.TrimSpace(value)
	value = strings.Replace(value, " ", "T", 1)
	for _, layout := range []string{"2006-01-02T15:04:05.999999999", localLayout, "2006-01-02T15:04"} {
		if parsed, err := time.ParseInLocation(layout, value, time.Local); err == nil {
			return LocalDateTime{Time: parsed, Valid: true}, nil
		}
	}
	return NullLocalTime, fmt.Errorf("invalid local datetime: %q", value)
}

// Scan implements sql.Scanner. The driver is opened with parseTime=true and
// loc=Local so a datetime(6) column arrives as a local time.Time.
func (t *LocalDateTime) Scan(src any) error {
	switch v := src.(type) {
	case nil:
		*t = NullLocalTime
	case time.Time:
		*t = LocalDateTime{Time: v, Valid: true}
	case []byte:
		parsed, err := ParseLocalDateTime(string(v))
		if err != nil {
			return err
		}
		*t = parsed
	case string:
		parsed, err := ParseLocalDateTime(v)
		if err != nil {
			return err
		}
		*t = parsed
	default:
		return fmt.Errorf("cannot scan %T into LocalDateTime", src)
	}
	return nil
}

// Value implements driver.Valuer: the wall clock is written as a literal
// string so no driver-side zone conversion can apply.
func (t LocalDateTime) Value() (driver.Value, error) {
	if !t.Valid {
		return nil, nil
	}
	return t.Time.Format("2006-01-02 15:04:05.000000"), nil
}

// IsZero reports whether the value is NULL.
func (t LocalDateTime) IsZero() bool { return !t.Valid }
