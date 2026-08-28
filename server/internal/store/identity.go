package store

import (
	"time"
)

// Now returns the creation timestamp the way BaseDataObject.prePersist does.
func Now() *LocalDateTime {
	return &LocalDateTime{Time: time.Now().UTC()}
}
