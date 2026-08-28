package store

import (
	"database/sql/driver"
	"fmt"
	"time"
)

// LocalDateTime serializes like Jackson renders a Java LocalDateTime:
// "2026-06-24T17:44:28.724532" — local wall-clock time, no zone suffix.
// Values are stored in MySQL as UTC (the JDBC side connects with
// serverTimezone=UTC), so we convert to the process-local zone on output.
type LocalDateTime struct {
	time.Time
}

func (t LocalDateTime) MarshalJSON() ([]byte, error) {
	if t.IsZero() {
		return []byte("null"), nil
	}
	return []byte(`"` + t.In(time.Local).Format("2006-01-02T15:04:05.999999") + `"`), nil
}

func (t *LocalDateTime) Scan(value any) error {
	switch typed := value.(type) {
	case nil:
		t.Time = time.Time{}
	case time.Time:
		t.Time = typed
	default:
		return fmt.Errorf("cannot scan %T into LocalDateTime", value)
	}
	return nil
}

func (t LocalDateTime) Value() (driver.Value, error) {
	if t.IsZero() {
		return nil, nil
	}
	return t.Time, nil
}

// Now returns the creation timestamp the way BaseDataObject.prePersist does.
func Now() *LocalDateTime {
	return &LocalDateTime{Time: time.Now().UTC()}
}
