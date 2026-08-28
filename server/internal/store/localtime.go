package store

import (
	"database/sql/driver"
	"fmt"
	"time"
)

// LocalDateTime serializes like Jackson renders a Java LocalDateTime:
// "2026-06-24T17:44:28.724532" — a naive wall clock with no zone suffix.
//
// The datetime(6) columns hold exactly that: the wall clock of the process
// that wrote the row, which on the JVM side was LocalDateTime.now() in the
// container zone. So the value is read, written and rendered verbatim — the
// connection is pinned to loc=Local (see config.MySQLDSN) so the driver never
// shifts it either. Converting on output would push every Java-era row
// forward by the local offset.
type LocalDateTime struct {
	time.Time
}

func (t LocalDateTime) MarshalJSON() ([]byte, error) {
	if t.IsZero() {
		return []byte("null"), nil
	}
	return []byte(`"` + t.Format("2006-01-02T15:04:05.999999") + `"`), nil
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

// Now returns the creation timestamp the way BaseDataObject.prePersist does:
// LocalDateTime.now(), the local wall clock.
func Now() *LocalDateTime {
	return &LocalDateTime{Time: time.Now()}
}
